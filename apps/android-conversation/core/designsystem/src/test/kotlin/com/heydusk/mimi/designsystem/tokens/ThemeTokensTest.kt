package com.heydusk.mimi.designsystem.tokens

import com.heydusk.mimi.designsystem.tokens.Wcag.contrast
import com.heydusk.mimi.designsystem.tokens.Wcag.hexString
import com.heydusk.mimi.domain.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 令牌测试，逐条对应 iOS 的 CatThemeTokenTests（apps/ios-conversation/Tests/CatThemeTokenTests.swift）。
 * 纯值运算，不需要 Android。`ContrastTable` 的结果会写到 build/reports/contrast-table.md 方便查看。
 */
class ThemeTokensTest {
    private data class Combo(val name: String, val palette: Palette, val mode: Mode)

    private val combos = listOf(
        Combo("C-day", Palette.InkWhite, Mode.Light), Combo("C-night", Palette.InkWhite, Mode.Dark),
        Combo("B-day", Palette.SoftBlush, Mode.Light), Combo("B-night", Palette.SoftBlush, Mode.Dark),
    )

    private fun set(palette: Palette, mode: Mode) = ThemeTokens.of(palette).set(mode)

    // ---- 提案 JSON 里的数值（ink3 另表：有意偏离） ----
    private val ink3ProposalToShipped = mapOf(
        "C-day" to (0x8A8782 to 0x6F6D69), "C-night" to (0x76746F to 0x8F8E89),
        "B-day" to (0x94857A to 0x73655A), "B-night" to (0x9A8C80 to 0x9C8E82),
    )

    private val proposal: Map<String, Map<ColorRole, Int>> = mapOf(
        "C-day" to mapOf(
            ColorRole.Canvas to 0xFAF9F7, ColorRole.Card to 0xFFFFFF, ColorRole.Surface to 0xF2F0EC, ColorRole.Hairline to 0xE6E3DE, ColorRole.Track to 0xE6E3DE,
            ColorRole.Ink to 0x111111, ColorRole.Ink2 to 0x5A5853, ColorRole.Ink3 to 0x8A8782, ColorRole.AccentOn to 0xD15C22, ColorRole.AccentOnPressed to 0xAE4F1F,
            ColorRole.OrangeText to 0x111111, ColorRole.Success to 0x1E7B46, ColorRole.SuccessFill to 0xE7F4EC, ColorRole.Attention to 0x8A5A00,
            ColorRole.AttentionFill to 0xFFF3D6, ColorRole.Danger to 0xC0352B, ColorRole.DangerFill to 0xFDECEA, ColorRole.Accent to 0xE85D1A,
        ),
        "C-night" to mapOf(
            ColorRole.Canvas to 0x0B0B0C, ColorRole.Card to 0x1A1A1C, ColorRole.Surface to 0x262628, ColorRole.Hairline to 0x3A3A3D, ColorRole.Track to 0x3A3A3D,
            ColorRole.Ink to 0xF5F4F2, ColorRole.Ink2 to 0xA9A7A2, ColorRole.Ink3 to 0x76746F, ColorRole.AccentOn to 0xFF702A, ColorRole.AccentOnPressed to 0xFF945F,
            ColorRole.OrangeText to 0xF5F4F2, ColorRole.Success to 0x6FCF97, ColorRole.SuccessFill to 0x16281D, ColorRole.Attention to 0xF2C14E,
            ColorRole.AttentionFill to 0x2B2412, ColorRole.Danger to 0xFF8A80, ColorRole.DangerFill to 0x331A18, ColorRole.Accent to 0xFF702A,
        ),
        "B-day" to mapOf(
            ColorRole.Canvas to 0xFCF3EF, ColorRole.Card to 0xFFFCFA, ColorRole.Surface to 0xF2E6DC, ColorRole.Hairline to 0xEADBD0, ColorRole.Track to 0xF1E3D3,
            ColorRole.Ink to 0x33261D, ColorRole.Ink2 to 0x6E5C4F, ColorRole.Ink3 to 0x94857A, ColorRole.AccentOn to 0xC95821, ColorRole.AccentOnPressed to 0xAE4F20,
            ColorRole.OrangeText to 0xB8410A, ColorRole.Success to 0x3F6B3A, ColorRole.SuccessFill to 0xE6EFDA, ColorRole.Attention to 0x8F5200,
            ColorRole.AttentionFill to 0xFFEFCB, ColorRole.Danger to 0xB23B34, ColorRole.DangerFill to 0xFBE0DC, ColorRole.Accent to 0xFF702A,
        ),
        "B-night" to mapOf(
            ColorRole.Canvas to 0x1C1714, ColorRole.Card to 0x2F2721, ColorRole.Surface to 0x261F1A, ColorRole.Hairline to 0x3E342B, ColorRole.Track to 0x3E342B,
            ColorRole.Ink to 0xF7EEE5, ColorRole.Ink2 to 0xCDBFB2, ColorRole.Ink3 to 0x9A8C80, ColorRole.AccentOn to 0xFF8A52, ColorRole.AccentOnPressed to 0xFFA77D,
            ColorRole.OrangeText to 0xFFB48A, ColorRole.Success to 0xA9D49A, ColorRole.SuccessFill to 0x2C3A2A, ColorRole.Attention to 0xF0C46A,
            ColorRole.AttentionFill to 0x3C3018, ColorRole.Danger to 0xFF9C93, ColorRole.DangerFill to 0x432522, ColorRole.Accent to 0xFF8A52,
        ),
    )

    private val proposalKids = mapOf(
        "C-day" to listOf(0x3C3C43, 0x3E7CB1, 0x4A8A5F, 0x7566C2, 0xC2527A),
        "C-night" to listOf(0xD1D1D6, 0x6FA8DC, 0x6BB586, 0xA395E8, 0xE887A9),
        "B-day" to listOf(0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A),
        "B-night" to listOf(0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A),
    )

    @Test
    fun everyTokenMatchesTheProposal() {
        for (combo in combos) {
            val s = set(combo.palette, combo.mode)
            val expected = proposal.getValue(combo.name)
            assertEquals("${combo.name}: proposal table must cover every role", ColorRole.entries.size, expected.size)
            for (role in ColorRole.entries) {
                if (role == ColorRole.Ink3) {
                    val pair = ink3ProposalToShipped.getValue(combo.name)
                    assertEquals(pair.first, expected[ColorRole.Ink3])
                    assertEquals("${combo.name} ink3 is the deliberate accessibility override", pair.second, s.hex(ColorRole.Ink3))
                    continue
                }
                assertEquals("${combo.name} $role: ${hexString(s.hex(role))}", expected[role], s.hex(role))
            }
            assertEquals("${combo.name} kid colours", proposalKids[combo.name], s.kids)
        }
    }

    // ---- 对比度：文字 ≥ 4.5:1 ----
    private fun textPairs(s: ColorSet): List<Triple<String, Int, Int>> {
        val pairs = mutableListOf<Triple<String, Int, Int>>()
        for ((bgName, bg) in listOf("canvas" to s.canvas, "card" to s.card, "surface" to s.surface)) {
            pairs += listOf(
                Triple("ink/$bgName", s.ink, bg), Triple("ink2/$bgName", s.ink2, bg), Triple("ink3/$bgName", s.ink3, bg),
                Triple("orangeText/$bgName", s.orangeText, bg), Triple("success/$bgName", s.success, bg),
                Triple("attention/$bgName", s.attention, bg), Triple("danger/$bgName", s.danger, bg),
            )
        }
        pairs += listOf(
            Triple("success/successFill", s.success, s.successFill),
            Triple("attention/attentionFill", s.attention, s.attentionFill),
            Triple("danger/dangerFill", s.danger, s.dangerFill),
        )
        for ((surfaceName, surface) in listOf("canvas" to ButtonSurface.Canvas, "card" to ButtonSurface.Card)) {
            for (state in listOf(TintedButtonState.Normal, TintedButtonState.Pressed, TintedButtonState.VoiceHold)) {
                pairs += Triple("ink/button-$state-on-$surfaceName", s.ink, s.tintedButtonFill(state, surface).hex)
            }
        }
        for ((bgName, bg) in listOf("canvas" to s.canvas, "card" to s.card)) {
            pairs += Triple("ink/selected-tab-on-$bgName", s.ink, s.tabSelectedFill.composited(Rgba(bg)).hex)
        }
        return pairs
    }

    @Test
    fun everyTextPairIsAtLeast4_5() {
        val report = StringBuilder("# 文字对比度（WCAG 2.x，要求 ≥ 4.5:1）\n\n| 组合 | 配对 | 前景 | 背景 | 对比度 | 结果 |\n|---|---|---|---|---|---|\n")
        var failures = 0
        var count = 0
        var minRatio = Double.MAX_VALUE
        for (combo in combos) {
            for ((name, fg, bg) in textPairs(set(combo.palette, combo.mode))) {
                val ratio = contrast(fg, bg)
                count++
                minRatio = minOf(minRatio, ratio)
                val ok = ratio >= 4.5
                if (!ok) failures++
                report.append("| ${combo.name} | $name | ${hexString(fg)} | ${hexString(bg)} | %.2f | %s |\n".format(ratio, if (ok) "PASS" else "FAIL"))
            }
        }
        report.append("\n共 $count 对，最小 %.2f，失败 $failures。\n".format(minRatio))
        writeReport("contrast-table.md", report.toString())
        assertEquals("text pairs below 4.5:1 (see build/reports/contrast-table.md)", 0, failures)
    }

    // ---- 对比度：图形（按钮/Tab 图标）≥ 3:1 ----
    @Test
    fun accentIconsAreAtLeast3OnTheTintedButton() {
        for (combo in combos) {
            val s = set(combo.palette, combo.mode)
            for (surface in ButtonSurface.entries) {
                val normal = contrast(s.accentOn, s.tintedButtonFill(TintedButtonState.Normal, surface).hex)
                val pressed = contrast(s.accentOnPressed, s.tintedButtonFill(TintedButtonState.Pressed, surface).hex)
                assertTrue("${combo.name} accentOn on default fill = $normal", normal >= 3.0)
                assertTrue("${combo.name} accentOnPressed on pressed fill = $pressed", pressed >= 3.0)
            }
            for (bg in listOf(s.canvas, s.card)) {
                val tab = contrast(s.accentOn, s.tabSelectedFill.composited(Rgba(bg)).hex)
                assertTrue("${combo.name} tab icon = $tab", tab >= 3.0)
            }
        }
    }

    // ---- ink3：上线值在每个底上 ≥ 4.5；提案值不满足（所以才覆盖） ----
    @Test
    fun ink3IsAtLeast4_5OnEverySurfaceAndTheProposalValueWasNot() {
        for (combo in combos) {
            val s = set(combo.palette, combo.mode)
            for ((surface, bg) in listOf("canvas" to s.canvas, "card" to s.card, "surface" to s.surface)) {
                val ratio = contrast(s.ink3, bg)
                assertTrue("${combo.name} ink3 ${hexString(s.ink3)} on $surface = $ratio", ratio >= 4.5)
            }
            val proposalInk3 = ink3ProposalToShipped.getValue(combo.name).first
            val worst = listOf(s.canvas, s.card, s.surface).minOf { contrast(proposalInk3, it) }
            assertTrue("${combo.name}: proposal ink3 ${hexString(proposalInk3)} should fail 4.5 (worst $worst)", worst < 4.5)
        }
    }

    // ---- 着色按钮：各态透明度与合成色 ----
    @Test
    fun buttonAlphasPerState() {
        val cDay = set(Palette.InkWhite, Mode.Light).button
        assertEquals("C day default tint is 20 %", 0.20, cDay.fillAlpha(TintedButtonState.Normal), 0.0)
        assertEquals("pressed tint is 30 % in every combination", 0.30, cDay.fillAlpha(TintedButtonState.Pressed), 0.0)
        assertEquals(0.16, set(Palette.SoftBlush, Mode.Light).button.fillAlpha(TintedButtonState.Normal), 0.0)
        assertEquals(0.30, set(Palette.SoftBlush, Mode.Light).button.fillAlpha(TintedButtonState.Pressed), 0.0)
        for (palette in Palette.entries) {
            val night = set(palette, Mode.Dark).button
            assertEquals(0.20, night.fillAlpha(TintedButtonState.Normal), 0.0)
            assertEquals(0.30, night.fillAlpha(TintedButtonState.Pressed), 0.0)
            assertEquals(0.40, night.borderAlpha(TintedButtonState.Normal), 0.0)
            assertEquals(0.60, night.borderAlpha(TintedButtonState.Pressed), 0.0)
        }
        for (combo in combos) {
            val b = set(combo.palette, combo.mode).button
            assertEquals(0.85, b.baseAlpha, 0.0)
            assertEquals("disabled has no tint", 0.0, b.fillAlpha(TintedButtonState.Disabled), 0.0)
            assertEquals("disabled has no orange stroke", 0.0, b.borderAlpha(TintedButtonState.Disabled), 0.0)
            assertEquals(0.0, b.haloAlpha(TintedButtonState.Disabled), 0.0)
            assertEquals(0.0, b.haloAlpha(TintedButtonState.Normal), 0.0)
            assertEquals(0.0, b.haloAlpha(TintedButtonState.Pressed), 0.0)
            assertEquals(0.28, b.fillAlpha(TintedButtonState.VoiceHold), 0.0)
            assertEquals(0.65, b.borderAlpha(TintedButtonState.VoiceHold), 0.0)
            assertEquals(0.14, b.haloAlpha(TintedButtonState.VoiceHold), 0.0)
            assertTrue(b.fillAlpha(TintedButtonState.Normal) in 0.16..0.20)
            assertTrue(b.fillAlpha(TintedButtonState.Normal) < b.fillAlpha(TintedButtonState.Pressed))
        }
    }

    @Test
    fun compositedButtonColoursMatchTheProposalTable() {
        // 取自提案 JSON 的 compositedButtonFill；C 日间默认按 20% 重算，日间按下按 30% 重算，夜间沿用 JSON。
        // 每项依次为 [默认, 按下, 描边]。
        val expected = mapOf(
            "B-day" to (listOf(0xFFE5D7, 0xFFD1BA, 0xFFC6AA) to listOf(0xFFE6D9, 0xFFD2BC, 0xFFC7AB)),
            "B-night" to (listOf(0x563421, 0x6B3C22, 0x804323) to listOf(0x593623, 0x6D3D24, 0x824425)),
            "C-day" to (listOf(0xFEE2D4, 0xFED3BE, 0xFEC8AD) to listOf(0xFFE2D4, 0xFFD4BF, 0xFFC9AE)),
            "C-night" to (listOf(0x462A1D, 0x5D321F, 0x743B20) to listOf(0x482B1F, 0x5F3420, 0x763C22)),
        )
        for (combo in combos) {
            val s = set(combo.palette, combo.mode)
            val (canvas, card) = expected.getValue(combo.name)
            assertEquals("${combo.name} default/canvas", canvas[0], s.tintedButtonFill(TintedButtonState.Normal, ButtonSurface.Canvas).hex)
            assertEquals("${combo.name} pressed/canvas", canvas[1], s.tintedButtonFill(TintedButtonState.Pressed, ButtonSurface.Canvas).hex)
            assertEquals("${combo.name} border/canvas", canvas[2], s.tintedButtonBorder(TintedButtonState.Normal, ButtonSurface.Canvas).hex)
            assertEquals("${combo.name} default/card", card[0], s.tintedButtonFill(TintedButtonState.Normal, ButtonSurface.Card).hex)
            assertEquals("${combo.name} pressed/card", card[1], s.tintedButtonFill(TintedButtonState.Pressed, ButtonSurface.Card).hex)
            assertEquals("${combo.name} border/card", card[2], s.tintedButtonBorder(TintedButtonState.Normal, ButtonSurface.Card).hex)
            assertEquals("the reduce-transparency fill is opaque", 1.0, s.tintedButtonFill(TintedButtonState.Normal, ButtonSurface.Card).alpha, 0.0)
        }
    }

    @Test
    fun rgbaOverRoundsToEightBitsLikeThePublishedValues() {
        assertEquals(0x123456, Rgba(0xFF702A, 0.0).composited(Rgba(0x123456)).hex)
        assertEquals(0xFF702A, Rgba(0xFF702A, 1.0).composited(Rgba(0x123456)).hex)
        assertEquals("127.5 rounds to even (128)", 0x808080, Rgba(0xFFFFFF, 0.5).composited(Rgba(0x000000)).hex)
    }

    // ---- 结构 ----
    @Test
    fun tabSelectionStyleAndWash() {
        assertEquals(TabSelectedStyle.Neutral, ThemeTokens.InkWhite.tabSelectedStyle)
        assertEquals(TabSelectedStyle.OrangeTint, ThemeTokens.SoftBlush.tabSelectedStyle)
        assertEquals(Rgba(0x111111, 0.07), set(Palette.InkWhite, Mode.Light).tabSelectedFill)
        assertEquals(Rgba(0xFF702A, 0.12), set(Palette.SoftBlush, Mode.Light).tabSelectedFill)
        assertEquals(Rgba(0xFF702A, 0.16), set(Palette.SoftBlush, Mode.Dark).tabSelectedFill)
        assertNull(set(Palette.InkWhite, Mode.Light).wash)
        assertNull(set(Palette.InkWhite, Mode.Dark).wash)
        val glow = set(Palette.SoftBlush, Mode.Dark).wash
        assertNotNull(glow)
        assertEquals(0xE6968C, glow!!.spots[0].hex)
        assertTrue(glow.spots[0].alpha <= 0.16)
    }

    @Test
    fun tokensForPaletteSelectsTheRightSet() {
        assertEquals(Palette.InkWhite, ThemeTokens.of(Palette.InkWhite).palette)
        assertEquals(0xFAF9F7, ThemeTokens.of(Palette.InkWhite).set(Mode.Light).canvas)
        assertEquals(0xFCF3EF, ThemeTokens.of(Palette.SoftBlush).set(Mode.Light).canvas)
        assertEquals(0x1C1714, ThemeTokens.of(Palette.SoftBlush).set(Mode.Dark).canvas)
        assertEquals(0xFF702A, ThemeTokens.LOGO_ORANGE)
    }

    private fun writeReport(name: String, text: String) {
        val dir = File("build/reports")
        dir.mkdirs()
        File(dir, name).writeText(text)
    }
}
