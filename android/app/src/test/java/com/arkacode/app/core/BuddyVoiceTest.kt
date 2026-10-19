package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TTS kini memakai `android.speech.tts` bawaan Android (lokal). Engine-nya tidak
 * bisa dijalankan di unit test JVM, jadi yang dites hanya logika murni:
 * pembersihan & pemotongan teks sebelum dibacakan, dan pemilihan suara.
 */
class BuddyVoiceTest {

    @Test
    fun `teks dipotong tapi tetap rapi di ujung`() {
        assertEquals("halo", BuddyVoice.fitText("  halo  "))
        assertEquals("abcd", BuddyVoice.fitText("abcdefgh", max = 4))
        assertEquals("", BuddyVoice.fitText("   "))
    }

    // Kualitas voice: 1 normal, 2 high, 3 very high (sama dengan
    // android.speech.tts.Voice.QUALITY_*), ditulis angka biar tesnya murni.

    private fun voice(
        name: String,
        language: String = "id",
        region: String = "ID",
        quality: Int = 1,
        needsNetwork: Boolean = false,
    ) = VoiceChoice(name, language, region, quality, needsNetwork)

    @Test
    fun `tanpa suara indonesia tidak ada yang dipilih`() {
        val voices = listOf(voice("en-US", language = "en", region = "US"))
        assertNull(pickVoice(voices))
    }

    @Test
    fun `suara offline lebih dulu daripada yang butuh jaringan`() {
        val voices = listOf(
            voice("jaringan", quality = 3, needsNetwork = true),
            voice("offline", quality = 1),
        )
        assertEquals("offline", pickVoice(voices)?.name)
    }

    @Test
    fun `suara berkualitas lebih tinggi yang dipilih`() {
        val voices = listOf(voice(" biasa", quality = 1), voice("bagus", quality = 3))
        assertEquals("bagus", pickVoice(voices)?.name)
    }

    @Test
    fun `kode tiga huruf ind juga dianggap indonesia`() {
        // Engine sherpa-onnx membandingkan kode ISO 639-3 apa adanya.
        val voices = listOf(voice("supertonic", language = "ind", region = ""))
        assertEquals("supertonic", pickVoice(voices)?.name)
    }

    @Test
    fun `wilayah indonesia lebih dulu daripada wilayah lain`() {
        val voices = listOf(
            voice("umum", region = "", quality = 3),
            voice("id", region = "ID", quality = 1),
        )
        assertEquals("id", pickVoice(voices)?.name)
    }
}
