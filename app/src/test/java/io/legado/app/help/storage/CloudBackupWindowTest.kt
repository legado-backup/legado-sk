package io.legado.app.help.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自动备份的「云端 24 小时」判据回归锁。
 *
 * 需求：24 小时内**任一设备**在云端备份过，本机就不再自动备份。
 * 旧逻辑只比本机 `LocalConfig.lastBackup`，换设备后各传各的。
 *
 * [isCloudBackupWithinOneDay] 是纯函数（不碰 `appCtx`），故直接测真实行为；
 * 接线另用一条源码断言锁住，避免"函数正确但没人调用"。
 */
class CloudBackupWindowTest {

    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)

    /** 平板 23 小时前备份过 ⇒ 手机（本机无记录）必须跳过自动备份。 */
    @Test
    fun backupFromAnotherDeviceWithinOneDaySkipsBackup() {
        assertTrue(isCloudBackupWithinOneDay(now - day + 1_000, now))
    }

    /** 云端最近一次已超过 24 小时 ⇒ 本机应正常备份。 */
    @Test
    fun backupOlderThanOneDayDoesNotSkipBackup() {
        assertFalse(isCloudBackupWithinOneDay(now - day - 1_000, now))
    }

    /** 没有云端备份、或服务器未返回时间（解析失败为 0）时不得当成"刚备份过"。 */
    @Test
    fun missingTimestampIsNotTreatedAsRecent() {
        assertFalse(isCloudBackupWithinOneDay(0, now))
    }

    /** 判据必须真的接在 [Backup.autoBack] 上，否则语义正确但自动备份照旧只看本机。 */
    @Test
    fun autoBackConsultsCloudBackupTime() {
        val f = File("src/main/java/io/legado/app/help/storage/Backup.kt")
        assertTrue("找不到 Backup.kt（Gradle 单测 CWD 应为模块目录 app/）", f.exists())
        val body = f.readText()
            .substringAfter("fun autoBack(context: Context)")
            .substringBefore("fun autoBackupOnShelfChangeIfNeeded")
        assertTrue("自动备份必须查询云端最近备份时间（跨设备判据）", body.contains("AppWebDav.lastBackUp()"))
        assertTrue("自动备份必须使用统一的 24 小时判据", body.contains("isCloudBackupWithinOneDay("))
    }
}
