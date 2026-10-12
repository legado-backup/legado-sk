package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读音量增强的换算契约。
 *
 * 这里锁定三件事：①「只增大、不衰减」；② 上限就是 2 倍（对应 UI 开关的开启档），
 * ③ 越界值（含早期版本存下的 300/400）在同一条 `coerceIn` 路径上收敛，不得出现 2 倍以上。
 * 第三条取代了迁移代码：上限一旦被调高，老用户的陈旧高增益会直接生效并破音。
 */
class VolumeGainTest {

    @Test
    fun offPosition_doesNotChangeVolume() {
        // 关闭 = 不增强，绝不能变成衰减
        assertEquals(1f, VolumeGain.factorFor(0))
        assertEquals(1f, VolumeGain.factorFor(AppConfigDefaultVolume))
    }

    @Test
    fun percentMapsToLinearFactor() {
        assertEquals(1.5f, VolumeGain.factorFor(50))
        assertEquals(2f, VolumeGain.factorFor(100))
    }

    /**
     * ⚠️ 开关只有「开 = 2 倍」一档。上限必须精确等于 100，否则：
     * 调低 → 开关打开的增益不再是 2 倍（文案与实际不符）；
     * 调高 → 老用户的 300/400 脏值不再被收敛，直接破音。
     */
    @Test
    fun ceilingIsExactlyTwoTimes() {
        assertEquals(2f, VolumeGain.MAX_FACTOR)
        assertEquals(100, VolumeGain.MAX_PERCENT)
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.factorFor(VolumeGain.MAX_PERCENT))
    }

    @Test
    fun outOfRange_isClamped_neverAttenuates_neverExceedsCeiling() {
        // 负数（含历史脏值）必须收敛为不增强，而不是小于 1 的衰减
        assertEquals(1f, VolumeGain.factorFor(-100))
        // 早期版本滑条上限 400：旧值必须在读取路径收敛回 2 倍，不得生效
        assertEquals(2f, VolumeGain.factorFor(400))
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.factorFor(Int.MAX_VALUE))
    }

    @Test
    fun refreshKeepsCachedFactorInSync() {
        // 播放线程只读 currentFactor，必须与设置值换算一致
        VolumeGain.refresh(0)
        assertEquals(1f, VolumeGain.currentFactor)
        VolumeGain.refresh(100)
        assertEquals(2f, VolumeGain.currentFactor)
        // 越界值经同一入口收敛，避免缓存里出现非法增益
        VolumeGain.refresh(Int.MAX_VALUE)
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.currentFactor)
        VolumeGain.refresh(0)
    }

    private companion object {
        /** 与 AppConfig.defaultVolumeGain 保持一致；单测无法读 SharedPreferences。 */
        const val AppConfigDefaultVolume = 0
    }
}
