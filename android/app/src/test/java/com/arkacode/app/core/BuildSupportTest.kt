package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat

class BuildSupportTest {

    private fun tempRoot(): File = Files.createTempDirectory("arka-test").toFile()

    @Test
    fun `pindai folder yang mengandung gradlew`() {
        val root = tempRoot()
        File(root, "A/gradlew").apply { parentFile.mkdirs(); writeText("") }
        File(root, "B").mkdirs()
        File(root, "B/settings.gradle.kts").writeText("")
        File(root, "C").mkdirs()
        File(root, "loose.txt").writeText("")
        assertEquals(listOf("A", "B"), BuildSupport.findProjects(root))
    }

    @Test
    fun `quote aman untuk kutip dan spasi`() {
        assertEquals("'it'\\''s a \"test\"'", BuildSupport.shQuote("it's a \"test\""))
        assertEquals("'plain'", BuildSupport.shQuote("plain"))
    }

    @Test
    fun `tailer baca baris baru dan tahan potongan`() {
        val f = File(tempRoot(), "x.log")
        val t = BuildSupport.LogTailer(f)
        f.writeText("a\nb\n")
        assertEquals(listOf("a", "b"), t.readNew())
        f.appendText("c")
        assertEquals(emptyList<String>(), t.readNew())
        f.appendText("d\n")
        assertEquals(listOf("cd"), t.readNew())
    }

    @Test
    fun `tailer reset saat file menyusut`() {
        val f = File(tempRoot(), "x.log")
        val t = BuildSupport.LogTailer(f)
        f.writeText("zzz\n")
        assertEquals(listOf("zzz"), t.readNew())
        f.writeText("y\n")
        assertEquals(listOf("y"), t.readNew())
    }

    @Test
    fun `parse probe semua siap`() {
        assertTrue(
            BuildSupport.parseToolchain("JAVA_OK\nSDK_OK\nAAPT2_OK\nADB_OK\n").ready
        )
        assertFalse(
            BuildSupport.parseToolchain("JAVA_OK\nSDK_OK\n").ready
        )
    }

    @Test
    fun `nama apk unik dan bersih`() {
        val now = SimpleDateFormat("yyyyMMdd-HHmmss").parse("20261023-101500")
        assertEquals("My-App-debug-20261023-101500.apk", BuildSupport.apkOutName("My App!", now))
    }
}
