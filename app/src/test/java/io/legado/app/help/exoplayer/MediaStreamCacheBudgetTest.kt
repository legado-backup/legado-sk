package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体流缓存预算的**真行为**回归锁。
 *
 * 背景（平板上实测的真实缺陷）：`book_cache/<书>/video_media` 堆到 6.5GB。
 * 原因是 `ExoPlayerHelper.simpleCache()` 给**每本书**各建一个 4GB 的
 * `LeastRecentlyUsedCacheEvictor`，而淘汰器只看自己那一个目录 ⇒ 32 本书
 * 就是 32 份 4GB 配额，谁都不触发，只增不减。
 *
 * 因此本测试断言的是「跨书总量超预算时按最久未播放删」，而不是某行常量还在。
 */
class MediaStreamCacheBudgetTest {

    private fun entry(
        path: String,
        mb: Long,
        lastUsedAt: Long,
        keep: Boolean = false,
    ) = MediaCacheEntry(
        path = path,
        bytes = mb * 1024 * 1024,
        lastUsedAt = lastUsedAt,
        keep = keep,
    )

    /** 多点小缓存合计不超预算时，一个都不许删。 */
    @Test
    fun underBudgetKeepsEverything() {
        val plan = planMediaCacheEvictions(
            listOf(
                entry("/a", 50, lastUsedAt = 1),
                entry("/b", 50, lastUsedAt = 2),
            ),
            budgetBytes = 200L * 1024 * 1024,
        )
        assertTrue("未超预算不得删除任何目录", plan.deletePaths.isEmpty())
        assertEquals(0L, plan.freedBytes)
    }

    /** 32 本各数百 MB 的真实形态：必须删到只剩预算以内，而不是每本各 4GB 配额。 */
    @Test
    fun overBudgetTrimsAcrossBooks() {
        val entries = (1..32).map { i ->
            entry("/book$i", 200, lastUsedAt = i.toLong())
        }
        val budget = 200L * 1024 * 1024
        val plan = planMediaCacheEvictions(entries, budget)
        assertEquals("总 6400MB / 预算 200MB，应收敛到 1 项", 31, plan.deletePaths.size)
        assertTrue("删完必须落回预算内", plan.remainingBytes <= budget)
        assertEquals(
            "必须优先删最久未播放的（lastUsedAt 最小）",
            "/book1",
            plan.deletePaths.first()
        )
    }

    /** 正在播放或真实下载过的目录绝不删除，即使它最旧、即使总量超预算。 */
    @Test
    fun keepEntriesAreNeverDeleted() {
        val plan = planMediaCacheEvictions(
            listOf(
                entry("/playing", 500, lastUsedAt = 0, keep = true),
                entry("/downloaded", 500, lastUsedAt = 1, keep = true),
                entry("/old", 500, lastUsedAt = 2),
            ),
            budgetBytes = 200L * 1024 * 1024,
        )
        assertEquals(
            "keep 项（正在播放 / 有完成标记）必须原样保留",
            listOf("/old"),
            plan.deletePaths
        )
        assertTrue(
            "全部可删项都删光也回不到预算内时，remaining 必须如实大于预算（不得虚报）",
            plan.remainingBytes > 200L * 1024 * 1024
        )
    }

    /** 预算必须是显式正数：传 0/负数属于调用方错误，直接抛。 */
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveBudget() {
        planMediaCacheEvictions(listOf(entry("/a", 1, 0)), budgetBytes = 0)
    }
}
