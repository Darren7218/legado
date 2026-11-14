package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAutoReadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.page.AutoScrollMath
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale

/**
 * Interactive control sheet shown during auto-scroll / auto-page reading.
 */
class AutoReadDialog : BaseDialogFragment(R.layout.dialog_auto_read) {

    private val binding by viewBinding(DialogAutoReadBinding::bind)
    private val callBack: CallBack? get() = activity as? CallBack
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTickRunnable = object : Runnable {
        override fun run() {
            if (!isAdded || view == null) return
            upTimerUi()
            val remaining = callBack?.remainingAutoReadSleepMs() ?: 0L
            if (remaining > 0L) {
                timerHandler.postDelayed(this, TIMER_REFRESH_MS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes?.apply {
                dimAmount = 0.0f
                gravity = Gravity.BOTTOM
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initEvents()
    }

    override fun onDestroyView() {
        timerHandler.removeCallbacks(timerTickRunnable)
        super.onDestroyView()
    }

    private fun initView() {
        upSpeedUi(AutoScrollMath.clampAutoReadSeconds(ReadBookConfig.autoReadSpeed))
        upPauseState()
        upTimedPageTurnVisibility()
        binding.llSetting.isVisible = callBack?.supportsPageAnimConfig() != false
        binding.swStopChapterEnd.isChecked = AppConfig.autoReadStopAtChapterEnd
        binding.swTimedPageTurn.isChecked = AppConfig.autoReadTimedPageTurn
        scheduleTimerUiRefresh()
    }

    private fun initEvents() {
        binding.ivSpeedSlower.setOnClickListener { stepSpeed(+1) }
        binding.ivSpeedFaster.setOnClickListener { stepSpeed(-1) }
        binding.seekAutoRead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newSeconds = AutoScrollMath.sliderProgressToSeconds(progress)
                if (newSeconds != ReadBookConfig.autoReadSpeed) {
                    ReadBookConfig.autoReadSpeed = newSeconds
                    binding.tvReadSpeed.text =
                        getString(R.string.auto_scroll_speed_seconds, newSeconds)
                    callBack?.onAutoReadConfigChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                upSpeedUi(AutoScrollMath.clampAutoReadSeconds(ReadBookConfig.autoReadSpeed))
            }
        })

        binding.swStopChapterEnd.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.autoReadStopAtChapterEnd = isChecked
            callBack?.onAutoReadConfigChanged()
        }
        binding.swTimedPageTurn.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.autoReadTimedPageTurn = isChecked
            callBack?.onAutoReadConfigChanged()
        }

        binding.llAutoTimer.setOnClickListener { showTimerSelector() }
        binding.llAutoPagePause.setOnClickListener {
            callBack?.toggleAutoReadPause()
            upPauseState()
        }
        binding.llCatalog.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.openChapterList()
        }
        binding.llMainMenu.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showMenuBar()
        }
        binding.llAutoPageStop.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.autoPageStop()
        }
        binding.llSetting.setOnClickListener {
            (activity as? BaseReadBookActivity)?.showPageAnimConfig {
                callBack?.onAutoPageAnimChanged()
                upTimedPageTurnVisibility()
            }
        }
    }

    private fun stepSpeed(deltaSeconds: Int) {
        val next = AutoScrollMath.clampAutoReadSeconds(ReadBookConfig.autoReadSpeed + deltaSeconds)
        if (next == ReadBookConfig.autoReadSpeed) return
        ReadBookConfig.autoReadSpeed = next
        upSpeedUi(next)
        callBack?.onAutoReadConfigChanged()
    }

    private fun upSpeedUi(seconds: Int) {
        val clamped = AutoScrollMath.clampAutoReadSeconds(seconds)
        binding.tvReadSpeed.text = getString(R.string.auto_scroll_speed_seconds, clamped)
        val progress = AutoScrollMath.secondsToSliderProgress(clamped)
        if (binding.seekAutoRead.progress != progress) {
            binding.seekAutoRead.progress = progress
        }
    }

    private fun upPauseState() {
        val paused = callBack?.isAutoReadUserPaused() == true
        binding.ivAutoPagePause.setImageResource(
            if (paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp
        )
        binding.tvAutoPagePause.setText(if (paused) R.string.resume else R.string.pause)
        binding.llAutoPagePause.contentDescription =
            getString(if (paused) R.string.resume else R.string.pause)
    }

    private fun upTimedPageTurnVisibility() {
        if (!isAdded || view == null) return
        binding.swTimedPageTurn.isVisible = callBack?.supportsTimedPageTurn() == true
    }

    private fun showTimerSelector() {
        val options = TIMER_MINUTES.map { minutes ->
            if (minutes == 0) {
                getString(R.string.auto_scroll_timer_off)
            } else {
                getString(R.string.auto_scroll_timer_minutes, minutes)
            }
        }
        requireContext().selector(getString(R.string.auto_scroll_timer), options) { _, index ->
            callBack?.setAutoReadSleepTimer(TIMER_MINUTES[index])
            scheduleTimerUiRefresh()
        }
    }

    private fun scheduleTimerUiRefresh() {
        if (!isAdded || view == null) return
        timerHandler.removeCallbacks(timerTickRunnable)
        upTimerUi()
        val remaining = callBack?.remainingAutoReadSleepMs() ?: 0L
        if (remaining > 0L) {
            timerHandler.postDelayed(timerTickRunnable, TIMER_REFRESH_MS)
        }
    }

    private fun upTimerUi() {
        if (!isAdded || view == null) return
        val remaining = callBack?.remainingAutoReadSleepMs() ?: 0L
        if (remaining <= 0L) {
            binding.tvAutoTimer.setText(R.string.auto_scroll_timer_off)
            return
        }
        val totalSeconds = (remaining + 999L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        binding.tvAutoTimer.text = String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
        fun onAutoReadConfigChanged()
        fun toggleAutoReadPause()
        fun isAutoReadUserPaused(): Boolean
        fun setAutoReadSleepTimer(minutes: Int)
        fun remainingAutoReadSleepMs(): Long
        fun supportsTimedPageTurn(): Boolean
        fun supportsPageAnimConfig(): Boolean
        fun onAutoPageAnimChanged()
    }

    companion object {
        private val TIMER_MINUTES = intArrayOf(0, 5, 10, 15, 30, 45, 60)
        private const val TIMER_REFRESH_MS = 1000L
    }
}
