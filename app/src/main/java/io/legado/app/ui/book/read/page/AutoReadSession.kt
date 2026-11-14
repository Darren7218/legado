package io.legado.app.ui.book.read.page

import android.os.SystemClock

/**
 * Contract shared by page/scroll and paragraph auto-read controllers.
 */
interface AutoReadController {
    val isRunning: Boolean
    val isUserPaused: Boolean
    fun start()
    fun stop()
    fun setUserPaused(paused: Boolean)
    fun setTouchPaused(paused: Boolean)
    fun setMenuPaused(paused: Boolean)
    fun setLifecyclePaused(paused: Boolean)
    fun setSleepTimerMinutes(minutes: Int)
    fun remainingSleepMs(): Long
    fun refreshConfig()
    fun onChapterSelected(chapterIndex: Int)
}

/**
 * Tracks independent pause sources, chapter-end guards, and sleep timer state for auto-read.
 */
class AutoReadSession(
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    enum class PauseReason {
        USER,
        TOUCH,
        MENU,
        LIFECYCLE,
    }

    private val pauseReasons = HashSet<PauseReason>(4)

    var isRunning = false
        private set
    var startChapterIndex = 0
        private set
    var sleepDeadlineMs: Long? = null
        private set

    val isPaused: Boolean get() = pauseReasons.isNotEmpty()
    val isUserPaused: Boolean get() = PauseReason.USER in pauseReasons
    val isTouchPaused: Boolean get() = PauseReason.TOUCH in pauseReasons
    val isMenuPaused: Boolean get() = PauseReason.MENU in pauseReasons
    val isLifecyclePaused: Boolean get() = PauseReason.LIFECYCLE in pauseReasons

    fun start(chapterIndex: Int) {
        isRunning = true
        pauseReasons.clear()
        startChapterIndex = chapterIndex
        sleepDeadlineMs = null
    }

    fun stop() {
        isRunning = false
        pauseReasons.clear()
        sleepDeadlineMs = null
    }

    fun setPaused(reason: PauseReason, paused: Boolean): Boolean {
        if (!isRunning) return false
        return if (paused) pauseReasons.add(reason) else pauseReasons.remove(reason)
    }

    fun setUserPaused(paused: Boolean): Boolean = setPaused(PauseReason.USER, paused)

    fun setTouchPaused(paused: Boolean): Boolean = setPaused(PauseReason.TOUCH, paused)

    fun setMenuPaused(paused: Boolean): Boolean = setPaused(PauseReason.MENU, paused)

    fun setLifecyclePaused(paused: Boolean): Boolean = setPaused(PauseReason.LIFECYCLE, paused)

    fun updateStartChapterIndex(chapterIndex: Int) {
        startChapterIndex = chapterIndex
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepDeadlineMs = if (minutes <= 0) {
            null
        } else {
            nowMs() + minutes * 60_000L
        }
    }

    fun remainingSleepMs(): Long {
        val deadline = sleepDeadlineMs ?: return 0L
        return (deadline - nowMs()).coerceAtLeast(0L)
    }

    fun isSleepTimerExpired(): Boolean {
        val deadline = sleepDeadlineMs ?: return false
        return nowMs() >= deadline
    }
}
