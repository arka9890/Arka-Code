package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolchainSetupTest {

    private fun tempRoot(): File = Files.createTempDirectory("arka-setup").toFile()

    @Test
    fun `ensureScript menulis skrip dengan marker dan guard`() {
        val f = ToolchainSetup.ensureScript(tempRoot())
        val isi = f.readText()
        assertTrue(isi.startsWith("#!/bin/sh"))
        assertTrue(isi.contains("# arka-toolchain-setup"))
        assertTrue(isi.contains("TOOLCHAIN_OK"))
        // tiap blok instalasi ber-guard idempoten — minimal 4 guard `if [ !`
        assertTrue(Regex("""if \[ !""").findAll(isi).count() >= 4)
        assertTrue(isi.contains("|| {"))
        // pin versi aapt2 + URL platform-tools (keputusan spike)
        assertTrue(isi.contains("AAPT2_VER=8.9.3-12782657"))
        assertTrue(isi.contains("platform-tools-latest-linux.zip"))
        assertTrue(f.canExecute())
    }

    @Test
    fun `ensureScript idempoten - konten sama tidak ditulis ulang`() {
        val ws = tempRoot()
        val f1 = ToolchainSetup.ensureScript(ws)
        val t1 = f1.lastModified()
        val f2 = ToolchainSetup.ensureScript(ws)
        assertEquals(f1, f2)
        assertEquals(t1, f2.lastModified())
    }

    @Test
    fun `probe memakai semua flag`() {
        listOf("JAVA_OK", "SDK_OK", "AAPT2_OK", "ADB_OK").forEach {
            assertTrue("flag $it hilang", ToolchainSetup.PROBE_CMD.contains(it))
        }
    }
}
