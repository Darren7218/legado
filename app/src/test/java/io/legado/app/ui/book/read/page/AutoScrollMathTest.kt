package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollMathTest {

    @Test
    fun accumulateStep_accumulatesSubPixelsAcrossHighRefreshFrames() {
        var remainder = 0.0
        var totalMoved = 0
        // 60 px/sec at 120Hz (8ms per frame) moves 0.48px per frame -> must accumulate instead of 0.
        repeat(100) {
            val step = AutoScrollMath.accumulateStep(remainder, elapsedMs = 8L, speedPxPerSec = 60.0)
            totalMoved += step.deltaPx
            remainder = step.nextRemainderPx
        }
        // Binary floating point keeps 47px moved + ~0.999999999999986px remainder = 48.0px total.
        assertEquals(48.0, totalMoved + remainder, 1e-9)
        assertTrue(totalMoved in 47..48)
        assertTrue(remainder in 0.0..<1.0)

        // Binary-exact 0.25px per frame (50 px/s @ 5ms) must reach exact integer pixels with 0 remainder.
        var exactRemainder = 0.0
        var exactMoved = 0
        repeat(100) {
            val step = AutoScrollMath.accumulateStep(exactRemainder, elapsedMs = 5L, speedPxPerSec = 50.0)
            exactMoved += step.deltaPx
            exactRemainder = step.nextRemainderPx
        }
        assertEquals(25, exactMoved)
        assertEquals(0.0, exactRemainder, 1e-12)
    }

    @Test
    fun accumulateStep_clampsLongFrameStalls() {
        val step = AutoScrollMath.accumulateStep(
            remainderPx = 0.0,
            elapsedMs = 5_000L,
            speedPxPerSec = 100.0,
        )
        // Clamped to MAX_FRAME_ELAPSED_MS (100ms) -> 10px instead of 500px
        assertEquals(10, step.deltaPx)
    }

    @Test
    fun accumulateStep_handlesNonPositiveInputsSafely() {
        val zeroTime = AutoScrollMath.accumulateStep(0.4, elapsedMs = 0L, speedPxPerSec = 100.0)
        assertEquals(0, zeroTime.deltaPx)
        assertEquals(0.4, zeroTime.nextRemainderPx, 1e-6)

        val zeroSpeed = AutoScrollMath.accumulateStep(0.4, elapsedMs = 16L, speedPxPerSec = 0.0)
        assertEquals(0, zeroSpeed.deltaPx)
        assertEquals(0.4, zeroSpeed.nextRemainderPx, 1e-6)
    }

    @Test
    fun sliderMapping_increasesSpeedMonotonicallyLeftToRight() {
        assertEquals(AutoScrollMath.MAX_READ_SPEED_SECONDS, AutoScrollMath.sliderProgressToSeconds(0))
        assertEquals(AutoScrollMath.MIN_READ_SPEED_SECONDS, AutoScrollMath.sliderProgressToSeconds(100))

        var previousSeconds = Int.MAX_VALUE
        for (progress in 0..AutoScrollMath.SLIDER_MAX) {
            val sec = AutoScrollMath.sliderProgressToSeconds(progress)
            assertTrue("Expected non-increasing seconds at $progress", sec <= previousSeconds)
            previousSeconds = sec
        }

        // Verify fine precision in the common 1s..15s reading range (>= 30% of slider travel).
        val progress15s = AutoScrollMath.secondsToSliderProgress(15)
        assertTrue("1s..15s should occupy at least 30 ticks, starts at $progress15s", 100 - progress15s >= 30)
    }

    @Test
    fun secondsToSliderProgress_roundTripsEndpointsAndCommonValues() {
        assertEquals(0, AutoScrollMath.secondsToSliderProgress(120))
        assertEquals(100, AutoScrollMath.secondsToSliderProgress(1))
        val roundTrip = AutoScrollMath.sliderProgressToSeconds(AutoScrollMath.secondsToSliderProgress(10))
        assertTrue(kotlin.math.abs(roundTrip - 10) <= 1)
    }

    @Test
    fun mangaSpeed_scalesWithDensityAndClampsBounds() {
        assertEquals(
            20.0 * AutoScrollMath.MIN_MANGA_SCROLL_SPEED * 2.0,
            AutoScrollMath.mangaScrollPxPerSec(-5, 2f),
            1e-6,
        )
        assertEquals(
            20.0 * AutoScrollMath.MAX_MANGA_SCROLL_SPEED * 3.0,
            AutoScrollMath.mangaScrollPxPerSec(99, 3f),
            1e-6,
        )
        assertEquals(1, AutoScrollMath.clampMangaPageSeconds(0))
        assertEquals(120, AutoScrollMath.clampMangaPageSeconds(999))
    }
}
