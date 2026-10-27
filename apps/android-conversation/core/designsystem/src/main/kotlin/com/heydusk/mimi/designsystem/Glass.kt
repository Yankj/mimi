package com.heydusk.mimi.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.tokens.ButtonSurface
import com.heydusk.mimi.designsystem.tokens.TintedButtonState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 着色玻璃（对应 iOS `CatButtonStyles` 的 tinted-glass）。从下到上三层：
 *   1. 卡片色底层（baseAlpha 85%）  2. Logo 橙 #FF702A 着色层  3. 1dp 橙色描边。
 * 文字用 ink，图标用 accentOn；不画橙色投影。禁用态：无着色，描边换成 hairline，文字用 ink3。
 * 长按听写（VoiceHold）：着色 28%、描边 65%、外加 14% 光晕环。
 *
 * 传入 [hazeState] 时底层会先做 Haze 背景模糊（需要内容区调用 `hazeSource(hazeState)`）；
 * 不支持模糊的系统由 Haze 自己降级为纯色罩，不会出现空白。
 * "降低透明度"（[LocalReduceTransparency]）时整个玻璃改画为不透明的降级色，与令牌里的合成值一致。
 */
@Composable
fun Modifier.mimiTintedGlass(
    shape: Shape,
    state: TintedButtonState,
    surface: ButtonSurface = ButtonSurface.Canvas,
    hazeState: HazeState? = null,
): Modifier {
    val colors = LocalMimiColors.current
    val set = colors.set
    val spec = set.button
    val reduce = LocalReduceTransparency.current
    val orange = colors.logoOrange
    val borderColor: Color
    val fillModifier: Modifier
    if (reduce) {
        val fill = set.tintedButtonFill(state, surface).toColor()
        borderColor = if (state == TintedButtonState.Disabled) colors.hairline
        else set.tintedButtonBorder(state, surface).toColor()
        fillModifier = Modifier.background(fill, shape)
    } else {
        borderColor = if (state == TintedButtonState.Disabled) colors.hairline
        else orange.copy(alpha = spec.borderAlpha(state).toFloat())
        val blur = if (hazeState != null) Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = glassBlurStyle(colors),
        ) else Modifier
        fillModifier = blur
            .background(colors.card.copy(alpha = spec.baseAlpha.toFloat()), shape)
            .background(orange.copy(alpha = spec.fillAlpha(state).toFloat()), shape)
    }
    val halo = spec.haloAlpha(state)
    val haloModifier = if (halo > 0) Modifier.drawBehind {
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            color = orange.copy(alpha = halo.toFloat()),
            style = Stroke(width = 6.dp.toPx() * 2),
        )
    } else Modifier
    return this
        .then(haloModifier)
        .clip(shape)
        .then(fillModifier)
        .border(BorderStroke(1.dp, borderColor), shape)
}

/** 玻璃背景的 Haze 样式：不支持模糊时退回更不透明的卡片色罩，而不是空白。 */
private fun glassBlurStyle(colors: MimiColors): HazeBlurStyle = HazeBlurStyle {
    blurRadius(20.dp)
    noiseFactor(0f)
    backgroundColor(colors.card)
    colorEffects(listOf(HazeColorEffect.tint(colors.card.copy(alpha = 0.55f))))
    fallbackColorEffect(HazeColorEffect.tint(colors.card.copy(alpha = 0.90f)))
}

/** 底栏用的 Haze 样式（比按钮更通透一些，仍保证文字对比度：上面还叠着 85% 卡片色）。 */
fun mimiBarBlurStyle(colors: MimiColors): HazeBlurStyle = glassBlurStyle(colors)

/**
 * 着色玻璃主按钮。最小触控 48dp；按下态由交互源驱动（30% 着色）。
 * 文字色 ink（禁用 ink3）；图标请用 [MimiColors.accentOn]（按下用 accentOnPressed）。
 */
@Composable
fun MimiTintedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    voiceHold: Boolean = false,
    surface: ButtonSurface = ButtonSurface.Canvas,
    hazeState: HazeState? = null,
    shape: Shape = CircleShape,
    contentPadding: Dp = 20.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalMimiColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val state = when {
        !enabled -> TintedButtonState.Disabled
        voiceHold -> TintedButtonState.VoiceHold
        pressed -> TintedButtonState.Pressed
        else -> TintedButtonState.Normal
    }
    Row(
        modifier = modifier
            .mimiTintedGlass(shape, state, surface, hazeState)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
            .padding(horizontal = contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.ink else colors.ink3) {
            content()
        }
    }
}
