package io.legado.app.ui.book.read.page

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * Drives per-frame callbacks synchronized with display vsync.
 * Guards against re-entrant start/stop calls inside [onFrame] so only one frame callback
 * is ever queued at a time.
 */
class FrameTicker(
    private val onFrame: (elapsedMs: Long) -> Boolean,
) : Choreographer.FrameCallback {

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private var lastFrameNanos = 0L
    private var isScheduled = false
    private var isDispatching = false

    var isActive = false
        private set

    fun start() {
        if (isActive) return
        isActive = true
        lastFrameNanos = 0L
        scheduleNextFrame()
    }

    fun stop() {
        if (!isActive && !isScheduled) return
        isActive = false
        lastFrameNanos = 0L
        if (isScheduled) {
            isScheduled = false
            choreographer.removeFrameCallback(this)
        }
    }

    fun resetClock() {
        lastFrameNanos = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        isScheduled = false
        if (!isActive) return
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            scheduleNextFrame()
            return
        }
        val elapsedMs = ((frameTimeNanos - lastFrameNanos) / 1_000_000L).coerceAtLeast(0L)
        lastFrameNanos = frameTimeNanos
        isDispatching = true
        val keepRunning = try {
            onFrame(elapsedMs)
        } finally {
            isDispatching = false
        }
        if (!keepRunning) {
            stop()
        } else if (isActive) {
            scheduleNextFrame()
        }
    }

    private fun scheduleNextFrame() {
        if (isActive && !isScheduled && !isDispatching) {
            isScheduled = true
            choreographer.postFrameCallback(this)
        }
    }
}

/**
 * Schedules sleep-timer expiration callbacks backed by an [AutoReadSession] deadline.
 */
class SleepTimerDriver(
    private val session: AutoReadSession,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onExpired: () -> Unit,
) {
    private val timeoutRunnable = Runnable {
        if (session.isRunning && session.isSleepTimerExpired()) {
            onExpired()
        }
    }

    fun schedule(minutes: Int) {
        if (!session.isRunning) return
        session.setSleepTimerMinutes(minutes)
        handler.removeCallbacks(timeoutRunnable)
        val remaining = session.remainingSleepMs()
        if (remaining > 0L) {
            handler.postDelayed(timeoutRunnable, remaining)
        }
    }

    fun cancel() {
        handler.removeCallbacks(timeoutRunnable)
    }
}
