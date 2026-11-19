package com.arkacode.app.core

import com.arkacode.app.net.ChatFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(rel: String, text: String, mtime: Long? = null): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        if (mtime != null) f.setLastModified(mtime)
        return f
    }

    @Test
    fun `scan melewati folder yang diabaikan`() {
        write("out/chart.png", "x")
        write("node_modules/a/index.js", "x")
        write("uploads/user.png", "x")
        write(".git/HEAD", "x")
        val map = TurnFiles.scan(tmp.root)!!
        assertEquals(setOf("out/chart.png"), map.keys)
    }

    @Test
    fun `diff hanya berkas baru atau berubah`() {
        val lama = write("tetap.txt", "sama", mtime = 1000)
        val diubah = write("ubah.txt", "v1", mtime = 1000)
        write("buang.txt", "akan dihapus", mtime = 1000)
        val before = TurnFiles.scan(tmp.root)!!

        write("baru.png", "x", mtime = 2000)
        diubah.writeText("v2 lebih panjang")
        diubah.setLastModified(3000)
        File(tmp.root, "buang.txt").delete()
        lama.setLastModified(1000)

        val changed = TurnFiles.diff(before, tmp.root).map { it.path }
        assertEquals(listOf("ubah.txt", "baru.png"), changed)
    }

    @Test
    fun `diff mengabaikan berkas yang hanya dibaca`() {
        write("masukan.txt", "isi")
        val before = TurnFiles.scan(tmp.root)!!
        assertTrue(TurnFiles.diff(before, tmp.root).isEmpty())
    }

    @Test
    fun `scan mengembalikan null bila terlalu banyak berkas`() {
        repeat(5) { write("f$it.txt", "x") }
        assertNull(TurnFiles.scan(tmp.root, maxEntries = 3))
    }

    @Test
    fun `diff dipotong sesuai limit`() {
        val before = TurnFiles.scan(tmp.root)!!
        repeat(10) { i -> write("b$i.png", "x", mtime = 1000L + i) }
        assertEquals(4, TurnFiles.diff(before, tmp.root, limit = 4).size)
    }

    @Test
    fun `nama dan mime dibawa ke ChatFile`() {
        val before = TurnFiles.scan(tmp.root)!!
        write("hasil video.mp4", "x")
        val f = TurnFiles.diff(before, tmp.root).single()
        assertEquals("hasil video.mp4", f.name)
        assertEquals("video/mp4", f.mime)
        assertEquals(ChatFile.Kind.VIDEO, f.kind)
    }
}