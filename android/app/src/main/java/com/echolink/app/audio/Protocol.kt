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

    /** 流类型字段（协议 v3）。0 = 下行系统音频，1 = 上行麦克风，2 = 控制命令。 */
    const val STREAM_OFFSET = 16
    const val STREAM_DOWNLINK = 0
    const val STREAM_UPLINK = 1
    const val STREAM_CONTROL = 2

    /**
     * 控制包（PC → 手机）：复用 20 B 包头，载荷 8 B = [command:u32][value:f32]。
     * 与音频包靠 stream 字段区分，长度固定 28 B。
     */
    const val CONTROL_PAYLOAD_BYTES = 8
    const val CONTROL_PACKET_BYTES = HEADER_BYTES + CONTROL_PAYLOAD_BYTES   // 28
    const val CONTROL_SET_GAIN = 1

    /** 判定一个数据报是不是控制包：看 stream 字段 + 定长 28 B，双重确认。 */
    fun isControlDatagram(stream: Int, datagramBytes: Int): Boolean =
        stream == STREAM_CONTROL && datagramBytes == CONTROL_PACKET_BYTES

    // ---------------------------------------------------------------- 局域网发现
    //
    // 无线连接时，PC 往各网段广播地址发探测，手机从这里应答。
    // 见 PC 端 PacketFormat.StreamDiscovery 的说明。

    const val STREAM_DISCOVERY = 3
    const val DISCOVER_REQUEST = 1
    const val DISCOVER_REPLY = 2

    /** 应答里设备名的最大字节数（UTF-8）。 */
    const val DISCOVER_NAME_MAX_BYTES = 48

    const val DISCOVER_REQUEST_BYTES = HEADER_BYTES + 8
    const val DISCOVER_REPLY_HEADER_BYTES = HEADER_BYTES + 12

    /** 是发现请求就返回其中的 nonce，否则 null。 */
    fun readDiscoverNonce(buffer: ByteBuffer, datagramBytes: Int): Int? {
        if (datagramBytes < DISCOVER_REQUEST_BYTES || buffer.capacity() < DISCOVER_REQUEST_BYTES) {
            return null
        }

        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            if (buffer.getInt(STREAM_OFFSET) != STREAM_DISCOVERY) {
                null
            } else if (buffer.getInt(HEADER_BYTES) != DISCOVER_REQUEST) {
                null
            } else {
                buffer.getInt(HEADER_BYTES + 4)
            }
        } finally {
            buffer.order(order)
        }
    }

    /** 构造发现应答：[command][nonce][nameLen][name]。 */
    fun buildDiscoverReply(nonce: Int, name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val nameBytes = if (raw.size > DISCOVER_NAME_MAX_BYTES) raw.copyOf(DISCOVER_NAME_MAX_BYTES) else raw

        val out = ByteBuffer.allocate(DISCOVER_REPLY_HEADER_BYTES + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(nonce)                       // seq 位置放 nonce，方便对账
        out.putLong(0L)
        out.putInt(0)
        out.putInt(STREAM_DISCOVERY)
        out.putInt(DISCOVER_REPLY)
        out.putInt(nonce)
        out.putInt(nameBytes.size)
        out.put(nameBytes)
        return out.array()
    }

    fun controlGainFrom(buffer: ByteBuffer): Float? {
        if (buffer.capacity() < CONTROL_PACKET_BYTES) {
            return null
        }

        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val command = buffer.getInt(HEADER_BYTES)
            if (command != CONTROL_SET_GAIN) {
                null
            } else {
                buffer.getFloat(HEADER_BYTES + 4).coerceIn(0.0f, 2.0f)
            }
        } finally {
            buffer.order(order)
        }
    }

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
    // 4 = 认识控制包（PC → 手机音量）。PC 端据此决定要不要发控制包：
    // 旧版 APK 会把 28 B 控制包当成 2 帧音频播出来，所以版本必须卡住。
    const val VERSION = 4
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
