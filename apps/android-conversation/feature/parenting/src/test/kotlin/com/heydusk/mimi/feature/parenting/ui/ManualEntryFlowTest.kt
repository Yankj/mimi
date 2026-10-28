package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import com.heydusk.mimi.feature.parenting.forms.ChildEntryDialog
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class ManualEntryFlowTest {
    @get:Rule val rule = createComposeRule()

    private fun shell(actions: RecordingActions = RecordingActions()) = rule.setContent {
        MimiTheme {
            ParentingHomeScreen(
                preferences = ThemePreferences(),
                onPreferencesChange = {},
                workspace = ParentingWorkspace.EMPTY,
                loaded = true,
                error = null,
                onboardingDone = true,
                privacyShield = false,
                undoSeconds = null,
                versionName = "0.1.0",
                versionCode = 1,
                actions = actions,
                onCompleteOnboarding = {},
                onClearData = {},
                onSignOut = {},
            )
        }
    }

    @Test fun discardChildDialogDoesNotConfirm() {
        var confirmed: String? = null
        var dismissed = false
        rule.setContent { MimiTheme { ChildEntryDialog(onConfirm = { confirmed = it }, onDismiss = { dismissed = true }) } }
        rule.onNodeWithTag("parenting.form").assertIsDisplayed()
        rule.onNodeWithTag("parenting.form.discard").performClick()
        assertEquals(null, confirmed)
        assertEquals(true, dismissed)
    }

    @Test fun confirmChildDialogWritesOnlyOnSave() {
        var confirmed: String? = null
        rule.setContent { MimiTheme { ChildEntryDialog(onConfirm = { confirmed = it }, onDismiss = {}) } }
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        assertEquals(null, confirmed) // 输入过程零写入
        rule.onNodeWithTag("parenting.name.save").performClick()
        assertEquals("Xiaoming", confirmed)
    }

    @Test fun threeTabsShowRealEmptyStatesFromLedger() {
        shell()
        rule.onNodeWithTag("parenting.today.hero").assertIsDisplayed()
        rule.onNodeWithTag("parenting.hero.addChild").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.growth").performClick()
        rule.onNodeWithTag("parenting.growth.empty").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.empty").assertIsDisplayed()
        rule.onNodeWithTag("parenting.family.addChild").assertIsDisplayed()
    }

    @Test fun openingAddChildFromFamilyShowsFormAndDiscardClosesWithoutCallback() {
        val actions = RecordingActions()
        shell(actions)
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.addChild").performClick()
        rule.onNodeWithTag("parenting.form").assertIsDisplayed()
        rule.onNodeWithTag("parenting.form.discard").performClick()
        assertEquals(emptyList(), actions.children)
        rule.onNodeWithTag("parenting.form").assertDoesNotExist()
    }
}
