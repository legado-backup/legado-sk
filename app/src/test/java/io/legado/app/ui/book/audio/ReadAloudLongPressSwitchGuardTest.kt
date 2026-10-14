package io.legado.app.ui.book.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「长按朗读直接进听书页」开关的**守卫契约**（源码级静态断言）。
 *
 * 作者要求：在朗读设置页加一个开关；开启时长按阅读菜单里的「朗读」按钮直接进入
 * 专门的听书页（`AudioPlayActivity`）；关闭（默认）时维持旧行为——弹出半截的
 * 朗读控制面板（`ReadAloudDialog`）。
 *
 * ⚠️ 只断言「设置页存在这个 key」是不够的：key 存在但没人读，开关点了没有任何效果。
 * 因此必须三处同时断言——设置项与默认值、AppConfig 读取默认值、长按的实际分流。
 */
class ReadAloudLongPressSwitchGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        // 统一折成 LF：本仓库 .kt 是 CRLF，readText() 不归一化行尾，含换行的标记会找不到。
        return f.readText().replace("\r\n", "\n")
    }

    private fun prefXmlSource(): String = moduleSource("src/main/res/xml/pref_config_aloud.xml")

    private fun appConfigSource(): String =
        moduleSource("src/main/java/io/legado/app/help/config/AppConfig.kt")

    private fun readMenuSource(): String =
        moduleSource("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")

    private fun readBookActivitySource(): String =
        moduleSource("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

    /** 取 [startMarker] 到 [endMarker]（含）之间的源码，用于锚定单个函数体。 */
    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    /**
     * 取包含 [needle] 的那个 `<io.legado.app.lib.prefs.SwitchPreference …/>` 元素全文。
     * ⚠️ 不能从 key 往后截：`android:defaultValue` 在 key **之前**，从 key 起截会把
     * 默认值漏掉，断言就变成「看了但没有真的看」。
     */
    private fun switchBlockContaining(source: String, needle: String): String {
        val at = source.indexOf(needle)
        assertTrue("未找到 $needle", at >= 0)
        val start = source.lastIndexOf("<io.legado.app.lib.prefs.SwitchPreference", at)
        assertTrue("$needle 不在 SwitchPreference 元素内", start >= 0)
        val end = source.indexOf("/>", at)
        assertTrue("$needle 所在元素未闭合", end >= 0)
        return source.substring(start, end + 2)
    }

    @Test
    fun switchDefaultsOffAndMatchesAppConfigDefault() {
        val prefBlock = switchBlockContaining(
            prefXmlSource(),
            "android:key=\"readAloudLongPressToAudioPlay\""
        )
        assertTrue(
            "长按开关必须默认关（保持旧行为）",
            prefBlock.contains("android:defaultValue=\"false\"")
        )

        val configBody = slice(appConfigSource(), "val readAloudLongPressToAudioPlay", "\n\n")
        assertTrue(
            "AppConfig 读取默认值必须与 xml 的 defaultValue 一致（都 false）",
            configBody.contains("PreferKey.readAloudLongPressToAudioPlay, false")
        )
    }

    /**
     * 长按必须真的按开关分流。
     *
     * ⚠️ 不能仍然无条件弹面板：那样开关开了也没用（这正是本契约要防的回归）。
     * 同时两个分支都要在：关 → 旧面板；开 → 听书页。
     */
    @Test
    fun longPressBranchesOnTheSwitch() {
        val longPressBody = slice(readMenuSource(), "llReadAloud.onLongClick {", "}\n        }")
        assertTrue(
            "长按必须走 onLongClickReadAloud（否则开关不起作用）",
            longPressBody.contains("callBack.onLongClickReadAloud()")
        )

        val impl = slice(
            readBookActivitySource(),
            "override fun onLongClickReadAloud()",
            "fun toReadAloudBackstage()"
        )
        assertTrue(
            "开关关闭时必须维持旧行为：弹出朗读控制面板",
            impl.contains("if (!AppConfig.readAloudLongPressToAudioPlay)")
                    && impl.contains("showReadAloudDialog()")
        )
        assertTrue(
            "开关开启时必须进入听书页",
            impl.contains("ReadAloud.openAudioPlayActivity(")
        )
        assertTrue(
            "未在朗读时进听书页前必须先起会话，否则页面打开后无可控会话",
            impl.contains("ReadAloud.upReadAloudClass()")
        )
    }

    /**
     * 开关必须归入朗读设置页的某个分组页签，否则会被 `applyGroupFilter()` 整项隐藏
     * ——表现为「设置里根本没有这一项」。
     */
    @Test
    fun switchIsInAGroupTab() {
        val dialogSource =
            moduleSource("src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt")
        val groupBody = slice(
            dialogSource,
            "enum class ReadAloudConfigGroup(",
            "companion object"
        )
        assertTrue(
            "新开关必须登记进某一组的 preferenceKeys，否则分组过滤会把它藏掉",
            groupBody.contains("PreferKey.readAloudLongPressToAudioPlay")
        )
    }
}
