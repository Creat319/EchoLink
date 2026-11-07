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
    const val FRAMES_PER_PACKET = 240
    const val BYTES_PER_FRAME = CHANNELS * (BITS_PER_SAMPLE / 8)   // 4
    const val PAYLOAD_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME    // 960
    const val HEADER_BYTES = 16
    const val PACKET_BYTES = HEADER_BYTES + PAYLOAD_BYTES            // 976

    const val DEFAULT_AUDIO_PORT = 47810
    const val DEFAULT_REPORT_PORT = 47811

    const val PACKET_INTERVAL_MS = 5
    const val PACKETS_PER_SECOND = 200

    const val MICROS_PER_FRAME = 1_000_000.0 / SAMPLE_RATE

    fun framesToMs(frames: Long): Double = frames * 1000.0 / SAMPLE_RATE

    fun msToFrames(ms: Double): Int = (ms * SAMPLE_RATE / 1000.0).toInt()
}

/** 音频包头（16 B）：[seq:u32][sampleCount:u64][timestamp:u32]。 */
data class AudioPacketHeader(
    val sequence: Int,
    val sampleCount: Long,
    val timestampMicros: Int,
)

object AudioPacketCodec {
    /** 从 DirectByteBuffer 里按绝对偏移读包头，不改动 position/limit（收包线程无分配）。 */
    fun readHeader(buffer: ByteBuffer): AudioPacketHeader? {
        if (buffer.capacity() < Protocol.PACKET_BYTES) {
            return null
        }

        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            AudioPacketHeader(
                sequence = buffer.getInt(0),
                sampleCount = buffer.getLong(4),
                timestampMicros = buffer.getInt(12),
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
    const val VERSION = 1
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
