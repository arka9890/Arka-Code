package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes katalog distro: pemetaan ABI, URL rootfs, dan parsing versi Alpine.
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
    fun `byId mengembalikan default untuk id tak dikenal`() {
        assertEquals(DistroCatalog.ALPINE_320.id, DistroCatalog.byId(null).id)
        assertEquals(DistroCatalog.ALPINE_320.id, DistroCatalog.byId("nope").id)
        assertEquals(DistroCatalog.ALPINE_321.id, DistroCatalog.byId("alpine-3.21").id)
        assertEquals(DistroCatalog.CUSTOM.id, DistroCatalog.byId("custom").id)
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
        DistroCatalog.PRESETS.forEach { spec ->
            val urls = spec.rootfsUrls("arm64-v8a")
            assertTrue("${spec.id} harus punya >1 kandidat", urls.size > 1)
            assertEquals("${spec.id} kandidat harus unik", urls.size, urls.distinct().size)
            urls.forEach { u ->
                assertTrue("${spec.id} URL harus https", u.startsWith("https://"))
            }
        }
    }

    @Test
    fun `resolusi versi terbaru mengambil versi paling tinggi`() {
        val html = """
            <a href="alpine-minirootfs-3.20.3-aarch64.tar.gz">alpine-minirootfs-3.20.3-aarch64.tar.gz</a>
            <a href="alpine-minirootfs-3.20.10-aarch64.tar.gz">alpine-minirootfs-3.20.10-aarch64.tar.gz</a>
            <a href="alpine-minirootfs-3.20.9-aarch64.tar.gz">alpine-minirootfs-3.20.9-aarch64.tar.gz</a>
        """.trimIndent()
        // 3.20.10 > 3.20.9 secara numerik (bukan leksikografis)
        assertEquals("3.20.10", DistroCatalog.parseLatestVersion(html, "aarch64"))
    }

    @Test
    fun `resolusi versi terbaru membuang versi rc`() {
        val html = """
            <a href="alpine-minirootfs-3.21.0_rc2-aarch64.tar.gz">rc2</a>
            <a href="alpine-minirootfs-3.21.0-aarch64.tar.gz">stable</a>
        """.trimIndent()
        assertEquals("3.21.0", DistroCatalog.parseLatestVersion(html, "aarch64"))
    }

    @Test
    fun `resolusi versi terbaru mengabaikan arsitektur lain`() {
        val html = """
            <a href="alpine-minirootfs-3.20.10-x86_64.tar.gz">x86</a>
            <a href="alpine-minirootfs-3.20.3-aarch64.tar.gz">arm</a>
        """.trimIndent()
        assertEquals("3.20.3", DistroCatalog.parseLatestVersion(html, "aarch64"))
        assertNull(DistroCatalog.parseLatestVersion(html, "riscv64"))
    }

    @Test
    fun `resolusi versi terbaru menangani indeks kosong`() {
        assertNull(DistroCatalog.parseLatestVersion("", "aarch64"))
        assertNull(DistroCatalog.parseLatestVersion("<html>403 Forbidden</html>", "aarch64"))
    }

    @Test
    fun `distro custom tidak punya url bawaan dan ditandai custom`() {
        assertTrue(DistroCatalog.CUSTOM.custom)
        assertTrue(DistroCatalog.CUSTOM.rootfsUrls("arm64-v8a").isEmpty())
        // Preset Alpine bukan custom
        DistroCatalog.PRESETS.forEach { assertTrue(!it.custom) }
    }

    @Test
    fun `penanda rilis dipakai untuk mendeteksi rootfs terpasang`() {
        assertNotNull(DistroCatalog.ALPINE_320.releaseMarkers)
        assertTrue(DistroCatalog.ALPINE_320.releaseMarkers.contains("etc/alpine-release"))
        // Custom menerima distro apa pun yang punya os-release atau sh
        assertTrue(DistroCatalog.CUSTOM.releaseMarkers.contains("etc/os-release"))
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
