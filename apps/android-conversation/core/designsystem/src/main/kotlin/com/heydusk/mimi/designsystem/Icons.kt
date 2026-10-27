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
}
