package com.echolink.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import kotlin.math.abs
import kotlin.math.max

/**
 * 上行麦克风（P0：打通链路，尚未接 AEC）。
 *
 * 采集 → 攒 10 ms（480 帧单声道）→ 打上行包 → UDP 发回 PC 的源端口。
 * 10 ms 分块是刻意的：WebRTC AEC 只吃 10 ms 块，接 AEC 时不需要改分包逻辑。
 *
 * **采集源不用 VOICE_COMMUNICATION**：那会打开平台的 AEC/AGC/NS，
 * 与我们自己的 AEC 叠加，既消不干净又会压人声。用 UNPROCESSED（设备不支持时退
 * VOICE_RECOGNITION），拿到的是尽量原始的麦克风信号。
 *
 * 半双工压低（P1）：下行电平高时把上行衰减约 20 dB。没有 AEC 时的保底手段，
 * 代价是对方说话时插不上话。
 */
class MicUplink(
    private val targetProvider: () -> InetSocketAddress?,
    private val onLog: (String) -> Unit,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var channel: DatagramChannel? = null

    private val packet = ByteBuffer.allocate(Protocol.HEADER_BYTES + Protocol.MIC_FRAMES_PER_PACKET * 2)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val block = ShortArray(Protocol.MIC_FRAMES_PER_PACKET)
    private var sequence = 0
    private var sampleCount = 0L
    private val startedAtNanos = System.nanoTime()

    @Volatile
    private var running = false

    @Volatile
    private var duckGain = 1.0f

    @Volatile
    var packetsSent = 0L
        private set

    @Volatile
    var sendErrors = 0L
        private set

    @Volatile
    var readErrors = 0L
        private set

    @Volatile
    var peakLevel = 0.0
        private set

    @Volatile
    var isRunning = false
        private set

    /** 实际使用的采集源（UNPROCESSED / VOICE_RECOGNITION），用于界面显示。 */
    var sourceLabel = "-"
        private set

    var openError: String? = null
        private set

    /** 下行电平高时由控制器调低（0.1 ≈ −20 dB）。 */
    fun setDuckGain(gain: Float) {
        duckGain = gain.coerceIn(0.05f, 1.0f)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRunning) {
            return true
        }

        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        var created: AudioRecord? = null
        var usedSource = -1
        for (source in candidateSources) {
            val minBytes = AudioRecord.getMinBufferSize(
                Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) {
                continue
            }

            val bufferBytes = max(minBytes, Protocol.MIC_FRAMES_PER_PACKET * 2 * 8)
            val candidate = try {
                AudioRecord(
                    source,
                    Protocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord($source) 创建失败: ${t.message}")
                null
            }

            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                created = candidate
                usedSource = source
                break
            }

            candidate?.release()
        }

        val active = created ?: run {
            openError = "无法初始化麦克风（可能未授予录音权限）"
            onLog("麦克风初始化失败：没有可用的采集源")
            return false
        }

        record = active
        sourceLabel = when (usedSource) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "MIC"
        }

        channel = try {
            DatagramChannel.open().apply { setOption(StandardSocketOptions.SO_SNDBUF, 256 * 1024) }
        } catch (t: Throwable) {
            openError = t.message
            onLog("麦克风发送通道创建失败：${t.message}")
            active.release()
            record = null
            return false
        }

        running = true
        try {
            active.startRecording()
        } catch (t: Throwable) {
            openError = t.message
            onLog("麦克风启动录音失败：${t.message}")
            closeAll()
            return false
        }

        if (active.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            openError = "麦克风未进入录音状态"
            onLog("麦克风未进入录音状态（可能被其它应用占用）")
            closeAll()
            return false
        }

        thread = Thread({ recordLoop(active) }, "EchoLink-Mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        isRunning = true
        onLog("麦克风已启动：48 kHz 单声道，源 $sourceLabel")
        return true
    }

    private fun recordLoop(active: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(Protocol.MIC_FRAMES_PER_PACKET)

        while (running) {
            val read = try {
                active.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                readErrors++
                Log.w(TAG, "麦克风读取异常: ${t.message}")
                break
            }

            if (read <= 0) {
                if (read < 0) {
                    readErrors++
                }

                continue
            }

            applyGainAndSend(chunk, read)
        }
    }

    private fun applyGainAndSend(chunk: ShortArray, frames: Int) {
        val gain = duckGain
        var peak = peakLevel
        var offset = 0

        while (offset < frames) {
            val take = minOf(Protocol.MIC_FRAMES_PER_PACKET - blockFrames, frames - offset)
            for (i in 0 until take) {
                val value = chunk[offset + i]
                val magnitude = abs(value.toInt())
                if (magnitude > peak) {
                    peak = magnitude.toDouble()
                }

                block[blockFrames + i] = (value * gain).toInt().coerceIn(-32768, 32767).toShort()
            }

            blockFrames += take
            offset += take

            if (blockFrames == Protocol.MIC_FRAMES_PER_PACKET) {
                sendBlock()
                blockFrames = 0
            }
        }

        peakLevel = peak
    }

    private var blockFrames = 0

    private fun sendBlock() {
        val target = targetProvider() ?: return
        val activeChannel = channel ?: return

        packet.clear()
        packet.putInt(sequence)
        packet.putLong(sampleCount)
        packet.putInt(((System.nanoTime() - startedAtNanos) / 1000L).toInt())
        packet.putInt(Protocol.STREAM_UPLINK)
        for (i in 0 until Protocol.MIC_FRAMES_PER_PACKET) {
            packet.putShort(block[i])
        }

        packet.flip()
        try {
            activeChannel.send(packet, target)
            packetsSent++
        } catch (t: Throwable) {
            sendErrors++
            Log.w(TAG, "上行发送失败: ${t.message}")
        }

        sequence++
        sampleCount += Protocol.MIC_FRAMES_PER_PACKET
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }

        thread = null
        closeAll()
        isRunning = false
        blockFrames = 0
    }

    private fun closeAll() {
        try {
            record?.stop()
        } catch (_: Throwable) {
        }

        try {
            record?.release()
        } catch (_: Throwable) {
        }

        record = null
        try {
            channel?.close()
        } catch (_: Throwable) {
        }

        channel = null
    }

    private companion object {
        const val TAG = "EchoLinkMic"
    }
}
