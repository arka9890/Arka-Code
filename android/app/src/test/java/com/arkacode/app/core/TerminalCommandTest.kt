package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandTest {

    private fun target(
        termuxLayout: Boolean = false,
        workspaceDir: String? = "/data/app/workspace",
    ) = TerminalTarget(
        proot = "/lib/libproot.so",
        loader = "/lib/libproot-loader.so",
        rootfs = "/data/app/distro/alpine",
        prootTmp = "/data/app/proot-tmp",
        envPath = "/bin/busybox",
        shellPath = "/bin/sh",
        home = "/root",
        path = "/opt/containers/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        termuxLayout = termuxLayout,
        workspaceDir = workspaceDir,
        containersDir = "/data/app/containers",
        shmDir = "/data/app/distro/alpine/tmp",
        extraBinds = listOf("/system", "/apex"),
    )

    @Test
    fun `argv dimulai proot dan memakai rootfs distro`() {
        val argv = TerminalCommand.build(target())
        assertEquals("/lib/libproot.so", argv[0])
        val i = argv.indexOf("-r")
        assertEquals("/data/app/distro/alpine", argv[i + 1])
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("--link2symlink"))
    }

    @Test
    fun `layout termux membuang -0`() {
        assertTrue(TerminalCommand.build(target()).contains("-0"))
        assertFalse(TerminalCommand.build(target(termuxLayout = true)).contains("-0"))
    }

    @Test
    fun `bind workspace containers dan shm`() {
        val argv = TerminalCommand.build(target())
        assertTrue(argv.contains("/data/app/workspace:/root/workspace"))
        assertTrue(argv.contains("/data/app/containers:/opt/containers"))
        assertTrue(argv.contains("/data/app/distro/alpine/tmp:/dev/shm"))
        assertTrue(argv.contains("/system"))
    }

    @Test
    fun `env tail lengkap sebelum shell`() {
        val argv = TerminalCommand.build(target())
        val env = argv.indexOf("/bin/busybox")
        assertTrue(env > 0)
        assertEquals("-i", argv[env + 1])
        assertTrue(argv.contains("HOME=/root"))
        assertTrue(argv.contains("TERM=xterm-256color"))
        assertTrue(argv.contains("LANG=C.UTF-8"))
        assertTrue(argv.contains("PROOT_LOADER=/lib/libproot-loader.so"))
        assertTrue(argv.contains("PROOT_TMP_DIR=/data/app/proot-tmp"))
        assertTrue(argv.contains("PROOT_NO_SECCOMP=1"))
        assertTrue(argv.contains(
            "PATH=/opt/containers/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ))
    }

    @Test
    fun `argv diakhiri shell login`() {
        val argv = TerminalCommand.build(target())
        assertEquals(listOf("/bin/sh", "-l"), argv.takeLast(2))
    }

    @Test
    fun `tanpa workspace tidak ada bind workspace`() {
        val argv = TerminalCommand.build(target(workspaceDir = null))
        assertFalse(argv.any { it.endsWith(":/root/workspace") })
    }
}
