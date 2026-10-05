package io.legado.app.ui.book.changesource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「点到当前书源行必须有反馈」的守卫契约（源码级静态断言）。
 *
 * 原实现（`544c1d1a` 基线即如此，上游自带）：
 * ```kotlin
 * holder.itemView.setOnClickListener {
 *     getItem(holder.layoutPosition)?.let {
 *         if (it.bookUrl != callBack.oldBookUrl) callBack.changeTo(it)   // 无 else
 *     }
 * }
 * ```
 * 打勾的条件（`convert` 里 `callBack.oldBookUrl == item.bookUrl`）与它**是同一个布尔式**，
 * 所以「带勾的那一行」必然就是「点了没反应的那一行」，且不弹提示、不报错。
 * 用户无法区分「设计上不给换」和「程序坏了」。
 *
 * ⚠️ 断言必须落在 `else` 分支内部，且必须先用 [executableOnly] 去掉注释：
 * 本缺陷的注释天然会写「点不动 / 要提示」，若断言对整个文件做 `contains(...)`，
 * 则「删掉 else 只留注释」或「interface 里留着方法声明」都会假绿 ——
 * 这正是本仓库记录过的失效写法（见 `AutoBackupOnShelfChangeGuardTest` 与其注释）。
 *
 * 这里只锁接线，运行期点击效果仍需真机验证。
 */
class ChangeSourceCurrentRowGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    /** 去掉注释行，避免「注释冒充代码」通过断言。 */
    private fun executableOnly(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    private fun adapterSource(): String = executableOnly(
        moduleSource("src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceAdapter.kt")
    )

    private fun dialogSource(): String = executableOnly(
        moduleSource("src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceDialog.kt")
    )

    /**
     * 用 `holder.itemView.setOnClickListener` 唯一锚定（`ivGood`/`ivBad` 也是 `setOnClickListener`，
     * 不能只认 `setOnClickListener`）；块尾由紧随其后的 `holder.itemView.onLongClick` 收边。
     */
    private fun itemViewClickBlock(): String {
        val block = adapterSource()
            .substringAfter("holder.itemView.setOnClickListener")
            .substringBefore("holder.itemView.onLongClick")
        assertTrue("未能定位 holder.itemView 的点击监听块（锚点已漂移）", block.isNotBlank())
        return block
    }

    /** ① 正向：当前书源那一行必须走 else 分支并调用反馈回调，不能是空分支。 */
    @Test
    fun currentSourceRowClickGivesFeedback() {
        val block = itemViewClickBlock()
        val elseBranch = block.substringAfter("else")
        assertTrue(
            "点击块必须带 else 分支，否则「带勾的当前书源行」点了全程静默",
            block.contains("else") && elseBranch.isNotBlank()
        )
        assertTrue(
            "else 分支必须调用 onCurrentSourceClick(it)，不得空实现或只留注释",
            elseBranch.contains("onCurrentSourceClick(")
        )
    }

    /**
     * ② 反向：反馈必须**结构上位于 else 分支内部**，不能只是"在判断之后出现"。
     *
     * ⚠️ 首版写 `guardAt < feedbackAt` 是无效断言：把调用移到整个 if/else **之后**
     * （即无条件提示）仍然满足该序关系，注入实测仍 GREEN。必须取 else 块本身。
     */
    @Test
    fun feedbackIsConditionalNotUnconditional() {
        val block = itemViewClickBlock()
        val guardAt = block.indexOf("!= callBack.oldBookUrl")
        val changeAt = block.indexOf("changeTo(")
        assertTrue("换源主路径 changeTo 不得被删", changeAt >= 0)
        assertTrue("必须存在对当前源的判断", guardAt >= 0)

        // 取 else 之后、块结束之前的那一段（块尾已由 onLongClick 收边）。
        val elseBranch = block.substringAfter("else")
        assertTrue(
            "必须存在 else 分支，反馈不能无条件执行",
            block.contains("else") && elseBranch.isNotBlank()
        )
        assertTrue(
            "反馈必须写在 else 分支内部，不能提到 if/else 之外（否则每次点击都提示）",
            elseBranch.contains("onCurrentSourceClick(")
        )
        // 反馈不得出现在 else 之前（即不得无条件执行）。
        val feedbackAt = block.indexOf("onCurrentSourceClick(")
        assertTrue("反馈必须晚于对当前源的判断", guardAt < feedbackAt)
    }

    /** ③ 接线完整性：接口方法与 Dialog 实现必须同时存在，且实现要发用户可见反馈。 */
    @Test
    fun dialogImplementsCurrentSourceFeedback() {
        assertTrue(
            "ChangeBookSourceAdapter.CallBack 必须声明 onCurrentSourceClick",
            adapterSource().contains("fun onCurrentSourceClick(")
        )
        val source = dialogSource()
        // ⚠️ 锚点本身含 "override fun"，所以定位后必须从**下一个** "override fun" 开始收边，
        // 否则 substringBefore("override fun") 在偏移 0 就命中，切出空串，
        // 断言变成「永远看不见方法体」的空断言（首版即踩此坑）。
        val implAt = source.indexOf("override fun onCurrentSourceClick(")
        assertTrue("ChangeBookSourceDialog 必须实现 onCurrentSourceClick", implAt >= 0)
        val bodyStart = implAt + "override fun onCurrentSourceClick(".length
        val nextAt = source.indexOf("override fun", bodyStart)
        val body = source.substring(bodyStart, if (nextAt > bodyStart) nextAt else source.length)
        assertTrue("未能定位 onCurrentSourceClick 的实现体（锚点已漂移）", body.isNotBlank())
        assertTrue(
            "实现不得为空，必须发用户可见反馈",
            body.contains("toastOnUi(") || body.contains("longToastOnUi(")
        )
    }

    /** ④ 文案必须存在：不得引用不存在的字符串资源。 */
    @Test
    fun feedbackStringIsDefined() {
        for (path in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh/strings.xml"
        )) {
            assertTrue(
                "$path 必须定义 change_source_current_in_use",
                moduleSource(path).contains("name=\"change_source_current_in_use\"")
            )
        }
    }

    /**
     * ⑤ 单章换源不得被顺手改成「提示」：
     * 它点当前源本来就是**打开目录预览**（可用功能），加守卫等于砍功能。
     *
     * ⚠️ 首版用全文件 `contains("callBack.openToc(it)")` 是无效断言：
     * 注入「把该调用包进 `if (bookUrl != oldBookUrl) { … }`」后子串仍存在，实测仍 GREEN。
     * 必须取那个点击块本身，并断言块内**没有**对当前源的判断。
     */
    @Test
    fun chapterSourceKeepsDirectTocPreview() {
        val source = executableOnly(
            moduleSource(
                "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceAdapter.kt"
            )
        )
        val block = source
            .substringAfter("holder.itemView.setOnClickListener")
            .substringBefore("holder.itemView.onLongClick")
        assertTrue("未能定位单章换源的行点击块（锚点已漂移）", block.isNotBlank())
        assertTrue(
            "单章换源点行必须直接 openToc（当前源可查看目录）",
            block.contains("callBack.openToc(it)")
        )
        assertTrue(
            "单章换源不得引入「是否当前源」的守卫 —— 那会砍掉点当前源查看目录的能力",
            !block.contains("oldBookUrl")
        )
    }
}
