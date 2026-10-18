package com.arkacode.app.core

import com.arkacode.app.net.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `replyForTurn` adalah keputusan yang tidak bisa diverifikasi lewat
 * pemeriksaan kompilasi: keliru di sini membuat Buddy membacakan balasan
 * giliran LAMA, atau balasan yang belum selesai.
 */
class BuddyAgentTest {

    private fun asst(
        id: String,
        text: String,
        finish: String? = "stop",
        error: String? = null,
    ) = ChatItem.Assistant(
        id = id,
        texts = if (text.isEmpty()) emptyList() else listOf(text),
        reasonings = emptyList(),
        tools = emptyList(),
        finish = finish,
        error = error,
    )

    @Test
    fun `balasan giliran ini dipilih bukan balasan sebelumnya`() {
        val before = setOf("u1", "a1")
        val messages = listOf(
            ChatItem.User("u1", "halo", 0),
            asst("a1", "jawaban lama"),
            asst("a2", "jawaban baru"),
        )
        assertEquals("jawaban baru", replyForTurn(before, messages)?.texts?.first())
    }

    @Test
    fun `assistant yang id-nya sudah ada sebelum giliran tidak dipilih`() {
        val messages = listOf(
            ChatItem.User("u1", "halo", 0),
            asst("a1", "jawaban lama"),
        )
        assertNull(replyForTurn(setOf("u1", "a1"), messages))
    }

    @Test
    fun `balasan yang masih menggantung tidak dibacakan`() {
        val messages = listOf(asst("a2", "setengah jadi", finish = null))
        assertNull(replyForTurn(emptySet(), messages))
    }

    @Test
    fun `giliran yang masih tool-calls belum selesai`() {
        val messages = listOf(asst("a2", "langkah", finish = "tool-calls"))
        assertNull(replyForTurn(emptySet(), messages))
    }

    @Test
    fun `yang menggantung dilewati dan yang selesai tetap terpakai`() {
        // Dua assistant di luar snapshot: satu selesai, satu masih berjalan.
        // Mengambil yang terbaru tanpa syarat akan memilih yang belum selesai.
        val messages = listOf(
            asst("a2", "jawaban lengkap", finish = "stop"),
            asst("a3", "sedang berjalan", finish = null),
        )
        assertEquals("jawaban lengkap", replyForTurn(emptySet(), messages)?.texts?.first())
    }

    @Test
    fun `galat assistant ikut terbawa supaya bisa ditampilkan`() {
        val messages = listOf(asst("a2", "", finish = "error", error = "model tidak tersedia"))
        assertEquals("model tidak tersedia", replyForTurn(emptySet(), messages)?.error)
    }

    @Test
    fun `aturan selesai dipakai sama oleh polling maupun buddy`() {
        assertFalse(isTerminalFinish(null))
        assertFalse(isTerminalFinish("tool-calls"))
        assertTrue(isTerminalFinish("stop"))
        assertTrue(isTerminalFinish("length"))
    }
}
