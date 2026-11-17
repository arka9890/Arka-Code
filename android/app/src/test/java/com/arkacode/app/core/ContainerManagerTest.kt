package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes [ContainerManager] — hanya fungsi murni di companion (tanpa Android,
 * tanpa filesystem): perakitan path guest, nama folder rootfs, dan argumen
 * bind proot.
 *
 * Alur install/remove butuh Context + jaringan, jadi tidak diuji di sini
 * (pola yang sama dengan DistroManager: yang diuji adalah bagian murni).
 */
class ContainerManagerTest {

    @Test
    fun `guestPath menyusun path rootfs di dalam guest`() {
        assertEquals("/opt/containers/python/rootfs", ContainerManager.guestPath("python"))
        assertEquals("/opt/containers/node/rootfs", ContainerManager.guestPath("node"))
    }

    @Test
    fun `containerDirName memakai id container`() {
        assertEquals("python", ContainerManager.containerDirName("python"))
        assertEquals("node", ContainerManager.containerDirName("node"))
        // rootfs SELALU di dalam <folder container>/rootfs, dan itu harus sama
        // dengan path yang dilihat guest. Ini penjaga regresi bug "rootfs satu
        // tingkat terlalu dalam" (containers/<id>/<id>/rootfs): staging
        // mengekstrak ke <staging>/rootfs lalu rename → containers/<id>/rootfs.
        assertEquals(
            ContainerManager.containerDirName("python") + "/" + ContainerManager.ROOTFS_SUBDIR,
            ContainerManager.guestPath("python").removePrefix("/opt/containers/"),
        )
        assertEquals(
            ContainerManager.containerDirName("node") + "/" + ContainerManager.ROOTFS_SUBDIR,
            ContainerManager.guestPath("node").removePrefix("/opt/containers/"),
        )
    }

    @Test
    fun `ROOTFS_SUBDIR adalah nama folder rootfs`() {
        assertEquals("rootfs", ContainerManager.ROOTFS_SUBDIR)
        assertTrue(
            "guestPath harus berakhir dengan /<ROOTFS_SUBDIR>",
            ContainerManager.guestPath("python").endsWith("/" + ContainerManager.ROOTFS_SUBDIR),
        )
    }

    @Test
    fun `containerBindArgs mengikat folder containers ke opt containers`() {
        assertEquals(
            listOf("-b", "/data/user/0/com.arkacode.app/files/containers:/opt/containers"),
            ContainerManager.containerBindArgs("/data/user/0/com.arkacode.app/files/containers"),
        )

        val args = ContainerManager.containerBindArgs("/tmp/containers")
        assertEquals(2, args.size)
        assertEquals("-b", args[0])
        assertTrue("bind harus menuju /opt/containers", args[1].endsWith(":/opt/containers"))
    }
}