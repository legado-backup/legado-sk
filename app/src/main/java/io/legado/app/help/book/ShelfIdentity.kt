package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * 书架「在线书身份」的唯一判据。
 *
 * 用途（两处，共用同一份口径）：
 * 1. 自动备份的「书架是否有增删」判定；
 * 2. 恢复时「按备份覆盖」——找出本机比备份多的在线书。
 *
 * ## 为什么必须归一化（trim）
 *
 * ⚠️ **判据必须 trim，不能直接用 [BookMergeRules.identityKeyOf]。**
 * 备份与本机的同名书可能只在首尾空白上不同（跨设备/跨版本 JSON 常见），
 * 不归一会让「备份里确实存在的书」被误判为「本机多余」→ **误删用户有阅读历史的书**。
 *
 * ⚠️ 这与 [BookMergeRules.identityKeyOf]（**刻意不 trim**）的差异是**刻意的**：
 * 后者服务入库收敛（`BookUpsert`/书架合并），改动它会连带改变那些既有语义；
 * 本文件服务「跨来源书单比对」，必须归一化。两者不可互相替代，也不得合并。
 * 本判据原为 `ShelfCleanupRules.keyOf`，10074 起移入本文件并复用，**语义一字未改**。
 *
 * ## 为什么忽略离线书
 *
 * [BookMergeRules.stableMediaType] 对**本地书/归档书/未入架书/无书名**返回 null，
 * 这些书一律不参与比对（`keyOf` 返回 null）。这同时满足产品要求「离线书全程无视」：
 * 它们既不参与自动备份的变动判定，也不会进入恢复的删除集合。
 * ⚠️ 尤其是本地书 —— `Book.delete()` 对 `isLocal` 会调
 * `LocalBook.deletePersistentBookResources()` **物理删文件**，绝不能误入删除集合。
 *
 * ⚠️ 本文件**不碰数据库、不依赖 Android 资源**，可在 JVM 单测里直接覆盖判据语义。
 */
object ShelfIdentity {

    /** 归一化后的在线书身份键。 */
    data class Key(val name: String, val author: String, val mediaType: Int)

    /**
     * 取归一化身份键。
     *
     * @return null 表示这本书**不参与比对**（离线书/未入架/归档/无书名），
     * 调用方必须把它排除在一切集合之外
     */
    fun keyOf(book: Book): Key? {
        // 复用入库侧的稳定媒体判据，保证「同一本书」的口径与书架上一致；
        // 它同时承担了「本地书/归档书/未入架书/无书名不参与」的语义。
        val mediaType = BookMergeRules.stableMediaType(book) ?: return null
        return Key(
            name = book.name.trim(),
            author = book.author.trim(),
            mediaType = mediaType
        )
    }

    /** 一组书里所有在线书的身份键集合（离线书自动被排除）。 */
    fun keysOf(books: List<Book>): Set<Key> {
        return books.mapNotNull { keyOf(it) }.toHashSet()
    }

    /** 只保留参与比对的在线书（离线书被过滤掉）。 */
    fun onLineOnly(books: List<Book>): List<Book> {
        return books.filter { keyOf(it) != null }
    }

    /**
     * 找出「本机有、备份没有」的在线书。
     *
     * @param localBooks 本机书籍（内部会过滤离线书）
     * @param backupBooks 备份中的书籍（内部会过滤离线书）
     * @return 候选列表。**空表示无需处理**
     */
    fun findLocalOnlyBooks(
        localBooks: List<Book>,
        backupBooks: List<Book>
    ): List<Book> {
        val backupKeys = keysOf(backupBooks)
        return localBooks.filter { book ->
            // ⚠️ keyOf 返回 null（离线书/未入架/无书名）时**一律排除**：
            // 写成 `key !in backupKeys` 会让 null 被当成「不在集合内」而入选，
            // 删除本地书会连带清掉本地文件资源（Book.delete -> LocalBook.deletePersistentBookResources）。
            val key = keyOf(book) ?: return@filter false
            key !in backupKeys
        }
    }

    /**
     * 本机不参与比对（离线书等）的书籍数量。
     *
     * 用于向用户如实报告「另有 N 条未参与比对」，避免用户以为功能漏了
     * （AGENTS.md：有问题直接暴露，不静默）。
     */
    fun countExcludedLocalBooks(localBooks: List<Book>): Int {
        return localBooks.count { keyOf(it) == null }
    }
}
