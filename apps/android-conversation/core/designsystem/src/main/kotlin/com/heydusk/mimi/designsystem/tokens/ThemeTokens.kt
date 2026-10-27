package com.heydusk.mimi.designsystem.tokens

import com.heydusk.mimi.domain.theme.Palette

/**
 * 主题令牌：与 iOS `CatTheme.swift` 的 `CatThemeTokens` 一一对应（逐值抄写）。
 * 数值的权威来源是 apps/ios-conversation/Sources/DesignSystem/CatTheme.swift；
 * `scripts/export-theme-tokens.py` 把它导出成 docs/design/mimi-theme-tokens.json，
 * 单测 `TokenSnapshotTest` 用该快照独立校验本文件，防止两端漂移。
 *
 * 相对 mimi-brand-themes-tokens.json 的有意偏离（与 iOS 相同，均有测试覆盖）：
 *  - `ink3` 日间更深、夜间更浅，使三级文字在 canvas/card/surface 上都 ≥ 4.5:1。
 *    提案 → 上线：C-day #8A8782→#6F6D69 · C-night #76746F→#8F8E89 · B-day #94857A→#73655A · B-night #9A8C80→#9C8E82。
 *  - C 日间默认按钮着色为 20%（JSON 为 16%）。
 *  - 按下态着色在四种组合下都是 30%（JSON 日间为 26%）。
 */
enum class ColorRole {
    Canvas, Card, Surface, Hairline, Track,
    Ink, Ink2, Ink3,

    /** 图形强调色（环、"现在"线、圆点），不用于正文。 */
    Accent,

    /** 着色按钮 / 选中 Tab 上的图标色（≥3:1，图形）。 */
    AccentOn, AccentOnPressed,

    /** 小面积链接色；C 用墨色（没有橙色文字）。 */
    OrangeText,
    Success, SuccessFill, Attention, AttentionFill, Danger, DangerFill,
}

enum class TintedButtonState { Normal, Pressed, Disabled, VoiceHold }

/** 按钮所在的底（决定不透明降级色叠在哪个颜色上）。 */
enum class ButtonSurface { Canvas, Card }

enum class Mode { Light, Dark }

/** 着色玻璃主按钮参数；所有透明度取 0…1。 */
data class TintedButtonSpec(
    val fill: Double,
    val fillPressed: Double,
    val border: Double,
    val borderPressed: Double,
    /** 着色层之下的卡片色底层不透明度（玻璃叠在杂乱内容上时仍保证对比度）。 */
    val baseAlpha: Double,
    /** 长按听写（语音）——所有主题相同：着色 28%、描边 65%、14% 光晕环。 */
    val voiceHoldFill: Double = 0.28,
    val voiceHoldBorder: Double = 0.65,
    val voiceHoldHalo: Double = 0.14,
) {
    fun fillAlpha(state: TintedButtonState): Double = when (state) {
        TintedButtonState.Normal -> fill
        TintedButtonState.Pressed -> fillPressed
        TintedButtonState.Disabled -> 0.0
        TintedButtonState.VoiceHold -> voiceHoldFill
    }

    /** 禁用态不画橙色描边，改用 hairline。 */
    fun borderAlpha(state: TintedButtonState): Double = when (state) {
        TintedButtonState.Normal -> border
        TintedButtonState.Pressed -> borderPressed
        TintedButtonState.Disabled -> 0.0
        TintedButtonState.VoiceHold -> voiceHoldBorder
    }

    fun haloAlpha(state: TintedButtonState): Double =
        if (state == TintedButtonState.VoiceHold) voiceHoldHalo else 0.0
}

/** 页面装饰性色晕（渐变 + 若干色斑），只做装饰，不进入对比度检查。 */
data class CanvasWash(val topHex: Int, val fadeEnd: Double, val spots: List<Spot>) {
    /** 圆心与半径均为宽/高的比例。 */
    data class Spot(
        val hex: Int,
        val alpha: Double,
        val centerX: Double,
        val centerY: Double,
        val radiusX: Double,
        val radiusY: Double,
    )
}

enum class TabSelectedStyle {
    /** C：中性胶囊（墨 7% / 白 12%），图标用强调色。 */
    Neutral,

    /** B：橙色着色胶囊（12% / 16%），图标用强调色。 */
    OrangeTint,
}

/** 一个配色在一种外观下的全部命名颜色。 */
data class ColorSet(
    val canvas: Int, val card: Int, val surface: Int, val hairline: Int, val track: Int,
    val ink: Int, val ink2: Int, val ink3: Int,
    val accent: Int, val accentOn: Int, val accentOnPressed: Int, val orangeText: Int,
    val success: Int, val successFill: Int, val attention: Int, val attentionFill: Int,
    val danger: Int, val dangerFill: Int,
    /** 每个孩子的环/点颜色（第一个给孩子 1，第二个给孩子 2…）。 */
    val kids: List<Int>,
    val button: TintedButtonSpec,
    /** 选中 Tab 胶囊填充（含透明度）。 */
    val tabSelectedFill: Rgba,
    val wash: CanvasWash?,
) {
    fun hex(role: ColorRole): Int = when (role) {
        ColorRole.Canvas -> canvas
        ColorRole.Card -> card
        ColorRole.Surface -> surface
        ColorRole.Hairline -> hairline
        ColorRole.Track -> track
        ColorRole.Ink -> ink
        ColorRole.Ink2 -> ink2
        ColorRole.Ink3 -> ink3
        ColorRole.Accent -> accent
        ColorRole.AccentOn -> accentOn
        ColorRole.AccentOnPressed -> accentOnPressed
        ColorRole.OrangeText -> orangeText
        ColorRole.Success -> success
        ColorRole.SuccessFill -> successFill
        ColorRole.Attention -> attention
        ColorRole.AttentionFill -> attentionFill
        ColorRole.Danger -> danger
        ColorRole.DangerFill -> dangerFill
    }

    /** 按钮背后的页面色。 */
    fun background(of: ButtonSurface): Int = if (of == ButtonSurface.Canvas) canvas else card

    private fun glassBase(on: ButtonSurface): Rgba =
        Rgba(card, button.baseAlpha).composited(Rgba(background(on)))

    /**
     * 着色按钮的不透明颜色：`品牌橙 @alpha` 叠在 `card @baseAlpha` 之上，再叠在所在的底上。
     * 这是"降低透明度"时的填充色，也是对比度检查所用的数值。
     */
    fun tintedButtonFill(state: TintedButtonState, on: ButtonSurface): Rgba =
        Rgba(ThemeTokens.LOGO_ORANGE, button.fillAlpha(state)).composited(glassBase(on))

    /** 着色按钮的不透明描边色（降低透明度时使用）。 */
    fun tintedButtonBorder(state: TintedButtonState, on: ButtonSurface): Rgba =
        Rgba(ThemeTokens.LOGO_ORANGE, button.borderAlpha(state)).composited(glassBase(on))
}

/** 一种配色的浅色/深色两套颜色。 */
data class ThemeTokens(
    val palette: Palette,
    val light: ColorSet,
    val dark: ColorSet,
    val tabSelectedStyle: TabSelectedStyle,
) {
    fun set(mode: Mode): ColorSet = if (mode == Mode.Dark) dark else light

    companion object {
        /** Logo 橙 #FF702A：所有配色里着色按钮的着色来源。 */
        const val LOGO_ORANGE: Int = 0xFF702A

        fun of(palette: Palette): ThemeTokens =
            if (palette == Palette.InkWhite) InkWhite else SoftBlush

        // ---- C 素白墨黑 ----
        val InkWhite = ThemeTokens(
            palette = Palette.InkWhite,
            light = ColorSet(
                canvas = 0xFAF9F7, card = 0xFFFFFF, surface = 0xF2F0EC, hairline = 0xE6E3DE, track = 0xE6E3DE,
                ink = 0x111111, ink2 = 0x5A5853, ink3 = 0x6F6D69,
                accent = 0xE85D1A, accentOn = 0xD15C22, accentOnPressed = 0xAE4F1F, orangeText = 0x111111,
                success = 0x1E7B46, successFill = 0xE7F4EC, attention = 0x8A5A00, attentionFill = 0xFFF3D6,
                danger = 0xC0352B, dangerFill = 0xFDECEA,
                kids = listOf(0x3C3C43, 0x3E7CB1, 0x4A8A5F, 0x7566C2, 0xC2527A),
                // 日间默认着色 20%（提案风险 3，用户决定），JSON 为 16%；按下态四种组合都是 30%。
                button = TintedButtonSpec(fill = 0.20, fillPressed = 0.30, border = 0.38, borderPressed = 0.55, baseAlpha = 0.85),
                tabSelectedFill = Rgba(0x111111, 0.07),
                wash = null,
            ),
            dark = ColorSet(
                canvas = 0x0B0B0C, card = 0x1A1A1C, surface = 0x262628, hairline = 0x3A3A3D, track = 0x3A3A3D,
                ink = 0xF5F4F2, ink2 = 0xA9A7A2, ink3 = 0x8F8E89,
                accent = 0xFF702A, accentOn = 0xFF702A, accentOnPressed = 0xFF945F, orangeText = 0xF5F4F2,
                success = 0x6FCF97, successFill = 0x16281D, attention = 0xF2C14E, attentionFill = 0x2B2412,
                danger = 0xFF8A80, dangerFill = 0x331A18,
                kids = listOf(0xD1D1D6, 0x6FA8DC, 0x6BB586, 0xA395E8, 0xE887A9),
                button = TintedButtonSpec(fill = 0.20, fillPressed = 0.30, border = 0.40, borderPressed = 0.60, baseAlpha = 0.85),
                tabSelectedFill = Rgba(0xFFFFFF, 0.12),
                wash = null,
            ),
            tabSelectedStyle = TabSelectedStyle.Neutral,
        )

        // ---- B 雾粉 + 燕麦 ----
        val SoftBlush = ThemeTokens(
            palette = Palette.SoftBlush,
            light = ColorSet(
                canvas = 0xFCF3EF, card = 0xFFFCFA, surface = 0xF2E6DC, hairline = 0xEADBD0, track = 0xF1E3D3,
                ink = 0x33261D, ink2 = 0x6E5C4F, ink3 = 0x73655A,
                accent = 0xFF702A, accentOn = 0xC95821, accentOnPressed = 0xAE4F20, orangeText = 0xB8410A,
                success = 0x3F6B3A, successFill = 0xE6EFDA, attention = 0x8F5200, attentionFill = 0xFFEFCB,
                danger = 0xB23B34, dangerFill = 0xFBE0DC,
                kids = listOf(0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A),
                button = TintedButtonSpec(fill = 0.16, fillPressed = 0.30, border = 0.38, borderPressed = 0.55, baseAlpha = 0.85),
                tabSelectedFill = Rgba(0xFF702A, 0.12),
                wash = CanvasWash(
                    topHex = 0xFAE6E1, fadeEnd = 0.40,
                    spots = listOf(
                        CanvasWash.Spot(0xE6AAA2, 0.55, 0.90, 0.00, 1.20, 0.55),
                        CanvasWash.Spot(0xEFE2C8, 0.90, 0.00, 0.45, 0.90, 0.40),
                        CanvasWash.Spot(0xD5E4C6, 0.50, 1.00, 0.88, 0.90, 0.40),
                    ),
                ),
            ),
            dark = ColorSet(
                canvas = 0x1C1714, card = 0x2F2721, surface = 0x261F1A, hairline = 0x3E342B, track = 0x3E342B,
                ink = 0xF7EEE5, ink2 = 0xCDBFB2, ink3 = 0x9C8E82,
                accent = 0xFF8A52, accentOn = 0xFF8A52, accentOnPressed = 0xFFA77D, orangeText = 0xFFB48A,
                success = 0xA9D49A, successFill = 0x2C3A2A, attention = 0xF0C46A, attentionFill = 0x3C3018,
                danger = 0xFF9C93, dangerFill = 0x432522,
                kids = listOf(0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A),
                button = TintedButtonSpec(fill = 0.20, fillPressed = 0.30, border = 0.40, borderPressed = 0.60, baseAlpha = 0.85),
                tabSelectedFill = Rgba(0xFF702A, 0.16),
                // B 夜间：一点点雾粉光晕（没有橙色光晕），与提案一致。
                wash = CanvasWash(
                    topHex = 0x2A211C, fadeEnd = 0.45,
                    spots = listOf(
                        CanvasWash.Spot(0xE6968C, 0.16, 0.90, 0.00, 1.20, 0.50),
                        CanvasWash.Spot(0x96785A, 0.14, 0.00, 0.45, 0.90, 0.40),
                        CanvasWash.Spot(0x648C5A, 0.12, 1.00, 0.90, 0.90, 0.40),
                    ),
                ),
            ),
            tabSelectedStyle = TabSelectedStyle.OrangeTint,
        )
    }
}
