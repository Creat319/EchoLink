package com.echolink.app

import com.echolink.app.audio.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网发现的线格式。
 *
 * 下面的十六进制是**实测**的——用 PC 端 `PacketFormat.WriteDiscoverRequest` /
 * 同样的应答布局 dump 出来的字节，不是照文档手写的。两端布局一旦错位，这里立刻失败。
 */
class LanDiscoveryPacketTest {

    private companion object {
        /** PC 端 WriteDiscoverRequest(nonce=12345) 的真实输出，28 B。 */
        const val REQUEST_HEX =
            "39300000" +             // seq（= nonce）
            "0000000000000000" +     // sampleCount = 0
            "00000000" +             // timestamp = 0
            "03000000" +             // stream = 3（发现）
            "01000000" +             // command = DISCOVER_REQUEST
            "39300000"               // nonce = 12345

        /** 手机端应答布局（PC 侧解析），nonce=999、设备名 "Pixel 8"。 */
        const val REPLY_HEX =
            "E7030000" +             // seq
            "0000000000000000" +
            "00000000" +
            "03000000" +             // stream = 3
            "02000000" +             // command = DISCOVER_REPLY
            "E7030000" +             // nonce = 999
            "07000000" +             // nameLen = 7
            "506978656C2038"         // "Pixel 8"
    }

    private fun hexToBuffer(hex: String): ByteBuffer {
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(bytes)
        return buffer
    }

    @Test
    fun parsesRequestWrittenByPc() {
        val buffer = hexToBuffer(REQUEST_HEX)
        val nonce = Protocol.readDiscoverNonce(buffer, buffer.capacity())
        assertNotNull(nonce)
        assertEquals(12345, nonce)
    }

    @Test
    fun replyLayoutMatchesPcExpectation() {
        val reply = Protocol.buildDiscoverReply(999, "Pixel 8")
        assertEquals(REPLY_HEX, reply.toHex())
        assertEquals(Protocol.DISCOVER_REPLY_HEADER_BYTES + 7, reply.size)
    }

    @Test
    fun replyCarriesNonceAndNameAtAgreedOffsets() {
        val reply = Protocol.buildDiscoverReply(4242, "小米 15")
        val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(Protocol.STREAM_DISCOVERY, b.getInt(Protocol.STREAM_OFFSET))
        assertEquals(Protocol.DISCOVER_REPLY, b.getInt(Protocol.HEADER_BYTES))
        assertEquals(4242, b.getInt(Protocol.HEADER_BYTES + 4))

        val nameLen = b.getInt(Protocol.HEADER_BYTES + 8)
        val name = String(reply, Protocol.DISCOVER_REPLY_HEADER_BYTES, nameLen, Charsets.UTF_8)
        assertEquals("小米 15", name)
    }

    @Test
    fun longDeviceNameIsTruncatedOnCharBoundary() {
        // 30 个汉字 = 90 字节，超过 48 字节上限；必须截断且不能切坏 UTF-8
        val name = "手机".repeat(15)
        val reply = Protocol.buildDiscoverReply(1, name)
        val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        val nameLen = b.getInt(Protocol.HEADER_BYTES + 8)

        assertTrue("长度应被限制", nameLen <= Protocol.DISCOVER_NAME_MAX_BYTES)
        val decoded = String(reply, Protocol.DISCOVER_REPLY_HEADER_BYTES, nameLen, Charsets.UTF_8)
        assertTrue("截断后的字符串应仍是完整字符", decoded.all { it == '手' || it == '机' })
    }

    @Test
    fun audioPacketsAreNotMistakenForDiscovery() {
        // 下行音频包：stream = 0
        val audio = ByteBuffer.allocateDirect(Protocol.PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        audio.putInt(0, 1)
        audio.putInt(Protocol.STREAM_OFFSET, Protocol.STREAM_DOWNLINK)
        assertNull(Protocol.readDiscoverNonce(audio, Protocol.PACKET_BYTES))

        // 控制包：stream = 2
        val control = ByteBuffer.allocateDirect(Protocol.CONTROL_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        control.putInt(Protocol.STREAM_OFFSET, Protocol.STREAM_CONTROL)
        control.putInt(Protocol.HEADER_BYTES, Protocol.CONTROL_SET_GAIN)
        assertNull(Protocol.readDiscoverNonce(control, Protocol.CONTROL_PACKET_BYTES))
    }

    @Test
    fun truncatedRequestIsRejected() {
        val buffer = hexToBuffer(REQUEST_HEX)
        assertNull(Protocol.readDiscoverNonce(buffer, Protocol.DISCOVER_REQUEST_BYTES - 1))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
