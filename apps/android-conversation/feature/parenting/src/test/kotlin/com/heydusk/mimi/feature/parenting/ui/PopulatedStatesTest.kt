package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 有数据的三个 Tab：孩子 / 目标 / 已确认的安排 / 待确认草稿 / 观察记录，以及各表单只在「保存」时回调。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class PopulatedStatesTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.now()
    private val child = UUID.randomUUID()
    private val goal = UUID.randomUUID()
    private val planned = UUID.randomUUID()
    private val draft = UUID.randomUUID()
    private val record = UUID.randomUUID()

    private fun build(withDraft: Boolean): ParentingWorkspace = listOfNotNull<ParentingCommand>(
        ParentingCommand.AddChild(child, "Xiaoming"),
        ParentingCommand.AddGoal(goal, child, "Read every day"),
        ParentingCommand.CreateDraft(planned, child, "Read together", now.plusSeconds(120), goal, ""),
        ParentingCommand.ConfirmArrangement(planned),
        ParentingCommand.CreateDraft(draft, child, "Piano", now.plusSeconds(240), null, "").takeIf { withDraft },
        ParentingCommand.AddObservation(record, child, goal, "Finished a whole chapter"),
    ).fold(ParentingWorkspace.EMPTY) { w, c -> w.applying(c, now) }

    private var workspace: ParentingWorkspace = build(withDraft = false)

    private class Calls {
        val confirmed = mutableListOf<UUID>()
        val completed = mutableListOf<UUID>()
        val goals = mutableListOf<Pair<UUID, String>>()
        val observations = mutableListOf<Pair<UUID, String>>()
        var undo = 0
    }

    private fun shell(calls: Calls, undoSeconds: Int? = null) = rule.setContent {
        MimiTheme {
            ParentingHomeScreen(
                preferences = ThemePreferences(),
                onPreferencesChange = {},
                workspace = workspace,
                loaded = true,
                error = null,
                onboardingDone = true,
                privacyShield = false,
                undoSeconds = undoSeconds,
                versionName = "0.1.0",
                versionCode = 1,
                onAddChild = {},
                onAddGoal = { c, t -> calls.goals += c to t },
                onAddArrangement = { _, _ -> },
                onAddObservation = { c, t -> calls.observations += c to t },
                onConfirmArrangement = { calls.confirmed += it },
                onCompleteArrangement = { calls.completed += it },
                onUndoCompletion = { calls.undo += 1 },
                onCompleteOnboarding = {},
                onClearData = {},
                onSignOut = {},
            )
        }
    }

    @Test fun todayShowsTheHeroAndOneTapCompleteCallsBack() {
        val calls = Calls()
        shell(calls)
        rule.onNodeWithTag("parenting.today.hero").assertIsDisplayed()
        rule.onNodeWithTag("parenting.complete.$planned").performClick()
        assertEquals(listOf(planned), calls.completed)
    }

    @Test fun draftsAreListedForConfirmationAndConfirmCallsBackOnlyOnTap() {
        workspace = build(withDraft = true)
        val calls = Calls()
        shell(calls)
        rule.onNodeWithTag("parenting.confirm.$draft").assertIsDisplayed()
        assertEquals(emptyList(), calls.confirmed)
        rule.onNodeWithTag("parenting.hero.confirmAll").performClick()
        assertEquals(listOf(draft), calls.confirmed)
    }

    @Test fun undoBarAppearsWithTheCountdownAndCallsBack() {
        val calls = Calls()
        shell(calls, undoSeconds = 5)
        rule.onNodeWithTag("parenting.undo.bar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.undo").performClick()
        assertEquals(1, calls.undo)
    }

    @Test fun growthShowsGoalsAndRecordsAndTheFormsOnlyCallBackOnSave() {
        val calls = Calls()
        shell(calls)
        rule.onNodeWithTag("parenting.tab.growth").performClick()
        rule.onNodeWithTag("parenting.growth.week").assertIsDisplayed()
        rule.onNodeWithTag("parenting.goal.$goal").assertIsDisplayed()
        rule.onNodeWithTag("parenting.record.body.$record").assertIsDisplayed()

        rule.onNodeWithTag("parenting.growth.addGoal").performClick()
        rule.onNodeWithTag("parenting.goal.title").performTextInput("Swim")
        rule.onNodeWithTag("parenting.form.discard").performClick()
        assertEquals(emptyList(), calls.goals)
        rule.onNodeWithTag("parenting.growth.addGoal").performClick()
        rule.onNodeWithTag("parenting.goal.title").performTextInput("Swim")
        rule.onNodeWithTag("parenting.goal.save").performClick()
        assertEquals(listOf(child to "Swim"), calls.goals)

        rule.onNodeWithTag("parenting.growth.addObservation").performClick()
        rule.onNodeWithTag("parenting.record.text").performTextInput("Shared a toy")
        rule.onNodeWithTag("parenting.record.save").performClick()
        assertEquals(listOf(child to "Shared a toy"), calls.observations)
    }

    @Test fun familyShowsTheChildCard() {
        val calls = Calls()
        shell(calls)
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.child.$child").assertIsDisplayed()
        // 孩子 + 已确认安排 → 必做的前两步已完成，启动清单收起
        rule.onNodeWithTag("parenting.family.launch").assertDoesNotExist()
        assertNull(calls.confirmed.firstOrNull())
    }

    @Test fun settingsOpensAndCloses() {
        shell(Calls())
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.settings").performClick()
        rule.onNodeWithTag("parenting.settings").assertIsDisplayed()
        rule.onNodeWithTag("parenting.settings.signOut").assertIsDisplayed()
        rule.onNodeWithTag("parenting.settings.close").performClick()
        rule.onNodeWithTag("parenting.settings").assertDoesNotExist()
    }
}
