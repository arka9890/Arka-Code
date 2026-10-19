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
                assertTrue("$where armRaise", f.armRaise in 0f..1f)
                assertTrue("$where armWave", f.armWave in -1f..1f)
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

    @Test
    fun figure_stacksHeadBodyAndFeetTopDown() {
        for (mood in moods) {
            for (t in samples) {
                val f = buddyFigure(300f, 600f, buddyFrame(mood, t))
                val where = "$mood@$t"
                assertTrue("$where unit", f.unit > 0f)
                assertTrue("$where kepala di atas badan", f.headCy < f.bodyTop)
                assertTrue("$where badan di atas kaki", f.bodyBottom < f.footY)
            }
        }
    }

    @Test
    fun figure_fitsInsideCanvas() {
        // Kanvas tinggi (kartu potret) dan lebar (lanskap) sama-sama harus muat.
        for ((w, h) in listOf(300f to 600f, 360f to 460f, 600f to 280f)) {
            for (mood in moods) {
                for (t in samples) {
                    val f = buddyFigure(w, h, buddyFrame(mood, t))
                    val where = "$mood@$t ${w}x$h"
                    // Figur penuh (ahoge/gelembung .. bayangan) di dalam kanvas.
                    val top = f.headCy + FIGURE_TOP_U * f.unit
                    val bottom = f.groundY + 0.10f * f.unit
                    assertTrue("$where atas di dalam kanvas", top >= 0f)
                    assertTrue("$where bawah di dalam kanvas", bottom <= h)
                    val half = f.unit * FIGURE_HALF_W_U
                    assertTrue("$where melebar di dalam kanvas", f.headCx + half <= w)
                    assertTrue("$where kaki di atas bayangan", f.footY < f.groundY + 0.05f * f.unit)
                }
            }
        }
    }

    @Test
    fun figure_shadowStaysOnTheGround() {
        // Bayangan tanah tidak ikut melayang meski kepala naik-turun.
        val ys = samples.map { buddyFigure(360f, 460f, buddyFrame(BuddyMood.HAPPY, it)) }
        assertEquals(ys.minOf { it.groundY }, ys.maxOf { it.groundY }, 1e-3f)
        assertTrue("kepala harus melayang", ys.maxOf { it.headCy } - ys.minOf { it.headCy } > 1f)
    }

    @Test
    fun happy_raisesArms_idleKeepsThemDown() {
        for (t in samples) {
            assertTrue("bahagia = tangan terangkat ($t)", buddyFrame(BuddyMood.HAPPY, t).armRaise > 0.7f)
            assertTrue("diam = tangan di samping ($t)", buddyFrame(BuddyMood.IDLE, t).armRaise < 0.1f)
        }
    }

    @Test
    fun talking_wavesHand() {
        val waves = (0..199).map { buddyFrame(BuddyMood.TALKING, it * 17f).armWave }
        assertTrue("tangan melambai bolak-balik", waves.max() > 0.3f && waves.min() < -0.3f)
    }

    @Test
    fun mouthShape_doesNotFlickerBetweenStyles() {
        // Mulut berganti gaya pada ambang 0,25 (buka) dan 0,3 (kucing). Mood
        // yang mulutnya "diam" tak boleh bolak-balik melewati ambang itu.
        for (t in samples) {
            assertTrue(buddyFrame(BuddyMood.LISTENING, t).mouthOpen > 0.25f)
            assertTrue(buddyFrame(BuddyMood.HAPPY, t).mouthOpen > 0.25f)
            assertTrue(buddyFrame(BuddyMood.THINKING, t).mouthCurve < 0.3f)
            assertTrue(buddyFrame(BuddyMood.IDLE, t).mouthCurve > 0.3f)
        }
    }
}
