package com.echolink.app

import com.echolink.app.audio.EngineStats
import com.echolink.app.audio.Protocol
import com.echolink.app.net.NetworkSnapshot
import com.echolink.app.onboarding.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LinkPhase {
    STOPPED,
    LISTENING,
    STREAMING,
    REOPENING,
    FAULT,
}

data class ReceiverSettings(
    val port: Int = Protocol.DEFAULT_AUDIO_PORT,
    val requestExclusive: Boolean = true,
    val autoTargetWater: Boolean = true,
    val manualTargetWaterMs: Int = 30,
    val keepScreenOn: Boolean = false,

    /** 上行麦克风：把手机麦克风发回 PC 当麦克风用。 */
    // 默认开启：装了本应用就是要用它当麦克风，且权限会在首次启动时弹窗询问。
    // 不想要的人关一次即可（会持久化）。
    val micEnabled: Boolean = true,

    /** 界面语言（引导第一页可选，之后在设置里也能改）。 */
    val language: AppLanguage = AppLanguage.ZH_CN,
)

/** UI 唯一的状态源。由 AudioEngineController 覆盖写，Activity 只读。 */
data class ReceiverState(
    val phase: LinkPhase = LinkPhase.STOPPED,
    val statusText: String = "已停止",
    val engine: EngineStats = EngineStats.EMPTY,
    val network: NetworkSnapshot = NetworkSnapshot(),
    val settings: ReceiverSettings = ReceiverSettings(),
    val targetWaterMs: Double = 30.0,
    val waterMinMs: Double = 0.0,
    val jitterP99Ms: Double = 0.0,
    val jitterP50Ms: Double = 0.0,
    val controlWarm: Boolean = false,
    val bufferBursts: Int = 2,
    val reopenCount: Int = 0,
    val lastReopenReason: String? = null,
    val paramWarnings: List<String> = emptyList(),
    val localAddresses: List<String> = emptyList(),
    val waterHistory: List<Float> = emptyList(),
    val beepPromptVisible: Boolean = false,
    val beepConfirmed: Boolean? = null,
    val log: List<String> = emptyList(),

    // ---- 上行麦克风 ----
    val micRunning: Boolean = false,
    val micSource: String = "-",
    val micPackets: Long = 0,
    val micPeak: Double = 0.0,
    val micDuckGain: Float = 1.0f,
    val micError: String? = null,

    // ---- 版本自检 ----
    val updateCheckText: String = "",
    val latestVersion: String? = null,
) {
    val isRunning: Boolean get() = phase != LinkPhase.STOPPED
    val targetWaterFrames: Long get() = engine.targetWaterFrames
}

/**
 * 进程内的状态持有者：Service 写，Activity 读。
 * 用单例而不是 binder，是为了让 UI 在 Service 已经跑起来的情况下也能立刻拿到状态。
 */
object EchoLinkStateHolder {
    private val mutable = MutableStateFlow(ReceiverState())

    val state: StateFlow<ReceiverState> = mutable

    /** Activity 修改设置后写入；控制闭环每周期读取（只影响可热更的项：目标水位模式）。 */
    @Volatile
    var liveSettings: ReceiverSettings = ReceiverSettings()
        private set

    fun publish(state: ReceiverState) {
        mutable.value = state
    }

    fun updateSettings(settings: ReceiverSettings) {
        liveSettings = settings
        mutable.value = mutable.value.copy(settings = settings)
    }

    /** 版本自检结果（Activity 在 IO 线程查完写回）。 */
    fun setUpdate(text: String, latestVersion: String?) {
        mutable.value = mutable.value.copy(updateCheckText = text, latestVersion = latestVersion)
    }
}
