package com.arkacode.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalOutputTest {

    @Test
    fun `potongan kecil tidak langsung di-flush`() {
        val buf = TerminalOutputBuffer(maxBytes = 8)
        assertNull(buf.append("abc".toByteArray(), 3))
    }

    @Test
    fun `melewati batas mengembalikan potongan`() {
        val buf = TerminalOutputBuffer(maxBytes = 4)
        assertArrayEquals("abcd".toByteArray(), buf.append("abcd".toByteArray(), 4))
    }

    @Test
    fun `drain mengembalikan sisa lalu kosong`() {
        val buf = TerminalOutputBuffer(maxBytes = 64)
        buf.append("halo".toByteArray(), 4)
        assertArrayEquals("halo".toByteArray(), buf.drain())
        assertEquals(0, buf.drain().size)
    }

    @Test
    fun `length menghormati isi buffer yang lebih pendek`() {
        val buf = TerminalOutputBuffer(maxBytes = 64)
        buf.append("abcdXXXX".toByteArray(), 4)
        assertArrayEquals("abcd".toByteArray(), buf.drain())
    }
}
