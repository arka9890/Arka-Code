package com.arkacode.app.core

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Runtime musl untuk engine `opencode`.
 *
 * Kenapa perlu:
 *
 * Binary opencode (paket npm `@opencode/cli-linux-arm64-musl`) dibangun untuk
 * musl dan **dinamis**:
 *
 * ```
 * PT_INTERP  = /lib/ld-musl-aarch64.so.1
 * DT_NEEDED  = libstdc++.so.6, libc.musl-aarch64.so.1
 * ```
 *
 * artinya:
 *  - Alpine (musl) bisa jalan ✓
 *  - Ubuntu/Debian/Fedora/Arch/... (glibc) **tidak** bisa: kernel mencari
 *    `/lib/ld-musl-aarch64.so.1` di dalam rootfs, tidak ada → `execve` gagal
 *    dengan ENOENT → muncul sebagai
 *    `env: '/opt/arkacode/opencode': No such file or directory`.
 *
 * Solusinya: pasang runtime musl (loader + libc + libstdc++ + libgcc) ke
 * dalam rootfs apa pun. Dua bagian:
 *
 *  1. **Loader + libc** → disalin ke `<rootfs>/lib/`, karena PT_INTERP
 *    isezolve oleh kernel dan harus persis di `/lib`.
 *  2. **libstdc++ + libgcc** → disalin ke folder engine (di-bind ke
 *     `/opt/arkacode/lib` di dalam guest). Agar tidak bentrok dengan
 *     libstdc++ milik distro (yang glibc), pencarian path-nya diarahkan lewat
 *     file konfigurasi milik musl:
 *
 *     ```
 *     <rootfs>/etc/ld-musl-<arch>.path  ->  /opt/arkacode/lib
 *     ```
 *
 *     Loader musl membaca file itu (sudah ada di binary loader-nya sebagai
 *     string `%.*s/etc/ld-musl-aarch64.path`) dan mencarinya **sebelum**
 *     direktori default. Program glibc di dalam guest tidak terpengaruh.
 *
 * Berkas runtime diambil dari paket Alpine (musl + gcc), yang memang libc
 * yang dipakai untuk membangun binary opencode.
 */
object MuslRuntime {

    /** Cabang Alpine tempat paket runtime diambil. */
    const val ALPINE_BRANCH = "v3.20"

    /** Basis URL repository Alpine. */
    const val ALPINE_REPO = "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main"

    /** Folder di dalam guest tempat libstdc++/libgcc musl ditaruh (bind dari folder engine). */
    const val GUEST_LIB_DIR = "/opt/arkacode/lib"

    /** Satu paket Alpine yang dipakai. */
    private data class Apk(
        val file: String,
        /** Path berkas di dalam member "data" paket (bisa beda per arch). */
        val member: (arch: String) -> String,
        /** Nama file hasil ekstraksi. */
        val outName: (arch: String) -> String,
    )

    private val PACKAGES = listOf(
        Apk(
            file = "musl-1.2.5-r3.apk",
            member = { arch -> "lib/ld-musl-$arch.so.1" },
            outName = { arch -> "ld-musl-$arch.so.1" },
        ),
        Apk(
            file = "libstdc++-13.2.1_git20240309-r1.apk",
            member = { _ -> "usr/lib/libstdc++.so.6.0.32" },
            outName = { _ -> "libstdc++.so.6" },
        ),
        Apk(
            file = "libgcc-13.2.1_git20240309-r1.apk",
            member = { _ -> "usr/lib/libgcc_s.so.1" },
            outName = { _ -> "libgcc_s.so.1" },
        ),
    )

    /** SHA256 per arch (paket Alpine berbeda isi per arsitektur). */
    private val SHA256_BY_ARCH = mapOf(
        "aarch64" to mapOf(
            "musl-1.2.5-r3.apk" to "e455c49c6c3de1dfcd4b9867c35097f588de2fb01a939c77ddb149f8e6086a24",
            "libstdc++-13.2.1_git20240309-r1.apk" to "ee68ab3af02350f2135b6c0de393a55be88fbb20171e6182cdda6517c38b60a0",
            "libgcc-13.2.1_git20240309-r1.apk" to "0d6aabf922c91c8bfc62cf625366a6669d67048c3a4fef2318a1246f152de2c0",
        ),
        "x86_64" to mapOf(
            "musl-1.2.5-r3.apk" to "70705bdeb1a8d54ee1ec7ce3b06f176206f4f3b105d86cf5576d74b8277adfa0",
            "libstdc++-13.2.1_git20240309-r1.apk" to "af0fe894ef5051116e321bf4753a10fffa85abc2b71f30b2e949467775421ace",
            "libgcc-13.2.1_git20240309-r1.apk" to "f348d99e10b5267566afe6f80861661b08cb5aa43a6d4d1c8f1792b5001d0995",
        ),
    )

    /** Arsitektur musl untuk ABI Android. */
    fun archFor(abi: String): String? = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> null // opencode hanya punya build arm64 & x64
    }

    /** Folder cache runtime musl di dalam folder data aplikasi. */
    fun cacheDir(context: Context): File = File(context.filesDir, "musl").apply { mkdirs() }

    /**
     * Pastikan runtime musl tersedia di cache; unduh bila belum.
     *
     * Mengembalikan folder berisi:
     *  - `ld-musl-<arch>.so.1` (loader musl = libc musl)
     *  - `libstdc++.so.6`
     *  - `libgcc_s.so.1`
     *
     * null kalau gagal (mis. offline).
     */
    fun ensure(context: Context, arch: String): File? {
        val dir = cacheDir(context)
        val loader = File(dir, "ld-musl-$arch.so.1")
        val stdcxx = File(dir, "libstdc++.so.6")
        val gcc = File(dir, "libgcc_s.so.1")
        if (loader.isFile && stdcxx.isFile && gcc.isFile) return dir

        val hashes = SHA256_BY_ARCH[arch] ?: return null
        for (pkg in PACKAGES) {
            val target = File(dir, pkg.outName(arch))
            if (target.isFile && sha256(target) == hashes[pkg.file]) continue
            val apk = File(dir, pkg.file)
            if (!apk.isFile || sha256(apk) != hashes[pkg.file]) {
                if (!download("$ALPINE_REPO/$arch/${pkg.file}", apk)) return null
                if (sha256(apk) != hashes[pkg.file]) {
                    apk.delete()
                    return null
                }
            }
            val files = runCatching { apkDataFiles(apk.readBytes()) }.getOrNull() ?: return null
            val bytes = files[pkg.member(arch)] ?: return null
            FileOutputStream(target).use { it.write(bytes) }
        }
        return dir
    }

    // ------------------------------------------------------------------ unduhan

    /**
     * Salin `libstdc++.so.6` + `libgcc_s.so.1` ke folder engine.
     *
     * Folder engine di-bind ke `/opt/arkacode` di dalam guest, jadi file-nya
     * terlihat sebagai `/opt/arkacode/lib` — path yang ditulis di
     * `/etc/ld-musl-<arch>.path`.
     */
    fun stageEngineLibs(context: Context, arch: String): Boolean {
        val dir = cacheDir(context)
        val engineLib = File(context.filesDir, "engine/lib").apply { mkdirs() }
        var ok = true
        listOf("libstdc++.so.6", "libgcc_s.so.1").forEach { name ->
            val src = File(dir, name)
            if (!src.isFile) {
                ok = false
                return@forEach
            }
            val dst = File(engineLib, name)
            if (!dst.isFile || dst.length() != src.length()) {
                ok = runCatching { src.copyTo(dst, overwrite = true) }.isSuccess && ok
            }
        }
        return ok
    }

    private fun download(url: String, target: File): Boolean {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "arkacode-android")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    false
                } else {
                    val tmp = File(target.absolutePath + ".part")
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (tmp.length() < 1024) {
                        tmp.delete()
                        false
                    } else {
                        if (target.exists()) target.delete()
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        true
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --------------------------------------------------------------- format .apk

    /**
     * Berkas `.apk` Alpine = beberapa member gzip yang disambung:
     * signature, control, lalu data (isi paket).
     *
     * Dipisah di sini dengan membaca member gzip satu per satu — mem-parse
     * seluruhnya sebagai satu tar tidak akan berhasil karena tiap member
     * punya akhir arsip sendiri.
     */
    fun gzipMembers(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var off = 0
        while (off + 10 <= bytes.size &&
            bytes[off] == 0x1f.toByte() && bytes[off + 1] == 0x8b.toByte()
        ) {
            val flg = bytes[off + 3].toInt()
            var p = off + 10
            if (flg and 0x04 != 0) { // FEXTRA
                val len = (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8)
                p += 2 + len
            }
            if (flg and 0x08 != 0) { // FNAME
                while (p < bytes.size && bytes[p] != 0.toByte()) p++
                p++
            }
            if (flg and 0x10 != 0) { // FCOMMENT
                while (p < bytes.size && bytes[p] != 0.toByte()) p++
                p++
            }
            if (flg and 0x02 != 0) p += 2 // FHCRC

            val inflater = Inflater(true) // deflate mentah
            try {
                inflater.setInput(bytes, p, bytes.size - p)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                val consumed = inflater.bytesRead.toInt()
                result += out.toByteArray()
                // +8 = footer gzip (CRC32 + ISIZE)
                off = p + consumed + 8
            } catch (e: Exception) {
                // Berkas rusak: berhenti di member terakhir yang terbaca.
                break
            } finally {
                inflater.end()
            }
        }
        return result
    }

    /** Isi member "data" dari paket .apk: nama path → isi berkas. */
    fun apkDataFiles(bytes: ByteArray): Map<String, ByteArray> {
        val data = gzipMembers(bytes).getOrNull(2) ?: return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        TarArchiveInputStream(ByteArrayInputStream(data)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                // apk Alpine menyimpan nama entri berawalan "./" (mis.
                // "./lib/ld-musl-aarch64.so.1") — normalisasi agar kunci
                // cocok dengan path yang dicari PACKAGES; tanpa ini runtime
                // musl tidak pernah terpasang di distro non-Alpine.
                out[entry.name.removePrefix("./")] = tar.readBytes()
            }
        }
        return out
    }
}
