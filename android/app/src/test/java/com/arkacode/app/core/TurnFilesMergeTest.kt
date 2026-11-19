package com.arkacode.app.core

import com.arkacode.app.net.ChatFile
import com.arkacode.app.net.ChatItem
import org.junit.Assert.assertEquals
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
}