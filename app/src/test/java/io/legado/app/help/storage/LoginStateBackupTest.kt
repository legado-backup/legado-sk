package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「登录状态」备份/恢复的**契约守卫**（源码级静态断言）。
 *
 * 背景：正式版 `io.legado.app.c` 与共存版 `io.legado.app.sk2` 数据互不透传，
 * 靠现有备份 zip 互通时，登录态原本落在两张不属于任何 json 的表里
 * （`cookies` 表 + `caches` 表的登录信息/源变量），换包就必须重新登录每个源。
 *
 * 为什么是静态断言：`Backup`/`Restore`/`BackupItems.all` 都会触碰 `appCtx`（Android），
 * JVM 单测无法求值（`ClassNotFoundException: android.app.ActivityThread`）。
 *
 * ⚠️ 这些用例锁的是**成对关系**：只加写入不加恢复（或只加恢复不加写入）、
 * 或把某一边的文件名换成字面量导致两边漂移，都必须失败。
 */
class LoginStateBackupTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun backupSource(): String =
        moduleSource("src/main/java/io/legado/app/help/storage/Backup.kt")

    private fun restoreSource(): String =
        moduleSource("src/main/java/io/legado/app/help/storage/Restore.kt")

    private fun configSource(): String =
        moduleSource("src/main/java/io/legado/app/help/storage/BackupConfig.kt")

    private fun cacheDaoSource(): String =
        moduleSource("src/main/java/io/legado/app/data/dao/CacheDao.kt")

    /** 只保留可执行行，避免说明性注释里的关键字被误判（同 RestoreOverwriteGuardTest）。 */
    private fun executableOnly(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    /**
     * 清单里必须有 `loginState`，且它声明的两个文件正是备份/恢复共用的常量。
     *
     * 少了这一项：用户即使备份了这两个文件，恢复侧按「恢复忽略列表」过滤时也会
     * 把它们当作无主文件丢弃；同时「默认备份内容」里也没有可取消勾选的开关。
     */
    @Test
    fun backupItemPackagesLoginStateFiles() {
        val items = configSource()
            .substringAfter("internal object BackupItems")
            .substringBefore("object BackupTargetConfig")
        assertTrue("未定位到 BackupItems 主体", items.isNotBlank())

        assertTrue("清单必须声明 cookie.json 常量", items.contains("loginCookieFileName = \"cookie.json\""))
        assertTrue("清单必须声明 loginCache.json 常量", items.contains("loginCacheFileName = \"loginCache.json\""))
        assertTrue("清单必须有 loginState 项目", items.contains("\"loginState\""))
        assertTrue(
            "loginState 必须打包两个登录态文件",
            items.substringAfter("\"loginState\"").substringBefore(")").let {
                it.contains("loginCookieFileName") && it.contains("loginCacheFileName")
            }
        )
    }

    /**
     * ⚠️ 每条登录态 json 都要**写两次到代码里**：一次落盘，一次进入 `backupFileNames`。
     * 只写盘不进清单 → `existingZipSources` 过滤时被丢掉，文件永远不进 zip（静默失效）。
     */
    @Test
    fun backupWritesBothLoginStateFiles() {
        val source = executableOnly(backupSource())

        assertTrue(
            "cookie 表必须备份为 loginCookieFileName",
            source.contains("writeListToJson(appDb.cookieDao.all(), BackupItems.loginCookieFileName")
        )
        assertTrue(
            "cache 表登录态必须备份为 loginCacheFileName",
            source.contains("writeListToJson(appDb.cacheDao.sourceState(), BackupItems.loginCacheFileName")
        )
        listOf("loginCookieFileName", "loginCacheFileName").forEach { name ->
            val count = Regex(Regex.escape("BackupItems.$name")).findAll(source).count()
            assertTrue("$name 必须在 Backup.kt 出现两次（落盘 + backupFileNames），实际 $count", count >= 2)
        }
    }

    /** cache 侧的取数口径必须覆盖全部五类登录/变量前缀，漏一类就等于丢一类登录态。 */
    @Test
    fun cacheQueryCoversEveryLoginKeyPrefix() {
        val source = cacheDaoSource()
        assertTrue("未定位到 sourceState 声明", source.contains("fun sourceState()"))

        // 查询文本在 `fun sourceState()` 之前，且是它前面最近的一个 @Query
        val query = source.substringBefore("fun sourceState()").substringAfterLast("@Query")

        listOf("v", "userInfo", "loginHeader", "sourceVariable", "infoMap").forEach { prefix ->
            assertTrue("cache 登录态查询必须包含 $prefix 前缀", query.contains("'$prefix"))
        }
    }

    /**
     * 恢复端必须真的消费这两个文件，且反序列化类型与写入类型一致
     * （`Cookie` 对应 cookies 表、`Cache` 对应 caches 表）。
     */
    @Test
    fun restoreConsumesBothLoginStateFiles() {
        val source = executableOnly(restoreSource())

        assertTrue(
            "必须恢复 cookie.json",
            source.contains("fileToListT<Cookie>(path, BackupItems.loginCookieFileName)")
        )
        assertTrue(
            "cookie 恢复后必须写入 cookies 表",
            source.substringAfter("fileToListT<Cookie>(").substringBefore("fileToListT<Cache>(")
                .contains("appDb.cookieDao.insert(")
        )
        assertTrue(
            "必须恢复 loginCache.json",
            source.contains("fileToListT<Cache>(path, BackupItems.loginCacheFileName)")
        )
        assertTrue(
            "cache 恢复后必须写入 caches 表",
            source.substringAfter("fileToListT<Cache>(").substringBefore("private fun")
                .contains("appDb.cacheDao.insert(")
        )
    }
}
