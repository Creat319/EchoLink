package com.echolink.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自适应目标水位 + 微量重采样的闭环策略（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 策略要点（见 docs/architecture.md 参数表）：
 *  - 起步保守：固定 30 ms，头一段时间只统计抖动，不收缩；
 *  - 收缩到 P99 + 5 ms，并 clamp 在 [20, 60] ms；
 *    下限 20 ms 是**硬约束**：PC 端按 10 ms 引擎周期成批发包，水位必须能平稳跨过一个发包间隔，
 *    10 ms 的下限在真机上会导致每个发包周期都欠载（PHJ110 实测 2475 次）。
 *  - 欠载：立刻 +1 个 burst（LatencyTuner 式），不等待统计；
 *  - 收缩：每次控制周期最多减 0.5 ms（≈10 ms/s），涨得快收得慢；
 *  - 重采样比率偏差限 ±0.1%，一阶低通（环路带宽 ~0.02 Hz）+ 每周期斜率限幅，
 *    避免可闻的音高/延迟变化。
 */
class LatencyController(
    private val sampleRate: Int = Protocol.SAMPLE_RATE,
    private val minWaterMs: Double = 20.0,
    private val maxWaterMs: Double = 60.0,
    private val startWaterMs: Double = 30.0,
    private val marginMs: Double = 5.0,
    private val warmupSeconds: Double = 90.0,
    private val warmupMinSamples: Int = 600,
    private val shrinkStepMsPerTick: Double = 0.5,
    private val growStepMsPerTick: Double = 2.0,
    private val ratioLimit: Double = 0.001,
    private val ratioGainPerMs: Double = 5e-5,
    private val ratioSlewPerTick: Double = 1e-5,
    private val loopAlpha: Double = 0.01,
    private val jitterBucketUs: Int = 250,
    private val jitterBuckets: Int = 256,
) {
    private val histogram = IntArray(jitterBuckets)
    private var jitterSamples = 0
    private var jitterMaxUs = 0L

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
        val waterMs: Double,
        val warm: Boolean,
        val growsBecauseOfUnderrun: Boolean,
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

    /** 记录一次单跳抖动 |d_i|（微秒）。只由收包线程调用。 */
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
                // 取桶上界，略微保守
                return ((bucket + 1) * jitterBucketUs).toDouble()
            }
        }

        return jitterMaxUs.toDouble()
    }

    private fun isWarm(elapsedSeconds: Double) = elapsedSeconds >= warmupSeconds && jitterSamples >= warmupMinSamples

    /**
     * 每个控制周期调用一次。
     *
     * @param waterMs 当前水位（毫秒）
     * @param elapsedSeconds 开流以来的秒数
     * @param underrunDelta 自上个周期以来新增的欠载次数
     * @param framesPerBurst 音轨 burst（帧）。用于“欠载 +1 burst”
     * @param fixedTargetMs 非 null 时表示用户手动锁定目标水位（仍然保留欠载加 burst 的兜底）
     * @param dynamicMinWaterMs 由 Oboe 实际缓冲大小推出的动态下限：环里必须至少存着
     *        “一次回调可能取走的量 + 抖动余量”，否则每个回调都会欠载。取 max(20 ms, 该值)。
     */
    @Synchronized
    fun update(
        waterMs: Double,
        elapsedSeconds: Double,
        underrunDelta: Long,
        framesPerBurst: Int,
        fixedTargetMs: Double? = null,
        dynamicMinWaterMs: Double? = null,
    ): Decision {
        val p99 = percentileUs(0.99) / 1000.0
        val p50 = percentileUs(0.50) / 1000.0
        val warm = isWarm(elapsedSeconds)

        val floor = maxOf(minWaterMs, dynamicMinWaterMs ?: 0.0).coerceAtMost(maxWaterMs)
        val candidate = (p99 + marginMs).coerceIn(floor, maxWaterMs)
        var desired = when {
            fixedTargetMs != null -> fixedTargetMs.coerceIn(floor, maxWaterMs)
            warm -> candidate
            else -> maxOf(startWaterMs, floor)
        }
        var grewByUnderrun = false

        // 欠载：立刻加一个 burst，不等统计收敛
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

            // 欠载瞬间不要把水位再往下拉
            targetMs = max(targetMs, desired)
        }

        // 涨得快、收得慢
        targetMs = if (desired > targetMs) {
            min(desired, targetMs + growStepMsPerTick)
        } else {
            max(desired, targetMs - shrinkStepMsPerTick)
        }

        targetMs = targetMs.coerceIn(floor, maxWaterMs)

        // 硬下限：缓冲变大导致 floor 抬高时，必须立刻抬到 floor，不能等“缓慢上涨”
        if (targetMs < floor) {
            targetMs = floor
        }

        // 微量重采样：水位高于目标就略微读快一点（ratio > 1 → 排空）
        val errorMs = waterMs - targetMs
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
            waterMs = waterMs,
            warm = warm,
            growsBecauseOfUnderrun = grewByUnderrun,
        )
    }
}
