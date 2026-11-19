package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `simpan dan baca ulang`() {
        val root = tmp.newFolder("sesi")
        File(root, "a.png").writeText("x")
        val store = TurnFileStore(tmp.newFolder("data"))
        store.save("m1", listOf("a.png"))
        assertEquals(listOf("a.png"), store.paths("m1"))
        val ulang = TurnFileStore(File(tmp.root, "data"))
        assertEquals(listOf("a.png"), ulang.files("m1", root).map { it.path })
    }

    @Test
    fun `berkas yang sudah dihapus tidak dikembalikan`() {
        val root = tmp.newFolder("sesi")
        File(root, "ada.png").writeText("x")
        val store = TurnFileStore(tmp.newFolder("data2"))
        store.save("m1", listOf("ada.png", "hilang.png"))
        assertEquals(listOf("ada.png"), store.files("m1", root).map { it.path })
    }

    @Test
    fun `dipangkas ke jumlah pesan maksimum`() {
        val dir = tmp.newFolder("data3")
        val store = TurnFileStore(dir, maxMessages = 2)
        store.save("m1", listOf("a"))
        store.save("m2", listOf("b"))
        store.save("m3", listOf("c"))
        val ulang = TurnFileStore(dir, maxMessages = 2)
        assertTrue(ulang.paths("m1").isEmpty())
        assertEquals(listOf("b"), ulang.paths("m2"))
        assertEquals(listOf("c"), ulang.paths("m3"))
    }

    @Test
    fun `id tak dikenal mengembalikan daftar kosong`() {
        assertTrue(TurnFileStore(tmp.newFolder("data4")).paths("nope").isEmpty())
    }
}