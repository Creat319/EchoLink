package com.echolink.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自适应目标水位 + 微量重采样的闭环策略（纯 Kotlin，可单元测试）。
 *
 * 控制量是**回调窗口内水位的最小值**，不是平均值。原因：
 * PC 端按引擎周期成批投递，水位是锯齿；控制均值会系统性地高估半个批次（约 5 ms），
 * 而且 20 Hz 的控制周期恰好是 10 ms 批的整数倍，采样会锁定在锯齿的固定相位上，
 * 两端时钟缓慢漂移时还会出现慢拍干扰。控制最小值就没有这些问题。
 *
 * 目标值的物理含义也随之改变：它不再是“缓冲里平均要存多少”，
 * 而是“锯齿低谷不能低于多少”，因此下限只需覆盖**一次回调可能取走的量 + 少量余量**：
 *
 *     target = burstMs + marginMs        (PHJ110: 5.33 + 5 ≈ 10.3 ms)
 *
 * 这比原先 `P99(抖动) + 5 ms` 更小且更稳：抖动 P99 里混着 PC 批处理，
 * 用它定目标会把 PC 侧的批处理延迟重复计一次。
 */
class LatencyController(
    private val sampleRate: Int = Protocol.SAMPLE_RATE,
    private val minWaterMs: Double = 6.0,
    private val maxWaterMs: Double = 60.0,
    private val startWaterMs: Double = 20.0,
    private val marginMs: Double = 5.0,
    private val shrinkStepMsPerTick: Double = 0.5,
    private val growStepMsPerTick: Double = 2.0,
    private val ratioLimit: Double = 0.001,
    private val ratioGainPerMs: Double = 5e-5,
    private val ratioSlewPerTick: Double = 1e-5,
    private val loopAlpha: Double = 0.01,
    private val jitterBucketUs: Int = 250,
    private val jitterBuckets: Int = 256,
) {
    // 纯展示用：抖动的分位数不再参与控制，只用于 UI 和诊断
    private val histogram = IntArray(jitterBuckets)
    private var jitterSamples = 0
    private var jitterMaxUs = 0L

    /** 目标（窗口最小值语义）。 */
    @Volatile
    var targetMs: Double = startWaterMs
        private set

    @Volatile
    var ratio: Double = 1.0
        private set

    data class Decision(
        val targetMs: Double,
        val targetFrames: Int,
        val ratio: Double,
        val jitterP99Ms: Double,
        val jitterP50Ms: Double,
        val waterMinMs: Double,
        val waterAvgMs: Double,
        val growsBecauseOfUnderrun: Boolean,
        val errorMs: Double,
    )

    val jitterSampleCount: Int get() = jitterSamples

    @Synchronized
    fun reset() {
        histogram.fill(0)
        jitterSamples = 0
        jitterMaxUs = 0
        targetMs = startWaterMs
        ratio = 1.0
    }

    /** 记录一次单跳抖动 |d_i|（微秒）。只用于展示，不参与控制。 */
    @Synchronized
    fun onJitter(micros: Long) {
        val magnitude = abs(micros)
        if (magnitude > jitterMaxUs) {
            jitterMaxUs = magnitude
        }

        val bucket = (magnitude / jitterBucketUs).toInt().coerceIn(0, jitterBuckets - 1)
        if (histogram[bucket] < Int.MAX_VALUE - 1) {
            histogram[bucket]++
        }

        if (jitterSamples < Int.MAX_VALUE - 1) {
            jitterSamples++
        }
    }

    @Synchronized
    fun percentileUs(percentile: Double): Double {
        if (jitterSamples == 0) {
            return 0.0
        }

        val target = (jitterSamples * percentile).toInt().coerceAtLeast(1)
        var cumulative = 0
        for (bucket in histogram.indices) {
            cumulative += histogram[bucket]
            if (cumulative >= target) {
                return ((bucket + 1) * jitterBucketUs).toDouble()
            }
        }

        return jitterMaxUs.toDouble()
    }

    /** 由 burst 与余量推出的“物理最小值”目标。 */
    fun physicalTargetMs(framesPerBurst: Int): Double {
        val burstMs = if (framesPerBurst > 0) framesPerBurst * 1000.0 / sampleRate else Protocol.PACKET_INTERVAL_MS.toDouble()
        return (burstMs + marginMs).coerceIn(minWaterMs, maxWaterMs)
    }

    /**
     * 每个控制周期调用一次。
     *
     * @param waterMinMs 最近一个窗口内水位的**最小值**（控制量）
     * @param waterAvgMs 当前瞬时水位（仅展示/日志）
     * @param underrunDelta 自上个周期以来新增的欠载次数
     * @param framesPerBurst 音轨 burst（帧）
     * @param bufferMs Oboe 内部缓冲时长（仅用于诊断，不再参与目标计算）
     * @param fixedTargetMs 用户手动锁定目标时非空
     */
    @Synchronized
    fun update(
        waterMinMs: Double,
        waterAvgMs: Double,
        underrunDelta: Long,
        framesPerBurst: Int,
        bufferMs: Double,
        fixedTargetMs: Double? = null,
    ): Decision {
        val p99 = percentileUs(0.99) / 1000.0
        val p50 = percentileUs(0.50) / 1000.0
        val physical = physicalTargetMs(framesPerBurst)

        var desired = fixedTargetMs?.coerceIn(minWaterMs, maxWaterMs) ?: physical
        var grewByUnderrun = false

        // 欠载：立刻加一个 burst（LatencyTuner 式），不等统计
        if (underrunDelta > 0) {
            val burstMs = if (framesPerBurst > 0) {
                framesPerBurst * 1000.0 / sampleRate
            } else {
                Protocol.PACKET_INTERVAL_MS.toDouble()
            }

            val bumped = (targetMs + burstMs.coerceAtLeast(1.0)).coerceAtMost(maxWaterMs)
            if (bumped > desired) {
                desired = bumped
                grewByUnderrun = bumped > targetMs
            }

            targetMs = max(targetMs, desired)
        }

        // 涨得快、收得慢
        targetMs = if (desired > targetMs) {
            min(desired, targetMs + growStepMsPerTick)
        } else {
            max(desired, targetMs - shrinkStepMsPerTick)
        }

        targetMs = targetMs.coerceIn(minWaterMs, maxWaterMs)

        // 最小值高于目标 → 排空（ratio > 1）；低于目标 → 补水
        val errorMs = waterMinMs - targetMs
        var ratioTarget = 1.0 + (errorMs * ratioGainPerMs)
        if (underrunDelta > 0) {
            ratioTarget = 1.0
        }

        ratioTarget = ratioTarget.coerceIn(1.0 - ratioLimit, 1.0 + ratioLimit)

        val smoothed = ratio + (ratioTarget - ratio) * loopAlpha
        val slewLimited = smoothed.coerceIn(ratio - ratioSlewPerTick, ratio + ratioSlewPerTick)
        ratio = slewLimited.coerceIn(1.0 - ratioLimit, 1.0 + ratioLimit)

        return Decision(
            targetMs = targetMs,
            targetFrames = Protocol.msToFrames(targetMs),
            ratio = ratio,
            jitterP99Ms = p99,
            jitterP50Ms = p50,
            waterMinMs = waterMinMs,
            waterAvgMs = waterAvgMs,
            growsBecauseOfUnderrun = grewByUnderrun,
            errorMs = errorMs,
        )
    }
}
