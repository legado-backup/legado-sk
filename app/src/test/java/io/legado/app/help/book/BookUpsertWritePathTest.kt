package io.legado.app.help.book

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书籍写入路径的存在性分流契约（源码级静态断言）。
 *
 * 背景：`BookDao.insert` 是 `OnConflictStrategy.REPLACE`。SQLite 的 INSERT OR REPLACE
 * 命中主键冲突时 = DELETE 旧行 + INSERT 新行，且本应用 FK 开启（Room 生成
 * `PRAGMA foreign_keys = ON`），隐式 DELETE 会触发 chapters / book_shortcuts /
 * book_collection_items / book_illustrations 四张子表的 `onDelete = CASCADE`——
 * 对**已存在 bookUrl** 的书裸 insert 等于级联清空章节、快捷入口、合集成员与配图。
 *
 * 修复方式 = 与 `Book.save()` 同构的存在性分流（has → update / else insert）。
 * BookUpsert 依赖 appDb（Room 顶层对象）无法在 JVM 单测中实例化，故退化为源码静态断言：
 * 它不能证明运行正确，但能挡住"把分流改回裸 insert"这个具体回归。
 */
class BookUpsertWritePathTest {

    private fun moduleSource(relativePath: String): File {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f
    }

    @Test
    fun savePlainMustBranchOnExistenceBeforeWriting() {
        val body = moduleSource("src/main/java/io/legado/app/help/book/BookUpsert.kt")
            .readText()
            .substringAfter("private fun savePlain")
            .substringBefore("private fun moveShortcuts")

        assertTrue("未能定位 savePlain 方法体", body.isNotBlank())
        assertTrue(
            "savePlain 必须先 bookDao.has(bookUrl) 判存在再写：裸 INSERT(REPLACE) 会触发子表 CASCADE",
            body.contains("appDb.bookDao.has(target.bookUrl)")
        )
        assertTrue(
            "已存在路径必须走 bookDao.update(target)（@Update 不删行、不触发 CASCADE）",
            body.contains("appDb.bookDao.update(target)")
        )
        val insertIdx = body.indexOf("appDb.bookDao.insert(target)")
        val hasIdx = body.indexOf("appDb.bookDao.has(target.bookUrl)")
        // 从 has(...) 之后找 else：savePlain 前段的 val target = if...else 不算
        val elseIdx = body.indexOf("else {", hasIdx)
        val updateIdx = body.indexOf("appDb.bookDao.update(target)")
        assertTrue(
            "insert(target) 必须位于 has(...) 之后的 else 分支内",
            hasIdx in 0 until updateIdx && updateIdx < elseIdx && elseIdx < insertIdx
        )
    }

    @Test
    fun loadChapterMustUseUpdateWhenBookUrlUnchanged() {
        val body = moduleSource(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        ).readText()
            .substringAfter("val oldBook = book.copy()")
            .substringBefore("}.onError {")

        assertTrue("未能定位 loadChapter 拉目录成功分支", body.isNotBlank())
        val updateIdx = body.indexOf("appDb.bookDao.update(book)")
        val replaceIdx = body.indexOf("appDb.bookDao.replace(oldBook, book)")
        val branchIdx = body.indexOf("oldBook.bookUrl == book.bookUrl")
        assertTrue(
            "同 bookUrl 刷新目录必须走 update：无条件 replace 会级联清空快捷入口/合集/配图",
            branchIdx in 0 until updateIdx && updateIdx < replaceIdx
        )
    }

    /**
     * 书架重复书籍（同名同作者、不同书源）的结构性根因：
     * `upsertByIdentity` 的「查同书候选 → 选 keep → 写入」全文都在事务外、无任何互斥，
     * 并发调用（换源与加入书架几乎同时触发）会各自查到「库中没有同书」而各自 insert。
     *
     * 修复方式 = 把整段放进 `writeLock`（可重入，因为 upsert 内部还会调 merge）。
     * 锁的互斥性由 JDK 保证，这里只能静态锁住「候选查询与写入都被包在同一把锁内」这个接线，
     * 因此断言锚定**函数体委派**而非行号：入口函数只允许出现 `writeLock.withLock` + 委派。
     */
    @Test
    fun upsertByIdentityMustSerializeSelectAndWrite() {
        val source = moduleSource("src/main/java/io/legado/app/help/book/BookUpsert.kt").readText()
        assertTrue(
            "必须只有一把互斥锁（ReentrantLock 实例），否则两条写路径互不排除",
            Regex("""ReentrantLock\(\)""").findAll(source).count() == 1
        )
        val entry = source
            .substringAfter("fun upsertByIdentity(")
            .substringBefore("private fun upsertLocked(")
        assertTrue("未能定位 upsertByIdentity 入口", entry.isNotBlank())
        assertTrue(
            "upsertByIdentity 必须先进 writeLock 再委派 upsertLocked",
            entry.contains("writeLock.withLock")
        )
        assertTrue(
            "入口函数体内不得出现候选查询：SELECT 必须在锁内（与写入同一临界区）",
            !entry.contains("getBooks(") && !entry.contains("savePlain(")
        )
        val locked = source
            .substringAfter("private fun upsertLocked(")
            .substringBefore("internal fun merge(")
        assertTrue(
            "upsertLocked 必须包含候选查询 .getBooks(（锁内完成查+写）",
            locked.contains(".getBooks(")
        )
    }

    @Test
    fun mergeMustUseTheSameLock() {
        val source = moduleSource("src/main/java/io/legado/app/help/book/BookUpsert.kt").readText()
        val entry = source
            .substringAfter("internal fun merge(")
            .substringBefore("private fun mergeLocked(")
        assertTrue("未能定位 merge 入口", entry.isNotBlank())
        assertTrue(
            "merge 可被外部（手动合并重复书籍）直接调用，必须拿同一把 writeLock",
            entry.contains("writeLock.withLock") && !entry.contains("runInTransaction")
        )
        assertTrue(
            "mergeLocked 必须包含事务写入",
            source.substringAfter("private fun mergeLocked(").contains("appDb.runInTransaction {")
        )
    }

    /**
     * `Book.save()` 的 insert 分支曾经裸 `insert`：换源/合并删掉旧记录后，
     * 拿陈旧 bookUrl 调 `save()` 会把已并掉的记录“复活”成第二条书架条目。
     */
    @Test
    fun saveMustConvergeByIdentityInsteadOfBareInsert() {
        val body = moduleSource("src/main/java/io/legado/app/data/entities/Book.kt")
            .readText()
            .substringAfter("fun save() {")
            .substringBefore("fun delete() {")
        assertTrue("未能定位 Book.save() 方法体", body.isNotBlank())
        assertTrue(
            "save() 的存在性分支必须保留：已存在走 update（@Update 不删行、不触发 CASCADE）",
            body.contains("appDb.bookDao.has(bookUrl)") && body.contains("appDb.bookDao.update(this)")
        )
        assertTrue(
            "save() 的「不存在」分支必须走 BookUpsert.upsertByIdentity(this)",
            body.contains("BookUpsert.upsertByIdentity(this)")
        )
        assertTrue(
            "save() 不得再裸 insert：并发/陈旧调用会复活已被合并删除的记录",
            !body.contains("appDb.bookDao.insert(")
        )
    }
}
