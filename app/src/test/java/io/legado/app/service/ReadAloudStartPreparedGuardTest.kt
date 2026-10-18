package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 朗读引擎「contentList 为空」的两种含义不得混用的守卫契约。
 *
 * 缺陷现象（真机，冷启动后第一次点朗读必然失败，第二次正常）：
 * 「完全退出 → 重启 → 第一次点朗读」时服务被创建后立刻自停，等于停止朗读。
 *
 * 根因是纯时序竞态：`TTSReadAloudService.onTtsInit()` 在 TTS 初始化成功回调里直接
 * `play()`。TTS 引擎热态（MultiTTS 常驻）时该回调只需几十毫秒，早于 `newReadAloud`
 * 在 IO 上的章节准备落地。此时 `contentList` 为空且 `currentChapterIndex` 仍是 -1，
 * `play()` 把它当成「本章读完了」→ `nextChapter()` →
 * `switchReadAloudChapterKeepingViewByOffset(-1)` → "Detached read aloud chapter is
 * missing" → `stopSelf()`。平板/模拟器 TTS 冷启动更慢，章节准备先落地，恰好躲开窗口。
 *
 * ⚠️ **`contentList` 空 → `nextChapter()` 本身是必需的**，它是章节边界续读的恢复
 * 机制（读章尾、纯图片章都靠它）。本修复只把「还没有章节身份」这一种情况排除掉，
 * 不能把整条推进路径删掉或改成无条件等待。
 */
class ReadAloudStartPreparedGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return stripComments(f.readText().replace("\r\n", "\n"))
    }

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

    private fun ttsPlayBody(): String {
        val source = moduleSource("src/main/java/io/legado/app/service/TTSReadAloudService.kt")
        val start = source.indexOf("override fun play() {")
        assertTrue("TTSReadAloudService 必须有 play()", start >= 0)
        val end = source.indexOf("override fun playStop()", start)
        assertTrue("未能定位 play() 函数体末尾", end > start)
        return source.substring(start, end)
    }

    /**
     * ⚠️ 核心不变量①：`contentList` 为空时仍必须能推进下一章。
     * 这是章节边界续读的唯一恢复机制——拿掉它朗读会卡死在章末/章首。
     */
    @Test
    fun emptyContentListStillAdvancesToNextChapter() {
        val body = ttsPlayBody()
        val emptyCheck = body.indexOf("contentList.isEmpty()")
        assertTrue("play() 必须显式处理空 contentList", emptyCheck >= 0)
        val next = body.indexOf("nextChapter()")
        assertTrue(
            "空 contentList 分支必须保留 nextChapter()（章节边界续读靠它）",
            next > emptyCheck
        )
    }

    /**
     * ⚠️ 核心不变量②：还没有章节身份时（起点未准备落地）绝不能推进。
     * 判据只取 `currentChapterIndex < 0`——章界续读与纯图片章此时必然 >= 0，
     * 所以这条早退对它们零影响。
     */
    @Test
    fun advanceIsGuardedByMissingChapterIdentity() {
        val body = ttsPlayBody()
        val emptyCheck = body.indexOf("contentList.isEmpty()")
        val guard = body.indexOf("currentChapterIndex < 0")
        val next = body.indexOf("nextChapter()")
        assertTrue("空 contentList 分支必须有 currentChapterIndex < 0 守卫", guard >= 0)
        assertTrue("守卫必须在 isEmpty 分支内、nextChapter() 之前", guard in emptyCheck until next)
        val guardBlock = body.substring(guard, next)
        assertTrue(
            "未准备时必须 return 等待确认块再次 play()，不能落到 nextChapter()",
            guardBlock.contains("return")
        )
    }

    /**
     * ⚠️ 守卫**不得**放在 `requestFocus()` / 保洁取销之前，也不得包住整个 play()：
     * `switchReadAloudChapterKeepingView` 等切章路径不设起点令牌，若 play() 入口
     * 无差别早退，跨章推进会被静默吞掉（朗读卡住且无日志）。
     */
    @Test
    fun guardIsScopedToEmptyContentListBranchOnly() {
        val body = ttsPlayBody()
        val focus = body.indexOf("requestFocus()")
        val emptyCheck = body.indexOf("contentList.isEmpty()")
        val guard = body.indexOf("currentChapterIndex < 0")
        assertTrue("守卫必须晚于 requestFocus()", guard > focus)
        assertTrue("守卫必须在 isEmpty 分支内", guard > emptyCheck)
    }
}
