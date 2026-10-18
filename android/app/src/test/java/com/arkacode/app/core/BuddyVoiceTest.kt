package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TTS kini memakai `android.speech.tts` bawaan Android (lokal). Engine-nya tidak
 * bisa dijalankan di unit test JVM, jadi yang dites hanya logika murni:
 * pembersihan & pemotongan teks sebelum dibacakan.
 */
class BuddyVoiceTest {

    @Test
    fun `teks dipotong tapi tetap rapi di ujung`() {
        assertEquals("halo", BuddyVoice.fitText("  halo  "))
        assertEquals("abcd", BuddyVoice.fitText("abcdefgh", max = 4))
        assertEquals("", BuddyVoice.fitText("   "))
    }
}
