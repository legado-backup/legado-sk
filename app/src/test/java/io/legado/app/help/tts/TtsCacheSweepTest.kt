package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 音频缓存清扫的**真行为**回归锁。
 *
 * 背景（平板上实测的同源缺陷）：`MediaStreamCacheJanitor` 的 200MB 预算只覆盖
 * `video_media` / `audio_media`，`tts_cache` 完全没在管辖范围内。而系统 TTS 的
 * 实时缓存会把每个朗读单元的 wav 无上限写进
 * `book_cache/<书>/tts_cache/<章节>/`，该目录属于 externalFiles，被系统计为
 * **用户数据**而非缓存 —— 与 10100 修掉的 video_media 是同一类问题。
 *
 * 作者选定的规则：**只留当前章，其余全删**；但主动预缓存过的章必须豁免。
 * 因此本测试断言的是这三条语义，而不是某行常量还在。
 */
class TtsCacheSweepTest {

    private fun chapter(
        stem: String,
        mb: Long = 1,
        batch: Boolean = false,
    ) = TtsChapterCacheEntry(
        stem = stem,
        bytes = mb * 1024 * 1024,
        batch = batch,
    )

    /** 只留当前章：其余全部进入待删列表。 */
    @Test
    fun keepsOnlyCurrentChapter() {
        val plan = planTtsCacheSweep(
            listOf(chapter("c-1"), chapter("c-2"), chapter("c-3")),
            keepChapterStems = setOf("c-3"),
        )
        assertEquals(listOf("c-1", "c-2"), plan.deleteStems)
        assertEquals(2L * 1024 * 1024, plan.freedBytes)
    }

    /** 批量预缓存的章即使不是当前章也不得删除。 */
    @Test
    fun batchChaptersAreNeverDeleted() {
        val plan = planTtsCacheSweep(
            listOf(
                chapter("c-1", batch = true),
                chapter("c-2"),
                chapter("c-3", batch = true),
            ),
            keepChapterStems = setOf("c-3"),
        )
        assertEquals(
            "带 batch 标记的章必须原样保留，只删既非当前章也非预缓存的",
            listOf("c-2"),
            plan.deleteStems,
        )
    }

    /** 只有当前章、且它恰好还是批量预缓存章：一个都不删。 */
    @Test
    fun nothingToDeleteWhenAllKept() {
        val plan = planTtsCacheSweep(
            listOf(chapter("c-1", batch = true), chapter("c-2")),
            keepChapterStems = setOf("c-1", "c-2"),
        )
        assertTrue("无可删项不得产生删除动作", plan.deleteStems.isEmpty())
        assertEquals(0L, plan.freedBytes)
    }

    /** keep 集合为空（启动清扫非当前书）：除预缓存章外全删。 */
    @Test
    fun emptyKeepDeletesAllUnmarked() {
        val plan = planTtsCacheSweep(
            listOf(chapter("c-1"), chapter("c-2", batch = true), chapter("c-3")),
            keepChapterStems = emptySet(),
        )
        assertEquals(listOf("c-1", "c-3"), plan.deleteStems)
    }

    /** 空目录不得抛异常（书首次朗读前 tts_cache 可能尚不存在）。 */
    @Test
    fun emptyEntriesProduceEmptyPlan() {
        val plan = planTtsCacheSweep(emptyList(), setOf("c-1"))
        assertTrue(plan.deleteStems.isEmpty())
        assertEquals(0L, plan.freedBytes)
    }
}
