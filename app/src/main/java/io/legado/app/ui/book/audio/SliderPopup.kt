package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.databinding.PopupSeekBarBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.visible

class SliderPopup(
    private val context: Context,
    private val name: Int,
    private val onValueChanged: (() -> Unit)? = null,
) :
    PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    companion object {
        const val TIMER = 1
        const val SPEED = 2
    }

    private val binding = PopupSeekBarBinding.inflate(LayoutInflater.from(context))
    init {
        contentView = binding.root
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
        setProcess()
        bindChapterOptions()
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (name == TIMER) {
                    setProcessTimerText(progress)
                    if (fromUser) {
                        ReadAloud.setTimer(context, progress)
                        onValueChanged?.invoke()
                    }
                    return
                }
                val speed = speedFromProgress(progress)
                setProcessSpeedText(speed)
                if (fromUser) {
                    if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
                        ReadAloud.setSpeed(context, speed)
                    } else {
                        AppConfig.ttsSpeechRate = progress
                        ReadAloud.upTtsSpeechRate(context)
                    }
                    onValueChanged?.invoke()
                }
            }
        })
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        super.showAsDropDown(anchor, xoff, yoff, gravity)
        syncDisplay()
    }

    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        syncDisplay()
    }

    /**
     * 打开时同步显示：分钟滑条取当前剩余分钟；章节定时激活时数值区改显剩余章数
     * （滑条此时归 0，直接显示“0 分钟”会与状态不符）。
     */
    private fun syncDisplay() {
        if (name != TIMER) {
            binding.seekBar.progress = currentSpeedProgress()
            return
        }
        binding.seekBar.progress = BaseReadAloudService.timeMinute.coerceAtLeast(0)
        BaseReadAloudService.chapterTimerRemainingText()?.let {
            binding.tvSeekValue.text = it
        }
    }

    /**
     * 按章节定时快捷档（本章/3章/5章）：仅 TIMER 模式，与分钟滑条共用互斥语义。
     * 选择后关闭弹窗，剩余章数由 BaseReadAloudService.timeChapter 与事件同步展示。
     */
    private fun bindChapterOptions() {
        if (name != TIMER) return
        binding.llChapterOptions.visible()
        val chapters = BaseReadAloudService.chapterTimerOptions
        val views = arrayOf(binding.tvChapterCurrent, binding.tvChapter3, binding.tvChapter5)
        views.forEachIndexed { index, view ->
            val count = chapters[index]
            view.text = BaseReadAloudService.chapterTimerOptionText(count)
            view.setOnClickListener {
                ReadAloud.setChapterTimer(context, count)
                onValueChanged?.invoke()
                dismiss()
            }
        }
    }

    private fun setProcessTimerText(process: Int) {
        binding.tvSeekValue.text = context.getString(R.string.timer_m, process)
    }

    @SuppressLint("SetTextI18n")
    private fun setProcessSpeedText(speed: Float) {
        binding.tvSeekValue.text = "%.1fX".format(speed)
    }

    private fun setProcess() {
        if (name == TIMER) {
            binding.seekBar.max = 180
            setProcessTimerText(0)
            return
        }
        binding.seekBar.max = if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) 25 else 45
        binding.seekBar.progress = currentSpeedProgress()
        setProcessSpeedText(speedFromProgress(binding.seekBar.progress))
    }

    private fun currentSpeedProgress(): Int {
        return if (ReadAloud.engineType == ReadAloudEngineType.SOURCE_AUDIO) {
            (((ReadBook.book?.getPlaySpeed() ?: 1f) - 0.5f) * 10).toInt().coerceIn(0, 25)
        } else {
            AppConfig.ttsSpeechRate.coerceIn(0, 45)
        }
    }

    private fun speedFromProgress(progress: Int): Float {
        return (progress + 5) / 10f
    }
}
