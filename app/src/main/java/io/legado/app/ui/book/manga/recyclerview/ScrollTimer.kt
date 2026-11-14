package io.legado.app.ui.book.manga.recyclerview

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.book.read.page.AutoReadSession
import io.legado.app.ui.book.read.page.AutoScrollMath
import io.legado.app.ui.book.read.page.FrameTicker

/**
 * Coordinates vsync continuous scrolling and timed page turning for manga reading.
 */
class ScrollTimer(
    private val callback: ScrollCallback,
    private val recyclerView: RecyclerView,
) : RecyclerView.OnScrollListener() {

    enum class Mode {
        NONE,
        CONTINUOUS_SCROLL,
        TIMED_PAGE,
    }

    var mode: Mode = Mode.NONE
        private set

    private val session = AutoReadSession()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = FrameTicker { elapsedMs -> onScrollFrame(elapsedMs) }
    private val pageTurnRunnable = Runnable { onTimedPageTick() }

    private var scrollSpeedLevel = AutoScrollMath.DEFAULT_MANGA_SCROLL_SPEED
    private var pageTurnSeconds = AutoScrollMath.MIN_MANGA_PAGE_SECONDS
    private var remainderPx = 0.0
    private var isUserScrolling = false
    private var isWaitingForContent = false

    val isRunning: Boolean
        get() = session.isRunning && mode != Mode.NONE

    val isActivelyRunning: Boolean
        get() = isRunning && !isPaused

    private val isPaused: Boolean
        get() = session.isPaused || isUserScrolling || isWaitingForContent

    init {
        recyclerView.addOnScrollListener(this)
    }

    fun startContinuousScroll(speedLevel: Int) {
        scrollSpeedLevel = AutoScrollMath.clampMangaScrollSpeed(speedLevel)
        startMode(Mode.CONTINUOUS_SCROLL)
    }

    fun startTimedPage(seconds: Int) {
        pageTurnSeconds = AutoScrollMath.clampMangaPageSeconds(seconds)
        startMode(Mode.TIMED_PAGE)
    }

    private fun startMode(targetMode: Mode) {
        mode = targetMode
        session.start(0)
        isUserScrolling = recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
        isWaitingForContent = false
        remainderPx = 0.0
        updateDrivers()
    }

    fun stop() {
        clearDrivers()
        mode = Mode.NONE
        session.stop()
        isUserScrolling = false
        isWaitingForContent = false
        remainderPx = 0.0
    }

    fun release() {
        stop()
        recyclerView.removeOnScrollListener(this)
    }

    fun setScrollSpeedLevel(level: Int) {
        scrollSpeedLevel = AutoScrollMath.clampMangaScrollSpeed(level)
        if (isRunning && mode == Mode.CONTINUOUS_SCROLL) {
            ticker.resetClock()
        }
    }

    fun setPageTurnSeconds(seconds: Int) {
        pageTurnSeconds = AutoScrollMath.clampMangaPageSeconds(seconds)
        if (isRunning && mode == Mode.TIMED_PAGE) {
            updateDrivers()
        }
    }

    fun setTouchPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.TOUCH, paused)
    }

    fun setMenuPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.MENU, paused)
    }

    fun setLifecyclePaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.LIFECYCLE, paused)
    }

    private fun updatePauseReason(reason: AutoReadSession.PauseReason, paused: Boolean) {
        if (session.setPaused(reason, paused)) {
            updateDrivers()
        }
    }

    fun onContentUpdated() {
        if (!isRunning || !isWaitingForContent) return
        isWaitingForContent = false
        remainderPx = 0.0
        updateDrivers()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        val scrolling = newState != RecyclerView.SCROLL_STATE_IDLE
        if (isUserScrolling != scrolling) {
            isUserScrolling = scrolling
            if (!scrolling) {
                ticker.resetClock()
            }
            updateDrivers()
        }
    }

    private fun clearDrivers() {
        ticker.stop()
        handler.removeCallbacks(pageTurnRunnable)
    }

    private fun updateDrivers() {
        clearDrivers()
        if (!isRunning || isPaused) return
        when (mode) {
            Mode.CONTINUOUS_SCROLL -> ticker.start()
            Mode.TIMED_PAGE -> handler.postDelayed(pageTurnRunnable, pageTurnSeconds * 1000L)
            Mode.NONE -> Unit
        }
    }

    private fun onScrollFrame(elapsedMs: Long): Boolean {
        if (!isRunning || mode != Mode.CONTINUOUS_SCROLL || isPaused) return false
        val speedPxPerSec = AutoScrollMath.mangaScrollPxPerSec(
            scrollSpeedLevel,
            recyclerView.resources.displayMetrics.density,
        )
        val step = AutoScrollMath.accumulateStep(remainderPx, elapsedMs, speedPxPerSec)
        remainderPx = step.nextRemainderPx
        if (step.deltaPx <= 0) return true

        if (callback.scrollBy(step.deltaPx)) {
            return true
        }
        handleReachedCurrentEnd()
        return false
    }

    private fun onTimedPageTick() {
        if (!isRunning || mode != Mode.TIMED_PAGE || isPaused) return
        if (callback.canAutoScrollMore() && callback.scrollPage()) {
            updateDrivers()
            return
        }
        handleReachedCurrentEnd()
    }

    private fun handleReachedCurrentEnd() {
        if (callback.hasMoreChaptersToLoad()) {
            isWaitingForContent = true
            clearDrivers()
            callback.requestNextChapterForAutoScroll()
        } else {
            stop()
            callback.onAutoScrollReachedEnd()
        }
    }

    interface ScrollCallback {
        fun scrollBy(distance: Int): Boolean
        fun scrollPage(): Boolean
        fun canAutoScrollMore(): Boolean
        fun hasMoreChaptersToLoad(): Boolean
        fun requestNextChapterForAutoScroll()
        fun onAutoScrollReachedEnd()
    }
}
