package com.heydusk.mimi.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.heydusk.mimi.designsystem.tokens.Rgba

/**
 * 页面背景：canvas 加上配色自带的装饰色晕（仅 B；C 是纯色）。纯装饰，不进入对比度检查，也对读屏隐藏。
 * 色斑用椭圆径向渐变近似提案里的 CSS radial-gradient。
 */
@Composable
fun MimiCanvasBackground(modifier: Modifier = Modifier) {
    val colors = LocalMimiColors.current
    val wash = colors.set.wash
    Box(modifier.fillMaxSize().background(colors.canvas).clearAndSetSemantics { }) {
        if (wash != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Rgba(wash.topHex).toColor(),
                        wash.fadeEnd.toFloat() to colors.canvas.copy(alpha = 0f),
                    ),
                )
                for (spot in wash.spots) {
                    val c = Rgba(spot.hex, spot.alpha).toColor()
                    val radiusX = (size.width * spot.radiusX * 0.5).toFloat()
                    val radiusY = (size.height * spot.radiusY * 0.5).toFloat()
                    val center = Offset((size.width * spot.centerX).toFloat(), (size.height * spot.centerY).toFloat())
                    // 椭圆：先按纵横比缩放画布，再画正圆径向渐变。
                    val ratio = radiusY / radiusX
                    scale(scaleX = 1f, scaleY = ratio, pivot = center) {
                        drawCircle(
                            brush = Brush.radialGradient(listOf(c, Color.Transparent), center = center, radius = radiusX),
                            radius = radiusX,
                            center = center,
                        )
                    }
                }
            }
        }
    }
}
