package com.echolink.app.bridge

import com.echolink.app.ReceiverState
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 Kotlin 侧的状态序列化成 JSON，供 Flutter UI 消费。
 *
 * 为什么要这一层：换 UI 框架时**音频链路一行都不动**，只在边界上做一次序列化。
 * 这样 Flutter 只负责画，出问题也只会是显示问题，不会影响播放。
 *
 * 约定：
 * - 字段名用 camelCase，与 Kotlin 属性名一致，减少两端对不上的机会
 * - 浮点统一保留原始精度，格式化交给 UI 层（不同语言的小数点/单位不同）
 * - 数组类字段（日志、水位轨迹）有长度上限，避免每帧传输过大
 */
object StateJson {

    /** 日志最多带这么多行给 UI（完整日志在原生侧另有上限）。 */
    private const val MaxLogLines = 60

    /** 水位轨迹点上限：UI 只画最近一小段。 */
    private const val MaxWaterHistory = 120

    fun encode(state: ReceiverState): String = JSONObject().apply {
        put("phase", state.phase.name)
        put("statusText", state.statusText)

        put("targetWaterMs", state.targetWaterMs)
        put("waterMinMs", state.waterMinMs)
        put("waterAvgMs", state.waterAvgMs)
        put("waterSessionMinMs", state.waterSessionMinMs)
        put("jitterP50Ms", state.jitterP50Ms)
        put("jitterP99Ms", state.jitterP99Ms)
        put("bufferBursts", state.bufferBursts)
        put("reopenCount", state.reopenCount)
        put("lastReopenReason", state.lastReopenReason ?: JSONObject.NULL)
        put("controlWarm", state.controlWarm)

        put("engine", encodeEngine(state))
        put("network", encodeNetwork(state))

        put("paramWarnings", JSONArray(state.paramWarnings))
        put("localAddresses", JSONArray(state.localAddresses))
        put("waterHistory", JSONArray(state.waterHistory.takeLast(MaxWaterHistory)))
        put("log", JSONArray(state.log.takeLast(MaxLogLines)))

        put("beepPromptVisible", state.beepPromptVisible)
        put("beepConfirmed", state.beepConfirmed ?: JSONObject.NULL)

        put("settings", JSONObject().apply {
            put("port", state.settings.port)
            put("requestExclusive", state.settings.requestExclusive)
            put("autoTargetWater", state.settings.autoTargetWater)
            put("manualTargetWaterMs", state.settings.manualTargetWaterMs)
            put("keepScreenOn", state.settings.keepScreenOn)
            put("language", state.settings.language.tag)
        })

        put("updateCheckText", state.updateCheckText)
        put("latestVersion", state.latestVersion ?: JSONObject.NULL)
    }.toString()

    private fun encodeEngine(state: ReceiverState): JSONObject {
        val e = state.engine
        return JSONObject().apply {
            put("waterMs", e.waterMs)
            put("targetWaterMs", e.targetWaterMs)
            put("waterMinMs", e.waterMinMs)
            put("bufferMs", e.bufferMs)
            put("sampleRate", e.sampleRate)
            put("channels", e.channels)
            put("framesPerBurst", e.framesPerBurst)
            put("bufferSizeFrames", e.bufferSizeFrames)
            put("latencyMs", e.latencyMs)
            put("exclusive", e.exclusive)
            put("exclusiveDeniedCode", e.exclusiveDeniedCode)
            put("openAttempts", e.openAttempts)
            put("streamGeneration", e.streamGeneration.toDouble())
            put("maxCallbackGapMs", e.maxCallbackGapMs)
            put("schedGapMaxMs", e.schedGapMaxMs)
            put("ratio", e.ratio)
            put("mmapSupported", e.mmapSupported)
            put("mmapEnabled", e.mmapEnabled)
            put("mmapUsed", e.mmapUsed)
            put("underrunEvents", e.underrunEvents.toDouble())
            put("underrunFrames", e.underrunFrames.toDouble())
            put("readStarveFrames", e.readStarveFrames.toDouble())
            put("xRunCount", e.xRunCount)
            put("silenceFilledFrames", e.silenceFilledFrames.toDouble())
            put("dropEvents", e.dropEvents.toDouble())
            put("dropFrames", e.dropFrames.toDouble())
            put("resyncEvents", e.resyncEvents.toDouble())
            put("overflowDrops", e.overflowDrops.toDouble())
            put("prefillFrames", e.prefillFrames.toDouble())
            put("framesPlayed", e.framesPlayed.toDouble())
            put("callbackCount", e.callbackCount.toDouble())
            put("maxCallbackFrames", e.maxCallbackFrames)
        }
    }

    private fun encodeNetwork(state: ReceiverState): JSONObject {
        val n = state.network
        return JSONObject().apply {
            put("packetsReceived", n.packetsReceived.toDouble())
            put("packetsLost", n.packetsLost.toDouble())
            put("packetsOutOfOrder", n.packetsOutOfOrder.toDouble())
            put("packetsMalformed", n.packetsMalformed.toDouble())
            put("packetsPerSecond", n.packetsPerSecond)
            put("jitterP50Us", n.jitterP50Us.toDouble())
            put("jitterP99Us", n.jitterP99Us.toDouble())
            put("senderAddress", n.senderAddress ?: JSONObject.NULL)
            put("senderRestarts", n.senderRestarts.toDouble())
            put("stallEvents", n.stallEvents.toDouble())
            put("stallMaxMs", n.stallMaxMs)
            put("catchUpEvents", n.catchUpEvents.toDouble())
            put("lastArrivalAgeMs", n.lastArrivalAgeMs.toDouble())
        }
    }
}
