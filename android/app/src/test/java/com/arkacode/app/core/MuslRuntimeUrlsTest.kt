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
 * Yang diuji di sini adalah komposisi URL (murni, tanpa jaringan).
 */
class MuslRuntimeUrlsTest {

    @Test
    fun `repoUrls CDN utama di posisi pertama`() {
        val urls = MuslRuntime.repoUrls("musl-1.2.5-r3.apk")
        // Perilaku lama dipertahankan: CDN utama tetap kandidat pertama,
        // dengan struktur <repo>/<arch>/<file>.
        assertEquals(
            "${MuslRuntime.ALPINE_REPO}/aarch64/musl-1.2.5-r3.apk",
            urls.first(),
        )
    }

    @Test
    fun `repoUrls menyertakan semua mirror dengan struktur branch`() {
        val urls = MuslRuntime.repoUrls("musl-1.2.5-r3.apk")
        assertEquals(1 + MuslRuntime.MIRRORS.size, urls.size)
        MuslRuntime.MIRRORS.forEach { mirror ->
            assertTrue(
                "mirror $mirror harus ada di daftar kandidat",
                urls.any { it.startsWith("$mirror/${MuslRuntime.ALPINE_BRANCH}/") },
            )
        }
    }

    @Test
    fun `repoUrls untuk file berbeda tetap konsisten`() {
        val urls = MuslRuntime.repoUrls("libgcc-13.2.1_git20240309-r1.apk")
        assertEquals(
            "${MuslRuntime.ALPINE_REPO}/x86_64/libgcc-13.2.1_git20240309-r1.apk",
            urls.first(),
        )
        assertTrue(urls.all { it.endsWith("libgcc-13.2.1_git20240309-r1.apk") })
    }
}
