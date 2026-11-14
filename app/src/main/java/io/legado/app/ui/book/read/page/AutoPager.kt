package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.withClip
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeeded
import io.legado.app.utils.toastOnUi

/**
 * Vsync-driven auto-scroll and auto-page-turn controller for ReadView.
 */
class AutoPager(private val readView: ReadView) : AutoReadController {

    private enum class Mode {
        SCROLL,
        E_INK_TIMER,
        TIMED_PAGE,
        WIPE,
    }

    private val session = AutoReadSession()
    private val handler = Handler(Looper.getMainLooper())
    private val scrollTicker = FrameTicker { elapsedMs -> onFrameTick(elapsedMs) }
    private val sleepTimer = SleepTimerDriver(session, handler) {
        if (session.isLifecyclePaused) {
            readView.callBack.autoPageStop()
        } else {
            stopWithMessage(R.string.auto_scroll_stopped_timer)
        }
    }

    private var canvasRecorder = CanvasRecorderFactory.create()
    private var progressPx = 0
    private var remainderPx = 0.0
    private var isAnimPaused = false
    private val wipeRect = Rect()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
    }

    private val pageTurnRunnable = Runnable { onTimedPageTurn() }

    override val isRunning: Boolean get() = session.isRunning
    override val isUserPaused: Boolean get() = session.isUserPaused
    val isPaused: Boolean get() = session.isPaused || isAnimPaused

    override fun start() {
        session.start(ReadBook.durChapterIndex)
        progressPx = 0
        remainderPx = 0.0
        isAnimPaused = false
        canvasRecorder.invalidate()
        scheduleDriver()
    }

    override fun stop() {
        if (!session.isRunning) return
        clearDrivers()
        progressPx = 0
        remainderPx = 0.0
        isAnimPaused = false
        canvasRecorder.invalidate()
        session.stop()
        readView.invalidate()
    }

    override fun setUserPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.USER, paused)
    }

    override fun setTouchPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.TOUCH, paused)
    }

    override fun setMenuPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.MENU, paused)
    }

    override fun setLifecyclePaused(paused: Boolean) {
        if (!paused && isRunning && session.isSleepTimerExpired()) {
            stopWithMessage(R.string.auto_scroll_stopped_timer)
            return
        }
        updatePauseReason(AutoReadSession.PauseReason.LIFECYCLE, paused)
    }

    private fun updatePauseReason(reason: AutoReadSession.PauseReason, paused: Boolean) {
        if (session.setPaused(reason, paused)) {
            scheduleDriver()
        }
    }

    /**
     * Pauses auto-paging during scroll/page animations or PdfZoom gestures.
     */
    fun pause() {
        if (!isRunning || isAnimPaused) return
        isAnimPaused = true
        scheduleDriver()
    }

    /**
     * Releases animation or PdfZoom pause state.
     */
    fun resume() {
        if (!isRunning || !isAnimPaused) return
        isAnimPaused = false
        scheduleDriver()
    }

    override fun setSleepTimerMinutes(minutes: Int) {
        sleepTimer.schedule(minutes)
    }

    override fun remainingSleepMs(): Long = session.remainingSleepMs()

    override fun refreshConfig() {
        if (!isRunning) return
        remainderPx = 0.0
        scrollTicker.resetClock()
        scheduleDriver()
    }

    override fun onChapterSelected(chapterIndex: Int) {
        if (!isRunning) return
        session.updateStartChapterIndex(chapterIndex)
        progressPx = 0
        remainderPx = 0.0
        canvasRecorder.invalidate()
        scheduleDriver()
    }

    fun invalidatePreview() {
        canvasRecorder.invalidate()
    }

    fun reset() {
        progressPx = 0
        remainderPx = 0.0
        canvasRecorder.invalidate()
        if (!isRunning) return
        val currentChapter = ReadBook.durChapterIndex
        if (!isPaused && AppConfig.autoReadStopAtChapterEnd && currentChapter != session.startChapterIndex) {
            stopWithMessage(R.string.auto_scroll_stopped_chapter_end)
            return
        }
        session.updateStartChapterIndex(currentChapter)
        scheduleDriver()
    }

    fun upRecorder() {
        canvasRecorder.recycle()
        canvasRecorder = CanvasRecorderFactory.create()
    }

    fun onDraw(canvas: Canvas) {
        if (!isRunning || currentMode() != Mode.WIPE) return
        val width = readView.width
        val height = readView.height
        if (width <= 0 || height <= 0 || progressPx <= 0) return

        val clamped = progressPx.coerceIn(0, height)
        if (clamped < height && !isBlockedAtChapterEnd() && readView.pageFactory.hasNext()) {
            canvasRecorder.recordIfNeeded(readView.nextPage)
            if (canvasRecorder.width > 0 && canvasRecorder.height > 0) {
                wipeRect.set(0, 0, width, clamped)
                canvas.withClip(wipeRect) {
                    canvasRecorder.draw(this)
                }
            }
        }
        linePaint.color = ThemeStore.accentColor
        val y = clamped.toFloat()
        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
    }

    private fun currentMode(): Mode {
        return when {
            readView.isScroll -> Mode.SCROLL
            AppConfig.isEInkMode -> Mode.E_INK_TIMER
            AppConfig.autoReadTimedPageTurn -> Mode.TIMED_PAGE
            else -> Mode.WIPE
        }
    }

    private fun clearDrivers() {
        scrollTicker.stop()
        handler.removeCallbacks(pageTurnRunnable)
        sleepTimer.cancel()
    }

    private fun scheduleDriver() {
        scrollTicker.stop()
        handler.removeCallbacks(pageTurnRunnable)
        if (!isRunning || isPaused) return

        when (currentMode()) {
            Mode.SCROLL, Mode.WIPE -> scrollTicker.start()
            Mode.E_INK_TIMER, Mode.TIMED_PAGE -> {
                val delayMs = AutoScrollMath.clampAutoReadSeconds(ReadBookConfig.autoReadSpeed) * 1000L
                handler.postDelayed(pageTurnRunnable, delayMs)
            }
        }
    }

    private fun onFrameTick(elapsedMs: Long): Boolean {
        if (!isRunning || isPaused) return false
        if (session.isSleepTimerExpired()) {
            stopWithMessage(R.string.auto_scroll_stopped_timer)
            return false
        }

        val mode = currentMode()
        if (mode != Mode.SCROLL && mode != Mode.WIPE) {
            scheduleDriver()
            return false
        }
        if (!isChapterContentReady()) {
            return true
        }

        val height = readView.height
        val speed = AutoScrollMath.speedPxPerSecForScreenDuration(height, ReadBookConfig.autoReadSpeed)
        val step = AutoScrollMath.accumulateStep(remainderPx, elapsedMs, speed)
        remainderPx = step.nextRemainderPx
        if (step.deltaPx <= 0) return true

        return when (mode) {
            Mode.SCROLL -> handleScrollStep(step.deltaPx)
            Mode.WIPE -> handleWipeStep(step.deltaPx, height)
        }
    }

    private fun handleScrollStep(deltaPx: Int): Boolean {
        if (readView.curPage.isAtBookEnd()) {
            val messageId = if (AppConfig.autoReadStopAtChapterEnd) {
                R.string.auto_scroll_stopped_chapter_end
            } else {
                R.string.no_next_page
            }
            stopWithMessage(messageId)
            return false
        }
        readView.curPage.scroll(-deltaPx)
        // Synchronous chapter crossings inside scroll() already invoke reset() via onPageChange().
        if (!isRunning) return false
        val currentChapter = ReadBook.durChapterIndex
        if (AppConfig.autoReadStopAtChapterEnd && currentChapter != session.startChapterIndex) {
            stopWithMessage(R.string.auto_scroll_stopped_chapter_end)
            return false
        }
        return true
    }

    private fun isChapterContentReady(): Boolean {
        if (readView.pageFactory.isRefreshingResources || ReadBook.msg != null) return false
        val chapter = ReadBook.curTextChapter ?: return false
        if (!chapter.isCompleted && chapter.pageSize == 0) return false
        val curTextPage = readView.pageFactory.curPage
        return !curTextPage.isMsgPage && curTextPage.pageSize > 0
    }

    private fun isBlockedAtChapterEnd(): Boolean {
        if (!AppConfig.autoReadStopAtChapterEnd) return false
        val curTextPage = readView.pageFactory.curPage
        return !curTextPage.isMsgPage && curTextPage.index >= curTextPage.pageSize - 1
    }

    private fun canAdvanceFromCurrentPage(): Boolean {
        if (isBlockedAtChapterEnd()) {
            stopWithMessage(R.string.auto_scroll_stopped_chapter_end)
            return false
        }
        if (!readView.pageFactory.hasNext()) {
            stopWithMessage(R.string.no_next_page)
            return false
        }
        return true
    }

    private fun handleWipeStep(deltaPx: Int, viewHeight: Int): Boolean {
        if (viewHeight <= 0) return true
        progressPx += deltaPx
        if (progressPx < viewHeight) {
            readView.invalidate()
            return true
        }
        progressPx = 0
        remainderPx = 0.0
        canvasRecorder.invalidate()

        if (!canAdvanceFromCurrentPage()) return false
        if (!readView.fillPage(PageDirection.NEXT)) {
            readView.callBack.autoPageStop()
            return false
        }
        readView.invalidate()
        return true
    }

    private fun onTimedPageTurn() {
        if (!isRunning || isPaused) return
        if (session.isSleepTimerExpired()) {
            stopWithMessage(R.string.auto_scroll_stopped_timer)
            return
        }
        if (!isChapterContentReady()) {
            scheduleDriver()
            return
        }
        if (!canAdvanceFromCurrentPage()) return

        when (currentMode()) {
            Mode.TIMED_PAGE -> {
                val delegate = readView.pageDelegate
                if (delegate != null) {
                    delegate.nextPageByAnim(100)
                    if (isRunning && !isPaused && (!delegate.isRunning || delegate is NoAnimPageDelegate)) {
                        scheduleDriver()
                    }
                } else if (readView.fillPage(PageDirection.NEXT)) {
                    scheduleDriver()
                } else {
                    readView.callBack.autoPageStop()
                }
            }
            Mode.E_INK_TIMER -> {
                if (!readView.fillPage(PageDirection.NEXT)) {
                    readView.callBack.autoPageStop()
                    return
                }
                readView.invalidate()
                scheduleDriver()
            }
            else -> scheduleDriver()
        }
    }

    private fun stopWithMessage(messageResId: Int) {
        readView.context.toastOnUi(messageResId)
        readView.callBack.autoPageStop()
    }
}
