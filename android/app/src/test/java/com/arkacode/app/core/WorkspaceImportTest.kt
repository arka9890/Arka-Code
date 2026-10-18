package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceImportTest {

    @Test
    fun `zip dikenali dari signature bukan cuma ekstensi`() {
        val pk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0)
        assertTrue(WorkspaceImport.isZip("apa", pk))
        assertTrue(WorkspaceImport.isZip("arsip.ZIP", ByteArray(5)))
    }

    @Test
    fun `bukan zip kalau tanpa signature dan tanpa ekstensi`() {
        assertFalse(WorkspaceImport.isZip("catatan.txt", "halo".toByteArray()))
    }

    @Test
    fun `nama berkas tak boleh membawa jalur direktori`() {
        assertEquals("laporan.pdf", WorkspaceImport.safeName("../../laporan.pdf"))
        assertEquals("c.txt", WorkspaceImport.safeName("a/b\\c.txt"))
        assertEquals("upload-file", WorkspaceImport.safeName("   "))
    }
}
