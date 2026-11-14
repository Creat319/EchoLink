package com.echolink.app

import com.echolink.app.net.ArrivalKind
import com.echolink.app.net.classifyArrival
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成串停顿的判定阈值。
 *
 * 这条统计是"WiFi 热点不稳"的唯一直接证据（抖动直方图看不出来：
 * 一次 200 ms 卡顿只贡献一个样本，P99 几乎不动），阈值判错了就等于没测。
 */
class ArrivalClassificationTest {

    /** 单位：微秒。抖动 = 到达间隔 − 采集间隔。 */
    @Test
    fun normalJitterIsNormal() {
        assertEquals(ArrivalKind.NORMAL, classifyArrival(0))
        assertEquals(ArrivalKind.NORMAL, classifyArrival(3_000))     // +3 ms
        assertEquals(ArrivalKind.NORMAL, classifyArrival(-3_000))    // −3 ms
        assertEquals(ArrivalKind.NORMAL, classifyArrival(24_000))    // 24 ms，还没到阈值
    }

    @Test
    fun longGapIsStall() {
        assertEquals(ArrivalKind.STALL, classifyArrival(26_000))     // 26 ms
        assertEquals(ArrivalKind.STALL, classifyArrival(120_000))    // 120 ms，典型的 WiFi 卡顿
        assertEquals(ArrivalKind.STALL, classifyArrival(500_000))    // 500 ms
    }

    @Test
    fun bunchedArrivalsAreCatchUp() {
        // 停顿之后数据一次性涌来：到达间隔远小于采集间隔
        assertEquals(ArrivalKind.CATCH_UP, classifyArrival(-11_000))
        assertEquals(ArrivalKind.CATCH_UP, classifyArrival(-190_000))
    }

    @Test
    fun thresholdsAreExclusiveAtTheBoundary() {
        // 恰好落在阈值上不算，避免边界抖动反复计数
        assertEquals(ArrivalKind.NORMAL, classifyArrival(25_000))
        assertEquals(ArrivalKind.NORMAL, classifyArrival(-10_000))
        assertEquals(ArrivalKind.STALL, classifyArrival(25_001))
        assertEquals(ArrivalKind.CATCH_UP, classifyArrival(-10_001))
    }
}