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
}
