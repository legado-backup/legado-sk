package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    /**
     * 上次记账时书架的**在线书身份键集合**（用于「书架变动时自动备份」的增删判据）。
     *
     * ⚠️ **必须放在 `LocalConfig`（独立的 `"local"` pref 文件）**，不能放进 `AppConfig`：
     * `AppConfig` 走 `defaultSharedPreferences`，会被 `Backup` 全量写进 `config.xml`
     * 并在恢复时带回本机 —— 那会把本机的判据基线"校准"成另一台设备的状态，
     * 使自动备份的增删判定静默失准。这是本机运行态，不是用户配置。
     *
     * ⚠️ 写入时点必须在**备份成功之后**（见 `Backup.autoBackupOnShelfChangeIfNeeded`）。
     */
    var lastShelfKeys: String?
        get() = getString("lastShelfKeys", null)
        set(value) {
            if (value != null) {
                putString("lastShelfKeys", value)
            } else {
                remove("lastShelfKeys")
            }
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    fun defaultDataVersion(versionKey: String): Int = getInt(versionKey, 0)

    fun markDefaultDataVersion(versionKey: String, version: Int) {
        edit { putInt(versionKey, version) }
    }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

    /**
     * 内置主题预设是否已播种为本地主题包（一次性语义）。
     *
     * ⚠️ 用「一次性标记」而不是「检查目录是否存在」作判据：后者会在用户**主动删除**
     * 某条内置预设后把它重新塞回来，等于用户删不掉。标记位只保证「补种一次」——
     * 存量设备首次运行到主题页时标记位不存在 → 补种一次；此后用户删任意预设都不复活。
     */
    var builtinThemePresetSeeded: Boolean
        get() = getBoolean("builtinThemePresetSeeded")
        set(value) {
            putBoolean("builtinThemePresetSeeded", value)
        }

}
