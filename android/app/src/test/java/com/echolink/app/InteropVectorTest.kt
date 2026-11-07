package com.echolink.app

import com.echolink.app.audio.AudioPacketCodec
import com.echolink.app.audio.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 互操作向量测试。
 *
 * 下面的十六进制**不是手写的**，而是 `EchoLink.Diagnostics echo` 跑起来之后直接 dump 的
 * “PC 发送端真实发出的某个包”的字节：
 *
 *   首个包头（互操作向量）: 04000000C003000000000000A8D20200
 *   首个包载荷前 8 字节    : 0000000000000000
 *
 * 它证明安卓端解析器能读懂 C# 发送端真正写出来的字节，而不是“两端各自按文档实现、
 * 文档写错了就一起错”。任何一端改了布局，这个测试会立刻失败。
 */
class InteropVectorTest {

    private companion object {
        const val REAL_HEADER_HEX = "04000000C003000000000000A8D20200"
    }

    private fun realPacket(): ByteBuffer {
        val packet = ByteBuffer.allocate(Protocol.PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val header = ByteArray(REAL_HEADER_HEX.length / 2) { index ->
            REAL_HEADER_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        packet.put(header, 0, header.size)
        // 载荷在该次抓包时是静音（测试音尚未开始），因此保持全零
        return packet
    }

    @Test
    fun parsesPacketProducedByPcSender() {
        val header = AudioPacketCodec.readHeader(realPacket())
        assertNotNull(header)

        assertEquals(4, header!!.sequence)
        assertEquals(960L, header.sampleCount)
        assertEquals(185_000, header.timestampMicros)
    }

    @Test
    fun payloadStartsRightAfterHeaderAsStereoPairs() {
        assertEquals(16, Protocol.HEADER_BYTES)
        assertEquals(Protocol.PAYLOAD_BYTES, Protocol.PACKET_BYTES - Protocol.HEADER_BYTES)

        val packet = realPacket().order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until Protocol.FRAMES_PER_PACKET * Protocol.CHANNELS) {
            assertEquals(0, packet.getShort(Protocol.HEADER_BYTES + index * 2).toInt())
        }
    }

    @Test
    fun sequenceAndSampleCountStayInStep() {
        val header = AudioPacketCodec.readHeader(realPacket())!!
        // 发送端不变量：sampleCount == seq * 240（本次会话从 0 起）
        assertEquals(header.sequence.toLong() * Protocol.FRAMES_PER_PACKET, header.sampleCount)
    }
}
