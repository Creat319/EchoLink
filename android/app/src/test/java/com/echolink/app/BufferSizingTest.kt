package com.echolink.app

import com.echolink.app.audio.BufferSizing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓冲扩容档位。
 *
 * 用实测数字做基准：该平板 burst = 256 帧 @48 kHz = 5.333 ms，目标水位 18–20 ms。
 * 日志里旧逻辑是 2 burst(10.7 ms) → 欠载 → 3 burst(16 ms) → 又欠载 → 4 burst(21.3 ms)，
 * 两轮共约 10 次欠载。新逻辑应当一步到位。
 */
class BufferSizingTest {

    private val burstMs = 256 * 1000.0 / 48000   // 5.3333

    @Test
    fun jumpsStraightToEnoughForTargetWater() {
        // 18 ms 水位 + 半个 burst ≈ 20.67 ms → 需要 4 个 burst
        assertEquals(4, BufferSizing.nextBursts(18.0, burstMs, currentBursts = 2, maxBursts = 4))
        assertEquals(4, BufferSizing.nextBursts(20.3, burstMs, currentBursts = 2, maxBursts = 4))
    }

    @Test
    fun neverShrinksAndAlwaysGrowsByAtLeastOne() {
        // 水位很低也不会缩回去，但至少要 +1，否则调用方会以为扩了容
        assertEquals(2, BufferSizing.nextBursts(1.0, burstMs, currentBursts = 1, maxBursts = 4))
        assertEquals(3, BufferSizing.nextBursts(1.0, burstMs, currentBursts = 2, maxBursts = 4))
    }

    @Test
    fun respectsUpperBound() {
        assertEquals(4, BufferSizing.nextBursts(60.0, burstMs, currentBursts = 2, maxBursts = 4))
        assertEquals(4, BufferSizing.nextBursts(60.0, burstMs, currentBursts = 4, maxBursts = 4))
    }

    @Test
    fun fallsBackToSingleStepWithoutBurstInformation() {
        // 设备没报 burst：只能保守 +1，不能除零
        assertEquals(3, BufferSizing.nextBursts(20.0, burstMs = 0.0, currentBursts = 2, maxBursts = 4))
        assertEquals(2, BufferSizing.nextBursts(20.0, burstMs = -1.0, currentBursts = 1, maxBursts = 4))
    }

    @Test
    fun smallBurstNeedsMoreStepsButStillBounded() {
        // 128 帧 @48k = 2.67 ms；20 ms 水位需要 8 个 burst，但上限是 4
        val small = 128 * 1000.0 / 48000
        assertEquals(4, BufferSizing.nextBursts(20.0, small, currentBursts = 2, maxBursts = 4))
    }
}
