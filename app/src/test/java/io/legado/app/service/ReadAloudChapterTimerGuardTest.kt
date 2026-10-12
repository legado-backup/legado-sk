package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「听书按章节定时」的**守卫契约**（源码级静态断言）。
 *
 * `BaseReadAloudService` 继承 Android `Service`，无法在 JVM 单测里驱动真实播放，
 * 故退化为源码断言：不能证明运行正确，但能挡住把守卫改掉的具体回归。
 * 断言一律锚定到某个函数的**函数体**与语句**先后关系**，不用「全文件包含某字符串」
 * ——那种写法在把调用挪进/挪出守卫分支后仍会通过（本仓库已多次踩过）。
 */
class ReadAloudChapterTimerGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun executableOnly(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    private fun serviceSource(): String =
        executableOnly(moduleSource("src/main/java/io/legado/app/service/BaseReadAloudService.kt"))

    /** 取 [startMarker] 到 [endMarker]（含）之间的源码，用于锚定单个函数体。 */
    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end + endMarker.length)
    }

    private fun nextChapterBody(): String = slice(
        serviceSource(),
        "open fun nextChapter(syncView: Boolean = false)",
        "advanceToNextChapter(syncView)"
    )

    /**
     * ⚠️ 章节计数只能在**引擎自然推进**（syncView=false）时消耗，且归零后必须
     * **停止而不是推进**。三条语句的先后关系是该不变量的全部内容：
     * 守卫 → 递减 → 判零 → 停止 → return，且 `advanceToNextChapter` 必须在停止之后。
     */
    @Test
    fun naturalAdvanceConsumesChapterAndStopsBeforeAdvancing() {
        val body = nextChapterBody()

        val guard = body.indexOf("!syncView && timeChapter > 0")
        val decrement = body.indexOf("timeChapter--")
        val zeroCheck = body.indexOf("timeChapter == 0")
        val stop = body.indexOf("ReadAloud.stop(this)")
        val advance = body.indexOf("advanceToNextChapter(syncView)")

        assertTrue("未找到 !syncView 守卫（用户显式跳章不得消耗章节定时）", guard >= 0)
        assertTrue("未找到 timeChapter-- 递减", decrement >= 0)
        assertTrue("递减必须发生在 !syncView 守卫之内", decrement > guard)
        assertTrue(
            "递减后必须发布 READ_ALOUD_DS_CHAPTER，否则界面剩余章数不实时更新",
            body.substring(decrement).contains("READ_ALOUD_DS_CHAPTER")
        )
        assertTrue("递减与停止之间必须有 timeChapter == 0 判零", zeroCheck > decrement)
        assertTrue("判零后必须调用 ReadAloud.stop(this)", stop > zeroCheck)
        assertTrue("停止必须发生在推进到下一章之前", advance > stop)
        // 停止分支必须立刻 return，否则会继续执行后面的推进
        val afterStop = body.substring(stop)
        assertTrue("停止后必须 return，不能继续推进到下一章", afterStop.contains("return"))
    }

    /** 用户显式跳章（syncView=true，对话框/通知栏/线控）不消耗定时。 */
    @Test
    fun userExplicitChapterJumpDoesNotConsume() {
        val lines = nextChapterBody().lines()
        val decrementIndex = lines.indexOfFirst { it == "timeChapter--" }
        assertTrue("未找到 timeChapter-- 语句", decrementIndex > 0)
        // 锚定递减语句的**上一行**就是守卫：这样断言不会因为“文件里存在某字符串”而误绿
        assertTrue(
            "递减必须紧跟在 !syncView 守卫行之后（上一行实际为：" +
                "${lines[decrementIndex - 1]}）",
            lines[decrementIndex - 1].contains("!syncView && timeChapter > 0")
        )
    }

    /**
     * ⚠️ 两种定时互斥。任一分钟侧入口都必须清空章节定时，
     * 且章节侧入口必须把 timeMinute 归零，否则两个倒计时会同时生效。
     */
    @Test
    fun minuteAndChapterTimersAreMutuallyExclusive() {
        val source = serviceSource()
        val setTimer = slice(source, "private fun setTimer(minute: Int)", "doDs()\n}")
        val addTimer = slice(source, "private fun addTimer()", "doDs()\n}")
        val setChapterTimer = slice(
            source,
            "private fun setChapterTimer(count: Int)",
            "postEvent(EventBus.READ_ALOUD_DS_CHAPTER, timeChapter)"
        )

        assertTrue("setTimer 必须清空章节定时", setTimer.contains("clearChapterTimer()"))
        assertTrue(
            "通知栏 +10 分钟按钮（addTimer）同属分钟定时，必须清空章节定时",
            addTimer.contains("clearChapterTimer()")
        )
        assertTrue("setChapterTimer 必须清空分钟定时", setChapterTimer.contains("timeMinute = 0"))
    }

    /**
     * ⚠️ 按章节定时是**会话内一次性设定，不持久化**：服务每次启动都必须从关闭开始，
     * 否则上一晚「听完本章」会残留成默认行为。
     */
    @Test
    fun chapterTimerResetsOnServiceStart() {
        val onCreate = slice(serviceSource(), "override fun onCreate()", "setTimer(AppConfig.ttsTimer)")
        assertTrue(
            "onCreate 必须在应用默认分钟定时之前把 timeChapter 复位为 0",
            onCreate.contains("timeChapter = 0")
        )
    }

    /**
     * ⚠️ 停止朗读（onDestroy）也必须清掉章节定时。分钟定时有 AppConfig.ttsTimer 兜底显示，
     * 章节定时没有持久化默认值：不清就会在服务已停止后仍显示「还剩 N 章」（已实机复现）。
     */
    @Test
    fun chapterTimerClearsOnServiceDestroy() {
        val onDestroy = slice(
            serviceSource(),
            "override fun onDestroy()",
            "if (runningClass == this::class.java)"
        )
        assertTrue(
            "onDestroy 必须 clearChapterTimer()，否则停止朗读后面板/通知仍显示剩余章数",
            onDestroy.contains("clearChapterTimer()")
        )
    }

    /**
     * 档位表只能有**一份**（`BaseReadAloudService.chapterTimerOptions`），三个入口都消费它。
     * 断言逐入口锚定「消费档位表 + 派发 setChapterTimer」，避免某处偷偷写死别的档位而漂移。
     */
    @Test
    fun everyChapterTimerSurfaceConsumesTheSharedOptions() {
        val service = serviceSource()
        val options = slice(service, "val chapterTimerOptions", "intArrayOf(1, 3, 5)")
        assertTrue("档位表必须集中在 BaseReadAloudService", options.contains("intArrayOf(1, 3, 5)"))

        val dialog = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        )
        // 入口一：定时行选择器
        val selector = slice(dialog, "private fun showTimerSelector()", "ReadAloud.setChapterTimer")
        assertTrue("选择器必须使用共享档位表", selector.contains("chapterTimerOptions"))
        assertTrue("选择器必须派发 setChapterTimer", selector.contains("ReadAloud.setChapterTimer"))
        // 入口二：定时行下方的三个快捷档按钮
        val buttons = slice(
            dialog,
            "private fun bindChapterTimerButtons()",
            "ReadAloud.setChapterTimer"
        )
        for (view in listOf("tvTimerChapterCurrent", "tvTimerChapter3", "tvTimerChapter5")) {
            assertTrue("快捷档必须绑定 $view", buttons.contains(view))
        }
        assertTrue("快捷档必须使用共享档位表", buttons.contains("chapterTimerOptions"))
        // 只绑定不接线是死按钮：必须真的挂在 initEvent 里
        val source = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        )
        val initEvent = slice(
            source,
            "private fun initEvent()",
            "seekTtsSpeechRate.setOnSeekBarChangeListener"
        )
        assertTrue(
            "bindChapterTimerButtons 必须在 initEvent 中被调用，否则快捷键既无文案也点不动",
            initEvent.contains("bindChapterTimerButtons()")
        )

        val popup = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/audio/SliderPopup.kt")
        )
        val quickPick = slice(popup, "private fun bindChapterOptions()", "dismiss()")
        assertTrue("听书页快捷档必须使用共享档位表", quickPick.contains("chapterTimerOptions"))
        assertTrue("听书页必须派发 setChapterTimer", quickPick.contains("ReadAloud.setChapterTimer"))
        assertTrue(
            "章节快捷档只能是 TIMER 模式（倍速弹窗不得出现）",
            quickPick.contains("if (name != TIMER) return")
        )
    }

    /**
     * 通知栏/媒体控件标题必须显示章节剩余，而不是落回默认文案。
     * 断言锚定标题状态函数的**章节分支本身**（含所用字符串资源 id），
     * 否则把分支改成别的文案仍会绿。
     */
    @Test
    fun notificationTitleShowsChapterRemaining() {
        val titleFn = slice(
            serviceSource(),
            "private fun aloudStatusTitle()",
            "else -> getString(R.string.read_aloud_t)"
        )
        assertTrue("未定位 aloudStatusTitle", titleFn.isNotBlank())
        assertTrue(
            "章节分支必须出现在 timeChapter > 0 之后并使用 read_aloud_timer_chapter",
            titleFn.contains("timeChapter > 0 -> getString(R.string.read_aloud_timer_chapter")
        )
        // 通知栏与媒体元数据必须共用同一入口，避免两处文案漂移
        val source = serviceSource()
        val shares = Regex("aloudStatusTitle\\(\\)").findAll(source).count()
        assertTrue(
            "通知与媒体元数据必须共用 aloudStatusTitle()（实际引用 $shares 次）",
            shares >= 2
        )
    }

    /**
     * 文案辅助函数必须真正引用各自的字符串资源（声明了字符串但代码不用，
     * 只查 strings.xml 存在与否是查不出来的）。
     */
    @Test
    fun chapterTimerTextHelpersUseTheirStrings() {
        val source = serviceSource()
        val remaining = slice(
            source,
            "fun chapterTimerRemainingText()",
            "timer_chapter, it)"
        )
        assertTrue("剩余章数文案必须使用 timer_chapter", remaining.contains("R.string.timer_chapter"))
        val option = slice(
            source,
            "fun chapterTimerOptionText(count: Int)",
            "R.string.timer_chapter_m, count)"
        )
        assertTrue("档位文案必须使用 timer_chapter_current", option.contains("R.string.timer_chapter_current"))
        assertTrue("档位文案必须使用 timer_chapter_m", option.contains("R.string.timer_chapter_m"))
    }

    /** 显示侧必须消费新事件与状态，且两个语言目录都要有新文案。 */
    @Test
    fun displaySurfacesAndStringsWired() {        val audio = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt")
        )
        assertTrue(
            "听书页必须观察 READ_ALOUD_DS_CHAPTER",
            audio.contains("EventBus.READ_ALOUD_DS_CHAPTER")
        )
        assertTrue(
            "听书页必须优先显示剩余章数",
            audio.contains("chapterTimerRemainingText()")
        )

        val dialog = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        )
        assertTrue(
            "朗读面板必须观察 READ_ALOUD_DS_CHAPTER",
            dialog.contains("EventBus.READ_ALOUD_DS_CHAPTER")
        )
        // 章节模式下滑条由程序归零，其 onProgressChanged 会把文案覆盖回“0 分钟”。
        // 这一 early-return 是显示正确性的必要条件，删掉即回归（已实机语义验证过依赖关系）。
        val progressHandler = slice(
            dialog,
            "override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)",
            "upTimerMinutesText(progress)"
        )
        assertTrue(
            "章节定时激活时 onProgressChanged 必须提前返回，否则章节文案被“0 分钟”覆盖",
            progressHandler.contains("chapterTimerRemainingText() != null") &&
                progressHandler.contains("return")
        )

        for (path in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh/strings.xml"
        )) {
            val strings = moduleSource(path)
            for (name in listOf(
                "read_aloud_timer_chapter",
                "timer_chapter_current",
                "timer_chapter_m",
                "timer_chapter"
            )) {
                assertTrue("$path 缺少字符串 $name", strings.contains("name=\"$name\""))
            }
        }
    }

    /**
     * 音量增强的说明文案只能出现在 ⓘ 弹窗里（声明了字符串但代码不用，只查 strings.xml 是查不出来的）。
     */
    @Test
    fun volumeGainHelpDialogUsesItsStrings() {
        val dialog = executableOnly(
            moduleSource("src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt")
        )
        val help = slice(
            dialog,
            "ivVolumeGainHelp.setOnClickListener",
            "R.string.read_aloud_volume_gain_hint"
        )
        assertTrue(
            "ⓘ 弹窗必须同时给出标题与说明（read_aloud_volume_gain / _hint）",
            help.contains("R.string.read_aloud_volume_gain") &&
                help.contains("R.string.read_aloud_volume_gain_hint")
        )
        for (path in listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh/strings.xml"
        )) {
            val strings = moduleSource(path)
            for (name in listOf(
                "read_aloud_volume_gain",
                "read_aloud_volume_gain_hint"
            )) {
                assertTrue("$path 缺少字符串 $name", strings.contains("name=\"$name\""))
            }
        }
    }
}
