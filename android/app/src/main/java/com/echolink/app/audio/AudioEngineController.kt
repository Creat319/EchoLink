package com.echolink.app.audio

import android.content.Context
import android.os.SystemClock
import com.echolink.app.EchoLinkStateHolder
import com.echolink.app.LinkPhase
import com.echolink.app.ReceiverSettings
import com.echolink.app.ReceiverState
import com.echolink.app.net.NetworkSnapshot
import com.echolink.app.net.UdpReceiver
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 接收端总控：native 引擎 + UDP 收包 + 控制闭环 + 看门狗。
 *
 * 线程模型：
 *  - UDP 收包在自己的线程（最高优先级），只做解析与写入环形缓冲；
 *  - 控制闭环与看门狗跑在同一个单线程 dispatcher 上（顺序执行，不用锁），20 Hz / 4 Hz；
 *  - 音频回调由 Oboe 驱动，只碰 native 的环形缓冲与原子量。
 */
class AudioEngineController(
    private val context: Context,
    private val onLogLine: (String) -> Unit = {},
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val latency = LatencyController()
    private val statsBuffer = DoubleArray(StatsIndex.COUNT)
    private val waterHistory = ArrayDeque<Float>(HISTORY_POINTS + 1)
    private val logLines = ArrayDeque<String>(MAX_LOG_LINES + 1)
    private val paramWarnings = mutableListOf<String>()

    @Volatile
    private var stats: EngineStats = EngineStats.EMPTY

    private var engine: NativeAudioEngine? = null
    private var receiver: UdpReceiver? = null
    private var routeMonitor: AudioRouteMonitor? = null
    private var controlJob: Job? = null
    private var watchdogJob: Job? = null

    private var settings = ReceiverSettings()
    private var startedAtMs = 0L
    private var lastUnderrunEvents = 0L
    private var lastUnderrunActionMs = 0L
    private var lastPrefillRequestMs = 0L
    private var bufferBursts = START_BUFFER_BURSTS
    private val waterMinWindow = ArrayDeque<Double>(WATER_WINDOW_TICKS + 1)
    private var lastCallbackCount = -1L
    private var lastCallbackAdvanceMs = 0L
    private var reopenCount = 0
    private var lastReopenReason: String? = null
    private var beepPromptVisible = false
    private var beepConfirmed: Boolean? = null
    private var firstPacketSeen = false
    private var reportSeq = 0
    private var tick = 0

    private var targetWaterMs = 30.0
    private var waterMinMs = 0.0
    private var jitterP99Ms = 0.0
    private var jitterP50Ms = 0.0
    private var controlWarm = false
    private var phase = LinkPhase.STOPPED

    val isRunning: Boolean get() = engine != null

    fun start(newSettings: ReceiverSettings) {
        if (engine != null) {
            return
        }

        settings = newSettings
        EchoLinkStateHolder.updateSettings(newSettings)
        phase = LinkPhase.LISTENING
        publish("正在打开音频设备…")

        scope.launch {
            val newEngine = NativeAudioEngine()
            engine = newEngine

            val startResult = newEngine.start(Protocol.SAMPLE_RATE, newSettings.requestExclusive)
            if (startResult != 0) {
                log("音频流开流失败，返回码 $startResult（继续尝试收包）")
            }

            newEngine.setTargetWaterFrames(Protocol.msToFrames(START_WATER_MS))
            newEngine.setResampleRatio(1.0)

            latency.reset()
            paramWarnings.clear()
            lastUnderrunEvents = 0
            lastUnderrunActionMs = 0
            lastPrefillRequestMs = 0
            bufferBursts = START_BUFFER_BURSTS
            waterMinWindow.clear()
            lastCallbackCount = -1
            firstPacketSeen = false
            waterHistory.clear()
            reopenCount = 0
            lastReopenReason = null
            beepPromptVisible = false

            val newReceiver = UdpReceiver(
                port = newSettings.port,
                externalFrameConsumer = { refFrameIndex, buffer, offset, frames ->
                    newEngine.push(refFrameIndex, buffer, offset, frames)
                },
                onJitterMicros = { micros -> latency.onJitter(micros) },
                reportPayload = { network -> buildReport(network) },
            )
            receiver = newReceiver
            runCatching { newReceiver.start() }
                .onFailure {
                    log("UDP 端口 ${newSettings.port} 打不开：${it.message}")
                    phase = LinkPhase.FAULT
                }

            startedAtMs = SystemClock.elapsedRealtime()
            lastCallbackAdvanceMs = startedAtMs

            routeMonitor = AudioRouteMonitor(context) { reason -> onRouteChanged(reason) }.also { it.start() }

            log("开始监听 UDP ${newSettings.port}，请求 ${if (newSettings.requestExclusive) "独占" else "共享"}模式")
            startLoops()
            publish("等待 PC 数据…")
        }
    }

    fun updateSettings(newSettings: ReceiverSettings) {
        val portChanged = newSettings.port != settings.port && receiver != null
        settings = newSettings
        if (portChanged) {
            log("端口变更需要重启接收：请先停止再启动")
        }
    }

    fun stop() {
        controlJob?.cancel()
        watchdogJob?.cancel()
        controlJob = null
        watchdogJob = null

        scope.launch {
            receiver?.close()
            receiver = null
            routeMonitor?.stop()
            routeMonitor = null
            engine?.stop()
            engine?.close()
            engine = null
            phase = LinkPhase.STOPPED
            log("已停止")
            publish("已停止")
        }
    }

    fun playTestBeep() {
        engine?.playBeep()
        log("播放测试音（1 kHz / 400 ms）")
    }

    fun confirmBeep(heard: Boolean) {
        beepConfirmed = heard
        beepPromptVisible = false
        log(if (heard) "用户确认听到测试音" else "用户表示没听到测试音：请检查媒体音量/输出设备/是否被重路由到蓝牙")
        publish(if (heard) "已确认出声" else "请注意：用户未确认听到测试音")
    }

    fun requestReopen(reason: String) {
        scope.launch { reopen(reason) }
    }

    private fun startLoops() {
        controlJob = scope.launch {
            while (isActive) {
                delay(CONTROL_INTERVAL_MS)
                runCatching { controlTick() }.onFailure { log("控制闭环异常：${it.message}") }
            }
        }

        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                runCatching { watchdogTick() }.onFailure { log("看门狗异常：${it.message}") }
            }
        }
    }

    private fun controlTick() {
        val activeEngine = engine ?: return
        activeEngine.readStats(statsBuffer)
        stats = EngineStats.from(statsBuffer)

        val underrunDelta = stats.underrunEvents - lastUnderrunEvents
        lastUnderrunEvents = stats.underrunEvents

        // 控制量：最近 1 秒内水位的最小值。
        // 原生的 kWaterMinFrames 覆盖“上次读取到现在”（≈50 ms，足以跨过多个发包批次），
        // 这里再取 20 个周期（1 s）的最小值，得到对锯齿低谷的稳健估计。
        waterMinWindow.addLast(stats.waterMinMs)
        while (waterMinWindow.size > WATER_WINDOW_TICKS) {
            waterMinWindow.removeFirst()
        }

        val windowMinMs = waterMinWindow.min()
        val nowMs = SystemClock.elapsedRealtime()

        // 目标水位模式允许运行中热切换；端口/独占模式需要重开流，只在 start 时读取
        val live = EchoLinkStateHolder.liveSettings
        val fixedTarget = if (live.autoTargetWater) null else live.manualTargetWaterMs.toDouble()

        val decision = latency.update(
            waterMinMs = windowMinMs,
            waterAvgMs = stats.waterMs,
            underrunDelta = underrunDelta,
            framesPerBurst = stats.framesPerBurst,
            bufferMs = stats.bufferMs,
            fixedTargetMs = fixedTarget,
        )

        activeEngine.setTargetWaterFrames(decision.targetFrames)
        activeEngine.setResampleRatio(decision.ratio)

        targetWaterMs = decision.targetMs
        jitterP99Ms = decision.jitterP99Ms
        jitterP50Ms = decision.jitterP50Ms
        waterMinMs = windowMinMs
        controlWarm = stats.prefillFrames > 0 && !stats.prefillActive

        if (underrunDelta > 0) {
            handleUnderrun(activeEngine, windowMinMs, decision.targetMs, nowMs)
        }

        pushWaterHistory(stats.waterMs)

        // 20 Hz 采样、10 Hz 发布，避免 Compose 过度重组
        tick++
        if (tick % 2 != 0) {
            return
        }

        val network = receiver?.snapshot ?: NetworkSnapshot()
        phase = when {
            stats.errorPending -> LinkPhase.REOPENING
            network.packetsReceived > 0 && network.lastArrivalAgeMs < 1000 -> LinkPhase.STREAMING
            else -> LinkPhase.LISTENING
        }

        publish(statusTextFor(phase, network))
    }

    /**
     * 欠载处理（去抖）。
     *
     * 原先每 50 ms 就记一次日志、并且靠重采样比率（τ≈5 s）慢慢把水位抬回来，
     * 结果一次持续故障会刷出几千条日志，而且恢复要十几秒。
     * 现在改为：1 秒内最多处理一次；水位掉到目标一半以下就直接重走垫水位（几十毫秒静音），
     * 否则按 burst 递增 Oboe 缓冲（LatencyTuner 式）。
     */
    private fun handleUnderrun(
        activeEngine: NativeAudioEngine,
        windowMinMs: Double,
        targetMs: Double,
        nowMs: Long,
    ) {
        if (nowMs - lastUnderrunActionMs < UNDERRUN_DEBOUNCE_MS) {
            return
        }

        lastUnderrunActionMs = nowMs

        if (windowMinMs < targetMs * 0.5) {
            if (nowMs - lastPrefillRequestMs >= UNDERRUN_DEBOUNCE_MS) {
                lastPrefillRequestMs = nowMs
                activeEngine.requestPrefill()
                log("欠载且水位低于目标一半（最小 ${"%.1f".format(windowMinMs)} ms），重新垫水位")
            }

            return
        }

        if (bufferBursts < MAX_BUFFER_BURSTS) {
            bufferBursts++
            val frames = activeEngine.setBufferSizeInBursts(bufferBursts)
            log("欠载，Oboe 缓冲上调到 $bufferBursts burst（实际 $frames 帧）")
        } else {
            log("欠载（水位最小 ${"%.1f".format(windowMinMs)} ms / 目标 ${"%.1f".format(targetMs)} ms），缓冲已达上限")
        }
    }

    private fun watchdogTick() {
        val activeEngine = engine ?: return
        val now = SystemClock.elapsedRealtime()

        // 1) 错误回调（onErrorAfterClose）——流已经 disconnected，必须重开
        if (stats.errorPending) {
            reopen("错误回调 onErrorAfterClose(code=${stats.lastErrorCode})")
            return
        }

        // 2) 回调停摆检测：运行中但数据回调不推进 → 判定流异常
        if (stats.callbackCount != lastCallbackCount) {
            lastCallbackCount = stats.callbackCount
            lastCallbackAdvanceMs = now
        } else if (now - lastCallbackAdvanceMs > STALL_THRESHOLD_MS) {
            reopen("数据回调停摆 ${now - lastCallbackAdvanceMs} ms")
            return
        }

        // 3) 开流后核对实际参数（只做一次）
        verifyStreamParameters()

        // 4) 首次收到数据：放测试音并请用户确认（发现“静默重路由”的唯一务实手段）
        val network = receiver?.snapshot ?: NetworkSnapshot()
        if (!firstPacketSeen && network.packetsReceived > 0) {
            firstPacketSeen = true
            beepPromptVisible = true
            activeEngine.playBeep()
            log("收到第一包数据，播放测试音请用户确认")
            publish(statusTextFor(LinkPhase.STREAMING, network))
        }
    }

    private fun reopen(reason: String) {
        val activeEngine = engine ?: return
        reopenCount++
        lastReopenReason = reason
        log("重开音频流（第 $reopenCount 次）：$reason")

        val result = activeEngine.reopen()
        if (result != 0) {
            log("重开失败，返回码 $result")
            phase = LinkPhase.FAULT
        } else {
            phase = LinkPhase.STREAMING
        }

        lastCallbackCount = -1
        lastCallbackAdvanceMs = SystemClock.elapsedRealtime()
        // 时间线重建：保留目标水位与比率（那是网络侧结论），但重新计时
        startedAtMs = SystemClock.elapsedRealtime()
        publish(statusTextFor(phase, receiver?.snapshot ?: NetworkSnapshot()))
    }

    private fun onRouteChanged(reason: String) {
        if (engine == null) {
            return
        }

        log("音频路由变化：$reason")
        scope.launch { reopen(reason) }
    }

    private fun verifyStreamParameters() {
        if (paramWarnings.isNotEmpty() || !stats.running) {
            return
        }

        if (stats.sampleRate != Protocol.SAMPLE_RATE) {
            paramWarnings += "实际采样率 ${stats.sampleRate} Hz（请求 48000，已按 ${"%.4f".format(stats.baseStep)} 倍率校正）"
        }

        if (stats.channels != Protocol.CHANNELS) {
            paramWarnings += "实际声道数 ${stats.channels}（请求 2）"
        }

        stats.exclusiveFailureText?.let { paramWarnings += it }

        if (stats.framesPerBurst <= 0) {
            paramWarnings += "拿不到 burst 大小"
        }

        // Oboe 缓冲必须能跨过 PC 端 10 ms 的成批发包间隔，否则会周期性欠载
        val minBufferMs = 15.0
        if (stats.bufferMs in 0.01..minBufferMs) {
            paramWarnings += "Oboe 缓冲仅 ${"%.1f".format(stats.bufferMs)} ms（目标 ≥4 burst / 20 ms），" +
                "可能周期性欠载（setBufferSizeInFrames 返回 ${stats.bufferSetResult}）"
        }

        if (stats.bufferSetResult != 0 && stats.bufferSetResult != OBOE_ERROR_UNIMPLEMENTED) {
            paramWarnings += "setBufferSizeInFrames 返回 ${stats.bufferSetResult}，缓冲未能调整"
        }

        if (stats.openAttempts > 2) {
            paramWarnings += "连共享模式都要 ${stats.openAttempts} 次尝试才成功，设备音频栈可能有问题"
        }

        paramWarnings.forEach { log("参数核对：$it") }
    }

    private fun buildReport(network: NetworkSnapshot): ByteArray {
        val current = stats
        return StatusReport.build(
            reportSeq = reportSeq++,
            framesPlayed = current.framesPlayed,
            packetsReceived = network.packetsReceived.toInt(),
            packetsLost = network.packetsLost.toInt(),
            underruns = current.underrunEvents.toInt(),
            waterMs = current.waterMs.toInt(),
            targetWaterMs = current.targetWaterMs.toInt(),
            jitterP99Us = network.jitterP99Us.toInt(),
            latencyMs = current.latencyMs.toInt(),
        )
    }

    private fun statusTextFor(phase: LinkPhase, network: NetworkSnapshot): String = when (phase) {
        LinkPhase.STOPPED -> "已停止"
        LinkPhase.LISTENING -> "等待 PC 数据（UDP ${settings.port}）"
        LinkPhase.STREAMING -> "流式中 · 水位 ${"%.1f".format(stats.waterMs)} ms"
        LinkPhase.REOPENING -> "正在重开音频流…"
        LinkPhase.FAULT -> "音频流故障：${lastReopenReason ?: "未知"}"
    }

    private fun pushWaterHistory(waterMs: Double) {
        if (waterHistory.size >= HISTORY_POINTS) {
            waterHistory.removeFirst()
        }

        waterHistory.addLast(waterMs.toFloat())
    }

    private fun publish(statusText: String) {
        val network = receiver?.snapshot ?: NetworkSnapshot()
        EchoLinkStateHolder.publish(
            ReceiverState(
                phase = phase,
                statusText = statusText,
                engine = stats,
                network = network,
                settings = EchoLinkStateHolder.liveSettings,
                targetWaterMs = targetWaterMs,
                waterMinMs = waterMinMs,
                jitterP99Ms = jitterP99Ms,
                jitterP50Ms = jitterP50Ms,
                controlWarm = controlWarm,
                bufferBursts = bufferBursts,
                reopenCount = reopenCount,
                lastReopenReason = lastReopenReason,
                paramWarnings = paramWarnings.toList(),
                localAddresses = NetworkInfo.localAddresses(),
                waterHistory = waterHistory.toList(),
                beepPromptVisible = beepPromptVisible,
                beepConfirmed = beepConfirmed,
                log = logLines.toList(),
            ),
        )
    }

    private fun log(message: String) {
        val line = "%s  %s".format(
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
            message,
        )
        if (logLines.size >= MAX_LOG_LINES) {
            logLines.removeFirst()
        }

        logLines.addLast(line)
        onLogLine(line)
        android.util.Log.i("EchoLink", message)
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private companion object {
        const val CONTROL_INTERVAL_MS = 50L
        const val WATCHDOG_INTERVAL_MS = 250L
        const val STALL_THRESHOLD_MS = 400L
        const val START_WATER_MS = 30.0
        const val HISTORY_POINTS = 400
        const val MAX_LOG_LINES = 120

        /** 控制量窗口：20 个 50 ms 周期 = 1 s */
        const val WATER_WINDOW_TICKS = 20

        /** 欠载处理去抖：1 秒内只处理一次 */
        const val UNDERRUN_DEBOUNCE_MS = 1_000L

        /** Oboe 缓冲起始 burst 数，以及允许上调到的上限 */
        const val START_BUFFER_BURSTS = 2
        const val MAX_BUFFER_BURSTS = 4

        /** oboe::Result::ErrorUnimplemented。独占（MMAP）下缓冲由驱动固定时返回，属正常情况。 */
        const val OBOE_ERROR_UNIMPLEMENTED = -898
    }
}
