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

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
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
