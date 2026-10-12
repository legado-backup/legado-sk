package io.legado.app.ui.book.read.config

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.core.view.doOnLayout
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.VolumeGain
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.TtsCacheParams
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadAloudUiState
import io.legado.app.plugin.TtsVoiceDirectories
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudDialogFloatingPresentation
import io.legado.app.service.ReadAloudEngineType
import io.legado.app.service.ReadAloudFloatingHost
import io.legado.app.service.ReadAloudProgress
import io.legado.app.ui.book.cache.TtsCacheChapterDialog
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding


class ReadAloudDialog : BaseReaderSheetDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)
    private var loadingAnimator: ObjectAnimator? = null
    private var showMainMenuOnDismiss = false
    private var ownsDialogVisibility = false
    private var dialogPresentationReady = false
    private var displayedReadProgress: ReadAloudProgress? = null
    private var trackingReadProgress = false

    /** TTS 缓存入口当前不可用的原因（字符串资源 id）；null = 可用。 */
    private var ttsCacheUnavailableReason: Int? = null
    private val menuLayoutChangeListener =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            publishMenuTopOnScreen(view)
        }
    private val isSourceAudioSelected
        get() = ReadAloud.selectedEngineType == ReadAloudEngineType.SOURCE_AUDIO

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        binding.rootView.removeOnLayoutChangeListener(menuLayoutChangeListener)
        binding.rootView.addOnLayoutChangeListener(menuLayoutChangeListener)
        binding.rootView.doOnLayout(::publishMenuTopOnScreen)
        publishDialogVisibilityAfterFirstDraw()
    }

    private fun resolveFloatingHost(): ReadAloudFloatingHost {
        val window = dialog?.window ?: error("ReadAloudDialog window is unavailable")
        val token = window.decorView.windowToken
            ?: error("ReadAloudDialog window token is unavailable")
        return ReadAloudFloatingHost(window.windowManager, token)
    }

    private fun publishMenuTopOnScreen(view: View) {
        check(view.height > 0) { "Read-aloud menu has no measurable height" }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        (activity as? ReadBookActivity)?.postReadAloudFloatingAvoidanceFromScreenBounds(
            EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_DIALOG,
            location[1],
            location[1] + view.height,
        )
    }

    private fun publishDialogVisibilityAfterFirstDraw() {
        val root = binding.rootView
        root.doAfterFirstDraw {
            if (!ownsDialogVisibility || dialogPresentationReady) return@doAfterFirstDraw
            updateDialogVisibility(true)
        }
    }

    private fun updateDialogVisibility(visible: Boolean) {
        if (visible) {
            if (!ownsDialogVisibility || dialogPresentationReady || dialog?.isShowing != true) {
                return
            }
        } else if (!dialogPresentationReady) {
            return
        }
        val floatingPresentation = ReadAloudDialogFloatingPresentation(
            if (visible) resolveFloatingHost() else null
        )
        dialogPresentationReady = visible
        ReadAloudUiState.setReadAloudDialogVisible(visible)
        postEvent(EventBus.READ_ALOUD_DIALOG_VISIBILITY, visible)
        postEvent(EventBus.READ_ALOUD_DIALOG_FLOATING_PRESENTATION, floatingPresentation)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        binding.rootView.removeOnLayoutChangeListener(menuLayoutChangeListener)
        if (ownsDialogVisibility) {
            ownsDialogVisibility = false
            updateDialogVisibility(false)
        }
        stopLoadingAnimation()
        (activity as ReadBookActivity).bottomDialog--
        (activity as? ReadBookActivity)?.clearReadAloudFloatingAvoidance(
            EventBus.FLOATING_AVOID_SOURCE_READ_ALOUD_DIALOG
        )
        if (showMainMenuOnDismiss) {
            showMainMenuOnDismiss = false
            callBack?.showMenuBar()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        ownsDialogVisibility = true
        binding.root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        val palette = ReaderSheetStyle.resolve(requireContext())
        binding.run {
            panelTransport.background = null
            panelTimer.background = null
            panelTts.background = null
            panelActions.background = null
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivOpenAudioPlay.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(palette.secondaryTextColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            cbVolumeGain.setTextColor(textColor)
            ivVolumeGainHelp.setColorFilter(palette.secondaryTextColor)
            tvTimerChapterCurrent.setTextColor(textColor)
            tvTimerChapter3.setTextColor(textColor)
            tvTimerChapter5.setTextColor(textColor)
            ivCatalog.setColorFilter(textColor)
            tvCatalog.setTextColor(textColor)
            tvCatalogValue.setTextColor(textColor)
            ivTtsCache.setColorFilter(textColor)
            tvTtsCache.setTextColor(textColor)
            ivMainMenu.setColorFilter(textColor)
            tvMainMenu.setTextColor(textColor)
            ivToBackstage.setColorFilter(textColor)
            tvToBackstage.setTextColor(textColor)
            ivSetting.setColorFilter(textColor)
            tvSetting.setTextColor(textColor)
            cbTtsFollowSys.setTextColor(textColor)
        }
        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        upPlayState()
        upSpeakEngineSummary()
        upSeekTimer()
        upTimerText()
        upReadProgress()
    }

    /**
     * 定时档位：章节（本章/3章/5章）+ 分钟（0=关闭）。
     * 章节档排在**前面**：分钟档有 8 项，排后面才能一眼看到新增的按章节定时。
     * 两种定时互斥，由服务侧 setTimer/setChapterTimer 统一清理另一模式。
     */
    private fun showTimerSelector() {
        val chapterOptions = BaseReadAloudService.chapterTimerOptions
        val minuteOptions = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
        val labels = chapterOptions.map { BaseReadAloudService.chapterTimerOptionText(it) } +
            minuteOptions.map { "$it 分钟" }
        context?.selector("设定时间", labels) { _, index ->
            val ctx = requireContext()
            if (index < chapterOptions.size) {
                ReadAloud.setChapterTimer(ctx, chapterOptions[index])
            } else {
                ReadAloud.setTimer(ctx, minuteOptions[index - chapterOptions.size])
            }
        }
    }

    /**
     * 章节定时快捷档（本章/3章/5章）：直接摆在定时行下方，与 `tvTimer` 的选择器同源同文案。
     * 引擎未运行时 `ReadAloud.setChapterTimer` 是空操作，故未朗读时点击无效果。
     */
    private fun bindChapterTimerButtons() = binding.run {
        val views = arrayOf(tvTimerChapterCurrent, tvTimerChapter3, tvTimerChapter5)
        views.forEachIndexed { index, view ->
            val count = BaseReadAloudService.chapterTimerOptions[index]
            view.text = BaseReadAloudService.chapterTimerOptionText(count)
            view.setOnClickListener {
                ReadAloud.setChapterTimer(requireContext(), count)
            }
        }
    }

    private fun initEvent() = binding.run {
        ivCatalog.gone()
        llMainMenu.visible(AppConfig.readAloudHideFloatingWindow && BaseReadAloudService.isRun)
        upAiRoleEntry()
        llAiRole.setOnClickListener {
            if (llAiRole.isEnabled) {
                AiMultiVoiceDialog.show(childFragmentManager)
            }
        }
        upTtsCacheEntry()
        llTtsCache.setOnClickListener {
            // 不可用不再静默：点击弹底部通知说明原因
            ttsCacheUnavailableReason?.let { reason ->
                toastOnUi(reason)
                return@setOnClickListener
            }
            ReadBook.book?.let { book ->
                TtsCacheChapterDialog.newInstance(book)
                    .show(childFragmentManager, "ttsCacheChapterDialog")
            }
        }
        llCatalog.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        llMainMenu.setOnClickListener {
            showMainMenuOnDismiss = true
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        tvPre.setOnClickListener {
            if (BaseReadAloudService.isRun) {
                ReadAloud.prevChapter(requireContext())
            } else {
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            }
        }
        tvNext.setOnClickListener {
            if (BaseReadAloudService.isRun) {
                ReadAloud.nextChapter(requireContext())
            } else {
                ReadBook.moveToNextChapter(true)
            }
        }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivOpenAudioPlay.setOnClickListener {
            updateDialogVisibility(false)
            dismissAllowingStateLoss()
            ReadAloud.openAudioPlayActivity(requireContext())
        }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llToBackstage.setOnClickListener {
            (activity as? ReadBookActivity)?.toReadAloudBackstage()
            dismissAllowingStateLoss()
        }
        ivTtsSpeechReduce.setOnClickListener {
            seekTtsSpeechRate.progress -= 1
            saveSpeechRate(seekTtsSpeechRate.progress)
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            seekTtsSpeechRate.progress += 1
            saveSpeechRate(seekTtsSpeechRate.progress)
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.progress
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            showTimerSelector()
        }
        bindChapterTimerButtons()
        seekTtsSpeechRate.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                super.onProgressChanged(seekBar, progress, fromUser)
                upTtsSpeechRateText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                saveSpeechRate(seekBar.progress)
                upTtsSpeechRate()
            }
        })
        ivVolumeGainHelp.setOnClickListener {
            context?.alert(
                R.string.read_aloud_volume_gain,
                R.string.read_aloud_volume_gain_hint
            )
        }
        seekTimer.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // 章节定时激活时滑条是被程序归零的（timeMinute=0），此时不得用分钟
                // 文案覆盖章节状态；用户真正拖动切换回分钟模式由 READ_ALOUD_DS 事件纠正。
                if (BaseReadAloudService.chapterTimerRemainingText() != null) return
                upTimerMinutesText(progress)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadAloud.setTimer(requireContext(), seekTimer.progress)
            }
        })

        seekReadProgress.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (displayedReadProgress?.kind) {
                    ReadAloudProgress.Kind.PARAGRAPH -> {
                        binding.tvDurTime.text = getString(
                            R.string.read_aloud_paragraph_progress,
                            progress + 1
                        )
                    }
                    ReadAloudProgress.Kind.TIME -> {
                        binding.tvDurTime.text = formatTime(progress)
                    }
                    null -> Unit
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingReadProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingReadProgress = false
                val progress = displayedReadProgress ?: return
                if (seekBar.progress != progress.position) {
                    // 用户显式传送：跳到目标位置后再自动“回原进度”一次，
                    // 显示对齐到新朗读位置（段落/时间两种形态同一条命令）。
                    ReadAloud.seekToProgress(
                        requireContext(),
                        progress.chapterIndex,
                        seekBar.progress,
                        syncView = true
                    )
                }
            }
        })
    }

    /**
     * AI分角色入口：注册了发音人目录（自有构建）或存在已启用的 V2 脚本引擎时显示
     * （开源构建无内置引擎与发音人，V2 脚本引擎同样支持分角色，入口不隐藏）。
     * 按多角色门控结果置灰：当前引擎不支持分角色朗读时不可点。
     */
    private fun upAiRoleEntry() = binding.llAiRole.run {
        visible(TtsVoiceDirectories.active != null || TtsEngineStore.hasEnabledScriptEngines())
        val unsupportedReason = BookTtsCastingCoordinator.multiRoleUnsupportedReason()
        isEnabled = unsupportedReason == null
        alpha = if (unsupportedReason == null) 1f else 0.45f
    }

    /**
     * TTS 缓存入口常显（置灰代替隐藏，避免看起来像没有这个功能）。
     * 门控与沉浸页下载按钮同源（[TtsCacheParams.unavailableReasonRes]）；
     * 置灰只是视觉提示，按钮保持可点：点击弹底部通知说明不可用原因，不静默。
     */
    private fun upTtsCacheEntry() = binding.llTtsCache.run {
        visible()
        ttsCacheUnavailableReason = ReadBook.book?.let(TtsCacheParams::unavailableReasonRes)
        alpha = if (ttsCacheUnavailableReason == null) 1f else 0.45f
    }

    override fun upSpeakEngineSummary() {
        binding.tvCatalogValue.text = speakEngineSummary()
        bindSpeechRateControls()
        bindVolumeGainControls()
    }

    private fun speakEngineSummary(): String {
        val ttsEngine = ReadAloud.ttsEngine ?: return getString(R.string.system_tts)
        if (ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID) {
            return getString(R.string.source_audio_engine)
        }
        if (StringUtils.isNumeric(ttsEngine)) {
            return appDb.httpTTSDao.getName(ttsEngine.toLong())
                ?: getString(R.string.http_tts_missing, ttsEngine)
        }
        return ReadAloud.selectedEngineLabel() ?: getString(R.string.system_tts)
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(currentSpeechRateProgress())
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun bindSpeechRateControls() = binding.run {
        cbTtsFollowSys.setOnCheckedChangeListener(null)
        cbTtsFollowSys.visible(!isSourceAudioSelected)
        cbTtsFollowSys.isChecked = AppConfig.ttsFlowSys
        configureSpeechRateSlider()
        upTtsSpeechRateEnabled(isSourceAudioSelected || !cbTtsFollowSys.isChecked)
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            if (isSourceAudioSelected) {
                return@setOnCheckedChangeListener
            }
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
    }

    private fun upPlayState() {
        if (BaseReadAloudService.loading) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_refresh_black_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.loading)
            binding.ivPlayPause.isEnabled = false
            startLoadingAnimation()
        } else if (!BaseReadAloudService.pause) {
            stopLoadingAnimation()
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
            binding.ivPlayPause.isEnabled = true
        } else {
            stopLoadingAnimation()
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
            binding.ivPlayPause.isEnabled = true
        }
        binding.ivPlayPause.setColorFilter(ReaderSheetStyle.resolve(requireContext()).textColor)
    }

    private fun startLoadingAnimation() {
        if (loadingAnimator?.isStarted == true) return
        loadingAnimator?.cancel()
        loadingAnimator = ObjectAnimator
            .ofFloat(binding.ivPlayPause, View.ROTATION, 0f, 360f)
            .apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopLoadingAnimation() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        binding.ivPlayPause.rotation = 0f
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            when {
                // 章节定时激活：分钟滑条无意义，归零避免显示保存的分钟默认值。
                BaseReadAloudService.chapterTimerRemainingText() != null -> {
                    binding.seekTimer.progress = 0
                }

                BaseReadAloudService.timeMinute > 0 -> {
                    binding.seekTimer.progress = BaseReadAloudService.timeMinute
                }

                else -> {
                    binding.seekTimer.progress = AppConfig.ttsTimer
                }
            }
        }
    }

    /**
     * 定时状态文案：章节模式优先（显示剩余章数），否则显示剩余分钟。
     * 章节模式下滑条归 0（服务侧 timeMinute=0），避免滑条位置与状态不符。
     */
    private fun upTimerText() {
        val chapterText = BaseReadAloudService.chapterTimerRemainingText()
        if (chapterText != null) {
            binding.tvTimer.text = chapterText
            binding.seekTimer.progress = 0
        } else {
            upTimerMinutesText(BaseReadAloudService.timeMinute)
        }
    }

    private fun upTimerMinutesText(timeMinute: Int) {
        binding.tvTimer.text = requireContext().getString(
            R.string.timer_m,
            timeMinute.coerceAtLeast(0)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = if (isSourceAudioSelected) {
            "%.1f".format(value / 10f + 0.5f)
        } else {
            ((value + 5) / 10f).toString()
        }
    }

    private fun upTtsSpeechRate() {
        if (isSourceAudioSelected) {
            ReadAloud.setSpeed(
                requireContext(),
                binding.seekTtsSpeechRate.progress / 10f + 0.5f,
            )
        } else {
            ReadAloud.upTtsSpeechRate(requireContext())
        }
    }

    /**
     * 音量增强 = 开关（开 = 固定 [VolumeGain.MAX_PERCENT]，即 2 倍；关 = 不增强）。
     *
     * 增益是播放端 PCM 乘系数，由朗读服务在每次播放新音频时读取设置，故这里只需落盘，
     * 无需通知服务。仅两档：更高倍数是纯数字放大，只会削波失真。
     */
    private fun bindVolumeGainControls() = binding.run {
        cbVolumeGain.setOnCheckedChangeListener(null)
        cbVolumeGain.isChecked = AppConfig.ttsVolumeGain > 0
        cbVolumeGain.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsVolumeGain = if (isChecked) VolumeGain.MAX_PERCENT else 0
        }
    }

    private fun configureSpeechRateSlider() = binding.seekTtsSpeechRate.run {
        if (isSourceAudioSelected) {
            max = 25
        } else {
            max = 45
        }
        progress = currentSpeechRateProgress()
        upTtsSpeechRateText(progress)
    }

    private fun currentSpeechRateProgress(): Int {
        return if (isSourceAudioSelected) {
            (((ReadBook.book?.getPlaySpeed() ?: 1f) - 0.5f) * 10).toInt().coerceIn(0, 25)
        } else {
            AppConfig.ttsSpeechRate
        }
    }

    private fun saveSpeechRate(progress: Int) {
        if (!isSourceAudioSelected) {
            AppConfig.ttsSpeechRate = progress
        }
    }

    private fun upReadProgress() {
        val progress = ReadAloud.progressForSelectedEngine()
        if (progress != null) {
            updateReadProgress(progress)
        } else {
            showPendingProgressForSelectedEngine()
        }
    }

    private fun showPendingProgressForSelectedEngine() = binding.run {
        displayedReadProgress = null
        panelProgress.visible()
        seekReadProgress.isEnabled = false
        seekReadProgress.max = 1
        seekReadProgress.progress = 0
        if (isSourceAudioSelected) {
            tvDurTime.setText(R.string.read_aloud_time_pending)
            tvAllTime.setText(R.string.read_aloud_time_pending)
        } else {
            tvDurTime.setText(R.string.read_aloud_paragraph_pending)
            tvAllTime.setText(R.string.read_aloud_paragraph_pending)
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            binding.seekTimer.progress = it
            upTimerText()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_DS_CHAPTER) { upTimerText() }
        observeEvent<Boolean>(EventBus.CLOSE_READ_ALOUD_DIALOG) {
            dismissAllowingStateLoss()
        }
        observeEvent<ReadAloudProgress>(EventBus.READ_ALOUD_PROGRESS) { progress ->
            if (ReadAloud.isProgressForSelectedEngine(progress)) {
                updateReadProgress(progress)
            }
        }
        observeEvent<ReadAloudEngineType>(EventBus.READ_ALOUD_ENGINE_CHANGED) {
            upSpeakEngineSummary()
            upReadProgress()
            upAiRoleEntry()
            upTtsCacheEntry()
        }
    }

    private fun updateReadProgress(progress: ReadAloudProgress) = binding.run {
        displayedReadProgress = progress
        panelProgress.visible()
        when (progress.kind) {
            ReadAloudProgress.Kind.PARAGRAPH -> {
                tvDurTime.text = getString(
                    R.string.read_aloud_paragraph_progress,
                    progress.position + 1
                )
                tvAllTime.text = getString(R.string.read_aloud_paragraph_progress, progress.total)
                seekReadProgress.max = progress.total - 1
                seekReadProgress.isEnabled = progress.total > 1
            }
            ReadAloudProgress.Kind.TIME -> {
                tvDurTime.text = formatTime(progress.position)
                tvAllTime.text = formatTime(progress.total)
                seekReadProgress.max = progress.total
                seekReadProgress.isEnabled = progress.total > 0
            }
        }
        if (!trackingReadProgress) {
            seekReadProgress.progress = progress.position
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun onClickReadAloud()
    }
}
