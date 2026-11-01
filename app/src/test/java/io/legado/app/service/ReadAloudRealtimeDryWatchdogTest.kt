package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「实时朗读快速重试」的守卫契约（源码级静态断言）。
 *
 * 实机事故（2026-11-01 17:43，平板 HA1KAPWG，系统引擎 MultiTTS 实时缓存模式）：
 * 第 54 段 `synthesizeToFile` 请求被引擎吞掉（onDone/onError 都不来），已合成段播到
 * 17:43:10 枯竭 → 无声 67 秒且界面仍显示「正在播放」，直到 90 秒单段看门狗到点才
 * 重建客户端恢复。单段超时从派发起算，与「实际没声音」之间有整段队列余量差。
 *
 * 修复：新增可选开关（默认关闭＝保持原保守行为）。开启后单段超时固定 30 秒，
 * 并追加「队列干涸 15 秒」判据——播放器已空转且当前段迟迟不出声即重试。
 * JVM 单测无法驱动真实播放，故守卫锚定到源码结构。
 */
class ReadAloudRealtimeDryWatchdogTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到源文件：${f.absolutePath}", f.exists())
        return f.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")
    }

    private fun serviceSource(): String =
        source("src/main/java/io/legado/app/service/TTSReadAloudService.kt")

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    private fun armBody(): String = slice(
        serviceSource(),
        "private fun armRealtimeDryWatchdog(index: Int) {",
        "private fun cancelRealtimeDryWatchdog()"
    )

    /** 默认关闭，且关闭时仍用用户设置值（保守行为不变）。 */
    @Test
    fun fastRetryDefaultsOffAndFallsBackToConfiguredTimeout() {
        val config = source("src/main/java/io/legado/app/help/config/AppConfig.kt")
        assertTrue(
            "ttsCacheFastRetry 必须默认关闭",
            config.contains("getPrefBoolean(PreferKey.ttsCacheFastRetry, false)")
        )
        assertTrue("快速重试单段超时固定 30 秒", config.contains("FAST_RETRY_SEGMENT_TIMEOUT_SECONDS = 30"))
        val effective = slice(
            config,
            "val ttsRealtimeSegmentTimeoutSeconds: Int",
            "const val FAST_RETRY_SEGMENT_TIMEOUT_SECONDS"
        )
        assertTrue(
            "关闭快速重试必须回落用户设置值：$effective",
            effective.contains("ttsCacheSegmentTimeoutSeconds")
        )
        assertTrue(
            "开启快速重试必须用固定值：$effective",
            effective.contains("FAST_RETRY_SEGMENT_TIMEOUT_SECONDS")
        )
    }

    /** 干涸判据只在快速重试开启时生效，默认路径不得被改变。 */
    @Test
    fun dryWatchdogOnlyArmedInFastRetryMode() {
        val body = armBody()
        assertTrue(
            "未开启快速重试必须直接返回：$body",
            body.contains("if (!AppConfig.ttsCacheFastRetry) return")
        )
        val guard = body.indexOf("if (!AppConfig.ttsCacheFastRetry) return")
        val post = body.indexOf("postDelayed")
        assertTrue("派发必须在开关判定之后：$body", guard >= 0 && post > guard)
    }

    /** 15 秒干涸阈值 + 必须确认「确实没在出声」才判挂死。 */
    @Test
    fun dryWatchdogWaitsFifteenSecondsOfSilence() {
        val service = serviceSource()
        assertTrue(
            "干涸阈值必须是 15 秒常量",
            service.contains("private const val REALTIME_DRY_STALL_GRACE_SECONDS = 15")
        )
        val body = armBody()
        assertTrue("必须按秒换算后派发：$body", body.contains("REALTIME_DRY_STALL_GRACE_SECONDS * 1000L"))
        assertTrue(
            "必须交给纯函数判定「确实没在出声」（当前段/在跑/未暂停/播放器停着）：$body",
            body.contains("shouldTreatAsDry(index, nowSpeak, isRun, pause, wavPlayer.isPlaying)")
        )
        assertTrue(
            "必须走既有重试链（重建客户端）：$body",
            body.contains("handleSpeakError(\"tts realtime dry timeout\", retryWithReinit = true)")
        )
    }

    /**
     * 干涸判据是纯函数，直接断言行为（源码字面量断言挡不住语义退化）。
     * 尤其：重建引擎/重试期间不能把「还没开始出声」当成第二次失败而跳段。
     */
    @Test
    fun dryDecisionFollowsRealBehaviour() {
        assertTrue(
            "当前段、在跑、未暂停、播放器停着⇒判干涸",
            shouldTreatAsDry(index = 54, nowSpeak = 54, isRun = true, paused = false, playerPlaying = false)
        )
        assertFalse(
            "不是当前段不得判干涸",
            shouldTreatAsDry(54, 55, isRun = true, paused = false, playerPlaying = false)
        )
        assertFalse(
            "已暂停不得判干涸",
            shouldTreatAsDry(54, 54, isRun = true, paused = true, playerPlaying = false)
        )
        assertFalse(
            "未在朗读不得判干涸",
            shouldTreatAsDry(54, 54, isRun = false, paused = false, playerPlaying = false)
        )
        assertFalse(
            "播放器仍在出声不属干涸",
            shouldTreatAsDry(54, 54, isRun = true, paused = false, playerPlaying = true)
        )
    }

    /** 每次进场都重新计时（残留的旧截止时刻会提前误判），且播放/暂停/复位/重试处必须撤销。 */
    @Test
    fun dryWatchdogIsPerUnitAndCancelledWherePlaybackStarts() {
        val service = serviceSource()
        val arm = armBody()
        assertTrue(
            "武装前必须先撤销旧回调（同一段重入需续期）：$arm",
            arm.indexOf("cancelRealtimeDryWatchdog()") in 0 until arm.indexOf("postDelayed")
        )
        assertTrue(
            "重试链开始时必须撤销，否则重建引擎期会被当成第二次失败而跳段",
            slice(service, "private fun handleSpeakError(message: String, retryWithReinit: Boolean) {", "val paragraphKey")
                .contains("cancelRealtimeDryWatchdog()")
        )
        val speakUnit = slice(
            service,
            "private fun speakRealtimeUnit(index: Int, text: String) {",
            "private fun buildRealtimeTaskList("
        )
        assertTrue("当前段解析入口必须武装看门狗：$speakUnit", speakUnit.contains("armRealtimeDryWatchdog(index)"))

        val playFile = slice(service, "private fun playWavFile(index: Int, file: File?) {", "private fun advanceWavParagraph()")
        assertTrue("出声后必须撤销：$playFile", playFile.contains("cancelRealtimeDryWatchdog()"))

        val pause = slice(service, "override fun pauseReadAloud(abandonFocus: Boolean) {", "override fun resumeReadAloud()")
        assertTrue("暂停必须撤销：$pause", pause.contains("cancelRealtimeDryWatchdog()"))

        val reset = slice(service, "private fun resetWavPlayback() {", "private fun speakWavCurrentParagraph()")
        assertTrue("复位必须撤销：$reset", reset.contains("cancelRealtimeDryWatchdog()"))
    }

    /** 看门狗必须用「快速重试感知」的有效超时，不能再直接读原始设置值。 */
    @Test
    fun watchdogUsesEffectiveTimeout() {
        val service = serviceSource()
        assertTrue(
            "看门狗派发必须用有效超时",
            service.contains("val timeoutSeconds = AppConfig.ttsRealtimeSegmentTimeoutSeconds") &&
                service.contains("timeoutSeconds * 1000L")
        )
        assertFalse(
            "看门狗不得再用原始设置值",
            service.contains("AppConfig.ttsCacheSegmentTimeoutSeconds * 1000L")
        )
    }

    /** 开关必须在 TTS 缓存设置里可见，且界面默认值与读取默认值一致（false）。 */
    @Test
    fun switchIsExposedWithMatchingDefault() {
        val pref = source("src/main/res/xml/pref_config_tts_cache.xml")
        assertTrue("设置项必须存在", pref.contains("android:key=\"ttsCacheFastRetry\""))
        assertTrue(
            "必须是默认关闭的开关：$pref",
            pref.contains("android:defaultValue=\"false\"\nandroid:key=\"ttsCacheFastRetry\"")
        )
        assertTrue("必须有说明文案", pref.contains("@string/tts_cache_fast_retry_summary"))
    }
}
