package com.echolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.echolink.app.LinkPhase
import com.echolink.app.ReceiverSettings
import com.echolink.app.ReceiverState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoLinkApp(
    state: ReceiverState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestBeep: () -> Unit,
    onConfirmBeep: (Boolean) -> Unit,
    onReopen: () -> Unit,
    onSettingsChange: (ReceiverSettings) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("EchoLink · 手机当电脑音响", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HeaderCard(state, onStart, onStop, onTestBeep, onReopen) }

            if (state.beepPromptVisible) {
                item { BeepConfirmCard(onConfirmBeep) }
            }

            item { WaterCard(state) }
            item { StreamCard(state) }
            item { NetworkCard(state) }
            item { StabilityCard(state) }

            if (state.paramWarnings.isNotEmpty()) {
                item { WarningCard(state) }
            }

            item { AddressCard(state) }
            item { SettingsCard(state, onSettingsChange) }
            item { LogCard(state) }
        }
    }
}

@Composable
private fun phaseColor(phase: LinkPhase): Color = when (phase) {
    LinkPhase.STREAMING -> MaterialTheme.colorScheme.secondary
    LinkPhase.LISTENING -> MaterialTheme.colorScheme.tertiary
    LinkPhase.REOPENING -> MaterialTheme.colorScheme.primary
    LinkPhase.FAULT -> MaterialTheme.colorScheme.error
    LinkPhase.STOPPED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun HeaderCard(
    state: ReceiverState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestBeep: () -> Unit,
    onReopen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = when (state.phase) {
                        LinkPhase.STREAMING -> "流式中"
                        LinkPhase.LISTENING -> "等待数据"
                        LinkPhase.REOPENING -> "重开中"
                        LinkPhase.FAULT -> "故障"
                        LinkPhase.STOPPED -> "已停止"
                    },
                    color = phaseColor(state.phase),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%.1f", state.engine.waterMs),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                    color = phaseColor(state.phase),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "ms 水位 · 目标 ${String.format(Locale.US, "%.1f", state.targetWaterMs)} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (state.isRunning) onStop else onStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isRunning) "停止接收" else "开始接收")
                }

                OutlinedButton(onClick = onTestBeep, modifier = Modifier.weight(1f)) {
                    Text("测试音")
                }

                OutlinedButton(onClick = onReopen, enabled = state.isRunning) {
                    Text("重开流")
                }
            }
        }
    }
}

@Composable
private fun BeepConfirmCard(onConfirmBeep: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "刚才那声 1 kHz 提示音，你听到了吗？",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这是发现“被静默重路由到蓝牙/其他输出”的唯一务实手段——API 层检测不到，只能靠耳朵。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirmBeep(true) }, modifier = Modifier.weight(1f)) {
                    Text("听到了")
                }

                OutlinedButton(onClick = { onConfirmBeep(false) }, modifier = Modifier.weight(1f)) {
                    Text("没听到")
                }
            }
        }
    }
}

@Composable
private fun WaterCard(state: ReceiverState) {
    SectionCard(title = "水位闭环", accent = MaterialTheme.colorScheme.primary) {
        WaterSparkline(
            history = state.waterHistory,
            targetMs = state.targetWaterMs.toFloat(),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            "水位最低值",
            String.format(Locale.US, "%.2f ms", state.waterMinMs),
            valueColor = if (state.waterMinMs < state.targetWaterMs * 0.6 && state.targetWaterMs > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            hint = "闭环控制量",
        )
        MetricRow("当前水位", String.format(Locale.US, "%.2f ms", state.engine.waterMs))
        MetricRow("目标（低谷）", String.format(Locale.US, "%.2f ms", state.targetWaterMs))
        MetricRow(
            "重采样比率",
            String.format(Locale.US, "%+.4f %%", (state.engine.ratio - 1.0) * 100.0),
            hint = "限 ±0.1%",
        )
        MetricRow("链路抖动 P50", String.format(Locale.US, "%.2f ms", state.jitterP50Ms))
        MetricRow("链路抖动 P99", String.format(Locale.US, "%.2f ms", state.jitterP99Ms), hint = "仅展示")
    }
}

@Composable
private fun StreamCard(state: ReceiverState) {
    SectionCard(title = "播放链路（Oboe / AAudio）", accent = MaterialTheme.colorScheme.secondary) {
        MetricRow("采样率", "${state.engine.sampleRate} Hz")
        MetricRow("声道", "${state.engine.channels}")
        MetricRow("burst", "${state.engine.framesPerBurst} 帧")
        MetricRow(
            "Oboe 缓冲",
            "${state.engine.bufferSizeFrames} 帧 / ${String.format(Locale.US, "%.1f", state.engine.bufferMs)} ms",
            valueColor = if (state.bufferBursts <= 2) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            hint = "${state.bufferBursts} burst",
        )
        MetricRow(
            "回调帧数上限",
            if (state.engine.maxCallbackFrames > 0) "${state.engine.maxCallbackFrames} 帧" else "-",
            hint = "应 ≤ burst",
        )
        MetricRow(
            "共享模式",
            state.engine.sharingModeText,
            valueColor = if (state.engine.exclusive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
        )
        val exclusiveFailure = state.engine.exclusiveFailureText
        if (exclusiveFailure != null) {
            MetricRow("独占失败", exclusiveFailure, valueColor = MaterialTheme.colorScheme.tertiary)
        }
        MetricRow("开流尝试", "${state.engine.openAttempts} 次", hint = "独占 48k → 独占原生 → 共享")
        MetricRow("输出延迟", String.format(Locale.US, "%.2f ms", state.engine.latencyMs))
        MetricRow("开流代数", "${state.engine.streamGeneration}", hint = "重开次数 ${state.reopenCount}")
        MetricRow(
            "回调最大间隔",
            String.format(Locale.US, "%.2f ms", state.engine.maxCallbackGapMs),
            valueColor = if (state.engine.maxCallbackGapMs > 100) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (state.lastReopenReason != null) {
            MetricRow("最近重开原因", state.lastReopenReason)
        }
    }
}

@Composable
private fun NetworkCard(state: ReceiverState) {
    val net = state.network
    SectionCard(title = "网络（UDP）", accent = MaterialTheme.colorScheme.tertiary) {
        MetricRow("发送端", net.senderAddress ?: "尚未收到数据")
        MetricRow("收包", "${net.packetsReceived}")
        MetricRow("收包速率", String.format(Locale.US, "%.1f 包/秒", net.packetsPerSecond))
        MetricRow("丢包", "${net.packetsLost}", valueColor = if (net.packetsLost > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
        MetricRow("乱序", "${net.packetsOutOfOrder}")
        MetricRow("畸形包", "${net.packetsMalformed}", valueColor = if (net.packetsMalformed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        MetricRow("时间线跳变", "${net.sampleCountJumps}", hint = "发送端重启")
        MetricRow(
            "最后一包距今",
            if (net.lastArrivalAgeMs == Long.MAX_VALUE) "—" else "${net.lastArrivalAgeMs} ms",
        )
    }
}

@Composable
private fun StabilityCard(state: ReceiverState) {
    val e = state.engine
    SectionCard(title = "稳定性", accent = MaterialTheme.colorScheme.error) {
        MetricRow("欠载次数", "${e.underrunEvents}", valueColor = if (e.underrunEvents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        MetricRow("欠载补静音", "${e.underrunFrames} 帧")
        MetricRow("启动垫水位", "${e.prefillFrames} 帧", hint = "不计欠载")
        MetricRow("积压丢帧", "${e.dropEvents} 次 / ${e.dropFrames} 帧")
        MetricRow("缺包补静音", "${e.silenceFilledFrames} 帧")
        MetricRow("时间线重建", "${e.resyncEvents} 次")
        MetricRow("环缓冲溢出", "${e.overflowDrops} 帧")
        MetricRow("已播放", String.format(Locale.US, "%.1f s", e.framesPlayed / 48000.0))
        MetricRow("回调次数", "${e.callbackCount}")
    }
}

@Composable
private fun WarningCard(state: ReceiverState) {
    SectionCard(title = "参数核对", accent = MaterialTheme.colorScheme.error) {
        state.paramWarnings.forEach { warning ->
            Text(
                text = "• $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AddressCard(state: ReceiverState) {
    SectionCard(title = "本机地址（PC 端手动填这个）", accent = MaterialTheme.colorScheme.outline) {
        if (state.localAddresses.isEmpty()) {
            Text("未检测到可用 IPv4 地址", style = MaterialTheme.typography.bodySmall)
        } else {
            state.localAddresses.forEach { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "监听端口 ${state.settings.port}。USB 网络共享时，PC 端通常能自动发现网关地址，无需手填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(state: ReceiverState, onSettingsChange: (ReceiverSettings) -> Unit) {
    var portText by remember(state.settings.port) { mutableStateOf(state.settings.port.toString()) }
    var manualTarget by remember(state.settings.manualTargetWaterMs) {
        mutableStateOf(state.settings.manualTargetWaterMs.toFloat())
    }

    SectionCard(title = "设置", accent = MaterialTheme.colorScheme.outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("独占模式（拿不到会自动回退共享）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.requestExclusive,
                onCheckedChange = { onSettingsChange(state.settings.copy(requestExclusive = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动目标水位", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.autoTargetWater,
                onCheckedChange = { onSettingsChange(state.settings.copy(autoTargetWater = it)) },
            )
        }

        if (!state.settings.autoTargetWater) {
            Text(
                text = "手动目标：${manualTarget.toInt()} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = manualTarget,
                onValueChange = { manualTarget = it },
                valueRange = 10f..60f,
                steps = 49,
                onValueChangeFinished = {
                    onSettingsChange(state.settings.copy(manualTargetWaterMs = manualTarget.toInt()))
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { input ->
                portText = input.filter { it.isDigit() }.take(5)
                portText.toIntOrNull()?.let { port ->
                    if (port in 1024..65535) {
                        onSettingsChange(state.settings.copy(port = port))
                    }
                }
            },
            label = { Text("UDP 端口") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("保持屏幕常亮", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.keepScreenOn,
                onCheckedChange = { onSettingsChange(state.settings.copy(keepScreenOn = it)) },
            )
        }

        Text(
            text = "端口与独占模式的改动在下次“开始接收”时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LogCard(state: ReceiverState) {
    SectionCard(title = "日志（最近 ${state.log.size} 条）", accent = MaterialTheme.colorScheme.outline) {
        val tail = state.log.takeLast(14)
        if (tail.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodySmall)
        } else {
            Column {
                tail.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
