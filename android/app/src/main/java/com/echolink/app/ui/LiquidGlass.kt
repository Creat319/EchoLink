package com.echolink.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部浮动「液态玻璃」导航条。
 *
 * 视觉参数取自 FlClash / prism_ui 的 `LiquidGlassStyle.pill`（深色分支）：
 * tint ≈ 48% 不透明度、haze 模糊 15.6、rim 0.35。那一套是 **GLSL 片元着色器**
 * 做真实背景折射的，Compose 没有等价能力，所以这里是**视觉复刻**：
 *
 * - 半透明填充（上下渐变，模拟玻璃的厚度感）
 * - 1.2 dp 渐变描边（顶部亮、底部暗，模拟边缘高光 / rim）
 * - 顶部一道内高光细线
 * - 选中项后面一块会滑动的「玻璃胶囊」，带轻微内高光
 * - 外阴影把 dock 从内容层浮起来
 *
 * 没有做的：真实背景模糊（需要 API 33+ 的 RuntimeShader 采样背景，
 * 属于另一个量级的工程，见 CHANGELOG）。
 */
data class GlassNavItem(
    val icon: ImageVector,
    val label: String,
)

@Composable
fun LiquidGlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        return
    }

    val shape = RoundedCornerShape(28.dp)
    val surface = MaterialTheme.colorScheme.surface

    // 玻璃的 tint：深色底上取低透明度的白，靠上下渐变制造"厚度"
    val tintTop = Color.White.copy(alpha = 0.13f)
    val tintBottom = Color.White.copy(alpha = 0.06f)
    // rim：顶部亮、底部几乎消失，这是"液态玻璃"最关键的一笔
    val rimTop = Color.White.copy(alpha = 0.45f)
    val rimBottom = Color.White.copy(alpha = 0.05f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val itemWidth: Dp = maxWidth / items.size
        val slotHeight = 52.dp

        // 选中胶囊用弹簧滑动，比直接换色更有"液态"的感觉
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "glassIndicator",
        )

        Box(
            Modifier
                .fillMaxWidth()
                // 外阴影：把 dock 从下面的内容里"浮"起来
                .drawBehind {
                    val radius = 28.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        topLeft = Offset(0f, 10f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    )
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(tintTop, tintBottom)))
                .border(
                    width = 1.2.dp,
                    brush = Brush.verticalGradient(listOf(rimTop, rimBottom)),
                    shape = shape,
                )
                // 顶部内高光：一条极细的亮线，模拟玻璃上缘的反射
                .drawBehind {
                    val inset = 1.5.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f),
                        start = Offset(size.width * 0.18f, inset),
                        end = Offset(size.width * 0.82f, inset),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        ) {
            // 滑动的选中胶囊
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .height(slotHeight)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.07f),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        ),
                        RoundedCornerShape(22.dp),
                    ),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(slotHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .height(slotHeight)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 玻璃质感的卡片（给指标卡片用，比纯色略微"通透"一点）。
 * 同样是视觉复刻：半透明填充 + 顶部亮边。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        surface.copy(alpha = 0.92f),
                        surface.copy(alpha = 0.72f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f)),
                ),
                shape,
            )
            .padding(14.dp),
    ) {
        content()
    }
}
