package com.arkacode.app.core

import android.content.Context
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manajer **tool container**: unduh + ekstrak mini-rootfs Alpine ke
 * `<filesDir>/containers/<id>/rootfs`, jalankan `setupScript` di dalamnya via
 * proot, lalu tulis shim ke `<filesDir>/containers/bin/<id>-<binary>`.
 *
 * Container bukan distro pilihan user (itu [DistroManager]) — ia hidup di
 * folder sendiri, dipasang sekali, dan di-bind ke `/opt/containers` di distro
 * mana pun (lihat [ContainerSpec] + [ContainerShim]). Karena itu pemasangannya
 * tidak menyentuh rootfs distro aktif sama sekali: binary proot/loader hanya
 * dipinjam dari [DistroCatalog.ALPINE_320] untuk menjalankan `setupScript`.
 *
 * Pola instalasi mengikuti [DistroManager]:
 *  - unduh ke cache, ekstrak ke staging `.staging-<id>`, tukar atomik;
 *  - format arsip dideteksi dari magic bytes (bukan ekstensi URL);
 *  - pembatalan koroutine diteruskan (tombol "Batal" harus tetap mempan).
 *
 * Semua fungsi yang menyentuh jaringan/proses adalah `suspend` dan memanggil
 * [onProgress] dari thread IO — jangan menyentuh main thread di callback.
 */
class ContainerManager(
    private val context: Context,
    private val abi: String,
    /** Override binary proot; null → ambil dari [DistroCatalog.ALPINE_320]. */
    private val prootBinary: File? = null,
    /** Override loader proot; null → ambil dari [DistroCatalog.ALPINE_320]. */
    private val loaderBinary: File? = null,
) {

    companion object {
        /** Path rootfs container di dalam guest, mis. `/opt/containers/python/rootfs`. */
        fun guestPath(id: String): String = "/opt/containers/$id/rootfs"

        /** Nama folder container di dalam `containers/` (mis. `containers/python`). */
        fun containerDirName(id: String): String = id

        /**
         * Nama folder rootfs **di dalam** folder container — selalu `rootfs`.
         *
         * Konstanta ini dipakai saat menyiapkan staging: isi arsip diekstrak ke
         * `<staging>/rootfs` supaya `rename(staging → containers/<id>)` langsung
         * menghasilkan `containers/<id>/rootfs` — path yang sama dengan
         * [rootfsDir] dan [guestPath]. Tanpa ini rootfs berakhir satu tingkat
         * terlalu dalam (`containers/<id>/<id>/rootfs`) dan shim menunjuk path
         * yang tidak ada.
         */
        const val ROOTFS_SUBDIR: String = "rootfs"

        /**
         * Argumen bind proot yang membuat folder containers host terlihat di
         * `/opt/containers` di dalam guest (dipakai tiga argv builder — Task 5).
         */
        fun containerBindArgs(containersHostDir: String): List<String> =
            listOf("-b", "$containersHostDir:/opt/containers")

        /** Timeout `setupScript` (`apk add` + unduhan paket) dalam milidetik. */
        const val SETUP_TIMEOUT_MS: Long = 300_000L

        /** Penanda bahwa arsip memang rootfs Alpine (cek setelah ekstraksi). */
        private val ROOTFS_MARKERS = listOf(
            "etc/alpine-release",
            "etc/os-release",
            "bin/busybox",
            "usr/bin/env",
        )
    }

    /** Dipakai hanya untuk binary proot/loader + env proses proot. */
    private val distro by lazy { DistroManager(context, DistroCatalog.ALPINE_320) }

    // ------------------------------------------------------------- filesystem

    /** `<filesDir>/containers` — dibuat bila belum ada. */
    fun containersDir(): File = File(context.filesDir, "containers").apply { mkdirs() }

    /** `<filesDir>/containers/<id>/rootfs`. */
    fun rootfsDir(spec: ContainerSpec): File =
        File(containersDir(), "${containerDirName(spec.id)}/$ROOTFS_SUBDIR")

    /** `<filesDir>/containers/bin` — tempat shim. */
    fun shimDir(): File = File(containersDir(), "bin")

    /** File shim untuk [binary] milik [spec] (nama dari [ContainerShim.shimPath]). */
    fun shimFile(spec: ContainerSpec, binary: String): File =
        File(context.filesDir, ContainerShim.shimPath(spec.id, binary))

    private fun stagingRoot(spec: ContainerSpec): File =
        File(containersDir(), ".staging-${spec.id}")

    private fun prootTmpDir(): File =
        File(context.filesDir, "proot-tmp").apply { mkdirs() }

    // ------------------------------------------------------------ status/aksi

    /**
     * Terpasang = folder rootfs ada + semua [ContainerSpec.releaseMarkers] ada
     * di dalam rootfs + semua shim binary ([ContainerSpec.shimBinaries]) ada.
     *
     * Shim ikut diperiksa karena tanpa shim tool "terpasang" tapi tidak bisa
     * dipanggil dari distro.
     */
    fun isInstalled(spec: ContainerSpec): Boolean {
        val rootfs = rootfsDir(spec)
        if (!rootfs.isDirectory) return false
        if (!spec.releaseMarkers.all { File(rootfs, it).isFile }) return false
        return spec.shimBinaries.all { shimFile(spec, it).isFile }
    }

    /** Ukuran `containers/<id>` dalam MB (`%.1f MB`); 0.0 bila belum ada. */
    fun sizeLabel(spec: ContainerSpec): String {
        val dir = File(containersDir(), containerDirName(spec.id))
        if (!dir.isDirectory) return "0.0 MB"
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** Label status untuk UI: "terpasang" / "belum terpasang". */
    fun status(spec: ContainerSpec): String =
        if (isInstalled(spec)) "terpasang" else "belum terpasang"

    // ---------------------------------------------------------------- install

    /**
     * Unduh + ekstrak + setup + shim. Idempoten: kalau [spec] sudah terpasang
     * langsung sukses tanpa jaringan.
     *
     * Kandidat URL dicoba berurutan (CDN utama → mirror); kegagalan satu
     * kandidat tidak menghapus rootfs yang sudah ada (ekstraksi ke staging).
     *
     * [onProgress] menerima 0..100 + label untuk UI.
     */
    suspend fun install(
        spec: ContainerSpec,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isInstalled(spec)) {
                onProgress(100, "${spec.label} sudah terpasang.")
                return@withContext Result.success(Unit)
            }

            val candidates = spec.rootfsUrls(abi).distinct()
            if (candidates.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Tidak ada URL rootfs untuk ABI $abi."),
                )
            }

            var lastError: Throwable? = null
            for ((index, url) in candidates.withIndex()) {
                try {
                    installFrom(spec, url, index, candidates.size, onProgress)
                    return@withContext Result.success(Unit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // dibatalkan user -> jangan dianggap kegagalan biasa
                } catch (e: Throwable) {
                    lastError = e
                }
            }

            runCatching { stagingRoot(spec).deleteRecursively() }
            Result.failure(lastError ?: IllegalStateException("Unduhan rootfs container gagal"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Pasang dari satu URL. Semua langkah di sini blocking (dipanggil dari
     * [Dispatchers.IO]); exception dibiarkan menyebar supaya kandidat URL
     * berikutnya dicoba.
     */
    private suspend fun installFrom(
        spec: ContainerSpec,
        url: String,
        index: Int,
        total: Int,
        onProgress: (Int, String) -> Unit,
    ) {
        val containers = containersDir()
        val staging = stagingRoot(spec)
        // Isi arsip diekstrak ke `<staging>/rootfs` — BUKAN `<staging>/<id>`.
        // Dengan begitu `renameTo(containers/<id>)` menghasilkan
        // `containers/<id>/rootfs`, persis [rootfsDir] dan [guestPath].
        val stagingRootfs = File(staging, ROOTFS_SUBDIR)
        val archive = File(context.cacheDir, "${spec.id}.tar.gz")

        onProgress(0, "Mengunduh ${url.substringAfterLast('/')} (${index + 1}/${total})…")
        staging.deleteRecursively()
        stagingRootfs.mkdirs()
        archive.delete()
        download(url, archive) { percent, read, size ->
            val label = if (size > 0) {
                "Mengunduh ${read / 1024 / 1024} MB / ${size / 1024 / 1024} MB"
            } else {
                "Mengunduh ${read / 1024 / 1024} MB"
            }
            onProgress(percent / 2, label)
        }

        onProgress(52, "Mengekstrak rootfs…")
        extractArchive(archive, stagingRootfs) { percent ->
            onProgress(52 + percent / 2, "Mengekstrak rootfs… $percent%")
        }
        check(looksLikeRootfs(stagingRootfs)) {
            "Arsip bukan rootfs Alpine (penanda ${ROOTFS_MARKERS.firstOrNull()} tidak ditemukan)."
        }

        // /root/workspace, /tmp, /etc/resolv.conf, /etc/hosts, bit executable.
        ContainerRootfs.ensureContainerLinks(stagingRootfs)

        onProgress(82, "Memasang ${spec.label} di dalam container…")
        runSetupScript(spec, stagingRootfs)

        check(spec.releaseMarkers.all { File(stagingRootfs, it).isFile }) {
            "Setup selesai tapi ${spec.releaseMarkers.joinToString()} tidak ada di rootfs container."
        }

        // Tukar staging → rootfs (rootfs lama dihapus hanya setelah yang baru siap).
        val target = File(containers, containerDirName(spec.id))
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        archive.delete()

        onProgress(94, "Menulis shim…")
        writeShims(spec)
        runCatching { File(target, "home").mkdirs() }
        runCatching { File(target, ".installed").writeText("ok\n") }
        onProgress(100, "${spec.label} terpasang.")
    }

    /** Tulis shim `containers/bin/<id>-<binary>` untuk tiap binary + setExecutable. */
    private fun writeShims(spec: ContainerSpec) {
        val bin = shimDir().apply { mkdirs() }
        spec.shimBinaries.forEach { binary ->
            val shim = File(bin, "${spec.id}-$binary")
            runCatching {
                shim.parentFile?.mkdirs()
                shim.writeText(ContainerShim.script(spec, binary, abi))
                shim.setReadable(true, false)
                shim.setExecutable(true, false)
            }
        }
    }

    /** Jalankan `spec.setupScript` di dalam rootfs staging via proot. */
    private suspend fun runSetupScript(spec: ContainerSpec, rootfs: File) {
        val proot = resolvedProot()
            ?: throw IllegalStateException(
                "Binary proot tidak ada di APK ini (task Gradle downloadProotBinaries gagal/di-skip).",
            )
        val loader = resolvedLoader()
            ?: throw IllegalStateException(
                "Loader proot tidak ada di APK ini (task Gradle downloadProotBinaries gagal/di-skip).",
            )

        val argv = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            // SELinux Android melarang link() untuk app biasa; proot
            // menerjemahkannya jadi symlink agar apk bisa jalan.
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${prootTmpDir().absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "/bin/sh",
            "-c",
            spec.setupScript,
        )

        val result = runProot(argv, SETUP_TIMEOUT_MS)
        if (result.code != 0) {
            val tail = result.output.trim().takeLast(800)
            val detail = if (tail.isEmpty()) "." else ": $tail"
            throw IllegalStateException("Setup ${spec.label} gagal (exit ${result.code})$detail")
        }
    }

    /**
     * Hapus container: folder `containers/<id>`, staging, dan semua shim.
     *
     * [engineRunning] wajib diisi pemanggil: shim yang sedang dipakai sesi aktif
     * akan menggantung kalau rootfs-nya dihapus di bawah proot. Guard ada di sini
     * (bukan hanya di UI) supaya pemanggil lain tidak bisa melewatinya.
     */
    fun remove(spec: ContainerSpec, engineRunning: Boolean): Result<Unit> = try {
        if (engineRunning) throw IllegalStateException("Hentikan engine dulu sebelum menghapus container.")
        runCatching { File(containersDir(), spec.id).deleteRecursively() }
        runCatching { stagingRoot(spec).deleteRecursively() }
        spec.shimBinaries.forEach { binary ->
            runCatching { shimFile(spec, binary).delete() }
        }
        Result.success(Unit)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    // ----------------------------------------------------------------- binary

    private fun resolvedProot(): File? = prootBinary ?: distro.prootBinary()

    private fun resolvedLoader(): File? = loaderBinary ?: distro.loaderBinary()

    /**
     * Environment untuk proses proot (di luar guest): loader + tmp dir +
     * `LD_LIBRARY_PATH` ke symlink libtalloc/libandroid-shmem (lihat
     * [DistroManager.prootProcessEnv]). Override loader tetap dihormati.
     */
    private fun prootProcessEnv(): Map<String, String> {
        val env = distro.prootProcessEnv().toMutableMap()
        resolvedLoader()?.let { env["PROOT_LOADER"] = it.absolutePath }
        env["PROOT_TMP_DIR"] = prootTmpDir().absolutePath
        return env
    }

    // ---------------------------------------------------------------- proses

    private data class ProotResult(val code: Int, val output: String)

    /**
     * Jalankan argv lewat `ProcessBuilder` dan kumpulkan output gabungan.
     *
     * Output dibaca di thread terpisah: `apk add` bisa menulis >64 KB dan
     * pipe akan penuh (deadlock) kalau dibaca setelah proses selesai.
     */
    private suspend fun runProot(argv: List<String>, timeoutMs: Long): ProotResult {
        val builder = ProcessBuilder(argv)
        builder.directory(context.filesDir)
        builder.environment().apply {
            putAll(prootProcessEnv())
            this["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
            this["HOME"] = context.filesDir.absolutePath
            this["TMPDIR"] = context.cacheDir.absolutePath
        }
        builder.redirectErrorStream(true)
        val proc = builder.start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { input ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(output) { output.append(buf, 0, n) }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        try {
            val code = awaitExit(proc, timeoutMs)
            reader.join(5_000)
            return ProotResult(code, synchronized(output) { output.toString() })
        } finally {
            if (proc.isAlive) runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * Tunggu proses selesai; batalkan (CancellationException) bila job
     * koroutine dibatalkan, dan gagalkan bila melewati [timeoutMs].
     */
    private suspend fun awaitExit(proc: Process, timeoutMs: Long): Int {
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (job != null && !job.isActive) {
                throw kotlinx.coroutines.CancellationException("instalasi container dibatalkan")
            }
            if (proc.waitFor(200, TimeUnit.MILLISECONDS)) return proc.exitValue()
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("timeout menjalankan setup container")
            }
        }
    }

    // -------------------------------------------------------------- download

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
                throw IllegalStateException("HTTP ${conn.responseCode} saat mengunduh rootfs container")
            }
            val total = conn.contentLengthLong
            var read = 0L
            var lastPercent = -1
            val downloadJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
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

    // -------------------------------------------------------------- ekstraksi

    /**
     * Ekstraksi arsip rootfs — format dari magic bytes (URL bisa tanpa ekstensi
     * dan mirror bisa membalas HTML error). Pola sama dengan
     * [DistroManager] `extractArchive`.
     */
    private fun extractArchive(source: File, targetDir: File, onProgress: (Int) -> Unit = {}) {
        BufferedInputStream(source.inputStream()).use { extractTar(it, targetDir, onProgress) }
    }

    /**
     * Ekstraksi .tar(.gz/.xz/.zst) aman: path traversal ditolak, symlink &
     * hardlink dipulihkan, bit executable diambil dari mode entri.
     */
    private fun extractTar(input: InputStream, targetDir: File, onProgress: (Int) -> Unit = {}) {
        val root = targetDir.canonicalFile
        root.mkdirs()
        val buffer = ByteArray(64 * 1024)
        TarArchiveInputStream(decompressor(input)).use { tar ->
            var entryCount = 0
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                entryCount++
                if (entryCount % 200 == 0) onProgress((entryCount / 40).coerceAtMost(100))

                val name = entry.name.replace('\\', '/').removePrefix("./")
                if (name.isBlank()) continue
                val target = File(root, name)
                val canonical = runCatching { target.canonicalPath }.getOrNull() ?: continue
                if (canonical != root.path && !canonical.startsWith(root.path + File.separator)) {
                    continue // entri mencurigakan (path traversal) — dilewati
                }

                when {
                    entry.isDirectory -> target.mkdirs()

                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        runCatching {
                            target.delete()
                            java.nio.file.Files.createSymbolicLink(
                                java.nio.file.Paths.get(target.absolutePath),
                                java.nio.file.Paths.get(entry.linkName),
                            )
                        }
                    }

                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = File(root, entry.linkName)
                        runCatching {
                            target.delete()
                            java.nio.file.Files.createLink(
                                java.nio.file.Paths.get(target.absolutePath),
                                java.nio.file.Paths.get(linkTarget.absolutePath),
                            )
                        }.onFailure {
                            // Fallback: salin isinya kalau hardlink tidak didukung.
                            if (linkTarget.isFile) {
                                runCatching { linkTarget.copyTo(target, overwrite = true) }
                            }
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
                        if (mode and 0b001_000_000 != 0 ||
                            mode and 0b000_001_000 != 0 ||
                            mode and 0b000_000_001 != 0
                        ) {
                            runCatching { target.setExecutable(true, false) }
                        }
                        runCatching { target.setReadable(true, false) }
                    }
                }
            }
        }
    }

    /**
     * Deteksi kompresi dari magic bytes — `.tar.gz` (Alpine) dan `.tar.xz`
     * sama-sama didukung tanpa bergantung pada ekstensi URL.
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

    /** True kalau folder hasil ekstraksi tampak seperti rootfs Alpine. */
    private fun looksLikeRootfs(dir: File): Boolean =
        ROOTFS_MARKERS.any { File(dir, it).isFile }
}