package com.echolink.app

import com.echolink.app.audio.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量控制包的互操作向量。
 *
 * 下面的十六进制对应 C# 端 `PacketFormat.WriteControl` 写出来的字节：
 *   sequence = 7，command = SET_GAIN(1)，value = 0.75f（0x3F400000）
 *
 * 任何一端改了布局（偏移、字节序、命令号），这个测试立刻失败——
 * 这正是最怕的那种"两端各自按文档实现、文档写错了就一起错"。
 */
class ControlPacketTest {

    private companion object {
        /** 28 B：20 B 包头 + [command:u32][value:f32]。 */
        const val CONTROL_HEX =
            "07000000" +                 // sequence = 7
            "0000000000000000" +         // sampleCount = 0（控制包没有采样计数）
            "00000000" +                 // timestamp = 0
            "02000000" +                 // stream = 2（控制）
            "01000000" +                 // command = SET_GAIN
            "0000403F"                   // value = 0.75f
    }

    private fun controlPacket(): ByteBuffer {
        val bytes = ByteArray(CONTROL_HEX.length / 2) { index ->
            CONTROL_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(bytes)
        return buffer
    }

    @Test
    fun parsesGainWrittenByPc() {
        val gain = Protocol.controlGainFrom(controlPacket())
        assertEquals(0.75f, gain!!, 1e-6f)
    }

    @Test
    fun packetLengthMatchesConstant() {
        assertEquals(28, Protocol.CONTROL_PACKET_BYTES)
        assertEquals(Protocol.HEADER_BYTES + Protocol.CONTROL_PAYLOAD_BYTES, Protocol.CONTROL_PACKET_BYTES)
        assertEquals(28, controlPacket().capacity())
    }

    @Test
    fun controlDatagramIsRecognisedOnlyWithExactLength() {
        assertTrue(Protocol.isControlDatagram(Protocol.STREAM_CONTROL, Protocol.CONTROL_PACKET_BYTES))
        // 长度不对（比如被截断或串进音频包）就不当控制包
        assertFalse(Protocol.isControlDatagram(Protocol.STREAM_CONTROL, Protocol.CONTROL_PACKET_BYTES + 4))
        // 音频包绝不能被误认成控制包
        assertFalse(Protocol.isControlDatagram(Protocol.STREAM_DOWNLINK, Protocol.PACKET_BYTES))
        assertFalse(Protocol.isControlDatagram(Protocol.STREAM_UPLINK, Protocol.CONTROL_PACKET_BYTES))
    }

    @Test
    fun unknownCommandIsIgnored() {
        val buffer = controlPacket()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(Protocol.HEADER_BYTES, 99)   // 未知命令号
        assertNull(Protocol.controlGainFrom(buffer))
    }

    @Test
    fun outOfRangeGainIsClamped() {
        val loud = controlPacket().also {
            it.order(ByteOrder.LITTLE_ENDIAN)
            it.putFloat(Protocol.HEADER_BYTES + 4, 9.0f)
        }
        assertEquals(2.0f, Protocol.controlGainFrom(loud)!!, 1e-6f)

        val silent = controlPacket().also {
            it.order(ByteOrder.LITTLE_ENDIAN)
            it.putFloat(Protocol.HEADER_BYTES + 4, -3.0f)
        }
        assertEquals(0.0f, Protocol.controlGainFrom(silent)!!, 1e-6f)
    }
}
