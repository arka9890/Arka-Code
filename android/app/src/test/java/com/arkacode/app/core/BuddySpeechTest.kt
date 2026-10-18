package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keputusan di sini menentukan apakah ucapan pengguna terpakai atau hilang.
 * Dites tanpa Android karena `SpeechRecognizer` tidak bisa dijalankan di JVM.
 *
 * Nilai galat ditulis sebagai angka karena itulah nilai sebenarnya di Android:
 * SPEECH_TIMEOUT = 6, NO_MATCH = 7.
 */
class BuddySpeechTest {

    private val retryable = setOf(6, 7)

    @Test
    fun `hasil recognizer yang berhenti sendiri saat tombol ditahan tidak mengakhiri perekaman`() {
        // Ini inti perbaikannya: recognizer memanggil onResults sendiri pada
        // jeda pertama, tidak peduli tombol masih ditekan.
        assertEquals(
            SpeechAction.RETRY,
            speechAction(held = true, error = null, retryable = retryable, said = "halo"),
        )
    }

    @Test
    fun `galat tidak ada suara saat tombol ditahan diteruskan bukan dianggap selesai`() {
        assertEquals(
            SpeechAction.RETRY,
            speechAction(held = true, error = 6, retryable = retryable, said = ""),
        )
        assertEquals(
            SpeechAction.RETRY,
            speechAction(held = true, error = 7, retryable = retryable, said = "halo"),
        )
    }

    @Test
    fun `galat sungguhan saat tombol ditahan tetap berhenti`() {
        // 9 = INSUFFICIENT_PERMISSIONS, tidak akan membaik dengan mengulang.
        assertEquals(
            SpeechAction.DELIVER,
            speechAction(held = true, error = 9, retryable = retryable, said = "halo"),
        )
        assertEquals(
            SpeechAction.FAIL,
            speechAction(held = true, error = 9, retryable = retryable, said = ""),
        )
    }

    @Test
    fun `ucapan yang sudah terkumpul tidak dibuang oleh sesi terakhir yang sepi`() {
        // Bicara, jeda beberapa detik, lalu melepas saat sesi pengganti belum
        // sempat mendapat suara apa pun.
        assertEquals(
            SpeechAction.DELIVER,
            speechAction(held = false, error = 6, retryable = retryable, said = "halo apa kabar"),
        )
    }

    @Test
    fun `tanpa suara sama sekali tetap dikirim supaya layar bisa memberi tahu`() {
        assertEquals(
            SpeechAction.DELIVER,
            speechAction(held = false, error = null, retryable = retryable, said = ""),
        )
    }

    @Test
    fun `galat tanpa ucapan sama sekali dilaporkan`() {
        assertEquals(
            SpeechAction.FAIL,
            speechAction(held = false, error = 3, retryable = retryable, said = ""),
        )
    }

    @Test
    fun `potongan hasil ditampung berurutan dengan spasi`() {
        assertEquals("halo", joinSpeech("", "halo"))
        assertEquals("halo apa kabar", joinSpeech("halo", "apa kabar"))
    }

    @Test
    fun `potongan kosong tidak mengubah apa pun`() {
        assertEquals("", joinSpeech("", ""))
        assertEquals("halo", joinSpeech("halo", ""))
        assertEquals("halo", joinSpeech("halo", "   "))
    }
}
