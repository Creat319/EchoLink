package com.echolink.app.audio

import java.nio.ByteBuffer

/**
 * JNI 封装。native 侧实现见 app/src/main/cpp/src/audio_engine.{h,cpp}。
 *
 * 回调内铁律（不分配、不加锁、不做 IO）由 C++ 侧保证；
 * 这个类只在控制线程 / UDP 线程上被调用。
 */
class NativeAudioEngine : AutoCloseable {

    private var handle: Long = nativeCreate()

    val isAlive: Boolean get() = handle != 0L

    fun start(sampleRate: Int = Protocol.SAMPLE_RATE, exclusive: Boolean = true): Int =
        nativeStart(handle, sampleRate, exclusive)

    fun stop(): Int = nativeStop(handle)

    /** 设备路由变化 / onErrorAfterClose / 回调停摆后重开流。 */
    fun reopen(): Int = nativeReopen(handle)

    /** 把一包 PCM 写入环形缓冲。buffer 是 DirectByteBuffer，offsetBytes 指向载荷起点。 */
    fun push(refFrameIndex: Long, buffer: ByteBuffer, offsetBytes: Int = Protocol.HEADER_BYTES, frames: Int = Protocol.FRAMES_PER_PACKET) {
        nativePush(handle, refFrameIndex, buffer, offsetBytes, frames)
    }

    fun setTargetWaterFrames(frames: Int) = nativeSetTargetWaterFrames(handle, frames)

    fun targetWaterFrames(): Int = nativeGetTargetWaterFrames(handle)

    fun setResampleRatio(ratio: Double) = nativeSetResampleRatio(handle, ratio)

    fun resampleRatio(): Double = nativeGetResampleRatio(handle)

    fun playBeep() = nativePlayBeep(handle)

    fun resetRing() = nativeResetRing(handle)

    /** 读取统计。传入预分配的数组，避免每帧 GC。 */
    fun readStats(into: DoubleArray) {
        if (into.size < StatsIndex.COUNT) {
            throw IllegalArgumentException("stats array too small: ${into.size} < ${StatsIndex.COUNT}")
        }

        nativeGetStats(handle, into)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeStart(handle: Long, sampleRate: Int, exclusive: Boolean): Int

    private external fun nativeStop(handle: Long): Int

    private external fun nativeReopen(handle: Long): Int

    private external fun nativePush(
        handle: Long,
        refFrameIndex: Long,
        buffer: ByteBuffer,
        offsetBytes: Int,
        frames: Int,
    )

    private external fun nativeSetTargetWaterFrames(handle: Long, frames: Int)

    private external fun nativeGetTargetWaterFrames(handle: Long): Int

    private external fun nativeSetResampleRatio(handle: Long, ratio: Double)

    private external fun nativeGetResampleRatio(handle: Long): Double

    private external fun nativePlayBeep(handle: Long)

    private external fun nativeResetRing(handle: Long)

    private external fun nativeGetStats(handle: Long, out: DoubleArray)

    companion object {
        init {
            System.loadLibrary("echolink_audio")
        }
    }
}

/** nativeGetStats 的下标，必须与 C++ 的 echolink::StatsIndex 一致。 */
object StatsIndex {
    const val WATER_FRAMES = 0
    const val TARGET_WATER_FRAMES = 1
    const val FRAMES_PLAYED = 2
    const val UNDERRUN_EVENTS = 3
    const val UNDERRUN_FRAMES = 4
    const val DROP_EVENTS = 5
    const val DROP_FRAMES = 6
    const val SILENCE_FILLED = 7
    const val RESYNC_EVENTS = 8
    const val SAMPLE_RATE = 9
    const val CHANNELS = 10
    const val FRAMES_PER_BURST = 11
    const val SHARING_MODE = 12
    const val LATENCY_MS = 13
    const val STREAM_GENERATION = 14
    const val ERROR_FLAG = 15
    const val LAST_ERROR_CODE = 16
    const val CALLBACK_COUNT = 17
    const val MAX_CALLBACK_GAP_MS = 18
    const val RATIO = 19
    const val WRITE_HEAD = 20
    const val RING_CAPACITY = 21
    const val OVERFLOW_DROPS = 22
    const val STARTED = 23
    const val BASE_STEP = 24
    const val OPEN_RESULT = 25
    const val BUFFER_SIZE_FRAMES = 26
    const val BUFFER_CAPACITY_FRAMES = 27
    const val REQUESTED_EXCLUSIVE = 28
    const val BUFFER_SET_RESULT = 29
    const val OPEN_ATTEMPTS = 30
    const val EXCLUSIVE_DENIED_CODE = 31
    const val PREFILL_FRAMES = 32
    const val COUNT = 40
}

/** 一次 native 统计快照。</summary> */
data class EngineStats(
    val waterFrames: Long,
    val targetWaterFrames: Long,
    val framesPlayed: Long,
    val underrunEvents: Long,
    val underrunFrames: Long,
    val dropEvents: Long,
    val dropFrames: Long,
    val silenceFilledFrames: Long,
    val resyncEvents: Long,
    val sampleRate: Int,
    val channels: Int,
    val framesPerBurst: Int,
    val exclusive: Boolean,
    val latencyMs: Double,
    val streamGeneration: Long,
    val errorPending: Boolean,
    val lastErrorCode: Int,
    val callbackCount: Long,
    val maxCallbackGapMs: Double,
    val ratio: Double,
    val writeHead: Long,
    val ringCapacityFrames: Int,
    val overflowDrops: Long,
    val running: Boolean,
    val baseStep: Double,
    val openResult: Int,
    val bufferSizeFrames: Int,
    val bufferCapacityFrames: Int,
    val requestedExclusive: Boolean,
    val bufferSetResult: Int,
    val openAttempts: Int,
    val exclusiveDeniedCode: Int,
    val prefillFrames: Long,
) {
    val waterMs: Double get() = waterFrames * 1000.0 / Protocol.SAMPLE_RATE
    val targetWaterMs: Double get() = targetWaterFrames * 1000.0 / Protocol.SAMPLE_RATE

    /** Oboe 内部缓冲的实际时长：必须能跨过 PC 端 10 ms 的成批发包间隔。 */
    val bufferMs: Double
        get() = if (sampleRate > 0) bufferSizeFrames * 1000.0 / sampleRate else 0.0

    /** 独占请求了但没拿到时，返回失败码文案；拿到了返回 null。 */
    val exclusiveFailureText: String?
        get() = when {
            !requestedExclusive -> null
            exclusive -> null
            exclusiveDeniedCode != 0 -> "独占失败 code=$exclusiveDeniedCode，已回退共享"
            else -> "未拿到独占，已回退共享"
        }

    val sharingModeText: String get() = if (exclusive) "独占 (Exclusive)" else "共享 (Shared)"

    companion object {
        val EMPTY = EngineStats(
            waterFrames = 0,
            targetWaterFrames = 0,
            framesPlayed = 0,
            underrunEvents = 0,
            underrunFrames = 0,
            dropEvents = 0,
            dropFrames = 0,
            silenceFilledFrames = 0,
            resyncEvents = 0,
            sampleRate = Protocol.SAMPLE_RATE,
            channels = Protocol.CHANNELS,
            framesPerBurst = 0,
            exclusive = false,
            latencyMs = 0.0,
            streamGeneration = 0,
            errorPending = false,
            lastErrorCode = 0,
            callbackCount = 0,
            maxCallbackGapMs = 0.0,
            ratio = 1.0,
            writeHead = 0,
            ringCapacityFrames = 0,
            overflowDrops = 0,
            running = false,
            baseStep = 1.0,
            openResult = 0,
            bufferSizeFrames = 0,
            bufferCapacityFrames = 0,
            requestedExclusive = false,
            bufferSetResult = 0,
            openAttempts = 0,
            exclusiveDeniedCode = 0,
            prefillFrames = 0,
        )

        fun from(values: DoubleArray): EngineStats = EngineStats(
            waterFrames = values[StatsIndex.WATER_FRAMES].toLong(),
            targetWaterFrames = values[StatsIndex.TARGET_WATER_FRAMES].toLong(),
            framesPlayed = values[StatsIndex.FRAMES_PLAYED].toLong(),
            underrunEvents = values[StatsIndex.UNDERRUN_EVENTS].toLong(),
            underrunFrames = values[StatsIndex.UNDERRUN_FRAMES].toLong(),
            dropEvents = values[StatsIndex.DROP_EVENTS].toLong(),
            dropFrames = values[StatsIndex.DROP_FRAMES].toLong(),
            silenceFilledFrames = values[StatsIndex.SILENCE_FILLED].toLong(),
            resyncEvents = values[StatsIndex.RESYNC_EVENTS].toLong(),
            sampleRate = values[StatsIndex.SAMPLE_RATE].toInt(),
            channels = values[StatsIndex.CHANNELS].toInt(),
            framesPerBurst = values[StatsIndex.FRAMES_PER_BURST].toInt(),
            exclusive = values[StatsIndex.SHARING_MODE].toInt() == 0,
            latencyMs = values[StatsIndex.LATENCY_MS],
            streamGeneration = values[StatsIndex.STREAM_GENERATION].toLong(),
            errorPending = values[StatsIndex.ERROR_FLAG].toInt() != 0,
            lastErrorCode = values[StatsIndex.LAST_ERROR_CODE].toInt(),
            callbackCount = values[StatsIndex.CALLBACK_COUNT].toLong(),
            maxCallbackGapMs = values[StatsIndex.MAX_CALLBACK_GAP_MS],
            ratio = values[StatsIndex.RATIO],
            writeHead = values[StatsIndex.WRITE_HEAD].toLong(),
            ringCapacityFrames = values[StatsIndex.RING_CAPACITY].toInt(),
            overflowDrops = values[StatsIndex.OVERFLOW_DROPS].toLong(),
            running = values[StatsIndex.STARTED].toInt() != 0,
            baseStep = values[StatsIndex.BASE_STEP],
            openResult = values[StatsIndex.OPEN_RESULT].toInt(),
            bufferSizeFrames = values[StatsIndex.BUFFER_SIZE_FRAMES].toInt(),
            bufferCapacityFrames = values[StatsIndex.BUFFER_CAPACITY_FRAMES].toInt(),
            requestedExclusive = values[StatsIndex.REQUESTED_EXCLUSIVE].toInt() != 0,
            bufferSetResult = values[StatsIndex.BUFFER_SET_RESULT].toInt(),
            openAttempts = values[StatsIndex.OPEN_ATTEMPTS].toInt(),
            exclusiveDeniedCode = values[StatsIndex.EXCLUSIVE_DENIED_CODE].toInt(),
            prefillFrames = values[StatsIndex.PREFILL_FRAMES].toLong(),
        )
    }
}
