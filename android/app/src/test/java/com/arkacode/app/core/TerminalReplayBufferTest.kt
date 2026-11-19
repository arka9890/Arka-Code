package com.arkacode.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalReplayBufferTest {

    @Test
    fun `snapshot mengembalikan byte dengan urutan sama`() {
        val buf = TerminalReplayBuffer(maxBytes = 64)
        buf.record("abc".toByteArray())
        buf.record("def".toByteArray())
        assertArrayEquals("abcdef".toByteArray(), buf.snapshot())
    }

    @Test
    fun `melebihi batas membuang byte tertua`() {
        val buf = TerminalReplayBuffer(maxBytes = 4)
        buf.record("abcdef".toByteArray())
        assertArrayEquals("cdef".toByteArray(), buf.snapshot())
    }

    @Test
    fun `potongan lebih besar dari batas menyisakan ekor`() {
        val buf = TerminalReplayBuffer(maxBytes = 3)
        buf.record("abcdefgh".toByteArray())
        assertArrayEquals("fgh".toByteArray(), buf.snapshot())
    }

    @Test
    fun `snapshot tidak mengosongkan buffer`() {
        val buf = TerminalReplayBuffer(maxBytes = 16)
        buf.record("x".toByteArray())
        assertEquals(1, buf.snapshot().size)
        assertEquals(1, buf.snapshot().size)
    }
}
