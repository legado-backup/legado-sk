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
     * ⚠️ 未配置备份路径时必须跳过并记日志，不得抛错、不得弹 UI。
     */
    @Test
    fun skipsWhenBackupPathNotConfigured() {
        val body = autoBackupBlock()

        assertTrue(
            "必须检查备份路径是否为空",
            body.contains("backupPath.isNullOrBlank()")
        )
        assertTrue(
            "跳过时必须记日志（拒绝静默）",
            body.contains("AppLog.put")
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
        val backupCallAt = body.indexOf("backup(context, backupPath")
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
     * ⚠️ 触发点必须是**显式白名单**，不得挂在 DAO 层。
     * 全库 200+ 处 `bookDao` 写点绝大多数只改进度/目录，且 `Restore` 也直接写 `bookDao`
     * —— 在 DAO 层拦截会把恢复流程自身卷进来（见 autoBackupSuspendedWhileRestoring）。
     */
    @Test
    fun triggerIsExplicitNotDaoWide() {
        // 触发方法应存在且被显式调用方引用
        val vm = moduleSource("src/main/java/io/legado/app/ui/main/bookshelf/BookshelfViewModel.kt")

        assertTrue(
            "BookshelfViewModel 应提供显式触发入口",
            vm.contains("fun notifyShelfMaybeChanged()")
        )
        assertTrue(
            "触发入口应委托 Backup.autoBackupOnShelfChangeIfNeeded",
            vm.contains("Backup.autoBackupOnShelfChangeIfNeeded(context)")
        )
        // Backup 自身不得读取 bookDao 之外的隐式写路径钩子（即不注册 DAO 观察者）
        assertTrue(
            "Backup 侧不得注册 DAO 层拦截（无 InvalidationTracker 观察者）",
            !backupSource().contains("InvalidationTracker")
        )
    }
}
