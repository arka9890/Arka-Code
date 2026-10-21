package com.arkacode.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Jembatan ke aplikasi Termux yang terpasang di perangkat.
 *
 * Termux memberi kita lingkungan Linux sungguhan (glibc, `pkg`, tool
 * pengembangan lengkap) tanpa perlu rootfs di dalam app. Jadi ada dua runtime:
 *
 *  - [RuntimeMode.PROOT] — rootfs sendiri (Alpine/dsb) via proot. Default.
 *  - [RuntimeMode.TERMUX] — command dijalankan langsung di Termux.
 *
 * How-to Termux menjalankan command dari app lain:
 *  - Intent `com.termux.RUN_COMMAND` ke service `com.termux.app.RunCommandService`.
 *  - App kita harus memegang permission `com.termux.permission.RUN_COMMAND`.
 *    Permission itu diberikan oleh Termux lewat daftar "allow app" (bukan
 *    dialog sistem biasa), jadi [requestPermissionIntent] membuka layar itu.
 *  - Output command TIDAK bisa diambil langsung dari intent — Termux
 *    menulisnya ke sesinya sendiri. Karena itu [run] meminta output ditulis
 *    ke berkas di folder Download, lalu app membacanya kembali lewat URI SAF
 *    miliknya sendiri.
 */
class TermuxBridge(private val context: Context) {

    companion object {
        const val PACKAGE = "com.termux"
        const val SERVICE = "com.termux.app.RunCommandService"
        const val ACTION = "com.termux.RUN_COMMAND"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"

        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING"

        /** Prefix action yang Termux pakai untuk mendaftarkan app ke allowlist. */
        const val ACTION_ADD_ALLOWED_APP = "com.termux.action.ADD_ALLOWED_APP"
        const val EXTRA_ALLOWED_APP_PACKAGE = "com.termux.extra.ALLOWED_APP_PACKAGE_NAME"

        /** Path shell Termux (prefix resmi Termux). */
        const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/bash"
    }

    /** Status ringkas Termux untuk ditampilkan di UI. */
    data class Status(
        val installed: Boolean,
        val permissionGranted: Boolean,
        val message: String,
    ) {
        val ready: Boolean get() = installed && permissionGranted
    }

    // --------------------------------------------------------------- deteksi

    /** Termux terpasang di perangkat? */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Apakah app kita memegang izin `com.termux.permission.RUN_COMMAND`?
     *
     * Termux memberi izin ini lewat daftar "allow app"-nya sendiri, jadi
     * `checkSelfPermission` adalah cara yang benar untuk mengeceknya.
     */
    fun hasPermission(): Boolean = runCatching {
        context.checkPermission(PERMISSION, context.packageName, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): Status {
        val installed = isInstalled()
        return when {
            !installed -> Status(
                false, false,
                "Termux tidak terpasang. Pasang Termux (F-Droid/Play) lalu ulangi.",
            )
            !hasPermission() -> Status(
                true, false,
                "Termux terpasang, tapi app ini belum diizinkan menjalankan script. " +
                    "Tekan \" Izinkan di Termux\" lalu setujui di aplikasi Termux.",
            )
            else -> Status(true, true, "Termux siap dipakai sebagai runtime command.")
        }
    }

    /**
     * Buka layar Termux untuk mendaftarkan app ini ke daftar "allow app".
     *
     * Termux tidak punya dialog izin standar — jadi kita arahkan user ke
     * layarnya, tempat centang "Allow execution of scripts" dikendalikan.
     */
    fun requestPermissionIntent(): Intent? = runCatching {
        Intent(ACTION_ADD_ALLOWED_APP)
            .putExtra(EXTRA_ALLOWED_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    /** Intent untuk membuka aplikasi Termux. */
    fun openTermuxIntent(): Intent? = runCatching {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    // ------------------------------------------------------------- eksekusi

    /**
     * Jalankan [command] di Termux dan kembalikan hasilnya.
     *
     * Output ditulis Termux ke [outputFile] (harus berada di folder yang bisa
     * ditulis Termux — yaitu shared storage), lalu dibaca app lewat
     * [outputUri]. [outputUri] dibuat SAF sehingga app tetap punya izin baca
     *nya sendiri.
     */
    suspend fun run(
        command: String,
        outputFile: File,
        outputUri: Uri,
        workDir: String? = null,
        timeoutMs: Long = ExecRunner.PROOT_DEFAULT_TIMEOUT_MS,
    ): ExecResult {
        if (!isInstalled()) throw IllegalStateException("Termux tidak terpasang di perangkat.")
        if (!hasPermission()) {
            throw IllegalStateException("Termux belum mengizinkan app ini menjalankan script.")
        }

        // 1. Bersihkan berkas output lama supaya tidak salah baca sisa.
        runCatching { outputFile.delete() }

        // 2. Susun perintah: jalankan command, tulis stdout+stderr ke berkas,
        //    lalu tulis exit code ke berkas terpisah.
        val rcFile = File(outputFile.parentFile, outputFile.name + ".rc")
        runCatching { rcFile.delete() }

        val quoted = command.replace("'", "'\\''")
        val shellScript = "{ $quoted ; } > '${outputFile.absolutePath}' 2>&1; echo $? > '${rcFile.absolutePath}'"

        val args = arrayOf("-lc", shellScript)
        val intent = Intent(ACTION).apply {
            component = android.content.ComponentName(PACKAGE, SERVICE)
            putExtra(EXTRA_PATH, TERMUX_SHELL)
            putExtra(EXTRA_ARGUMENTS, args)
            workDir?.let { putExtra(EXTRA_WORKDIR, it) }
            putExtra(EXTRA_BACKGROUND, false)
            putExtra(EXTRA_PENDING, true)
        }

        val started = runCatching {
            context.startService(intent)
            true
        }.getOrElse { e ->
            throw IllegalStateException("Gagal memanggil service Termux: ${e.message}")
        }
        if (!started) throw IllegalStateException("Service Termux menolak perintah.")

        // 3. Tunggu berkas rc muncul (Termux sudah selesai) atau timeout.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rcFile.isFile && rcFile.length() > 0) break
            kotlinx.coroutines.delay(300)
        }

        val timedOut = !rcFile.isFile
        val exitCode = if (timedOut) 124 else runCatching {
            rcFile.readText().trim().toIntOrNull() ?: 0
        }.getOrDefault(0)

        val output = readViaSaf(outputUri)
        return ExecResult(
            stdout = output,
            stderr = "",
            exitCode = exitCode,
            timedOut = timedOut,
            backend = "termux",
        )
    }

    /**
     * Baca berkas lewat URI SAF milik app sendiri.
     *
     * Dipakai karena path absolut di shared storage tidak selalu bisa dibuka
     * langsung (scoped storage Android 11+), sedangkan URI hasil SAF
     * selalu punya izin untuk app pembuatnya.
     */
    private fun readViaSaf(uri: Uri): String = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: ""
    }.getOrDefault("")

    /**
     * Jalankan perintah singkat di Termux dan kembalikan output-nya.
     *
     * Dipakai untuk memeriksa apakah `opencode` sudah terpasang di Termux.
     * Output ditulis ke berkas di folder Download lalu dibaca balik.
     */
    suspend fun probe(command: String, timeoutMs: Long = 15_000): String {
        val sink = createOutputSink("arkacode-probe") ?: return ""
        return try {
            val result = run(command, sink.file, sink.uri, timeoutMs = timeoutMs)
            result.stdout.trim()
        } finally {
            runCatching { context.contentResolver.delete(sink.uri, null, null) }
            runCatching { sink.file.delete() }
        }
    }

    /**
     * Jalankan perintah berlatar (server) di Termux.
     *
     * Tidak menunggu selesai — perintah dipostic ke Termux dan hasilnya
     * ditulis ke log yang ditentukan oleh pemanggil.
     */
    fun startBackground(command: String, workDir: String? = null): Boolean = runCatching {
        val intent = Intent(ACTION).apply {
            component = android.content.ComponentName(PACKAGE, SERVICE)
            putExtra(EXTRA_PATH, TERMUX_SHELL)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            workDir?.let { putExtra(EXTRA_WORKDIR, it) }
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING, false)
        }
        context.startService(intent)
        true
    }.getOrDefault(false)

    /**
     * Termux butuh lokasi yang bisa ditulis dua pihak.
     *
     * Folder Download publik adalah satu-satunya tempat yang praktis: Termux
     * bisa menulis di sana setelah storage permission-nya diberikan, dan app
     * bisa membacanya lewat URI SAF. [folder] biasanya
     * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`.
     */
    fun defaultOutputFolder(): File? = runCatching {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !dir.isDirectory) null else dir
    }.getOrNull()

    /**
     * buat "corong" output: satu berkas yang bisa ditulis Termux di path
     * filesystem, DAN bisa dibaca app lewat URI MediaStore milik app sendiri.
     *
     * Kenapa MediaStore dan bukan file biasa di app dir:
     *  - app-private dir (`/data/data/...`) tidak bisa diakses Termux (UID beda)
     *  - `Android/data/<app>` butuh MANAGE_EXTERNAL_STORAGE dari Termux
     *  - MediaStore Downloads: app boleh insert tanpa izin khusus, dan Termux
     *    (dengan storage permission) bisa menulis ke path yang sama.
     */
    fun createOutputSink(tag: String = "arkacode"): OutputSink? = runCatching {
        val downloads = android.provider.MediaStore.Downloads.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val name = "$tag-${System.currentTimeMillis()}.txt"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
        }
        val uri = context.contentResolver.insert(downloads, values)
            ?: return null
        // Path filesystem yang setara — inilah yang ditulis Termux.
        val file = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        ), name)
        OutputSink(file = file, uri = uri)
    }.getOrNull()

    /** Corong output: berkas di filesystem + URI MediaStore untuk dibaca app. */
    data class OutputSink(val file: File, val uri: Uri)
}
