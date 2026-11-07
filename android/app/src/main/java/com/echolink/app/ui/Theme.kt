package com.echolink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 深色优先：这是个长时间开着的“音频仪表盘”，深色省电也更适合放桌上。
private val EchoLinkColorScheme = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color(0xFF00212C),
    primaryContainer = Color(0xFF0E3A4A),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = Color(0xFF7EE2B8),
    onSecondary = Color(0xFF00291B),
    tertiary = Color(0xFFFFC46B),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE4EBF2),
    surface = Color(0xFF111822),
    onSurface = Color(0xFFE4EBF2),
    surfaceVariant = Color(0xFF1A2431),
    onSurfaceVariant = Color(0xFFA8B6C6),
    outline = Color(0xFF3A4859),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF3A0000),
)

@Composable
fun EchoLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EchoLinkColorScheme, content = content)
}
