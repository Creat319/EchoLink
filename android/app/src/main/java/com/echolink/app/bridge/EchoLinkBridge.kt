package com.echolink.app.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.echolink.app.AudioReceiverService
import com.echolink.app.EchoLinkStateHolder
import com.echolink.app.ReceiverSettings
import com.echolink.app.SettingsStore
import com.echolink.app.audio.Protocol
import com.echolink.app.onboarding.AppLanguage
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Flutter ↔ Kotlin 的桥。
 *
 * 设计原则：**音频链路一行都不动**。Flutter 只做两件事——
 * 订阅状态（EventChannel）、下命令（MethodChannel）。
 *
 * 状态每秒最多推 5 次：控制环本身 20 Hz，推太快只会让 UI 白掉帧。
 */
class EchoLinkBridge(private val context: Context) :
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    companion object {
        const val COMMAND_CHANNEL = "echolink/commands"
        const val STATE_CHANNEL = "echolink/state"

        /** 状态推送间隔：200 ms（5 Hz）。 */
        private const val PUSH_INTERVAL_MS = 200L
    }

    private val settingsStore = SettingsStore(context)
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pushJob: Job? = null
    private var eventSink: EventChannel.EventSink? = null

    // ---------------------------------------------------------------- 状态推送

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
        pushJob?.cancel()
        pushJob = scope.launch {
            while (true) {
                val state = EchoLinkStateHolder.state.value
                try {
                    events.success(StateJson.encode(state))
                } catch (t: Throwable) {
                    // sink 已经失效（Flutter 侧销毁）时不能让它把协程带崩
                    break
                }
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        pushJob?.cancel()
        pushJob = null
        eventSink = null
    }

    // ---------------------------------------------------------------- 命令

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> {
                    AudioReceiverService.start(context)
                    result.success(true)
                }

                "stop" -> {
                    AudioReceiverService.stop(context)
                    result.success(true)
                }

                "reopen" -> {
                    AudioReceiverService.reopen(context)
                    result.success(true)
                }

                "testBeep" -> {
                    AudioReceiverService.testBeep(context)
                    result.success(true)
                }

                "confirmBeep" -> {
                    AudioReceiverService.confirm(context, call.argument<Boolean>("heard") == true)
                    result.success(true)
                }

                "updateSettings" -> {
                    val updated = decodeSettings(call.arguments as? Map<*, *>)
                    settingsStore.save(updated)
                    EchoLinkStateHolder.updateSettings(updated)
                    result.success(true)
                }

                "getSettings" -> result.success(encodeSettings(settingsStore.load()))

                "getVersion" -> result.success(versionName())

                "onboardingDone" -> result.success(settingsStore.onboardingDone())

                "markOnboardingDone" -> {
                    settingsStore.markOnboardingDone()
                    result.success(true)
                }

                "clearOnboarding" -> {
                    // 供"重新查看引导"用：把标记清掉，UI 侧自己跳回引导
                    settingsStore.markOnboardingDone()
                    result.success(true)
                }

                "openUrl" -> {
                    val url = call.argument<String>("url")
                    if (url.isNullOrBlank()) {
                        result.error("bad_args", "url 为空", null)
                    } else {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        result.success(true)
                    }
                }

                "updateSkippedVersion" -> result.success(settingsStore.skippedUpdateVersion())

                "markUpdateSkipped" -> {
                    call.argument<String>("version")?.let { settingsStore.markUpdateSkipped(it) }
                    result.success(true)
                }

                "logNote" -> result.success(true)

                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            result.error("bridge_error", t.message, null)
        }
    }

    fun dispose() {
        pushJob?.cancel()
        scope.cancel()
        eventSink = null
    }

    // ---------------------------------------------------------------- 序列化

    private fun encodeSettings(settings: ReceiverSettings): Map<String, Any> = mapOf(
        "port" to settings.port,
        "requestExclusive" to settings.requestExclusive,
        "autoTargetWater" to settings.autoTargetWater,
        "manualTargetWaterMs" to settings.manualTargetWaterMs,
        "keepScreenOn" to settings.keepScreenOn,
        "language" to settings.language.tag,
    )

    private fun decodeSettings(raw: Map<*, *>?): ReceiverSettings {
        val current = settingsStore.load()
        if (raw == null) {
            return current
        }

        return ReceiverSettings(
            port = (raw["port"] as? Number)?.toInt() ?: current.port,
            requestExclusive = raw["requestExclusive"] as? Boolean ?: current.requestExclusive,
            autoTargetWater = raw["autoTargetWater"] as? Boolean ?: current.autoTargetWater,
            manualTargetWaterMs = (raw["manualTargetWaterMs"] as? Number)?.toInt()
                ?: current.manualTargetWaterMs,
            keepScreenOn = raw["keepScreenOn"] as? Boolean ?: current.keepScreenOn,
            micEnabled = current.micEnabled,
            language = AppLanguage.fromTag(raw["language"] as? String ?: current.language.tag),
        )
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"
}
