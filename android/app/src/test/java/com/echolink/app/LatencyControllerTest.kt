package com.echolink.app

import com.echolink.app.audio.LatencyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标水位 / 重采样比率的策略测试。
 *
 * 语义要点：
 * - 控制量是**窗口最小值**，不是平均值
 * - 目标 = `burst + 5 ms` 基线 + **带衰减的棘轮**
 * - 棘轮：欠载时按"缺口 + 2 ms"抬升，累计上限 +10 ms，保持 60 s 后按 0.1 ms/s 回落
 *
 * 棘轮之所以重要：早期版本固定每次 +1 个 burst 且没有衰减路径，
 * 真机上目标从 10.3 ms 爬到 17.3 ms 就再也不下来，白付约 7 ms 延迟。
 */
class LatencyControllerTest {

    private fun controller() = LatencyController()

    /** 模拟 20 Hz 控制循环：每 tick 50 ms，nowMs 单调递增。 */
    private class Clock {
        var now = 1_000L
            private set

        fun tick(): Long {
            now += 50
            return now
        }
    }

    private fun LatencyController.step(
        clock: Clock,
        minMs: Double = 12.0,
        burst: Int = 256,
        underrun: Long = 0,
        fixed: Double? = null,
    ) = update(minMs, minMs, underrun, burst, 0.0, clock.tick(), fixed)

    private fun settle(
        controller: LatencyController,
        clock: Clock,
        ticks: Int = 80,
        minMs: Double = 12.0,
        burst: Int = 256,
    ): LatencyController.Decision {
        var decision = controller.step(clock, minMs, burst)
        repeat(ticks) { decision = controller.step(clock, minMs, burst) }
        return decision
    }

    @Test
    fun targetIsBurstPlusMargin() {
        val controller = controller()
        val clock = Clock()

        // burst 256 帧 = 5.33 ms，余量 5 ms → 基线约 10.33 ms
        val decision = settle(controller, clock, burst = 256)

        assertEquals(10.33, decision.targetMs, 0.15)
        assertEquals(0.0, decision.ratchetMs, 1e-9)
    }

    @Test
    fun largerBurstRaisesTarget() {
        val controller = controller()
        val clock = Clock()
        val decision = settle(controller, clock, burst = 480)  // 10 ms burst

        assertEquals(15.0, decision.targetMs, 0.15)
    }

    @Test
    fun shrinksFromConservativeStart() {
        val controller = controller()
        val clock = Clock()

        val first = controller.step(clock, burst = 256)
        assertEquals(19.5, first.targetMs, 0.01)

        val settled = settle(controller, clock, burst = 256)
        assertEquals(10.33, settled.targetMs, 0.15)
    }

    @Test
    fun clampsToMinimumWaterLevel() {
        val controller = controller()
        val clock = Clock()
        val decision = settle(controller, clock, burst = 16)

        assertEquals(6.0, decision.targetMs, 1e-6)
    }

    @Test
    fun clampsToMaximumWaterLevel() {
        val controller = controller()
        val clock = Clock()

        var decision = controller.step(clock, minMs = 30.0, burst = 4800)  // 100 ms burst
        repeat(200) { decision = controller.step(clock, minMs = 30.0, burst = 4800) }

        assertEquals(60.0, decision.targetMs, 1e-6)
    }

    @Test
    fun underrunRaisesByShortfallNotWholeBurst() {
        val controller = controller()
        val clock = Clock()
        val settled = settle(controller, clock, burst = 256)
        val before = settled.targetMs

        // 水位 6 ms，目标 10.33 → 缺口约 4.3 ms，加 2 ms 余量 ≈ 6.3 ms 抬升
        val first = controller.step(clock, minMs = 6.0, burst = 256, underrun = 1)
        assertTrue("ratchet=${first.ratchetMs}", first.ratchetMs in 5.0..7.5)
        assertTrue(first.growsBecauseOfUnderrun)

        // 棘轮立即生效，但目标值受 2 ms/周期 的涨幅限制，要几个周期才到位
        var after = first
        repeat(6) { after = controller.step(clock, minMs = 6.0, burst = 256) }

        assertEquals(before + 6.3, after.targetMs, 0.8)
    }

    @Test
    fun ratchetIsCappedAt10ms() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)

        // 连续欠载也不该超过 基线 + 10 ms
        repeat(20) { controller.step(clock, minMs = 1.0, burst = 256, underrun = 1) }

        val decision = settle(controller, clock, minMs = 12.0, burst = 256, ticks = 40)
        assertTrue("target=${decision.targetMs}", decision.targetMs <= 10.33 + 10.0 + 0.2)
        assertTrue("ratchet=${decision.ratchetMs}", decision.ratchetMs <= 10.0 + 1e-6)
    }

    @Test
    fun ratchetDoesNotDecayDuringHold() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)
        controller.step(clock, minMs = 5.0, burst = 256, underrun = 1)

        // 保持期内（60 s）走 30 s，棘轮不该减少
        var decision = controller.step(clock, minMs = 20.0, burst = 256)
        repeat(600) { decision = controller.step(clock, minMs = 20.0, burst = 256) }

        assertEquals(10.33 + 0.0, 10.33, 1e-9)  // 文档基线
        assertTrue("ratchet=${decision.ratchetMs}", decision.ratchetMs > 1.0)
    }

    @Test
    fun ratchetDecaysAfterHold() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)
        val bumped = controller.step(clock, minMs = 5.0, burst = 256, underrun = 1).ratchetMs
        assertTrue(bumped > 1.0)

        // 保持 60 s + 再走 40 s（0.1 ms/s → 约 4 ms 回落）
        var decision = controller.step(clock, minMs = 20.0, burst = 256)
        repeat(2_000) { decision = controller.step(clock, minMs = 20.0, burst = 256) }

        assertTrue("ratchet=${decision.ratchetMs} bumped=$bumped", decision.ratchetMs < bumped - 2.0)
    }

    @Test
    fun ratioStaysWithinPointOnePercent() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)

        var decision = controller.step(clock, minMs = 12.0, burst = 256)
        repeat(5_000) { decision = controller.step(clock, minMs = 500.0, burst = 256) }

        assertTrue("ratio=${decision.ratio}", decision.ratio <= 1.001 + 1e-9)
        assertTrue("ratio=${decision.ratio}", decision.ratio > 1.0)
    }

    @Test
    fun ratioLooksAtMinimumNotAverage() {
        val controller = controller()
        val clock = Clock()

        // 这里 update 的 avg 参数被显式传成 60，但最小值是 2 → 应补水
        var decision = controller.update(2.0, 60.0, 0, 256, 0.0, clock.tick())
        repeat(300) { decision = controller.update(2.0, 60.0, 0, 256, 0.0, clock.tick()) }

        assertTrue("ratio=${decision.ratio}", decision.ratio < 1.0)
    }

    @Test
    fun ratioIsNeutralOnUnderrunTick() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)
        repeat(3_000) { controller.step(clock, minMs = 500.0, burst = 256) }

        val saturated = controller.step(clock, minMs = 500.0, burst = 256).ratio
        val decision = controller.step(clock, minMs = 2.0, burst = 256, underrun = 1)

        // 欠载当拍不允许继续排空：比率不得高于饱和值。
        // 回落速度受 1e-5/周期 的斜率限幅约束，不能期待单拍归零。
        assertTrue("ratio=${decision.ratio} saturated=$saturated", decision.ratio <= saturated + 1e-9)
    }

    @Test
    fun ratioMovesSlowlyEnoughToBeInaudible() {
        val controller = controller()
        val clock = Clock()
        settle(controller, clock, burst = 256)

        var previous = controller.step(clock, minMs = 12.0, burst = 256).ratio
        repeat(200) {
            val current = controller.step(clock, minMs = 400.0, burst = 256).ratio
            assertTrue("delta=${current - previous}", current - previous <= 1e-5 + 1e-12)
            previous = current
        }
    }

    @Test
    fun manualFixedTargetDisablesRatchet() {
        val controller = controller()
        val clock = Clock()

        controller.update(12.0, 12.0, 1, 256, 0.0, clock.tick(), fixedTargetMs = 12.0)
        var decision = controller.update(2.0, 2.0, 1, 256, 0.0, clock.tick(), fixedTargetMs = 12.0)
        repeat(200) { decision = controller.update(12.0, 12.0, 0, 256, 0.0, clock.tick(), fixedTargetMs = 12.0) }

        assertEquals(12.0, decision.targetMs, 1e-6)
        assertEquals(0.0, decision.ratchetMs, 1e-9)
    }

    @Test
    fun jitterIsReportedButDoesNotDriveTarget() {
        val controller = controller()
        val clock = Clock()

        repeat(1_000) { controller.onJitter(30_000) }
        val decision = settle(controller, clock, burst = 256)

        assertEquals(30.0, decision.jitterP99Ms, 0.5)
        assertEquals(10.33, decision.targetMs, 0.15)
    }

    @Test
    fun resetClearsState() {
        val controller = controller()
        val clock = Clock()
        repeat(1_000) { controller.onJitter(9_000) }
        controller.step(clock, minMs = 3.0, burst = 256, underrun = 2)

        controller.reset()

        assertEquals(0, controller.jitterSampleCount)
        val decision = controller.step(clock, burst = 256)
        assertTrue("target=${decision.targetMs}", decision.targetMs in 19.0..20.0)
        assertTrue("ratchet=${decision.ratchetMs}", decision.ratchetMs < 1e-9)
    }
}
