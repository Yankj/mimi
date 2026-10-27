package com.heydusk.mimi.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.heydusk.mimi.designsystem.tokens.ColorSet
import com.heydusk.mimi.designsystem.tokens.Mode
import com.heydusk.mimi.designsystem.tokens.Rgba
import com.heydusk.mimi.designsystem.tokens.ThemeTokens

/** 令牌的 Compose 视图：同一份 [ColorSet]，颜色已转成 [Color]。 */
@Immutable
class MimiColors internal constructor(
    val tokens: ThemeTokens,
    val mode: Mode,
    val set: ColorSet,
) {
    val isDark: Boolean get() = mode == Mode.Dark

    val canvas = Color(set.canvas or OPAQUE)
    val card = Color(set.card or OPAQUE)
    val surface = Color(set.surface or OPAQUE)
    val hairline = Color(set.hairline or OPAQUE)
    val track = Color(set.track or OPAQUE)
    val ink = Color(set.ink or OPAQUE)
    val ink2 = Color(set.ink2 or OPAQUE)
    val ink3 = Color(set.ink3 or OPAQUE)
    val accent = Color(set.accent or OPAQUE)
    val accentOn = Color(set.accentOn or OPAQUE)
    val accentOnPressed = Color(set.accentOnPressed or OPAQUE)
    val orangeText = Color(set.orangeText or OPAQUE)
    val success = Color(set.success or OPAQUE)
    val successFill = Color(set.successFill or OPAQUE)
    val attention = Color(set.attention or OPAQUE)
    val attentionFill = Color(set.attentionFill or OPAQUE)
    val danger = Color(set.danger or OPAQUE)
    val dangerFill = Color(set.dangerFill or OPAQUE)
    val logoOrange = Color(ThemeTokens.LOGO_ORANGE or OPAQUE)

    val kids: List<Color> = set.kids.map { Color(it or OPAQUE) }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
    }
}

fun Rgba.toColor(): Color = Color(
    red = red.toFloat(),
    green = green.toFloat(),
    blue = blue.toFloat(),
    alpha = alpha.toFloat(),
)

fun Int.toOpaqueColor(): Color = Color(this or 0xFF000000.toInt())

/** 当前主题的令牌；由 [MimiTheme] 提供。 */
val LocalMimiColors = compositionLocalOf<MimiColors> {
    error("MimiTheme not provided: wrap the content in MimiTheme { … }")
}

/** 降低透明度：玻璃改为不透明降级色（对应 iOS 的 Reduce Transparency）。M0 只有应用内开关，系统信号 M2 接入。 */
val LocalReduceTransparency = staticCompositionLocalOf { false }
