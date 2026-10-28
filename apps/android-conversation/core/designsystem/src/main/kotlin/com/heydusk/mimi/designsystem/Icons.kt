package com.heydusk.mimi.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 三个 Tab 图标（对应 iOS SF Symbols `sun.max` / `leaf` / `house`），24dp 描边风格。
 * 自绘是为了不引入体积巨大的 material-icons-extended。
 */
object MimiIcons {
    private fun stroked(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    )

    val Sun: ImageVector by lazy {
        stroked("Sun") {
            line {
                moveTo(16.5f, 12f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, -9f, 0f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, 9f, 0f)
                close()
            }
            line { moveTo(12f, 2.8f); lineTo(12f, 4.6f) }
            line { moveTo(12f, 19.4f); lineTo(12f, 21.2f) }
            line { moveTo(2.8f, 12f); lineTo(4.6f, 12f) }
            line { moveTo(19.4f, 12f); lineTo(21.2f, 12f) }
            line { moveTo(5.5f, 5.5f); lineTo(6.8f, 6.8f) }
            line { moveTo(17.2f, 17.2f); lineTo(18.5f, 18.5f) }
            line { moveTo(18.5f, 5.5f); lineTo(17.2f, 6.8f) }
            line { moveTo(6.8f, 17.2f); lineTo(5.5f, 18.5f) }
        }
    }

    val Leaf: ImageVector by lazy {
        stroked("Leaf") {
            line {
                moveTo(5f, 19f)
                curveTo(5f, 11f, 10f, 5.5f, 19f, 5f)
                curveTo(19f, 13.5f, 14f, 19f, 5f, 19f)
                close()
            }
            line { moveTo(5f, 19f); curveTo(8f, 14.5f, 11.5f, 11.5f, 15f, 9.5f) }
        }
    }

    val House: ImageVector by lazy {
        stroked("House") {
            line {
                moveTo(4f, 11f)
                lineTo(12f, 4.2f)
                lineTo(20f, 11f)
            }
            line {
                moveTo(6f, 9.6f)
                lineTo(6f, 19f)
                lineTo(18f, 19f)
                lineTo(18f, 9.6f)
            }
            line { moveTo(10f, 19f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 19f) }
        }
    }

    // 输入卡用的图标（对应 SF Symbols mic / plus / keyboard / arrow.up / xmark / waveform / sparkles）。

    val Mic: ImageVector by lazy {
        stroked("Mic") {
            line {
                moveTo(9f, 6f); arcToRelative(3f, 3f, 0f, false, true, 6f, 0f); lineTo(15f, 12f)
                arcToRelative(3f, 3f, 0f, false, true, -6f, 0f); close()
            }
            line { moveTo(5.5f, 11f); arcToRelative(6.5f, 6.5f, 0f, false, false, 13f, 0f) }
            line { moveTo(12f, 17.5f); lineTo(12f, 21f) }
        }
    }

    val Plus: ImageVector by lazy {
        stroked("Plus") {
            line { moveTo(12f, 5f); lineTo(12f, 19f) }
            line { moveTo(5f, 12f); lineTo(19f, 12f) }
        }
    }

    val Keyboard: ImageVector by lazy {
        stroked("Keyboard") {
            line {
                moveTo(5f, 6f); lineTo(19f, 6f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(21f, 16f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(5f, 18f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); lineTo(3f, 8f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
            }
            line { moveTo(7f, 10f); lineTo(7.2f, 10f) }
            line { moveTo(11f, 10f); lineTo(11.2f, 10f) }
            line { moveTo(15f, 10f); lineTo(15.2f, 10f) }
            line { moveTo(8f, 14.5f); lineTo(16f, 14.5f) }
        }
    }

    val ArrowUp: ImageVector by lazy {
        stroked("ArrowUp") {
            line { moveTo(12f, 19f); lineTo(12f, 5.5f) }
            line { moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
        }
    }

    val Close: ImageVector by lazy {
        stroked("Close") {
            line { moveTo(6f, 6f); lineTo(18f, 18f) }
            line { moveTo(18f, 6f); lineTo(6f, 18f) }
        }
    }

    val Check: ImageVector by lazy {
        stroked("Check") { line { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    }

    val Waveform: ImageVector by lazy {
        stroked("Waveform") {
            line { moveTo(5f, 10f); lineTo(5f, 14f) }
            line { moveTo(9f, 6f); lineTo(9f, 18f) }
            line { moveTo(13f, 3.5f); lineTo(13f, 20.5f) }
            line { moveTo(17f, 8f); lineTo(17f, 16f) }
            line { moveTo(21f, 10.5f); lineTo(21f, 13.5f) }
        }
    }

    val Sparkles: ImageVector by lazy {
        stroked("Sparkles") {
            line {
                moveTo(10f, 4f); lineTo(11.6f, 9f); lineTo(16.5f, 10.5f); lineTo(11.6f, 12f)
                lineTo(10f, 17f); lineTo(8.4f, 12f); lineTo(3.5f, 10.5f); lineTo(8.4f, 9f); close()
            }
            line { moveTo(18f, 15f); lineTo(18.8f, 17.2f); lineTo(21f, 18f); lineTo(18.8f, 18.8f); lineTo(18f, 21f); lineTo(17.2f, 18.8f); lineTo(15f, 18f); lineTo(17.2f, 17.2f); close() }
        }
    }
}
