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
        /** Nama berkas manifest symlink di dalam bootstrap Termux. */
        const val SYMLINK_MANIFEST = "SYMLINKS.txt"

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

    /**
     * Berapa komponen path pertama yang harus dibuang saat ekstraksi.
     * Rootfs proot-distro dibungkus satu folder teratas (mis.
     * `ubuntu-plucky-aarch64/`), jadi satu komponen harus dilepas.
     */
    private val stripCount: Int = if (spec.layout == RootfsLayout.STRIP_TOP_DIR) 1 else 0

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
     * Rootfs dianggap terpasang kalau salah satu penanda distro
     * ([spec.releaseMarkers]) ada sebagai FILE biasa.
     *
     * Penanda per distro: `etc/alpine-release` (Alpine), `etc/os-release`
     * (Ubuntu/Debian/Fedora/Arch/…), `data/data/com.termux/files/usr/bin/bash`
     * (Termux built-in).
     *
     * Jangan pakai `bin/sh`: di Alpine itu symlink absolut `/bin/sh -> /bin/busybox`.
     * `File.exists()` mengikuti symlink ke path HOST Android (`/bin/busybox` tidak
     * ada di perangkat), sehingga rootfs yang sudah terpasang dianggap belum ada.
     * Ini penyebab bug "sudah di-install tapi tidak terbaca".
     */
    fun isInstalled(): Boolean = isInstalledIn(rootfsDir)

    /** Cek penanda distro di folder tertentu (dipakai saat ekstraksi staging). */
    private fun isInstalledIn(dir: File): Boolean =
        spec.releaseMarkers.any { File(dir, it).isFile }

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
        try {
            val custom = customUrl?.trim()?.takeIf { it.isNotEmpty() }
            val candidates: List<String> = when {
                custom != null -> listOf(custom)
                else -> spec.rootfsUrls(abi)
            }.distinct()

            var lastError: Exception? = null
            for ((index, url) in candidates.withIndex()) {
                val outcome = tryInstall(url, index, candidates.size, onProgress)
                // tryInstall mengembalikan null kalau BERHASIL.
                if (outcome == null) return@withContext Result.success(Unit)
                lastError = outcome
            }

            // Semua kandidat gagal → coba cari versi terbaru yang masih ada.
            if (custom == null && spec.resolveLatestUrls != null) {
                val fresh = spec.resolveLatestUrls(abi)
                for ((index, url) in fresh.withIndex()) {
                    val outcome = tryInstall(url, index, fresh.size, onProgress)
                    if (outcome == null) return@withContext Result.success(Unit)
                    lastError = outcome
                }
            }

            Result.failure(lastError ?: IllegalStateException("Unduhan rootfs gagal"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Jangan ditelan: kalau tidak, tombol "Batal" tidak bisa
            // menghentikan unduhan yang sedang jalan.
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
            tmpArchive.delete()
            download(url, tmpArchive) { percent, read, size ->
                val label = if (size > 0) {
                    "Mengunduh ${read / 1024 / 1024} MB / ${size / 1024 / 1024} MB"
                } else {
                    "Mengunduh ${read / 1024 / 1024} MB"
                }
                onProgress(percent / 2, label)
            }
            onProgress(52, "Mengekstrak rootfs…")

            // Ekstrak ke folder STAGING dulu. Rootfs lama (kalau ada) tetap
            // utuh sampai ekstraksi baru benar-benar berhasil — jadi unduhan
            // yang gagal tidak meremukkan distro yang sedang dipakai.
            val staging = File(distroRoot, ".staging-${spec.folderName}")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                extractArchive(tmpArchive, staging) { percent ->
                    onProgress(52 + percent / 2, "Mengekstrak rootfs… $percent%")
                }
                check(isInstalledIn(staging)) {
                    "Ekstraksi selesai tapi penanda distro (${spec.releaseMarkers.firstOrNull() ?: "os-release"}) tidak ada — arsip bukan rootfs ${spec.label}."
                }
                // Tukar: hapus yang lama, pindahkan yang baru ke tempatnya.
                rootfsDir.deleteRecursively()
                if (!staging.renameTo(rootfsDir)) {
                    staging.copyRecursively(rootfsDir, overwrite = true)
                    staging.deleteRecursively()
                }
            } finally {
                runCatching { staging.deleteRecursively() }
            }

            tmpArchive.delete()
            onProgress(97, "Menyiapkan /root/workspace & resolv.conf…")
            postInstall()
            onProgress(100, "${spec.label} terpasang.")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // dibatalkan user -> jangan dianggap kegagalan biasa
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
            path.endsWith(".zip") -> ".zip"
            else -> ".tar.gz"
        }
    }

    /** Pasang dari file .tar.gz/.tar.xz/.zip lokal hasil SAF (opsi offline). */
    suspend fun installFromStream(input: InputStream, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                onProgress(5, "Menyiapkan…")
                val staging = File(distroRoot, ".staging-${spec.folderName}")
                staging.deleteRecursively()
                staging.mkdirs()
                try {
                    extractArchive(input, staging) { percent ->
                        onProgress(5 + percent * 9 / 10, "Mengekstrak rootfs… $percent%")
                    }
                    check(isInstalledIn(staging)) {
                        "Arsip bukan rootfs yang dikenal (penanda ${spec.releaseMarkers.firstOrNull() ?: "os-release"} tidak ditemukan)."
                    }
                    rootfsDir.deleteRecursively()
                    if (!staging.renameTo(rootfsDir)) {
                        staging.copyRecursively(rootfsDir, overwrite = true)
                        staging.deleteRecursively()
                    }
                } finally {
                    runCatching { staging.deleteRecursively() }
                }
                postInstall()
                onProgress(100, "${spec.label} terpasang dari file lokal.")
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun installFromUri(uri: Uri, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("File tidak bisa dibuka"))
            stream.use { installFromStream(it, onProgress).getOrThrow() }
            Result.success(Unit)
        }

    /**
     * Persiapan setelah rootfs diekstrak.
     *
     * - `/root/workspace` — tempat file sesi (di-bind proot).
     * - `resolv.conf` + `hosts` — supaya DNS & localhost jalan di dalam guest.
     *   Untuk layout Termux, file-nya ada di dalam prefix (`<prefix>/etc`),
     *   jadi ditulis di kedua tempat.
     */
    private fun postInstall() {
        // NOTE F0: sinkron kredensial git (GitCredentials) dipasang di F2 bersama layar Providers/Auth.
        runCatching {
            File(rootfsDir, "root/workspace").mkdirs()
            val resolv = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
            val hosts = "127.0.0.1 localhost\n::1 localhost\n"
            val etcDirs = buildList {
                add(File(rootfsDir, "etc"))
                if (spec.extractDir.isNotEmpty()) add(File(rootfsDir, "$spec.extractDir/etc"))
            }
            etcDirs.forEach { dir ->
                dir.mkdirs()
                File(dir, "resolv.conf").writeText(resolv)
                File(dir, "hosts").writeText(hosts)
            }
            // Termux memakai $PREFIX/home sebagai HOME.
            if (spec.extractDir.isNotEmpty()) {
                File(rootfsDir, "$spec.extractDir/home").mkdirs()
            }
            File(rootfsDir, "root/.profile").writeText(
                "export PATH=${guestPathValue()}\nexport PS1='\\$ '\\n",
            )
        }
        // Runtime musl: tanpa ini binary opencode (musl-dinamis) tidak bisa
        // jalan di distro non-Alpine.
        ensureMuslRuntime()
    }

    /**
     * Pasang runtime musl ke dalam rootfs.
     *
     * Idempoten — aman dipanggil berulang (mis. saat server start, untuk
     * distro yang dipasang sebelum fitur ini ada).
     *
     * Binary opencode dibangun untuk musl dan dinamis
     * (`PT_INTERP=/lib/ld-musl-<arch>.so.1`,
     * `DT_NEEDED=libstdc++.so.6, libc.musl-<arch>.so.1`). Di distro glibc
     * (Ubuntu/Debian/Fedora/…) file-file itu tidak ada, sehingga `execve`
     * gagal dengan ENOENT dan muncul sebagai
     * `env: '/opt/arkacode/opencode': No such file or directory`.
     *
     * Yang dipasang:
     *  1. loader musl + libc → `<rootfs>/lib/` (PT_INTERP dicari kernel di
     *     `/lib`, jadi harus persis di sana).
     *  2. `libstdc++.so.6` + `libgcc_s.so.1` → folder engine, yang di-bind ke
     *     `/opt/arkacode/lib` di dalam guest.
     *  3. `<rootfs>/etc/ld-musl-<arch>.path` → `/opt/arkacode/lib`, supaya
     *     loader musl mencari library DI SITU (bukan libstdc++ glibc milik
     *     distro, yang tidak kompatibel).
     */
    fun ensureMuslRuntime() {
        val arch = MuslRuntime.archFor(abi) ?: return
        val dir = MuslRuntime.ensure(context, arch) ?: return
        val loader = File(dir, "ld-musl-$arch.so.1")
        if (!loader.isFile) return

        // 1. loader + libc ke <rootfs>/lib
        val libDir = File(rootfsDir, "lib")
        if (!libDir.isDirectory) runCatching { libDir.mkdirs() }
        val loaderTarget = File(libDir, loader.name)
        if (!loaderTarget.isFile) {
            runCatching { loader.copyTo(loaderTarget, overwrite = true) }
        }
        val libcTarget = File(libDir, "libc.musl-$arch.so.1")
        if (!libcTarget.exists()) {
            runCatching {
                Files.createSymbolicLink(Paths.get(libcTarget.absolutePath), Paths.get(loader.name))
            }.onFailure {
                runCatching { loader.copyTo(libcTarget, overwrite = true) }
            }
        }

        // 2. libstdc++ + libgcc ke folder engine (-> /opt/arkacode/lib di guest)
        MuslRuntime.stageEngineLibs(context, arch)

        // 3. file path milik musl
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.isDirectory) runCatching { etcDir.mkdirs() }
        runCatching {
            File(etcDir, "ld-musl-$arch.path").writeText(MuslRuntime.GUEST_LIB_DIR)
        }
    }

    fun remove() {
        runCatching { rootfsDir.deleteRecursively() }
    }

    // ----------------------------------------------------------------- download

    private suspend fun download(
        url: String,
        target: File,
        onProgress: (Int, Long, Long) -> Unit,
    ): File {
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
            // Job untuk cek pembatalan — diambil sekali di luar loop.
            // (pakai Job.isActive supaya tidak bergantung pada API
            //  ensureActive yang belum ada di semua versi coroutines)
            val downloadJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        // Cek pembatalan — supaya tombol "Batal" langsung
                        // menghentikan unduhan yang sedang berjalan.
                        if (downloadJob != null && !downloadJob.isActive) {
                            throw kotlinx.coroutines.CancellationException("unduhan dibatalkan")
                        }
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

    /**
     * Ekstraksi arsip rootfs — format ditentukan dari magic bytes, bukan dari
     * ekstensi (URL bisa tanpa ekstensi).
     *
     * Zip dipakai bootstrap Termux; tar dipakai Alpine/ubuntu-base/proot-distro.
     *
     * [targetDir] adalah folder tempat isi arsip diletakkan. Normalnya ini
     * rootfs distro, tapi saat instalasi dipakai folder staging supaya rootfs
     * lama tidak ikut terhapus kalau unduhan/ekstraksi gagal.
     */
    private fun extractArchive(source: File, targetDir: File, onProgress: (Int) -> Unit = {}): Unit {
        BufferedInputStream(source.inputStream()).use { extractArchive(it, targetDir, onProgress) }
    }

    /** Ekstraksi dari stream (berkas lokal hasil SAF) — format ditentukan magic bytes. */
    private fun extractArchive(input: InputStream, targetDir: File, onProgress: (Int) -> Unit = {}) {
        val head = ByteArray(4)
        var read = 0
        val buffered = BufferedInputStream(input)
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        val replay = java.io.SequenceInputStream(java.io.ByteArrayInputStream(head.copyOf(read)), buffered)
        val isZip = read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (isZip) extractZip(replay, targetDir, onProgress)
        else extractTar(replay, targetDir, onProgress)
    }

    /**
     * Ekstraksi zip (bootstrap Termux).
     *
     * Dua perbedaan dari tar:
     *  1. Isi zip diletakkan di [DistroSpec.extractDir] (untuk Termux:
     *     `data/data/com.termux/files/usr`) — bukan langsung di rootfs.
     *  2. Symlink tidak disimpan sebagai entri zip; Termux menaruhnya di
     *     berkas `SYMLINKS.txt` dengan format `target←linkpath`.
     *     Contoh: `dash←./bin/sh` berarti symlink `bin/sh` → `dash`.
     */
    private fun extractZip(input: InputStream, targetDir: File, onProgress: (Int) -> Unit = {}) {
        val baseDir = if (spec.extractDir.isEmpty()) targetDir else File(targetDir, spec.extractDir)
        baseDir.mkdirs()
        val root = targetDir.canonicalFile
        var symlinkManifest: String? = null
        var count = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val raw = entry.name.replace('\\', '/').removePrefix("./")
                if (raw.isBlank()) continue
                if (raw == SYMLINK_MANIFEST) {
                    symlinkManifest = zip.readBytes().toString(Charsets.UTF_8)
                    continue
                }
                val relative = stripComponents(raw, stripCount)
                if (relative.isNullOrBlank()) continue
                val target = File(baseDir, relative)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                val canonical = runCatching { target.canonicalFile }.getOrNull() ?: continue
                if (!canonical.path.startsWith(root.path + File.separator)) continue
                canonical.parentFile?.mkdirs()
                canonical.outputStream().use { out -> zip.copyTo(out) }
                count++
                if (count % 200 == 0) onProgress((count / 40).coerceAtMost(100))
            }
        }
        symlinkManifest?.let { applySymlinkManifest(it, root, baseDir) }
    }

    /**
     * Terapkan daftar symlink dari manifest Termux.
     *
     * Format tiap baris: `target←linkpath`.
     *  - `linkpath` = lokasi symlink (relatif terhadap prefix, atau absolut
     *    di dalam prefix Termux).
     *  - `target` = tujuan symlink. Termux menulisnya relatif terhadap
     *    direktori symlink (`dash←./bin/sh` -> `bin/sh` -> `dash`), tapi
     *    beberapa entri lisensi menulisnya relatif terhadap prefix. Jadi
     *    keduanya dicoba.
     */
    private fun applySymlinkManifest(manifest: String, root: File, baseDir: File) {
        manifest.lineSequence().forEach { line ->
            val parts = line.split('←')
            if (parts.size != 2) return@forEach
            val target = parts[0].trim()
            val linkPath = parts[1].trim()
            if (target.isEmpty() || linkPath.isEmpty()) return@forEach
            val link = resolveGuestPath(root, baseDir, linkPath) ?: return@forEach
            val linkDir = link.parentFile ?: return@forEach
            // Target harus benar-benar ada, kalau tidak symlink jadi sia-sia.
            val resolved = resolveGuestPath(root, linkDir, target)
                ?: resolveGuestPath(root, baseDir, target)
                ?: return@forEach
            if (!resolved.exists()) return@forEach
            runCatching {
                link.parentFile?.mkdirs()
                link.delete()
                Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
            }
        }
    }

    /**
     * Resolve path di dalam rootfs.
     *
     * Path absolut yang diawali prefix distro (mis. `/data/data/com.termux/files/usr`)
     * dipetakan ke folder prefix di dalam rootfs; path absolut lain dipetakan
     * ke rootfs. Path relatif dihitung relatif terhadap [base].
     *
     * [root] = batas akar ekstraksi (rootfs atau folder staging).
     */
    private fun resolveGuestPath(root: File, base: File, path: String): File? {
        val norm = path.replace('\\', '/')
        val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val prefix = spec.guestPrefix
        val candidate = when {
            !norm.startsWith("/") -> File(base, norm)
            prefix.isNotEmpty() && (norm == prefix || norm.startsWith("$prefix/")) ->
                File(base, norm.removePrefix(prefix).trimStart('/'))
            prefix.isEmpty() -> File(rootCanon, norm.trimStart('/'))
            else -> return null // di luar prefix distro -> jangan sentuh
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(rootCanon.path + File.separator)) return null
        return canonical
    }

    /**
     * Buang [count] komponen pertama dari path (untuk tarball proot-distro yang
     * punya satu folder teratas). Null kalau tidak ada komponen yang tersisa.
     */
    private fun stripComponents(name: String, count: Int): String? {
        if (count <= 0) return name
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size <= count) return null
        return parts.drop(count).joinToString("/")
    }

    /** Ekstraksi .tar(.gz/.xz/.zst) aman (menolak path keluar rootfs + menangani symlink/hardlink). */
    private fun extractTar(source: File, targetDir: File, onProgress: (Int) -> Unit = {}): Unit =
        BufferedInputStream(source.inputStream()).use { extractTar(it, targetDir, onProgress) }

    private fun extractTar(input: InputStream, targetDir: File, onProgress: (Int) -> Unit = {}) {
        val root = targetDir.canonicalFile
        var buffer = ByteArray(64 * 1024)
        TarArchiveInputStream(decompressor(input)).use { tar ->
            var entryCount = 0
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                entryCount++
                if (entryCount % 200 == 0) onProgress(entryCount / 40)
                val name = entry.name.replace('\\', '/').removePrefix("./")
                if (name.isBlank()) continue
                val stripped = stripComponents(name, stripCount) ?: continue
                val target = File(root, stripped)
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
     * Folder bin di dalam guest.
     *
     * Layout Termux menaruh semua binary di `$PREFIX/bin`; distro biasa
     * memakai `/usr/bin` (atau `/bin` untuk distro non-merged-usr).
     */
    private fun guestBinDir(): String = when {
        spec.extractDir.isNotEmpty() -> "${spec.guestPrefix}/bin"
        else -> "/usr/bin"
    }

    /** Path absolut di dalam guest untuk binary bernama [name], null kalau tidak ada. */
    fun guestBinary(name: String): String? {
        val candidates = when {
            spec.extractDir.isNotEmpty() -> listOf("${spec.guestPrefix}/bin/$name", "/usr/bin/$name", "/bin/$name")
            else -> listOf("/usr/bin/$name", "/bin/$name")
        }
        return candidates.firstOrNull { File(rootfsDir, it.trimStart('/')).exists() }
    }

    /** PATH di dalam guest (bin distro didahulukan). */
    fun guestPathValue(): String {
        val bin = guestBinDir()
        val base = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        return if (bin == "/usr/bin") base else "$bin:$base"
    }

    /** Path `env` di dalam guest — dipakai untuk membersihkan environment. */
    fun guestEnvPath(): String = guestBinary("env") ?: "${guestBinDir()}/env"

    /** Path shell di dalam guest. */
    fun guestShellPath(): String =
        guestBinary("sh") ?: guestBinary("bash") ?: "${guestBinDir()}/sh"

    /** Path HOME di dalam guest (Termux memakai `$PREFIX/home`). */
    fun guestHomePath(): String =
        if (spec.extractDir.isNotEmpty()) "${spec.guestPrefix}/home" else "/root"

    /**
     * Argumen `-b` tambahan yang dibutuhkan distro tertentu.
     *
     * Termux memakai bionic Android: binary-nya butuh `/system` (linker64) dan
     * `/apex` (pustaka runtime). Hanya di-bind kalau path-nya memang ada.
     */
    fun extraBindArgs(): List<String> = buildList {
        spec.extraBinds.forEach { path ->
            if (File(path).exists()) add("-b"); add(path)
        }
    }

    /** Env tambahan khas distro (PREFIX/TERMUX_PREFIX untuk Termux). */
    fun guestExtraEnv(): Map<String, String> =
        if (spec.extractDir.isEmpty()) {
            emptyMap()
        } else {
            mapOf(
                "PREFIX" to spec.guestPrefix,
                "TERMUX_PREFIX" to spec.guestPrefix,
                "TMPDIR" to "${spec.guestPrefix}/tmp",
            )
        }

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
            guestHomePath()
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
        // Termux memakai bionic Android: binary-nya butuh /system (linker64)
        // dan /apex (pustaka runtime). Hanya di-bind kalau memang ada.
        argv += extraBindArgs()
        if (bindWorkspace && pw != null) {
            argv += listOf("-b", "${pw.absolutePath}:/root/workspace")
        }
        argv += listOf(
            guestEnvPath(),
            "-i",
            "HOME=${guestHomePath()}",
            "PATH=${guestPathValue()}",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
        )
        if (settings.prootNoSeccomp) argv += "PROOT_NO_SECCOMP=1"
        // Layout Termux: binary-nya cari prefix lewat env ini (RUNPATH-nya
        // absolut ke /data/data/com.termux/files/usr).
        argv += guestExtraEnv().map { (k, v) -> "$k=$v" }
        argv += listOf(guestShellPath(), "-c", command)
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

    /**
     * Diagnostik cepat: dipakai tombol "Tes distro" di Settings.
     *
     * termasuk pemeriksaan runtime musl — kalau loader-nya tidak ada di
     * `/lib`, binary opencode (musl-dinamis) tidak akan bisa start.
     */
    fun diagnosticCommand(): String {
        val arch = MuslRuntime.archFor(abi) ?: "aarch64"
        return "cat /etc/alpine-release 2>/dev/null; cat /etc/os-release 2>/dev/null | head -3; " +
            "uname -a; id; " +
            "echo 'musl loader:'; ls -l /lib/ld-musl-$arch.so.1 /lib/libc.musl-$arch.so.1 2>&1; " +
            "echo 'musl path:'; cat /etc/ld-musl-$arch.path 2>&1; " +
            "echo 'engine libs:'; ls -l /opt/arkacode/lib 2>&1 | head -5; " +
            "echo workspace:; ls -a /root/workspace 2>/dev/null | head -20"
    }
}
