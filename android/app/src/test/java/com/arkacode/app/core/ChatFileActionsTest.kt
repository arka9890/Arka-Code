package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatFileActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `resolve menerima path relatif bersubfolder`() {
        val root = tmp.newFolder("sesi")
        val f = File(root, "out/hasil video.mp4").apply { parentFile?.mkdirs(); writeText("x") }
        assertEquals(f.absolutePath, ChatFileActions.resolve(root, "out/hasil video.mp4")?.absolutePath)
    }

    @Test
    fun `resolve menolak traversal dan absolut`() {
        val root = tmp.newFolder("sesi2")
        assertNull(ChatFileActions.resolve(root, "../rahasia.txt"))
        assertTrue(ChatFileActions.resolve(root, "/etc/passwd") == null)
    }
}