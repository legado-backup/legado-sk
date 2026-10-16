package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 朗读起点「串行准备 + 主线程确认令牌」的守卫契约（源码级静态断言）。
 *
 * 缺陷现象：双击跳段 / 从本页听时**偶发**多跳段落，且被点段落离当前朗读段落越远跳得越多。
 *
 * 根因：`newReadAloud` 以 `execute(executeContext = IO)` 在 IO 线程池上跑，而
 * `prepareReadAloudChapter` 会写一组**共享播放游标字段**（`contentList` / `nowSpeak` /
 * `readAloudNumber` / `pageIndex` / `chapter` / `paragraphStartPos`）。旧请求的 prepare 与新
 * 请求的 prepare 可在线程池上交错写同一组字段（`preparedReadAloudStartRequest` /
 * `readAloudStartRequest` 只保护「发布」窗口，不串行化「写入」），`play()` 随后消费到的是
 * 混合游标 → 起点落到比目标更后的段落。被点段落离当前越远，两个游标的差值越大，混合后的
 * 偏移越明显。
 *
 * 上游（阅读C）已修：`68c06ed3`「串行准备朗读并在主线程确认令牌发布状态」——用
 * `readAloudPrepareMutex.withLock{}` 串行化整段准备，并把「令牌复检 + 写回 + `play()`」
 * 收进 `withContext(Main)` 构成原子临界区。**该提交不在 HEAD 祖先链上**（10036 重植上游
 * 基线时这条定制未被重放），故此处以源码断言锁住修复形态，防止再次丢失。
 *
 * ⚠️ 无法用 JVM 单测驱动（依赖 Android `Service` 与真实播放），故退化为源码文本断言。
 */
class ReadAloudStartRequestSerializationGuardTest {

    /** ⚠️ 仓库 `.kt` 为 CRLF，`readText()` 不归一化行尾，含换行的标记切不出边界。 */
    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText().replace("\r\n", "\n")
    }

    private fun serviceSource(): String = stripComments(
        moduleSource("src/main/java/io/legado/app/service/BaseReadAloudService.kt")
    )

    /**
     * 去掉注释后再断言。
     *
     * ⚠️ 与 `ReadAloudPanelPositionGuardTest` 同一教训：注释文本能喂饱裸 `contains()`，
     * 断言会变成永不失败的摆设。断言只看**会执行的代码**。
     * 逐字符扫描并保留行数（注释替换为等量空白）。
     */
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

    private fun newReadAloudBody(): String {
        val source = serviceSource()
        val start = source.indexOf("    private fun newReadAloud(")
        assertTrue("BaseReadAloudService 必须有 newReadAloud", start >= 0)
        val end = source.indexOf("    private suspend fun cancelReadAloudStart(", start)
        assertTrue("未能定位 newReadAloud 函数体末尾", end > start)
        return source.substring(start, end)
    }

    /**
     * ⚠️ 核心不变量：准备阶段必须被 `readAloudPrepareMutex` 串行化。
     * 缺它则两个请求的 prepare 在线程池上交错写共享游标字段，`play()` 消费到混合状态。
     */
    @Test
    fun chapterPreparationIsSerializedByMutex() {
        val source = serviceSource()
        assertTrue(
            "必须有 readAloudPrepareMutex 串行化章节准备（上游 68c06ed3）",
            source.contains("private val readAloudPrepareMutex = Mutex()")
        )
        val body = newReadAloudBody()
        assertTrue(
            "newReadAloud 的整段准备必须包在 readAloudPrepareMutex.withLock { } 内",
            body.contains("readAloudPrepareMutex.withLock {")
        )
        // withLock 必须包住 prepareReadAloudChapter，而不是只在旁边调一次
        val lockAt = body.indexOf("readAloudPrepareMutex.withLock {")
        val prepareAt = body.indexOf("prepareReadAloudChapter(")
        assertTrue("withLock 必须先于 prepareReadAloudChapter 出现", lockAt in 0 until prepareAt)
        // 两道 prepare 分支都必须在锁内（切片到 withContext 之前即锁体）
        val confirmAt = body.indexOf("withContext(Main) {")
        assertTrue("withLock 必须包住 withContext 确认块", lockAt in 0 until confirmAt)
    }

    /**
     * ⚠️ 令牌复检、状态写回与 `play()` 必须在**同一主线程临界区**内。
     * 上游注释：与 requestReadAloud 的令牌递增同在主线程，中间没有挂起点。
     * 若 play() 退回 launch(Main)（异步），令牌复检与 play 之间又出现可被抢占的窗口。
     */
    @Test
    fun tokenConfirmationAndPlayShareOneMainThreadCriticalSection() {
        val body = newReadAloudBody()
        assertTrue(
            "令牌确认块必须在 withContext(Main) 内（与令牌递增同线程）",
            body.contains("withContext(Main) {")
        )
        val outer = body.indexOf("withContext(Main) {")
        // ⚠️ 必须先切出 withContext 块再找令牌复检：withLock 内还有一处同名复检，
        // 裸 indexOf 会命中它，断言就锚错了对象。
        val confirmBlock = body.substring(outer)
        val check = confirmBlock.indexOf("if (request != readAloudStartRequest)")
        val writeBack = confirmBlock.indexOf("preparedReadAloudStartRequest = request")
        val play = confirmBlock.indexOf("if (play) play() else pageChanged = true")
        assertTrue("临界区内先复检令牌", check in 0 until writeBack)
        assertTrue("复检通过才写回 preparedReadAloudStartRequest", writeBack > check)
        assertTrue("play() 必须在同一临界区内（不得退回 launch(Main) 异步派发）", play > writeBack)
        assertFalse(
            "确认块内不得再出现 launch(Main)：那会重新引入可被抢占的窗口",
            confirmBlock.contains("launch(Main)")
        )
    }

    /**
     * ⚠️ 启动路径不得再跑在 IO 线程池上。`execute(executeContext = IO)` 是本缺陷的前提：
     * 线程池允许两个 prepare 真并发，主线程串行则不会有交错写。
     */
    @Test
    fun startPathNoLongerRunsPrepareOnIoThreadPool() {
        val body = newReadAloudBody()
        assertFalse(
            "newReadAloud 不得再用 execute(executeContext = IO)：线程池并发正是交错写的来源",
            body.contains("execute(executeContext = IO)")
        )
    }

    /**
     * ⚠️ 取消路径也必须回主线程：它写 `preparedReadAloudStartRequest`，
     * 若不与令牌递增同线程，过期请求仍可能清掉新请求刚写回的状态。
     */
    @Test
    fun cancelAlsoConfirmsOnMainThread() {
        val source = serviceSource()
        assertTrue(
            "cancelReadAloudStart 必须是 suspend 且切到 Main 执行",
            source.contains("private suspend fun cancelReadAloudStart(request: Long) = withContext(Main) {")
        )
        val cancelAt = source.indexOf("private suspend fun cancelReadAloudStart")
        val end = source.indexOf("private fun prepareReadAloudChapter(", cancelAt)
        assertTrue("未能定位 cancelReadAloudStart 函数体", end > cancelAt)
        val body = source.substring(cancelAt, end)
        assertTrue(
            "取消必须复检令牌，避免过期请求清掉新请求的写回状态",
            body.contains("if (request != readAloudStartRequest) return@withContext")
        )
    }
}
