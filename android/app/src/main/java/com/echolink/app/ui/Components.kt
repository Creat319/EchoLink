package com.echolink.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echolink.app.i18n.Tr
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accent != null) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(14.dp)
                            .background(accent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Text(
                    text = Tr.t(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

typealias ContentBlock = @Composable () -> Unit

@Composable
fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    hint: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Tr.t(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = Tr.t(hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = valueColor,
        )
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 水位曲线：把最近约 20 秒的水位画成折线，并用虚线标出目标水位。
 * 浸泡测试时看这条线是否“贴着目标、不单向漂移”比看数字快得多。
 */
@Composable
fun WaterSparkline(
    history: List<Float>,
    targetMs: Float,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    val maxValue = maxOf(
        targetMs * 2f,
        history.maxOrNull() ?: 0f,
        10f,
    )

    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height

        // 网格：0 / 目标 / 上限
        drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        val targetY = h - (targetMs / maxValue) * h
        drawLine(
            targetColor.copy(alpha = 0.7f),
            Offset(0f, targetY),
            Offset(w, targetY),
            strokeWidth = 1.5f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )

        if (history.size < 2) {
            return@Canvas
        }

        val path = Path()
        val stepX = w / (history.size - 1).coerceAtLeast(1)
        history.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - ((value / maxValue).coerceIn(0f, 1f) * h)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path, lineColor, style = Stroke(width = 2.2f))
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "0 ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 10.sp,
        )
        Text(
            text = "满量程 ${"%.0f".format(maxValue)} ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 10.sp,
        )
    }
}
