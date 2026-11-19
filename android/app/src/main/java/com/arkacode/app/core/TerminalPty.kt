package com.arkacode.app.core

/**
 * PTY native untuk tab Terminal.
 *
 * Android tidak menyediakan API Java untuk membuat pty, jadi modul native
 * [terminalpty] (lihat `src/main/cpp/terminal_pty.c`) yang membuka pty pair,
 * fork, lalu exec argv proot dari [TerminalCommand].
 */
object TerminalPty {

    /** fd master pty (baca/tulis sesi) dan pid proses anak (proot). */
    class Handle(val fd: Int, val pid: Int)

    init {
        System.loadLibrary("terminalpty")
    }

    /** Null bila pty/proses gagal dibuat; semua fd yang sempat dibuka sudah ditutup. */
    fun spawn(
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Handle? {
        val out = IntArray(2)
        if (!nativeSpawn(argv, env, cwd, rows, cols, out)) return null
        return Handle(out[0], out[1])
    }

    /** Ukur ulang pty saat view terminal berubah ukuran. */
    fun resize(fd: Int, rows: Int, cols: Int) {
        nativeResize(fd, rows, cols)
    }

    /** Kirim signal POSIX (mis. `OsConstants.SIGHUP` saat tab ditutup). */
    fun signal(pid: Int, sig: Int) {
        nativeSignal(pid, sig)
    }

    /** Blokir sampai proses anak selesai; -1 bila pid tidak valid. */
    fun waitFor(pid: Int): Int = nativeWait(pid)

    private external fun nativeSpawn(
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        out: IntArray,
    ): Boolean

    private external fun nativeResize(fd: Int, rows: Int, cols: Int)

    private external fun nativeSignal(pid: Int, sig: Int)

    private external fun nativeWait(pid: Int): Int
}
