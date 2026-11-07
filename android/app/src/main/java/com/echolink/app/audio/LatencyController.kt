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
 * 目标值 = 物理基线 + **带衰减的棘轮**：
 *
 *     base    = burst + marginMs            (PHJ110: 5.33 + 5 ≈ 10.3 ms)
 *     ratchet = 欠载缺口 + 2ms，累计上限 +10ms
 *     保持     = 最近一次欠载后 60 s
 *     衰减     = 保持期结束后 0.1 ms/s
 *
 * 棘轮的必要性：欠载说明"基线不够用"，必须临时抬高；但它抬高的是一次性事件的开销，
 * 不该永久留在缓冲里。早期版本固定每次 +1 个 burst 且**没有衰减路径**，
 * 真机上看到目标从 10.3 爬到 17.3 ms 就再也不下来——白付了约 7 ms 延迟。
 *
 * 为什么增量按"缺口 + 2ms"而不是固定加一个 burst：一次差 1 ms 的欠载和差 6 ms 的欠载
 * 需要不同的补偿；固定加 burst（PHJ110 上 5.33 ms）对前者是过度补偿，
 * 而过度补偿在下一次统计里又会看起来"水位偏高"，误导出相反的判断。
 */
class LatencyController(
    private val sampleRate: Int = Protocol.SAMPLE_RATE,
    private val minWaterMs: Double = 6.0,
    private val maxWaterMs: Double = 60.0,
    private val startWaterMs: Double = 20.0,
    private val marginMs: Double = 5.0,
    /** 棘轮累计上限：再高也盖不住单次长停顿，宁可接受偶发小欠载 */
    private val ratchetCapMs: Double = 10.0,
    /** 欠载后保持抬升的时长 */
    private val ratchetHoldMs: Long = 60_000,
    /** 保持期结束后的回落速度 */
    private val ratchetDecayMsPerSecond: Double = 0.1,
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

    private var ratchetMs = 0.0
    private var holdUntilMs = 0L
    private var lastUpdateMs = 0L

    /** 目标（窗口最小值语义），= 基线 + 棘轮。 */
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
        /** 当前棘轮抬升量（ms），用于判断"是不是在为过去的欠载付费" */
        val ratchetMs: Double,
        /** 还要保持多久（ms），0 表示已在衰减 */
        val holdRemainingMs: Long,
        val growsBecauseOfUnderrun: Boolean,
        val errorMs: Double,
    )

    val jitterSampleCount: Int get() = jitterSamples

    @Synchronized
    fun reset() {
        histogram.fill(0)
        jitterSamples = 0
        jitterMaxUs = 0
        ratchetMs = 0.0
        holdUntilMs = 0L
        lastUpdateMs = 0
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

    /** 由 burst 与余量推出的物理基线目标。 */
    fun physicalTargetMs(framesPerBurst: Int): Double {
        val burstMs = if (framesPerBurst > 0) {
            framesPerBurst * 1000.0 / sampleRate
        } else {
            Protocol.PACKET_INTERVAL_MS.toDouble()
        }

        return (burstMs + marginMs).coerceIn(minWaterMs, maxWaterMs)
    }

    /**
     * 每个控制周期调用一次。
     *
     * @param waterMinMs 最近一个窗口内水位的**最小值**（控制量）
     * @param waterAvgMs 当前瞬时水位（仅展示/日志）
     * @param underrunDelta 自上个周期以来新增的欠载次数
     * @param framesPerBurst 音轨 burst（帧）
     * @param bufferMs Oboe 内部缓冲时长（仅诊断，不参与目标计算）
     * @param nowMs 单调时钟毫秒（`SystemClock.elapsedRealtime()`）
     * @param fixedTargetMs 用户手动锁定目标时非空
     */
    @Synchronized
    fun update(
        waterMinMs: Double,
        waterAvgMs: Double,
        underrunDelta: Long,
        framesPerBurst: Int,
        bufferMs: Double,
        nowMs: Long,
        fixedTargetMs: Double? = null,
    ): Decision {
        val p99 = percentileUs(0.99) / 1000.0
        val p50 = percentileUs(0.50) / 1000.0
        val baseline = fixedTargetMs?.coerceIn(minWaterMs, maxWaterMs) ?: physicalTargetMs(framesPerBurst)

        var grewByUnderrun = false
        if (underrunDelta > 0 && fixedTargetMs == null) {
            // 缺口按"当前目标 − 观测最小值"估，夹在 [1, cap]
            val shortfall = (targetMs - waterMinMs).coerceIn(1.0, ratchetCapMs)
            val bumped = min(ratchetCapMs, ratchetMs + shortfall + 2.0)
            if (bumped > ratchetMs) {
                ratchetMs = bumped
                grewByUnderrun = true
            }

            holdUntilMs = nowMs + ratchetHoldMs
        }

        // 衰减：仅在没有欠载、且保持期已过时进行
        val elapsedMs = if (lastUpdateMs > 0) (nowMs - lastUpdateMs).coerceAtLeast(0) else 0L
        lastUpdateMs = nowMs
        if (underrunDelta == 0L && ratchetMs > 0.0 && nowMs > holdUntilMs) {
            ratchetMs = max(0.0, ratchetMs - ratchetDecayMsPerSecond * (elapsedMs / 1000.0))
        }

        val desired = (baseline + ratchetMs).coerceIn(minWaterMs, maxWaterMs)

        // 涨得快、收得慢（避免在抖动还没兑现时缩回去）
        targetMs = if (desired > targetMs) {
            min(desired, targetMs + growStepMsPerTick)
        } else {
            max(desired, targetMs - shrinkStepMsPerTick)
        }.coerceIn(minWaterMs, maxWaterMs)

        // 最小值高于目标 → 排空（ratio > 1）；低于目标 → 补水
        val errorMs = waterMinMs - targetMs
        var ratioTarget = 1.0 + (errorMs * ratioGainPerMs)
        if (underrunDelta > 0) {
            ratioTarget = 1.0
        }

        ratioTarget = ratioTarget.coerceIn(1.0 - ratioLimit, 1.0 + ratioLimit)
        val smoothed = ratio + (ratioTarget - ratio) * loopAlpha
        ratio = smoothed.coerceIn(ratio - ratioSlewPerTick, ratio + ratioSlewPerTick)
            .coerceIn(1.0 - ratioLimit, 1.0 + ratioLimit)

        return Decision(
            targetMs = targetMs,
            targetFrames = Protocol.msToFrames(targetMs),
            ratio = ratio,
            jitterP99Ms = p99,
            jitterP50Ms = p50,
            waterMinMs = waterMinMs,
            waterAvgMs = waterAvgMs,
            ratchetMs = ratchetMs,
            holdRemainingMs = (holdUntilMs - nowMs).coerceAtLeast(0),
            growsBecauseOfUnderrun = grewByUnderrun,
            errorMs = errorMs,
        )
    }
}
