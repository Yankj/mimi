package com.heydusk.mimi.feature.parenting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三 Tab 空壳的 UI 测试（屏幕按 411x891dp，避免悬浮底栏遮住滚动内容的点击）（Robolectric，在 Linux JVM 上跑）。
 * 测试标签与 iOS 的 accessibilityIdentifier 相同：parenting.tabBar、parenting.tab.today/growth/family。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class ParentingHomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var preferences by mutableStateOf(ThemePreferences())

    private fun show(initial: ThemePreferences = ThemePreferences()) {
        preferences = initial
        rule.setContent {
            MimiTheme(palette = preferences.palette, appearance = preferences.appearance) {
                ParentingHomeScreen(preferences = preferences, onPreferencesChange = { preferences = it })
            }
        }
    }

    @Test
    fun tabIdsMatchIos() {
        assertEquals(listOf("today", "growth", "family"), ParentingTab.entries.map { it.id })
        assertEquals(listOf("今天", "成长", "我们家"), ParentingTab.entries.map { it.titleKey })
        assertEquals("parenting.tab.today", ParentingTab.Today.tag)
        assertEquals("parenting.tabBar", ParentingTab.TAB_BAR_TAG)
    }

    @Test
    fun shellShowsTabBarWithThreeTabsAndStartsOnToday() {
        show()
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.today").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("parenting.tab.growth").assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithTag("parenting.tab.family").assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithTag("parenting.title").assertTextEquals("Today")
    }

    @Test
    fun tappingTabsSwitchesPagesAndSelection() {
        show()
        rule.onNodeWithTag("parenting.tab.growth").performClick()
        rule.onNodeWithTag("parenting.page.growth").assertIsDisplayed()
        rule.onNodeWithTag("parenting.title").assertTextEquals("Growth")
        rule.onNodeWithTag("parenting.tab.growth").assertIsSelected()
        rule.onNodeWithTag("parenting.tab.today").assertIsNotSelected()

        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.page.family").assertIsDisplayed()
        rule.onNodeWithTag("parenting.title").assertTextEquals("Our family")
        rule.onNodeWithTag("parenting.tab.family").assertIsSelected()

        rule.onNodeWithTag("parenting.tab.today").performClick()
        rule.onNodeWithTag("parenting.title").assertTextEquals("Today")
    }

    @Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
    @Test
    fun chineseLocaleShowsChineseTitlesFromUiStrings() {
        show()
        rule.onNodeWithTag("parenting.title").assertTextEquals("今天")
        rule.onNodeWithTag("parenting.tab.growth").performClick()
        rule.onNodeWithTag("parenting.title").assertTextEquals("成长")
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.title").assertTextEquals("我们家")
    }

    @Test
    fun familyPageThemeControlsChangePaletteAndAppearance() {
        show()
        assertEquals(Palette.InkWhite, preferences.palette)
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.palette.inkWhite").assertIsSelected()
        rule.onNodeWithTag("parenting.palette.softBlush").performScrollTo().assertIsNotSelected().performClick()
        rule.waitForIdle()
        assertEquals(Palette.SoftBlush, preferences.palette)
        rule.onNodeWithTag("parenting.palette.softBlush").assertIsSelected()
        // 配色与外观是两条独立的轴：改配色不动外观。
        assertEquals(Appearance.System, preferences.appearance)
        rule.onNodeWithTag("parenting.appearance.system").assertIsSelected()
        rule.onNodeWithTag("parenting.appearance.dark").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Appearance.Dark, preferences.appearance)
        assertEquals(Palette.SoftBlush, preferences.palette)
        rule.onNodeWithTag("parenting.appearance.dark").assertIsSelected()
    }

    @Test
    fun tintedButtonPreviewRendersEnabledAndDisabled() {
        show()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.demo.tintedButton").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("parenting.demo.tintedButtonDisabled").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shellRendersInAllFourThemeCombinations() {
        show()
        for (palette in Palette.entries) {
            for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                rule.runOnIdle { preferences = ThemePreferences(palette, appearance) }
                rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
                rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
            }
        }
    }
}
