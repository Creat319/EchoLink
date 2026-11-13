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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.echolink.app.net.UsbTetherDetector
import kotlinx.coroutines.delay

private const val PageCount = 4
private const val ConnectPageIndex = 3

/**
 * 首次启动的引导。
 *
 * 最后一页要求真实检测到 USB 网络共享才允许「下一步」——但**「跳过引导」始终可用**，
 * 避免用户卡死在里面（比如手机暂时没有可用的数据线）。
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

    val context = LocalContext.current
    var usbConnected by remember { mutableStateOf(false) }

    // 只在最后一页做轮询，避免没意义的常驻开销
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != ConnectPageIndex) {
            return@LaunchedEffect
        }

        while (true) {
            usbConnected = UsbTetherDetector.isConnected(context)
            delay(700)
        }
    }

    val onLastPage = pagerState.currentPage == ConnectPageIndex

    Column(Modifier.fillMaxSize()) {
        // 顶栏：跳过 + 页指示
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageDots(count = PageCount, current = pagerState.currentPage)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFinish) {
                Text(strings.skip, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = true,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> WelcomePage(
                    strings = strings,
                    selected = language,
                    onSelect = { picked ->
                        language = picked
                        onLanguageChange(picked)
                    },
                )

                1 -> IntroPage(
                    title = strings.intro1Title,
                    body = strings.intro1Body,
                    bullets = strings.intro1Bullets,
                )

                2 -> IntroPage(
                    title = strings.intro2Title,
                    body = strings.intro2Body,
                    bullets = strings.intro2Bullets,
                )

                else -> ConnectPage(
                    strings = strings,
                    connected = usbConnected,
                )
            }
        }

        // 底栏：上一步 / 下一步
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagerState.currentPage > 0) {
                OutlinedButton(onClick = { pagerState.requestScrollToPage(pagerState.currentPage - 1) }) {
                    Text(strings.back)
                }
            }

            Spacer(Modifier.weight(1f))

            if (onLastPage) {
                Button(
                    onClick = onFinish,
                    // 没连上 USB 就不让走完流程——但右上角的「跳过引导」仍然可以点
                    enabled = usbConnected,
                ) {
                    Text(strings.start)
                }
            } else {
                Button(onClick = { pagerState.requestScrollToPage(pagerState.currentPage + 1) }) {
                    Text(strings.next)
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(if (active) 9.dp else 7.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun PageScaffold(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
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
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun WelcomePage(strings: Strings, selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    PageScaffold {
        Text(
            text = strings.welcomeTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings.welcomeBody,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        Text(
            text = strings.languageTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // 四选一，选中即时生效（这一页的文案会跟着变，用户能立刻确认）
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

@Composable
private fun IntroPage(title: String, body: String, bullets: List<String>) {
    PageScaffold {
        PageTitle(title)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        bullets.forEach { bullet ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(text = bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ConnectPage(strings: Strings, connected: Boolean) {
    PageScaffold {
        PageTitle(strings.connectTitle)
        Text(
            text = strings.connectBody,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(20.dp))

        strings.connectSteps.forEachIndexed { index, step ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = step, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 实时状态：绿色=已连上，灰色=还没检测到
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.connectNextBlocked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = strings.connectSkipHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
