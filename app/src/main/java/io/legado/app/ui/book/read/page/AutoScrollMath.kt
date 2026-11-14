package io.legado.app.ui.book.read.page

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure math helpers for continuous auto-scroll timing and speed slider mapping.
 */
object AutoScrollMath {

    const val MIN_READ_SPEED_SECONDS = 1
    const val MAX_READ_SPEED_SECONDS = 120
    const val MAX_FRAME_ELAPSED_MS = 100L
    const val SLIDER_MAX = 100

    const val MIN_MANGA_SCROLL_SPEED = 1
    const val MAX_MANGA_SCROLL_SPEED = 20
    const val DEFAULT_MANGA_SCROLL_SPEED = 5
    const val MIN_MANGA_PAGE_SECONDS = 1
    const val MAX_MANGA_PAGE_SECONDS = 120

    data class StepResult(
        val deltaPx: Int,
        val nextRemainderPx: Double,
    )

    fun clampAutoReadSeconds(seconds: Int): Int {
        return seconds.coerceIn(MIN_READ_SPEED_SECONDS, MAX_READ_SPEED_SECONDS)
    }

    fun speedPxPerSecForScreenDuration(viewportHeightPx: Int, secondsPerScreen: Int): Double {
        if (viewportHeightPx <= 0) return 0.0
        return viewportHeightPx.toDouble() / clampAutoReadSeconds(secondsPerScreen).toDouble()
    }

    /**
     * Accumulates sub-pixel scroll distance across variable-rate frames while clamping
     * unusually large frame gaps to avoid sudden visual jumps.
     */
    fun accumulateStep(
        remainderPx: Double,
        elapsedMs: Long,
        speedPxPerSec: Double,
        maxFrameElapsedMs: Long = MAX_FRAME_ELAPSED_MS,
    ): StepResult {
        if (elapsedMs <= 0L || speedPxPerSec <= 0.0) {
            return StepResult(0, remainderPx.coerceAtLeast(0.0))
        }
        val clampedElapsed = elapsedMs.coerceIn(0L, maxFrameElapsedMs)
        val total = remainderPx.coerceAtLeast(0.0) + (speedPxPerSec * clampedElapsed) / 1000.0
        val delta = total.toInt().coerceAtLeast(0)
        val nextRemainder = (total - delta).coerceAtLeast(0.0)
        return StepResult(delta, nextRemainder)
    }

    /**
     * Maps slider progress [0..maxProgress] to seconds-per-screen [120..1], where dragging
     * right increases scroll speed and gives finer precision in the common 1s..15s range.
     */
    fun sliderProgressToSeconds(progress: Int, maxProgress: Int = SLIDER_MAX): Int {
        val safeMax = maxProgress.coerceAtLeast(1)
        val clamped = progress.coerceIn(0, safeMax)
        val fraction = (safeMax - clamped).toDouble() / safeMax.toDouble()
        val span = (MAX_READ_SPEED_SECONDS - MIN_READ_SPEED_SECONDS).toDouble()
        val raw = MIN_READ_SPEED_SECONDS + fraction * fraction * span
        return clampAutoReadSeconds(raw.roundToInt())
    }

    fun secondsToSliderProgress(seconds: Int, maxProgress: Int = SLIDER_MAX): Int {
        val safeMax = maxProgress.coerceAtLeast(1)
        val clamped = clampAutoReadSeconds(seconds)
        val span = (MAX_READ_SPEED_SECONDS - MIN_READ_SPEED_SECONDS).toDouble()
        val fraction = sqrt((clamped - MIN_READ_SPEED_SECONDS).toDouble() / span)
        return ((1.0 - fraction) * safeMax.toDouble()).roundToInt().coerceIn(0, safeMax)
    }

    fun clampMangaScrollSpeed(speed: Int): Int {
        return speed.coerceIn(MIN_MANGA_SCROLL_SPEED, MAX_MANGA_SCROLL_SPEED)
    }

    fun mangaScrollPxPerSec(speedLevel: Int, density: Float): Double {
        val safeDensity = density.coerceAtLeast(1f).toDouble()
        return 20.0 * clampMangaScrollSpeed(speedLevel) * safeDensity
    }

    fun clampMangaPageSeconds(seconds: Int): Int {
        return seconds.coerceIn(MIN_MANGA_PAGE_SECONDS, MAX_MANGA_PAGE_SECONDS)
    }
}
