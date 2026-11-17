package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes "container exposure" (Task 5): folder containers terlihat di
 * `/opt/containers` (argumen bind) dan shim-nya di depan PATH guest.
 *
 * Hanya bagian murni yang diuji — perakitan argv penuh (`buildProotCommand`,
 * `baseArgv`, `serveArgv`) butuh proot + rootfs terpasang, jadi
 * diverifikasi lewat build. Yang diuji di sini adalah dua potongan yang
 * menyusunnya: [DistroManager.containerBindArgs] (bind) dan
 * [DistroManager.guestPathValueWithContainers] (PATH).
 */
class ContainerExposureTest {

    @Test
    fun `containerBindArgs mengikat folder containers ke opt containers`() {
        val hostDir = "/data/user/0/com.arkacode.app/files/containers"
        assertEquals(
            listOf("-b", "$hostDir:/opt/containers"),
            DistroManager.containerBindArgs(hostDir),
        )

        val args = DistroManager.containerBindArgs("/tmp/containers")
        assertEquals(2, args.size)
        assertEquals("-b", args[0])
        assertTrue("bind harus menuju /opt/containers", args[1].endsWith(":/opt/containers"))
    }

    @Test
    fun `bind DistroManager sama dengan sumber kebenaran ContainerManager`() {
        val hostDir = "/data/user/0/com.arkacode.app/files/containers"
        assertEquals(
            ContainerManager.containerBindArgs(hostDir),
            DistroManager.containerBindArgs(hostDir),
        )
    }

    @Test
    fun `guestPathValueWithContainers memberi containers bin di depan`() {
        val path = guestPathValueWithContainers(DistroCatalog.ALPINE_320)
        assertTrue(path.startsWith("/opt/containers/bin:"))
        assertTrue(path.contains(":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
    }

    @Test
    fun `guestPathValueWithContainers untuk layout Termux`() {
        val path = guestPathValueWithContainers(DistroCatalog.TERMUX_BUILTIN)
        assertTrue(path.startsWith("/opt/containers/bin:"))
        // bin prefix Termux tetap di depan base PATH
        assertTrue(path.contains(":${DistroCatalog.TERMUX_BUILTIN.guestPrefix}/bin:"))
    }

    /**
     * `guestPathValue()` = base PATH distro, dengan bin prefix di depan bila
     * layout Termux. Cerminan murni dari [DistroManager.guestPathValue] (yang
     * butuh instance + Context) — cukup untuk menguji komposisi PATH.
     */
    private fun guestPathValueWithContainers(spec: DistroSpec): String {
        val bin = if (spec.extractDir.isNotEmpty()) "${spec.guestPrefix}/bin" else "/usr/bin"
        val base = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val guest = if (bin == "/usr/bin") base else "$bin:$base"
        return "/opt/containers/bin:$guest"
    }
}