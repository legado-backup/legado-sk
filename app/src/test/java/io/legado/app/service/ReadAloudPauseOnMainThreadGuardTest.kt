package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「在线朗读暂停必须真的停声」的**守卫契约**（源码级静态断言）。
 *
 * 实机事故（2026-10-31 夜，平板 HA1KAPWG）：合成失败回调在 IO 线程调用
 * `pauseReadAloud()` → `super` 已放弃音频焦点，`exoPlayer.pause()` 因跨线程抛
 * `IllegalStateException` 被 `runCatching` 吞掉 → 焦点已放弃但声音继续，缓冲播完后
 * 彻底静音（章中停、额度未用完、服务仍在）。JVM 单测无法驱动真实播放，故守卫
 * 锚定到函数体：暂停/恢复的播放器操作必须在主线程且不得吞异常，合成失败必须重试并明示。
 */
class ReadAloudPauseOnMainThreadGuardTest {

    private fun serviceSource(): String {
        val f = File("src/main/java/io/legado/app/service/HttpReadAloudService.kt")
        assertTrue("找不到源文件：${f.absolutePath}", f.exists())
        return f.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    private fun pauseBody(): String = slice(
        serviceSource(),
        "override fun pauseReadAloud(abandonFocus: Boolean) {",
        "override fun resumeReadAloud()"
    )

    private fun resumeBody(): String = slice(
        serviceSource(),
        "override fun resumeReadAloud() {",
        "private fun upPlayPos()"
    )

    private fun scriptDownloadBody(): String = slice(
        serviceSource(),
        "private fun downloadAndPlayScriptAudios()",
        "private suspend fun prepareScriptMediaItemsWithRetry"
    )

    /** 播放器只能在主线程被访问：暂停必须包在 lifecycleScope.launch 里，且不得 runCatching。 */
    @Test
    fun pauseTouchesPlayerOnMainThreadWithoutSwallowing() {
        val body = pauseBody()
        assertFalse("pauseReadAloud 不得用 runCatching 吞播放器异常：$body", body.contains("runCatching"))
        val launch = body.indexOf("lifecycleScope.launch")
        val pause = body.indexOf("exoPlayer.pause()")
        assertTrue("exoPlayer.pause() 必须存在", pause >= 0)
        assertTrue("exoPlayer.pause() 必须在 lifecycleScope.launch 之后（主线程）：$body", launch in 0 until pause)
    }

    /** 恢复同理：跨线程 play() 会抛同样异常。 */
    @Test
    fun resumeTouchesPlayerOnMainThreadWithoutSwallowing() {
        val body = resumeBody()
        assertFalse("resumeReadAloud 不得用 runCatching 吞播放器异常：$body", body.contains("runCatching"))
        val launch = body.indexOf("lifecycleScope.launch")
        val play = body.indexOf("exoPlayer.play()")
        assertTrue("exoPlayer.play() 必须存在", play >= 0)
        assertTrue("exoPlayer.play() 必须在 lifecycleScope.launch 之后（主线程）：$body", launch in 0 until play)
    }

    /** 合成失败必须经过重试入口，并在最终失败时记录日志 + toast + 暂停，不能静默。 */
    @Test
    fun synthesisFailureRetriesAndExposesReason() {
        val body = scriptDownloadBody()
        assertTrue("合成必须走带重试的入口", body.contains("prepareScriptMediaItemsWithRetry("))
        assertFalse(
            "不得直接调用无重试的 prepareScriptMediaItems(",
            body.contains("prepareScriptMediaItems(scriptEngine")
        )
        assertTrue("最终失败必须写日志", body.contains("LogModule.READ_ALOUD"))
        assertTrue("最终失败必须有可见提示", body.contains("toastOnUi(getString(R.string.read_aloud_synthesis_failed"))
        assertTrue("最终失败必须暂停", body.contains("pauseReadAloud()"))
    }
}
