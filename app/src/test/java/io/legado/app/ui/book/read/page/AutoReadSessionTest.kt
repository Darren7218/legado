package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReadSessionTest {

    @Test
    fun independentPauseSources_requireAllClearToResume() {
        val session = AutoReadSession { 1_000L }
        assertFalse(session.setTouchPaused(true))

        session.start(chapterIndex = 4)
        assertTrue(session.isRunning)
        assertFalse(session.isPaused)
        assertEquals(4, session.startChapterIndex)

        assertTrue(session.setUserPaused(true))
        assertTrue(session.setTouchPaused(true))
        assertTrue(session.isPaused)

        // Lifting finger while user pause is active keeps session paused.
        assertTrue(session.setTouchPaused(false))
        assertTrue(session.isPaused)
        assertTrue(session.isUserPaused)

        assertTrue(session.setUserPaused(false))
        assertFalse(session.isPaused)

        // Menu + lifecycle pauses behave orthogonally.
        assertTrue(session.setMenuPaused(true))
        assertTrue(session.setLifecyclePaused(true))
        assertTrue(session.setMenuPaused(false))
        assertTrue(session.isPaused)
        assertTrue(session.setLifecyclePaused(false))
        assertFalse(session.isPaused)
    }

    @Test
    fun sleepTimer_tracksDeadlineAndExpiresOnSchedule() {
        var nowMs = 10_000L
        val session = AutoReadSession { nowMs }
        session.start(chapterIndex = 0)

        assertEquals(0L, session.remainingSleepMs())
        assertFalse(session.isSleepTimerExpired())

        session.setSleepTimerMinutes(5)
        assertEquals(300_000L, session.remainingSleepMs())
        assertFalse(session.isSleepTimerExpired())

        nowMs += 200_000L
        assertEquals(100_000L, session.remainingSleepMs())
        assertFalse(session.isSleepTimerExpired())

        nowMs += 100_000L
        assertEquals(0L, session.remainingSleepMs())
        assertTrue(session.isSleepTimerExpired())

        session.setSleepTimerMinutes(0)
        assertFalse(session.isSleepTimerExpired())
        assertEquals(0L, session.remainingSleepMs())

        session.stop()
        assertFalse(session.isRunning)
        assertFalse(session.isPaused)
        assertEquals(0L, session.remainingSleepMs())
    }

    @Test
    fun setPaused_isIdempotentAndStopClearsAllReasons() {
        val session = AutoReadSession { 5_000L }
        session.start(chapterIndex = 2)

        assertTrue(session.setPaused(AutoReadSession.PauseReason.USER, true))
        assertFalse(session.setPaused(AutoReadSession.PauseReason.USER, true))
        assertTrue(session.setPaused(AutoReadSession.PauseReason.MENU, true))
        assertTrue(session.isUserPaused)
        assertTrue(session.isMenuPaused)

        session.updateStartChapterIndex(7)
        assertEquals(7, session.startChapterIndex)

        session.stop()
        assertFalse(session.isPaused)
        assertFalse(session.isUserPaused)
        assertFalse(session.isMenuPaused)
        assertFalse(session.setPaused(AutoReadSession.PauseReason.USER, false))
    }
}
