package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.feature.parenting.ui.JourneyHarness
import com.heydusk.mimi.feature.parenting.ui.UiDriver
import com.heydusk.mimi.voice.ScriptedDictationEngine
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 输入卡的版式：浅色/深色/大字号下都停在底栏正上方、不出屏、不压住内容；大字号强制键盘；渲染不是空白。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp-xxhdpi")
class ComposerLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)

    @After fun stop() = h.close()

    private fun start(dark: Boolean, fontScale: Float) {
        val deps = VoiceDeps(engine = ScriptedDictationEngine(), prefs = VoicePrefs.InMemory(explained = true), receiptMillis = 0)
        h.launch()
        rule.setContent { h.Host(dark = dark, fontScale = fontScale, voice = deps, now = Instant.parse("2026-10-28T02:00:00Z")) }
        ui.await("ledger") { h.model()?.loaded == true }
        h.seed(ParentingCommand.AddChild(UUID.randomUUID(), "团团"))
        ui.await("composer") { ui.exists("parenting.natural.bar") }
    }

    private fun checkSits() {
        val bar = rule.onNodeWithTag("parenting.natural.bar").fetchSemanticsNode().boundsInRoot
        val tabs = rule.onNodeWithTag("parenting.tabBar").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bar.bottom <= tabs.top + 1f, "输入卡在底栏之上: bar=$bar tabs=$tabs")
        assertTrue(bar.left >= root.left && bar.right <= root.right + 1f, "输入卡不出屏: $bar")
        assertTrue(bar.height > 0f && bar.width > 0f)
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertTrue(pixels.toSet().size > 8, "渲染不是空白")
        java.io.File("build/screenshots").apply { mkdirs() }.resolve("composer-${System.nanoTime()}.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun sitsAboveTheTabBarInLight() {
        start(dark = false, fontScale = 1f)
        checkSits()
        assertTrue(ui.exists("parenting.composer.mic"), "标准字号下语音优先")
    }

    @Test fun sitsAboveTheTabBarInDark() {
        start(dark = true, fontScale = 1f)
        checkSits()
        assertTrue(ui.exists("parenting.composer.mic"))
    }

    @Test fun largeFontsForceTheKeyboardAndStillFit() {
        start(dark = false, fontScale = 1.8f)
        ui.await("keyboard forced") { ui.exists("parenting.natural.field") }
        assertTrue(ui.exists("parenting.composer.keyboardToggle"), "大字号下仍可切回语音")
        checkSits()
    }

    @Test fun twoHundredPercentDarkStillFits() {
        start(dark = true, fontScale = 2f)
        ui.await("keyboard forced") { ui.exists("parenting.natural.field") }
        checkSits()
        assertEquals(true, ui.exists("parenting.natural.bar"))
    }
}
