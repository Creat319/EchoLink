package com.echolink.app

import com.echolink.app.audio.AudioPacketCodec
import com.echolink.app.audio.Protocol
import com.echolink.app.audio.StatusReport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 线格式一致性测试：这里的期望值直接取自 docs/protocol.md，
 * 用来防止两端字节布局悄悄漂移。
 */
class ProtocolTest {

    @Test
    fun packetLayoutMatchesSpec() {
        assertEquals(48000, Protocol.SAMPLE_RATE)
        assertEquals(2, Protocol.CHANNELS)
        assertEquals(240, Protocol.FRAMES_PER_PACKET)
        assertEquals(4, Protocol.BYTES_PER_FRAME)
        assertEquals(960, Protocol.PAYLOAD_BYTES)
        assertEquals(16, Protocol.HEADER_BYTES)
        // 总长 976：协议的核心不变量
        assertEquals(976, Protocol.PACKET_BYTES)
    }

    @Test
    fun headerIsLittleEndianAtFixedOffsets() {
        val packet = ByteBuffer.allocate(Protocol.PACKET_BYTES)
        // 手工按小端写入：[seq=0x01020304][sampleCount=0x1122334455667788][ts=0x0A0B0C0D]
        packet.put(0, 0x04)
        packet.put(1, 0x03)
        packet.put(2, 0x02)
        packet.put(3, 0x01)
        val sampleCount = 0x1122334455667788L
        for (index in 0 until 8) {
            packet.put(4 + index, ((sampleCount shr (8 * index)) and 0xFF).toByte())
        }

        packet.put(12, 0x0D)
        packet.put(13, 0x0C)
        packet.put(14, 0x0B)
        packet.put(15, 0x0A)

        val header = AudioPacketCodec.readHeader(packet)
        assertNotNull(header)
        assertEquals(0x01020304, header!!.sequence)
        assertEquals(0x1122334455667788L, header.sampleCount)
        assertEquals(0x0A0B0C0D, header.timestampMicros)
    }

    @Test
    fun headerRejectsBufferSmallerThanHeader() {
        // 契约变更：包长可变后，readHeader 只要求容纳 16 B 包头，
        // “载荷是否是整帧”由 Protocol.framesInDatagram 负责校验。
        val tooShort = ByteBuffer.allocate(Protocol.HEADER_BYTES - 1)
        assertNull(AudioPacketCodec.readHeader(tooShort))
    }

    @Test
    fun headerAcceptsSmallVariableLengthPacket() {
        // 板载 2.67 ms 端点：每包 128 帧 = 528 B，必须能读出包头
        val small = ByteBuffer.allocate(Protocol.HEADER_BYTES + 128 * Protocol.BYTES_PER_FRAME)
        small.order(ByteOrder.LITTLE_ENDIAN)
        small.putInt(0, 7)
        small.putLong(4, 384L)
        small.putInt(12, 12_345)

        val header = AudioPacketCodec.readHeader(small)
        assertNotNull(header)
        assertEquals(7, header!!.sequence)
        assertEquals(384L, header.sampleCount)
        assertEquals(12_345, header.timestampMicros)
        assertEquals(128, Protocol.framesInDatagram(small.capacity()))
    }

    @Test
    fun nonWholeFrameDatagramIsRejected() {
        // 976 - 1 字节：载荷不是整帧，不能当成合法的 243 帧包
        assertEquals(-1, Protocol.framesInDatagram(Protocol.PACKET_BYTES - 1))
    }

    @Test
    fun statusReportLayout() {
        val report = StatusReport.build(
            reportSeq = 7,
            framesPlayed = 480_000,
            packetsReceived = 2000,
            packetsLost = 3,
            underruns = 1,
            waterMs = 31,
            targetWaterMs = 30,
            jitterP99Us = 4200,
            latencyMs = 21,
        )

        assertEquals(40, report.size)

        val buffer = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(StatusReport.MAGIC, buffer.getInt(0))
        assertEquals(1, buffer.getShort(4).toInt())
        assertEquals(1, buffer.getShort(6).toInt())
        assertEquals(7, buffer.getInt(8))
        assertEquals(480_000L, buffer.getLong(12))
        assertEquals(2000, buffer.getInt(20))
        assertEquals(3, buffer.getInt(24))
        assertEquals(1, buffer.getInt(28))
        assertEquals(31, buffer.getShort(32).toInt())
        assertEquals(30, buffer.getShort(34).toInt())
        assertEquals(4200, buffer.getShort(36).toInt())
        assertEquals(21, buffer.getShort(38).toInt())
    }

    @Test
    fun reportFieldsSaturateInsteadOfOverflowing() {
        val report = StatusReport.build(
            reportSeq = 0,
            framesPlayed = 0,
            packetsReceived = 0,
            packetsLost = 0,
            underruns = 0,
            waterMs = 999_999,
            targetWaterMs = 999_999,
            jitterP99Us = 999_999,
            latencyMs = 999_999,
        )

        val buffer = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        // u16 饱和到 65535：Java 里 short 是带符号的，读出来要按无符号解释
        assertEquals(65535, buffer.getShort(32).toInt() and 0xFFFF)
        assertEquals(65535, buffer.getShort(36).toInt() and 0xFFFF)
    }

    @Test
    fun msAndFramesConversionsRoundTrip() {
        val frames = Protocol.msToFrames(30.0)
        assertEquals(1440, frames)
        assertEquals(30.0, Protocol.framesToMs(1440L), 1e-9)
    }
}
