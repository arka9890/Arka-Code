package com.arkacode.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji logika murni wajah Buddy (tanpa Compose): semua nilai geometri wajib
 * tetap di dalam rentang aman yang dipakai Canvas, dan animasi harus melingkar
 * mulus (frame pada t dan t+CYCLE identik) supaya tak ada lompatan tiap menit.
 */
class BuddyFaceTest {

    private val moods = BuddyMood.entries
    private val samples = (0..119).map { it * 500f } // 0..59.500 ms, kelipatan 500

    @Test
    fun frame_keepsEveryValueInRange() {
        for (mood in moods) {
            for (t in samples) {
                val f = buddyFrame(mood, t)
                val where = "$mood@$t"
                assertTrue("$where eyeOpen", f.eyeOpen in 0f..1f)
                assertTrue("$where mouthOpen", f.mouthOpen in 0f..1f)
                assertTrue("$where mouthCurve", f.mouthCurve in -1f..1f)
                assertTrue("$where lookX", f.lookX in -1f..1f)
                assertTrue("$where lookY", f.lookY in -1f..1f)
                assertTrue("$where bounce", f.bounce in -1f..1f)
                // squash harus positif (rx = r / squash) dan tak ekstrem.
                assertTrue("$where squash", f.squash > 0.5f && f.squash < 1.5f)
            }
        }
    }

    @Test
    fun frame_loopsSmoothlyAtCycleBoundary() {
        // Nilai di-wrap dari CYCLE_MS (60_000) ke 0: kedua sisi harus identik,
        // kalau tidak wajah akan "melompat" sekali tiap menit.
        for (mood in moods) {
            for (t in listOf(0f, 1_500f, 3_070f, 12_345f, 59_999f)) {
                val a = buddyFrame(mood, t)
                val b = buddyFrame(mood, t + 60_000f)
                assertEquals("$mood@$t eyeOpen", a.eyeOpen, b.eyeOpen, 1e-4f)
                assertEquals("$mood@$t mouthOpen", a.mouthOpen, b.mouthOpen, 1e-4f)
                assertEquals("$mood@$t squash", a.squash, b.squash, 1e-4f)
            }
        }
    }

    @Test
    fun blink_actuallyClosesTheEyes() {
        // Kedipan terjadi pada fase 0..140 ms dalam siklus 3 detik.
        val midBlink = buddyFrame(BuddyMood.IDLE, 70f)
        assertTrue("mata harus hampir tertutup saat kedip", midBlink.eyeOpen < 0.05f)
        val open = buddyFrame(BuddyMood.IDLE, 1_000f)
        assertTrue("mata terbuka di luar jendela kedip", open.eyeOpen > 0.99f)
    }

    @Test
    fun talking_opensAndClosesTheMouth() {
        val times = (0..199).map { it * 17f }
        val opens = times.map { buddyFrame(BuddyMood.TALKING, it).mouthOpen }
        assertTrue("mulut harus terbuka lebar saat bicara", opens.max() > 0.8f)
        assertTrue("mulut harus tertutup saat bicara", opens.min() < 0.2f)
    }

    @Test
    fun happy_keepsEyesShutAndSmiles() {
        for (t in samples) {
            val f = buddyFrame(BuddyMood.HAPPY, t)
            assertEquals("mata bahagia selalu terpejam", 0f, f.eyeOpen, 0f)
            assertEquals("mata bahagia selalu senyum", 1f, f.mouthCurve, 0f)
        }
    }

    @Test
    fun thinking_looksUpAndToTheRight() {
        val f = buddyFrame(BuddyMood.THINKING, 4_000f)
        assertTrue("arah pandang ke atas", f.lookY < 0f)
        assertTrue("arah pandang ke kanan", f.lookX > 0f)
    }

    @Test
    fun listening_keepsEyesWide() {
        for (t in samples) {
            val f = buddyFrame(BuddyMood.LISTENING, t)
            assertTrue("mendengar = mata melebar ($t)", f.eyeOpen > 0.5f)
        }
    }
}
