package com.arkacode.app.core

import com.arkacode.app.ui.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Starting)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val channel = Channel<ByteArray>(capacity = OUTPUT_CHUNKS)

    /** Satu instance selamanya: kolektor WebView tidak boleh ikut restart. */
    val output: Flow<ByteArray> = channel.receiveAsFlow()

    private val lock = Any()
    private var session: TerminalSession? = null
    private var activeRootfs: String? = null

    /** Naik tiap [open]/[stop]; exit dan output sesi lama tidak boleh menimpa sesi baru. */
    @Volatile
    private var generation = 0

    /**
     * Buka shell untuk [distro]. `true` bila sesi sudah/berhasil jalan,
     * `false` bila distro belum terpasang atau runtime bukan proot.
     */
    fun open(distro: DistroManager, workspaceDir: File?, containersDir: File): Boolean =
        synchronized(lock) {
            val rootfs = distro.rootfsDir.absolutePath
            val current = session
            if (current != null && activeRootfs == rootfs && _state.value !is TerminalState.Exited) {
                return true
            }
            if (current != null) stopLocked()
            val argv = TerminalCommand.forDistro(distro, workspaceDir, containersDir) ?: return false
            // cwd proses induk (host), bukan cwd guest: proot sendiri yang
            // memindahkan shell ke `-w <guestHomePath>` di argv.
            val cwd = workspaceDir?.absolutePath ?: distro.rootfsDir.absolutePath
            generation++
            val gen = generation
            _state.value = TerminalState.Starting
            val created = TerminalSession(
                scope = scope,
                onOutput = { chunk -> emit(gen, chunk) },
                onExit = { code ->
                    synchronized(lock) {
                        if (gen == generation) _state.value = TerminalState.Exited(code)
                    }
                },
            )
            // start() memblokir singkat (fork+exec), jadi jalankan di IO.
            scope.launch {
                val started = created.start(argv, hostEnv(distro, workspaceDir, cwd), cwd, ROWS, COLS)
                synchronized(lock) {
                    if (gen != generation) {
                        if (!started) created.close()
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

    fun replay(): ByteArray = session?.replay() ?: ByteArray(0)

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
        _state.value = TerminalState.Starting
        // Pembaca bisa sedang menunggu ruang di kanal; kosongkan supaya
        // close() tidak menggantung di runBlocking.
        while (channel.tryReceive().isSuccess) {
            // buang sisa potongan milik sesi lama
        }
        old?.close()
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
     * yang sudah diganti dibuang, bukan dikirim ke layar baru.
     */
    private fun emit(gen: Int, chunk: ByteArray) {
        if (gen != generation) return
        runBlocking { channel.send(chunk) }
    }
}
