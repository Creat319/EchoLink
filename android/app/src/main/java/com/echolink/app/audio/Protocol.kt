package com.echolink.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 线格式常量。必须与 docs/protocol.md 和
 * pc/src/EchoLink.Core/Protocol/PacketFormat.cs 完全一致。
 */
object Protocol {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    /**
     * 单包最大帧数。**实际包长是可变的**：PC 端每次抓取回调发一个包，
     * 板载 2.67 ms 端点上是 128 帧，10 ms 端点上拆成 240 帧的整包。
     * 接收端必须按数据报长度推导帧数，不能用这个常量当定长。
     */
    const val FRAMES_PER_PACKET = 240
    const val BYTES_PER_FRAME = CHANNELS * (BITS_PER_SAMPLE / 8)   // 4
    const val PAYLOAD_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME    // 960（单包载荷上限）
    const val HEADER_BYTES = 20
    const val PACKET_BYTES = HEADER_BYTES + PAYLOAD_BYTES            // 980（最大包长）

    /** 流类型字段（协议 v3）。0 = 下行系统音频，1 = 上行麦克风。 */
    const val STREAM_OFFSET = 16
    const val STREAM_DOWNLINK = 0
    const val STREAM_UPLINK = 1

    /** 上行麦克风：单声道 48 kHz，10 ms = 480 帧 = 960 B（AEC 也要求 10 ms 块）。 */
    const val MIC_CHANNELS = 1
    const val MIC_BYTES_PER_FRAME = MIC_CHANNELS * (BITS_PER_SAMPLE / 8)   // 2
    const val MIC_FRAMES_PER_PACKET = 480

    const val DEFAULT_AUDIO_PORT = 47810
    const val DEFAULT_REPORT_PORT = 47811

    const val PACKET_INTERVAL_MS = 5
    const val PACKETS_PER_SECOND = 200

    const val MICROS_PER_FRAME = 1_000_000.0 / SAMPLE_RATE

    fun framesToMs(frames: Long): Double = frames * 1000.0 / SAMPLE_RATE

    fun msToFrames(ms: Double): Int = (ms * SAMPLE_RATE / 1000.0).toInt()

    /**
     * 从数据报长度推导本包帧数（包长可变，跟随 PC 端抓取粒度）。
     * 返回 -1 表示畸形：载荷不足一帧，或不是整帧。
     */
    fun framesInDatagram(datagramBytes: Int, bytesPerFrame: Int = BYTES_PER_FRAME): Int {
        val payload = datagramBytes - HEADER_BYTES
        if (payload <= 0 || bytesPerFrame <= 0 || payload % bytesPerFrame != 0) {
            return -1
        }

        val frames = payload / bytesPerFrame
        return if (frames > FRAMES_PER_PACKET) -1 else frames
    }
}

/** 音频包头（20 B）：[seq:u32][sampleCount:u64][timestamp:u32][stream:u32]。 */
data class AudioPacketHeader(
    val sequence: Int,
    val sampleCount: Long,
    val timestampMicros: Int,
    val stream: Int = Protocol.STREAM_DOWNLINK,
) {
    val isUplink: Boolean get() = stream == Protocol.STREAM_UPLINK
}

object AudioPacketCodec {
    /** 从 DirectByteBuffer 里按绝对偏移读包头，不改动 position/limit（收包线程无分配）。 */
    fun readHeader(buffer: ByteBuffer): AudioPacketHeader? {
        if (buffer.capacity() < Protocol.HEADER_BYTES) {
            return null
        }

        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            AudioPacketHeader(
                sequence = buffer.getInt(0),
                sampleCount = buffer.getLong(4),
                timestampMicros = buffer.getInt(12),
                stream = buffer.getInt(Protocol.STREAM_OFFSET),
            )
        } finally {
            buffer.order(order)
        }
    }
}

/** 状态回报包（手机 → PC，40 B）。 */
object StatusReport {
    const val SIZE = 40
    const val MAGIC = 0x50524C45
    const val VERSION = 3   // 3 = 上行麦克风（包头加 stream 字段，20 B）；见 docs/protocol.md §5
    const val TYPE_STATUS = 1

    fun build(
        reportSeq: Int,
        framesPlayed: Long,
        packetsReceived: Int,
        packetsLost: Int,
        underruns: Int,
        waterMs: Int,
        targetWaterMs: Int,
        jitterP99Us: Int,
        latencyMs: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(TYPE_STATUS.toShort())
        buffer.putInt(reportSeq)
        buffer.putLong(framesPlayed)
        buffer.putInt(packetsReceived)
        buffer.putInt(packetsLost)
        buffer.putInt(underruns)
        buffer.putShort(waterMs.coerceIn(0, 65535).toShort())
        buffer.putShort(targetWaterMs.coerceIn(0, 65535).toShort())
        buffer.putShort(jitterP99Us.coerceIn(0, 65535).toShort())
        buffer.putShort(latencyMs.coerceIn(0, 65535).toShort())
        return buffer.array()
    }
}
