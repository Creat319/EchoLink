package com.echolink.app.audio

import android.media.MediaRecorder

/**
 * 麦克风采集源的选取策略与处理能力判断。
 *
 * 单独抽出来是因为这部分决定"有没有回声消除"，而它是这个功能能不能用的分水岭：
 * 之前刻意避开 `VOICE_COMMUNICATION`（怕平台的 AEC/AGC/NS 和"我们自己的 AEC"叠加），
 * 但我们自己的 AEC 一直没做——结果就是既没有回声消除、又只能用半双工压低兜底。
 *
 * 现在的结论：**EchoLink 的回声源是手机自己扬声器在放我们下发的音频**，
 * 平台 AEC 正好就是干这件事的，直接用比自研靠谱得多。
 *
 * 注意：`AcousticEchoCanceler` 只有在 `VOICE_COMMUNICATION` 这类语音源下才真正生效，
 * 挂在 `UNPROCESSED`/`MIC` 上多数机型会返回"创建成功但不工作"，所以这里必须
 * 按采集源判断，不能只看 effect 是否创建成功。
 */
data class MicProcessing(
    /** 是否走语音链路（VOICE_COMMUNICATION + 平台 AEC）。关掉就退回原始信号。 */
    val voiceMode: Boolean = true,
    /** 声学回声消除。只有语音链路下才有意义。 */
    val aec: Boolean = true,
    /** 降噪。 */
    val ns: Boolean = true,
    /** 自动增益。默认关：它会把底噪一起抬起来，通话软件通常自己也有 AGC。 */
    val agc: Boolean = false,
)

internal object MicSourcePolicy {

    /** 语音链路：优先 VOICE_COMMUNICATION（AEC 的宿主）。 */
    fun voiceCandidates(): IntArray = intArrayOf(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
    )

    /** 原始链路：优先 UNPROCESSED（不经任何平台处理）。 */
    fun rawCandidates(): IntArray = intArrayOf(
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
    )

    fun candidates(voiceMode: Boolean): IntArray =
        if (voiceMode) voiceCandidates() else rawCandidates()

    fun label(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SOURCE_$source"
    }

    /**
     * 这个采集源上平台 AEC 才可能真正生效。
     * 只有语音源会走平台的语音处理链，挂在其它源上的 AEC 实例往往是空转。
     */
    fun supportsPlatformAec(source: Int): Boolean =
        source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
}
