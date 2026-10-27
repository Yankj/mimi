package com.heydusk.mimi.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.heydusk.mimi.designsystem.tokens.Mode
import com.heydusk.mimi.designsystem.tokens.ThemeTokens
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette

/**
 * mimi 主题：配色（C 素白墨黑 / B 雾粉燕麦）与外观（跟随系统 / 浅 / 深）是两条独立的轴。
 * 动态取色（Material You）永远关闭：品牌色由令牌决定。
 */
@Composable
fun MimiTheme(
    palette: Palette = Palette.default,
    appearance: Appearance = Appearance.default,
    reduceTransparency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = appearance.resolveDark(isSystemInDarkTheme())
    val colors = remember(palette, dark) {
        val tokens = ThemeTokens.of(palette)
        val mode = if (dark) Mode.Dark else Mode.Light
        MimiColors(tokens, mode, tokens.set(mode))
    }
    CompositionLocalProvider(
        LocalMimiColors provides colors,
        LocalReduceTransparency provides reduceTransparency,
    ) {
        MaterialTheme(colorScheme = colors.toMaterialColorScheme(), content = content)
    }
}

/** 便于读取：`Mimi.colors.ink`。 */
object Mimi {
    val colors: MimiColors
        @Composable get() = LocalMimiColors.current
}

internal fun MimiColors.toMaterialColorScheme(): ColorScheme {
    val onAccent = if (isDark) canvas else Color.White
    return if (isDark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            background = canvas, onBackground = ink,
            surface = card, onSurface = ink,
            surfaceVariant = surface, onSurfaceVariant = ink2,
            surfaceContainer = card, surfaceContainerHigh = surface,
            outline = ink3, outlineVariant = hairline,
            error = danger, errorContainer = dangerFill,
        )
    } else {
        lightColorScheme(
            primary = accentOn, onPrimary = onAccent,
            background = canvas, onBackground = ink,
            surface = card, onSurface = ink,
            surfaceVariant = surface, onSurfaceVariant = ink2,
            surfaceContainer = card, surfaceContainerHigh = surface,
            outline = ink3, outlineVariant = hairline,
            error = danger, errorContainer = dangerFill,
        )
    }
}
