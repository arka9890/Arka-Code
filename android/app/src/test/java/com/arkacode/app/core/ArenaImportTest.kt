package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArenaImportTest {

    @Test
    fun `nama berkas diambil dari Content-Disposition`() {
        assertEquals(
            "laporan.pdf",
            ArenaImport.fileNameFrom("attachment; filename=laporan.pdf")
        )
    }

    @Test
    fun `nama berkas berpetik juga terbaca`() {
        assertEquals(
            "data set.csv",
            ArenaImport.fileNameFrom("attachment; filename=\"data set.csv\"; size=12")
        )
    }

    @Test
    fun `tanpa nama di Content-Disposition`() {
        assertNull(ArenaImport.fileNameFrom(null))
        assertNull(ArenaImport.fileNameFrom("inline"))
        assertNull(ArenaImport.fileNameFrom("attachment; filename="))
    }
}
