package com.heydusk.mimi

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import android.os.Looper
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 冒烟 + 端到端：真实的 MainActivity（Hilt 图、本机匿名会话、加密账本）能起来，过首次引导后显示三 Tab；
 * 设置里点选配色会落进 DataStore（键 "palette"）；手动添加孩子写进加密账本，放弃则不写。
 * 测试用的图把 DataStore 与 Keystore 换成临时文件与 JCA 内存密钥库（见 TestAppModule）。
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp", application = HiltTestApplication::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var store: DataStore<Preferences>
    @Inject lateinit var session: ParentingSession

    @Before
    fun inject() = hilt.inject()

    /** 写入与会话在 IO 线程、状态收集在（Robolectric 的）主 Looper：每轮先放行主 Looper 再检查。 */
    private fun await(what: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; tags=" + rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }.joinToString(","))
    }

    private fun passOnboarding() {
        await("onboarding") { rule.onAllNodesWithTag("parenting.onboarding").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.onboarding.continue").performClick()
        await("shell") { rule.onAllNodesWithTag("parenting.tabBar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun readyModel() = (session.phase.value as ParentingSession.Phase.Ready).model

    @Test
    fun firstLaunchShowsOnboardingThenTheShellOnToday() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
        rule.onNodeWithTag("parenting.hero.addChild").assertIsDisplayed()
    }

    @Test
    fun applicationIdIsFixed() {
        assertEquals("com.heydusk.mimi", RuntimeEnvironment.getApplication().packageName)
    }

    @Test
    fun pickingAPaletteInSettingsIsWrittenToDataStoreUnderTheIosKey() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.settings").performClick()
        rule.onNodeWithTag("parenting.palette.softBlush").performScrollTo().performClick()
        awaitStored("palette", "softBlush")
        rule.onNodeWithTag("parenting.palette.softBlush").assertIsSelected()
        rule.onNodeWithTag("parenting.appearance.dark").performScrollTo().performClick()
        awaitStored("theme", "dark")
        awaitStored("palette", "softBlush")
    }

    private fun awaitStored(key: String, expected: String) {
        await("$key=$expected") { runBlocking { store.data.first()[stringPreferencesKey(key)] } == expected }
    }

    @Test
    fun addingAChildThroughTheFormIsSavedInTheEncryptedLedgerAndDiscardWritesNothing() {
        passOnboarding()
        await("ledger loaded") { runCatching { readyModel().loaded }.getOrDefault(false) }
        // 放弃：零写入
        rule.onNodeWithTag("parenting.hero.addChild").performClick()
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        rule.onNodeWithTag("parenting.form.discard").performClick()
        await("form closed") { rule.onAllNodesWithTag("parenting.form").fetchSemanticsNodes().isEmpty() }
        assertTrue(readyModel().workspace.children.isEmpty())
        assertEquals(0, readyModel().workspace.revision)
        // 保存：账本里有，Family 页显示
        rule.onNodeWithTag("parenting.hero.addChild").performClick()
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        rule.onNodeWithTag("parenting.name.save").performClick()
        await("child saved") { readyModel().workspace.children.size == 1 }
        assertEquals("Xiaoming", readyModel().workspace.children.single().name)
        rule.onNodeWithTag("parenting.tab.family").performClick()
        await("child on family") { rule.onAllNodesWithTag("parenting.family.child.${readyModel().workspace.children.single().id}").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun composerShowsAfterAChildExistsAndKeyboardPathWritesOnlyAfterConfirm() {
        passOnboarding()
        await("ledger loaded") { runCatching { readyModel().loaded }.getOrDefault(false) }
        assertTrue("没有孩子时不显示输入卡", rule.onAllNodesWithTag("parenting.natural.bar").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("parenting.hero.addChild").performClick()
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        rule.onNodeWithTag("parenting.name.save").performClick()
        await("composer") { rule.onAllNodesWithTag("parenting.composer.mic").fetchSemanticsNodes().isNotEmpty() }
        // 第一次点话筒：先解释再申请权限；家长可以选“改用键盘”，此时不申请权限、不开会话。
        rule.onNodeWithTag("parenting.composer.mic").performClick()
        await("explanation") { rule.onAllNodesWithTag("parenting.composer.voiceExplain").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.composer.voiceExplain.keyboard").performClick()
        await("keyboard field") { rule.onAllNodesWithTag("parenting.natural.field").fetchSemanticsNodes().isNotEmpty() }
        val revision = readyModel().workspace.revision
        rule.onNodeWithTag("parenting.natural.field").performTextInput("记一下，今天自己收拾了书包")
        await("send") { rule.onAllNodesWithTag("parenting.natural.send").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.natural.send").performClick()
        await("card") { rule.onAllNodesWithTag("parenting.natural.confirm").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("确认之前零写入", revision, readyModel().workspace.revision)
        rule.onNodeWithTag("parenting.natural.confirm").performClick()
        await("record saved") { readyModel().workspace.records.size == 1 }
    }

    @Test
    fun settingsShowsHowVoiceRecognitionWorksOnThisPhone() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.settings").performClick()
        rule.onNodeWithTag("parenting.settings.voice").performScrollTo().assertIsDisplayed()
    }

    // ---- M4：本机提醒的生产接线（真实 Hilt 图：AlarmManager/NotificationManager 的 Robolectric 影子，不是假中心）----

    private fun seedPlanned(title: String = "Swim"): java.util.UUID {
        val child = java.util.UUID.randomUUID()
        val id = java.util.UUID.randomUUID()
        val due = java.time.Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC)
            .withHour(15).withMinute(0).withSecond(0).withNano(0).toInstant()
        runBlocking {
            val model = readyModel()
            model.apply(com.heydusk.mimi.domain.parenting.ParentingCommand.AddChild(child, "Xiaoming"))
            model.apply(com.heydusk.mimi.domain.parenting.ParentingCommand.CreateDraft(id, child, title, due, null, "src"))
            model.apply(com.heydusk.mimi.domain.parenting.ParentingCommand.ConfirmArrangement(id))
        }
        return id
    }

    @Test
    fun todayOffersRemindersAndTurningThemOnSchedulesARealAlarm() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        passOnboarding()
        await("ledger loaded") { runCatching { readyModel().loaded }.getOrDefault(false) }
        seedPlanned()
        await("prompt") { rule.onAllNodesWithTag("parenting.reminders.prompt").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.reminders.enable").performClick()
        await("alarm scheduled") {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size == 1
        }
        await("prompt gone") { rule.onAllNodesWithTag("parenting.reminders.prompt").fetchSemanticsNodes().isEmpty() }
        assertTrue(readyModel().state.value.reminderSettings.enabled)
        // 退出登录：闹钟、设置一并清掉。
        runBlocking { session.signOutAndStartFresh() }
        await("alarms cleared") {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty()
        }
        assertEquals(false, readyModel().state.value.reminderSettings.enabled)
    }

    @Test
    fun familyHasARemindersPageInTheRealApp() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.reminders").performScrollTo().performClick()
        await("page") { rule.onAllNodesWithTag("parenting.reminders.toggle").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun tappingAReminderNotificationReturnsToTodayAndAForeignAccountIsIgnored() {
        tapBody()
    }

    /** `rule.activity`/`scenario.onActivity` 会让 Robolectric 下的 scenario.close() 卡住；直接取前台 Activity。 */
    private fun resumedActivity(): MainActivity =
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()

    private fun tapBody() {
        passOnboarding()
        await("ledger loaded") { runCatching { readyModel().loaded }.getOrDefault(false) }
        val subject = (session.phase.value as ParentingSession.Phase.Ready).subjectID.toString()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        await("family") { rule.onAllNodesWithTag("parenting.page.family").fetchSemanticsNodes().isNotEmpty() }
        resumedActivity().let {
            it.onNewIntent(com.heydusk.mimi.device.reminders.ReminderOpen.intent(it, "00000000-0000-4000-8000-000000000000", "a1"))
        }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onNodeWithTag("parenting.tab.family").assertIsSelected() // 别的账号：不动
        resumedActivity().let {
            it.onNewIntent(com.heydusk.mimi.device.reminders.ReminderOpen.intent(it, subject, "a2"))
        }
        await("today") { rule.onAllNodesWithTag("parenting.page.today").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
    }
}
