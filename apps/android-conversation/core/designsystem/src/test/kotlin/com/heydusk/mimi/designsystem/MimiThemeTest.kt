package com.heydusk.mimi.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.heydusk.mimi.designsystem.tokens.Mode
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric 下检查 MimiTheme 提供的令牌与 Material 色板：四种组合 + 跟随系统。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MimiThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private var palette by mutableStateOf(Palette.default)
    private var appearance by mutableStateOf(Appearance.default)
    private var colors: MimiColors? = null
    private var background: Color? = null

    private fun show() {
        rule.setContent {
            MimiTheme(palette = palette, appearance = appearance) {
                colors = LocalMimiColors.current
                background = MaterialTheme.colorScheme.background
            }
        }
        rule.waitForIdle()
    }

    private fun set(p: Palette, a: Appearance) {
        rule.runOnIdle { palette = p; appearance = a }
        rule.waitForIdle()
    }

    @Test
    fun explicitAppearancesResolveForBothPalettes() {
        show()
        val cases = listOf(
            Triple(Palette.InkWhite, Appearance.Light, 0xFAF9F7), Triple(Palette.InkWhite, Appearance.Dark, 0x0B0B0C),
            Triple(Palette.SoftBlush, Appearance.Light, 0xFCF3EF), Triple(Palette.SoftBlush, Appearance.Dark, 0x1C1714),
        )
        for ((p, a, canvas) in cases) {
            set(p, a)
            assertEquals("$p/$a canvas", Color(canvas or 0xFF000000.toInt()), colors!!.canvas)
            assertEquals("$p/$a Material background = canvas", colors!!.canvas, background)
            assertEquals(p, colors!!.tokens.palette)
        }
    }

    @Test
    fun defaultIsInkWhiteAndFollowsTheSystem() {
        show()
        assertEquals(Palette.InkWhite, colors!!.tokens.palette)
        assertEquals("Robolectric 默认是浅色系统", Mode.Light, colors!!.mode)
    }

    @Config(sdk = [35], qualifiers = "night")
    @Test
    fun followSystemPicksDarkWhenTheSystemIsNight() {
        show()
        assertEquals(Mode.Dark, colors!!.mode)
        assertEquals(Color(0xFF0B0B0C.toInt()), colors!!.canvas)
    }

    @Config(sdk = [35], qualifiers = "night")
    @Test
    fun explicitLightWinsOverNightSystem() {
        show()
        set(Palette.SoftBlush, Appearance.Light)
        assertEquals(Mode.Light, colors!!.mode)
        assertEquals(Color(0xFFFCF3EF.toInt()), colors!!.canvas)
    }
}
