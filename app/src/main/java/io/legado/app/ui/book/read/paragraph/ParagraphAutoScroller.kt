package io.legado.app.ui.book.read.paragraph

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.AutoReadController
import io.legado.app.ui.book.read.page.AutoReadSession
import io.legado.app.ui.book.read.page.AutoScrollMath
import io.legado.app.ui.book.read.page.FrameTicker
import io.legado.app.ui.book.read.page.SleepTimerDriver
import io.legado.app.utils.toastOnUi

/**
 * Smooth vsync-driven auto-scroll controller for ParagraphReadView.
 */
class ParagraphAutoScroller(
    private val view: ParagraphReadView,
    private val hasNextChapter: () -> Boolean,
    private val requestNextChapter: () -> Unit,
    private val onStopRequested: () -> Unit,
    private val onStateChanged: () -> Unit,
) : AutoReadController {
    private val session = AutoReadSession()
    private val handler = Handler(Looper.getMainLooper())
    private var remainderPx = 0.0
    private var waitingForChapter = false
    private var lastReportAtMs = 0L

    private val sleepTimer = SleepTimerDriver(session, handler) {
        if (!session.isLifecyclePaused) {
            view.context.toastOnUi(R.string.auto_scroll_stopped_timer)
        }
        onStopRequested()
    }

    private val ticker = FrameTicker { elapsedMs ->
        step(elapsedMs)
    }

    override val isRunning: Boolean get() = session.isRunning
    override val isUserPaused: Boolean get() = session.isUserPaused
    val isPaused: Boolean get() = session.isPaused

    override fun start() {
        session.start(ReadBook.durChapterIndex)
        remainderPx = 0.0
        waitingForChapter = false
        lastReportAtMs = SystemClock.uptimeMillis()
        updateTicker()
        onStateChanged()
    }

    override fun stop() {
        if (!session.isRunning) return
        ticker.stop()
        sleepTimer.cancel()
        waitingForChapter = false
        remainderPx = 0.0
        session.stop()
        view.reportTopItem()
        onStateChanged()
    }

    override fun setUserPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.USER, paused, notifyState = true)
    }

    override fun setTouchPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.TOUCH, paused, notifyState = false)
    }

    override fun setMenuPaused(paused: Boolean) {
        updatePauseReason(AutoReadSession.PauseReason.MENU, paused, notifyState = false)
    }

    override fun setLifecyclePaused(paused: Boolean) {
        if (!paused && isRunning && session.isSleepTimerExpired()) {
            view.context.toastOnUi(R.string.auto_scroll_stopped_timer)
            onStopRequested()
            return
        }
        updatePauseReason(AutoReadSession.PauseReason.LIFECYCLE, paused, notifyState = true)
    }

    private fun updatePauseReason(
        reason: AutoReadSession.PauseReason,
        paused: Boolean,
        notifyState: Boolean,
    ) {
        if (!session.setPaused(reason, paused)) return
        if (paused) view.reportTopItem()
        updateTicker()
        if (notifyState) onStateChanged()
    }

    override fun setSleepTimerMinutes(minutes: Int) {
        sleepTimer.schedule(minutes)
        if (isRunning) onStateChanged()
    }

    override fun remainingSleepMs(): Long = session.remainingSleepMs()

    override fun refreshConfig() {
        if (!isRunning) return
        ticker.resetClock()
        updateTicker()
    }

    override fun onChapterSelected(chapterIndex: Int) {
        if (!isRunning) return
        session.updateStartChapterIndex(chapterIndex)
        remainderPx = 0.0
        waitingForChapter = true
        updateTicker()
    }

    fun onChapterRendered(hasContent: Boolean, chapterIndex: Int) {
        if (!isRunning || !waitingForChapter) return
        if (!hasContent) {
            onStopRequested()
            return
        }
        waitingForChapter = false
        session.updateStartChapterIndex(chapterIndex)
        remainderPx = 0.0
        updateTicker()
    }

    private fun updateTicker() {
        if (isRunning && !isPaused && !waitingForChapter) {
            ticker.start()
        } else {
            ticker.stop()
        }
    }

    private fun step(elapsedMs: Long): Boolean {
        if (!isRunning || isPaused || waitingForChapter) return false
        if (session.isSleepTimerExpired()) {
            view.context.toastOnUi(R.string.auto_scroll_stopped_timer)
            onStopRequested()
            return false
        }
        val viewportHeight = view.autoViewportHeight()
        val speed = AutoScrollMath.speedPxPerSecForScreenDuration(
            viewportHeight,
            ReadBookConfig.autoReadSpeed,
        )
        val result = AutoScrollMath.accumulateStep(remainderPx, elapsedMs, speed)
        remainderPx = result.nextRemainderPx
        if (result.deltaPx <= 0) return true

        if (view.autoScrollStep(result.deltaPx)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastReportAtMs >= REPORT_INTERVAL_MS) {
                lastReportAtMs = now
                view.reportTopItem()
            }
            return true
        }

        // Reached bottom of current chapter.
        view.reportTopItem()
        if (AppConfig.autoReadStopAtChapterEnd) {
            view.context.toastOnUi(R.string.auto_scroll_stopped_chapter_end)
            onStopRequested()
            return false
        }
        if (hasNextChapter()) {
            waitingForChapter = true
            requestNextChapter()
            return isRunning && !isPaused && !waitingForChapter
        }
        view.context.toastOnUi(R.string.no_next_page)
        onStopRequested()
        return false
    }

    companion object {
        private const val REPORT_INTERVAL_MS = 1000L
    }
}
