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

    private const val MAX_PROJ_LEVELS = 3
    private val PRUNE_DIRS = setOf("build", "node_modules")

    /**
     * Path relatif [root] yang merupakan proyek Gradle (berisi `gradlew` ATAU
     * `settings.gradle*`), urut alfabet. Anak root = tanpa prefix; bersarang
     * pakai `/` (mis. `Arka-Code/android`). Telusuri maksimal3 tingkat —
     * shortcut: proyek lebih dalam dari itu tidak terdeteksi, naikkan
     * MAX_PROJ_LEVELS kalau ada kasus nyata. Folder tersembunyi, `build`,
     * dan `node_modules` dilewati agar .git ratusan ribu file tak ditelusuri.
     */
    fun findProjects(root: File): List<String> {
        val out = mutableListOf<String>()

        fun isProject(d: File) =
            File(d, "gradlew").isFile ||
                File(d, "settings.gradle").isFile ||
                File(d, "settings.gradle.kts").isFile

        fun walk(dir: File, relPath: String, level: Int) {
            dir.listFiles()?.forEach { kid ->
                if (!kid.isDirectory) return@forEach
                val n = kid.name
                if (n.startsWith('.') || n in PRUNE_DIRS) return@forEach
                val rel = if (relPath.isEmpty()) n else "$relPath/$n"
                if (isProject(kid)) out.add(rel)
                else if (level + 1 < MAX_PROJ_LEVELS) walk(kid, rel, level + 1)
            }
        }

        walk(root, "", 0)
        return out.sorted()
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

    /** Nama file aman dari nama proyek (dipakai log & APK — satu sumber slug). */
    fun slugify(s: String): String =
        SLUG.replace(s, "-").replace(Regex("-+"), "-").trim('-').ifBlank { "apk" }

    /** Nama file APK hasil salin: `<slug>-debug-<yyyyMMdd-HHmmss>.apk`. */
    fun apkOutName(project: String, now: Date): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "${slugify(project)}-debug-$ts.apk"
    }

    /** Baris ringkasan — ikut ditulis ke file log agar terbaca saat overlay dibuka ulang. */
    fun summaryLine(exitCode: Int): String =
        "=== exit $exitCode === " + (if (exitCode == 0) "BUILD SUKSES" else "BUILD GAGAL") + "\n"
}
