package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 音量增强提示的赋值方式契约（源码级静态断言）。
 *
 * 背景：10050 首版用 `tvVolumeGainHint.setText(hintRes)`，并让"无提示"档返回 `0` 表示清空——
 * 但 `TextView.setText(Int)` 的参数是**字符串资源 id**，`0` 不是有效资源，实际运行抛
 * `Resources$` + `NotFoundException`（String resource ID #0x0）。**只要打开朗读面板且增益为默认值 0
 * 就必崩**（已在平板 TB-9707F 实测复现）。
 *
 * 10082 把滑条换成开关后仍有同一个空串分支（关闭时清空提示），故这条契约必须保留。
 *
 * 这条无法用 JVM 单测覆盖（`TextView` 是 Android 类），故退化为对源码文本的静态断言：
 * 它不能证明运行正确，但能挡住"又把资源 id 0 当空文本"这个具体回归。
 */
class ReadAloudVolumeGainHintTest {

    private val sourceFile = File(
        "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
    )

    private fun source(): String {
        assertTrue(
            "找不到 ReadAloudDialog.kt（Gradle 单测 CWD 应为模块目录 app/）：${sourceFile.absolutePath}",
            sourceFile.exists()
        )
        return sourceFile.readText()
    }

    @Test
    fun volumeGainHintNeverAssignsAResourceId() {
        val body = source().substringAfter("private fun upVolumeGainHint")
            .substringBefore("private fun configureSpeechRateSlider")

        assertTrue("未能定位 upVolumeGainHint 方法体", body.isNotBlank())
        // 曾崩溃的写法：把可能为 0 的资源 id 直接交给 setText(Int)
        assertFalse(
            "tvVolumeGainHint 不得用 setText(资源id)：0 不是有效资源，会抛 Resources NotFoundException",
            Regex("""tvVolumeGainHint\s*\.\s*setText\s*\(""").containsMatchIn(body)
        )
        // 空档必须赋 CharSequence，而不是数字
        assertTrue(
            "关闭档应以空字符串赋值",
            body.contains("\"\"")
        )
    }

    /**
     * 音量增强 = 开关，不是滑条。这里锁的是「开关真的落到设置上」这条端到端接线：
     * 布局有开关、旧滑条与其加减按钮已移除、对话框读同一个开关并写入 2 倍档。
     * 少任何一环都会表现为"开关能拨但没效果"。
     */
    @Test
    fun volumeGainIsASwitchEndToEnd() {
        val dialog = source()
        val layout = File("src/main/res/layout/dialog_read_aloud.xml").also {
            assertTrue("找不到 dialog_read_aloud.xml：${it.absolutePath}", it.exists())
        }.readText()

        assertTrue("布局必须有音量增强开关 cb_volume_gain", layout.contains("@+id/cb_volume_gain"))
        // 滑条及其 +/- 按钮必须一起删干净：留一个没有逻辑的控件就是死 UI
        for (stale in listOf("seek_volume_gain", "iv_volume_gain_reduce", "iv_volume_gain_add")) {
            assertFalse("音量增强已改为开关，不得残留 $stale", layout.contains("@+id/$stale"))
        }

        val binding = dialog.substringAfter("private fun bindVolumeGainControls")
            .substringBefore("private fun upVolumeGainHint")
        // ⚠️ 逐行锚定，不能只查"块里出现过 AppConfig.ttsVolumeGain"：硬编码 isChecked = false
        // 之后该标识符仍留在 setter 行里，断言会全绿（实测踩过）。
        assertTrue(
            "开关初始状态必须由设置回填，实际行为：" +
                "${binding.lines().firstOrNull { it.contains("cbVolumeGain.isChecked") }}",
            binding.contains("cbVolumeGain.isChecked = AppConfig.ttsVolumeGain > 0")
        )
        assertTrue(
            "打开必须写入固定 2 倍档、关闭必须写回 0（不增强），实际行为：" +
                "${binding.lines().firstOrNull { it.contains("AppConfig.ttsVolumeGain =") }}",
            binding.contains("AppConfig.ttsVolumeGain = if (isChecked) VolumeGain.MAX_PERCENT else 0")
        )
        // 监听器旧引用不清空会在回填 isChecked 时触发写入，把用户设置在打开面板时改掉。
        // ⚠️ 必须写成 clearAt >= 0 && clearAt < fillAt：只写 `<` 的话，把这行删掉会让
        // indexOf 返回 -1，-1 < fillAt 仍然成立，断言变成永不失败的摆设。
        val clearAt = binding.indexOf("cbVolumeGain.setOnCheckedChangeListener(null)")
        val fillAt = binding.indexOf("cbVolumeGain.isChecked")
        assertTrue(
            "回填 isChecked 前必须先清空监听器（clearAt=$clearAt fillAt=$fillAt）",
            clearAt >= 0 && fillAt >= 0 && clearAt < fillAt
        )
    }
}
