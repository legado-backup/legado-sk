package io.legado.app.help.tts

/**
 * TTS 音频缓存的**清扫决策纯函数**（不碰 Android / appCtx，可在 JVM 单测求值）。
 *
 * 背景：[TtsCacheStore] 原先**没有任何容量上限或淘汰规则**，只有「按章删 /
 * 按书删 / 全局清」。而实时缓存（系统 TTS + TTS-Wav + 实时缓存）会把每个朗读
 * 单元的 wav 无上限写进 `book_cache/<书>/tts_cache/<章节>/`，该目录属于
 * externalFiles，被系统计为**用户数据**而非缓存 —— 与 10100 修掉的
 * `video_media` 是同一类问题，只是当时只覆盖了媒体流缓存。
 *
 * 清扫规则（作者选定）：**只留当前章，其余全删**；但用户**主动预缓存**的章节
 * 必须豁免 —— 那是他花时间排队合成出来的产物，删掉等于白做，与 10100 用
 * `*_media_complete/` 豁免真实下载同构。
 *
 * 本文件只决定「删哪些」，不执行删除；执行在 [TtsCacheStore.sweep]。
 */
data class TtsChapterCacheEntry(
    /** 章节目录名（[TtsCacheStore.chapterStem] 产物）。 */
    val stem: String,
    val bytes: Long,
    /** true = 该章带批量预缓存完成标记，任何情况下都不得删除。 */
    val batch: Boolean,
)

data class TtsCacheSweepPlan(
    val deleteStems: List<String>,
    val freedBytes: Long,
)

/**
 * @param entries 本书 tts_cache 下的全部章节目录
 * @param keepChapterStems 需要保留的章节 stem（当前正在朗读/阅读的章）
 */
fun planTtsCacheSweep(
    entries: List<TtsChapterCacheEntry>,
    keepChapterStems: Set<String>,
): TtsCacheSweepPlan {
    val doomed = entries.filterNot { entry ->
        entry.batch || entry.stem in keepChapterStems
    }
    return TtsCacheSweepPlan(
        deleteStems = doomed.map { it.stem },
        freedBytes = doomed.sumOf { it.bytes },
    )
}

/** 朗读服务退出后延迟清扫：留出「手滑误退、几秒内重进」的窗口。 */
const val TTS_CACHE_SWEEP_DELAY_MS = 8_000L
