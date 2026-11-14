package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoScrollWiringContractTest {

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/main/$relativePath"),
            File("src/main/$relativePath"),
        )
        val file = candidates.firstOrNull(File::exists)
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun readView_fixesScrollSyncAndUsesTouchDispatchPause() {
        val readView = mainSource("java/io/legado/app/ui/book/read/page/ReadView.kt")
        val contentView = mainSource("java/io/legado/app/ui/book/read/page/ContentTextView.kt")

        assertFalse(
            "upContent must keep scroll mode progress/position in sync even while auto-scrolling",
            readView.contains("if (isScroll && !isAutoPage)"),
        )
        assertTrue(
            "ReadView should pause autoPager cleanly across touch down/up/cancel",
            readView.contains("autoPager.setTouchPaused(true)") &&
                readView.contains("autoPager.setTouchPaused(false)"),
        )
        assertFalse(
            "AutoPager should be driven by FrameTicker rather than computeScroll",
            readView.contains("autoPager.computeOffset()"),
        )
        assertFalse(
            "ContentTextView.onDraw must not advance or draw AutoPager",
            contentView.contains("autoPager?.onDraw"),
        )
        assertTrue(
            "ContentTextView should expose isAtBookEnd with loading and message guards",
            contentView.contains("fun isAtBookEnd()") &&
                contentView.contains("pageFactory.isRefreshingResources") &&
                contentView.contains("textPage.isMsgPage"),
        )
    }

    @Test
    fun autoPager_usesVsyncTickerAndPureOnDraw() {
        val autoPager = mainSource("java/io/legado/app/ui/book/read/page/AutoPager.kt")
        val onDrawBody = autoPager
            .substringAfter("fun onDraw(canvas: Canvas)")
            .substringBefore("private fun currentMode()")

        assertTrue(autoPager.contains("FrameTicker"))
        assertTrue(autoPager.contains("SleepTimerDriver"))
        assertTrue(autoPager.contains("isAtBookEnd()"))
        assertTrue(autoPager.contains("isBlockedAtChapterEnd()"))
        assertTrue(autoPager.contains("isRefreshingResources"))
        assertTrue(autoPager.contains("autoReadStopAtChapterEnd"))
        assertFalse("onDraw must stay side-effect free and not flip pages", onDrawBody.contains("fillPage"))
        assertFalse("onDraw must not schedule ticks via invalidate()", onDrawBody.contains("invalidate()"))

        val frameTicker = mainSource("java/io/legado/app/ui/book/read/page/FrameTicker.kt")
        assertTrue(frameTicker.contains("isDispatching"))
        assertTrue(frameTicker.contains("isScheduled"))

        val readView = mainSource("java/io/legado/app/ui/book/read/page/ReadView.kt")
        assertTrue(readView.contains("autoPager.invalidatePreview()"))
    }

    @Test
    fun readBookActivity_preservesAutoScrollAcrossLifecycleAndSupportsParagraphMode() {
        val activity = mainSource("java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        val onPauseBlock = activity
            .substringAfter("override fun onPause()")
            .substringBefore("override fun onCompatCreateOptionsMenu")
        val autoPageBlock = activity
            .substringAfter("override fun autoPage()")
            .substringBefore("override fun onAutoReadConfigChanged()")

        assertTrue(onPauseBlock.contains("setLifecyclePaused(true)"))
        assertFalse("onPause should pause rather than kill auto-read", onPauseBlock.contains("autoPageStop()"))
        assertTrue(activity.contains("paragraphAutoScroller.start()"))
        assertTrue(autoPageBlock.contains("paragraphAutoScroller.stop()"))
        assertTrue(activity.contains("onChapterSelected("))
    }

    @Test
    fun mangaAutoScroll_usesFrameTickerAndKeepsScreenOn() {
        val timer = mainSource("java/io/legado/app/ui/book/manga/recyclerview/ScrollTimer.kt")
        val mangaActivity = mainSource("java/io/legado/app/ui/book/manga/ReadMangaActivity.kt")

        assertTrue(timer.contains("FrameTicker"))
        assertTrue(timer.contains("AutoScrollMath.mangaScrollPxPerSec"))
        assertTrue(mangaActivity.contains("AppConfig.mangaAutoScrollSpeed"))
        assertTrue(mangaActivity.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(mangaActivity.contains("mScrollTimer.onContentUpdated()"))
    }
}
