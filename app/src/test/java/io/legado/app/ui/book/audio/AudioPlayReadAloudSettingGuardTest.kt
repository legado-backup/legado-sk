package io.legado.app.ui.book.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「听书页按 Rimchars 形态改造」的**守卫契约**（源码级静态断言）。
 *
 * 这一页是作者要求的"专门听书朗读界面"，本次改造对齐 Rimchars 的
 * `ReadAloudPlayerPanel`（底部 `FeaturePill` 四胶囊 + 右上角齿轮）
 * 与 `ReadAloudConfigDialog`（顶部分组页签）：
 *
 * 1. 底部功能胶囊栏：目录 / 定时 / 引擎 / 角色，点击必须真的接上对应入口；
 * 2. 右上角齿轮：打开朗读全量设置（此前听书页内不可达）；
 * 3. 设置页分组页签：基础 / 朗读 / 引擎，且**分组过滤只在对话框承载时生效**
 *    （ConfigActivity 复用同一 Fragment 时没有页签，过滤会把整页清空）。
 *
 * ⚠️ 断言锚定到函数体与具体语句，不用「全文件包含某字符串」——那在把调用挪到
 * 别的分支后仍会通过。⚠️ 竖屏与横屏布局各有同名控件，两者必须同步，故都断言。
 */
class AudioPlayReadAloudSettingGuardTest {

    /**
     * 读取模块内文件源码。
     * ⚠️ 统一把 CRLF 折成 LF：本仓库的 `.kt` 是 CRLF，`readText()` **不会**归一化行尾，
     * 于是按 `"\n\n"` 这类含换行的标记切片会永远找不到结束标记。
     */
    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText().replace("\r\n", "\n")
    }

    private fun activitySource(): String = moduleSource(
        "src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt"
    )

    private fun layoutSource(): String = moduleSource("src/main/res/layout/activity_audio_play.xml")

    private fun landscapeLayoutSource(): String =
        moduleSource("src/main/res/layout-land/activity_audio_play.xml")

    private fun menuSource(): String = moduleSource("src/main/res/menu/audio_play.xml")

    private fun configDialogSource(): String = moduleSource(
        "src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt"
    )

    /** 取 [startMarker] 到 [endMarker]（含）之间的源码，用于锚定单个函数体。 */
    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    /**
     * 底部四胶囊必须存在（竖屏与横屏都要），且每一项都必须真的接上入口。
     * 缺一就是"看着有胶囊、点了没反应"。
     */
    @Test
    fun featurePillsExistAndAreWired() {
        listOf("竖屏" to layoutSource(), "横屏" to landscapeLayoutSource()).forEach { (name, layout) ->
            listOf(
                "ll_pill_chapter", "ll_pill_timer", "ll_pill_engine", "ll_pill_role"
            ).forEach { id ->
                assertTrue("$name 布局必须存在功能胶囊 $id", layout.contains("@+id/$id"))
            }
        }

        val body = slice(
            activitySource(),
            "private fun initControls(",
            "private fun adjustProgressBy("
        )
        assertTrue(
            "目录胶囊必须打开目录（否则只是个装饰）",
            body.contains("llPillChapter?.setOnClickListener")
        )
        assertTrue(
            "定时胶囊必须弹出定时滑条",
            body.contains("llPillTimer?.setOnClickListener")
        )
        assertTrue(
            "引擎胶囊必须打开引擎选择",
            body.contains("llPillEngine?.setOnClickListener { showDialogFragment<SpeakEngineDialog>() }")
        )
        assertTrue(
            "角色胶囊必须打开 AI 角色对话框（作者要求复用现有的那份）",
            body.contains("llPillRole?.setOnClickListener")
                    && body.contains("AiMultiVoiceDialog.show(")
        )
    }

    /**
     * 右上角齿轮：必须存在、必须打开朗读全量设置。
     * 这是"设置都在这一页里"的第二个入口。
     */
    @Test
    fun settingsGearOpensConfigDialog() {
        assertTrue(
            "竖屏布局必须存在右上角齿轮 iv_settings",
            layoutSource().contains("android:id=\"@+id/iv_settings\"")
        )
        assertTrue(
            "横屏布局必须存在右上角齿轮 iv_settings",
            landscapeLayoutSource().contains("android:id=\"@+id/iv_settings\"")
        )
        assertTrue(
            "齿轮必须打开 ReadAloudConfigDialog",
            activitySource().contains("ivSettings?.setOnClickListener { showDialogFragment<ReadAloudConfigDialog>() }")
        )
    }

    /**
     * 菜单「朗读设置」保留（与齿轮同一目标的两个入口，作者要求设置页可达）。
     */
    @Test
    fun readAloudConfigMenuEntryStillOpensSettingsDialog() {
        assertTrue(
            "菜单必须声明 menu_read_aloud_config",
            menuSource().contains("android:id=\"@+id/menu_read_aloud_config\"")
        )
        val body = slice(
            activitySource(),
            "override fun onCompatOptionsItemSelected(",
            "return super.onCompatOptionsItemSelected(item)"
        )
        assertTrue(
            "该菜单项必须打开 ReadAloudConfigDialog",
            body.contains("R.id.menu_read_aloud_config -> showDialogFragment<ReadAloudConfigDialog>()")
        )
    }

    /**
     * 设置页分组页签：三组必须都在，且分组过滤**必须**受 `groupFilterEnabled` 门控。
     *
     * ⚠️ 这一条是本版最容易回归的点：`ReadAloudPreferenceFragment` 同时被 ConfigActivity
     * 复用，那里没有页签。若无条件过滤，ConfigActivity 打开朗读设置会只剩「基础」一组的项。
     */
    @Test
    fun configDialogGroupsAreGatedByDialogHost() {
        val source = configDialogSource()
        listOf(
            "read_aloud_group_basic", "read_aloud_group_reading", "read_aloud_group_engine"
        ).forEach { key ->
            assertTrue("分组页签必须声明 $key", source.contains("R.string.$key"))
        }

        val filterBody = slice(source, "private fun applyGroupFilter()", "fun selectGroup(")
        assertTrue(
            "分组过滤必须按当前组显示（isVisible = key in ...）",
            filterBody.contains("isVisible = key in selectedGroup.preferenceKeys")
        )

        // 门控：只有对话框传入分组参数时才允许过滤
        val createBody = slice(
            source,
            "override fun onCreatePreferences(",
            "private fun applyGroupFilter()"
        )
        assertTrue(
            "分组过滤必须受 groupFilterEnabled 门控（ConfigActivity 复用同一 Fragment，无条件过滤会清空整页）",
            createBody.contains("if (groupFilterEnabled) applyGroupFilter()")
        )
    }

    /**
     * 语速与音量增强必须在听书页面上（作者要求：这是听书页最常见的两个设置，
     * 不要收进设置里绕）。两者都必须真的接上，且书源音频下音量增强要隐藏
     * （它走 TTS 处理链，书源音频自建 ExoPlayer 不经过该链，否则是假控件）。
     */
    @Test
    fun speedAndVolumeGainAreOnTheListeningPage() {
        listOf("竖屏" to layoutSource(), "横屏" to landscapeLayoutSource()).forEach { (name, layout) ->
            listOf("iv_speed_control", "iv_volume_gain").forEach { id ->
                assertTrue(
                    "$name 布局必须在传输行上放 $id（语速/音量增强是听书页常见设置）",
                    layout.contains("@+id/$id")
                )
            }
        }

        val body = slice(
            activitySource(),
            "private fun initControls(",
            "private fun adjustProgressBy("
        )
        assertTrue(
            "语速按钮必须弹出语速滑条（复用既有 SliderPopup(SPEED)，它已分引擎）",
            body.contains("ivSpeedControl?.setOnClickListener")
                    && body.contains("speedControlPopup.showAsDropDown")
        )
        assertTrue(
            "音量增强按钮必须真的切换设置（二档：0 / VolumeGain.MAX_PERCENT）",
            body.contains("AppConfig.ttsVolumeGain =")
                    && body.contains("VolumeGain.MAX_PERCENT")
        )

        val gainBody = slice(
            activitySource(),
            "private fun updateVolumeGainUi()",
            "private fun applyDisplaySettings()"
        )
        assertTrue(
            "书源音频不经过 TTS 增益链，音量增强按钮必须隐藏（否则是点了没反应的假控件）",
            gainBody.contains("SOURCE_AUDIO") && gainBody.contains("gone()")
        )
        assertTrue(
            "音量增强按钮必须反映当前开关状态",
            gainBody.contains("isSelected = AppConfig.ttsVolumeGain > 0")
        )
    }

    /**
     * 听书页必须有真正的「停止」（结束会话），且与「暂停」是两件事。
     *
     * ⚠️ 本页原先只有一个 FAB：它在 播放/暂停/继续 之间切换，**没有停止**。
     * 作者明确要求补上「点一下结束朗读、退出前台服务」的入口，语义对照朗读面板
     * 的 `iv_stop` 与悬浮窗的叉，区别于可继续的三角。
     */
    @Test
    fun listeningPageHasRealStopSeparateFromPause() {
        listOf("竖屏" to layoutSource(), "横屏" to landscapeLayoutSource()).forEach { (name, layout) ->
            assertTrue("$name 必须存在停止按钮 iv_stop", layout.contains("@+id/iv_stop"))
            val stopBlock = slice(layout, "android:id=\"@+id/iv_stop\"", "/>")
            assertTrue(
                "$name 的停止按钮必须用方形停止图标（与三角暂停区分）",
                stopBlock.contains("@drawable/ic_stop_black_24dp")
            )
        }

        val body = slice(
            activitySource(),
            "private fun initControls(",
            "private fun adjustProgressBy("
        )
        assertTrue(
            "停止按钮必须调 ReadAloud.stop（结束会话，而非 pause）",
            body.contains("ivStop.setOnClickListener")
                    && body.contains("ReadAloud.stop(this@AudioPlayActivity)")
        )
        assertTrue(
            "FAB 仍是暂停/继续语义，不得被改成停止",
            body.contains("ReadAloud.pause(this@AudioPlayActivity)")
                    && body.contains("ReadAloud.resume(this@AudioPlayActivity)")
        )

        val stateBody = slice(
            activitySource(),
            "private fun updatePlayState()",
            "private fun updateCoverRotation("
        )
        assertTrue(
            "无会话时停止按钮必须禁用（否则是点了没反应的假控件）",
            stateBody.contains("ivStop.isEnabled = running")
        )
    }

    /**
     * ⚠️ 开关型圆按钮必须用带 `state_selected` 的 drawable。
     *
     * 这是本版真实踩到的缺陷：`iv_volume_gain` 最初沿用 `selector_circle_btn_bg`，
     * 而那个 drawable **只有 `state_pressed` 一项**，代码里设的 `isSelected`
     * 不产生任何视觉变化 —— 用户表现为「点击无任何反馈」。
     *
     * 断言分两半，缺一不可：
     * 1. 开关按钮的背景必须是有 selected 态的 drawable；
     * 2. 被引用的那个 drawable 里必须真的写了 `state_selected`（防止指向一个空有名字的文件）。
     */
    @Test
    fun toggleButtonsUseDrawableWithSelectedState() {
        val toggleDrawable = moduleSource("src/main/res/drawable/selector_circle_toggle_bg.xml")
        assertTrue(
            "开关态 drawable 必须真的声明 state_selected，否则 isSelected 无视觉效果",
            toggleDrawable.contains("android:state_selected=\"true\"")
        )

        listOf("竖屏" to layoutSource(), "横屏" to landscapeLayoutSource()).forEach { (name, layout) ->
            val block = slice(layout, "android:id=\"@+id/iv_volume_gain\"", "/>")
            assertTrue(
                "$name 的音量增强按钮必须用带选中态的 selector_circle_toggle_bg",
                block.contains("@drawable/selector_circle_toggle_bg")
            )
        }

        // 反证：只有 pressed 态的旧 drawable 不得再被开关按钮使用
        assertTrue(
            "selector_circle_btn_bg 无法表达开关态（只有 state_pressed）",
            !moduleSource("src/main/res/drawable/selector_circle_btn_bg.xml")
                .contains("state_selected")
        )
    }

    /**
     * 作者明确要求「控件位置对应回原版」：既有骨架控件不得被移除或改名。
     * 正文同步区是 SK 听书页相对其他分支的优势，不得被新改动挤掉。
     */
    @Test
    fun existingControlsKeepTheirPlace() {
        listOf("竖屏" to layoutSource(), "横屏" to landscapeLayoutSource()).forEach { (name, layout) ->
            listOf(
                "title_bar", "iv_cover", "play_content",
                "tv_sub_title", "ll_player_progress", "ll_play_menu",
                "fab_play_stop", "iv_skip_previous", "iv_skip_next",
                "iv_timer", "iv_chapter"
            ).forEach { id ->
                assertTrue("$name 既有控件 $id 不得被移除（作者要求位置对应原版）", layout.contains("@+id/$id"))
            }
            assertTrue(
                "$name 正文同步滚动区必须保留",
                layout.contains("listening_text_scroll") && layout.contains("listening_text_content")
            )
        }
    }

    /**
     * 语速与缓存原先占底部圆按钮，按作者选择收进设置页——入口必须真的接上，
     * 否则功能会静默消失。
     */
    @Test
    fun speedAndCacheEntriesMovedIntoConfigDialog() {
        val source = configDialogSource()
        assertTrue(
            "设置页必须声明语速入口 ttsSpeechRate",
            source.contains("PreferKey.ttsSpeechRate -> showSpeechRateDialog()")
        )
        assertTrue(
            "设置页必须声明缓存入口，且复用听书页原先的语义（本地书→TTS 缓存，其余→正文下载）",
            source.contains("PreferKey.readAloudCacheEntry -> openCacheEntry()")
                    && source.contains("TtsCacheChapterDialog.newInstance")
                    && source.contains("showBookDownloadRangeDialog(book)")
        )
    }

    /**
     * 定时状态显示在「定时」胶囊文字上（Rimchars 同款：胶囊文字即状态）。
     * 移除顶部 tv_timer 后，章节/分钟定时必须仍有可见出口。
     */
    @Test
    fun timerStateShowsOnPill() {
        val body = slice(
            activitySource(),
            "private fun updateSessionIndicators()",
            "override fun onCompatCreateOptionsMenu("
        )
        assertTrue(
            "定时状态必须写在「定时」胶囊文字上",
            body.contains("tvPillTimer?.text")
        )
        assertTrue(
            "章节定时优先于分钟定时（两者互斥）",
            body.contains("chapterTimerRemainingText()")
        )
    }

    /**
     * 句内轮询的**位置锚点必须取真实播放位置**，不能锚在本段段首。
     *
     * 播放端倍速兜底的引擎（合成 URL 无 `speakSpeed` 占位符，如转发器 multiTTS）
     * 每拖一格语速都会重启一次句内轮询（`upSpeechRate` → `upPlayPos`）。若锚点在
     * 段首，就会把朗读位置拉回本段开头 → 进度条/正文高亮回跳；已合成完（duration>0）
     * 且读到中后段时回拉幅度最大，故只在此时复现，流式合成中 curPos≈0 时几乎看不出。
     *
     * ⚠️ 这里断言的是**序关系**：锚点发布必须在 `start`（真实播放位置）算出之后，
     * 且表达式里必须用到 `anchor`/`start`。只断言「文件里有 upTtsProgress」是无效断言——
     * 把发布挪回段首后子串依然命中。
     */
    @Test
    fun intraParagraphAnchorUsesRealPlaybackPosition() {
        val source = moduleSource(
            "src/main/java/io/legado/app/service/HttpReadAloudService.kt"
        )
        val body = slice(source, "private fun upPlayPos()", "\n    }\n")

        val startAt = body.indexOf("val start =")
        assertTrue("未找到真实播放位置 start 的计算（锚点已漂移）", startAt >= 0)

        val anchorAt = body.indexOf("val anchor =")
        assertTrue(
            "必须基于真实播放位置计算锚点 anchor",
            anchorAt >= 0
        )
        assertTrue(
            "锚点必须在 start 之后计算（否则拿不到真实播放位置）",
            anchorAt > startAt
        )

        val publishAt = body.indexOf("upTtsProgress(")
        assertTrue(
            "位置发布必须在 start 之后：锚在段首会让拖语速把进度拉回本段开头",
            publishAt > startAt
        )
        assertTrue(
            "发布的必须是锚点位置，不能是本段段首（readAloudNumber + charBase + 1）",
            body.contains("upTtsProgress(readAloudNumber + charBase + anchor + 1)")
        )
    }

    /**
     * 系统 TTS 引擎链路（[TTSReadAloudService]，如转发器 multiTTS 当系统引擎用）有
     * **同构的同一处锚点问题**，必须一并锁住。
     *
     * 该链路 `upSpeechRate` 走 `playStop()+play()` 重播当前句 → `upWavPlayPos()` 重启，
     * 若锚在句首同样会把位置拉回本句开头。只修 HTTP 链路会让「转发器当系统引擎」
     * 这条最常见的用法继续复现（实测 10 次里偶发 1 次即来自这里）。
     */
    @Test
    fun wavIntraparagraphAnchorUsesRealPlaybackPosition() {
        val source = moduleSource(
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        )
        val body = slice(source, "private fun upWavPlayPos()", "\n    }\n")

        assertTrue(
            "必须先取播放器时长再算锚点（否则拿不到真实播放位置）",
            body.contains("val duration = wavPlayer.duration")
        )
        assertTrue(
            "必须基于真实播放位置计算锚点 anchor",
            body.contains("val anchor =")
        )
        assertTrue(
            "句首锚点必须消失：调语速重播当前句时它会把进度拉回本句开头",
            !body.contains("upTtsProgress(readAloudNumber + 1)")
        )
        assertTrue(
            "发布的必须是锚点位置",
            body.contains("upTtsProgress(readAloudNumber + anchor + 1)")
        )
        // 锚点发布必须在 pageSplit 提前返回之前，否则分段开启时本句位置永远不更新
        assertTrue(
            "锚点发布必须在 pageSplit 提前返回之前",
            body.indexOf("upTtsProgress(readAloudNumber + anchor + 1)") <
                body.indexOf("if (pageSplit) return@launch")
        )
    }

    /**
     * 调语速在**段落收尾期**不得重启当前句。
     *
     * 当前句只剩三五个字时，推进逻辑正准备切下一段；此时 `playStop()+play()`
     * 会清掉播放器现场并重播本句，与切段相撞 → 位置在段交界处跳变（实测可复现）。
     * 正确做法：收尾期只改引擎语速让它自然播完，由 STATE_ENDED → advanceWavParagraph()
     * 切段，新语速从下一段生效。
     */
    @Test
    fun speechRateChangeSkipsRestartNearUtteranceEnd() {
        val source = moduleSource(
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        )
        val body = slice(source, "override fun upSpeechRate(reset: Boolean)", "\n    }\n")

        assertTrue(
            "调语速前必须先把新语速设给引擎（收尾期不重启也能生效）",
            body.contains("textToSpeech?.setSpeechRate(speechRate)")
        )
        assertTrue(
            "收尾期必须提前返回，不得再 playStop()+play() 重播当前句",
            body.contains("isCurrentWavUtteranceAlmostFinished()")
        )
        // 守卫必须排在重启之前：先重启再判断等于没守。
        // ⚠️ 用 lastIndexOf：上方注释里也含 playStop() 字面量，indexOf 会先命中注释而假红。
        assertTrue(
            "收尾守卫必须排在 playStop() 调用之前（先重启再判断等于没守）",
            body.indexOf("isCurrentWavUtteranceAlmostFinished()") < body.lastIndexOf("playStop()")
        )
        assertTrue(
            "必须存在收尾判定实现",
            source.contains("private fun isCurrentWavUtteranceAlmostFinished()")
        )
    }

    /**
     * 朗读跨章挂起恢复不得依赖「永远不会来的回调」。
     *
     * 朗读跨章时若预加载的下一章尚未排版完成，恢复只挂 `pendingReadAloudChapterSync`
     * 标志；该标志只由 `curPageChanged` 消费，而预加载分支（contentLoadFinish 的
     * offset==±1）排版完成后**不回调** curPageChanged → 朗读停驻在章节连接处
     * （服务存活、非暂停非停止，手动介入才恢复）。修法：预加载排版完成后，
     * 若该章已被交换为当前显示章，补发一次 curPageChanged。
     */
    @Test
    fun pendingReadAloudSyncResumesWhenPreloadedChapterFinishesLayout() {
        val source = moduleSource(
            "src/main/java/io/legado/app/model/ReadBook.kt"
        )

        val helperAt = source.indexOf("private fun ensureReadAloudPendingSyncResumed")
        assertTrue("必须存在预加载排版完成的恢复入口", helperAt >= 0)
        val helperBody = slice(
            source,
            "private fun ensureReadAloudPendingSyncResumed",
            "curPageChanged()"
        )
        assertTrue(
            "恢复前必须先确认挂起标志仍在（无标志不得乱发回调）",
            helperBody.contains("if (!pendingReadAloudChapterSync) return")
        )
        assertTrue(
            "恢复前必须确认该章已是当前显示章（否则预加载未消费时就抢播）",
            helperBody.contains("chapter.chapter.index != durChapterIndex")
        )

        // 上一章 / 下一章两个预加载分支都必须挂上恢复钩子
        val callCount = Regex("ensureReadAloudPendingSyncResumed\\(textChapter\\)")
            .findAll(source).count()
        assertTrue(
            "上一章与下一章预加载分支都要挂恢复钩子（实际 $callCount 处）",
            callCount >= 2
        )

        // 钩子必须排在排版通道消费完之后（channel collect/for 之后），否则排版未完成就触发
        val nextBranch = slice(
            source,
            "1 -> nextChapterLoadingLock.withLock",
            "ensureReadAloudPendingSyncResumed(textChapter)"
        )
        assertTrue(
            "下一章分支的钩子必须等排版通道消费完",
            nextBranch.contains("textChapter.layoutChannel")
        )
    }
}
