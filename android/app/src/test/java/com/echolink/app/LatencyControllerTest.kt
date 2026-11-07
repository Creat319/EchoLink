package com.echolink.app

import com.echolink.app.audio.LatencyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标水位 / 重采样比率的策略测试。这些是“跑 2 小时必须收敛”的核心行为，
 * 放在纯 JVM 上跑，不需要真机。
 */
class LatencyControllerTest {

    private fun controller() = LatencyController()

    @Test
    fun startsConservativeAt30msDuringWarmup() {
        val controller = controller()

        // 抖动很小，但还在预热期：必须维持 30 ms 起步值，不许提前收缩
        repeat(100) { controller.onJitter(1_000) }
        val decision = controller.update(waterMs = 30.0, elapsedSeconds = 10.0, underrunDelta = 0, framesPerBurst = 192)

        assertEquals(30.0, decision.targetMs, 1e-9)
        assertTrue(!decision.warm)
        assertEquals(1440, decision.targetFrames)
    }

    @Test
    fun shrinksTowardsP99Plus5msOnceWarm() {
        val controller = controller()

        // 抖动稳定在 6 ms → P99+5 = 11.25 ms，但下限是 20 ms，所以最终停在 20 ms
        repeat(1_000) { controller.onJitter(6_000) }
        var decision = controller.update(30.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        assertTrue(decision.warm)

        // 收缩是缓慢的：一个周期最多降 0.5 ms
        repeat(60) {
            decision = controller.update(30.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        }

        assertEquals(20.0, decision.targetMs, 0.01)
        // 抖动 P99 是桶上界：6000 us 落在 24 号桶 → 25 * 250 = 6250 us
        assertEquals(6.25, decision.jitterP99Ms, 0.3)
    }

    @Test
    fun clampsToMinimumWaterLevel() {
        val controller = controller()

        repeat(1_000) { controller.onJitter(100) } // 抖动 < 0.25 ms
        var decision = controller.update(20.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        repeat(400) {
            decision = controller.update(20.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        }

        // 下限 20 ms：必须能跨过 PC 端 10 ms 的成批发包间隔（PHJ110 上 10 ms 下限导致 2475 次欠载）
        assertEquals(20.0, decision.targetMs, 1e-6)
    }

    @Test
    fun clampsToMaximumWaterLevel() {
        val controller = controller()

        repeat(1_000) { controller.onJitter(200_000) } // 200 ms 抖动
        var decision = controller.update(30.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        repeat(100) {
            decision = controller.update(30.0, elapsedSeconds = 200.0, underrunDelta = 0, framesPerBurst = 192)
        }

        assertEquals(60.0, decision.targetMs, 1e-6)
    }

    @Test
    fun underrunRaisesTargetByOneBurstImmediately() {
        val controller = controller()
        repeat(100) { controller.onJitter(1_000) }

        val before = controller.update(30.0, 10.0, 0, 192)
        // 192 帧 @48k = 4 ms → 期望 34 ms
        val after = controller.update(30.0, 10.1, 1, 192)

        assertEquals(before.targetMs + 4.0, after.targetMs, 0.6)
        assertTrue(after.growsBecauseOfUnderrun)
    }

    @Test
    fun ratioStaysWithinPointOnePercent() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(1_000) }

        // 水位比目标高得多：比率应该往上走，但永不越过 +0.1%
        var decision = controller.update(30.0, 200.0, 0, 192)
        repeat(5_000) {
            decision = controller.update(500.0, 200.0, 0, 192)
        }

        assertTrue("ratio=${decision.ratio}", decision.ratio <= 1.001 + 1e-9)
        assertTrue("ratio=${decision.ratio}", decision.ratio > 1.0)
    }

    @Test
    fun ratioIsNeutralOnUnderrunTick() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(1_000) }

        // 先让比率往上冲
        repeat(3_000) { controller.update(500.0, 200.0, 0, 192) }

        // 欠载瞬间不应继续加速排空
        val decision = controller.update(5.0, 200.0, 1, 192)
        assertTrue("ratio=${decision.ratio}", decision.ratio < 1.001)
    }

    @Test
    fun ratioMovesSlowlyEnoughToBeInaudible() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(1_000) }

        var previous = controller.update(30.0, 200.0, 0, 192).ratio
        repeat(200) {
            val current = controller.update(400.0, 200.0, 0, 192).ratio
            // 每个控制周期（50 ms）的比率变化不超过 1e-5
            assertTrue("delta=${current - previous}", current - previous <= 1e-5 + 1e-12)
            previous = current
        }
    }

    @Test
    fun dynamicFloorFromOboeBufferRaisesWaterLevel() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(1_000) } // 抖动很小，纯噪声不会把目标推高

        // PHJ110：burst 256 帧，强制 4 burst 缓冲 = 1024 帧 ≈ 21.3 ms → 下限 21.3×1.15 ≈ 24.5 ms
        val floor = 24.5
        var decision = controller.update(30.0, 200.0, 0, 256, dynamicMinWaterMs = floor)
        repeat(300) {
            decision = controller.update(floor, 200.0, 0, 256, dynamicMinWaterMs = floor)
        }

        assertEquals(floor, decision.targetMs, 0.01)
    }

    @Test
    fun dynamicFloorLiftsTargetImmediatelyWhenBufferGrows() {
        val controller = controller()
        repeat(500) { controller.onJitter(1_000) }

        val before = controller.update(30.0, 200.0, 0, 256)
        assertTrue(before.targetMs <= 30.0)

        // 缓冲被调大后，目标必须【立刻】抬到新下限，不能等“每周期涨 2 ms”
        val after = controller.update(30.0, 200.0, 0, 256, dynamicMinWaterMs = 40.0)
        assertEquals(40.0, after.targetMs, 0.01)
    }

    @Test
    fun manualFixedTargetOverridesAdaptiveTarget() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(6_000) }

        var decision = controller.update(30.0, 200.0, 0, 192, fixedTargetMs = 25.0)
        repeat(200) {
            decision = controller.update(30.0, 200.0, 0, 192, fixedTargetMs = 25.0)
        }

        assertEquals(25.0, decision.targetMs, 1e-6)
    }

    @Test
    fun resetClearsHistogramAndState() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(9_000) }
        controller.update(30.0, 200.0, 2, 192)

        controller.reset()

        assertEquals(0, controller.jitterSampleCount)
        val decision = controller.update(30.0, 0.0, 0, 192)
        assertEquals(30.0, decision.targetMs, 1e-9)
        assertEquals(1.0, decision.ratio, 1e-9)
    }
}
