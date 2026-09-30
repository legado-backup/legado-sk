package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「书架变动时自动备份」的**守卫契约**（源码级静态断言）。
 *
 * `Backup` 触碰 `appCtx`/`appDb`，无法在 JVM 单测里跑运行期结果，
 * 故退化为源码断言：不能证明运行正确，但能挡住把守卫改掉的具体回归。
 *
 * 判据本身的语义（身份集合、trim、离线书排除）由 [io.legado.app.help.book.ShelfIdentityTest]
 * 在真正可运行的 JVM 层覆盖，这里只锁结构与接线。
 */
class AutoBackupOnShelfChangeGuardTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun executableOnly(text: String): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    private fun backupSource(): String =
        executableOnly(moduleSource("src/main/java/io/legado/app/help/storage/Backup.kt"))

    private fun autoBackupBlock(): String {
        val source = backupSource()
        return source.substringAfter("fun autoBackupOnShelfChangeIfNeeded")
            .substringBefore("internal fun encodeKeys")
    }

    /**
     * ⚠️ 恢复流程进行中必须跳过自动备份。
     *
     * 恢复把备份解压到 `Backup.backupPath`，而 `backup()` 开头会 `FileUtils.delete(backupPath)`
     * —— 中途备份会把**正在被读取**的备份目录删掉，恢复后续步骤读到空/半截内容。
     * `Backup.mutex` 与 `Restore.mutex` 是两把独立私有锁，恢复期间 `Backup` 侧零阻塞，
     * 因此不能靠锁互斥，必须显式避让。
     */
    @Test
    fun autoBackupSuspendedWhileRestoring() {
        val body = autoBackupBlock()

        assertTrue("未能定位 autoBackupOnShelfChangeIfNeeded 方法体", body.isNotBlank())
        assertTrue(
            "触发前必须检查 Restore.isRestoring 并跳过",
            body.contains("Restore.isRestoring")
        )
        // 去抖后必须复查（等待期间可能又进了恢复）
        val occurrences = Regex("Restore\\.isRestoring").findAll(body).count()
        assertTrue(
            "去抖等待之后必须复查一次 isRestoring（实际出现 $occurrences 次）",
            occurrences >= 2
        )
    }

    /**
     * ⚠️ 必须绕开 `autoBack` 的「一天一次」闸门，且不得复用 `autoBack`。
     * 复用会让 `backup()` 无条件推高 `lastBackup`，反过来压制既有的一天一次周期。
     */
    @Test
    fun autoBackupBypassesDailyGate() {
        val body = autoBackupBlock()

        assertTrue(
            "不得调用 shouldBackup()（那是 autoBack 的一天一次闸门）",
            !body.contains("shouldBackup()")
        )
        assertTrue(
            "不得调用 autoBack()（会连带推高 lastBackup 并压制既有周期）",
            !body.contains("autoBack(")
        )
    }

    /**
     * ⚠️ 本地与 WebDAV **都没配**时必须跳过并记日志，不得抛错、不得弹 UI。
     */
    @Test
    fun skipsOnlyWhenNoTargetAtAll() {
        val body = autoBackupBlock()

        assertTrue(
            "必须在无任何可用目标时记日志（拒绝静默）",
            body.contains("AppLog.put")
        )
        assertTrue(
            "跳过时不得弹提示（自动行为不该打断用户）",
            body.substringBefore("backup(context,").let { !it.contains("toastOnUiBrief(") }
        )
    }

    /**
     * ⚠️ 记账（`lastShelfKeys`）必须在**备份成功之后**写。
     * 写在开头的话，备份中途被取消/进程被杀会留下「标记已更新但备份没做成」，
     * 该次书架变动此后**永不补备份**。
     */
    @Test
    fun shelfKeysRecordedOnlyAfterBackupSucceeds() {
        val body = autoBackupBlock()
        val backupCallAt = body.indexOf("backup(context, localPath")
        val recordAt = body.indexOf("LocalConfig.lastShelfKeys =")

        assertTrue("未找到 backup 调用点", backupCallAt >= 0)
        assertTrue("未找到 lastShelfKeys 记账点", recordAt >= 0)
        assertTrue(
            "记账必须晚于备份调用（否则失败的备份会把变动标记为已处理）",
            backupCallAt < recordAt
        )
    }

    /** 开关默认必须是关（不替用户默认打开联网自动备份）。 */
    @Test
    fun autoBackupSwitchDefaultsToOff() {
        val xml = moduleSource("src/main/res/xml/pref_config_backup.xml")
        val keyAt = xml.indexOf("android:key=\"autoBackupOnShelfChange\"")
        assertTrue("pref XML 中必须存在 autoBackupOnShelfChange 开关", keyAt >= 0)
        val elemStart = xml.lastIndexOf("<io.legado", keyAt)
        val elemEnd = xml.indexOf("/>", keyAt)
        val elem = xml.substring(elemStart, elemEnd)

        assertTrue(
            "开关默认值必须为 false（当前元素：$elem）",
            elem.contains("android:defaultValue=\"false\"")
        )
        assertTrue(
            "AppConfig 读取默认值必须为 false（与 XML 一致）",
            moduleSource("src/main/java/io/legado/app/help/config/AppConfig.kt")
                .contains("PreferKey.autoBackupOnShelfChange, false")
        )
    }

    /**
     * ⚠️ `lastShelfKeys` 必须放在 `LocalConfig`（独立 `"local"` pref 文件）。
     *
     * 放进 `AppConfig` 会被 `Backup` 全量写进 `config.xml` 并在恢复时带回本机，
     * 把本机判据基线"校准"成另一台设备的状态，使增删判定静默失准。
     */
    @Test
    fun shelfKeysStoredInLocalPrefsNotBackedUp() {
        val localConfig = moduleSource("src/main/java/io/legado/app/help/config/LocalConfig.kt")

        assertTrue(
            "lastShelfKeys 必须定义在 LocalConfig 中",
            localConfig.contains("var lastShelfKeys")
        )
        assertTrue(
            "LocalConfig 必须绑定到独立的 local pref 文件",
            localConfig.contains("getSharedPreferences(\"local\"")
        )
        assertTrue(
            "不得把 lastShelfKeys 放进 AppConfig（会被备份带走）",
            !moduleSource("src/main/java/io/legado/app/help/config/AppConfig.kt")
                .contains("lastShelfKeys")
        )
    }

    /**
     * ⚠️ 触发点必须是**显式声明的领域方法**，不得挂在 `bookDao` 层。
     *
     * 全库 200+ 处 `bookDao` 写点绝大多数只改进度/目录/分组，且 `Restore` 也**直接**写
     * `bookDao` —— 在 DAO 层拦截会把恢复流程自身卷进来（见 autoBackupSuspendedWhileRestoring）。
     *
     * 采用的接缝是 `Book.save()` / `Book.delete()` / `BookShortcutHelp.delete()` /
     * `BookUpsert.savePlain()` / `BookUpsert.merge()`，而不是 DAO 或 UI 入口。
     *
     * ⚠️ **10076 教训**：10075 的版本只断言了 `Book.kt` 里**存在**那行字符串，
     * 却漏了 `BookUpsert` 这条收口，于是「加入书架」主力路径全部漏触发而测试全绿。
     * 现在加架侧的断言改由 [shelfWriteFunnelsAllTriggerBackup] 覆盖 `BookUpsert`。
     */
    @Test
    fun triggerIsOnDomainMethodsNotDao() {
        val book = executableOnly(moduleSource("src/main/java/io/legado/app/data/entities/Book.kt"))
        val shortcut = executableOnly(
            moduleSource("src/main/java/io/legado/app/help/book/BookShortcutHelp.kt")
        )
        val call = "Backup.autoBackupOnShelfChangeIfNeeded(appCtx)"

        // ⚠️ 断言必须落在**函数体**上：整个文件里"有没有字符串"无法区分 save/delete，
        // 10075 的写法正是因此让缺陷漏网（`save()` 有、`delete()` 没有也算绿）。
        val deleteBody = book.substringAfter("fun delete()").substringBefore("deleteWithoutShelfBackup")
        assertTrue("未能定位 Book.delete() 函数体", deleteBody.isNotBlank())
        assertTrue(
            "Book.delete() 必须触发书架变动检查（覆盖删除路径）",
            deleteBody.contains(call)
        )

        val shortcutDeleteBody = shortcut
            .substringAfter("fun delete(")
            .substringBefore("fun update(")
        assertTrue("未能定位 BookShortcutHelp.delete() 函数体", shortcutDeleteBody.isNotBlank())
        assertTrue(
            "BookShortcutHelp.delete() 必须触发书架变动检查（覆盖删除路径）",
            shortcutDeleteBody.contains(call)
        )
        // Backup 自身不得注册 DAO 观察者
        assertTrue(
            "Backup 侧不得注册 DAO 层拦截（无 InvalidationTracker 观察者）",
            !backupSource().contains("InvalidationTracker")
        )
    }

    /**
     * ⚠️ **加架侧的每个写库出口都必须触发**（10076 新增，本缺陷的回归锁）。
     *
     * `BookUpsert` 是「按身份入库」的收口，主流加架路径（搜索页 / 详情页「加入书架」）
     * 全走它，而它**直接写 `bookDao`、不经过 `Book.save()`**。10075 只接了 `Book.save()`，
     * 导致加书静默不备份（实机报上来的现象）。
     *
     * ⚠️ 断言必须落在**两个写库函数的函数体**上，而不是整个文件里"有没有字符串"——
     * 后者正是 10075 漏掉本缺陷的原因：只要文件里任意一处有，测试就绿。
     * `savePlain` 有 insert/update/删 stray 三个出口，`merge` 有 update/删 src 等出口，
     * 故两个函数都必须各自带触发行。
     */
    @Test
    fun shelfWriteFunnelsAllTriggerBackup() {
        val upsert = executableOnly(
            moduleSource("src/main/java/io/legado/app/help/book/BookUpsert.kt")
        )
        val call = "Backup.autoBackupOnShelfChangeIfNeeded(appCtx)"

        // savePlain：加架主路径（`:182` 的裸 insert 就在其中）
        val savePlainBody = upsert
            .substringAfter("private fun savePlain(")
            .substringBefore("private fun moveShortcuts(")
        assertTrue("未能定位 savePlain 函数体", savePlainBody.isNotBlank())
        assertTrue(
            "BookUpsert.savePlain() 必须触发书架变动检查（否则新书入库不备份）",
            savePlainBody.contains(call)
        )

        // merge：换源并入既有记录时会删掉 src，是真实的书架减少
        val mergeBody = upsert
            .substringAfter("internal fun merge(")
            .substringBefore("private fun savePlain(")
        assertTrue("未能定位 merge 函数体", mergeBody.isNotBlank())
        assertTrue(
            "BookUpsert.merge() 必须触发书架变动检查（合并会删 src）",
            mergeBody.contains(call)
        )

        // ⚠️ 触发必须在事务**之后**：事务内失败会回滚，提前触发会备份出一个并未发生的新书架。
        val savePlainTxEnd = savePlainBody.indexOf("appDb.runInTransaction {")
        if (savePlainTxEnd >= 0) {
            val callAt = savePlainBody.indexOf(call, savePlainTxEnd)
            val bodyEnd = savePlainBody.lastIndexOf("return target")
            assertTrue("savePlain 的触发应位于事务块之后、return 之前", callAt in savePlainTxEnd..<bodyEnd)
        }
    }

    /**
     * ⚠️ **恢复流程删书必须走结构性隔离**，不得依赖 `Restore.isRestoring` 这一个运行时布尔。
     *
     * `Restore.overwriteShelfIfNeeded` 会调 `Book.delete()` —— 那是会触发备份的方法。
     * 10075 只靠 `Backup` 侧读 `Restore.isRestoring` 挡住，属隐式约定；
     * 一旦有人改动 `restoreLocked` 的 `isRestoring` 作用域，恢复就会在解压中途触发备份，
     * 把**正在被读取**的备份目录删掉。10076 起改为显式调用不含触发的删除。
     */
    @Test
    fun restoreDeletesWithoutTriggeringBackup() {
        val restore = executableOnly(moduleSource("src/main/java/io/legado/app/help/storage/Restore.kt"))

        assertTrue(
            "Restore 删书必须走 deleteWithoutShelfBackup()（结构性隔离）",
            restore.contains("deleteWithoutShelfBackup()")
        )
        assertTrue(
            "Restore 不得直接调用会触发备份的 Book.delete()",
            !Regex("\\?\\.delete\\(\\)").containsMatchIn(restore)
        )

        val book = executableOnly(moduleSource("src/main/java/io/legado/app/data/entities/Book.kt"))
        assertTrue(
            "Book 必须提供 deleteWithoutShelfBackup()（供恢复流程使用）",
            book.contains("fun deleteWithoutShelfBackup()")
        )
    }

    /**
     * ⚠️ **拿锁后必须复查 `lastShelfKeys`**，否则并发触发会重复备份一次。
     *
     * `pendingShelfChangeJob` 是普通 var（无 volatile / 无锁），并发调用时后写覆盖前者，
     * 两个 job 都可能排队进入 `withLock`；先到的备份完并记账后，后到的若不复查就会
     * **再备份一遍同样的书架**（不损坏数据，但白白多传一次）。
     */
    @Test
    fun rechecksShelfKeysAfterAcquiringLock() {
        val body = autoBackupBlock()
        val lockAt = body.indexOf("withLock {")
        assertTrue("未能定位 withLock 块", lockAt >= 0)
        val afterLock = body.substring(lockAt)

        // ⚠️ 断言不能用「块内出现过 lastShelfKeys」——记账那行 `LocalConfig.lastShelfKeys = …`
        // 也会命中，等于没断言（实测：摘掉复查块后该写法仍然全绿）。
        // 必须锚定**复查语句本身**：把 freshly-read 的 `verified` 与已记账值比较，并在
        // `backup(` 之前 return —— 这样才真的挡住「后到的 job 重复备份一遍」。
        val recheckAt = afterLock.indexOf("if (encodeKeys(verified) == LocalConfig.lastShelfKeys)")
        assertTrue(
            "withLock 内必须把**本次重新读取的** verified 与 lastShelfKeys 比较（防并发重复备份）",
            recheckAt >= 0
        )
        val backupAt = afterLock.indexOf("backup(context,")
        assertTrue("未找到 backup 调用点", backupAt >= 0)
        assertTrue(
            "复查必须发生在 backup 调用**之前**（否则拦不住重复备份）",
            recheckAt < backupAt
        )
        assertTrue(
            "复查命中时必须提前 return（不得落到 backup）",
            afterLock.substring(recheckAt, backupAt).contains("return@withLock")
        )
    }

    /**
     * ⚠️ **不得**把「备份路径为空」当作「没配置备份」而直接跳过。
     *
     * `AppConfig.backupPath` 只是**本地/SAF 目录**，与 WebDAV 完全无关
     * （WebDAV 由 `backup()` 内部的 `AppWebDav.backUpWebDav()` 独立完成）。
     * 早期实现写成 `backupPath.isNullOrBlank() → return`，导致「只配了 WebDAV」的用户
     * （最常见的用法）自动备份**永远静默不执行** —— 这正是实机报上来的现象。
     */
    @Test
    fun doesNotTreatMissingLocalPathAsUnconfigured() {
        val body = autoBackupBlock()
        val webDavCheckAt = body.indexOf("AppWebDav.isOk")
        assertTrue("必须把 WebDAV 纳入「是否有处可写」的判据", webDavCheckAt >= 0)

        // ⚠️ 关键不变式：**在决定"哪里有处可写"的那一小段里**，本地路径为空时
        // 不得提前 return —— 那会让只配 WebDAV 的用户永不自动备份（实机报来的现象）。
        // 观测窗口必须从本段起算：方法开头的 `Restore.isRestoring` 等意图级守卫
        // 本来就在更前面且合法地含 return，不能把它们算进来。
        val blockStart = body.lastIndexOf("val localPath", webDavCheckAt)
        assertTrue("应存在 localPath 取值的语句", blockStart >= 0)
        val decisionBlock = body.substring(blockStart, webDavCheckAt)
        assertTrue(
            "本地路径为空时不得在 WebDAV 判据之前提前退出（当前决策段：$decisionBlock）",
            !decisionBlock.contains("return")
        )
    }

    /** ⚠️ 备份成功后必须有**极短**提示（作者要求 0.5 秒一闪而过）。 */
    @Test
    fun showsBriefToastAfterSuccessfulAutoBackup() {
        val body = autoBackupBlock()

        assertTrue(
            "备份成功后必须提示",
            body.contains("toastOnUiBrief(")
        )
        // 提示必须晚于记账/备份，不能在建任务时就弹（否则失败也提示成功）
        val toastAt = body.indexOf("toastOnUiBrief(")
        val backupAt = body.indexOf("backup(context,")
        assertTrue("提示必须晚于备份调用", backupAt in 0..<toastAt)
    }
}
