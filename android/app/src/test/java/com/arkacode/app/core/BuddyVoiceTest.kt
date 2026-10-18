package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * decodeAudio hanya dites lewat jalur galatnya. decoding base64 sendiri
 * memakai android.util.Base64 yang di unit test JVM selalu gagal, jadi jalur
 * sukses diperiksa langsung di perangkat.
 */
class BuddyVoiceTest {

    @Test
    fun `teks dipotong tapi tetap rapi di ujung`() {
        assertEquals("halo", BuddyVoice.fitText("  halo  "))
        assertEquals("abcd", BuddyVoice.fitText("abcdefgh", max = 4))
        assertEquals("", BuddyVoice.fitText("   "))
    }

    @Test
    fun `respons tanpa kandidat dianggap galat`() {
        assertThrows(IOException::class.java) {
            BuddyVoice.decodeAudio("""{"promptFeedback":{}}""")
        }
    }

    @Test
    fun `kandidat tanpa inlineData dianggap galat`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"halo"}]}}]}"""
        assertThrows(IOException::class.java) { BuddyVoice.decodeAudio(body) }
    }

    @Test
    fun `pesan galat dibaca dari respons Gemini`() {
        val body = """{"error":{"message":"API key tidak valid"}}"""
        assertEquals("API key tidak valid", BuddyVoice.errorFrom(body))
    }

    @Test
    fun `respons tak terbaca tetap memberi pesan yang bisa dipahami`() {
        assertEquals("kunci atau model salah", BuddyVoice.errorFrom("bukan-json"))
    }
}
