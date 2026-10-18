package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.cache.CacheTtsWorkerRegistry
import io.legado.app.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TTS 音频缓存的**唯一清扫入口**（规则见 [planTtsCacheSweep]）。
 *
 * 触发点只有两个：朗读服务销毁后延迟清扫本书、App 启动时清扫所有书（回收进程
 * 被杀留下的残留）。两者都不删批量预缓存章。
 *
 * 批量预缓存标记放在 `tts_cache_complete/<章节stem>/`（与 `tts_cache/` **同级**），
 * 与 10100 的 `*_media_complete/` 同构。刻意不放进章节目录内：章节目录会被
 * 导出流程整目录扫描，标记文件会作为「未映射缓存」污染导出报告。
 *
 * ⚠️ 刻意边界：清扫以**章节目录**为粒度整删，不做单元级 LRU。章节是这套缓存唯一
 * 的自然身份（[TtsCacheStore.chapterStem] 与正文缓存同源），单元级淘汰会让
 * 「同一章内一半命中一半重合成」，收益低而状态更碎。
 */
object TtsCacheJanitor {

    /** 批量预缓存完成标记的目录名（与 tts_cache 同级）。 */
    private const val BATCH_DIR_NAME = "tts_cache_complete"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepJobs = ConcurrentHashMap<String, Job>()

    private fun batchDir(book: Book): File =
        File(TtsCacheStore.ttsCacheDir(book).parentFile, BATCH_DIR_NAME)

    /** 标记某章为「用户主动预缓存完成」：该章此后不被退出清扫删除。 */
    fun markBatchCached(book: Book, chapter: BookChapter) {
        val dir = File(batchDir(book), TtsCacheStore.chapterStem(chapter))
        runCatching { dir.mkdirs() }
            .onFailure { AppLog.put("TTS 缓存标记写入失败: ${it.localizedMessage}", it) }
    }

    /** 删掉某章的批量预缓存标记（按章删缓存时保持标记与产物一致）。 */
    fun clearBatchMark(book: Book, chapter: BookChapter) {
        val dir = File(batchDir(book), TtsCacheStore.chapterStem(chapter))
        if (dir.exists() && !FileUtils.delete(dir, deleteRootDir = true)) {
            AppLog.put("TTS 缓存标记删除失败: ${dir.absolutePath}")
        }
    }

    /**
     * 朗读停止：延迟清扫本书，只保留 [keepChapterStems]。
     * 延迟窗口内重新开始朗读会取消清扫，不用重合成。
     */
    fun onAloudStopped(book: Book, keepChapterStems: Set<String>) {
        val folder = book.getFolderName()
        sweepJobs.remove(folder)?.cancel()
        val job = scope.launch {
            delay(TTS_CACHE_SWEEP_DELAY_MS)
            runCatching { sweep(book, keepChapterStems) }
                .onFailure { AppLog.put("TTS 缓存清扫失败: ${it.localizedMessage}", it) }
        }
        sweepJobs[folder] = job
        job.invokeOnCompletion { sweepJobs.remove(folder, job) }
    }

    /** 重新开始朗读：取消已排队的清扫。 */
    fun cancelPending(book: Book) {
        sweepJobs.remove(book.getFolderName())?.cancel()
    }

    /**
     * App 启动兜底：清扫所有书，每本保留**它自己持久化的当前章**
     * （`books.durChapterIndex`）。
     *
     * ⚠️ 不能用 `ReadBook.book` 判断当前章：那是内存态，冷启动时必然为 null，
     * 保留集会退化成空集（见 `emptyKeepDeletesAllUnmarked`），把「上次听书的
     * 那一章」也当过期缓存删掉。进度持久化在书表里，只能从那里取。
     */
    fun enforceOnStartup() {
        scope.launch {
            runCatching {
                appDb.bookDao.all.forEach { book ->
                    if (!TtsCacheStore.ttsCacheDir(book).isDirectory) return@forEach
                    val keepStem = appDb.bookChapterDao
                        .getChapter(book.bookUrl, book.durChapterIndex)
                        ?.let(TtsCacheStore::chapterStem)
                    sweep(book, listOfNotNull(keepStem).toSet())
                }
            }.onFailure { AppLog.put("TTS 缓存启动清扫失败: ${it.localizedMessage}", it) }
        }
    }

    /**
     * 清扫一本书的 tts_cache：删掉除 [keepChapterStems] 与批量预缓存章之外的
     * 所有章节目录。该书正在批量合成时整体跳过，避免删掉在用的产物。
     */
    fun sweep(book: Book, keepChapterStems: Set<String>) {
        if (CacheTtsWorkerRegistry.isBookActive(book.bookUrl)) return
        val root = TtsCacheStore.ttsCacheDir(book)
        val chapterDirs = root.listFiles()?.filter { it.isDirectory } ?: return
        val batchRoot = batchDir(book)
        val entries = chapterDirs.map { dir ->
            TtsChapterCacheEntry(
                stem = dir.name,
                bytes = dir.directoryBytes(),
                batch = File(batchRoot, dir.name).isDirectory,
            )
        }
        val plan = planTtsCacheSweep(entries, keepChapterStems)
        if (plan.deleteStems.isEmpty()) return
        plan.deleteStems.forEach { stem ->
            val dir = File(root, stem)
            if (!FileUtils.delete(dir, deleteRootDir = true)) {
                AppLog.put("TTS 缓存章节目录删除失败: ${dir.absolutePath}")
            }
        }
        // 小于 1MB 时用 MB 整数截断会显示「回收 0MB」，把真实回收量藏掉
        // （排查时被这行误导过）。按量级选单位。
        val freedText = if (plan.freedBytes >= 1024L * 1024L) {
            "${plan.freedBytes / (1024L * 1024L)}MB"
        } else {
            "${plan.freedBytes / 1024L}KB"
        }
        AppLog.put(
            "TTS 缓存清扫 ${book.name}：删除 ${plan.deleteStems.size} 章" +
                "，回收 $freedText"
        )
    }

    private fun File.directoryBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.directoryBytes() } ?: 0L
    }
}
