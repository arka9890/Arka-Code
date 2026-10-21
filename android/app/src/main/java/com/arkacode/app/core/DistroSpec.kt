package com.arkacode.app.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Cara command dieksekusi di perangkat.
 *
 * - [PROOT] — di dalam rootfs Linux asli yang dijalankan lewat proot (tanpa root).
 *   Rootfs Alpine (atau distro lain) diunduh sendiri oleh app.
 * - [TERMUX] — langsung di environment Termux yang sudah terpasang di perangkat.
 *   Tidak butuh rootfs/unduhan apa pun, tapi Termux harus sudah terpasang dan
 *   mengizinkan eksekusi script dari app lain.
 */
enum class RuntimeMode {
    PROOT,
    TERMUX;

    companion object {
        fun from(raw: String?): RuntimeMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PROOT
    }
}

/**
 * Deskripsi satu distro yang bisa dipakai sebagai rootfs di dalam proot.
 *
 * Rootfs hanya perlu dua hal: sebuah URL untuk diunduh, dan satu penanda
 * berkas ("etc/alpine-release") yang membuktikan arsipnya memang distro
 * tersebut — bukan hasil unduhan yang korup.
 */
data class DistroSpec(
    val id: String,
    val label: String,
    val summary: String,
    /** Package manager distro ini ("apk", "apt", …) — untuk ditampilkan di UI. */
    val packageManager: String,
    /** Berkas/berkas yang harus ada di rootfs supaya dianggap terpasang. */
    val releaseMarkers: List<String>,
    /** Perkiraan ukuran unduhan dalam MB — ditampilkan sebelum mengunduh. */
    val approxSizeMb: Int,
    /** true untuk distro "URL sendiri" (user yang menyediakan rootfs). */
    val custom: Boolean = false,
    /** Kandidat URL rootfs untuk sebuah ABI (utama dulu, lalu mirror). */
    val rootfsUrls: (abi: String) -> List<String>,
    /**
     * URL fallback ketika semua kandidat di atas hilang (mis. versi patch Alpine
     * yang sudah dihapus). Null = tidak ada fallback.
     */
    val resolveLatestUrls: (suspend (abi: String) -> List<String>)? = null,
) {
    /** Nama folder rootfs di dalam folder data aplikasi. */
    val folderName: String get() = id
}

/**
 * Katalog distro yang bisa dipilih pengguna.
 *
 * Alpine jadi pilihan utama karena rootfs-nya kecil (±4 MB), `apk` cepat, dan
 * musl cocok dengan binary opencode yang diunduh. Sisanya untuk yang butuh
 * basis Debian/Ubuntu: [CUSTOM] menerima URL rootfs apa pun (atau berkas
 * .tar.gz/.tar.xz lokal lewat SAF), jadi user tidak terkunci ke Alpine.
 */
object DistroCatalog {

    const val CDN = "https://dl-cdn.alpinelinux.org/alpine"

    /** Mirror Alpine — dipakai kalau CDN utama tidak bisa dijangkau perangkat. */
    val MIRRORS = listOf(
        "https://mirror.leaseweb.com/alpine",
        "https://ftp.halifax.rwth-aachen.de/alpine",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://mirrors.ustc.edu.cn/alpine",
    )

    /** Pemetaan ABI Android → arsitektur Alpine. */
    fun archFor(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "aarch64"
    }

    private fun alpine(
        branch: String,
        version: String,
        label: String,
        summary: String,
    ): DistroSpec = DistroSpec(
        id = "alpine-${branch.removePrefix("v")}",
        label = label,
        summary = summary,
        packageManager = "apk",
        releaseMarkers = listOf("etc/alpine-release"),
        approxSizeMb = 4,
        rootfsUrls = { abi ->
            val arch = archFor(abi)
            val file = "alpine-minirootfs-$version-$arch.tar.gz"
            val rel = "$branch/releases/$arch/$file"
            buildList {
                add("$CDN/$rel")
                MIRRORS.forEach { add("$it/$rel") }
            }
        },
        resolveLatestUrls = { abi -> resolveAlpineLatest(branch, abi) },
    )

    /** Alpine 3.20 — branch LTS, paling stabil. */
    val ALPINE_320 = alpine(
        branch = "v3.20",
        version = "3.20.3",
        label = "Alpine Linux 3.20 (LTS)",
        summary = "Paling ringan & stabil. Paket manager apk, cocok untuk tool AI.",
    )

    /** Alpine 3.21 — branch stabil yang lebih baru. */
    val ALPINE_321 = alpine(
        branch = "v3.21",
        version = "3.21.8",
        label = "Alpine Linux 3.21",
        summary = "Cabang stabil terbaru, paket lebih baru daripada 3.20.",
    )

    /** Alpine 3.22 — cabang stabil mutakhir. */
    val ALPINE_322 = alpine(
        branch = "v3.22",
        version = "3.22.6",
        label = "Alpine Linux 3.22",
        summary = "Paket lebih baru lagi; butuh opencode >= 2.0.31.",
    )

    /** Alpine edge — rolling release, paket paling baru tapi bisa rewel. */
    val ALPINE_EDGE = alpine(
        branch = "edge",
        version = "20260805",
        label = "Alpine edge (rolling)",
        summary = "Rilisan bergulir: paket terbaru, tapi bisa saja rewel.",
    )

    /**
     * Rootfs sendiri — URL dari user (Debian/Ubuntu/Fedora/apa pun) atau
     * berkas .tar.gz/.tar.xz yang dipilih lewat SAF di layar Files.
     */
    val CUSTOM = DistroSpec(
        id = "custom",
        label = "Rootfs sendiri (URL / berkas)",
        summary = "Pakai rootfs apa pun: URL .tar.gz/.tar.xz atau berkas dari penyimpanan.",
        packageManager = "-",
        releaseMarkers = listOf("etc/os-release", "bin/sh", "bin/bash"),
        approxSizeMb = 0,
        custom = true,
        rootfsUrls = { emptyList() },
    )

    /** Semua preset (tanpa [CUSTOM], yang butuh URL dari user). */
    val PRESETS: List<DistroSpec> = listOf(ALPINE_320, ALPINE_321, ALPINE_322, ALPINE_EDGE)

    /** Semua pilihan, termasuk [CUSTOM]. */
    val ALL: List<DistroSpec> = PRESETS + CUSTOM

    fun byId(id: String?): DistroSpec =
        ALL.firstOrNull { it.id == id } ?: ALPINE_320

    /**
     * Cari versi minirootfs terbaru untuk sebuah branch Alpine.
     *
     * Dipakai kalau URL yang di-pin sudah hilang (Alpine sesekali menghapus
     * versi lama). Daftar direktori CDN diparse, lalu versi tertinggi dipilih.
     * Mengembalikan kosong kalau jaringan/CDN tidak bisa dijangkau.
     */
    suspend fun resolveAlpineLatest(branch: String, abi: String): List<String> {
        val arch = archFor(abi)
        val rel = "$branch/releases/$arch/"
        val bases = buildList {
            add(CDN)
            MIRRORS.forEach { add(it) }
        }
        for (base in bases) {
            val html = fetchText("$base/$rel") ?: continue
            val newest = parseLatestVersion(html, arch) ?: continue
            val file = "alpine-minirootfs-$newest-$arch.tar.gz"
            return buildList {
                add("$base/$rel$file")
                bases.forEach { add("$it/$rel$file") }
            }
        }
        return emptyList()
    }

    /**
     * Parse indeks direktori CDN dan ambil versi minirootfs tertinggi.
     *
     * Dipisah dari [resolveAlpineLatest] supaya bisa diuji tanpa jaringan.
     * Versi rc/beta dibuang — hanya versi stabil yang dipakai.
     */
    fun parseLatestVersion(html: String, arch: String): String? {
        val pattern = Regex("alpine-minirootfs-(\\d+(?:\\.\\d+)*)-$arch\\.tar\\.gz")
        return pattern.findAll(html)
            .map { it.groupValues[1] }
            .filter { v -> v.none { ch -> ch.isLetter() } } // buang rc/beta
            .distinct()
            .sortedBy { v -> v.split('.').map { it.toIntOrNull() ?: 0 } }
            .lastOrNull()
    }

    private fun fetchText(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Arkacode-Android")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
