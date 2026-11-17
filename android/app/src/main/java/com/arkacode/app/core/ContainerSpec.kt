package com.arkacode.app.core

/**
 * Deskripsi satu "tool container": mini-rootfs Alpine 3.20 persistent milik
 * satu tool (python, node, …) yang di-bind ke `/opt/containers` di semua distro.
 *
 * Berbeda dari [DistroSpec], container bukan distro yang dipilih user sebagai
 * rootfs utama: ia hidup di `<filesDir>/containers/<id>/rootfs`, dipasang
 * sekali, lalu dipakai lewat shim di `/opt/containers/bin` — jadi tool tetap
 * ada walaupun user berganti distro.
 */
data class ContainerSpec(
    val id: String,
    val label: String,
    val summary: String,
    /** Perkiraan ukuran unduhan rootfs dalam MB — ditampilkan sebelum mengunduh. */
    val approxSizeMb: Int,
    /** Berkas di dalam rootfs yang membuktikan instalasi sudah lengkap. */
    val releaseMarkers: List<String>,
    /** Skrip sh yang dijalankan di dalam container untuk memasang tool (apk add …). */
    val setupScript: String,
    /** Binary yang dibungkus shim di `/opt/containers/bin` (`python3`, `node`, …). */
    val shimBinaries: List<String>,
    /** Kandidat URL rootfs untuk sebuah ABI (utama dulu, lalu mirror). */
    val rootfsUrls: (abi: String) -> List<String>,
) {
    /** Nama folder container di dalam folder data aplikasi. */
    val folderName: String get() = id

    /** Path rootfs di dalam guest, mis. `/opt/containers/python/rootfs`. */
    val guestRootfs: String get() = "/opt/containers/$id/rootfs"
}

/**
 * Katalog tool container.
 *
 * Semua container memakai rootfs yang sama: Alpine minirootfs 3.20.3 (branch
 * v3.20) dari CDN resmi Alpine + mirror — versi identik dengan distro default
 * ([DistroCatalog.ALPINE_320]) supaya library musl tidak bentrok.
 */
object ContainerCatalog {

    /** Versi minirootfs yang dipakai semua container. */
    const val ALPINE_VERSION = "3.20.3"

    /** Branch Alpine tempat minirootfs diambil. */
    const val ALPINE_BRANCH = "v3.20"

    /**
     * Python 3 + pip.
     *
     * Shim: `python3`, `pip3`. Skrip instal memasang `ca-certificates` supaya
     * `pip install` via HTTPS tidak gagal di mini-rootfs yang belum punya CA.
     */
    val PYTHON = ContainerSpec(
        id = "python",
        label = "Python 3",
        summary = "Interpreter python3 + pip untuk script dan paket Python.",
        approxSizeMb = 4,
        releaseMarkers = listOf("usr/bin/python3", "usr/bin/pip3"),
        setupScript = """
            #!/bin/sh
            set -e
            apk add --no-cache python3 py3-pip ca-certificates
        """.trimIndent(),
        shimBinaries = listOf("python3", "pip3"),
        rootfsUrls = rootfsUrls(),
    )

    /**
     * Node.js + npm.
     *
     * Shim: `node`, `npm`, `npx`. Sama seperti Python, `ca-certificates`
     * dipasang supaya `npm install` via HTTPS jalan.
     */
    val NODE = ContainerSpec(
        id = "node",
        label = "Node.js",
        summary = "Runtime node + npm/npx untuk tool berbasis JavaScript.",
        approxSizeMb = 4,
        releaseMarkers = listOf("usr/bin/node", "usr/bin/npm"),
        setupScript = """
            #!/bin/sh
            set -e
            apk add --no-cache nodejs npm ca-certificates
        """.trimIndent(),
        shimBinaries = listOf("node", "npm", "npx"),
        rootfsUrls = rootfsUrls(),
    )

    /** Semua container yang bisa dipasang. */
    val ALL: List<ContainerSpec> = listOf(PYTHON, NODE)

    /** Container berdasarkan id; default [PYTHON] untuk nilai tak dikenal. */
    fun byId(id: String?): ContainerSpec =
        ALL.firstOrNull { it.id == id } ?: PYTHON

    /** ABI Android → arsitektur Alpine; null untuk ABI yang tidak didukung. */
    fun archFor(abi: String): String? = when (abi.lowercase()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }

    /**
     * Nama file loader musl untuk ABI tersebut, mis. `ld-musl-aarch64.so.1`.
     *
     * Nama arsitektur loader berbeda dari arsitektur rootfs untuk arm 32-bit
     * (`armhf`) dan x86 32-bit (`i386`).
     */
    fun loaderName(abi: String): String? {
        val loaderArch = when (archFor(abi)) {
            "aarch64" -> "aarch64"
            "armv7" -> "armhf"
            "x86_64" -> "x86_64"
            "x86" -> "i386"
            else -> null
        } ?: return null
        return "ld-musl-$loaderArch.so.1"
    }

    /** Kandidat URL minirootfs Alpine untuk sebuah ABI (CDN utama, lalu mirror). */
    private fun rootfsUrls(): (String) -> List<String> = { abi ->
        val arch = archFor(abi)
        if (arch == null) {
            emptyList()
        } else {
            val file = "alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
            val rel = "$ALPINE_BRANCH/releases/$arch/$file"
            buildList {
                add("${DistroCatalog.CDN}/$rel")
                DistroCatalog.MIRRORS.forEach { add("$it/$rel") }
            }
        }
    }
}