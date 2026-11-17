package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes kandidat URL runtime musl (regresi bug "opencode ENOENT di distro
 * glibc"): unduhan paket loader musl dulu hanya pegang satu CDN — kalau CDN
 * itu tidak terjangkau (mis. lewat VPN), [MuslRuntime.ensure] gagal senyap,
 * loader tidak pernah dipasang ke rootfs, dan exec opencode gagal dengan
 * `env: '/opt/arkacode/opencode': No such file or directory`.
 *
 * Sekarang CDN + mirror dicoba berurutan seperti unduhan rootfs distro.
 * Yang diuji di sini adalah komposisi URL (murni, tanpa jaringan) — termasuk
 * struktur `<repo>/<branch>/main/<arch>/<file>` yang benar untuk mirror.
 */
class MuslRuntimeUrlsTest {

    @Test
    fun `repoUrls CDN utama di posisi pertama dengan URL utuh`() {
        val urls = MuslRuntime.repoUrls("aarch64", "musl-1.2.5-r4.apk")
        // Perilaku lama dipertahankan: CDN utama tetap kandidat pertama.
        assertEquals(
            "${MuslRuntime.ALPINE_REPO}/aarch64/musl-1.2.5-r4.apk",
            urls.first(),
        )
    }

    @Test
    fun `repoUrls menyertakan semua mirror dengan struktur main-arch-file`() {
        val urls = MuslRuntime.repoUrls("x86_64", "musl-1.2.5-r4.apk")
        assertEquals(1 + MuslRuntime.MIRRORS.size, urls.size)
        MuslRuntime.MIRRORS.forEach { mirror ->
            val expected = "$mirror/${MuslRuntime.ALPINE_BRANCH}/main/x86_64/musl-1.2.5-r4.apk"
            assertTrue(
                "mirror $mirror harus menghasilkan $expected",
                urls.contains(expected),
            )
        }
    }

    @Test
    fun `repoUrls semua kandidat berakhir dengan nama file yang sama`() {
        val urls = MuslRuntime.repoUrls("aarch64", "libgcc-13.2.1_git20240309-r1.apk")
        assertTrue(urls.all { it.endsWith("/libgcc-13.2.1_git20240309-r1.apk") })
        // Tidak ada segmen arch dobel (regresi bug URL pertama kali).
        assertTrue(urls.none { it.contains("aarch64/aarch64") })
    }
}
