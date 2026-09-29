package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「恢复时按备份覆盖书架」的**守卫契约**（源码级静态断言）。
 *
 * 为什么是静态断言：`Restore`/`BackupConfig` 触碰 `appCtx`（Android），
 * 无法在 JVM 单测里实例化。这些用例不能证明运行正确，但能挡住把守卫改掉的具体回归。
 *
 * ⚠️ 这里锁的每一条都对应一个**会删光书架 / 误删用户书**的事故路径，
 * 删掉对应守卫会让用例失败（已双向证伪）。
 */
class RestoreOverwriteGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun restoreSource(): String =
        moduleSource("src/main/java/io/legado/app/help/storage/Restore.kt")

    /** 只保留可执行行，避免说明性注释里的关键字被误判。 */
    private fun executableOnly(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    private fun overwriteBlock(): String {
        val source = restoreSource()
        return executableOnly(
            source.substringAfter("private suspend fun overwriteShelfIfNeeded")
                .substringBefore("private fun writeOverwriteManifest")
        )
    }

    /**
     * ⚠️ 守卫①：`bookshelf.json` 不存在时必须拒绝删除。
     *
     * 恢复前「选择恢复项目」会把未勾选项的文件从解压目录删掉，而 `bookshelf.json`
     * 与 `covers` 绑在同一个「书架」可勾选项上。缺这条守卫时「备份在线书集合」= 空集
     * ⇒ `全部 − 空 = 全部` ⇒ **删光在线书架**。
     */
    @Test
    fun overwriteRefusesWhenShelfFileMissing() {
        val body = overwriteBlock()

        assertTrue("未能定位 overwriteShelfIfNeeded 方法体", body.isNotBlank())
        assertTrue(
            "必须显式检查 bookshelf.json 是否存在并拒绝删除",
            body.contains("shelfFile.exists()")
        )
        assertTrue(
            "文件不存在时必须提前 return，不得继续删除",
            Regex("""if\s*\(!shelfFile\.exists\(\)\)\s*\{[^}]*return""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(body)
        )
    }

    /**
     * ⚠️ 守卫②：解析失败（`fileToBookList` 返回 null）时必须拒绝删除。
     * 「读不出来」不等于「备份里没有」——把前者当后者就是引导用户删光书架。
     */
    @Test
    fun overwriteRefusesWhenParseFailed() {
        val body = overwriteBlock()

        assertTrue(
            "解析结果为 null 时必须拒绝删除（禁止 orEmpty() 降级）",
            body.contains("null") && body.contains("return")
        )
        assertTrue(
            "不得用 orEmpty() 把解析失败降级成空列表",
            !body.contains("orEmpty()")
        )
    }

    /**
     * ⚠️ 守卫③：离线书必须被显式排除。
     * 否则本地书进入删除集合会触发 `LocalBook.deletePersistentBookResources()` **物理删文件**。
     */
    @Test
    fun overwriteExcludesOfflineBooks() {
        val body = overwriteBlock()

        assertTrue(
            "删除集合必须经 ShelfIdentity.keyOf 过滤（离线书返回 null 时排除）",
            body.contains("ShelfIdentity.keyOf")
        )
        assertTrue(
            "keyOf 为 null 时必须排除，不得写成 `key !in backupKeys`",
            body.contains("?: return@filter false")
        )
    }

    /**
     * ⚠️ 本机集合必须在**合并之前**取快照。
     * 用合并后的集合会让新插入的备份书以其身份键"顶替"本机旧记录，使本该删掉的记录漏判。
     */
    @Test
    fun localKeysSnapshotTakenBeforeMerge() {
        val source = executableOnly(restoreSource())
        val snapshotAt = source.indexOf("localKeysBeforeMerge = if")
        val mergeAt = source.indexOf("val restoredBookUrls = restoreShelfBooks(path)")

        assertTrue("未找到本机集合快照赋值点", snapshotAt >= 0)
        assertTrue("未找到 restoreShelfBooks 调用点", mergeAt >= 0)
        assertTrue(
            "本机身份键快照必须早于书架合并（否则会漏判本应删除的记录）",
            snapshotAt < mergeAt
        )
    }

    /**
     * ⚠️ 删书必须在 **DB 事务之外、恢复流程末尾**。
     *
     * `RestoreJournal` 的快照目标**不含 `legado.db`**（既有继承缺陷），若删书在
     * `restoreDbData` 事务内提交，之后任一步骤失败触发 `rollbackNow()` 时只还原配置文件，
     * 而书已永久删除 —— 「看起来恢复失败，书其实已经没了」。
     */
    @Test
    fun overwriteDeletionRunsOutsideTransactionAndLast() {
        val source = executableOnly(restoreSource())
        val callAt = source.indexOf("overwriteShelfIfNeeded(path)")
        val toastAt = source.indexOf("appCtx.toastOnUi(R.string.restore_success)")

        assertTrue("未找到 overwriteShelfIfNeeded 调用点", callAt >= 0)
        assertTrue("未找到恢复成功提示点", toastAt >= 0)
        assertTrue(
            "删书必须在恢复流程最末尾（成功提示之前），确保所有可能失败的步骤都已执行完",
            callAt < toastAt
        )

        // 删书调用点必须落在 restoreDbData 方法体之外（= 不在 DB 事务内）。
        // 用方法边界划定，而不是用 withTransaction 的下标区间——后者会随注释位置漂移而假通过。
        val dbDataStart = source.indexOf("private suspend fun restoreDbData")
        val dbDataEnd = source.indexOf("private fun hintDuplicatesAfterRestore")
        assertTrue("未定位 restoreDbData 方法边界", dbDataStart >= 0 && dbDataEnd > dbDataStart)
        assertTrue(
            "删书不得位于 restoreDbData（含其 DB 事务）之内",
            callAt < dbDataStart || callAt > dbDataEnd
        )
        // 反向确认：restoreDbData 内确实调用了 restoreShelfBooks（即删书与合并不在同一方法）
        val dbDataBody = source.substring(dbDataStart, dbDataEnd)
        assertTrue(
            "restoreDbData 内应调用 restoreShelfBooks（用于确认上述边界有效）",
            dbDataBody.contains("restoreShelfBooks(path)")
        )
    }

    /**
     * ⚠️ 「恢复进行中」闸门：自动备份必须避让恢复流程。
     * 恢复把备份解压到 `backupPath`，而 `Backup.backup()` 开头会 `FileUtils.delete(backupPath)`
     * —— 中途备份会删掉正在被读取的备份目录。
     */
    @Test
    fun restoreSetsAndClearsRestoringFlag() {
        val source = executableOnly(restoreSource())

        assertTrue("必须提供 isRestoring 标志供自动备份侧避让", source.contains("isRestoring = true"))
        assertTrue("异常路径也必须复位 isRestoring（否则自动备份永久停摆）", source.contains("finally"))
        assertTrue("复位语句必须存在", source.contains("isRestoring = false"))
    }

    /**
     * ⚠️ 开关默认必须为**关**：开启后恢复会删除本机独有的书，而删书不可回滚
     * （`RestoreJournal` 不含 `legado.db`）。默认开等于把不可逆删除强加给只想增量恢复的用户。
     */
    @Test
    fun overwriteSwitchDefaultsToOff() {
        val xml = moduleSource("src/main/res/xml/pref_config_backup.xml")
        // 取该 SwitchPreference 整个元素（从最近的 "<io.legado" 到 "/>"）
        val keyAt = xml.indexOf("android:key=\"overwriteShelfOnRestore\"")
        assertTrue("pref XML 中必须存在 overwriteShelfOnRestore 开关", keyAt >= 0)
        val elemStart = xml.lastIndexOf("<io.legado", keyAt)
        val elemEnd = xml.indexOf("/>", keyAt)
        val elem = xml.substring(elemStart, elemEnd)

        assertTrue(
            "开关默认值必须为 false（当前元素：$elem）",
            elem.contains("android:defaultValue=\"false\"")
        )
        assertTrue(
            "Accessor 必须按 key 读取",
            moduleSource("src/main/java/io/legado/app/help/storage/BackupConfig.kt")
                .contains("getPrefBoolean(overwriteShelfKey)")
        )
    }
}
