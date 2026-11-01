package com.heydusk.mimi.feature.parenting.chrome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.feature.parenting.composer.VoiceDeps
import com.heydusk.mimi.feature.parenting.composer.VoicePrefs
import com.heydusk.mimi.feature.parenting.ui.JourneyHarness
import com.heydusk.mimi.feature.parenting.ui.UiDriver
import com.heydusk.mimi.voice.ScriptedDictationEngine
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 一行双态壳：输入 / 选 tab / 详情。动效只要求锁住连点，不要求形变。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class ParentingChromeShellTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)

    @After fun stop() = h.close()

    private fun start() {
        val deps = VoiceDeps(
            engine = ScriptedDictationEngine(),
            prefs = VoicePrefs.InMemory(explained = true),
            receiptMillis = 0,
        )
        h.launch()
        rule.setContent { h.Host(voice = deps, now = Instant.parse("2026-10-28T02:00:00Z")) }
        ui.await("ledger") { h.model()?.loaded == true }
        h.seed(ParentingCommand.AddChild(UUID.randomUUID(), "团团"))
        ui.await("input row") { ui.exists("parenting.natural.bar") && ui.exists(ParentingChromeTags.TYPE_CHIP) }
    }

    @Test fun inputIsOneRowAndPickTabReplacesIt() {
        start()
        assertFalse(ui.exists("parenting.tabBar"))
        assertTrue(ui.exists("parenting.composer.mic"))
        val bar = rule.onNodeWithTag("parenting.natural.bar").fetchSemanticsNode().boundsInRoot
        val chip = rule.onNodeWithTag(ParentingChromeTags.TYPE_CHIP).fetchSemanticsNode().boundsInRoot
        assertTrue(chip.top >= bar.top - 1f && chip.bottom <= bar.bottom + 1f)

        ui.click(ParentingChromeTags.TYPE_CHIP)
        ui.await("pick tab") { ui.exists("parenting.tab.today") && ui.exists("parenting.tab.growth") && ui.exists("parenting.tab.family") }
        assertTrue(ui.exists(ParentingChromeTags.DISMISS_PICK_TAB))
        assertTrue(ui.exists("parenting.tabBar"))
        assertFalse(ui.exists("parenting.natural.bar"))
        assertFalse(ui.exists(ParentingChromeTags.TYPE_CHIP))

        ui.click(ParentingChromeTags.DISMISS_PICK_TAB)
        ui.await("back to input") { ui.exists("parenting.natural.bar") && ui.exists(ParentingChromeTags.TYPE_CHIP) }
        assertFalse(ui.exists("parenting.tabBar"))
        assertTrue(ui.exists("parenting.page.today"))
    }

    @Test fun choosingGrowthReturnsToInputOnThatPageAndFamilyDropsTheComposer() {
        start()
        ui.click(ParentingChromeTags.TYPE_CHIP)
        ui.await("tabs") { ui.exists("parenting.tab.growth") }
        ui.click("parenting.tab.growth")
        ui.await("growth input") { ui.exists("parenting.page.growth") && ui.exists(ParentingChromeTags.TYPE_CHIP) }
        assertFalse(ui.exists("parenting.tabBar"))

        ui.click(ParentingChromeTags.TYPE_CHIP)
        ui.await("tabs again") { ui.exists("parenting.tab.family") }
        ui.click("parenting.tab.family")
        ui.await("family tabs only") { ui.exists("parenting.tabBar") && ui.exists("parenting.page.family") }
        assertFalse(ui.exists("parenting.natural.bar"))
        assertFalse(ui.exists(ParentingChromeTags.TYPE_CHIP))
    }

    @Test fun detailKeepsTheInputRowHidesTheChipAndFocusDismissesTheSheet() {
        start()
        ui.click("parenting.composer.keyboardToggle")
        ui.await("field") { ui.exists("parenting.natural.field") }
        ui.type("parenting.natural.field", "记一下，团团今天自己收好了书包")
        ui.await("send") { ui.exists("parenting.natural.send") }
        ui.click("parenting.natural.send")
        ui.await("sheet") { ui.exists("parenting.natural.sheet") }
        assertFalse(ui.exists(ParentingChromeTags.TYPE_CHIP))
        rule.onNodeWithTag("parenting.natural.bar").assertIsDisplayed()
        assertFalse(ui.exists("parenting.tabBar"))

        rule.onNodeWithTag("parenting.natural.field").performClick()
        ui.await("sheet dismissed") { !ui.exists("parenting.natural.sheet") && ui.exists(ParentingChromeTags.TYPE_CHIP) }
        assertTrue(ui.exists("parenting.natural.bar"))
    }
}
