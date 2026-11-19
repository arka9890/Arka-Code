package com.arkacode.app.core

import com.arkacode.app.net.ChatFile
import com.arkacode.app.net.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFilesMergeTest {
    @Test
    fun `berkas hasil giliran ditempelkan ke asisten dan digabung tanpa duplikat`() {
        val asisten = ChatItem.Assistant("a1", listOf("ok"), emptyList(), emptyList(), files = listOf(
            ChatFile("chart.png", "chart.png", 10, "image/png"),
        ))
        val hasil = mergeTurnFiles(listOf(ChatItem.User("u1", "halo", 0), asisten)) { id ->
            if (id == "a1") listOf(
                ChatFile("chart.png", "chart.png", 10, "image/png"),
                ChatFile("hasil.mp4", "hasil.mp4", 20, "video/mp4"),
            ) else emptyList()
        }
        val filed = hasil.filterIsInstance<ChatItem.Assistant>().single().files.map { it.path }
        assertEquals(listOf("chart.png", "hasil.mp4"), filed)
    }

    @Test
    fun `pesan tanpa berkas tidak berubah`() {
        val asisten = ChatItem.Assistant("a1", listOf("ok"), emptyList(), emptyList())
        val hasil = mergeTurnFiles(listOf(asisten)) { emptyList() }
        assertEquals(emptyList<ChatFile>(), hasil.filterIsInstance<ChatItem.Assistant>().single().files)
    }

    @Test
    fun `tanpa asisten baru tidak ada id untuk menyimpan berkas`() {
        val lama = ChatItem.Assistant("a1", listOf("ok"), emptyList(), emptyList())
        assertNull(newAssistantId(listOf(lama), setOf("a1")))
        val baru = ChatItem.Assistant("a2", listOf("x"), emptyList(), emptyList())
        assertEquals("a2", newAssistantId(listOf(lama, baru), setOf("a1")))
    }

    @Test
    fun `asisten yang hanya membawa berkas bukan balasan kosong`() {
        val asisten = ChatItem.Assistant(
            "a1",
            emptyList(),
            emptyList(),
            emptyList(),
            files = listOf(ChatFile("x.png", "x.png", 1, "image/png")),
        )
        assertTrue(hasBody(asisten))
    }
}
