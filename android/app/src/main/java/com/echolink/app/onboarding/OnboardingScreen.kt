package com.echolink.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echolink.app.net.UsbTetherDetector
import kotlinx.coroutines.delay

/** 语言 / 介绍 / 连接 —— 三页，够用就好。 */
private const val PageWelcome = 0
private const val PageIntro = 1
private const val PageConnect = 2
private const val PageCount = 3

/** 超过这个时长还没检测到 USB，就给一个"手动继续"的出口。 */
private const val ManualFallbackAfterMs = 10_000L

/**
 * 首次启动的引导。
 *
 * 三个设计上的坑，都是踩过之后改的：
 * - **不要**用 `verticalScroll` + `Arrangement.Center` + `fillMaxSize` 组合：
 *   内容一旦超过一屏，居中会把顶部裁掉，表现为"只剩序号、文字不见了"。
 *   现在一律顶对齐 + 滚动。
 * - **不要**画页指示圆点：用户反馈"那三个点是什么鬼"，去掉了，靠文案和按钮表达进度。
 * - 最后页要求真实检测到 USB，但**超时后给手动出口**、且「跳过引导」始终可用：
 *   检测逻辑再准也会有认不出的机型，不能让用户被卡死在引导里。
 */
@Composable
fun OnboardingScreen(
    initialLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onFinish: () -> Unit,
) {
    var language by remember { mutableStateOf(initialLanguage) }
    val strings = remember(language) { stringsFor(language) }
    val pagerState = rememberPagerState(initialPage = 0) { PageCount }

    var usbConnected by remember { mutableStateOf(false) }
    var interfaces by remember { mutableStateOf(emptyList<UsbTetherDetector.InterfaceInfo>()) }
    var waitedMs by remember { mutableStateOf(0L) }

    // 只在最后一页轮询；离开就停
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != PageConnect) {
            return@LaunchedEffect
        }

        waitedMs = 0L
        while (true) {
            usbConnected = UsbTetherDetector.isConnected()
            interfaces = UsbTetherDetector.listInterfaces()
            delay(700)
            if (!usbConnected) {
                waitedMs += 700
            }
        }
    }

    val manualFallback = !usbConnected && waitedMs >= ManualFallbackAfterMs

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onFinish) {
                Text(strings.skip)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                PageWelcome -> WelcomePage(
                    strings = strings,
                    selected = language,
                    onSelect = { picked ->
                        language = picked
                        onLanguageChange(picked)
                    },
                )

                PageIntro -> IntroPage(strings)

                else -> ConnectPage(
                    strings = strings,
                    connected = usbConnected,
                    interfaces = interfaces,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagerState.currentPage > 0) {
                OutlinedButton(onClick = { pagerState.requestScrollToPage(pagerState.currentPage - 1) }) {
                    Text(strings.back)
                }
            }

            Spacer(Modifier.weight(1f))

            if (pagerState.currentPage == PageConnect) {
                Button(
                    onClick = onFinish,
                    enabled = usbConnected || manualFallback,
                ) {
                    Text(
                        if (manualFallback) strings.connectManualContinue else strings.start,
                        maxLines = 1,
                    )
                }
            } else {
                Button(onClick = { pagerState.requestScrollToPage(pagerState.currentPage + 1) }) {
                    Text(strings.next)
                }
            }
        }
    }
}

/** 所有页面的统一滚动容器：顶对齐，绝不居中裁内容。 */
@Composable
private fun ScrollPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        content()
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun BodyText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}

@Composable
private fun WelcomePage(strings: Strings, selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    ScrollPage {
        Text(
            text = strings.welcomeTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        BodyText(strings.welcomeBody)
        Spacer(Modifier.height(24.dp))

        Text(
            text = strings.languageTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        AppLanguage.entries.forEach { language ->
            LanguageRow(
                language = language,
                selected = language == selected,
                onClick = { onSelect(language) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LanguageRow(language: AppLanguage, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 第 2 页：把原来两页介绍合成一页（用户反馈分成两页太啰嗦）。 */
@Composable
private fun IntroPage(strings: Strings) {
    ScrollPage {
        PageTitle(strings.intro1Title)
        BodyText(strings.intro1Body)

        Spacer(Modifier.height(12.dp))
        Bullets(strings.intro1Bullets)

        Spacer(Modifier.height(22.dp))
        HorizontalDividerLine()
        Spacer(Modifier.height(22.dp))

        PageTitle(strings.intro2Title)
        BodyText(strings.intro2Body)

        Spacer(Modifier.height(12.dp))
        Bullets(strings.intro2Bullets)
    }
}

@Composable
private fun Bullets(items: List<String>) {
    items.forEach { item ->
        Row(Modifier.padding(vertical = 5.dp)) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = item,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HorizontalDividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ConnectPage(
    strings: Strings,
    connected: Boolean,
    interfaces: List<UsbTetherDetector.InterfaceInfo>,
) {
    ScrollPage {
        PageTitle(strings.connectTitle)
        BodyText(strings.connectBody)
        Spacer(Modifier.height(14.dp))

        // 给"不知道 USB 线是什么"的人一句人话
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Text(
                text = strings.connectCableHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(14.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        // 步骤：序号画成圆形徽标，文字紧挨着。
        // 之前把序号和文字放在同一个 Row 里、外层又用了居中的滚动容器，内容一超屏
        // 顶部就被裁掉，用户只看到"1. 2. 3. 4."而没有文字。
        strings.connectSteps.forEachIndexed { index, step ->
            Row(
                Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // 实时状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        color = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (connected) strings.connectDetected else strings.connectWaiting,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (!connected) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.connectNextBlocked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // 把当前网卡列出来：检测不到时，这行文字就是唯一能帮上忙的线索
            if (interfaces.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = strings.connectInterfacesLabel + "：" +
                        interfaces.joinToString("、") { info ->
                            info.name + (info.ipv4?.let { " ($it)" } ?: "")
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = strings.connectSkipHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
