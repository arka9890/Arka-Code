package com.arkacode.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Tes [DistroManager.existsInRootfs] — penjaga regresi bug "env: can't execute
 * '/usr/bin/sh'": `File.exists()` mengikuti symlink ABSOLUT ke path HOST, jadi
 * `bin/sh -> /bin/busybox` di rootfs Alpine dianggap tidak ada.
 */
class DistroManagerSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rootfs: File get() = tmp.root

    private fun touch(rel: String): File =
        File(rootfs, rel).apply { parentFile.mkdirs(); writeText("") }

    private fun link(rel: String, target: String) {
        val path = File(rootfs, rel).apply { parentFile.mkdirs() }.toPath()
        Files.createSymbolicLink(path, File(target).toPath())
    }

    @Test
    fun `file biasa terbaca`() {
        touch("bin/busybox")
        assertTrue(DistroManager.existsInRootfs(rootfs, "/bin/busybox"))
        assertFalse(DistroManager.existsInRootfs(rootfs, "/bin/missing"))
    }

    @Test
    fun `symlink absolut yang targetnya ada di rootfs dianggap ada`() {
        touch("bin/busybox")
        // Layout Alpine: /bin/sh -> /bin/busybox (absolut, menunjuk akar guest).
        link("bin/sh", "/bin/busybox")
        assertTrue(DistroManager.existsInRootfs(rootfs, "/bin/sh"))
    }

    @Test
    fun `symlink absolut yang targetnya hilang tetap dianggap tidak ada`() {
        link("bin/sh", "/bin/nothing")
        assertFalse(DistroManager.existsInRootfs(rootfs, "/bin/sh"))
    }

    @Test
    fun `symlink relatif di-resolve terhadap folder induk`() {
        touch("usr/bin/dash")
        // Layout Debian/Ubuntu: /usr/bin/sh -> dash
        link("usr/bin/sh", "dash")
        assertTrue(DistroManager.existsInRootfs(rootfs, "/usr/bin/sh"))
    }

    @Test
    fun `rantai symlink diputus setelah kedalaman tertentu`() {
        touch("bin/busybox")
        link("bin/a", "b")
        link("bin/b", "a")
        assertFalse(DistroManager.existsInRootfs(rootfs, "/bin/a"))
    }
}
