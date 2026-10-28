package com.heydusk.mimi.feature.parenting.reminders

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.heydusk.mimi.device.reminders.FakeReminderCenter
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import com.heydusk.mimi.feature.parenting.ui.JourneyHarness
import com.heydusk.mimi.feature.parenting.ui.UiDriver
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M4 旅程：提醒开关、权限流程、安排上的提醒行、提醒页、点通知回到今天、退出登录清空——真实 Compose + 真实加密账本 + 假系统通知中心。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class ReminderJourneyTest {
    @get:Rule val rule = createComposeRule()
    private val ui = UiDriver(rule)
    private val child = UUID.randomUUID()
    private var h: JourneyHarness = JourneyHarness()

    /** 后天 15:00（UTC）：一小时提前量是 14:00，不在免打扰里；日期相对真实时钟，所以永远在未来。 */
    private fun due(hour: Int = 15): Instant =
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(2).atTime(hour, 0).toInstant(ZoneOffset.UTC)

    private fun boot(center: FakeReminderCenter?, seedArrangement: Boolean = true): UUID {
        h = JourneyHarness(center)
        h.launch()
        rule.setContent { h.Host() }
        ui.await("ledger loaded") { h.model()?.loaded == true }
        h.seed(ParentingCommand.AddChild(child, "团团"))
        val id = UUID.randomUUID()
        if (seedArrangement) h.seed(ParentingCommand.CreateDraft(id, child, "游泳课", due(), null, ""), ParentingCommand.ConfirmArrangement(id))
        ui.await("today") { ui.exists("parenting.tab.today") && ui.exists("parenting.page.today") }
        return id
    }

    @After fun stop() = h.close()

    private fun openAll() {
        ui.click("parenting.tab.today")
        if (!ui.exists("parenting.today.addArrangement")) ui.click("parenting.today.allArrangements")
        ui.await("all page") { ui.exists("parenting.today.addArrangement") }
    }

    private fun openRemindersPage() {
        ui.click("parenting.tab.family")
        ui.click("parenting.family.reminders")
        ui.await("reminders page") { ui.exists("parenting.reminders.toggle") || ui.exists("parenting.family.reminders.unavailable") }
    }

    @Test fun todayPromptAsksFirstThenEnablesAndTheArrangementShowsAConfirmedTime() {
        val center = FakeReminderCenter(NotificationAuthorization.NotDetermined)
        val id = boot(center)
        ui.await("prompt") { ui.exists("parenting.reminders.prompt") }
        assertEquals(0, center.authorizationRequests, "说明在前，系统权限框在后：打开应用不会自己弹")
        assertTrue(center.requests.isEmpty())
        ui.click("parenting.reminders.enable")
        ui.await("prompt gone") { !ui.exists("parenting.reminders.prompt") }
        assertEquals(1, center.authorizationRequests)
        assertEquals(1, center.requests.size)
        openAll()
        ui.await("reminder line") { ui.exists("parenting.arrangement.reminder.$id") }
        assertTrue(ui.text("parenting.arrangement.reminder.$id").contains("将在"))
    }

    @Test fun deniedPermissionShowsTheOpenSettingsCardAndComingBackWithPermissionFillsInTheReminders() {
        val center = FakeReminderCenter(NotificationAuthorization.Denied)
        val id = boot(center)
        ui.click("parenting.reminders.enable")
        ui.await("denied card") { ui.exists("parenting.reminders.denied") }
        assertEquals(0, center.authorizationRequests, "已经被拒绝：不再弹框，只引导去系统设置")
        ui.click("parenting.reminders.openSettings")
        assertEquals(listOf("notifications"), h.openedSettings.toList())
        center.status = NotificationAuthorization.Authorized // 家长在系统设置里打开了通知，回到应用
        h.refreshReminders()
        ui.await("denied card gone") { !ui.exists("parenting.reminders.denied") }
        assertEquals(1, center.requests.size)
        openAll()
        ui.await("reminder line") { ui.exists("parenting.arrangement.reminder.$id") }
    }

    @Test fun remindersPageSwitchesLeadHideContentAndExplainsHonestly() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        boot(center)
        ui.click("parenting.reminders.enable")
        ui.await("queued") { center.requests.size == 1 }
        val original = center.requests.single().fireAt
        openRemindersPage()
        ui.await("count") { ui.exists("parenting.reminders.count") }
        assertTrue(ui.text("parenting.reminders.count").contains("1"))
        ui.click("parenting.reminders.lead.minutes30")
        ui.await("rescheduled") { center.requests.singleOrNull()?.fireAt == original.plusSeconds(1800) }
        ui.click("parenting.reminders.hideContent")
        ui.await("hidden") { center.requests.singleOrNull()?.body?.contains("游泳课") == false }
        assertEquals(ParentingReminderSettings(true, ParentingReminderLead.Minutes30, true), h.reminderSettings.load())
        ui.click("parenting.reminders.toggle")
        ui.await("queue emptied") { center.requests.isEmpty() }
        assertFalse(ui.exists("parenting.reminders.lead"), "关闭后只剩开关")
    }

    @Test fun dailyLimitAndPastTimeAreReportedPerArrangementNotSilentlyDropped() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        boot(center, seedArrangement = false)
        val ids = (15..17).map { hour -> UUID.randomUUID().also { h.seed(ParentingCommand.CreateDraft(it, child, "课$hour", due(hour), null, ""), ParentingCommand.ConfirmArrangement(it)) } }
        ui.click("parenting.reminders.enable")
        ui.await("two queued") { center.requests.size == 2 }
        openAll()
        ui.await("lines") { ids.all { ui.exists("parenting.arrangement.reminder.$it") } }
        assertTrue(ui.text("parenting.arrangement.reminder.${ids[0]}").contains("将在"))
        assertTrue(ui.text("parenting.arrangement.reminder.${ids[2]}").contains("当天已有 2 条"))
    }

    @Test fun withoutExactAlarmPermissionThePageSaysSoAndOffersTheSystemSettings() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized, exact = false)
        boot(center)
        ui.click("parenting.reminders.enable")
        ui.await("queued") { center.requests.size == 1 }
        openRemindersPage()
        ui.await("exact notice") { ui.exists("parenting.reminders.exactAlarm") }
        ui.click("parenting.reminders.exactAlarm.open")
        ui.click("parenting.reminders.battery.open")
        assertEquals(listOf("exactAlarms", "battery"), h.openedSettings.toList())
    }

    @Test fun tappingTheNotificationReturnsToTodayFromAnyScreen() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val id = boot(center)
        openAll()
        rule.runOnIdle { h.reminderOpen = ReminderOpenRequest(id.toString(), 1) }
        ui.await("today home") { ui.exists("parenting.page.today") && !ui.exists("parenting.back") }
        ui.click("parenting.tab.growth")
        ui.await("growth") { ui.exists("parenting.page.growth") }
        rule.runOnIdle { h.reminderOpen = ReminderOpenRequest(id.toString(), 2) }
        ui.await("today again") { ui.exists("parenting.page.today") }
    }

    @Test fun shellWithoutReminderAuthorityShowsHonestUnavailableCopy() {
        boot(center = null)
        ui.click("parenting.tab.family")
        ui.await("row") { ui.exists("parenting.family.reminders") }
        assertTrue(ui.text("parenting.family.reminders").contains("暂不发送提醒"))
        ui.click("parenting.family.reminders")
        ui.await("unavailable") { ui.exists("parenting.family.reminders.unavailable") }
        assertFalse(ui.exists("parenting.reminders.toggle"))
        ui.click("parenting.tab.today")
        assertFalse(ui.exists("parenting.reminders.prompt"))
    }

    @Test fun signingOutClearsTheQueueAndTheAccountsReminderSettings() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        boot(center)
        ui.click("parenting.reminders.enable")
        ui.await("queued") { center.requests.size == 1 }
        h.signOut()
        assertTrue(center.requests.isEmpty(), "退出登录后不能有任何提醒留在队列里")
        assertEquals(1, h.signedOutReminders)
        assertEquals(ParentingReminderSettings.DEFAULT, h.reminderSettings.load())
    }

    @Test fun reminderStateSurvivesAColdRelaunchBecauseTheLedgerIsReplanned() {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        boot(center)
        ui.click("parenting.reminders.enable")
        ui.await("queued") { center.requests.size == 1 }
        // 模拟重启：系统把闹钟清空，应用冷启动后按账本重排。
        runBlockingRemoveAll(center)
        assertTrue(center.requests.isEmpty())
        h.launch()
        ui.await("reloaded") { h.model()?.loaded == true }
        ui.await("replanned") { center.requests.size == 1 }
    }

    private fun runBlockingRemoveAll(center: FakeReminderCenter) =
        kotlinx.coroutines.runBlocking { center.remove(center.requests.map { it.id }) }
}
