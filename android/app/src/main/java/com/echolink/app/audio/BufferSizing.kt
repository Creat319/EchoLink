package com.echolink.app.audio

/**
 * Oboe 缓冲扩容档位的计算。
 *
 * 单独抽出来是因为它是纯算术、又很容易悄悄算错（历史上这个项目在"水位 vs 缓冲"
 * 的换算上翻过车）。规则：
 *
 * - 目标是**一次跳到够用的档位**，不是每次 +1。逐级 +1 会让用户先听几秒断续，
 *   日志里表现为 2→3→4 burst 连着欠载两轮。
 * - 「够用」的判据：缓冲要覆盖 `目标水位 + 半个 burst`。目标水位管的是数据到达侧的
 *   抖动余量，缓冲管的是回调迟到；两者串联，缓冲明显小于水位时会周期性欠载。
 * - 至少比当前大 1（否则调用方会以为扩了容，其实原地踏步）。
 */
internal object BufferSizing {

    /**
     * @param targetWaterMs 当前目标水位（毫秒）
     * @param burstMs 一个 burst 的时长（毫秒）；<= 0 表示设备没报 burst，只能保守 +1
     * @param currentBursts 当前缓冲 burst 数
     * @param maxBursts 允许的上限
     */
    fun nextBursts(targetWaterMs: Double, burstMs: Double, currentBursts: Int, maxBursts: Int): Int {
        if (currentBursts >= maxBursts) {
            return maxBursts
        }

        if (burstMs <= 0.0 || targetWaterMs <= 0.0) {
            return (currentBursts + 1).coerceAtMost(maxBursts)
        }

        val wanted = kotlin.math.ceil((targetWaterMs + burstMs * 0.5) / burstMs).toInt()
        return wanted.coerceIn(currentBursts + 1, maxBursts)
    }
}
