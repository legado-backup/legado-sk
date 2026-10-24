package io.legado.app.help.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书页背景位图「共享期间不得回收」的守卫契约（源码级静态断言）。
 *
 * 实机崩溃（平板 TB-9707F，10103，10-23 / 10-24 各一次，栈完全一致）：
 * ```
 * java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
 *   at BitmapDrawable.draw → LayerDrawable.draw → View.drawBackground
 *   at ReadView.dispatchDraw(ReadView.kt:314)
 * ```
 * 触发时序：阅读页 onPause 后回到前台并重建（`BookTocLoadingActivity` / `BookInfoActivity`
 * 在其间走一遍），新实例 `ReadView.init` / `onSizeChanged` 调 `ReadBookConfig.upBg()`；
 * 旧实现会把上一张 `bg` 的 Bitmap `post { recycle() }`，而旧窗口仍把**同一个**共享
 * `Drawable` 挂在 `vwRoot` 背景的 `LayerDrawable` 里，于是首帧绘制即 use-after-free。
 *
 * ⚠️ 无法用 JVM 单测覆盖（依赖 View 绘制与窗口），故退化为源码文本断言。
 */
class ReadBookBackgroundBitmapGuardTest {

    private fun source(): String {
        val path = "src/main/java/io/legado/app/help/config/ReadBookConfig.kt"
        val file = File(path)
        assertTrue(
            "找不到 $path（Gradle 单测 CWD 应为模块目录 app/）：${file.absolutePath}",
            file.exists()
        )
        // 仓库 .kt 为 CRLF，readText() 不归一化行尾，含换行的标记切不出边界。
        return stripComments(file.readText().replace("\r\n", "\n"))
    }

    /** 注释替换为等量空白：断言只看会执行的代码，否则注释里的关键词会把断言喂饱。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n && !source.startsWith("\"\"\"", i)) {
                        out.append(source[i]); i++
                    }
                    if (i < n) { out.append("\"\"\""); i += 3 }
                }
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
                source.startsWith("/*", i) -> {
                    i += 2
                    while (i < n && !source.startsWith("*/", i)) {
                        if (source[i] == '\n') out.append('\n')
                        i++
                    }
                    if (i < n) i += 2
                }
                source.startsWith("//", i) -> {
                    while (i < n && source[i] != '\n') i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun upBgNeverRecyclesTheSharedBackgroundBitmap() {
        val source = source()

        val start = source.indexOf("fun upBg(width: Int, height: Int) {")
        assertTrue("ReadBookConfig 必须有 upBg(width, height)", start >= 0)
        val end = source.indexOf("\n    fun ", start + 1)
        assertTrue("未能定位 upBg 函数体", end > start)
        val body = source.substring(start, end)

        assertTrue("upBg 必须把新背景赋给共享的 bg", body.contains("bg = drawable"))
        assertFalse(
            "禁止回收上一张背景位图：bg 跨 Activity 实例共享，旧窗口仍可能在绘制，" +
                "重建首帧即 'Canvas: trying to use a recycled bitmap'（10103 实机崩溃）",
            body.contains("recycle()")
        )
    }
}
