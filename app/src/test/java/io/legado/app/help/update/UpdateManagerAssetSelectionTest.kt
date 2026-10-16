package io.legado.app.help.update

import io.legado.app.help.update.UpdateManager.GitHubAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「更新检查按安装变体选包」的守卫契约。
 *
 * 背景：`fetchLatestRelease` 原先对每个 Release 取
 * `assets.firstOrNull { it.name.endsWith(".apk") }` —— **资产顺序 = 上传顺序**。
 * 10065 起每个 Release 都同时挂正式版与共存版两个 APK，正式版先上传，
 * 于是共存版 `io.legado.app.sk2` 点「检查更新」下载到的永远是**正式包**
 * （包名 `io.legado.app.c`），装上去只会新增/更新另一个 App，共存版自己永远升不上去。
 *
 * ⚠️ 与仓库里多数守卫测试不同，本测试**直接调用真实函数**而非做源码文本断言：
 * `selectUpdateAsset` 是纯函数（不碰 `appCtx` / `BuildConfig`），
 * 传参即可覆盖两个分支，能真正证明行为而不是证明"某行代码还在"。
 * 只有 `isCoexistBuild()` 依赖 BuildConfig，故不在此断言其常量值。
 */
class UpdateManagerAssetSelectionTest {

    private fun asset(name: String) = GitHubAsset(name, "https://example.com/$name")

    /** 实测的线上资产名（10096 Release，两个包都已发布）。 */
    private val officialName = "legado_sk_3.26.101625c_10096_arm64-v8a.apk"
    private val coexistName = "legado_sk_3.26.101625c_10096_arm64-v8a_sk2-coexist.apk"

    /** 历史命名（10065）：早期共存版资产后缀是 `_sk2`，也必须认得。 */
    private val coexistLegacyName = "legado_sk_3.26.092117c_10065_arm64-v8a_sk2.apk"

    /**
     * ⚠️ 核心契约：**选包结果不得依赖资产顺序**。
     * 这正是线上 10096 的真实顺序（正式版在前），旧实现就是在这一条上出错。
     */
    @Test
    fun assetChoiceDoesNotDependOnUploadOrder() {
        val officialFirst = listOf(asset(officialName), asset(coexistName))
        val coexistFirst = listOf(asset(coexistName), asset(officialName))

        assertEquals(
            "正式版必须选到无 sk2 标记的包，与上传顺序无关",
            officialName,
            UpdateManager.selectUpdateAsset(officialFirst, isCoexistBuild = false)?.name
        )
        assertEquals(
            "正式版必须选到无 sk2 标记的包，与上传顺序无关",
            officialName,
            UpdateManager.selectUpdateAsset(coexistFirst, isCoexistBuild = false)?.name
        )
        assertEquals(
            "共存版必须选到 sk2-coexist 包，与上传顺序无关",
            coexistName,
            UpdateManager.selectUpdateAsset(officialFirst, isCoexistBuild = true)?.name
        )
        assertEquals(
            "共存版必须选到 sk2-coexist 包，与上传顺序无关",
            coexistName,
            UpdateManager.selectUpdateAsset(coexistFirst, isCoexistBuild = true)?.name
        )
    }

    /** 两个变体绝不可互相串包：任何顺序下都不得出现「共存版拿到正式包」或反之。 */
    @Test
    fun variantsNeverCrossContaminate() {
        val pool = listOf(asset(officialName), asset(coexistName), asset(coexistLegacyName))

        val forOfficial = UpdateManager.selectUpdateAsset(pool, isCoexistBuild = false)?.name
        val forCoexist = UpdateManager.selectUpdateAsset(pool, isCoexistBuild = true)?.name

        assertFalse("正式版不得拿到共存包: $forOfficial", forOfficial!!.contains("sk2"))
        assertTrue("共存版必须拿到共存包: $forCoexist", forCoexist!!.contains("sk2"))
    }

    /**
     * ⚠️ 反退化：非 APK 资产与"没有对应变体"的 Release 必须返回 null。
     *
     * 返回 null 的语义是「这个 Release 没有我这一版」→ 调用方跳过它、继续看下一个 →
     * 全部跳过时提示"已是最新"。**不允许退化成"随便挑一个 APK"**：那会让共存版
     * 下载到包名不同的包，安装后表现成"更新了另一个 App"而自己没变。
     */
    @Test
    fun missingVariantOrNonApkYieldsNull() {
        // 10058 及更早只有正式版资产：共存版必须判定为「无我这一版」
        assertNull(
            "只有正式包时，共存版不得挑走它",
            UpdateManager.selectUpdateAsset(listOf(asset(officialName)), isCoexistBuild = true)
        )
        // 只有共存包时，正式版同理
        assertNull(
            "只有共存包时，正式版不得挑走它",
            UpdateManager.selectUpdateAsset(listOf(asset(coexistName)), isCoexistBuild = false)
        )
        // 非 APK 资产（源码包、校验文件）不得被当成更新包
        assertNull(
            "非 .apk 资产不得入选",
            UpdateManager.selectUpdateAsset(
                listOf(asset("legado_sk_3.26.101625c_10096_source.zip")),
                isCoexistBuild = false
            )
        )
        assertNull(
            "空资产列表返回 null",
            UpdateManager.selectUpdateAsset(emptyList(), isCoexistBuild = true)
        )
    }

    /** 早期 `_sk2.apk` 命名必须仍被共存版认作自己的包。 */
    @Test
    fun legacyCoexistAssetNameStillRecognized() {
        assertEquals(
            coexistLegacyName,
            UpdateManager.selectUpdateAsset(
                listOf(asset(coexistLegacyName)), isCoexistBuild = true
            )?.name
        )
    }

    /**
     * ⚠️ 版本号解析必须能吃下共存版资产名（含 `sk2-coexist` 后缀）。
     * 若解析失败，候选会被整个丢弃 → 共存版收不到任何更新，问题会以另一种形式复现。
     */
    @Test
    fun coexistAssetNameStillParsableForVersion() {
        assertEquals(10096L, UpdateManager.parseVersionCodeFromAssetName(coexistName))
        assertEquals(10065L, UpdateManager.parseVersionCodeFromAssetName(coexistLegacyName))
        assertEquals(10096L, UpdateManager.parseVersionCodeFromAssetName(officialName))
        assertEquals(101625L, UpdateManager.parseVersionKeyFromAssetName(coexistName))
    }
}
