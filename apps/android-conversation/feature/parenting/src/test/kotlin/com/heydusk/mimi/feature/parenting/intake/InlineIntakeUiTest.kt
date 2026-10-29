package com.heydusk.mimi.feature.parenting.intake

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.feature.parenting.cloud.CloudUi
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import com.heydusk.mimi.feature.parenting.forms.ArrangementEntryDialog
import com.heydusk.mimi.feature.parenting.settings.SettingsScreen
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.network.auth.AuthSnapshot
import com.heydusk.mimi.network.intake.IntakeConsentRequest
import com.heydusk.mimi.network.intake.IntakeOutcome
import com.heydusk.mimi.network.intake.MemoryIntakeJournal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class InlineIntakeUiTest {
    @get:Rule val rule = createComposeRule()
    private val subject = UUID.randomUUID()
    private val child = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun model(service: FakeIntake) = InlineIntakeModel(
        subject, service, MemoryIntakeJournal(), AccountEpoch(subject.toString()), CoroutineScope(Dispatchers.Unconfined),
        clock = { Instant.parse("2026-10-29T02:00:00Z") }, zone = { zone },
    )

    @Test fun consentThenAdoptFillsTitleAndTimeButDoesNotSave() {
        val service = FakeIntake().apply {
            consent = IntakeConsentRequest("发送前请确认", "原话会发送到所选模型服务。", "a".repeat(64), 0)
            outcome = { IntakeOutcome.Completed(reply(it)) }
        }
        val intake = model(service)
        var saved: ArrangementDraft? = null
        rule.setContent {
            MimiTheme {
                ArrangementEntryDialog(
                    onConfirm = { d, _ -> saved = d }, onDismiss = {}, goals = emptyList(), zone = zone,
                    now = Instant.parse("2026-10-29T02:00:00Z"), intake = intake, childID = child,
                )
            }
        }
        rule.onNodeWithTag("parenting.arrangement.aiIntake").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("parenting.arrangement.source").performTextInput("明天下午三点带孩子去游泳")
        rule.onNodeWithTag("parenting.arrangement.aiIntake").performScrollTo().performClick()
        rule.onNodeWithTag("parenting.inline.consent").performScrollTo().assertIsDisplayed()
        assertEquals(0, service.starts.size)
        rule.onNodeWithTag("parenting.inline.consent.allow").performScrollTo().performClick()
        rule.onNodeWithTag("parenting.inline.candidate").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("parenting.inline.adopt").performScrollTo().performClick()
        rule.onNodeWithTag("parenting.arrangement.title").assertTextContains("带孩子去游泳")
        assertEquals(null, saved, "adopting is not saving")
        rule.onNodeWithTag("parenting.arrangement.save").performScrollTo().performClick()
        assertEquals("带孩子去游泳", saved?.title)
        assertEquals(Instant.parse("2026-10-30T07:00:00Z"), saved?.dueAt)
        assertEquals("明天下午三点带孩子去游泳", saved?.sourceText)
    }

    @Test fun unknownShowsReadOnlyCheckAndStop() {
        val service = FakeIntake()
        val intake = model(service)
        rule.setContent {
            MimiTheme {
                ArrangementEntryDialog(onConfirm = { _, _ -> }, onDismiss = {}, goals = emptyList(), zone = zone, now = Instant.now(), intake = intake, childID = child)
            }
        }
        rule.onNodeWithTag("parenting.arrangement.source").performTextInput("周五晚上读绘本")
        rule.onNodeWithTag("parenting.arrangement.aiIntake").performScrollTo().performClick()
        rule.onNodeWithTag("parenting.inline.reconcile").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("parenting.inline.cancel").performScrollTo().performClick()
        rule.onNodeWithTag("parenting.arrangement.aiIntake").performScrollTo().assertIsDisplayed()
        assertEquals(1, service.cancels.size)
    }

    @Test fun noIntakeSectionWithoutCloud() {
        rule.setContent {
            MimiTheme { ArrangementEntryDialog(onConfirm = { _, _ -> }, onDismiss = {}, goals = emptyList(), zone = zone, now = Instant.now()) }
        }
        rule.onNodeWithTag("parenting.form").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount("parenting.inline"))
    }

    @Test fun settingsShowsLoginWhenConfiguredAndEnrollWhenSignedIn() {
        var opened = 0
        var ui by androidx.compose.runtime.mutableStateOf(CloudUi(configured = true))
        rule.setContent {
            MimiTheme {
                SettingsScreen(ThemePreferences(), {}, "0.1.0", 1, {}, {}, {}, cloud = ui, onOpenLogin = { opened++ }, onOpenEnrollment = { opened += 10 })
            }
        }
        rule.onNodeWithTag("parenting.settings.login").performScrollTo().performClick()
        assertEquals(1, opened)
        ui = CloudUi(configured = true, phase = AuthSnapshot.Phase.Authenticated, email = "a@example.com", enrolled = false, subjectID = subject)
        rule.onNodeWithTag("parenting.settings.account").assertTextContains("a@example.com")
        rule.onNodeWithTag("parenting.settings.enroll").performScrollTo().performClick()
        assertEquals(11, opened)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
