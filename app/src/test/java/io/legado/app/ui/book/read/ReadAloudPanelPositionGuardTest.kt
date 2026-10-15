package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 朗读底部小面板「定位就绪才显示」的守卫契约（源码级静态断言）。
 *
 * 布局里两个面板（`read_aloud_playback_panel` / `read_aloud_page_panel`）都是
 * `layout_marginBottom="0dp"` + `layout_gravity="bottom"`：**不校正就必然贴屏幕底部**。
 * 校正值只能异步算（依赖 readView 高度与页脚位置）。
 *
 * 上游（阅读C）的原生写法是「先 `visible()`，再在 `doOnLayout` 里算 margin」——
 * 这有两个必现缺陷：① 从 `visible()` 到回调执行之间，面板已以贴底位置完成过一次布局；
 * ② 回调开头的三重 mode 校验任一失败就 `return`，此后再无人补——面板**永久**贴在屏底。
 * 作者观察到的正是「有时贴着底部，呼出菜单再退回又正常」：菜单切换会重跑一次
 * `showReadAloudPanel`，第二次机会才校正成功。
 *
 * 本仓库 10024 曾把定位与显示解耦修掉（`positionAndRevealReadAloudPanel` /
 * `tryReadAloudPanelBottomMargin` / `footerMeasurable`），但 **10036 重植上游基线时
 * 这条定制未被重放**，缺陷回归并持续到 10094。此测试锁住修复形态，防止再次丢失。
 *
 * ⚠️ 无法用 JVM 单测覆盖（依赖 View 布局），故退化为源码文本断言。
 */
class ReadAloudPanelPositionGuardTest {

    /** ⚠️ 仓库 `.kt` 为 CRLF，`readText()` 不归一化行尾，含换行的标记切不出边界。 */
    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText().replace("\r\n", "\n")
    }

    private fun activitySource(): String =
        stripComments(moduleSource("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"))

    private fun readViewSource(): String =
        stripComments(moduleSource("src/main/java/io/legado/app/ui/book/read/page/ReadView.kt"))

    private fun pageViewSource(): String =
        stripComments(moduleSource("src/main/java/io/legado/app/ui/book/read/page/PageView.kt"))

    /**
     * 去掉注释后再断言。
     *
     * ⚠️ 必要性由**注入证伪实测**得出：把 `panel.gone()` 注释成 `// panel.gone()` 后，
     * 裸 `contains("panel.gone()")` 仍然通过——断言被注释文本喂饱，成了永不失败的摆设。
     * 断言必须只看**会执行的代码**。
     *
     * 逐字符扫描并保留行数（注释替换为等量空白），故函数体切片仍按同一套标记定位。
     * 需同时跳过普通字符串与三引号原始串，否则 `"http://..."` 里的 `//` 会被误当注释。
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                // 三引号原始字符串：整段原样保留
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n && !source.startsWith("\"\"\"", i)) {
                        out.append(source[i]); i++
                    }
                    if (i < n) { out.append("\"\"\""); i += 3 }
                }
                // 普通字符串：跳过转义
                c == '"' -> {
                    out.append(c); i++
                    while (i < n && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < n) {
                            out.append(source[i]).append(source[i + 1]); i += 2
                        } else {
                            out.append(source[i]); i++
                        }
                    }
                    if (i < n) { out.append('"'); i++ }
                }
                // 块注释：替换为等量空白，换行保留
                source.startsWith("/*", i) -> {
                    i += 2
                    while (i < n && !source.startsWith("*/", i)) {
                        if (source[i] == '\n') out.append('\n')
                        i++
                    }
                    if (i < n) i += 2
                }
                // 行注释：到行尾
                source.startsWith("//", i) -> {
                    while (i < n && source[i] != '\n') i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * 页脚可测量判定必须存在且贯穿两层：`PageView.footerMeasurable`（真实判据）
     * → `ReadView.footerMeasurable`（转发）。少了它，`footerBounds` 的
     * `check(llFooter.height > 0)` 会在布局未就绪时抛异常或给出 0 区间。
     */
    @Test
    fun footerMeasurableIsExposedThroughBothLayers() {
        val pageView = pageViewSource()
        assertTrue(
            "PageView 必须暴露 footerMeasurable（判据是 llFooter 有实际高度），" +
                "不能只靠 footerBounds 的 check 抛异常",
            pageView.contains("val footerMeasurable: Boolean") &&
                pageView.contains("get() = binding.llFooter.height > 0")
        )

        val readView = readViewSource()
        assertTrue(
            "ReadView 必须把 footerMeasurable 转发出去，供面板定位判据使用",
            readView.contains("val footerMeasurable: Boolean get() = curPage.footerMeasurable")
        )
    }

    /**
     * 定位就绪才显示：两个面板都必须走 `positionAndRevealReadAloudPanel`，
     * 且该入口在未就绪时返回 null 的 margin 上**保持隐藏并重试**，绝不能先 `visible()`。
     */
    @Test
    fun panelsArePositionedBeforeRevealed() {
        val source = activitySource()

        val helperStart = source.indexOf("    private fun positionAndRevealReadAloudPanel(")
        assertTrue("ReadBookActivity 必须有 positionAndRevealReadAloudPanel 入口", helperStart >= 0)
        val helperEnd = source.indexOf("    private fun showReadAloudPlaybackPanel() {", helperStart)
        assertTrue("未能定位 positionAndRevealReadAloudPanel 函数体", helperEnd > helperStart)
        val helper = source.substring(helperStart, helperEnd)

        assertTrue(
            "未就绪必须返回 null 而不是抛异常（否则旋转/异常 insets 时崩溃朗读服务）",
            source.contains("private fun tryReadAloudPanelBottomMargin(panelHeight: Int): Int? {")
        )
        assertTrue(
            "margin 未就绪时必须注册下一次全局布局再重试，否则面板永久停在错误位置",
            helper.contains("addOnGlobalLayoutListener") &&
                helper.contains("positionAndRevealReadAloudPanel(panel, panelMode, onReveal)")
        )
        assertTrue(
            "算不出正确位置时必须保持隐藏（panel.gone()），不能先以贴底位置显示",
            helper.contains("panel.gone()")
        )
        assertTrue(
            "必须用 layoutParams.height 而非 panel.height：GONE 时 panel.height 实测为 0，算不出位置",
            helper.contains("(panel.layoutParams as? FrameLayout.LayoutParams)?.height")
        )

        // 两个面板都必须改走该入口，且函数体内不得再残留「先 visible 再 doOnLayout 校正」的旧写法。
        for ((startMarker, endMarker, name) in listOf(
            Triple(
                "    private fun showReadAloudPlaybackPanel() {",
                "    private fun hideReadAloudPlaybackPanelView(",
                "播放/暂停面板"
            ),
            Triple(
                "    private fun showReadAloudPagePanel() {",
                "    private fun hideReadAloudPagePanel(",
                "页内动作面板"
            ),
        )) {
            val start = source.indexOf(startMarker)
            assertTrue("未找到 $name 的 show 方法：$startMarker", start >= 0)
            val end = source.indexOf(endMarker, start)
            assertTrue("未能定位 $name 的 show 方法体", end > start)
            val body = source.substring(start, end)

            assertTrue(
                "$name 必须走 positionAndRevealReadAloudPanel 定位后再显示",
                body.contains("positionAndRevealReadAloudPanel(")
            )
            assertFalse(
                "$name 不得再先 visible()：那是贴屏底闪现的直接原因（上游原生写法）",
                body.contains(".visible()")
            )
            assertFalse(
                "$name 不得再依赖 doOnLayout 异步校正：三重 mode 校验失败即永久贴底",
                body.contains("doOnLayout")
            )
        }
    }

    /** 旧的同步校正函数必须彻底删除，避免两套定位逻辑并存。 */
    @Test
    fun legacySynchronousMarginHelperIsGone() {
        val source = activitySource()
        assertFalse(
            "readAloudPanelBottomMargin 必须删除，定位收敛到 positionAndRevealReadAloudPanel 一条路径",
            source.contains("private fun readAloudPanelBottomMargin(")
        )
    }
}
