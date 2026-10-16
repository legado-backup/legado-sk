package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「默认备份内容」→ 备份动作的**真行为**回归锁。
 *
 * 背景（10098 修的真实缺陷）：`selectedTargetsOrNull()` 用 **null 表示「全选」**
 * （全打包，不下发清单），而「中止备份」也需要一个信号。10073 起调用方写成
 * `selectBackupTargets() ?: return@launch`，把两者当成同一件事 —— 于是
 * **全选（也就是从未动过该设置的人的默认状态）时点「备份」毫无反应**，
 * 且从 10073 一路活到 10097 没被任何测试拦住。
 *
 * 拦不住的原因值得记下来：`BackupTargetConfigTest` 全是**源码字面量断言**，
 * 而当时 `return null` 还在、文本完全"正确"，坏掉的是语义。所以本测试
 * **直接调用真实函数**（`decideBackupScope` 是纯函数，不碰 `appCtx`，
 * 因此可在 JVM 单测中求值），断言的是行为而非"某行代码还在"。
 */
class BackupScopeDecisionTest {

    /**
     * ⚠️ 本缺陷的正主：**全选必须走向备份，而不是中止**。
     *
     * `null` 在这里是「全打包」这一个意思，绝不能兼任「用户取消」。
     */
    @Test
    fun allSelectedProceedsInsteadOfAborting() {
        val decision = decideBackupScope(null)
        assertTrue(
            "全选（selectedTargetsOrNull() == null）必须执行备份，不得被当成中止 —— " +
                "否则默认状态下备份按钮毫无反应（10073~10097 实际如此）",
            decision is BackupScopeDecision.Proceed
        )
        assertEquals(
            "全选必须原样保留 null（＝不下发清单，旧「全部打包」语义），" +
                "换成空集会让备份变成空包",
            null,
            (decision as BackupScopeDecision.Proceed).targets
        )
    }

    /**
     * ⚠️ 一个都没勾必须**中止**，不能塌缩成「全打包」。
     *
     * 塌缩会产出一个「以为备份成功、实际按用户明确排除的设定打了包」或
     * 「恢复后什么都没有」的结果，且全程无报错。
     */
    @Test
    fun emptySelectionAbortsInsteadOfWideningToFullBackup() {
        assertEquals(
            "空选择必须中止（调用方据此提示），不得塌缩成全打包",
            BackupScopeDecision.Abort,
            decideBackupScope(emptySet())
        )
    }

    /** 部分选择必须原样下发选中集合，不得被收窄或放宽。 */
    @Test
    fun partialSelectionPassesTargetsThrough() {
        val selected = setOf("bookshelf.json", "bookSource.json")
        val decision = decideBackupScope(selected)
        assertTrue("部分选择必须执行备份", decision is BackupScopeDecision.Proceed)
        assertEquals(
            "部分选择必须原样下发，不得增删目标",
            selected,
            (decision as BackupScopeDecision.Proceed).targets
        )
    }
}
