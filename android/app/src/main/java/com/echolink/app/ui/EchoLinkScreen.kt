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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.echolink.app.onboarding.stringsFor
import com.echolink.app.i18n.Tr
import com.echolink.app.onboarding.LocalStrings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.echolink.app.LinkPhase
import com.echolink.app.FeatureFlags
import com.echolink.app.ReceiverSettings
import com.echolink.app.ReceiverState
import java.util.Locale

/**
 * 发现新版本时的弹窗。
 *
 * 三个出口，分别对应三种用户意图：
 * - 立即下载：走浏览器（APK 的安装仍由系统确认，应用不静默安装）
 * - 稍后：这次先不更新，下次启动还会提醒
 * - 不再提示此版本：记住**这个版本号**，同一个版本不再弹；发新版仍会弹
 */
@Composable
fun UpdateAvailableDialog(
    version: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    val context = LocalContext.current
    val currentVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
    }

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(Tr.newVersionTitle(version)) },
        text = {
            Column {
                Text(Tr.currentVersionLine(currentVersion))
                Spacer(Modifier.height(8.dp))
                Text(
                    Tr.t("更新包会用浏览器下载，下载完点开安装即可覆盖，设置和数据都不会丢。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDownload) { Text(Tr.t("立即下载")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLater) { Text(Tr.t("稍后")) }
                TextButton(onClick = onSkipVersion) { Text(Tr.t("不再提示此版本")) }
            }
        },
    )
}

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
    onCheckUpdate: () -> Unit,
    onOpenDownload: () -> Unit,
    updateDialogVersion: String? = null,
    onDismissUpdateDialog: () -> Unit = {},
    onSkipUpdateVersion: () -> Unit = {},
    onReplayGuide: () -> Unit = {},
) {
    val strings = remember(state.settings.language) { stringsFor(state.settings.language) }

    // 底部导航：连接 / 设置 / 日志。之前所有卡片堆在一页、要滚很久，信息密度也太高。
    var tab by rememberSaveable { mutableStateOf(0) }

    CompositionLocalProvider(LocalStrings provides strings) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("EchoLink", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = strings.appTitle + " · " + state.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text(strings.tabHome) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(strings.tabSettings) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(strings.tabLog) },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                0 -> HomeTab(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onTestBeep = onTestBeep,
                    onConfirmBeep = onConfirmBeep,
                    onReopen = onReopen,
                )

                1 -> SettingsTab(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onSettingsChange = onSettingsChange,
                    onReplayGuide = onReplayGuide,
                    onCheckUpdate = onCheckUpdate,
                    onOpenDownload = onOpenDownload,
                )

                else -> LogTab(modifier = Modifier.padding(padding), state = state)
            }
        }

        // 弹窗挂在 Scaffold 外面：它不参与滚动，检测到就浮在最上层
        if (updateDialogVersion != null) {
            UpdateAvailableDialog(
                version = updateDialogVersion,
                onDownload = {
                    onDismissUpdateDialog()
                    onOpenDownload()
                },
                onLater = onDismissUpdateDialog,
                onSkipVersion = onSkipUpdateVersion,
            )
        }
    }
}

/** 连接：状态、水位、播放链路、网络、稳定性。 */
@Composable
private fun HomeTab(
    modifier: Modifier,
    state: ReceiverState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestBeep: () -> Unit,
    onConfirmBeep: (Boolean) -> Unit,
    onReopen: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
    }
}

/** 设置：地址、端口与各项开关、版本。 */
@Composable
private fun SettingsTab(
    modifier: Modifier,
    state: ReceiverState,
    onSettingsChange: (ReceiverSettings) -> Unit,
    onReplayGuide: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenDownload: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { AddressCard(state) }
        item { SettingsCard(state, onSettingsChange, onReplayGuide) }
        item { UpdateCard(state, onCheckUpdate, onOpenDownload) }
    }
}

/** 日志：单独一页，别和指标抢地方。 */
@Composable
private fun LogTab(modifier: Modifier, state: ReceiverState) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { LogCard(state) }
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
                        LinkPhase.STREAMING -> Tr.t("流式中")
                        LinkPhase.LISTENING -> Tr.t("等待数据")
                        LinkPhase.REOPENING -> Tr.t("重开中")
                        LinkPhase.FAULT -> Tr.t("故障")
                        LinkPhase.STOPPED -> Tr.t("已停止")
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
                    text = Tr.waterSummary(String.format(Locale.US, "%.1f", state.engine.waterMs), String.format(Locale.US, "%.1f", state.targetWaterMs)),
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
                    Text(if (state.isRunning) LocalStrings.current.btnStop else LocalStrings.current.btnStartReceive)
                }

                OutlinedButton(onClick = onTestBeep, modifier = Modifier.weight(1f)) {
                    Text(Tr.t("测试音"))
                }

                OutlinedButton(onClick = onReopen, enabled = state.isRunning) {
                    Text(Tr.t("重开流"))
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
                text = Tr.t("刚才那声 1 kHz 提示音，你听到了吗？"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Tr.t("这是发现“被静默重路由到蓝牙/其他输出”的唯一务实手段——API 层检测不到，只能靠耳朵。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirmBeep(true) }, modifier = Modifier.weight(1f)) {
                    Text(Tr.t("听到了"))
                }

                OutlinedButton(onClick = { onConfirmBeep(false) }, modifier = Modifier.weight(1f)) {
                    Text(Tr.t("没听到"))
                }
            }
        }
    }
}

@Composable
private fun WaterCard(state: ReceiverState) {
    SectionCard(title = LocalStrings.current.sectionWater, accent = MaterialTheme.colorScheme.primary) {
        WaterSparkline(
            history = state.waterHistory,
            targetMs = state.targetWaterMs.toFloat(),
        )
        Spacer(Modifier.height(8.dp))
        MetricRow(
            "水位最低值（1 s 窗口）",
            String.format(Locale.US, "%.2f ms", state.waterMinMs),
            valueColor = if (state.waterMinMs < state.targetWaterMs * 0.6 && state.targetWaterMs > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            hint = "闭环控制量",
        )
        // 同窗口的平均值：与上面的最小值严格同批样本，不会再出现 min > avg
        MetricRow(
            "水位平均值（1 s 窗口）",
            String.format(Locale.US, "%.2f ms", state.waterAvgMs),
            hint = "与上一行同批样本",
        )
        // 回调入口水位的历史最低：原生累计口径，是"读端到底见过多干"的可靠值
        MetricRow(
            "水位历史最低（回调入口）",
            String.format(Locale.US, "%.2f ms", state.waterSessionMinMs),
            hint = "原生累计，不会重置",
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
    SectionCard(title = LocalStrings.current.sectionPlayback, accent = MaterialTheme.colorScheme.secondary) {
        MetricRow("采样率", "${state.engine.sampleRate} Hz")
        MetricRow("声道", "${state.engine.channels}")
        MetricRow("burst", Tr.frames(state.engine.framesPerBurst))
        MetricRow(
            "Oboe 缓冲",
            Tr.buffered(state.engine.bufferSizeFrames, String.format(Locale.US, "%.1f", state.engine.bufferMs)),
            valueColor = if (state.bufferBursts <= 2) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            hint = "${state.bufferBursts} burst",
        )
        MetricRow(
            "MMAP",
            when {
                state.engine.mmapUsed -> Tr.t("正在使用")
                state.engine.mmapSupported -> Tr.t("支持但未使用")
                else -> Tr.t("设备不支持")
            },
            valueColor = if (state.engine.mmapUsed) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            hint = "绕开混音层的关键",
        )
        MetricRow(
            "回调帧数上限",
            if (state.engine.maxCallbackFrames > 0) Tr.frames(state.engine.maxCallbackFrames) else "-",
            hint = "应 ≤ burst",
        )
        MetricRow(
            "回调间隔峰值",
            if (state.engine.schedGapMaxMs > 0) {
                String.format(Locale.US, "%.1f ms", state.engine.schedGapMaxMs)
            } else {
                "-"
            },
            valueColor = if (state.engine.schedGapMaxMs > state.engine.bufferMs && state.engine.bufferMs > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            hint = "剔除开流停顿",
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
        MetricRow("开流尝试", Tr.times(state.engine.openAttempts), hint = "独占 48k → 独占原生 → 共享")
        MetricRow("输出延迟", String.format(Locale.US, "%.2f ms", state.engine.latencyMs))
        MetricRow("开流代数", "${state.engine.streamGeneration}", hint = Tr.reopens(state.reopenCount))
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
    SectionCard(title = LocalStrings.current.sectionNetwork, accent = MaterialTheme.colorScheme.tertiary) {
        MetricRow("发送端", net.senderAddress ?: Tr.t("尚未收到数据"))
        MetricRow("收包", "${net.packetsReceived}")
        MetricRow("收包速率", Tr.packetsPerSecond(String.format(Locale.US, "%.1f", net.packetsPerSecond)))
        MetricRow("丢包", "${net.packetsLost}", valueColor = if (net.packetsLost > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
        MetricRow("乱序", "${net.packetsOutOfOrder}")
        MetricRow("畸形包", "${net.packetsMalformed}", valueColor = if (net.packetsMalformed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        MetricRow("时间线跳变", "${net.sampleCountJumps}", hint = "发送端重启")

        // 成串停顿：WiFi 热点不稳时最直接的证据，抖动直方图看不出来
        MetricRow(
            Tr.stallLabel(),
            Tr.stallValue(net.stallEvents, net.stallMaxMs),
            valueColor = if (net.stallEvents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            hint = if (net.stallEvents > 0) Tr.stallAdvice() else null,
        )
        MetricRow(Tr.catchUpLabel(), Tr.catchUpValue(net.catchUpEvents))
        MetricRow(
            "最后一包距今",
            if (net.lastArrivalAgeMs == Long.MAX_VALUE) "—" else "${net.lastArrivalAgeMs} ms",
        )
    }
}

@Composable
private fun StabilityCard(state: ReceiverState) {
    val e = state.engine
    SectionCard(title = LocalStrings.current.sectionStability, accent = MaterialTheme.colorScheme.error) {
        MetricRow("欠载次数", "${e.underrunEvents}", valueColor = if (e.underrunEvents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        MetricRow("欠载补静音", Tr.frames(e.underrunFrames))
        // 两个不同事件必须分开看：读端饥饿是"我们的环没料"（多半是网络洞），
        // xrun 是"回调没赶上"（调度问题）。混在一起会误判因果。
        MetricRow(
            "├ 读端饥饿",
            Tr.frames(e.readStarveFrames),
            hint = "环里没数据，多半是网络成串丢包",
        )
        MetricRow(
            "└ 硬件 xrun",
            if (e.xRunCount < 0) Tr.t("不可用") else Tr.times(e.xRunCount),
            hint = if (e.xRunCount < 0) "本设备未提供 getXRunCount" else "回调迟到，调度问题",
        )
        MetricRow("启动垫水位", Tr.frames(e.prefillFrames), hint = "不计欠载")
        MetricRow("积压丢帧", Tr.times(e.dropEvents) + " / " + Tr.frames(e.dropFrames))
        MetricRow("缺包补静音", Tr.frames(e.silenceFilledFrames))
        MetricRow("时间线重建", Tr.times(e.resyncEvents))
        MetricRow("环缓冲溢出", Tr.frames(e.overflowDrops))
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
    SectionCard(title = LocalStrings.current.sectionAddress, accent = MaterialTheme.colorScheme.outline) {
        if (state.localAddresses.isEmpty()) {
            Text(Tr.t("未检测到可用 IPv4 地址"), style = MaterialTheme.typography.bodySmall)
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
            text = Tr.listeningPort(state.settings.port),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(
    state: ReceiverState,
    onSettingsChange: (ReceiverSettings) -> Unit,
    onReplayGuide: () -> Unit,
) {
    var portText by remember(state.settings.port) { mutableStateOf(state.settings.port.toString()) }
    var manualTarget by remember(state.settings.manualTargetWaterMs) {
        mutableStateOf(state.settings.manualTargetWaterMs.toFloat())
    }

    SectionCard(title = LocalStrings.current.sectionSettings, accent = MaterialTheme.colorScheme.outline) {
        // 上行麦克风暂时关闭（见 FeatureFlags），整块不显示；代码保留，开关打开后回来。
        if (FeatureFlags.MicUplink) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalStrings.current.sectionMic, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = Tr.t("需要重启接收才生效；PC 端要选虚拟麦克风（如 CABLE Input）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = state.settings.micEnabled,
                    onCheckedChange = { onSettingsChange(state.settings.copy(micEnabled = it)) },
                )
            }

            if (state.micRunning) {
                MetricRow("上行已发送", Tr.packets(state.micPackets), hint = state.micSource)
                MetricRow(
                    "上行电平",
                    String.format(Locale.US, "%.3f", state.micPeak),
                    hint = if (state.micDuckGain < 1.0f) "已压低 20 dB" else "正常",
                )
            } else if (state.settings.micEnabled && state.isRunning) {
                Text(
                    text = Tr.t("上行麦克风未运行：") + (state.micError ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.t("独占模式（拿不到会自动回退共享）"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.requestExclusive,
                onCheckedChange = { onSettingsChange(state.settings.copy(requestExclusive = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.t("自动目标水位"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.autoTargetWater,
                onCheckedChange = { onSettingsChange(state.settings.copy(autoTargetWater = it)) },
            )
        }

        if (!state.settings.autoTargetWater) {
            Text(
                text = Tr.manualTarget(manualTarget.toInt()),
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
            label = { Text(Tr.t("UDP 端口")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.t("保持屏幕常亮"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.settings.keepScreenOn,
                onCheckedChange = { onSettingsChange(state.settings.copy(keepScreenOn = it)) },
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LocalStrings.current.settingsReplayGuide,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onReplayGuide) {
                Text(LocalStrings.current.settingsReplayGuide)
            }
        }

        Text(
            text = Tr.t("端口与独占模式的改动在下次“开始接收”时生效。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 版本自检。只提示、不自动安装：APK 由浏览器下载后由用户手动安装。
 */
@Composable
private fun UpdateCard(state: ReceiverState, onCheckUpdate: () -> Unit, onOpenDownload: () -> Unit) {
    val context = LocalContext.current
    val currentVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "-"
    }

    SectionCard(title = LocalStrings.current.sectionVersion, accent = MaterialTheme.colorScheme.outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tr.currentVersionShort(currentVersion), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (state.updateCheckText.isBlank()) Tr.t("尚未检查") else state.updateCheckText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = onCheckUpdate) {
                Text(Tr.t("检查更新"))
            }
        }

        if (state.latestVersion != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Text(Tr.downloadVersion(state.latestVersion))
            }
        }
    }
}

@Composable
private fun LogCard(state: ReceiverState) {
    SectionCard(title = "${LocalStrings.current.sectionLog}（${state.log.size}）", accent = MaterialTheme.colorScheme.outline) {
        // 日志是技术排查用的，刻意只保留中文；这里明说，避免用户以为是漏翻译
        Text(
            text = Tr.logNote(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(6.dp))

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
