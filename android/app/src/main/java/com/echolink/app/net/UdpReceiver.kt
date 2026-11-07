package com.echolink.app.net

import com.echolink.app.audio.AudioPacketCodec
import com.echolink.app.audio.Protocol
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.DatagramChannel
import kotlin.math.abs

/** 收包侧的网络统计快照（不可变，可直接推给 UI）。 */
data class NetworkSnapshot(
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val packetsOutOfOrder: Long = 0,
    val packetsMalformed: Long = 0,
    val sampleCountJumps: Long = 0,
    val bytesReceived: Long = 0,
    val packetsPerSecond: Double = 0.0,
    val jitterP50Us: Long = 0,
    val jitterP99Us: Long = 0,
    val maxJitterUs: Long = 0,
    val lastArrivalAgeMs: Long = Long.MAX_VALUE,
    val senderAddress: String? = null,
    val senderRestarts: Long = 0,
)

/**
 * UDP 接收线程。
 *
 * - 用 DatagramChannel + DirectByteBuffer 直接收进 native 侧要读的那块内存，零拷贝；
 * - 线程优先级提到 AUDIO 级，减少调度抖动；
 * - 每个包计算单跳抖动 d_i = (到达间隔) - (采集时间戳间隔)，喂给 LatencyController；
 * - 每 500 ms 向发送端回一个 40 B 状态回报（丢了也不影响播放）。
 */
class UdpReceiver(
    private val port: Int,
    private val externalFrameConsumer: (refFrameIndex: Long, buffer: ByteBuffer, offsetBytes: Int, frames: Int) -> Unit,
    private val onJitterMicros: (Long) -> Unit,
    private val reportPayload: (NetworkSnapshot) -> ByteArray,
) : AutoCloseable {

    private val buffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
    private var channel: DatagramChannel? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var snapshot: NetworkSnapshot = NetworkSnapshot()
        private set

    private var lastSequence: Int? = null
    private var lastTimestampMicros: Int? = null
    private var lastArrivalNanos = 0L
    private var lastSampleCount: Long = -1

    private var packets = 0L
    private var lost = 0L
    private var outOfOrder = 0L
    private var malformed = 0L
    private var sampleJumps = 0L
    private var senderRestarts = 0L
    private var bytes = 0L
    private var jitterHistogram = LongArray(256)
    private var jitterMaxUs = 0L
    private var jitterSamples = 0L

    private var reportSeq = 0
    private var lastReportNanos = 0L
    private var lastRateNanos = 0L
    private var lastRatePackets = 0L
    private var lastSnapshotNanos = 0L
    private var lastArrivalNanosGlobal = 0L
    private var senderAddress: SocketAddress? = null
    private var discontinuityPending = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running) {
            return
        }

        val newChannel = DatagramChannel.open()
        newChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        newChannel.setOption(StandardSocketOptions.SO_RCVBUF, 1 shl 20)
        newChannel.bind(InetSocketAddress(port))
        channel = newChannel
        running = true

        thread = Thread({ receiveLoop(newChannel) }, "EchoLink-UdpRx").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun receiveLoop(activeChannel: DatagramChannel) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // 部分 ROM 限制设置，忽略
        }

        while (running) {
            val source: SocketAddress? = try {
                buffer.clear()
                activeChannel.receive(buffer)
            } catch (_: AsynchronousCloseException) {
                break
            } catch (_: Exception) {
                if (!running) break
                continue
            }

            if (source == null) {
                continue
            }

            val arrivalNanos = System.nanoTime()
            val length = buffer.position()
            bytes += length
            lastArrivalNanosGlobal = arrivalNanos

            if (source is InetSocketAddress) {
                senderAddress = source
            }

            if (length < Protocol.PACKET_BYTES) {
                malformed++
                publishIfDue(arrivalNanos)
                continue
            }

            val header = AudioPacketCodec.readHeader(buffer)
            if (header == null) {
                malformed++
                publishIfDue(arrivalNanos)
                continue
            }

            packets++
            trackContinuity(header)
            trackJitter(header, arrivalNanos)

            externalFrameConsumer(
                header.sampleCount,
                buffer,
                Protocol.HEADER_BYTES,
                Protocol.FRAMES_PER_PACKET,
            )

            maybeSendReport(activeChannel, arrivalNanos)
            publishIfDue(arrivalNanos)
        }
    }

    private fun trackContinuity(header: com.echolink.app.audio.AudioPacketHeader) {
        val previous = lastSequence
        if (previous != null) {
            val expected = previous + 1
            val delta = header.sequence - expected
            when {
                delta == 0 -> Unit
                delta > 0 && delta < 1000 -> lost += delta
                delta < 0 && delta > -1000 -> outOfOrder++
                else -> senderRestarts++
            }
        }

        if (lastSampleCount >= 0) {
            val expectedFrames = lastSampleCount + Protocol.FRAMES_PER_PACKET
            if (expectedFrames != header.sampleCount) {
                sampleJumps++
                discontinuityPending = true
            }
        }

        lastSequence = header.sequence
        lastSampleCount = header.sampleCount
    }

    private fun trackJitter(header: com.echolink.app.audio.AudioPacketHeader, arrivalNanos: Long) {
        val lastTs = lastTimestampMicros
        if (lastTs != null && lastArrivalNanos > 0) {
            // u32 微秒时间戳，做差分先处理回绕
            val captureDeltaUs = (header.timestampMicros - lastTs).toLong().let { raw ->
                val masked = raw and 0xFFFFFFFFL
                if (masked > 0x7FFFFFFFL) masked - 0x100000000L else masked
            }

            val arrivalDeltaUs = (arrivalNanos - lastArrivalNanos) / 1000L
            // 时间线不连续（发送端重启/大跳变）时，这一包的抖动没有意义
            if (discontinuityPending) {
                discontinuityPending = false
            } else {
                val jitter = arrivalDeltaUs - captureDeltaUs
                onJitterMicros(jitter)

                val magnitude = abs(jitter)
                if (magnitude > jitterMaxUs) {
                    jitterMaxUs = magnitude
                }

                if (jitterHistogram.isNotEmpty()) {
                    val bucket = (magnitude / 250L).toInt().coerceIn(0, jitterHistogram.size - 1)
                    jitterHistogram[bucket]++
                    jitterSamples++
                }
            }
        }

        lastTimestampMicros = header.timestampMicros
        lastArrivalNanos = arrivalNanos
    }

    private fun percentileUs(percentile: Double): Long {
        if (jitterSamples == 0L) {
            return 0
        }

        val target = (jitterSamples * percentile).toLong().coerceAtLeast(1)
        var cumulative = 0L
        for (bucket in jitterHistogram.indices) {
            cumulative += jitterHistogram[bucket]
            if (cumulative >= target) {
                return (bucket + 1) * 250L
            }
        }

        return jitterMaxUs
    }

    private fun publishIfDue(nowNanos: Long) {
        if (lastSnapshotNanos != 0L && nowNanos - lastSnapshotNanos < 100_000_000L) {
            return
        }

        lastSnapshotNanos = nowNanos
        val elapsed = if (lastRateNanos == 0L) 0.0 else (nowNanos - lastRateNanos) / 1e9
        val rate = if (elapsed > 0.0) (packets - lastRatePackets) / elapsed else 0.0
        lastRateNanos = nowNanos
        lastRatePackets = packets

        snapshot = NetworkSnapshot(
            packetsReceived = packets,
            packetsLost = lost,
            packetsOutOfOrder = outOfOrder,
            packetsMalformed = malformed,
            sampleCountJumps = sampleJumps,
            bytesReceived = bytes,
            packetsPerSecond = rate,
            jitterP50Us = percentileUs(0.50),
            jitterP99Us = percentileUs(0.99),
            maxJitterUs = jitterMaxUs,
            lastArrivalAgeMs = if (lastArrivalNanosGlobal == 0L) Long.MAX_VALUE else (nowNanos - lastArrivalNanosGlobal) / 1_000_000L,
            senderAddress = (senderAddress as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" },
            senderRestarts = senderRestarts,
        )
    }

    private fun maybeSendReport(activeChannel: DatagramChannel, nowNanos: Long) {
        val target = senderAddress ?: return
        if (lastReportNanos != 0L && nowNanos - lastReportNanos < 500_000_000L) {
            return
        }

        val payload = reportPayload(snapshot)
        try {
            activeChannel.send(ByteBuffer.wrap(payload), target)
            reportSeq++
        } catch (_: Exception) {
            // 回报只是诊断用途，失败无所谓
        }
    }

    override fun close() {
        running = false
        try {
            channel?.close()
        } catch (_: Exception) {
        }

        channel = null
        thread?.join(500)
        thread = null
    }
}
