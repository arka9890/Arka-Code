package com.arkacode.app.core

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Satu sesi pty di tab Terminal.
 *
 * Pemilik sesi adalah singleton proses (bukan Activity), jadi [scope] harus
 * hidup sepanjang proses. Pembacaan fd berjalan di [Dispatchers.IO]; setiap
 * potongan byte mentah dikirim lewat [onOutput] tanpa decode UTF-8 karena
 * emulator terminal di WebView yang menafsirkan escape sequence.
 *
 * Backpressure berupa jatah [BUDGET_BYTES]: pembaca mengambil jatah sebesar
 * potongan sebelum menyerahkannya ke UI, dan [ack] mengembalikannya. Bila UI
 * tertinggal, pembaca berhenti membaca fd sehingga proot ikut terblokir —
 * antrean tetap di kernel, bukan menumpuk di memori aplikasi.
 */
class TerminalSession(
    private val scope: CoroutineScope,
    /**
     * Dipanggil untuk setiap potongan byte. Argumen kedua adalah offset akhir
     * kumulatif potongan itu di buffer replay: pemilik sesi memakainya untuk
     * membedakan byte yang sudah diputar ulang dari byte yang masih baru.
     */
    private val onOutput: (ByteArray, Long) -> Unit,
    private val onExit: (Int) -> Unit,
) {

    companion object {
        /** Jatah byte yang belum di-ack (8 MB). */
        const val BUDGET_BYTES = 8 * 1024 * 1024
        private const val READ_BYTES = 8 * 1024
        private const val GRACE_MS = 300L
    }

    private val replayBuffer = TerminalReplayBuffer()
    private val lifecycle = Any()
    private val budget = Semaphore(BUDGET_BYTES)
    private val unacked = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val fdsClosed = AtomicBoolean(false)
    private val exitNotified = AtomicBoolean(false)

    @Volatile
    private var handle: TerminalPty.Handle? = null

    @Volatile
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var output: FileOutputStream? = null

    /** False bila pty/proses gagal dibuat, atau sesi sudah berjalan. */
    fun start(
        argv: List<String>,
        env: List<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Boolean = synchronized(lifecycle) {
        if (closed.get() || handle != null) return false
        val spawned = TerminalPty.spawn(argv.toTypedArray(), env.toTypedArray(), cwd, rows, cols)
            ?: return false
        // Java tidak punya konstruktor stream dari int fd; ParcelFileDescriptor
        // adalah pembungkus resmi yang memegang fd mentah milik pty.
        val descriptor = ParcelFileDescriptor.adoptFd(spawned.fd)
        val reader = FileInputStream(descriptor.fileDescriptor)
        val writer = FileOutputStream(descriptor.fileDescriptor)
        handle = spawned
        pfd = descriptor
        output = writer
        scope.launch(Dispatchers.IO) { readLoop(spawned, reader) }
        return true
    }

    fun write(bytes: ByteArray) {
        val out = output ?: return
        try {
            synchronized(this) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: IOException) {
            // fd sudah mati: tutup sesi, pembaca yang melaporkan exit code.
            close()
        }
    }

    fun resize(rows: Int, cols: Int) {
        val h = handle ?: return
        TerminalPty.resize(h.fd, rows, cols)
    }

    /** Lepas jatah backpressure untuk byte yang sudah ditampilkan WebView. */
    fun ack(bytes: Int) {
        if (bytes <= 0) return
        while (true) {
            val current = unacked.get()
            if (current <= 0) return
            val released = minOf(bytes, current)
            if (unacked.compareAndSet(current, current - released)) {
                // Jangan pernah melewati jatah awal, walau close() sempat
                // melepas semua permit di tengah jalan.
                val room = BUDGET_BYTES - budget.availablePermits()
                if (room > 0) budget.release(minOf(released, room))
                return
            }
        }
    }

    /** Isi layar terakhir untuk WebView baru; buffer tidak dikosongkan. */
    fun replay(): ByteArray = replayBuffer.snapshot()

    /**
     * WebView baru dipasang: kembalikan isi layar terakhir beserta offset
     * akhirnya, dan lepas jatah backpressure yang masih dipegang byte milik
     * WebView lama.
     *
     * Tanpa pelepasan jatah, byte yang sudah tergambar di WebView yang lalu
     * musnah tidak pernah di-ack, jadi pembaca pty berhenti permanen di jatah
     * 8 MB dan shell membeku sampai sesi di-restart. Snapshot diambil lebih dulu:
     * byte yang tercatat sesudah snapshot offset-nya lebih besar dari penanda,
     * jadi tidak ikut tersaring dan jatahnya tidak hilang.
     */
    fun reattach(): Pair<ByteArray, Long> {
        val snapshot = replayBuffer.snapshotWithTotal()
        releaseAllBudget()
        return snapshot
    }

    /** SIGHUP, jeda singkat, lalu SIGKILL; fd ditutup sekali saja. */
    fun close() {
        val h = synchronized(lifecycle) {
            if (!closed.compareAndSet(false, true)) return
            handle
        } ?: return
        // Pembaca sudah selesai dan pid sudah di-reap: jangan kirim sinyal ke
        // pid yang mungkin sudah dipakai proses lain.
        if (exitNotified.get()) {
            closeStreams()
            return
        }
        // Pembaca yang sedang menunggu jatah harus bisa lanjut dan berhenti.
        releaseAllBudget()
        Thread({
            try {
                // Cek ulang tepat sebelum signal: waitFor bisa sudah me-reap pid
                // di sela-sela, dan kill(-pid) ke pgid yang sudah didaur ulang
                // akan mengenai proses group lain.
                if (!exitNotified.get()) TerminalPty.signal(h.pid, OsConstants.SIGHUP)
            } catch (ignored: RuntimeException) {
                // Proses mungkin sudah mati; SIGKILL di bawah tetap dicoba.
            }
            try {
                Thread.sleep(GRACE_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                if (!exitNotified.get()) TerminalPty.signal(h.pid, OsConstants.SIGKILL)
            } catch (ignored: RuntimeException) {
                // Sama: fd tetap ditutup di bawah.
            } finally {
                closeStreams()
            }
        }, "terminal-close").apply { isDaemon = true }.start()
    }

    private fun readLoop(h: TerminalPty.Handle, reader: FileInputStream) {
        try {
            val buffer = ByteArray(READ_BYTES)
            while (!closed.get()) {
                val n = reader.read(buffer)
                if (n <= 0) break
                // Ambil jatah sebelum byte diserahkan; bila habis, fd berhenti dibaca.
                budget.acquire(n)
                if (closed.get()) {
                    budget.release(n)
                    break
                }
                unacked.addAndGet(n)
                val chunk = buffer.copyOf(n)
                val end = replayBuffer.record(chunk)
                try {
                    onOutput(chunk, end)
                } catch (ignored: RuntimeException) {
                    // UI pemilik sesi sudah mati (mis. WebView dilepas). Berhenti
                    // membaca; finally menutup pty supaya proot tidak menggantung
                    // dan onExit tetap dilaporkan.
                    break
                }
            }
        } catch (e: IOException) {
            // pty ditutup (proot berhenti); exit code menyusul lewat waitFor.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // Tutup fd lebih dulu: sisi slave melihat EIO sehingga proot tidak
            // terblokir menulis ke pty yang sudah tidak dibaca, dan waitFor
            // tidak menggantung. waitFor memblokir — jangan pernah di main thread.
            closeStreams()
            var code = -1
            try {
                code = TerminalPty.waitFor(h.pid)
            } finally {
                closeStreams()
                if (exitNotified.compareAndSet(false, true)) onExit(code)
            }
        }
    }

    private fun releaseAllBudget() {
        unacked.set(0)
        val available = budget.availablePermits()
        if (available < BUDGET_BYTES) budget.release(BUDGET_BYTES - available)
    }

    private fun closeStreams() {
        if (!fdsClosed.compareAndSet(false, true)) return
        // Cukup tutup pembungkus fd; stream read/write memakai fd yang sama dan
        // ikut mati. Menutup keduanya berisiko close ganda pada nomor fd itu.
        try {
            pfd?.close()
        } catch (ignored: IOException) {
            // fd mungkin sudah ditutup sisi native.
        }
    }
}
