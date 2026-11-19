package com.arkacode.app.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartsTest {

    /** `parseMessage` adalah fungsi instance — klien dipakai tanpa pernah melakukan request. */
    private val client = OpenCodeClient(port = 0)

    private fun parse(json: String) = client.parseMessage(Json.parseToJsonElement(json))

    @Test
    fun `part file jadi ChatFile`() {
        val item = parse(
            """
            {"info":{"id":"m1","role":"assistant","finish":"stop"},
             "parts":[{"type":"file","mime":"image/png","filename":"chart.png","url":"file:///root/workspace/sessions/s1/chart.png"}]}
            """.trimIndent(),
        ) as ChatItem.Assistant
        assertEquals(1, item.files.size)
        assertEquals("chart.png", item.files[0].name)
        assertEquals(ChatFile.Kind.IMAGE, item.files[0].kind)
    }

    @Test
    fun `part file tanpa nama atau url diabaikan`() {
        val item = parse(
            """
            {"info":{"id":"m2","role":"assistant","finish":"stop"},
             "parts":[{"type":"file","mime":"image/png"},{"type":"text","text":"halo"}]}
            """.trimIndent(),
        ) as ChatItem.Assistant
        assertTrue(item.files.isEmpty())
        assertEquals(listOf("halo"), item.texts)
    }

    @Test
    fun `mime dari ekstensi`() {
        assertEquals("image/webp", ChatFile.mimeOf("a.webp"))
        assertEquals("video/mp4", ChatFile.mimeOf("a.mp4"))
        assertEquals("application/octet-stream", ChatFile.mimeOf("tanpa-ekstensi"))
    }
}