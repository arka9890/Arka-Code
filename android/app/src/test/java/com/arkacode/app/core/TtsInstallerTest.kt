package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pemilih TTS engine tidak bisa dijalankan di unit test JVM (butuh Context
 * Android), jadi yang dites hanya logika murni: urutan engine yang dicoba.
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
}
