package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pemilih TTS engine tidak bisa dijalankan di unit test JVM (butuh Context
 * Android), jadi yang dites hanya logika murni: urutan engine yang dicoba dan
 * konfigurasi FileProvider-nya.
 */
class TtsInstallerTest {

    @Test
    fun `engine neural dicoba lebih dulu kalau sudah terpasang`() {
        val candidates = TtsInstaller.engineCandidates(neuralInstalled = true)
        assertEquals(TtsInstaller.PACKAGE, candidates.first())
        assertNull(candidates.last())
    }

    @Test
    fun `engine sistem tetap jadi cadangan`() {
        // Tanpa engine neural, atau kalau neuralnya rusak, Aru-chan tetap
        // bicara dengan TTS bawaan — jangan sampai bisu.
        val candidates = TtsInstaller.engineCandidates(neuralInstalled = false)
        assertEquals(1, candidates.size)
        assertNull(candidates.first())
    }

    @Test
    fun `nama paket engine sesuai yang dipakai di build`() {
        assertEquals("com.k2fsa.sherpa.onnx.tts.engine", TtsInstaller.PACKAGE)
        assertTrue(TtsInstaller.ASSET_NAME.endsWith(".apk"))
    }

    @Test
    fun `file_paths mendaftarkan folder tujuan, bukan berkasnya`() {
        // FileProvider menghitung path relatif dengan
        // `substring(rootPath.length() + 1)`. Kalau `path` menunjuk FILE,
        // root-nya jadi file dan penghitungannya meleset — URI tak pernah
        // terbentuk dan pemasangan diam-diam gagal. Jadi path WAJIB folder.
        val xml = filePathsXml()
        val name = Regex("name=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        val path = Regex("path=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
        assertNotNull("file_paths.xml harus punya atribut name & path", name)
        assertNotNull("file_paths.xml harus punya atribut name & path", path)
        assertEquals(TtsInstaller.DIR_NAME, name)
        assertEquals("${TtsInstaller.DIR_NAME}/", path)
        assertTrue("path harus menunjuk folder (akhiran '/')", path!!.endsWith("/"))
    }

    private fun filePathsXml(): String {
        val candidates = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("file_paths.xml tidak ditemukan di ${File(".").absolutePath}", file)
        return file!!.readText()
    }
}
