package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes katalog distro: pemetaan ABI, URL rootfs, tata letak arsip, dan
 * parsing versi.
 *
 * Fungsi-fungsi di sini murni (tanpa jaringan) supaya bisa diuji di JVM.
 */
class DistroSpecTest {

    @Test
    fun `abi dipetakan ke arsitektur alpine yang benar`() {
        assertEquals("aarch64", DistroCatalog.archFor("arm64-v8a"))
        assertEquals("armv7", DistroCatalog.archFor("armeabi-v7a"))
        assertEquals("x86_64", DistroCatalog.archFor("x86_64"))
        assertEquals("x86", DistroCatalog.archFor("x86"))
        // ABI yang tidak dikenal -> default aarch64
        assertEquals("aarch64", DistroCatalog.archFor("mips"))
    }

    @Test
    fun `abi dipetakan ke arsitektur proot-distro dan ubuntu`() {
        assertEquals("aarch64", DistroCatalog.pdArchFor("arm64-v8a"))
        assertEquals("arm", DistroCatalog.pdArchFor("armeabi-v7a"))
        assertEquals("x86_64", DistroCatalog.pdArchFor("x86_64"))
        assertEquals("i686", DistroCatalog.pdArchFor("x86"))

        assertEquals("arm64", DistroCatalog.ubuntuArchFor("arm64-v8a"))
        assertEquals("armhf", DistroCatalog.ubuntuArchFor("armeabi-v7a"))
        assertEquals("amd64", DistroCatalog.ubuntuArchFor("x86_64"))
        assertEquals("i386", DistroCatalog.ubuntuArchFor("x86"))

        assertEquals("aarch64", DistroCatalog.termuxArchFor("arm64-v8a"))
        assertEquals("arm", DistroCatalog.termuxArchFor("armeabi-v7a"))
        assertEquals("x86_64", DistroCatalog.termuxArchFor("x86_64"))
        assertEquals("i686", DistroCatalog.termuxArchFor("x86"))
    }

    @Test
    fun `byId mengembalikan default untuk id tak dikenal`() {
        assertEquals(DistroCatalog.ALPINE_320.id, DistroCatalog.byId(null).id)
        assertEquals(DistroCatalog.ALPINE_320.id, DistroCatalog.byId("nope").id)
        assertEquals(DistroCatalog.ALPINE_321.id, DistroCatalog.byId("alpine-3.21").id)
        assertEquals(DistroCatalog.CUSTOM.id, DistroCatalog.byId("custom").id)
        assertEquals(DistroCatalog.UBUNTU_2404.id, DistroCatalog.byId("ubuntu-24.04").id)
        assertEquals(DistroCatalog.DEBIAN_TRIXIE.id, DistroCatalog.byId("debian-trixie").id)
        assertEquals(DistroCatalog.TERMUX_BUILTIN.id, DistroCatalog.byId("termux").id)
    }

    @Test
    fun `url rootfs alpine memakai arsitektur sesuai abi`() {
        val urls = DistroCatalog.ALPINE_320.rootfsUrls("arm64-v8a")
        assertTrue("harus ada URL CDN", urls.first().startsWith(DistroCatalog.CDN))
        assertTrue("harus mengandung aarch64", urls.first().contains("aarch64"))

        val x86 = DistroCatalog.ALPINE_320.rootfsUrls("x86_64")
        assertTrue(x86.first().contains("x86_64"))
        assertTrue("tidak boleh mengandung aarch64", !x86.first().contains("aarch64"))
    }

    @Test
    fun `setiap distro alpine punya url utama dan mirror fallback`() {
        DistroCatalog.PRESETS.filter { it.group == "Alpine" }.forEach { spec ->
            val urls = spec.rootfsUrls("arm64-v8a")
            assertTrue("${spec.id} harus punya >1 kandidat", urls.size > 1)
            assertEquals("${spec.id} kandidat harus unik", urls.size, urls.distinct().size)
            urls.forEach { u ->
                assertTrue("${spec.id} URL harus https", u.startsWith("https://"))
            }
        }
    }

    @Test
    fun `setiap preset punya minimal satu url rootfs`() {
        DistroCatalog.PRESETS.forEach { spec ->
            val urls = spec.rootfsUrls("arm64-v8a")
            assertTrue("${spec.id} harus punya URL", urls.isNotEmpty())
            assertTrue("${spec.id} URL harus unik", urls.size == urls.distinct().size)
            urls.forEach { u -> assertTrue("${spec.id} URL harus https", u.startsWith("https://")) }
        }
    }

    @Test
    fun `url ubuntu memakai ubuntu-base resmi canonical`() {
        val url = DistroCatalog.UBUNTU_2404.rootfsUrls("arm64-v8a").first()
        assertTrue(url, url.startsWith(DistroCatalog.UBUNTU_BASE))
        assertTrue(url, url.contains("24.04"))
        assertTrue(url, url.contains("arm64"))
        // x86_64 harus pakai amd64, bukan aarch64
        assertTrue(DistroCatalog.UBUNTU_2404.rootfsUrls("x86_64").first().contains("amd64"))
    }

    @Test
    fun `url proot-distro mengikuti pola asset resmi`() {
        val url = DistroCatalog.DEBIAN_TRIXIE.rootfsUrls("arm64-v8a").first()
        assertTrue(url, url.startsWith(DistroCatalog.PROOT_DISTRO))
        assertTrue(url, url.contains("debian-trixie-aarch64-pd-v4.29.0.tar.xz"))
        // arm harus jadi "arm", bukan "armv7"
        assertTrue(DistroCatalog.DEBIAN_TRIXIE.rootfsUrls("armeabi-v7a").first().contains("debian-trixie-arm-"))
    }

    @Test
    fun `url bootstrap termux memakai release terbaru dan arsip per abi`() {
        val url = DistroCatalog.TERMUX_BUILTIN.rootfsUrls("arm64-v8a").first()
        assertTrue(url, url.startsWith(DistroCatalog.TERMUX_BOOTSTRAP))
        assertTrue(url, url.endsWith("bootstrap-aarch64.zip"))
        assertTrue(DistroCatalog.TERMUX_BUILTIN.rootfsUrls("x86").first().endsWith("bootstrap-i686.zip"))
    }

    @Test
    fun `layout arsip sesuai jenis sumber rootfs`() {
        // Alpine & ubuntu-base: tarball datar
        assertEquals(RootfsLayout.FLAT, DistroCatalog.ALPINE_320.layout)
        assertEquals(RootfsLayout.FLAT, DistroCatalog.UBUNTU_2404.layout)
        // proot-distro: ada satu folder teratas yang harus dilepas
        assertEquals(RootfsLayout.STRIP_TOP_DIR, DistroCatalog.DEBIAN_TRIXIE.layout)
        assertEquals(RootfsLayout.STRIP_TOP_DIR, DistroCatalog.FEDORA.layout)
        // Termux: zip berisi isi prefix
        assertEquals(RootfsLayout.TERMUX_PREFIX, DistroCatalog.TERMUX_BUILTIN.layout)
    }

    @Test
    fun `termux built-in menaruh prefix di lokasi yang dipakai RUNPATH`() {
        val spec = DistroCatalog.TERMUX_BUILTIN
        // RUNPATH binary Termux absolut ke /data/data/com.termux/files/usr/lib
        assertEquals("/data/data/com.termux/files/usr", spec.guestPrefix)
        assertEquals("data/data/com.termux/files/usr", spec.extractDir)
        // butuh bind Android untuk bionic (linker64)
        assertTrue(spec.extraBinds.contains("/system"))
        assertTrue(spec.extraBinds.contains("/apex"))
        // penanda terpasang
        assertTrue(spec.releaseMarkers.contains("data/data/com.termux/files/usr/bin/bash"))
    }

    @Test
    fun `distro tanpa prefix punya prefix kosong`() {
        assertEquals("", DistroCatalog.ALPINE_320.guestPrefix)
        assertEquals("", DistroCatalog.UBUNTU_2404.guestPrefix)
        assertTrue(DistroCatalog.ALPINE_320.extraBinds.isEmpty())
    }

    @Test
    fun `ketersediaan abi per distro dilaporkan dengan benar`() {
        // Fedora hanya ada untuk 64-bit
        assertTrue(DistroCatalog.FEDORA.supports("arm64-v8a"))
        assertTrue(DistroCatalog.FEDORA.supports("x86_64"))
        assertFalse(DistroCatalog.FEDORA.supports("armeabi-v7a"))
        assertFalse(DistroCatalog.FEDORA.supports("x86"))
        // Manjaro hanya aarch64
        assertTrue(DistroCatalog.MANJARO.supports("arm64-v8a"))
        assertFalse(DistroCatalog.MANJARO.supports("x86_64"))
        // Debian & Alpine: semua ABI
        assertTrue(DistroCatalog.DEBIAN_TRIXIE.supports("x86"))
        assertTrue(DistroCatalog.ALPINE_320.supports("x86"))
        // null = semua ABI
        assertTrue(DistroCatalog.CUSTOM.supports("riscv64"))
    }

    @Test
    fun `resolusi versi terbaru mengambil versi paling tinggi`() {
        val html = """
            <a href="alpine-minirootfs-3.20.3-aarch64.tar.gz">alpine-minirootfs-3.20.3-aarch64.tar.gz</a>
            <a href="alpine-minirootfs-3.20.10-aarch64.tar.gz">alpine-minirootfs-3.20.10-aarch64.tar.gz</a>
            <a href="alpine-minirootfs-3.20.9-aarch64.tar.gz">alpine-minirootfs-3.20.9-aarch64.tar.gz</a>
        """.trimIndent()
        // 3.20.10 > 3.20.9 secara numerik (bukan leksikografis)
        assertEquals(
            "3.20.10",
            DistroCatalog.parseLatestVersion(html, DistroCatalog.alpinePattern("aarch64")),
        )
    }

    @Test
    fun `resolusi versi terbaru membuang versi rc`() {
        val html = """
            <a href="alpine-minirootfs-3.21.0_rc2-aarch64.tar.gz">rc2</a>
            <a href="alpine-minirootfs-3.21.0-aarch64.tar.gz">stable</a>
        """.trimIndent()
        assertEquals(
            "3.21.0",
            DistroCatalog.parseLatestVersion(html, DistroCatalog.alpinePattern("aarch64")),
        )
    }

    @Test
    fun `resolusi versi terbaru mengabaikan arsitektur lain`() {
        val html = """
            <a href="alpine-minirootfs-3.20.10-x86_64.tar.gz">x86</a>
            <a href="alpine-minirootfs-3.20.3-aarch64.tar.gz">arm</a>
        """.trimIndent()
        assertEquals(
            "3.20.3",
            DistroCatalog.parseLatestVersion(html, DistroCatalog.alpinePattern("aarch64")),
        )
        assertNull(DistroCatalog.parseLatestVersion(html, DistroCatalog.alpinePattern("riscv64")))
    }

    @Test
    fun `resolusi versi ubuntu base mengikuti pola nama ubuntu-base`() {
        val html = """
            <a href="ubuntu-base-24.04.4-base-arm64.tar.gz">a</a>
            <a href="ubuntu-base-24.04.5-base-arm64.tar.gz">b</a>
            <a href="ubuntu-base-24.04.5-base-amd64.tar.gz">c</a>
        """.trimIndent()
        assertEquals(
            "24.04.5",
            DistroCatalog.parseLatestVersion(html, DistroCatalog.ubuntuBasePattern("arm64")),
        )
        assertEquals(
            "24.04.5",
            DistroCatalog.parseLatestVersion(html, DistroCatalog.ubuntuBasePattern("amd64")),
        )
    }

    @Test
    fun `resolusi versi terbaru menangani indeks kosong`() {
        assertNull(
            DistroCatalog.parseLatestVersion("", DistroCatalog.alpinePattern("aarch64")),
        )
        assertNull(
            DistroCatalog.parseLatestVersion(
                "<html>403 Forbidden</html>",
                DistroCatalog.alpinePattern("aarch64"),
            ),
        )
    }

    @Test
    fun `distro custom tidak punya url bawaan dan ditandai custom`() {
        assertTrue(DistroCatalog.CUSTOM.custom)
        assertTrue(DistroCatalog.CUSTOM.rootfsUrls("arm64-v8a").isEmpty())
        // Preset lain bukan custom
        DistroCatalog.PRESETS.forEach { assertTrue(!it.custom) }
    }

    @Test
    fun `penanda rilis dipakai untuk mendeteksi rootfs terpasang`() {
        assertNotNull(DistroCatalog.ALPINE_320.releaseMarkers)
        assertTrue(DistroCatalog.ALPINE_320.releaseMarkers.contains("etc/alpine-release"))
        // Distro basis Debian/Fedora/Arch pakai os-release
        assertTrue(DistroCatalog.UBUNTU_2404.releaseMarkers.contains("etc/os-release"))
        assertTrue(DistroCatalog.DEBIAN_TRIXIE.releaseMarkers.contains("etc/os-release"))
        assertTrue(DistroCatalog.ARCHLINUX.releaseMarkers.contains("etc/os-release"))
        // Custom menerima distro apa pun yang punya os-release atau sh
        assertTrue(DistroCatalog.CUSTOM.releaseMarkers.contains("etc/os-release"))
    }

    @Test
    fun `distro dikelompokkan dan semua preset tercakup`() {
        val grouped = DistroCatalog.grouped()
        // semua preset harus muncul di tepat satu kelompok
        val fromGroups = grouped.flatMap { it.second }.toSet()
        assertEquals(DistroCatalog.ALL.size, fromGroups.size)
        DistroCatalog.ALL.forEach { spec ->
            assertTrue("${spec.id} tidak ada di kelompok", fromGroups.contains(spec))
        }
        // urutan kelompok mengikuti GROUP_ORDER
        assertEquals(
            DistroCatalog.GROUP_ORDER,
            grouped.map { it.first },
        )
        // kelompok pertama adalah Alpine
        assertEquals("Alpine", grouped.first().first)
        assertTrue(grouped.first().second.contains(DistroCatalog.ALPINE_320))
    }

    @Test
    fun `katalog memuat distro ubuntu debian dan termux built-in`() {
        val ids = DistroCatalog.ALL.map { it.id }
        assertTrue(ids.contains("ubuntu-24.04"))
        assertTrue(ids.contains("ubuntu-22.04"))
        assertTrue(ids.contains("debian-trixie"))
        assertTrue(ids.contains("debian-bookworm"))
        assertTrue(ids.contains("termux"))
        assertTrue(ids.contains("fedora"))
        assertTrue(ids.contains("archlinux"))
        assertTrue(ids.contains("opensuse"))
        assertTrue(ids.contains("custom"))
    }

    @Test
    fun `runtime mode parsing aman terhadap nilai tak dikenal`() {
        assertEquals(RuntimeMode.PROOT, RuntimeMode.from(null))
        assertEquals(RuntimeMode.PROOT, RuntimeMode.from(""))
        assertEquals(RuntimeMode.PROOT, RuntimeMode.from("ngawur"))
        assertEquals(RuntimeMode.TERMUX, RuntimeMode.from("termux"))
        assertEquals(RuntimeMode.TERMUX, RuntimeMode.from("TERMUX"))
        assertEquals(RuntimeMode.PROOT, RuntimeMode.from("proot"))
    }
}
