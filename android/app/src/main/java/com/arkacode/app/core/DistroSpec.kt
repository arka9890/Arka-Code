package com.arkacode.app.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Cara command dieksekusi di perangkat.
 *
 * - [PROOT] — di dalam rootfs Linux asli yang dijalankan lewat proot (tanpa root).
 *   Rootfs diunduh sendiri oleh app (Alpine, Ubuntu, Debian, …).
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
 * Cara arsip rootfs dikemas dan ditata di dalam folder rootfs.
 *
 *Sumber rootfs berbeda-beda bentuknya, jadi cara membongkarnya juga berbeda:
 */
enum class RootfsLayout {
    /** Tarball datar: isi arsip langsung = rootfs (Alpine, ubuntu-base). */
    FLAT,

    /** Tarball dengan satu folder teratas yang harus dilepas (proot-distro). */
    STRIP_TOP_DIR,

    /**
     * Zip berisi isi `$PREFIX` Termux (bootstrap resmi Termux).
     *
     * Prefix-nya harus diletakkan persis di
     * `/data/data/com.termux/files/usr` di dalam guest, karena binary Termux
     * punya RUNPATH absolut ke sana dan PT_INTERP-nya `/system/bin/linker64`
     * (pustaka bionic Android).
     */
    TERMUX_PREFIX,
}

/**
 * Deskripsi satu distro yang bisa dipakai sebagai rootfs di dalam proot.
 *
 * Rootfs hanya perlu tiga hal: URL untuk diunduh, penanda berkas ("etc/os-release")
 * yang membuktikan arsipnya memang distro tersebut, dan tata letak arsipnya.
 */
data class DistroSpec(
    val id: String,
    val label: String,
    val summary: String,
    /** Package manager distro ini ("apk", "apt", "pacman", …) — ditampilkan di UI. */
    val packageManager: String,
    /** Berkas yang harus ada di rootfs supaya dianggap terpasang. */
    val releaseMarkers: List<String>,
    /** Perkiraan ukuran unduhan dalam MB — ditampilkan sebelum mengunduh. */
    val approxSizeMb: Int,
    /** Kelompok untuk tampilan (Alpine, Ubuntu, Debian, …). */
    val group: String = "Lain-lain",
    /** true untuk distro "URL sendiri" (user yang menyediakan rootfs). */
    val custom: Boolean = false,
    /** Cara membongkar arsipnya. */
    val layout: RootfsLayout = RootfsLayout.FLAT,
    /**
     * Folder di dalam rootfs tempat isi arsip diletakkan.
     * Kosong = arsip diletakkan langsung di rootfs.
     * Untuk Termux: `data/data/com.termux/files/usr`.
     */
    val extractDir: String = "",
    /**
     * Bind tambahan ke dalam guest.
     * Termux butuh `/system` + `/apex` + `/vendor` karena binary-nya pakai
     * bionic Android (`/system/bin/linker64`).
     */
    val extraBinds: List<String> = emptyList(),
    /**
     * ABI yang punya rootfs resmi. null = semua ABI.
     * Distro yang tidak mendukung ABI perangkat ini ditandai di UI.
     */
    val availableAbis: Set<String>? = null,
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

    /** Path prefix di dalam guest (untuk Termux: `/data/data/com.termux/files/usr`). */
    val guestPrefix: String get() = if (extractDir.isEmpty()) "" else "/$extractDir"

    /** Apakah rootfs ini tersedia untuk ABI perangkat tertentu? */
    fun supports(abi: String): Boolean {
        val list = availableAbis ?: return true
        return list.any { it.equals(abi, ignoreCase = true) }
    }
}

/**
 * Katalog distro yang bisa dipilih pengguna.
 *
 * Sumber rootfs:
 *  - Alpine      → CDN resmi Alpine (minirootfs, ±4 MB).
 *  - Ubuntu      → `cdimage.ubuntu.com` (ubuntu-base resmi Canonical).
 *  - Debian/Fedora/Arch/openSUSE/… → release `termux/proot-distro`
 *    (rootfs siap pakai yang sama dipakai proot-distro).
 *  - Termux      → bootstrap resmi Termux (userland Termux asli).
 *
 * Selain itu ada "custom": user sendiri yang menunjuk URL atau berkas rootfs.
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

    /** Basis URL release proot-distro (rootfs siap pakai multi-distro). */
    const val PROOT_DISTRO = "https://github.com/termux/proot-distro/releases/download"

    /** Basis URL bootstrap Termux (selalu versi terbaru). */
    const val TERMUX_BOOTSTRAP = "https://github.com/termux/termux-packages/releases/latest/download"

    /** Basis URL ubuntu-base resmi Canonical. */
    const val UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases"

    // ------------------------------------------------------------- pemetaan ABI

    /** ABI Android → arsitektur Alpine. */
    fun archFor(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "aarch64"
    }

    /** ABI Android → arsitektur proot-distro. */
    fun pdArchFor(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    /** ABI Android → arsitektur ubuntu-base. */
    fun ubuntuArchFor(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        "x86" -> "i386"
        else -> "arm64"
    }

    /** ABI Android → arsitektur bootstrap Termux. */
    fun termuxArchFor(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    // ------------------------------------------------------------------ Alpine

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
        group = "Alpine",
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

    // ------------------------------------------------------------------ Ubuntu

    private fun ubuntuBase(
        release: String,
        version: String,
        label: String,
        summary: String,
    ): DistroSpec = DistroSpec(
        id = "ubuntu-$release",
        label = label,
        summary = summary,
        packageManager = "apt",
        releaseMarkers = listOf("etc/os-release"),
        approxSizeMb = 30,
        group = "Ubuntu",
        rootfsUrls = { abi ->
            val arch = ubuntuArchFor(abi)
            listOf("$UBUNTU_BASE/$release/release/ubuntu-base-$version-base-$arch.tar.gz")
        },
        resolveLatestUrls = { abi -> resolveUbuntuBaseLatest(release, abi) },
    )

    /** Ubuntu 24.04 LTS — image resmi Canonical (ubuntu-base). */
    val UBUNTU_2404 = ubuntuBase(
        release = "24.04",
        version = "24.04.5",
        label = "Ubuntu 24.04 LTS (Noble)",
        summary = "Rilis resmi Canonical. Paket manager apt, glibc — paling lengkap.",
    )

    /** Ubuntu 22.04 LTS — image resmi Canonical, lebih ringan. */
    val UBUNTU_2204 = ubuntuBase(
        release = "22.04",
        version = "22.04.5",
        label = "Ubuntu 22.04 LTS (Jammy)",
        summary = "LTS lama, basis lebih ringan dan masih LTS sampai 2027.",
    )

    // ------------------------------------------------------------ proot-distro

    /**
     * Rootfs siap pakai dari release `termux/proot-distro`.
     *
     * Format nama asset: `<asset>-<arch>-pd-<tag>.tar.xz`, dan isinya punya
     * satu folder teratas yang harus dilepas saat ekstraksi.
     */
    private fun prootDistro(
        id: String,
        label: String,
        summary: String,
        packageManager: String,
        asset: String,
        tag: String,
        approxSizeMb: Int,
        group: String,
        availableAbis: Set<String>,
    ): DistroSpec = DistroSpec(
        id = id,
        label = label,
        summary = summary,
        packageManager = packageManager,
        releaseMarkers = listOf("etc/os-release"),
        approxSizeMb = approxSizeMb,
        group = group,
        layout = RootfsLayout.STRIP_TOP_DIR,
        availableAbis = availableAbis,
        rootfsUrls = { abi ->
            val arch = pdArchFor(abi)
            listOf("$PROOT_DISTRO/$tag/$asset-$arch-pd-$tag.tar.xz")
        },
    )

    private val ALL_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    private val ABI_64 = setOf("arm64-v8a", "x86_64")

    /** Debian 13 "trixie" — rootfs dari proot-distro. */
    val DEBIAN_TRIXIE = prootDistro(
        id = "debian-trixie",
        label = "Debian 13 (trixie)",
        summary = "Debian stabil terbaru, basis glibc. Paket manager apt.",
        packageManager = "apt",
        asset = "debian-trixie",
        tag = "v4.29.0",
        approxSizeMb = 35,
        group = "Debian",
        availableAbis = ALL_ABIS,
    )

    /** Debian 12 "bookworm" — rootfs dari proot-distro. */
    val DEBIAN_BOOKWORM = prootDistro(
        id = "debian-bookworm",
        label = "Debian 12 (bookworm)",
        summary = "Debian stabil lama, paket lebih stabil.",
        packageManager = "apt",
        asset = "debian-bookworm",
        tag = "v4.17.3",
        approxSizeMb = 35,
        group = "Debian",
        availableAbis = ALL_ABIS,
    )

    /** Fedora — rootfs dari proot-distro. */
    val FEDORA = prootDistro(
        id = "fedora",
        label = "Fedora",
        summary = "Distro modern dari Red Hat. Paket manager dnf.",
        packageManager = "dnf",
        asset = "fedora",
        tag = "v4.31.0",
        approxSizeMb = 34,
        group = "Lain-lain",
        availableAbis = ABI_64,
    )

    /** Arch Linux — rootfs dari proot-distro. */
    val ARCHLINUX = prootDistro(
        id = "archlinux",
        label = "Arch Linux",
        summary = "Rolling release, paket paling baru. Paket manager pacman.",
        packageManager = "pacman",
        asset = "archlinux",
        tag = "v4.34.2",
        approxSizeMb = 150,
        group = "Lain-lain",
        availableAbis = ALL_ABIS,
    )

    /** openSUSE — rootfs dari proot-distro. */
    val OPENSUSE = prootDistro(
        id = "opensuse",
        label = "openSUSE",
        summary = "Distro dari SUSE, package manager zypper. Rootfs-nya besar (±150 MB).",
        packageManager = "zypper",
        asset = "opensuse",
        tag = "v4.34.2",
        approxSizeMb = 150,
        group = "Lain-lain",
        availableAbis = ABI_64,
    )

    /** Void Linux — rootfs dari proot-distro. */
    val VOID = prootDistro(
        id = "void",
        label = "Void Linux",
        summary = "Rolling release yang sangat ramping, package manager xbps.",
        packageManager = "xbps",
        asset = "void",
        tag = "v4.29.0",
        approxSizeMb = 40,
        group = "Lain-lain",
        availableAbis = ALL_ABIS,
    )

    /** Rocky Linux — rootfs dari proot-distro. */
    val ROCKY = prootDistro(
        id = "rocky",
        label = "Rocky Linux",
        summary = "Clone RHEL Enterprise, stabil untuk server. dnf.",
        packageManager = "dnf",
        asset = "rocky",
        tag = "v4.29.0",
        approxSizeMb = 40,
        group = "Lain-lain",
        availableAbis = ABI_64,
    )

    /** AlmaLinux — rootfs dari proot-distro. */
    val ALMALINUX = prootDistro(
        id = "almalinux",
        label = "AlmaLinux",
        summary = "Alternatif RHEL yang Ramah komunitas. dnf.",
        packageManager = "dnf",
        asset = "almalinux",
        tag = "v4.31.0",
        approxSizeMb = 40,
        group = "Lain-lain",
        availableAbis = ABI_64,
    )

    /** Manjaro — rootfs dari proot-distro. */
    val MANJARO = prootDistro(
        id = "manjaro",
        label = "Manjaro",
        summary = "Turunan Arch yang ramah newbie, sudah punya GUI tools.",
        packageManager = "pacman",
        asset = "manjaro",
        tag = "v4.34.2",
        approxSizeMb = 90,
        group = "Lain-lain",
        availableAbis = setOf("arm64-v8a"),
    )

    /** Deepin — rootfs dari proot-distro. */
    val DEEPIN = prootDistro(
        id = "deepin",
        label = "Deepin",
        summary = "Distro China dengan tool lengkap, basis Debian.",
        packageManager = "apt",
        asset = "deepin",
        tag = "v4.29.0",
        approxSizeMb = 45,
        group = "Lain-lain",
        availableAbis = ABI_64,
    )

    /** Ubuntu 25.04 — rootfs dari proot-distro (lebih baru dari LTS). */
    val UBUNTU_PLUCKY = prootDistro(
        id = "ubuntu-plucky",
        label = "Ubuntu 25.04 (Plucky)",
        summary = "Rilis Interim terbaru, paket paling mutakhir.",
        packageManager = "apt",
        asset = "ubuntu-plucky",
        tag = "v4.29.0",
        approxSizeMb = 55,
        group = "Ubuntu",
        availableAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64"),
    )

    // ------------------------------------------------------------------ Termux

    /**
     * Termux bawaan (built-in).
     *
     * Userland Termux asli — `bash`, `pkg`, `apt`, `curl`, `python` (via pkg) —
     * dijalankan lewat proot TANPA perlu memasang aplikasi Termux.
     *
     * Binary Termux dibuat untuk bionic Android, jadi:
     *  - prefix harus persis `/data/data/com.termux/files/usr` di dalam guest
     *    (RUNPATH absolut di binarynya),
     *  - `/system`, `/apex`, `/vendor` di-bind ke guest (PT_INTERP-nya
     *    `/system/bin/linker64`).
     *
     * Arsipnya berformat zip dan symlink-nya disimpan terpisah di
     * `SYMLINKS.txt` (format `target←linkpath`), jadi perlu penanganan khusus.
     */
    val TERMUX_BUILTIN = DistroSpec(
        id = "termux",
        label = "Termux (built-in)",
        summary = "Userland Termux asli (bash, pkg, apt) — tanpa perlu pasang app Termux.",
        packageManager = "pkg",
        releaseMarkers = listOf("data/data/com.termux/files/usr/bin/bash"),
        approxSizeMb = 33,
        group = "Termux",
        layout = RootfsLayout.TERMUX_PREFIX,
        extractDir = "data/data/com.termux/files/usr",
        extraBinds = listOf("/system", "/apex", "/vendor"),
        rootfsUrls = { abi ->
            listOf("$TERMUX_BOOTSTRAP/bootstrap-${termuxArchFor(abi)}.zip")
        },
    )

    // ------------------------------------------------------------------ custom

    /**
     * Rootfs sendiri — URL dari user (Debian/Ubuntu/Fedora/apa pun) atau
     * berkas .tar.gz/.tar.xz yang dipilih lewat SAF di layar Files.
     */
    val CUSTOM = DistroSpec(
        id = "custom",
        label = "Rootfs sendiri (URL / berkas)",
        summary = "Pakai rootfs apa pun: URL .tar.gz/.tar.xz/.zip atau berkas dari penyimpanan.",
        packageManager = "-",
        releaseMarkers = listOf("etc/os-release", "bin/sh", "bin/bash"),
        approxSizeMb = 0,
        group = "Kustom",
        custom = true,
        rootfsUrls = { emptyList() },
    )

    /** Semua preset (tanpa [CUSTOM], yang butuh URL dari user). */
    val PRESETS: List<DistroSpec> = listOf(
        ALPINE_320, ALPINE_321, ALPINE_322, ALPINE_EDGE,
        UBUNTU_2404, UBUNTU_2204, UBUNTU_PLUCKY,
        DEBIAN_TRIXIE, DEBIAN_BOOKWORM,
        TERMUX_BUILTIN,
        FEDORA, ARCHLINUX, OPENSUSE, VOID, ROCKY, ALMALINUX, MANJARO, DEEPIN,
    )

    /** Semua pilihan, termasuk [CUSTOM]. */
    val ALL: List<DistroSpec> = PRESETS + CUSTOM

    /** Urutan kelompok untuk tampilan di UI. */
    val GROUP_ORDER: List<String> = listOf(
        "Alpine", "Ubuntu", "Debian", "Termux", "Lain-lain", "Kustom",
    )

    fun byId(id: String?): DistroSpec =
        ALL.firstOrNull { it.id == id } ?: ALPINE_320

    /** Preset dikelompokkan sesuai urutan [GROUP_ORDER]. */
    fun grouped(): List<Pair<String, List<DistroSpec>>> =
        GROUP_ORDER.mapNotNull { group ->
            val items = ALL.filter { it.group == group }
            if (items.isEmpty()) null else group to items
        }

    // ------------------------------------------------------------- resolusi URL

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
            val newest = parseLatestVersion(html, alpinePattern(arch)) ?: continue
            val file = "alpine-minirootfs-$newest-$arch.tar.gz"
            return buildList {
                add("$base/$rel$file")
                bases.forEach { add("$it/$rel$file") }
            }
        }
        return emptyList()
    }

    /** Cari versi ubuntu-base terbaru untuk sebuah rilis Ubuntu. */
    suspend fun resolveUbuntuBaseLatest(release: String, abi: String): List<String> {
        val arch = ubuntuArchFor(abi)
        val rel = "$release/release/"
        val html = fetchText("$UBUNTU_BASE/$rel") ?: return emptyList()
        val newest = parseLatestVersion(html, ubuntuBasePattern(arch)) ?: return emptyList()
        val file = "ubuntu-base-$newest-base-$arch.tar.gz"
        return listOf("$UBUNTU_BASE/$rel$file")
    }

    /** Regex nama file minirootfs Alpine, mis. `alpine-minirootfs-3.20.10-aarch64.tar.gz`. */
    fun alpinePattern(arch: String): Regex =
        Regex("alpine-minirootfs-(\\d+(?:\\.\\d+)*)-${Regex.escape(arch)}\\.tar\\.gz")

    /** Regex nama file ubuntu-base, mis. `ubuntu-base-24.04.5-base-arm64.tar.gz`. */
    fun ubuntuBasePattern(arch: String): Regex =
        Regex("ubuntu-base-(\\d+(?:\\.\\d+)*)-base-${Regex.escape(arch)}\\.tar\\.gz")

    /**
     * Parse indeks direktori CDN dan ambil versi tertinggi.
     *
     * Dipisah dari [resolveAlpineLatest] supaya bisa diuji tanpa jaringan.
     * Versi rc/beta dibuang — hanya versi stabil yang dipakai.
     *
     * @param regex harus punya satu grup rekam: versi (mis. "3.20.10").
     */
    fun parseLatestVersion(html: String, regex: Regex): String? =
        regex.findAll(html)
            .map { it.groupValues.getOrElse(1) { "" } }
            .filter { v -> v.isNotEmpty() && v.none { ch -> ch.isLetter() } } // buang rc/beta
            .distinct()
            // Urut secara numerik: "3.20.10" harus lebih besar dari "3.20.9".
            // Dipakai kunci string nol-terisi supaya tetap Comparable.
            .sortedBy { v ->
                v.split('.').joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(8, '0') }
            }
            .lastOrNull()

    private fun fetchText(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Arkacode-Android")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    null
                } else {
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
