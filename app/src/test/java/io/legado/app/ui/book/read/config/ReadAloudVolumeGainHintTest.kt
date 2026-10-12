package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 音量增强开关的源码级静态断言。
 *
 * 历史：10050 首版用 `tvVolumeGainHint.setText(hintRes)`，并让"无提示"档返回 `0` 表示清空——
 * 但 `TextView.setText(Int)` 的参数是**字符串资源 id**，`0` 不是有效资源，实际运行抛
 * `Resources$NotFoundException`（String resource ID #0x0）。**只要打开朗读面板且增益为默认值 0
 * 就必崩**（已在平板 TB-9707F 实测复现）。
 *
 * 10083 把说明文字整个收进 ⓘ 弹窗，那个"空串清空"分支随之删除，崩溃路径不复存在。
 * 但同一类陷阱仍可能被重新引入（任何 `setText(可能为 0 的资源 id)`），故保留一条通用守卫。
 *
 * 这些无法用 JVM 单测覆盖（`TextView` 是 Android 类），故退化为对源码文本的静态断言。
 */
class ReadAloudVolumeGainHintTest {

    private val dialogFile = File(
        "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
    )

    private fun source(): String {
        assertTrue(
            "找不到 ReadAloudDialog.kt（Gradle 单测 CWD 应为模块目录 app/）：${dialogFile.absolutePath}",
            dialogFile.exists()
        )
        return dialogFile.readText()
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
            .substringBefore("private fun configureSpeechRateSlider")
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

    /**
     * 语速与音量增强必须共用同一行：两者各自独占一行时，内容摊在两端、中间留下大片空白
     * （作者实机指出）。同时标签必须用短文案，否则挤不下。
     */
    @Test
    fun speedAndVolumeGainShareOneRow() {
        val layout = File("src/main/res/layout/dialog_read_aloud.xml").readText()
        // ⚠️ 必须按**该行自身的闭合标签**截取，不能用「下一个出现的任意标记」：
        // 只要把开关挪进紧随其后的另一个 LinearLayout，那种截法仍然包含它而误判为"同一行"。
        // 行容器缩进 8 空格，故其闭合标签是 `        </LinearLayout>`；子控件闭合为 12 空格。
        val rowStart = layout.indexOf("android:id=\"@+id/ll_tts_SpeechRate\"")
        assertTrue("未找到语速行 ll_tts_SpeechRate", rowStart >= 0)
        val rowEnd = layout.indexOf("\n        </LinearLayout>", rowStart)
        assertTrue("未找到语速行的闭合标签", rowEnd > rowStart)
        val row = layout.substring(rowStart, rowEnd)

        assertTrue("语速标签必须在同一行内", row.contains("@+id/tv_tts_speed\""))
        assertTrue("跟随系统开关必须在同一行内", row.contains("@+id/cb_tts_follow_sys"))
        assertTrue("音量增强开关必须在同一行内", row.contains("@+id/cb_volume_gain"))
        assertTrue("ⓘ 帮助入口必须在同一行内", row.contains("@+id/iv_volume_gain_help"))
        // 独立的"音质会受影响"提示行已删除：文案收进 ⓘ 弹窗，避免挤占行宽
        assertFalse(
            "音质代价不应再单独占一个常显控件",
            layout.contains("@+id/tv_volume_gain_hint")
        )
        for (path in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh/strings.xml"
        )) {
            val strings = File(path).readText()
            assertTrue("$path 缺少 read_aloud_volume_gain", strings.contains("name=\"read_aloud_volume_gain\""))
            // 标签必须短：带着"（2 倍）"后缀会挤掉同一行的其它控件
            assertFalse(
                "$path 的 read_aloud_volume_gain 不得再带倍数后缀（挤占行宽，倍数写在 ⓘ 里）",
                strings.contains("""<string name="read_aloud_volume_gain">音量增强（2 倍）</string>""") ||
                    strings.contains("""<string name="read_aloud_volume_gain">Volume boost (2x)</string>""")
            )
        }
    }
}
