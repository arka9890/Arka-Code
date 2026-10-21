package arka

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Menyiapkan binary proot Android (dari paket Termux) ke sebuah folder jniLibs.
 *
 * Kenapa harus di dalam APK? Sejak Android 10 (targetSdk 29+) aplikasi tidak
 * boleh mengeksekusi file dari folder datanya sendiri (kebijakan W^X / SELinux),
 * sedangkan folder nativeLibraryDir boleh. Karena itu proot + loader + library
 * pendukungnya harus dibundel sebagai `lib*.so` di dalam APK.
 *
 * Logika ini sengaja ditaruh di buildSrc (Kotlin biasa) bukan di
 * app/build.gradle.kts, supaya bisa dites dan tidak bertabrakan dengan
 * keterbatasan compiler Kotlin-DSL untuk closure bersarang.
 */
object ProotBinaries {

    /**
     * Versi paket Termux yang dibundel.
     *
     * Catatan: `proot` harus mengikuti rilis Termux. Paket versi lama dihapus
     * dari mirror begitu rilis baru keluar (mis. 5.1.107.97 hilang saat
     * 5.1.107.98 rilis), sehingga build yang memakai versi lama akan 404
     * diam-diam dan APK terbit tanpa binary proot.
     *
     * Karena itu [SHA256] ikut dipin di bawah: kalau versi dinaikkan tanpa
     * memperbarui hash, build gagal cepat dengan pesan jelas, bukan diam-diam
     * menghasilkan APK yang tidak bisa menjalankan command.
     */
    const val PROOT_VERSION = "5.1.107.98"
    const val TALLOC_VERSION = "2.5.0"
    const val SHMEM_VERSION = "0.7"

    /**
     * SHA256 paket Termux per (paket, abi), diambil dari index resmi
     * `dists/stable/main/binary-<arch>/Packages`.
     *
     * Dipakai untuk memastikan binary yang dibundel benar-benar yang
     * dipublish Termux (anti supply-chain) dan tidak korup karena unduhan
     * terpotong. Kalau hash tidak cocok, mirror lain dicoba; kalau semua
     * mirror gagal, build gagal (bukan diam-diam lanjut).
     */
    val SHA256: Map<String, Map<String, String>> = mapOf(
        "proot" to mapOf(
            "aarch64" to "29de40efea4daa2a6168b617915206511c63e8d46bf5f4b07d48723441ef7f3b",
            "arm" to "23d505c57c5d9fdbe4d6ac669bcb8a25610912c62acddd9a5575f01e56015879",
            "x86_64" to "1c31ad7e1046f4d418fef19b6dfaa9a14801d2047ade6ad0375b3f5501e3e227",
            "i686" to "819ce5904a3e851c6675bdea87e4df43745cca08c8a798fa890269682ad4a85c",
        ),
        "libtalloc" to mapOf(
            "aarch64" to "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
            "arm" to "98ace4f1fcbb04b75e859a12b689a9ae4ac91ff8fd34ab221e5359c95ba47f52",
            "x86_64" to "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530",
            "i686" to "7994c6c6ec74a117630f5e8e88273bd701c4e475e638b69826879a32916f5e64",
        ),
        "libandroid-shmem" to mapOf(
            "aarch64" to "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
            "arm" to "5832fd11dca9be2a288dd8fbc2b2799b289c812c7a8764f1f8234c425aa64ce5",
            "x86_64" to "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797",
            "i686" to "e9ccecee1aeed7dd70ac93bf44a6ba1bf6d4cb9559aabeb086acdc89accb4ba4",
        ),
    )

    /**
     * Gabungan semua versi paket. Dipakai sebagai input Gradle dan isi penanda
     * `outputDir/.proot-version`, supaya perubahan versi benar-benar
     * memaksa unduhan ulang (bukan cuma mengubah konstanta).
     */
    val VERSIONS = "$PROOT_VERSION-$TALLOC_VERSION-$SHMEM_VERSION"

    /** Mirror paket Termux (semua sudah diverifikasi punya paket proot terbaru). */
    val MIRRORS = listOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
    )

    val ABI_TO_ARCH = listOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "i686",
    )

    /** Ukuran minimum file .deb yang masuk akal (aarch64 proot ~99 KB). */
    private const val MIN_DEB_BYTES = 2_000L

    private data class Package(
        val name: String,
        val path: String,
        /** entri di dalam data.tar -> nama file di jniLibs */
        val files: Map<String, String>,
    )

    private val packages = listOf(
        Package(
            name = "proot",
            path = "pool/main/p/proot/proot_${PROOT_VERSION}_{arch}.deb",
            files = mapOf(
                "/usr/bin/proot" to "libproot.so",
                "/usr/libexec/proot/loader" to "libproot-loader.so",
            ),
        ),
        Package(
            name = "libtalloc",
            path = "pool/main/libt/libtalloc/libtalloc_${TALLOC_VERSION}_{arch}.deb",
            files = mapOf("/usr/lib/libtalloc.so.${TALLOC_VERSION}" to "libtalloc.so"),
        ),
        Package(
            name = "libandroid-shmem",
            path = "pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_{arch}.deb",
            files = mapOf("/usr/lib/libandroid-shmem.so" to "libandroid-shmem.so"),
        ),
    )

    /** Ringkasan hasil [prepare] — dipakai task Gradle untuk pesan yang jelas. */
    data class Report(
        val readyAbis: List<String>,
        val failedAbis: Map<String, String>,
    ) {
        val readyCount: Int get() = readyAbis.size
    }

    /**
     * @return jumlah ABI yang berhasil disiapkan (0 = tidak ada, build tetap lanjut
     *         hanya bila task Gradle mengizinkan).
     */
    fun prepare(outputDir: File, cacheDir: File, log: (String) -> Unit): Int =
        prepareDetailed(outputDir, cacheDir, log).readyCount

    fun prepareDetailed(outputDir: File, cacheDir: File, log: (String) -> Unit): Report {
        outputDir.mkdirs()
        cacheDir.mkdirs()

        // Binary hasil ekstraksi versi lain (mis. cache lama 5.1.107.97) harus
        // dibuang dulu, jangan dipakai ulang diam-diam di bawah label versi baru.
        val marker = File(outputDir, ".proot-version")
        val cachedVersion = if (marker.exists()) marker.readText().trim() else null
        if (cachedVersion != VERSIONS) {
            outputDir.listFiles()?.forEach { child ->
                if (child == marker) return@forEach
                child.deleteRecursively()
            }
            log("[proot] versi berubah ($cachedVersion -> $VERSIONS), hasil ekstraksi lama dibersihkan")
        }

        val ready = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()

        for ((abi, arch) in ABI_TO_ARCH) {
            val abiDir = File(outputDir, abi)
            if (File(abiDir, "libproot.so").exists() && File(abiDir, "libproot-loader.so").exists()) {
                log("[proot] $abi sudah ada (cache) — dilewati")
                ready += abi
                continue
            }
            var abiOk = true
            for (pkg in packages) {
                // Nama file cache sengaja memuat versi, supaya cache versi lama
                // tidak diekstrak ulang setelah pin naik.
                val deb = File(cacheDir, pkg.path.substringAfterLast('/').replace("{arch}", arch))
                if (!deb.exists() || !hashMatches(deb, pkg.name, arch)) {
                    if (deb.exists()) {
                        log("[proot] cache $abi/${deb.name} hash tidak cocok — unduh ulang")
                        deb.delete()
                    }
                    val relative = pkg.path.replace("{arch}", arch)
                    var downloaded = false
                    var lastError = "tidak ada mirror yang bisa dipakai"
                    for (mirror in MIRRORS) {
                        val url = "$mirror/$relative"
                        if (download(url, deb, log)) {
                            val expected = SHA256[pkg.name]?.get(arch)
                            if (expected == null) {
                                // Tidak ada hash untuk ABI ini — tetap diterima,
                                // tapi dicatat lewat log supaya terlihat.
                                log("[proot] $abi/${pkg.name}: tidak ada hash pin untuk $arch")
                                downloaded = true
                                break
                            }
                            val actual = sha256(deb)
                            if (actual == expected) {
                                downloaded = true
                                break
                            }
                            log("[proot] hash $abi/${pkg.name} tidak cocok (mirror ${mirror.substringAfter("//").substringBefore("/")})")
                            deb.delete()
                            lastError = "hash tidak cocok di semua mirror"
                        }
                    }
                    if (!downloaded) {
                        log("[proot] gagal mengunduh paket ${pkg.name} untuk $abi: $lastError")
                        failed[abi] = "$pkg.name: $lastError"
                        abiOk = false
                        break
                    }
                }
                for ((entry, libName) in pkg.files) {
                    val target = File(abiDir, libName)
                    val extracted = extractEntry(deb, entry, target)
                    if (extracted) {
                        target.setExecutable(true, false)
                        log("[proot] $abi/${libName} ← ${pkg.name} (${target.length()} byte)")
                    } else {
                        log("[proot] entri $entry tidak ditemukan di ${pkg.name}")
                        failed[abi] = "entri $entry tidak ada di ${pkg.name}"
                        abiOk = false
                    }
                }
            }
            if (abiOk && File(abiDir, "libproot.so").exists()) ready += abi
        }

        // Hanya tulis penanda kalau ada yang benar-benar siap; kalau gagal
        // semua (offline), build berikutnya tetap mencoba ulang.
        if (ready.isNotEmpty()) marker.writeText(VERSIONS)
        return Report(ready, failed)
    }

    private fun hashMatches(deb: File, pkgName: String, arch: String): Boolean {
        val expected = SHA256[pkgName]?.get(arch) ?: return true // tidak dipin -> trusted
        return sha256(deb) == expected
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

    private fun download(url: String, target: File, log: (String) -> Unit): Boolean {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "arka-gradle")
            try {
                if (conn.responseCode !in 200..299) {
                    log("[proot] HTTP ${conn.responseCode} untuk $url")
                    return false
                }
                val tmp = File(target.absolutePath + ".part")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (tmp.length() < MIN_DEB_BYTES) {
                    log("[proot] berkas terlalu kecil (${tmp.length()} byte) — $url")
                    tmp.delete()
                    return false
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log("[proot] gagal unduh $url (${e.message})")
            false
        }
    }

    /** Ambil satu file dari `data.tar.*` di dalam paket .deb. */
    private fun extractEntry(deb: File, entrySuffix: String, target: File): Boolean {
        val wanted = entrySuffix.replace('\\', '/').trimStart('/')
        var found = false
        try {
            ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
                var dataStream: InputStream? = null
                while (!found) {
                    val arEntry = ar.nextArEntry ?: break
                    val name = arEntry.name.trimEnd('/')
                    if (!name.startsWith("data.tar")) continue
                    dataStream = when {
                        name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                        else -> ar
                    }
                    found = scanTar(dataStream, wanted, target)
                }
            }
        } catch (e: Exception) {
            return false
        }
        return found
    }

    private fun scanTar(stream: InputStream, wantedSuffix: String, target: File): Boolean {
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: return false
                if (!entry.isFile) continue
                val normalized = entry.name.removePrefix("./").trimStart('/')
                if (!normalized.endsWith(wantedSuffix)) {
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> tar.copyTo(out) }
                return true
            }
        }
    }
}
