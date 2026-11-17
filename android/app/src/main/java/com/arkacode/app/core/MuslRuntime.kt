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
import java.util.Base64
import java.util.zip.GZIPInputStream
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
 *    diselesaikan oleh kernel dan harus persis di `/lib`.
 *  2. **libstdc++ + libgcc** → disalin ke folder engine (di-bind ke
 *    `/opt/arkacode/lib` di dalam guest). Agar tidak bentrok dengan
 *    libstdc++ milik distro (yang glibc), pencarian path-nya diarahkan lewat
 *    file konfigurasi milik musl:
 *
 *     ```
 *     <rootfs>/etc/ld-musl-<arch>.path  ->  /opt/arkacode/lib
 *     ```
 *
 *     Loader musl membaca file itu (sudah ada di string binary loader-nya
 *     sebagai `%.*s/etc/ld-musl-aarch64.path`) dan mencarinya **sebelum**
 *     direktori default. Program glibc di dalam guest tidak terpengaruh.
 *
 * Berkas runtime diambil dari paket Alpine (musl + gcc), yang memang libc
 * yang dipakai untuk membangun binary opencode.
 *
 * **Versi paket dicari saat runtime, bukan ditulis mati.** Repository Alpine
 * hanya menyimpan satu revisi terbaru per paket — begitu Alpine merilis
 * `musl-1.2.5-r4`, berkas `musl-1.2.5-r3.apk` langsung dihapus dari CDN dan
 * semua mirror. Nama berkas yang dipatok di kode karena itu menjadi 404
 * beberapa bulan kemudian dan runtime musl **tidak akan pernah** terpasang
 * lagi (gejala: "Runtime musl gagal dipasang (unduh paket Alpine dari
 * CDN+mirror tidak berhasil — cek koneksi/VPN)" padahal jaringan sehat dan
 * rootfs distro berhasil diunduh). Sekarang [ensure] membaca `APKINDEX.tar.gz`
 * untuk mendapat nama berkas yang benar-benar ada, memverifikasi isinya lewat
 * checksum `C:` milik indeks (SHA-1 member control — sama dengan yang
 * diperiksa `apk` sendiri), dan baru jatuh ke nama yang dipatok
 * ([Pkg.pinnedFile]) bila indeks tidak terjangkau.
 */
object MuslRuntime {

    /** Basis CDN Alpine. */
    const val ALPINE_CDN = "https://dl-cdn.alpinelinux.org/alpine"

    /** Cabang Alpine utama tempat paket runtime diambil. */
    const val ALPINE_BRANCH = "v3.20"

    /**
     * Cabang cadangan — dipakai hanya kalau [ALPINE_BRANCH] sudah dihapus dari
     * CDN (Alpine menurunkan cabang lama saat EOL). Nama paket di cabang lain
     * berbeda, sehingga selalu diselesaikan lewat indeks.
     */
    val FALLBACK_BRANCHES = listOf("v3.21", "v3.22")

    /** Basis URL repository Alpine cabang utama. */
    const val ALPINE_REPO = "$ALPINE_CDN/$ALPINE_BRANCH/main"

    /**
     * Cermin alternatif — pola sama dengan [DistroManager] (rootfs distro).
     * Tanpa ini, CDN tunggal yang tidak terjangkau (mis. lewat VPN) membuat
     * runtime musl gagal senyap dan opencode mati ENOENT di distro glibc.
     */
    val MIRRORS = listOf(
        "https://mirror.leaseweb.com/alpine",
        "https://ftp.halifax.rwth-aachen.de/alpine",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
    )

    /** Folder di dalam guest tempat libstdc++/libgcc musl ditaruh (bind dari folder engine). */
    const val GUEST_LIB_DIR = "/opt/arkacode/lib"

    /** Batas ukuran APKINDEX / paket yang mau ditampung di memori. */
    private const val MAX_INDEX_BYTES = 8 * 1024 * 1024

    /**
     * Alasan kegagalan terakhir [ensure] (null kalau sukses).
     *
     * Dipakai pemanggil untuk pesan error yang jujur: dulu kegagalan 404
     * selalu dilaporkan sebagai "cek koneksi/VPN", padahal jaringan normal dan
     * penyebabnya berkas paket sudah tidak ada di repo.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Cabang yang dicoba berurutan: cabang utama lalu cadangan. */
    fun branches(): List<String> = buildList {
        add(ALPINE_BRANCH)
        addAll(FALLBACK_BRANCHES)
    }

    /** Basis repo (`.../alpine/<branch>/main`) untuk satu cabang. */
    fun repoBase(branch: String): String = "$ALPINE_CDN/$branch/main"

    /**
     * Kandidat URL unduhan paket runtime: CDN utama dulu, lalu cermin.
     * URL hasil SUDAH utuh (repo + arch + file) — pemanggil tinggal memakai
     * `download(url, apk)`. Murni (tanpa jaringan) — diuji di JVM.
     */
    fun repoUrls(arch: String, file: String): List<String> = repoUrls(ALPINE_BRANCH, arch, file)

    /** Varian per cabang — dipakai saat menyusuri [FALLBACK_BRANCHES]. */
    fun repoUrls(branch: String, arch: String, file: String): List<String> = buildList {
        add("${repoBase(branch)}/$arch/$file")
        MIRRORS.forEach { add("$it/$branch/main/$arch/$file") }
    }

    /** Kandidat URL indeks paket (`APKINDEX.tar.gz`) — CDN lalu cermin. */
    fun indexUrls(branch: String, arch: String): List<String> = buildList {
        add("${repoBase(branch)}/$arch/APKINDEX.tar.gz")
        MIRRORS.forEach { add("$it/$branch/main/$arch/APKINDEX.tar.gz") }
    }

    // ------------------------------------------------------------------- paket

    /**
     * Satu paket Alpine yang dibutuhkan runtime musl.
     *
     * [member] memilih berkas di dalam paket dari daftar path yang tersedia —
     * bukan path mati, karena Alpine ikut menaikkan nama berkas saat gcc
     * naik versi (mis. `libstdc++.so.6.0.32` → `...6.0.33`).
     */
    private class Pkg(
        val name: String,
        /** Nama berkas yang dipatok — fallback bila indeks tidak terjangkau. */
        val pinnedFile: String,
        /** SHA256 berkas patokan per arch (ikuti [pinnedFile]). */
        private val pinned: Map<String, String>,
        /** Nama berkas hasil ekstraksi di folder cache. */
        val outName: (arch: String) -> String,
        /** Pilih path berkas di dalam paket. */
        val member: (arch: String, paths: Set<String>) -> String?,
    ) {
        fun pinnedSha256(arch: String): String? = pinned[arch]
    }

    private val PACKAGES = listOf(
        Pkg(
            name = "musl",
            pinnedFile = "musl-1.2.5-r4.apk",
            pinned = mapOf(
                "aarch64" to "71d53cd568d014bf71fa3f76819ddcd68d4f2ec5d9e9203e8ad4dd44667b9e9f",
                "x86_64" to "b255cdcf4f64eb301f4dbd40d1fbdc0fcb021613b3f0d124a41eeff62e2bc7d8",
            ),
            outName = { arch -> "ld-musl-$arch.so.1" },
            member = { arch, paths -> paths.firstOrNull { it == "lib/ld-musl-$arch.so.1" } },
        ),
        Pkg(
            name = "libstdc++",
            pinnedFile = "libstdc++-13.2.1_git20240309-r1.apk",
            pinned = mapOf(
                "aarch64" to "ee68ab3af02350f2135b6c0de393a55be88fbb20171e6182cdda6517c38b60a0",
                "x86_64" to "af0fe894ef5051116e321bf4753a10fffa85abc2b71f30b2e949467775421ace",
            ),
            outName = { _ -> "libstdc++.so.6" },
            member = { _, paths ->
                // Paket memuat `libstdc++.so.6` (symlink) + berkas asli
                // `libstdc++.so.6.0.32`. Ambil berkas asli — symlink tidak
                // terbaca sebagai entry file, dan kalau gcc naik versi angka
                // terakhirnya ikut berubah.
                paths.filter { it.startsWith("usr/lib/libstdc++.so.6.") }.maxOrNull()
            },
        ),
        Pkg(
            name = "libgcc",
            pinnedFile = "libgcc-13.2.1_git20240309-r1.apk",
            pinned = mapOf(
                "aarch64" to "0d6aabf922c91c8bfc62cf625366a6669d67048c3a4fef2318a1246f152de2c0",
                "x86_64" to "f348d99e10b5267566afe6f80861661b08cb5aa43a6d4d1c8f1792b5001d0995",
            ),
            outName = { _ -> "libgcc_s.so.1" },
            member = { _, paths -> paths.firstOrNull { it == "usr/lib/libgcc_s.so.1" } },
        ),
    )

    /** Satu entri paket dari `APKINDEX`. */
    data class ApkEntry(
        val name: String,
        val version: String,
        val arch: String,
        val size: Long,
        /** Field `C:` — `Q1` + base64(SHA-1 member control terkompresi). */
        val checksum: String?,
    ) {
        /** Nama berkas di repo: `<nama>-<versi>.apk`. */
        val fileName: String get() = "$name-$version.apk"
    }

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
     * null kalau gagal (mis. offline) — alasannya ada di [lastError].
     */
    fun ensure(context: Context, arch: String): File? {
        lastError = null
        val dir = cacheDir(context)
        val targets = PACKAGES.associate { it.outName(arch) to File(dir, it.outName(arch)) }

        // Jalur cepat: ketiga berkas sudah ada dan masih ELF yang sah. Sengaja
        // tidak menyentuh jaringan — distro bisa start offline berkali-kali
        // tanpa mengunduh ulang, dan tidak bergantung pada indeks yang bisa
        // saja sudah tidak memuat versi yang tersimpan.
        if (targets.values.all { it.isFile && isElf(it) }) return dir

        val reasons = mutableListOf<String>()
        val resolution = resolve(arch)
        val used = mutableListOf<String>()

        for (pkg in PACKAGES) {
            val target = targets.getValue(pkg.outName(arch))
            val apk = obtainApk(pkg, arch, dir, resolution, reasons) ?: return fail(reasons)
            used += apk.name

            val files = runCatching { apkDataFiles(apk.readBytes()) }.getOrNull()
            if (files.isNullOrEmpty()) {
                reasons += "${apk.name}: isi paket tidak terbaca (bukan .apk Alpine yang sah)"
                return fail(reasons)
            }
            val member = pkg.member(arch, files.keys)
            val bytes = member?.let { files[it] }
            if (bytes == null) {
                reasons += "${apk.name}: berkas ${pkg.name} tidak ada di dalam paket"
                return fail(reasons)
            }
            if (!isElf(bytes)) {
                reasons += "${apk.name}: $member bukan binary ELF"
                return fail(reasons)
            }
            if (!writeAtomically(target, bytes)) {
                reasons += "${target.name}: gagal ditulis ke ${dir.absolutePath}"
                return fail(reasons)
            }
        }

        prune(dir, used)
        runCatching {
            File(dir, "installed-$arch.txt").writeText(
                "branch=${resolution.branch}\n" + used.joinToString("\n") { "pkg=$it" } + "\n",
            )
        }
        return dir
    }

    // ------------------------------------------------------------------ resolusi

    /** Hasil pencarian nama paket: cabang + isi indeksnya (null = indeks tak terbaca). */
    private class Resolution(val branch: String, val index: Map<String, ApkEntry>?)

    /**
     * Cari cabang yang indeksnya memuat SEMUA paket runtime.
     *
     * Cabang utama didahulukan. Kalau indeks tidak terjangkau sama sekali
     * (offline), hasilnya `Resolution(ALPINE_BRANCH, null)` dan pemanggil
     * memakai nama berkas patokan.
     */
    private fun resolve(arch: String): Resolution {
        for (branch in branches()) {
            val index = fetchIndex(branch, arch) ?: continue
            if (PACKAGES.all { index.containsKey(it.name) }) return Resolution(branch, index)
        }
        return Resolution(ALPINE_BRANCH, null)
    }

    /** Unduh + parse `APKINDEX` satu cabang; null kalau semua kandidat gagal. */
    private fun fetchIndex(branch: String, arch: String): Map<String, ApkEntry>? {
        for (url in indexUrls(branch, arch)) {
            val raw = downloadBytes(url, MAX_INDEX_BYTES) ?: continue
            val text = runCatching { parseIndexArchive(raw) }.getOrNull() ?: continue
            val parsed = runCatching { parseApkIndex(text) }.getOrNull() ?: continue
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    // ---------------------------------------------------------------- unduhan

    /** Satu kandidat berkas paket + cara memverifikasinya. */
    private class Candidate(
        val file: String,
        val controlChecksum: String?,
        val sha256: String?,
        val branch: String,
    )

    /** True kalau [apk] benar-benar berkas yang dimaksud [candidate]. */
    private fun accepts(candidate: Candidate, apk: File): Boolean {
        if (!apk.isFile || apk.length() < 1024) return false
        candidate.sha256?.let { return sha256(apk) == it }
        candidate.controlChecksum?.let { wanted ->
            val bytes = runCatching { apk.readBytes() }.getOrNull() ?: return false
            return apkControlChecksum(bytes) == wanted
        }
        return false
    }

    /**
     * Ambil berkas `.apk` untuk [pkg]: pakai cache kalau isinya masih cocok,
     * kalau tidak unduh dari CDN + cermin (nama versi terbaru dulu, lalu nama
     * patokan).
     */
    private fun obtainApk(
        pkg: Pkg,
        arch: String,
        dir: File,
        resolution: Resolution,
        reasons: MutableList<String>,
    ): File? {
        val candidates = mutableListOf<Candidate>()

        resolution.index?.get(pkg.name)?.let { entry ->
            candidates += Candidate(entry.fileName, entry.checksum, null, resolution.branch)
        }
        // Nama patokan hanya berlaku di cabang tempat hash-nya diambil.
        if (resolution.branch == ALPINE_BRANCH) {
            val pinnedSha = pkg.pinnedSha256(arch)
            if (pinnedSha != null && candidates.none { it.file == pkg.pinnedFile }) {
                candidates += Candidate(pkg.pinnedFile, null, pinnedSha, ALPINE_BRANCH)
            }
        }

        if (candidates.isEmpty()) {
            reasons += "${pkg.name}: tidak ada kandidat paket (arch $arch tidak tersedia)"
            return null
        }

        // 1. Paket yang sudah pernah terunduh — tanpa jaringan.
        for (candidate in candidates) {
            val cached = File(dir, candidate.file)
            if (accepts(candidate, cached)) return cached
        }

        // 2. Unduh: CDN utama lalu cermin, per kandidat nama berkas.
        for (candidate in candidates) {
            val target = File(dir, candidate.file)
            for (url in repoUrls(candidate.branch, arch, candidate.file)) {
                if (!download(url, target)) {
                    reasons += "$url: tidak terjangkau"
                } else if (accepts(candidate, target)) {
                    return target
                } else {
                    reasons += "$url: isi tidak cocok checksum"
                    target.delete()
                }
            }
        }
        return null
    }

    private fun fail(reasons: List<String>): File? {
        val shown = reasons.take(3).joinToString("; ")
        lastError = if (reasons.size > 3) {
            "$shown (+${reasons.size - 3} kandidat lain)"
        } else {
            shown.ifEmpty { "paket runtime tidak tersedia" }
        }
        android.util.Log.w("MuslRuntime", "gagal memasang runtime musl: $lastError")
        return null
    }

    private fun downloadBytes(url: String, limit: Int): ByteArray? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "arkacode-android")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    null
                } else {
                    val out = ByteArrayOutputStream()
                    var overflow = false
                    conn.inputStream.use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > limit) {
                                overflow = true
                                break
                            }
                        }
                    }
                    if (overflow) null else out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
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

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** Buang paket versi lama (dan sisa unduhan) agar cache tidak menumpuk. */
    private fun prune(dir: File, keep: List<String>) {
        val wanted = keep.toSet()
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name.endsWith(".part") || (file.name.endsWith(".apk") && file.name !in wanted)) {
                    file.delete()
                }
            }
        }
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

    // --------------------------------------------------------------- indeks paket

    /**
     * Baca `APKINDEX` dari `APKINDEX.tar.gz`.
     *
     * Isi tar cuma satu berkas teks bernama `APKINDEX` (+ tanda tangan).
     * null kalau arsipnya rusak / bukan tar.gz (mis. halaman error mirror).
     */
    fun parseIndexArchive(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.name == "APKINDEX" || entry.name.endsWith("/APKINDEX")) {
                        return@runCatching String(tar.readBytes(), Charsets.UTF_8)
                    }
                }
                null
            }
        }
    }.getOrNull()

    /**
     * Parse isi `APKINDEX` menjadi nama paket → entri terbaru.
     *
     * Blok dipisah baris kosong, satu field per baris berbentuk `P:nilai`
     * (`P` nama, `V` versi, `A` arch, `S` ukuran, `C` checksum).
     *
     * Murni (tanpa jaringan) — diuji di JVM.
     */
    fun parseApkIndex(text: String): Map<String, ApkEntry> {
        val out = LinkedHashMap<String, ApkEntry>()
        for (block in text.split("\n\n")) {
            var name: String? = null
            var version: String? = null
            var arch = ""
            var size = 0L
            var checksum: String? = null
            for (line in block.lineSequence()) {
                if (line.length < 3 || line[1] != ':') continue
                val value = line.substring(2).trim()
                when (line[0]) {
                    'P' -> name = value
                    'V' -> version = value
                    'A' -> arch = value
                    'S' -> size = value.toLongOrNull() ?: 0L
                    'C' -> checksum = value
                }
            }
            val entryName = name ?: continue
            val entryVersion = version ?: continue
            val entry = ApkEntry(entryName, entryVersion, arch, size, checksum)
            val previous = out[entryName]
            if (previous == null || compareVersions(entryVersion, previous.version) > 0) {
                out[entryName] = entry
            }
        }
        return out
    }

    /**
     * Bandingkan versi Alpine (`1.2.5-r4`, `13.2.1_git20240309-r1`).
     *
     * Cukup untuk memilih revisi terbaru: bagian numerik dipisah titik
     * dibandingkan berurutan, lalu angka `-rN` di ekor.
     */
    fun compareVersions(a: String, b: String): Int {
        fun split(version: String): Pair<String, Int> {
            val i = version.lastIndexOf("-r")
            return if (i >= 0) {
                version.substring(0, i) to (version.substring(i + 2).toIntOrNull() ?: 0)
            } else {
                version to 0
            }
        }
        val (upstreamA, revisionA) = split(a)
        val (upstreamB, revisionB) = split(b)
        val partsA = upstreamA.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val partsB = upstreamB.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val x = partsA.getOrElse(i) { 0 }
            val y = partsB.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return revisionA.compareTo(revisionB)
    }

    // --------------------------------------------------------------- format .apk

    /** Satu member gzip: isinya + rentang byte-nya di berkas asal. */
    private class GzipMember(val data: ByteArray, val start: Int, val end: Int)

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
        while (true) {
            val member = gzipMemberAt(bytes, off) ?: break
            result += member.data
            off = member.end
        }
        return result
    }

    /**
     * Checksum field `C:` milik indeks (`Q1` + base64 SHA-1 member control
     * terkompresi) — sama dengan yang diverifikasi `apk` saat memasang paket.
     *
     * Dipakai untuk paket yang namanya baru diketahui dari indeks (tidak
     * mungkin dipatok SHA256-nya di kode).
     */
    fun apkControlChecksum(bytes: ByteArray): String? {
        val signature = gzipMemberAt(bytes, 0) ?: return null
        val control = gzipMemberAt(bytes, signature.end) ?: return null
        val slice = bytes.copyOfRange(control.start, control.end)
        val digest = MessageDigest.getInstance("SHA-1").digest(slice)
        return "Q1" + Base64.getEncoder().encodeToString(digest)
    }

    /** Baca satu member gzip mulai [off]; null kalau bukan gzip / terpotong. */
    private fun gzipMemberAt(bytes: ByteArray, off: Int): GzipMember? {
        if (off + 10 > bytes.size) return null
        if (bytes[off] != 0x1f.toByte() || bytes[off + 1] != 0x8b.toByte()) return null

        val flg = bytes[off + 3].toInt() and 0xff
        var p = off + 10
        if (flg and 0x04 != 0) { // FEXTRA
            if (p + 2 > bytes.size) return null
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
        if (p > bytes.size) return null

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
            if (!inflater.finished()) return null
            // +8 = footer gzip (CRC32 + ISIZE) yang tidak dikonsumsi Inflater.
            val end = p + inflater.bytesRead.toInt() + 8
            if (end > bytes.size) return null
            return GzipMember(out.toByteArray(), off, end)
        } catch (e: Exception) {
            // Berkas rusak / terpotong: berhenti di member terakhir yang terbaca.
            return null
        } finally {
            inflater.end()
        }
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

    // ------------------------------------------------------------------ lainnya

    /** Cek magic ELF — menolak berkas kosong / halaman error mirror. */
    fun isElf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(head, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 && isElf(head)
        }
    }.getOrDefault(false)

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
}
