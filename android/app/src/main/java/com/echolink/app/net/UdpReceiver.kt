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

    /** 下行电平峰值（0–1），用于半双工压低：扬声器响的时候把上行压下去。 */
    val downlinkPeak: Double = 0.0,

    /**
     * 成串停顿次数：到达间隔比采集间隔多出超过 [STALL_THRESHOLD_MS] 的次数。
     * 这是"WiFi 卡顿 / 热点不稳"最直接的证据——抖动直方图看不出来，
     * 因为一次 200 ms 的卡顿只贡献一个样本，P99 几乎不动。
     */
    val stallEvents: Long = 0,

    /** 最大一次停顿（毫秒）。 */
    val stallMaxMs: Double = 0.0,

    /** 追赶次数：到达间隔明显**小于**采集间隔（停顿后一次性涌来）。与停顿成对出现。 */
    val catchUpEvents: Long = 0,
)

/**
 * UDP 接收线程。
 *
 * - 用 DatagramChannel + DirectByteBuffer 直接收进 native 侧要读的那块内存，零拷贝；
 * - 线程优先级提到 AUDIO 级，减少调度抖动；
 * - 每个包计算单跳抖动 d_i = (到达间隔) - (采集时间戳间隔)，喂给 LatencyController；
 * - 每 500 ms 向发送端回一个 40 B 状态回报（丢了也不影响播放）。
 */
private const val STALL_THRESHOLD_MS = 25L
private const val CATCH_UP_THRESHOLD_MS = 10L

/** 单跳抖动（到达间隔 − 采集间隔）的分类。抽成纯函数是为了能单测阈值。 */
internal enum class ArrivalKind { NORMAL, STALL, CATCH_UP }

internal fun classifyArrival(jitterUs: Long): ArrivalKind = when {
    jitterUs > STALL_THRESHOLD_MS * 1000L -> ArrivalKind.STALL
    jitterUs < -CATCH_UP_THRESHOLD_MS * 1000L -> ArrivalKind.CATCH_UP
    else -> ArrivalKind.NORMAL
}

class UdpReceiver(
    private val port: Int,
    private val externalFrameConsumer: (refFrameIndex: Long, buffer: ByteBuffer, offsetBytes: Int, frames: Int) -> Unit,
    private val onJitterMicros: (Long) -> Unit,
    private val reportPayload: (NetworkSnapshot) -> ByteArray,
    /** 收到 PC 端音量控制包时回调（线性增益，1.0 = 原始音量）。 */
    private val onGainCommand: (Float) -> Unit = {},

    /** 回答局域网发现时上报的设备名。 */
    private val deviceNameProvider: () -> String = {
        android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android 手机"
    },
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
    private var lastFramesInPacket: Long = Protocol.FRAMES_PER_PACKET.toLong()

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

    /** 最近一次收到的音量增益（1.0 = 原始）。 */
    @Volatile
    var lastGain: Float = 1.0f
        private set
    private var lastRatePackets = 0L
    private var lastSnapshotNanos = 0L
    private var lastArrivalNanosGlobal = 0L
    private var senderAddress: SocketAddress? = null
    private var discontinuityPending = false

    /** 下行峰值电平（原始 int16 幅度，0–32768），用于半双工压低。 */
    @Volatile
    private var downlinkPeak = 0.0

    @Volatile
    private var stallEvents = 0L

    @Volatile
    private var stallMaxMs = 0.0

    @Volatile
    private var catchUpEvents = 0L

    /**
     * PC 的地址（下行包源地址 = PC 的 47811）。
     * 上行麦克风要往这里发，所以必须暴露出来。
     */
    fun serverEndpoint(): InetSocketAddress? = senderAddress as? InetSocketAddress

    /**
     * 用**接收这个 socket** 发上行数据。
     *
     * 为什么不能自己另开一个 socket：上行包如果从临时端口发出，到 PC 那边就是
     * "预期之外的入站 UDP"，Windows 防火墙会直接丢掉（回报包之所以能过，是因为它从
     * 47810 发出，和 PC 发过来的音频是同一条五元组，属于已建立会话的回包）。
     *
     * 复用同一个 socket 后，源端口固定 47810，和回报包走同一条五元组，
     * 不需要用户给应用加任何防火墙规则。
     *
     * [DatagramChannel] 允许一个线程 receive 的同时另一个线程 send，这里依赖这一点。
     */
    fun sendUplink(buffer: ByteBuffer, target: InetSocketAddress): Boolean {
        val activeChannel = channel ?: return false
        return try {
            activeChannel.send(buffer, target)
            true
        } catch (_: Exception) {
            false
        }
    }

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

            // 包长可变（跟随 PC 端抓取粒度）
            val framesInPacket = Protocol.framesInDatagram(length)
            if (framesInPacket <= 0) {
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

            // 局域网发现：PC 广播探测过来，我们单播回一个带设备名的应答。
            // 这是无线连接下 PC 自动找到手机的唯一途径。
            if (header.stream == Protocol.STREAM_DISCOVERY) {
                Protocol.readDiscoverNonce(buffer, length)?.let { nonce ->
                    replyToDiscovery(activeChannel, source, nonce)
                }
                publishIfDue(arrivalNanos)
                continue
            }

            // 控制包（PC → 手机音量）：不是音频，不参与丢包/抖动/时间线统计
            if (header.stream == Protocol.STREAM_CONTROL) {
                if (Protocol.isControlDatagram(header.stream, length)) {
                    Protocol.controlGainFrom(buffer)?.let { gain ->
                        lastGain = gain
                        onGainCommand(gain)
                    }
                } else {
                    malformed++
                }
                maybeSendReport(activeChannel, arrivalNanos)
                publishIfDue(arrivalNanos)
                continue
            }

            packets++
            trackContinuity(header, framesInPacket)
            trackJitter(header, arrivalNanos)

            externalFrameConsumer(
                header.sampleCount,
                buffer,
                Protocol.HEADER_BYTES,
                framesInPacket,
            )

            // 下行电平（每 8 个采样取一个，热路径上足够且几乎不占 CPU）：
            // 半双工压低靠它判断"扬声器现在有没有在响"
            var peak = downlinkPeak
            var i = 0
            val samples = framesInPacket * Protocol.CHANNELS
            while (i < samples) {
                val value = buffer.getShort(Protocol.HEADER_BYTES + (i * 2)).toInt()
                val magnitude = kotlin.math.abs(value)
                if (magnitude > peak) {
                    peak = magnitude.toDouble()
                }

                i += 8
            }

            downlinkPeak = peak

            maybeSendReport(activeChannel, arrivalNanos)
            publishIfDue(arrivalNanos)
        }
    }

    /**
     * 回应局域网发现探测。
     *
     * 必须从**接收这个 socket** 发出去（和上行麦克风同样的理由）：
     * 源端口固定 47810，PC 侧才可能把它当成自己那条流的回程放行。
     */
    private fun replyToDiscovery(
        activeChannel: DatagramChannel,
        source: SocketAddress,
        nonce: Int,
    ) {
        val name = deviceNameProvider()
        val payload = Protocol.buildDiscoverReply(nonce, name)
        try {
            activeChannel.send(ByteBuffer.wrap(payload), source)
            discoveryReplies++
        } catch (_: Exception) {
            // 发现是尽力而为，失败不影响音频
        }
    }

    /** 应答过的发现请求次数（界面可用来确认"PC 确实找过我"）。 */
    @Volatile
    var discoveryReplies = 0L
        private set

    private fun trackContinuity(header: com.echolink.app.audio.AudioPacketHeader, framesInPacket: Int) {        val previous = lastSequence
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
            val expectedFrames = lastSampleCount + lastFramesInPacket
            if (expectedFrames != header.sampleCount) {
                sampleJumps++
                discontinuityPending = true
            }
        }

        lastFramesInPacket = framesInPacket.toLong()

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

                // 成串停顿 / 追赶：这两个才是"网络卡顿"的直接证据。
                // 抖动直方图会把一次 200 ms 卡顿摊进 P99 里，几乎看不出来。
                when (classifyArrival(jitter)) {
                    ArrivalKind.STALL -> {
                        stallEvents++
                        val stallMs = jitter / 1000.0
                        if (stallMs > stallMaxMs) {
                            stallMaxMs = stallMs
                        }
                    }

                    ArrivalKind.CATCH_UP -> catchUpEvents++
                    ArrivalKind.NORMAL -> Unit
                }

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
            downlinkPeak = (downlinkPeak / 32768.0).coerceIn(0.0, 1.0),
            stallEvents = stallEvents,
            stallMaxMs = stallMaxMs,
            catchUpEvents = catchUpEvents,
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
