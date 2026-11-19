package com.arkacode.app.core

import com.arkacode.app.ui.TerminalState
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Pemilik tunggal sesi terminal proses.
 *
 * Sesi hidup selama proses aplikasi: tidak ada foreground service, jadi tab
 * terminal ikut mati saat app dibunuh sistem (lihat non-goal design). Host
 * sengaja singleton, bukan `remember` per layar — WebView dibuat ulang tiap
 * layar dipasang, sedangkan pty dan buffer replay harus bertahan.
 *
 * Tidak ada `Context` di sini: semua path datang dari pemanggil.
 */
object TerminalHost {

    private const val ROWS = 24
    private const val COLS = 80

    /**
     * Batas kasar kedua di depan jatah 8 MB milik [TerminalSession].
     *
     * Jatah byte menahan pty saat UI tertinggal; kanal ini meredam ledakan
     * output di antara dua pemasangan layar tanpa membuang byte (pengirim
     * diblokir, bukan di-drop).
     */
    private const val OUTPUT_CHUNKS = 64

    /**
     * Satu potongan output. [gen] menandai sesi pemilik, [end] offset akhir
     * kumulatif potongan itu di buffer replay.
     */
    private class Chunk(val bytes: ByteArray, val gen: Int, val end: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Starting)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val channel = Channel<Chunk>(capacity = OUTPUT_CHUNKS)

    /**
     * Offset replay yang sudah diputar ulang untuk layar yang sedang terpasang.
     * Potongan dengan `end` di bawah/ sama dengan nilai ini sudah ikut di
     * replay, jadi dibuang agar layar tidak mendapat byte ganda setelah
     * rotasi / tutup-buka tab.
     */
    private val replayed = AtomicLong(0L)

    /**
     * Satu koleksi selamanya: kolektor WebView tidak boleh ikut restart.
     * Saringan generasi + offset bekerja di sisi kolektor, jadi potongan milik
     * sesi yang sudah pensiun tidak pernah sampai ke layar baru.
     */
    val output: Flow<ByteArray> = channel.receiveAsFlow()
        .filter { it.gen == generation && it.end > replayed.get() }
        .map { it.bytes }

    private val lock = Any()

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    private var activeRootfs: String? = null

    /** Naik tiap [open]/[stop]; exit dan output sesi lama tidak boleh menimpa sesi baru. */
    @Volatile
    private var generation = 0

    /**
     * Buka shell untuk [distro]. `true` bila sesi sudah/berhasil jalan,
     * `false` bila distro belum terpasang atau runtime bukan proot.
     *
     * Resolusi argv menyentuh filesystem (symlink binary proot, isi rootfs),
     * jadi dikerjakan SEBELUM mengambil kunci: pemanggil dari main thread
     * tidak boleh ikut menunggu I/O.
     */
    fun open(distro: DistroManager, workspaceDir: File?, containersDir: File): Boolean {
        val rootfs = distro.rootfsDir.absolutePath
        val argv = TerminalCommand.forDistro(distro, workspaceDir, containersDir)
        val cwd = workspaceDir?.absolutePath ?: distro.rootfsDir.absolutePath
        return synchronized(lock) {
            val current = session
            if (current != null && activeRootfs == rootfs && _state.value !is TerminalState.Exited) {
                return true
            }
            if (argv == null) return false
            if (current != null) stopLocked()
            // cwd proses induk (host), bukan cwd guest: proot sendiri yang
            // memindahkan shell ke `-w <guestHomePath>` di argv.
            generation++
            val gen = generation
            // Offset sesi baru mulai dari nol lagi, jadi penanda replay lama
            // harus dikosongkan; kalau tidak, potongan baru tersaring semua.
            replayed.set(0L)
            _state.value = TerminalState.Starting
            val created = TerminalSession(
                scope = scope,
                onOutput = { chunk, end -> emit(gen, chunk, end) },
                onExit = { code ->
                    synchronized(lock) {
                        if (gen == generation) _state.value = TerminalState.Exited(code)
                    }
                },
            )
            // start() memblokir singkat (fork+exec), jadi jalankan di IO.
            scope.launch {
                // Gagal di boundary native (mis. `libterminalpty.so` tak bisa
                // dimuat, atau `adoptFd` menolak) harus dilaporkan sebagai
                // sesi mati, bukan mematikan seluruh app lewat uncaught handler.
                val started = runCatching {
                    created.start(argv, hostEnv(distro, workspaceDir, cwd), cwd, ROWS, COLS)
                }.getOrElse {
                    Log.w("TerminalHost", "start gagal: ${it.message}")
                    false
                }
                synchronized(lock) {
                    // Bisa jadi sudah di-stop/di-ganti saat start() berjalan.
                    // Sesi itu harus selalu ditutup, walau start() sukses:
                    // kalau tidak, pty + shell proot hidup tanpa pemilik.
                    if (gen != generation) {
                        created.close()
                        return@launch
                    }
                    if (!started) {
                        created.close()
                        session = null
                        activeRootfs = null
                        _state.value = TerminalState.Exited(-1)
                        return@launch
                    }
                    session = created
                    activeRootfs = rootfs
                    _state.value = TerminalState.Running
                }
            }
            true
        }
    }

    /**
     * Layar terminal dipasang kembali (mutasi komposisi / rotasi).
     *
     * Mengembalikan isi layar terakhir dan menandai offset akhirnya sebagai
     * sudah diputar ulang. Kanal TIDAK dikuras di sini: potongan yang isinya
     * sudah ikut snapshot tersaring sendiri oleh [output] lewat penanda offset,
     * sedangkan potongan yang datang sesudah snapshot tetap di kanal dan harus
     * tetap sampai ke layar. Menguras kanal akan membuang byte yang belum ada
     * di snapshot (mis. saat rotasi di tengah `yes`).
     */
    fun reattach(): ByteArray = synchronized(lock) {
        val live = session ?: return ByteArray(0)
        val (snapshot, total) = live.reattach()
        replayed.set(total)
        snapshot
    }

    /**
     * Ada sesi, atau `open()` sudah pernah dipanggil di proses ini. Dipakai
     * layar terminal untuk membedakan "rotasi saat pty sedang disiapkan"
     * (harus tetap terbuka) dari "proses baru setelah app dibunuh" (pty sudah
     * tidak ada, layar tidak ada gunanya).
     */
    fun wasOpened(): Boolean = synchronized(lock) { session != null || generation > 0 }

    fun write(bytes: ByteArray) {
        session?.write(bytes)
    }

    fun resize(rows: Int, cols: Int) {
        session?.resize(rows, cols)
    }

    fun ack(bytes: Int) {
        session?.ack(bytes)
    }

    fun restart(distro: DistroManager, workspaceDir: File?, containersDir: File): Boolean {
        stop()
        return open(distro, workspaceDir, containersDir)
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** True selama sesi hidup untuk rootfs ini (path absolut sama). */
    fun aliveFor(rootfs: String): Boolean = synchronized(lock) {
        session != null && _state.value !is TerminalState.Exited && rootfs == activeRootfs
    }

    private fun stopLocked() {
        val old = session
        session = null
        activeRootfs = null
        // Naikkan generasi dulu: exit yang menyusul dari sesi lama diabaikan.
        generation++
        replayed.set(0L)
        _state.value = TerminalState.Starting
        // Pembaca bisa sedang menunggu ruang di kanal; kosongkan supaya
        // close() tidak menggantung di runBlocking.
        drainChannel()
        old?.close()
    }

    private fun drainChannel() {
        while (channel.tryReceive().isSuccess) {
            // potongan milik sesi lama / sudah diputar ulang
        }
    }

    /**
     * Lingkungan host untuk proot. `execve` di [TerminalPty] memakai env ini
     * apa adanya; environment guest diatur `env -i` di dalam argv.
     */
    private fun hostEnv(distro: DistroManager, workspaceDir: File?, cwd: String): List<String> =
        buildList {
            distro.prootProcessEnv().forEach { (k, v) -> add("$k=$v") }
            add("PATH=/system/bin:/system/xbin:/vendor/bin")
            add("HOME=${workspaceDir?.absolutePath ?: cwd}")
            add("TMPDIR=${workspaceDir?.absolutePath ?: cwd}")
        }

    /**
     * Kolektor layar bisa belum siap saat byte pertama datang; blokir pembaca
     * pty alih-alih membuang byte. Aman: dipanggil dari thread pembaca
     * [TerminalSession] (Dispatchers.IO), bukan main thread. Byte dari sesi
     * yang sudah diganti disaring di [output] lewat penanda generasi.
     */
    private fun emit(gen: Int, chunk: ByteArray, end: Long) {
        if (gen != generation) return
        runBlocking { channel.send(Chunk(chunk, gen, end)) }
    }
}