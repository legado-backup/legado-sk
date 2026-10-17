package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 媒体流缓存的**唯一清理入口**（视频 / 书源音频的播放缓存）。
 *
 * 设计前提（平板实测）：播放过程本身就把整段流写进
 * `book_cache/<书>/video_media`，不依赖「下载」。原实现给**每本书**各建一个
 * 4GB LRU 淘汰器，淘汰器只统计自己那一个目录 ⇒ 32 本书 = 32 份 4GB 配额，
 * 谁都不会触发，实测堆到 6.5GB 且只增不减。
 *
 * 两条收敛规则：
 * 1. **退出即清**：离开视频播放页 / 关闭悬浮窗 / 音频朗读服务销毁后延迟
 *    [MEDIA_CACHE_SWEEP_DELAY_MS]，把这本的流缓存目录整体删掉。延迟是为了
 *    「手滑误退、几秒内重进」，期间重新播放会取消清扫，不用重下。
 * 2. **全局预算兜底**：所有书的流缓存合计超 [MEDIA_STREAM_CACHE_BUDGET_BYTES]
 *    时按「最久未被播放」优先删。专治进程被杀、悬浮窗异常等走不到规则 1 的残留。
 *
 * ⚠️ 刻意边界：某本书只要存在完成标记（`*_media_complete/`，即 `cacheMedia`
 * 真实下载过）就**整本跳过**。标记与播放缓存无法从目录层逐资源区分，宁可少删。
 * 若日后「下载」成为常用功能，升级路径是按 chapter.resourceUrl 逐个
 * `Cache.removeResource` 只删未标记资源，而不是删整目录。
 */
object MediaStreamCacheJanitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepJobs = ConcurrentHashMap<String, Job>()
    private val playingFolders = ConcurrentHashMap.newKeySet<String>()
    private val lock = ReentrantLock()

    /** 开始播放：既保护目录不被预算淘汰，也取消可能已排队的清扫。 */
    fun onPlaybackStarted(book: Book) {
        val folder = book.getFolderName()
        playingFolders.add(folder)
        sweepJobs.remove(folder)?.cancel()
    }

    /** 停止播放：延迟清扫这本的流缓存（下载产物由完成标记豁免）。 */
    fun onPlaybackStopped(book: Book, delayMs: Long = MEDIA_CACHE_SWEEP_DELAY_MS) {
        val folder = book.getFolderName()
        playingFolders.remove(folder)
        sweepJobs.remove(folder)?.cancel()
        val job = scope.launch {
            delay(delayMs)
            if (playingFolders.contains(folder)) return@launch
            runCatching { sweepFolder(folder) }
                .onFailure { AppLog.put("媒体缓存清扫失败: ${it.localizedMessage}", it) }
            runCatching { enforceBudget() }
                .onFailure { AppLog.put("媒体缓存预算清理失败: ${it.localizedMessage}", it) }
        }
        sweepJobs[folder] = job
        job.invokeOnCompletion { sweepJobs.remove(folder, job) }
    }

    /** 全局预算兜底；App 启动时也跑一次，回收上次进程遗留的缓存。 */
    fun enforceBudget() {
        if (!lock.tryLock()) return
        try {
            val bookDirs = bookCacheDirs()
            enforceKind(bookDirs, ExoPlayerHelper.VIDEO_BOOK_CACHE_DIR, ExoPlayerHelper.VIDEO_BOOK_COMPLETE_DIR)
            enforceKind(bookDirs, ExoPlayerHelper.AUDIO_BOOK_CACHE_DIR, ExoPlayerHelper.AUDIO_BOOK_COMPLETE_DIR)
        } finally {
            lock.unlock()
        }
    }

    /** 缓存管理页「临时视频/音频缓存」的统计口径：按书的流缓存目录。 */
    fun streamCacheDirs(video: Boolean): List<File> {
        val dirName = if (video) ExoPlayerHelper.VIDEO_BOOK_CACHE_DIR else ExoPlayerHelper.AUDIO_BOOK_CACHE_DIR
        return bookCacheDirs().map { File(it, dirName) }.filter { it.isDirectory }
    }

    /** 缓存管理页「临时视频/音频缓存 → 清除」：删掉所有书的对应流缓存目录。 */
    fun clearStreamCaches(video: Boolean) {
        val playing = playingFolders.toSet()
        streamCacheDirs(video).forEach { dir ->
            val folder = dir.parentFile?.name ?: return@forEach
            if (folder in playing) return@forEach
            ExoPlayerHelper.releaseCacheDirectories(dir)
            FileUtils.delete(dir, deleteRootDir = true)
        }
    }

    private fun bookCacheDirs(): List<File> =
        File(BookHelp.cachePath).listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun sweepFolder(folder: String) {
        if (!lock.tryLock()) return
        try {
            if (playingFolders.contains(folder)) return
            val bookDir = File(BookHelp.cachePath, folder)
            if (!bookDir.isDirectory) return
            val mediaDirs = listOf(
                File(bookDir, ExoPlayerHelper.VIDEO_BOOK_CACHE_DIR),
                File(bookDir, ExoPlayerHelper.AUDIO_BOOK_CACHE_DIR),
            ).filter { it.isDirectory }
            if (mediaDirs.isEmpty()) return
            ExoPlayerHelper.releaseCacheDirectories(*mediaDirs.toTypedArray())
            mediaDirs.forEach { dir ->
                if (!FileUtils.delete(dir, deleteRootDir = true)) {
                    AppLog.put("媒体缓存目录删除失败: ${dir.absolutePath}")
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun enforceKind(bookDirs: List<File>, cacheDirName: String, completeDirName: String) {
        val entries = bookDirs.mapNotNull { bookDir ->
            val cacheDir = File(bookDir, cacheDirName)
            if (!cacheDir.isDirectory) return@mapNotNull null
            MediaCacheEntry(
                path = cacheDir.absolutePath,
                bytes = cacheDir.directoryBytes(),
                lastUsedAt = cacheDir.lastModified(),
                keep = playingFolders.contains(bookDir.name) ||
                    File(bookDir, completeDirName).listFiles()?.isNotEmpty() == true,
            )
        }
        val plan = planMediaCacheEvictions(entries, MEDIA_STREAM_CACHE_BUDGET_BYTES)
        if (plan.deletePaths.isEmpty()) return
        AppLog.put(
            "媒体缓存超预算，回收 ${plan.freedBytes / 1024 / 1024}MB" +
                "（${plan.deletePaths.size} 项，剩余 ${plan.remainingBytes / 1024 / 1024}MB）"
        )
        plan.deletePaths.forEach { path ->
            val dir = File(path)
            ExoPlayerHelper.releaseCacheDirectories(dir)
            FileUtils.delete(dir, deleteRootDir = true)
        }
    }

    private fun File.directoryBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.directoryBytes() } ?: 0L
    }
}
