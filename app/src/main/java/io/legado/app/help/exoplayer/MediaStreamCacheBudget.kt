package io.legado.app.help.exoplayer

/**
 * 媒体流缓存的**预算决策纯函数**（不碰 Android / appCtx，可在 JVM 单测求值）。
 *
 * 背景：视频/音频播放时 ExoPlayer 的 CacheDataSource+CacheDataSink 会把流写进
 * `book_cache/<书>/video_media` 这类**按书独立**的目录。原实现给每本书各建一个
 * 4GB 的 LRU 淘汰器，而淘汰器只统计自己那一个目录 —— 32 本书就是 32 份 4GB
 * 配额，合计永不淘汰，实测在平板上堆到 6.5GB。
 *
 * 这里把「谁该被删」收敛成一个可测试的决策：总量超预算时按最久未播放优先删，
 * 且**永不删除带完成标记（真实下载）或正在播放的条目**。
 */
data class MediaCacheEntry(
    /** 需要删除时使用的绝对路径。 */
    val path: String,
    val bytes: Long,
    /** 该目录最近一次被写入的时间（播放即刷新）。 */
    val lastUsedAt: Long,
    /** true = 真实下载产物或正在播放，任何情况下都不得删除。 */
    val keep: Boolean,
)

data class MediaCacheEvictionPlan(
    val deletePaths: List<String>,
    val freedBytes: Long,
    val remainingBytes: Long,
)

/**
 * @param entries 全部候选目录（含 keep 项，keep 项同样计入总量）
 * @param budgetBytes 允许占用的总字节数
 */
fun planMediaCacheEvictions(
    entries: List<MediaCacheEntry>,
    budgetBytes: Long,
): MediaCacheEvictionPlan {
    require(budgetBytes > 0L) { "media cache budget must be positive: $budgetBytes" }
    val total = entries.sumOf { it.bytes }
    if (total <= budgetBytes) {
        return MediaCacheEvictionPlan(emptyList(), 0L, total)
    }
    val deletable = entries
        .asSequence()
        .filterNot { it.keep }
        .sortedWith(compareBy({ it.lastUsedAt }, { it.path }))
    val deleted = mutableListOf<String>()
    var remaining = total
    for (entry in deletable) {
        if (remaining <= budgetBytes) break
        deleted.add(entry.path)
        remaining -= entry.bytes
    }
    return MediaCacheEvictionPlan(deleted, total - remaining, remaining)
}

/** 退出播放后延迟清扫：留出「手滑误退、几秒内重进」的窗口。 */
const val MEDIA_CACHE_SWEEP_DELAY_MS = 8_000L

/** 视频、音频各 200MB 全局上限。 */
const val MEDIA_STREAM_CACHE_BUDGET_BYTES = 200L * 1024 * 1024
