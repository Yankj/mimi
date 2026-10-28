package com.heydusk.mimi.device.reminders

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingChild
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderOutcome
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import com.heydusk.mimi.domain.parenting.ParentingReminderSkipReason
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 对应 iOS `ParentingReminderSchedulerTests`（11 个）里与平台无关的部分：假通知中心上的对账。
 * 「模型 + 真账本」两条（`ParentingReminderModelTests`）在 `:feature:parenting` 的 `ReminderModelTest`。
 */
class ParentingReminderSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val calendar = MimiCalendar(zone)
    private val subject = UUID.fromString("D1000000-0000-4000-8000-000000000001")
    private val child = UUID.fromString("C1000000-0000-4000-8000-000000000001")
    private val on = ParentingReminderSettings(enabled = true, lead = ParentingReminderLead.Hour1, hideContent = false)
    private val copy = ReminderCopy.PLAIN

    private fun date(day: Int, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant()
    private fun item(n: Int, due: Instant, state: ParentingArrangementState = ParentingArrangementState.Planned, title: String = "合成绘本") = ParentingArrangement(
        UUID.fromString("A" + n.toString(16).uppercase().padStart(7, '0') + "-0000-4000-8000-000000000001"), child, title, due, null, state, "", date(20, 8), date(20, 8),
    )
    private fun workspace(items: List<ParentingArrangement>) = ParentingWorkspace(children = listOf(ParentingChild(child, "合成小星", date(20, 8))), arrangements = items)
    private fun scheduler(center: FakeReminderCenter, current: () -> Boolean = { true }, memory: ReminderMemory? = null) =
        ParentingReminderScheduler(center, subject, { calendar }, copy, memory, current)

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test fun schedulesConfirmedItemsAndReportsOnlyWhatTheSystemKept() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val a = item(1, date(26, 15)); val draft = item(2, date(26, 16), ParentingArrangementState.Draft)
        val status = scheduler(center).reconcile(workspace(listOf(a, draft)), on, date(25, 10))
        assertEquals(1, center.requests.size)
        assertEquals(ParentingReminderOutcome.Scheduled(date(26, 14)), status.outcomes[a.id])
        assertEquals(null, status.outcomes[draft.id])
        assertEquals(1, status.scheduledCount)
        assertFalse(status.systemRefused)
        assertTrue(center.requests.single().id.startsWith(ParentingReminderPayload.subjectPrefix(subject)))
    }

    @Test fun nothingIsClaimedWhenTheSystemKeepsNothingOrFails() = run {
        val dropping = FakeReminderCenter(NotificationAuthorization.Authorized).apply { silentlyDropAdds = true }
        val a = scheduler(dropping).reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        assertEquals(0, a.scheduledCount); assertTrue(a.systemRefused)
        val failing = FakeReminderCenter(NotificationAuthorization.Authorized).apply { failAdds = true }
        val b = scheduler(failing).reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        assertEquals(0, b.scheduledCount); assertTrue(b.systemRefused)
    }

    @Test fun reconcileIsIdempotentAndFollowsEveryLedgerChange() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val reminders = scheduler(center)
        val original = item(1, date(26, 15))
        reminders.reconcile(workspace(listOf(original)), on, date(25, 10))
        reminders.reconcile(workspace(listOf(original)), on, date(25, 10))
        assertEquals(1, center.addCount, "Unchanged plan must not re-add")
        // 改期：同一标识符，新时间与新文字。
        val moved = original.copy(dueAt = date(26, 17))
        val status = reminders.reconcile(workspace(listOf(moved)), on, date(25, 10))
        assertEquals(2, center.addCount)
        assertEquals(1, center.requests.size)
        assertEquals(ParentingReminderOutcome.Scheduled(date(26, 16)), status.outcomes[original.id])
        // 取消：待发提醒被移除。
        val afterCancel = reminders.reconcile(workspace(listOf(original.copy(state = ParentingArrangementState.Cancelled))), on, date(25, 10))
        assertTrue(center.requests.isEmpty()); assertEquals(0, afterCancel.scheduledCount)
        // 完成：同样被移除。
        reminders.reconcile(workspace(listOf(original)), on, date(25, 10))
        assertEquals(1, center.requests.size)
        reminders.reconcile(workspace(listOf(original.copy(state = ParentingArrangementState.Completed))), on, date(25, 10))
        assertTrue(center.requests.isEmpty())
        // 从账本里彻底删除。
        reminders.reconcile(workspace(listOf(original)), on, date(25, 10))
        assertEquals(1, center.requests.size)
        reminders.reconcile(workspace(emptyList()), on, date(25, 10))
        assertTrue(center.requests.isEmpty())
    }

    @Test fun remindersRememberedAcrossSyncsAndForgottenWhenNoLongerPlanned() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val memory = InMemoryReminderMemory()
        val reminders = scheduler(center, memory = memory)
        val one = item(1, date(26, 15))
        val first = reminders.reconcile(workspace(listOf(one)), on, date(25, 10))
        assertEquals(ParentingReminderOutcome.Scheduled(date(26, 14)), first.outcomes[one.id])
        assertEquals(mapOf(one.id to date(26, 14)), memory.load())
        // 系统已发出（不再待发），14:20 打开应用。
        center.remove(center.requests.map { it.id })
        val later = reminders.reconcile(workspace(listOf(one)), on, date(26, 14, 20))
        assertEquals(ParentingReminderOutcome.Reminded(date(26, 14)), later.outcomes[one.id])
        assertEquals(0, later.scheduledCount)
        assertEquals(1, center.addCount, "A reminder that already went off is never scheduled again")
        reminders.reconcile(workspace(listOf(one.copy(state = ParentingArrangementState.Completed))), on, date(26, 14, 30))
        assertTrue(memory.load().isEmpty())
    }

    @Test fun offDeniedAndUndeterminedScheduleNothingAndNeverPrompt() = run {
        val seeded = item(1, date(26, 15))
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val reminders = scheduler(center)
        reminders.reconcile(workspace(listOf(seeded)), on, date(25, 10))
        assertEquals(1, center.requests.size)
        val off = reminders.reconcile(workspace(listOf(seeded)), ParentingReminderSettings.DEFAULT, date(25, 10))
        assertTrue(center.requests.isEmpty()); assertEquals(0, off.scheduledCount)
        val denied = FakeReminderCenter(NotificationAuthorization.Denied)
        val deniedStatus = scheduler(denied).reconcile(workspace(listOf(seeded)), on, date(25, 10))
        assertEquals(0, denied.addCount); assertEquals(NotificationAuthorization.Denied, deniedStatus.authorization); assertEquals(0, deniedStatus.scheduledCount)
        val fresh = FakeReminderCenter(NotificationAuthorization.NotDetermined)
        val freshStatus = scheduler(fresh).reconcile(workspace(listOf(seeded)), on, date(25, 10))
        assertEquals(0, fresh.authorizationRequests); assertEquals(0, fresh.addCount); assertEquals(NotificationAuthorization.NotDetermined, freshStatus.authorization)
        val result = scheduler(fresh).requestAuthorization()
        assertEquals(NotificationAuthorization.Authorized, result); assertEquals(1, fresh.authorizationRequests)
    }

    @Test fun hideContentKeepsChildNameAndTitleOffTheLockScreen() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        scheduler(center).reconcile(workspace(listOf(item(1, date(26, 15), title = "合成私密事项"))), on.copy(hideContent = true), date(25, 10))
        val request = center.requests.first()
        assertFalse(request.body.contains("合成小星")); assertFalse(request.body.contains("合成私密事项"))
        assertFalse(request.title.contains("合成小星"))
    }

    @Test fun quietHourMoveIsExplainedInTheReminderText() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val status = scheduler(center).reconcile(workspace(listOf(item(1, date(26, 7, 30)))), on, date(25, 10))
        val request = center.requests.first()
        assertEquals(date(25, 20, 30), request.fireAt)
        assertTrue(request.body.contains("为避开夜间安静时段"))
        assertEquals(1, status.scheduledCount)
    }

    @Test fun accountThatIsNoLongerCurrentNeverWritesAndLeavesNothingBehind() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val status = scheduler(center, current = { false }).reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        assertEquals(0, center.addCount); assertEquals(0, status.scheduledCount)
        // 写的过程中账号丢了：已经加进去的要撤掉。
        var current = true
        val center2 = FakeReminderCenter(NotificationAuthorization.Authorized).apply { onAdd = { current = false } }
        val racing = scheduler(center2, current = { current })
        val raced = racing.reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        assertTrue(center2.addCount >= 1)
        assertTrue(center2.requests.isEmpty(), "Nothing of a signed-out account may stay queued")
        assertEquals(0, raced.scheduledCount)
        racing.clear()
        assertTrue(center2.requests.isEmpty())
    }

    @Test fun otherAccountsAndForeignNotificationsAreNeverTouched() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val other = NotificationRequest(ParentingReminderPayload.identifier(UUID.randomUUID(), UUID.randomUUID()), "t", "b", date(26, 9), emptyMap())
        center.seed(other)
        val reminders = scheduler(center)
        reminders.reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        reminders.reconcile(workspace(emptyList()), on, date(25, 10))
        reminders.clear()
        assertEquals(listOf(other.id), center.requests.map { it.id })
    }

    @Test fun timezoneChangeMovesQuietHoursAndTheDailyDay() = run {
        // 同一个安排在不同时区里对"夜间安静时段"的判断不同：时区一变，下一次对账就必须改排。
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        var current = calendar
        val reminders = ParentingReminderScheduler(center, subject, { current }, copy, null) { true }
        val due = date(26, 15) // 上海 15:00，提前 1 小时 = 14:00（白天）
        val first = reminders.reconcile(workspace(listOf(item(1, due))), on, date(25, 10))
        assertEquals(ParentingReminderOutcome.Scheduled(date(26, 14)), first.outcomes[item(1, due).id])
        // 手机飞到 UTC：同一时刻在 UTC 是 07:00，提前 1 小时 06:00 落在免打扰里，要挪到前一晚 20:30（UTC）。
        current = MimiCalendar(ZoneId.of("UTC"))
        val second = reminders.reconcile(workspace(listOf(item(1, due))), on, date(25, 10))
        val expected = ZonedDateTime.of(2026, 10, 25, 20, 30, 0, 0, ZoneId.of("UTC")).toInstant()
        assertEquals(ParentingReminderOutcome.Scheduled(expected), second.outcomes[item(1, due).id])
        assertEquals(1, center.requests.size, "同一标识符被改排，不是多排一条")
        assertEquals(expected, center.requests.single().fireAt)
    }

    @Test fun exactAlarmAvailabilityIsReportedHonestly() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized, exact = false)
        val status = scheduler(center).reconcile(workspace(listOf(item(1, date(26, 15)))), on, date(25, 10))
        assertFalse(status.exactAlarms)
        assertEquals(1, status.scheduledCount, "没有精确闹钟权限时仍然排（可能晚几分钟）")
    }

    @Test fun dailyLimitAndSkipReasonsReachTheStatus() = run {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val items = listOf(item(1, date(26, 9)), item(2, date(26, 11)), item(3, date(26, 13)))
        val status = scheduler(center).reconcile(workspace(items), on, date(25, 10))
        assertEquals(2, center.requests.size)
        assertEquals(ParentingReminderOutcome.Skipped(ParentingReminderSkipReason.DailyLimit), status.outcomes[items[2].id])
    }
}
