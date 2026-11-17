package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Tes helper post-install rootfs container.
 *
 * `appendMuslPath` murni (tanpa filesystem); `ensureContainerLinks` dan
 * `ensureShForTermuxLayout` diuji lewat folder sementara ([TemporaryFolder]) —
 * keduanya hanya menyentuh file biasa + symlink, jadi aman di JVM tanpa
 * Android.
 */
class ContainerRootfsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val termuxPrefix = "data/data/com.termux/files/usr"

    // ------------------------------------------------------- appendMuslPath

    @Test
    fun `append musl path saat kosong`() {
        assertEquals(
            "/opt/containers/python/rootfs/usr/lib",
            ContainerRootfs.appendMuslPath("", "/opt/containers/python/rootfs/usr/lib"),
        )
    }

    @Test
    fun `append musl path menambahkan setelah path lama`() {
        assertEquals(
            "/opt/arkacode/lib:/opt/containers/node/rootfs/usr/lib",
            ContainerRootfs.appendMuslPath(
                "/opt/arkacode/lib",
                "/opt/containers/node/rootfs/usr/lib",
            ),
        )
    }

    @Test
    fun `append musl path idempoten`() {
        val guestDir = "/opt/containers/python/rootfs/usr/lib"
        val once = ContainerRootfs.appendMuslPath("", guestDir)
        assertEquals(once, ContainerRootfs.appendMuslPath(once, guestDir))
        // elemen di tengah path juga terdeteksi (bukan cuma di ujung)
        assertEquals(
            "/opt/arkacode/lib:$guestDir:/usr/local/lib",
            ContainerRootfs.appendMuslPath("/opt/arkacode/lib:$guestDir:/usr/local/lib", guestDir),
        )
    }

    @Test
    fun `append musl path membandingkan elemen ter-trim`() {
        // elemen " /b " dianggap sama dengan "/b" -> path dikembalikan apa adanya
        assertEquals("/a: /b ", ContainerRootfs.appendMuslPath("/a: /b ", "/b"))
        // guestDir dengan spasi di ujung tetap dikenali
        assertEquals("/a:/b", ContainerRootfs.appendMuslPath("/a:/b", " /b "))
    }

    // --------------------------------------------------- ensureContainerLinks

    @Test
    fun `ensure container links membuat workspace tmp resolv hosts`() {
        val rootfs = tmp.newFolder("rootfs")
        ContainerRootfs.ensureContainerLinks(rootfs)

        assertTrue(File(rootfs, "root/workspace").isDirectory)
        assertTrue(File(rootfs, "tmp").isDirectory)
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            File(rootfs, "etc/resolv.conf").readText(),
        )
        assertEquals(
            "127.0.0.1 localhost\n::1 localhost\n",
            File(rootfs, "etc/hosts").readText(),
        )
    }

    @Test
    fun `ensure container links menandai ELF dan shebang executable`() {
        val rootfs = tmp.newFolder("rootfs-elf")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val usrBin = File(rootfs, "usr/bin").apply { mkdirs() }
        val usrLocalBin = File(rootfs, "usr/local/bin").apply { mkdirs() }

        val elfInBin = File(bin, "busybox").apply { writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 1, 2)) }
        val elfInUsrBin = File(usrBin, "python3").apply { writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) }
        val scriptInUsrLocalBin = File(usrLocalBin, "setup.sh").apply { writeText("#!/bin/sh\necho hi\n") }
        val textInBin = File(bin, "plain.txt").apply { writeText("bukan binary\n") }

        ContainerRootfs.ensureContainerLinks(rootfs)

        assertTrue("ELF di bin harus executable", elfInBin.canExecute())
        assertTrue("ELF di usr/bin harus executable", elfInUsrBin.canExecute())
        assertTrue("shebang di usr/local/bin harus executable", scriptInUsrLocalBin.canExecute())
        // file teks tidak diutak-atik: tetap ada (bit x tidak ditambah/dihapus)
        assertTrue(textInBin.isFile)
    }

    @Test
    fun `ensure container links idempoten`() {
        val rootfs = tmp.newFolder("rootfs-idem")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val tool = File(bin, "tool").apply { writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) }

        ContainerRootfs.ensureContainerLinks(rootfs)
        ContainerRootfs.ensureContainerLinks(rootfs) // panggilan kedua tidak boleh meledak

        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            File(rootfs, "etc/resolv.conf").readText(),
        )
        assertEquals(
            "127.0.0.1 localhost\n::1 localhost\n",
            File(rootfs, "etc/hosts").readText(),
        )
        assertTrue(tool.canExecute())
    }

    @Test
    fun `ensure container links aman untuk rootfs kosong`() {
        val rootfs = tmp.newFolder("rootfs-empty")
        ContainerRootfs.ensureContainerLinks(rootfs) // tidak ada bin/ etc. -> tanpa exception
        assertTrue(File(rootfs, "etc/resolv.conf").isFile)
        assertTrue(File(rootfs, "root/workspace").isDirectory)
    }

    // ---------------------------------------------- ensureShForTermuxLayout

    /** Rootfs layout Termux: `sh` hanya ada di dalam prefix. */
    private fun termuxRootfs(): File {
        val rootfs = tmp.newFolder("rootfs-termux")
        val prefixBin = File(rootfs, "$termuxPrefix/bin").apply { mkdirs() }
        File(prefixBin, "sh").writeText("#!$termuxPrefix/bin/bash\n")
        return rootfs
    }

    @Test
    fun `ensure sh membuat symlink bin sh relatif untuk layout termux`() {
        val rootfs = termuxRootfs()

        assertTrue(ContainerRootfs.ensureShForTermuxLayout(rootfs, termuxPrefix))

        val link = File(rootfs, "bin/sh")
        assertTrue("bin/sh harus berupa symlink", Files.isSymbolicLink(link.toPath()))
        assertEquals(
            "../$termuxPrefix/bin/sh",
            Files.readSymbolicLink(link.toPath()).toString(),
        )
        // symlink resolve ke file sh milik prefix (bukan symlink menggantung)
        assertTrue(Files.exists(link.toPath()))
    }

    @Test
    fun `ensure sh prefix dengan garis miring ujung dinormalkan`() {
        val rootfs = termuxRootfs()

        assertTrue(ContainerRootfs.ensureShForTermuxLayout(rootfs, "/$termuxPrefix/"))

        assertEquals(
            "../$termuxPrefix/bin/sh",
            Files.readSymbolicLink(File(rootfs, "bin/sh").toPath()).toString(),
        )
    }

    @Test
    fun `ensure sh idempoten dan tidak menimpa bin sh yang sudah ada`() {
        val rootfs = termuxRootfs()

        assertTrue(ContainerRootfs.ensureShForTermuxLayout(rootfs, termuxPrefix))
        assertFalse(
            "panggilan kedua tidak boleh membuat symlink lagi",
            ContainerRootfs.ensureShForTermuxLayout(rootfs, termuxPrefix),
        )

        // bin/sh asli (file biasa) tidak boleh diganti symlink
        val other = tmp.newFolder("rootfs-termux-2")
        val bin = File(other, "bin").apply { mkdirs() }
        val existing = File(bin, "sh").apply { writeText("sh asli\n") }
        File(other, "$termuxPrefix/bin").mkdirs()
        File(other, "$termuxPrefix/bin/sh").writeText("sh prefix\n")

        assertFalse(ContainerRootfs.ensureShForTermuxLayout(other, termuxPrefix))
        assertFalse(Files.isSymbolicLink(existing.toPath()))
        assertEquals("sh asli\n", existing.readText())
    }

    @Test
    fun `ensure sh tanpa prefix tidak melakukan apa pun`() {
        val rootfs = tmp.newFolder("rootfs-noprefix")

        assertFalse(ContainerRootfs.ensureShForTermuxLayout(rootfs, ""))
        assertFalse(File(rootfs, "bin").exists())
    }

    @Test
    fun `ensure sh tidak dibuat kalau sh prefix tidak ada`() {
        val rootfs = tmp.newFolder("rootfs-nosh")

        assertFalse(ContainerRootfs.ensureShForTermuxLayout(rootfs, termuxPrefix))
        assertFalse(
            Files.exists(
                Paths.get(File(rootfs, "bin/sh").absolutePath),
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
    }
}