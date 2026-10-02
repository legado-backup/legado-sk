package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「阅读页云端进度」两个菜单项必须**常驻**的守卫契约（源码级静态断言）。
 *
 * 原实现把可见性放在 `upMenu` 的异步分支里（`ReadBook.inBookshelf && AppWebDav.isOk`）：
 * 阅读页右上角三个点弹出的是一个 `SurfacePopupMenu`，它在 `show()` 的同一帧就按
 * `isVisible` 对条目做了快照 —— 协程里那次赋值永远赶不上渲染；而
 * `onPrepareOptionsMenu` 拿到的工具栏菜单里根本没有这两个 id。
 * 于是这两个选项长期缺失，只在偶发时序下才出现。
 *
 * 这里只锁结构与接线，运行期点击效果仍需模拟器/真机验证。
 */
class CloudProgressMenuGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun activitySource(): String =
        moduleSource("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")

    private fun menuItemBlock(id: String): String {
        val xml = moduleSource("src/main/res/menu/book_read.xml")
        val idIndex = xml.indexOf("android:id=\"@+id/$id\"")
        assertTrue("book_read.xml 里找不到 $id", idIndex >= 0)
        val itemStart = xml.lastIndexOf("<item", idIndex)
        val itemEnd = xml.indexOf("/>", idIndex)
        assertTrue("$id 的 item 未正确闭合", itemStart >= 0 && itemEnd > idIndex)
        return xml.substring(itemStart, itemEnd)
    }

    /** 常驻：XML 里不许再声明 `android:visible="false"`，也不许由 upMenu 异步改写。 */
    @Test
    fun cloudProgressItemsAreAlwaysVisible() {
        for (id in listOf("menu_get_progress", "menu_cover_progress")) {
            val block = menuItemBlock(id)
            assertTrue("$id 的 item 块解析异常：$block", block.contains("@string/"))
            assertFalse(
                "$id 必须常驻，不能再带 android:visible 声明",
                block.contains("android:visible")
            )
        }

        val source = activitySource()
        val upMenuBody = source.substringAfter("private fun upMenu(")
            .substringBefore("private fun cloudProgressReady()")
        assertTrue("未能定位 upMenu 方法体", upMenuBody.isNotBlank())
        for (id in listOf("menu_get_progress", "menu_cover_progress")) {
            assertFalse(
                "upMenu 不得再改 $id 的可见性（异步赋值赶不上弹出菜单快照）",
                upMenuBody.contains(id)
            )
        }
    }

    /** 常驻后点击必须仍被前置条件拦截，且明确告知原因，不能静默无反应。 */
    @Test
    fun clickHandlersGuardAndExplain() {
        val source = activitySource()
        val selected = source.substringAfter("override fun onCompatOptionsItemSelected(")
        for (id in listOf("R.id.menu_get_progress ->", "R.id.menu_cover_progress ->")) {
            val start = selected.indexOf(id)
            assertTrue("onCompatOptionsItemSelected 里找不到 $id 分支", start >= 0)
            val next = selected.indexOf("R.id.", start + id.length)
            val branch = selected.substring(start, if (next > start) next else selected.length)
            assertTrue(
                "$id 分支必须先调用 cloudProgressReady()，不能静默无反应",
                branch.contains("cloudProgressReady()")
            )
        }

        val guard = source.substringAfter("private fun cloudProgressReady()")
            .substringBefore("return true")
        assertTrue("未配置 WebDAV 时必须提示", guard.contains("R.string.webdav_not_configured"))
        assertTrue(
            "同步开关关闭时必须提示",
            guard.contains("R.string.sync_book_progress_disabled")
        )
    }
}
