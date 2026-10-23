package com.arkacode.app.core

import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper murni untuk fitur tab Build: pindai proyek dari workspace,
 * tail log build (baca byte baru sejak offset — log bisa ratusan KB),
 * quote nama path untuk perintah sh, dan parsing status toolchain.
 */
object BuildSupport {

    /** Nama direktori di root yang merupakan proyek Gradle, urut alfabet. */
    fun findProjects(root: File): List<String> {
        val dirs = root.listFiles() ?: return emptyList()
        return dirs
            .filter { d ->
                d.isDirectory && (
                    File(d, "gradlew").isFile ||
                        File(d, "settings.gradle").isFile ||
                        File(d, "settings.gradle.kts").isFile
                    )
            }
            .map { it.name }
            .sorted()
    }

    /** Bungkus string dengan kutip tunggal untuk sh: `'` → `'\''`. */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Pembaca baris baru dari sebuah file log.
     * Offset tidak maju melewati baris tanpa `\n` (potongan dibaca
     * berikutnya); file yang menyusut (rotasi) mereset offset ke 0.
     * shortcut: perubahan isi dengan panjang sama tidak terdeteksi —
     * log fitur ini hanya ditambah, jadi cukup sampai ada kasus baru.
     */
    class LogTailer(private val file: File) {
        private var pos = 0L

        fun readNew(): List<String> {
            if (!file.isFile) return emptyList()
            val len = file.length()
            if (len < pos) pos = 0
            if (len <= pos) return emptyList()

            val bytes = ByteArray((len - pos).toInt())
            val n = FileInputStream(file).use { input ->
                var skipped = 0L
                while (skipped < pos) {
                    val step = input.skip(pos - skipped)
                    if (step <= 0) break
                    skipped += step
                }
                var off = 0
                while (off < bytes.size) {
                    val r = input.read(bytes, off, bytes.size - off)
                    if (r < 0) break
                    off += r
                }
                off
            }
            if (n == 0) return emptyList()

            // 0x0A tidak pernah muncul di tengah sekuens UTF-8 multibyte,
            // jadi pencarian byte aman untuk menemukan baris utuh.
            var lastNl = -1
            for (i in n - 1 downTo 0) if (bytes[i] == 0x0A.toByte()) { lastNl = i; break }
            if (lastNl < 0) return emptyList() // masih potongan — jangan konsumsi

            pos += lastNl + 1
            return String(bytes, 0, lastNl, Charsets.UTF_8).split('\n')
        }
    }

    data class ToolchainStatus(
        val java: Boolean,
        val sdk: Boolean,
        val aapt2: Boolean,
        val adb: Boolean,
    ) {
        val ready: Boolean get() = java && sdk && aapt2 && adb
    }

    private val FLAGS = mapOf(
        "JAVA_OK" to true,
        "SDK_OK" to true,
        "AAPT2_OK" to true,
        "ADB_OK" to true,
    )

    /** Parse keluaran PROBE_CMD (baris flag) menjadi status toolchain. */
    fun parseToolchain(output: String): ToolchainStatus {
        val present = output.lineSequence().map { it.trim() }.filter { it in FLAGS }.toSet()
        return ToolchainStatus(
            java = "JAVA_OK" in present,
            sdk = "SDK_OK" in present,
            aapt2 = "AAPT2_OK" in present,
            adb = "ADB_OK" in present,
        )
    }

    private val SLUG = Regex("[^A-Za-z0-9._-]")

    /** Nama file APK hasil salin: `<slug>-debug-<yyyyMMdd-HHmmss>.apk`. */
    fun apkOutName(project: String, now: Date): String {
        val slug = SLUG.replace(project, "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "apk" }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "$slug-debug-$ts.apk"
    }
}
