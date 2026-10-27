package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对应 iOS `ParentingReminderPlannerTests`（9 个）。
 * `ParentingReminderSchedulerTests` / `ParentingReminderModelTests`（通知中心、权限、设置持久化）依赖平台通知，属 M4。
 */
class ReminderPlannerTest {
    private val calendar = T.calendar
    private val child = UUID.fromString("C1000000-0000-4000-8000-000000000001")
    private fun date(day: Int, hour: Int, minute: Int = 0): Instant = T.at(2026, 10, day, hour, minute)
    private val Planned = ParentingArrangementState.Planned
    private fun arrangement(n: Int, due: Instant, state: ParentingArrangementState = Planned, title: String = "合成安排") = ParentingArrangement(
        UUID.fromString("A" + n.toString(16).uppercase().padStart(7, '0') + "-0000-4000-8000-000000000001"), child, title + n, due, null, state, "", date(20, 8), date(20, 8),
    )
    private fun workspace(items: List<ParentingArrangement>) = ParentingWorkspace(children = listOf(ParentingChild(child, "合成小星", date(20, 8))), arrangements = items)
    private val on = ParentingReminderSettings(enabled = true, lead = ParentingReminderLead.Hour1, hideContent = false)
    private fun plan(items: List<ParentingArrangement>, now: Instant, settings: ParentingReminderSettings = on, remembered: Map<UUID, Instant> = emptyMap()) =
        ParentingReminderPlanner.plan(workspace(items), settings, now, calendar, remembered)

    @Test fun onlyConfirmedFutureArrangementsGetReminders() {
        val items = listOf(
            arrangement(1, date(26, 15), ParentingArrangementState.Draft), arrangement(2, date(26, 15), ParentingArrangementState.Cancelled),
            arrangement(3, date(26, 15), ParentingArrangementState.Completed), arrangement(4, date(26, 15)), arrangement(5, date(24, 15)),
        )
        val p = plan(items, date(25, 10))
        assertEquals(listOf(items[3].id), p.reminders.map { it.arrangementID })
        assertEquals(date(26, 14), p.reminders.first().fireAt)
        assertFalse(p.reminders.first().movedForQuietHours)
        assertTrue(p.skipped.isEmpty(), "Past, draft, cancelled and completed items are not 'skipped reminders'")
    }

    @Test fun disabledSettingsPlanNothing() {
        assertEquals(ParentingReminderPlan(emptyList(), emptyMap(), emptySet(), emptyMap()), plan(listOf(arrangement(1, date(26, 15))), date(25, 10), ParentingReminderSettings.DEFAULT))
        assertFalse(ParentingReminderSettings.DEFAULT.enabled, "reminders start off")
    }

    @Test fun quietHoursMoveTheReminderToTheEveningBefore() {
        val early = ParentingReminderPlanner.fireDate(date(26, 7, 30), ParentingReminderLead.Hour1, calendar)
        assertEquals(date(25, 20, 30), early?.date); assertEquals(true, early?.moved)
        val late = ParentingReminderPlanner.fireDate(date(26, 22), ParentingReminderLead.Minutes30, calendar)
        assertEquals(date(26, 20, 30), late?.date); assertEquals(true, late?.moved)
        assertFalse(ParentingReminderPlanner.isQuiet(date(26, 8), calendar))
        assertFalse(ParentingReminderPlanner.isQuiet(date(26, 20, 59), calendar))
        assertTrue(ParentingReminderPlanner.isQuiet(date(26, 21), calendar))
        assertTrue(ParentingReminderPlanner.isQuiet(date(26, 7, 59), calendar))
        val p = plan(listOf(arrangement(1, date(26, 7, 30))), date(25, 10))
        assertEquals(true, p.reminders.first().movedForQuietHours)
        for (r in p.reminders) assertFalse(ParentingReminderPlanner.isQuiet(r.fireAt, calendar))
    }

    @Test fun eveningBeforeLeadAndPastReminderTime() {
        val evening = ParentingReminderPlanner.fireDate(date(26, 9), ParentingReminderLead.EveningBefore, calendar)
        assertEquals(date(25, 20), evening?.date); assertEquals(false, evening?.moved)
        val settings = ParentingReminderSettings(true, ParentingReminderLead.EveningBefore, false)
        val item = arrangement(1, date(26, 9))
        val p = plan(listOf(item), date(25, 21, 30), settings)
        assertTrue(p.reminders.isEmpty())
        assertEquals(ParentingReminderSkipReason.PastReminderTime, p.skipped[item.id])
        val soon = arrangement(2, date(25, 10, 20))
        val soonPlan = plan(listOf(soon), date(25, 10), ParentingReminderSettings(true, ParentingReminderLead.Minutes30, false))
        assertEquals(ParentingReminderSkipReason.PastReminderTime, soonPlan.skipped[soon.id])
    }

    @Test fun atMostTwoRemindersPerLocalDayAndTheRestAreReportedNotDropped() {
        val items = listOf(arrangement(1, date(26, 9)), arrangement(2, date(26, 11)), arrangement(3, date(26, 13)), arrangement(4, date(27, 9)))
        val p = plan(items, date(25, 10))
        assertEquals(listOf(items[0].id, items[1].id, items[3].id), p.reminders.map { it.arrangementID })
        assertEquals(mapOf(items[2].id to ParentingReminderSkipReason.DailyLimit), p.skipped)
    }

    @Test fun aReminderThatAlreadyWentOffIsNotReportedAsMissedAndCountsTowardTheDay() {
        val a = arrangement(1, date(26, 11)); val b = arrangement(2, date(26, 11, 20)); val c = arrangement(3, date(26, 15))
        val remembered = mapOf(a.id to date(26, 10), b.id to date(26, 10, 20))
        val p = plan(listOf(a, b, c), date(26, 10, 30), remembered = remembered)
        assertEquals(remembered, p.reminded)
        assertNull(p.skipped[a.id]); assertNull(p.skipped[b.id])
        assertTrue(p.reminders.isEmpty())
        assertEquals(ParentingReminderSkipReason.DailyLimit, p.skipped[c.id], "Two reminders already went off today")
        val forgotten = plan(listOf(a, b), date(26, 10, 30))
        assertEquals(mapOf(a.id to ParentingReminderSkipReason.PastReminderTime, b.id to ParentingReminderSkipReason.PastReminderTime), forgotten.skipped)
        assertTrue(forgotten.reminded.isEmpty())
        val moved = plan(listOf(a), date(26, 10, 30), remembered = mapOf(a.id to date(25, 8)))
        assertEquals(ParentingReminderSkipReason.PastReminderTime, moved.skipped[a.id])
    }

    @Test fun planIsDeterministicAndCapsPendingCount() {
        val items = (0 until 80).map { arrangement(it + 1, date(26, 9).plusSeconds(it * 86_400L)) }
        val first = plan(items, date(25, 10)); val second = plan(items.reversed(), date(25, 10))
        assertEquals(first, second)
        assertEquals(ParentingReminderPlanner.MAX_PENDING, first.reminders.size)
        assertEquals(80 - ParentingReminderPlanner.MAX_PENDING, first.deferred.size, "The rest wait for a later sync, they are not lost")
        assertTrue(first.deferred.intersect(first.reminders.map { it.arrangementID }.toSet()).isEmpty())
    }

    @Test fun reminderPayloadBelongsOnlyToTheSchedulingAccount() {
        val subject = UUID.randomUUID()
        val info: Map<String, Any?> = mapOf(ParentingReminderPayload.MARKER to "1", ParentingReminderPayload.SUBJECT_KEY to subject.toString().lowercase())
        assertTrue(ParentingReminderPayload.belongsToCurrent(info, subject.toString().uppercase()))
        assertFalse(ParentingReminderPayload.belongsToCurrent(info, UUID.randomUUID().toString()))
        assertFalse(ParentingReminderPayload.belongsToCurrent(info, null))
        assertFalse(ParentingReminderPayload.belongsToCurrent(emptyMap(), subject.toString()))
        assertFalse(ParentingReminderPayload.isParenting(mapOf("catpaw_proactive_event" to "x")))
        assertEquals("mimi.parenting.v1.${subject.toString().lowercase()}.", ParentingReminderPayload.subjectPrefix(subject))
    }

    @Test fun deleteAllRecordsClearsEverythingButKeepsTheReviewSetting() {
        val item = arrangement(1, date(26, 15))
        val seeded = workspace(listOf(item)).copy(
            suggestionsEnabled = true,
            goals = listOf(ParentingGoal(UUID.randomUUID(), child, "合成目标", date(20, 8))),
            records = listOf(ParentingRecord(UUID.randomUUID(), child, null, null, ParentingRecordKind.Observation, "合成观察", "合成观察", date(20, 9), date(20, 9))),
        )
        val erased = seeded.applying(ParentingCommand.DeleteAllRecords, date(25, 10))
        assertTrue(erased.children.isEmpty() && erased.goals.isEmpty() && erased.arrangements.isEmpty() && erased.records.isEmpty() && erased.editHistory.isEmpty())
        assertTrue(erased.suggestionsEnabled)
        assertEquals(seeded.revision + 1, erased.revision)
        assertTrue(plan(erased.arrangements, date(25, 10)).reminders.isEmpty())
        assertNotNull(erased)
    }
}
