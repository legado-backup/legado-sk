package io.legado.app.ui.book.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「听书页设置全在页内」的**守卫契约**（源码级静态断言）。
 *
 * 这一页是作者要求的"专门听书朗读界面"，核心诉求是**设置不必回阅读页**。
 * 两个入口缺一不可，且都必须真的接上：
 * 1. 页内引擎徽标（点击直开引擎选择）——此前引擎选择只藏在右上角菜单；
 * 2. 菜单「朗读设置」——此前朗读全量设置只能在阅读页的朗读面板里开。
 *
 * ⚠️ 断言锚定到函数体与具体语句，不用「全文件包含某字符串」——那在把调用挪到
 * 别的分支后仍会通过。同时断言**不重排**既有控件（作者要求控件位置对应原版）。
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

    private fun menuSource(): String = moduleSource("src/main/res/menu/audio_play.xml")

    /** 取 [startMarker] 到 [endMarker]（含）之间的源码，用于锚定单个函数体。 */
    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    /**
     * 页内引擎徽标：必须存在视图、必须绑定点击、且点击真的打开引擎选择对话框。
     * 三者缺一就是"看起来有徽标、点了没反应"。
     */
    @Test
    fun engineBadgeOpensEngineDialog() {
        assertTrue(
            "布局必须存在页内引擎徽标 tv_engine",
            layoutSource().contains("android:id=\"@+id/tv_engine\"")
        )

        val body = slice(
            activitySource(),
            "private fun updateEngineBadge()",
            "private fun currentEngineLabel()"
        )
        assertTrue(
            "徽标点击必须打开 SpeakEngineDialog（否则只是个不响应的文字）",
            body.contains("showDialogFragment<SpeakEngineDialog>()")
        )
        assertTrue(
            "书源音频没有 TTS 引擎概念，必须隐藏徽标",
            body.contains("SOURCE_AUDIO")
        )
    }

    /**
     * 菜单「朗读设置」：必须声明菜单项，且选中后真的打开 ReadAloudConfigDialog。
     */
    @Test
    fun readAloudConfigEntryOpensSettingsDialog() {
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
            "该菜单项必须打开 ReadAloudConfigDialog（朗读全量设置的唯一入口）",
            body.contains("R.id.menu_read_aloud_config -> showDialogFragment<ReadAloudConfigDialog>()")
        )
    }

    /**
     * 作者明确要求「控件位置对应回原版」：既有控件不得被移除或改名。
     * 这里锚定几个位置基准控件——它们构成这一页的布局骨架。
     */
    @Test
    fun existingControlsKeepTheirPlace() {
        val layout = layoutSource()
        listOf(
            "title_bar", "tv_timer", "iv_cover", "play_content",
            "tv_sub_title", "ll_player_progress", "ll_play_menu",
            "fab_play_stop", "iv_skip_previous", "iv_skip_next",
            "iv_timer", "iv_speed_control", "iv_chapter"
        ).forEach { id ->
            assertTrue("既有控件 $id 不得被移除（作者要求位置对应原版）", layout.contains("@+id/$id"))
        }
        // 正文同步区与点句跳转是 SK 听书页相对其他分支的优势，不得被新改动挤掉
        assertTrue(
            "正文同步滚动区必须保留",
            layout.contains("listening_text_scroll") && layout.contains("listening_text_content")
        )
    }
}
