package com.echolink.app

import com.echolink.app.audio.LatencyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标水位 / 重采样比率的策略测试。
 *
 * 语义已按外部评审调整：控制量从“水位平均值”改成“窗口最小值”，
 * 目标从 `P99(抖动) + 5 ms` 改成物理量 `burst + 5 ms`（抖动只做展示）。
 * PHJ110: burst 256 帧 = 5.33 ms → 目标约 10.33 ms。
 */
class LatencyControllerTest {

    private fun controller() = LatencyController()

    private fun settle(
        controller: LatencyController,
        ticks: Int = 80,
        minMs: Double = 10.0,
        burst: Int = 256,
        underrun: Long = 0,
    ): LatencyController.Decision {
        var decision = controller.update(minMs, minMs, underrun, burst, 0.0)
        repeat(ticks) {
            decision = controller.update(minMs, minMs, 0, burst, 0.0)
        }

        return decision
    }

    @Test
    fun targetIsBurstPlusMargin() {
        val controller = controller()

        // burst 256 帧 = 5.33 ms，余量 5 ms → 目标约 10.33 ms
        val decision = settle(controller, burst = 256)

        assertEquals(10.33, decision.targetMs, 0.15)
        assertTrue("frames=${decision.targetFrames}", decision.targetFrames in 470..520)
    }

    @Test
    fun largerBurstRaisesTarget() {
        val controller = controller()
        val decision = settle(controller, burst = 480)  // 10 ms burst

        assertEquals(15.0, decision.targetMs, 0.15)
    }

    @Test
    fun shrinksFromConservativeStart() {
        val controller = controller()

        // 起步 20 ms，随后按 0.5 ms/周期收缩到物理目标（第一个周期就会开始收）
        val first = controller.update(20.0, 20.0, 0, 256, 0.0)
        assertEquals(19.5, first.targetMs, 0.01)

        val settled = settle(controller, burst = 256)
        assertEquals(10.33, settled.targetMs, 0.15)
    }

    @Test
    fun clampsToMinimumWaterLevel() {
        val controller = controller()

        // 极小 burst 时目标是 6 ms 下限，而不是 burst+margin
        val decision = settle(controller, burst = 16)
        assertEquals(6.0, decision.targetMs, 1e-6)
    }

    @Test
    fun clampsToMaximumWaterLevel() {
        val controller = controller()

        var decision = controller.update(30.0, 30.0, 1, 4800, 0.0)  // 100 ms burst
        repeat(200) {
            decision = controller.update(30.0, 30.0, 0, 4800, 0.0)
        }

        assertEquals(60.0, decision.targetMs, 1e-6)
    }

    @Test
    fun underrunRaisesTargetByOneBurstImmediately() {
        val controller = controller()
        val before = settle(controller, burst = 192)

        val after = controller.update(before.waterMinMs, 10.0, 1, 192, 0.0)

        // 192 帧 @48k = 4 ms
        assertEquals(before.targetMs + 4.0, after.targetMs, 0.6)
        assertTrue(after.growsBecauseOfUnderrun)
    }

    @Test
    fun targetShrinksBackAfterUnderrunStops() {
        val controller = controller()
        settle(controller, burst = 256)
        controller.update(10.0, 10.0, 1, 256, 0.0)  // 触发一次上调

        val recovered = settle(controller, ticks = 60, burst = 256)
        assertEquals(10.33, recovered.targetMs, 0.2)
    }

    @Test
    fun ratioStaysWithinPointOnePercent() {
        val controller = controller()
        settle(controller, burst = 256)

        var decision = controller.update(10.0, 10.0, 0, 256, 0.0)
        repeat(5_000) {
            decision = controller.update(500.0, 500.0, 0, 256, 0.0)  // 水位远高于目标
        }

        assertTrue("ratio=${decision.ratio}", decision.ratio <= 1.001 + 1e-9)
        assertTrue("ratio=${decision.ratio}", decision.ratio > 1.0)
    }

    @Test
    fun ratioLooksAtMinimumNotAverage() {
        val controller = controller()
        settle(controller, burst = 256)

        // 平均值很高但最低值只有 2 ms（低于目标）→ 应该补水而不是排空
        var decision = controller.update(2.0, 60.0, 0, 256, 0.0)
        repeat(200) {
            decision = controller.update(2.0, 60.0, 0, 256, 0.0)
        }

        assertTrue("ratio=${decision.ratio}", decision.ratio < 1.0)
    }

    @Test
    fun ratioIsNeutralOnUnderrunTick() {
        val controller = controller()
        settle(controller, burst = 256)
        repeat(3_000) { controller.update(500.0, 500.0, 0, 256, 0.0) }

        val decision = controller.update(2.0, 500.0, 1, 256, 0.0)
        assertTrue("ratio=${decision.ratio}", decision.ratio < 0.9995 + 0.0015)
    }

    @Test
    fun ratioMovesSlowlyEnoughToBeInaudible() {
        val controller = controller()
        settle(controller, burst = 256)

        var previous = controller.update(10.0, 10.0, 0, 256, 0.0).ratio
        repeat(200) {
            val current = controller.update(400.0, 400.0, 0, 256, 0.0).ratio
            assertTrue("delta=${current - previous}", current - previous <= 1e-5 + 1e-12)
            previous = current
        }
    }

    @Test
    fun manualFixedTargetOverridesAdaptiveTarget() {
        val controller = controller()

        var decision = controller.update(12.0, 12.0, 0, 256, 0.0, fixedTargetMs = 12.0)
        repeat(200) {
            decision = controller.update(12.0, 12.0, 0, 256, 0.0, fixedTargetMs = 12.0)
        }

        assertEquals(12.0, decision.targetMs, 1e-6)
    }

    @Test
    fun jitterIsReportedButDoesNotDriveTarget() {
        val controller = controller()

        // 抖动很大，但目标仍由 burst 决定
        repeat(1_000) { controller.onJitter(30_000) }
        val decision = settle(controller, burst = 256)

        assertEquals(30.0, decision.jitterP99Ms, 0.5)
        assertEquals(10.33, decision.targetMs, 0.15)
    }

    @Test
    fun resetClearsState() {
        val controller = controller()
        repeat(1_000) { controller.onJitter(9_000) }
        controller.update(3.0, 3.0, 2, 256, 0.0)

        controller.reset()

        assertEquals(0, controller.jitterSampleCount)
        val decision = controller.update(20.0, 20.0, 0, 256, 0.0)
        // reset 后回到保守起步值，并在第一个周期就开始向物理目标收缩
        assertTrue("target=${decision.targetMs}", decision.targetMs in 19.0..20.0)
        assertEquals(1.0, decision.ratio, 1e-5)
    }
}
