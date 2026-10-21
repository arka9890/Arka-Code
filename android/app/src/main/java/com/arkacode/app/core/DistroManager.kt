package com.arkacode.app.core

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths

data class DistroStatus(
    val abi: String,
    val prootAvailable: Boolean,
    val loaderAvailable: Boolean,
    val installed: Boolean,
    val rootfsDir: File?,
    val sizeBytes: Long,
    val message: String,
    /** Id distro yang sedang dipakai ("alpine-3.20", "custom", …). */
    val distroId: String = "alpine-3.20",
    /** Nama distro yang enak dibaca. */
    val distroLabel: String = "Alpine Linux",
)

/**
 * Runtime distro untuk `run_command` (M8).
 *
 * Kenapa proot + Alpine?
 *  - `Runtime.exec` di Android hanya memberi toybox tanpa package manager, jadi
 *    AI praktis tidak bisa "ngoding" (tanpa git/node/python/apk).
 *  - proot (tanpa root) + Alpine minirootfs memberi distro Linux asli: shell,
 *    apk, git, python3, node, dll. Ini yang dipakai Termux/Andronix/UserLAnd.
 *
 * Detail penting platform (Android 10+, targetSdk 29+):
 *  - Eksekusi file dari folder data aplikasi **diblokir** (SELinux). Karena itu
 *    binary `proot` + `loader` harus ikut di dalam APK sebagai `jniLibs`
 *    (`libproot.so`, `libproot-loader.so`) sehingga terpasang di
 *    `nativeLibraryDir` yang boleh dieksekusi. Task Gradle
 *    `downloadProotBinaries` yang mengisinya (lihat app/build.gradle.kts).
 *  - Rootfs Alpine (file biasa) tetap boleh di folder data karena dieksekusi
 *    *lewat* proot-loader, bukan langsung oleh kernel.
 *
 * Distro yang dipakai bisa diganti lewat [spec] — lihat [DistroCatalog].
 */
class DistroManager(
    private val context: Context,
    val spec: DistroSpec = DistroCatalog.ALPINE_320,
) {

    companion object {
        const val DISTRO_NAME = "alpine"

        /**
         * Alpine minirootfs per-ABI (v3.20, LTS ringan ±3.6 MB).
         *
         * Dipertahankan untuk kompatibilitas; lebih baik pakai
         * [DistroCatalog.ALPINE_320] yang menangani beberapa mirror.
         */
        fun rootfsUrlFor(abi: String): String {
            val arch = when (abi.lowercase()) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a" -> "armv7"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> "aarch64"
            }
            val file = if (arch == "aarch64") {
                "alpine-minirootfs-3.20.3-aarch64.tar.gz"
            } else {
                "alpine-minirootfs-3.20.3-$arch.tar.gz"
            }
            val dir = when (arch) {
                "aarch64" -> "aarch64"
                "armv7" -> "armv7"
                "x86_64" -> "x86_64"
                else -> "x86"
            }
            return "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$dir/$file"
        }

        const val DEFAULT_ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

        /** Cermin alternatif kalau CDN utama tidak bisa dijangkau dari perangkat. */
        val ROOTFS_MIRRORS = listOf(
            "https://mirror.leaseweb.com/alpine",
            "https://ftp.halifax.rwth-aachen.de/alpine",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        )

        /** Daftar ABI yang binary proot-nya benar-benar dibundel. */
        val SUPPORTED_ABIS = Build.SUPPORTED_ABIS.toList()
    }

    private val distroRoot = File(context.filesDir, "distro").apply { mkdirs() }
    val rootfsDir: File get() = File(distroRoot, spec.folderName)
    private val tmpDir = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    /** nativeLibraryDir berisi libproot.so / libtalloc.so / libproot-loader.so dari APK. */
    private val libDir: File = File(context.applicationInfo.nativeLibraryDir)

    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun prootBinary(): File? = libDir.listFiles()
        ?.firstOrNull { it.name == "libproot.so" || it.name == "libproot" }

    fun loaderBinary(): File? = libDir.listFiles()
        ?.firstOrNull { it.name == "libproot-loader.so" || it.name == "libproot-loader" }

    fun tallocBinary(): File? = libDir.listFiles()
        ?.firstOrNull { it.name == "libtalloc.so" || it.name == "libtalloc.so.2" }

    fun shmemBinary(): File? = libDir.listFiles()
        ?.firstOrNull { it.name == "libandroid-shmem.so" }

    /**
     * Rootfs dianggap terpasang kalau salah satu penanda distro ([spec.releaseMarkers])
     * ada sebagai FILE biasa.
     *
     * Jangan pakai `bin/sh`: di Alpine itu symlink absolut `/bin/sh -> /bin/busybox`.
     * `File.exists()` mengikuti symlink ke path HOST Android (`/bin/busybox` tidak
     * ada di perangkat), sehingga rootfs yang sudah terpasang dianggap belum ada.
     * Ini penyebab bug "sudah di-install tapi tidak terbaca".
     */
    fun isInstalled(): Boolean =
        spec.releaseMarkers.any { File(rootfsDir, it).isFile } ||
            File(rootfsDir, "etc/alpine-release").isFile ||
            File(rootfsDir, "bin/busybox").isFile

    fun status(): DistroStatus {
        val proot = prootBinary()
        val loader = loaderBinary()
        val installed = isInstalled()
        val size = if (installed) rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val message = when {
            proot == null || loader == null ->
                "Binary proot belum ada di APK ini (task Gradle downloadProotBinaries gagal/di-skip). " +
                    "Build ulang dengan jaringan agar run_command bisa jalan."
            !installed -> "${spec.label} belum dipasang. Unduh ±${spec.approxSizeMb} MB untuk mengaktifkan distro."
            else -> "${spec.label} siap (${size / (1024 * 1024)} MB) di ${rootfsDir.absolutePath}"
        }
        return DistroStatus(
            abi = abi,
            prootAvailable = proot != null,
            loaderAvailable = loader != null,
            installed = installed,
            rootfsDir = if (installed) rootfsDir else null,
            sizeBytes = size,
            message = message,
            distroId = spec.id,
            distroLabel = spec.label,
        )
    }

    fun canRunProot(): Boolean = prootBinary() != null && loaderBinary() != null && isInstalled()

    // ------------------------------------------------------------------ install

    /**
     * Unduh rootfs distro lalu ekstrak ke folder data aplikasi.
     *
     * [customUrl] dipakai kalau distro adalah [DistroCatalog.CUSTOM] — user
     * bisa memberi URL rootfs sendiri (Debian/Ubuntu/apa pun).
     *
     * Alur kandidat:
     *  1. URL dari [DistroSpec.rootfsUrls] (utama → mirror).
     *  2. Kalau semuanya 404, [DistroSpec.resolveLatestUrls] mencari versi
     *     terbaru yang masih ada (Alpine sesekali menghapus versi lama).
     *  3. Kalau [customUrl] diisi, URL itu dipakai apa adanya.
     *
     * [onProgress] menerima 0..100 dan label status untuk ditampilkan di UI.
     */
    suspend fun install(
        customUrl: String? = null,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val custom = customUrl?.trim()?.takeIf { it.isNotEmpty() }
            var candidates: List<String> = when {
                custom != null -> listOf(custom)
                else -> spec.rootfsUrls(abi)
            }.distinct()

            var lastError: Exception? = null
            for ((index, url) in candidates.withIndex()) {
                val outcome = tryInstall(url, index, candidates.size, onProgress)
                // tryInstall mengembalikan null kalau BERHASIL.
                if (outcome == null) return@runCatching
                lastError = outcome
            }

            // Semua kandidat gagal → coba cari versi terbaru yang masih ada.
            if (custom == null && spec.resolveLatestUrls != null) {
                val fresh = spec.resolveLatestUrls(abi)
                for ((index, url) in fresh.withIndex()) {
                    val outcome = tryInstall(url, index, fresh.size, onProgress)
                    if (outcome == null) return@runCatching
                    lastError = outcome
                }
            }

            throw lastError ?: IllegalStateException("Unduhan rootfs gagal")
        }
    }

    /**
     * Coba pasang rootfs dari satu URL. Mengembalikan null kalau BERHASIL,
     * atau exception kalau gagal (supaya kandidat berikutnya dicoba).
     */
    private suspend fun tryInstall(
        url: String,
        index: Int,
        total: Int,
        onProgress: (Int, String) -> Unit,
    ): Exception? {
        return try {
            onProgress(0, "Mengunduh ${url.substringAfterLast('/')} (${index + 1}/${total})…")
            val tmpArchive = File(tmpDir, "rootfs" + archiveSuffix(url))
            download(url) { percent, read, size ->
                val label = if (size > 0) {
                    "Mengunduh ${read / 1024 / 1024} MB / ${size / 1024 / 1024} MB"
                } else {
                    "Mengunduh ${read / 1024 / 1024} MB"
                }
                onProgress(percent / 2, label)
            }
            onProgress(52, "Mengekstrak rootfs…")
            // Bersihkan sisa instalasi sebelumnya supaya tidak campur.
            rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            extractTar(tmpArchive) { percent -> onProgress(52 + percent / 2, "Mengekstrak rootfs… $percent%") }
            tmpArchive.delete()
            check(isInstalled()) {
                "Ekstraksi selesai tapi penanda distro (${spec.releaseMarkers.firstOrNull() ?: "os-release"}) tidak ada — arsip bukan rootfs ${spec.label}."
            }
            onProgress(97, "Menyiapkan /root/workspace & resolv.conf…")
            postInstall()
            onProgress(100, "${spec.label} terpasang.")
            null
        } catch (e: Exception) {
            e
        }
    }

    /** Ekstensi arsip dari URL (default .tar.gz). */
    private fun archiveSuffix(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".tar.xz") -> ".tar.xz"
            path.endsWith(".tar.gz") || path.endsWith(".tgz") -> ".tar.gz"
            path.endsWith(".tar.zst") -> ".tar.zst"
            else -> ".tar.gz"
        }
    }

    /** Pasang dari file .tar.gz/.tar.xz lokal hasil SAF (opsi offline). */
    suspend fun installFromStream(input: InputStream, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                onProgress(5, "Menyiapkan…")
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                extractTar(input) { percent -> onProgress(5 + percent * 9 / 10, "Mengekstrak rootfs… $percent%") }
                check(isInstalled()) {
                    "Arsip bukan rootfs yang dikenal (penanda ${spec.releaseMarkers.firstOrNull() ?: "os-release"} tidak ditemukan)."
                }
                postInstall()
                onProgress(100, "${spec.label} terpasang dari file lokal.")
            }
        }

    suspend fun installFromUri(uri: Uri, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("File tidak bisa dibuka"))
            stream.use { installFromStream(it, onProgress).getOrThrow() }
            Result.success(Unit)
        }

    private fun postInstall() {
        // NOTE F0: sinkron kredensial git (GitCredentials) dipasang di F2 bersama layar Providers/Auth.
        runCatching {
            File(rootfsDir, "root/workspace").mkdirs()
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            File(rootfsDir, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
            File(rootfsDir, "root/.profile").writeText(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nexport PS1='\\$ '\\n",
            )
        }
    }

    fun remove() {
        runCatching { rootfsDir.deleteRecursively() }
    }

    // ----------------------------------------------------------------- download

    private fun download(
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
    ): File {
        val target = File(tmpDir, "rootfs.tar.gz")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arka-Android")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} saat mengunduh rootfs")
            }
            val total = conn.contentLengthLong
            var read = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val percent = if (total > 0) ((read * 100) / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent, read, if (total > 0) total else 0)
                        }
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** Ekstraksi .tar(.gz/.xz/.zst) aman (menolak path keluar rootfs + menangani symlink/hardlink). */
    private fun extractTar(source: File, onProgress: (Int) -> Unit = {}): Unit =
        BufferedInputStream(source.inputStream()).use { extractTar(it, onProgress) }

    private fun extractTar(input: InputStream, onProgress: (Int) -> Unit = {}) {
        val root = rootfsDir.canonicalFile
        var buffer = ByteArray(64 * 1024)
        TarArchiveInputStream(decompressor(input)).use { tar ->
            var entryCount = 0
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                entryCount++
                if (entryCount % 200 == 0) onProgress(entryCount / 40)
                val name = entry.name.replace('\\', '/').removePrefix("./")
                if (name.isBlank()) continue
                val target = File(root, name)
                if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
                    target.canonicalPath != root.canonicalPath
                ) {
                    continue // entri mencurigakan (path traversal) — dilewati
                }
                when {
                    entry.isDirectory -> target.mkdirs()

                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        runCatching {
                            target.delete()
                            Files.createSymbolicLink(
                                Paths.get(target.absolutePath),
                                Paths.get(entry.linkName),
                            )
                        }
                    }

                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = File(root, entry.linkName)
                        runCatching {
                            target.delete()
                            Files.createLink(Paths.get(target.absolutePath), Paths.get(linkTarget.absolutePath))
                        }.onFailure {
                            // Fallback: salin isinya kalau hardlink tidak didukung.
                            if (linkTarget.isFile) runCatching { linkTarget.copyTo(target, overwrite = true) }
                        }
                    }

                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = entry.size
                            while (remaining > 0) {
                                val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        val mode = entry.mode
                        if (mode and 0b001_000_000 != 0 || mode and 0b000_001_000 != 0 || mode and 0b000_000_001 != 0) {
                            runCatching { target.setExecutable(true, false) }
                        }
                        runCatching { target.setReadable(true, false) }
                    }
                }
            }
        }
        buffer = ByteArray(0)
    }

    /**
     * Deteksi kompresi dari magic bytes stream — rootfs Alpine (.tar.gz) dan
     * rootfs Debian/Ubuntu (.tar.xz) sama-sama didukung tanpa perlu tahu
     * ekstensi URL.
     */
    private fun decompressor(input: InputStream): InputStream {
        val head = ByteArray(6)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        val prefix = ByteArray(read)
        System.arraycopy(head, 0, prefix, 0, read)
        val replay = java.io.SequenceInputStream(java.io.ByteArrayInputStream(prefix), input)
        return when {
            read >= 2 && prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte() ->
                GzipCompressorInputStream(replay, true)
            read >= 6 && prefix[0] == 0xfd.toByte() && prefix[1] == '7'.code.toByte() ->
                XZCompressorInputStream(replay, true)
            read >= 4 && prefix[0] == 0x28.toByte() && prefix[1] == 0xb5.toByte() &&
                prefix[2] == 0x2f.toByte() && prefix[3] == 0xfd.toByte() ->
                // ZstdCompressorInputStream tidak punya overload (InputStream, boolean)
                // di commons-compress 1.27 — hanya bisa pakai satu argumen.
                ZstdCompressorInputStream(replay)
            else -> replay // tar polos
        }
    }

    // ----------------------------------------------------------------- command

    /**
     * Rangkaian argv untuk menjalankan [command] di dalam distro.
     * Mengembalikan null kalau prasyarat (proot + rootfs) belum siap.
     *
     * [prootWorkspaceDir] (root bersama semua sesi) di-bind ke `/root/workspace`;
     * cwd command diarahkan ke subfolder sesi (`/root/workspace/sessions/<sid>`)
     * kalau tersedia.
     */
    fun buildProotCommand(
        command: String,
        prootWorkspaceDir: File?,
        sessionWorkspaceDir: File?,
        settings: ExecSettings,
    ): List<String>? {
        val proot = prootBinary() ?: return null
        val loader = loaderBinary() ?: return null
        if (!isInstalled()) return null

        val pw = prootWorkspaceDir
        val bindWorkspace = settings.bindWorkspace && pw != null
        val workdir = if (bindWorkspace && sessionWorkspaceDir != null) {
            "/root/workspace/sessions/${sessionWorkspaceDir.name}"
        } else {
            "/root"
        }

        val argv = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-w", workdir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", rootfsDir.absolutePath + "/tmp:/dev/shm",
        )
        if (bindWorkspace && pw != null) {
            argv += listOf("-b", "${pw.absolutePath}:/root/workspace")
        }
        argv += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
        )
        if (settings.prootNoSeccomp) argv += "PROOT_NO_SECCOMP=1"
        argv += listOf("/bin/sh", "-c", command)
        return argv
    }

    /**
     * Environment untuk proses proot itu sendiri (bukan di dalam proot):
     * loader + tmp dir harus diset di sini juga karena proot membaca
     * PROOT_LOADER sebelum exec.
     */
    fun prootProcessEnv(): Map<String, String> {
        val linkDir = ensureLibLinks()
        val env = mutableMapOf<String, String>()
        loaderBinary()?.let { env["PROOT_LOADER"] = it.absolutePath }
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${linkDir.absolutePath}:${libDir.absolutePath}"
        return env
    }

    /**
     * Android hanya mengekstrak file bernama `lib*.so` dari APK, sedangkan
     * proot Termux ditautkan ke SONAME `libtalloc.so.2`. Karena itu dibuat
     * symlink di folder aplikasi yang menunjuk ke file asli di
     * nativeLibraryDir (folder itu satu-satunya tempat yang boleh di-exec /
     * di-mmap-linker di Android 10+).
     */
    fun ensureLibLinks(): File {
        val dir = File(context.filesDir, "proot-libs").apply { mkdirs() }
        fun link(name: String, source: File?) {
            if (source == null || !source.exists()) return
            val target = File(dir, name)
            if (target.exists()) {
                val same = runCatching { target.canonicalFile == source.canonicalFile }.getOrDefault(false)
                if (same) return
            }
            runCatching {
                target.delete()
                Files.createSymbolicLink(Paths.get(target.absolutePath), Paths.get(source.absolutePath))
            }.onFailure {
                // Fallback terakhir: salin (mungkin gagal dimuat linker di Android 10+,
                // tapi lebih baik daripada tidak ada apa-apa).
                runCatching { source.copyTo(target, overwrite = true) }
            }
        }
        link("libtalloc.so.2", tallocBinary())
        link("libandroid-shmem.so", shmemBinary())
        return dir
    }

    /** Diagnostik cepat: dipakai tombol "Tes distro" di Settings. */
    fun diagnosticCommand(): String =
        "cat /etc/alpine-release 2>/dev/null; uname -a; id; echo workspace:; ls -a /root/workspace 2>/dev/null | head -20"
}
