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
     * 采用的接缝是 `Book.save()` / `Book.delete()` / `BookShortcutHelp.delete()`
     * （领域方法，恢复流程**不经过**它们），而不是 DAO 或 UI 入口。
     */
    @Test
    fun triggerIsOnDomainMethodsNotDao() {
        val book = moduleSource("src/main/java/io/legado/app/data/entities/Book.kt")
        val shortcut = moduleSource("src/main/java/io/legado/app/help/book/BookShortcutHelp.kt")

        // 加入书架的唯一收口
        assertTrue(
            "Book.save() 必须触发书架变动检查（覆盖全部加架路径）",
            book.contains("Backup.autoBackupOnShelfChangeIfNeeded(appCtx)")
        )
        // 删除漏斗
        assertTrue(
            "BookShortcutHelp.delete() 必须触发书架变动检查（覆盖全部删除路径）",
            shortcut.contains("Backup.autoBackupOnShelfChangeIfNeeded(appCtx)")
        )
        // Backup 自身不得注册 DAO 观察者
        assertTrue(
            "Backup 侧不得注册 DAO 层拦截（无 InvalidationTracker 观察者）",
            !backupSource().contains("InvalidationTracker")
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
