package com.echolink.app

import com.echolink.app.audio.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 包长可变（跟随 PC 端抓取粒度）之后，接收端必须按数据报长度推导帧数。
 * 板载 2.67 ms 端点上每包 128 帧（528 B），10 ms 端点上拆成 240 帧（976 B）。
 */
class PacketLengthTest {

    private fun bytesFor(frames: Int) = Protocol.HEADER_BYTES + frames * Protocol.BYTES_PER_FRAME

    @Test
    fun fullPacketGives240Frames() {
        assertEquals(240, Protocol.framesInDatagram(bytesFor(240)))
        assertEquals(980, bytesFor(240))
    }

    @Test
    fun smallPacketGives128Frames() {
        // S/PDIF 板载端点：每回调 128 帧 → 20 + 512 = 532 B
        assertEquals(128, Protocol.framesInDatagram(bytesFor(128)))
        assertEquals(532, bytesFor(128))
    }

    @Test
    fun singleFramePacketIsValid() {
        assertEquals(1, Protocol.framesInDatagram(bytesFor(1)))
    }

    @Test
    fun headerOnlyIsMalformed() {
        assertEquals(-1, Protocol.framesInDatagram(Protocol.HEADER_BYTES))
    }

    @Test
    fun partialFrameIsMalformed() {
        // 16 + 6 字节不是整帧
        assertEquals(-1, Protocol.framesInDatagram(Protocol.HEADER_BYTES + 6))
    }

    @Test
    fun oversizedPacketIsMalformed() {
        // 超过单包上限（240 帧）
        assertEquals(-1, Protocol.framesInDatagram(bytesFor(241)))
    }

    @Test
    fun allFrameCountsUpToMaxAreAccepted() {
        for (frames in 1..Protocol.FRAMES_PER_PACKET) {
            assertEquals(frames, Protocol.framesInDatagram(bytesFor(frames)))
        }
    }
}
