package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 对应 iOS `ParentingGrowthInsightsTests`（21 个）。2026-10-26（周一）20:00，Asia/Shanghai。 */
class GrowthInsightsTest {
    private val calendar = T.calendar
    private val now = at(2026, 10, 26, 20, 0)
    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0): Instant = T.at(y, m, d, h, min)
    private fun daysAgo(n: Int, hour: Int = 9): Instant = calendar.addingDays(calendar.startOfDay(now), -n).plusSeconds(hour * 3600L)
    private fun id(n: Int) = UUID.fromString("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

    private val childA = UUID.fromString("AAAAAAAA-0000-4000-8000-000000000001")
    private val childB = UUID.fromString("BBBBBBBB-0000-4000-8000-000000000002")
    private var counter = 100
    private fun nextID(): UUID { counter += 1; return id(counter) }
    private fun child(uuid: UUID, name: String, created: Instant) = ParentingChild(uuid, name, created)
    private fun record(kind: ParentingRecordKind = ParentingRecordKind.Observation, child: UUID, goal: UUID? = null, at: Instant) =
        ParentingRecord(nextID(), child, goal, null, kind, "r", "", at, at)
    private fun goal(n: Int, child: UUID, created: Instant) = ParentingGoal(id(n), child, "g$n", created)
    private fun arrangement(goal: UUID?, child: UUID, state: ParentingArrangementState, due: Instant) =
        ParentingArrangement(nextID(), child, "a", due, goal, state, "", due, due)
    private fun workspace(
        children: List<ParentingChild> = emptyList(), goals: List<ParentingGoal> = emptyList(),
        arrangements: List<ParentingArrangement> = emptyList(), records: List<ParentingRecord> = emptyList(),
    ) = ParentingWorkspace(children = children, goals = goals, arrangements = arrangements, records = records)
    private val oneChild get() = listOf(child(childA, "A", daysAgo(60)))
    private val Obs = ParentingRecordKind.Observation
    private val Comp = ParentingRecordKind.Completion

    // ---- 本周密度 ----
    @Test fun weekDensityCoversSevenDaysEndingToday() {
        val density = ParentingWeekDensity.build(workspace(children = oneChild), childA, now, calendar)
        assertEquals(7, density.days.size)
        assertEquals(calendar.startOfDay(now), density.days.last().date)
        assertEquals(calendar.startOfDay(daysAgo(6)), density.days.first().date)
        assertEquals(1, density.days.count { it.isToday })
        assertEquals(0, density.total); assertEquals(0, density.peak)
        assertEquals(0.0, density.level(density.days[0]))
    }

    @Test fun weekDensityCountsKindsPerDayAndIgnoresOtherChildrenAndOlderRecords() {
        val records = listOf(
            record(child = childA, at = daysAgo(0, 8)), record(Comp, childA, at = daysAgo(0, 18)),
            record(child = childA, at = daysAgo(2)), record(child = childA, at = daysAgo(6, 23)),
            record(child = childA, at = daysAgo(7)), record(child = childB, at = daysAgo(0)),
        )
        val ws = workspace(children = oneChild + child(childB, "B", daysAgo(30)), records = records)
        val density = ParentingWeekDensity.build(ws, childA, now, calendar)
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 2), density.days.map { it.total })
        assertEquals(1, density.days.last().observations); assertEquals(1, density.days.last().completions)
        assertEquals(4, density.total); assertEquals(3, density.activeDays); assertEquals(2, density.peak)
        assertEquals(5, ParentingWeekDensity.build(ws, null, now, calendar).total)
    }

    @Test fun weekDensityLevelsAreRelativeToPeakWithAVisibleFloor() {
        val records = (0 until 10).map { record(child = childA, at = daysAgo(0)) } + record(child = childA, at = daysAgo(3))
        val d = ParentingWeekDensity.build(workspace(children = oneChild, records = records), childA, now, calendar)
        assertEquals(1.0, d.level(d.days[6]), 1e-9)
        assertEquals(0.18, d.level(d.days[3]), 1e-9)
        assertEquals(0.0, d.level(d.days[0]))
    }

    @Test fun weekDensityWindowFollowsCalendarDaysAcrossTheMidnightBoundary() {
        val late = at(2026, 10, 26, 0, 5); val early = at(2026, 10, 25, 23, 55)
        val ws = workspace(children = oneChild, records = listOf(record(child = childA, at = late), record(child = childA, at = early)))
        val d = ParentingWeekDensity.build(ws, childA, late, calendar)
        assertEquals(1, d.days[6].total); assertEquals(1, d.days[5].total)
    }

    // ---- 旅程 ----
    @Test fun journeyGroupsByDayNewestFirstAndKeepsFilters() {
        val a = record(child = childA, at = daysAgo(0, 8)); val b = record(Comp, childA, at = daysAgo(0, 21)); val c = record(child = childA, at = daysAgo(3))
        val ws = workspace(children = oneChild, records = listOf(a, b, c))
        val all = ParentingGrowthJourney.build(ws, childA, calendar = calendar)
        assertEquals(2, all.days.size)
        assertEquals(listOf(b.id, a.id), all.days[0].items.map { it.id })
        assertEquals(listOf(c.id), all.days[1].items.map { it.id })
        assertEquals(3, all.recordCount)
        assertEquals(2, ParentingGrowthJourney.build(ws, childA, ParentingGrowthTimeline.Filter.Observation, calendar = calendar).recordCount)
        assertEquals(1, ParentingGrowthJourney.build(ws, childA, ParentingGrowthTimeline.Filter.Completion, calendar = calendar).days.size)
        assertEquals(true, ParentingGrowthJourney.build(workspace(children = oneChild), childA, calendar = calendar).isEmpty)
    }

    @Test fun journeyMarksFirstRecordAndCountMilestonesPerChild() {
        val records = (0 until 12).map { record(child = childA, at = daysAgo(40 - it, 7)) } + record(child = childB, at = daysAgo(5))
        val ws = workspace(children = oneChild + child(childB, "B", daysAgo(30)), records = records)
        val journey = ParentingGrowthJourney.build(ws, null, calendar = calendar)
        val milestones = HashMap<UUID, ParentingGrowthMilestone>()
        for (day in journey.days) for (item in day.items) if (item is ParentingGrowthDay.Item.Record && item.milestone != null) milestones[item.record.id] = item.milestone
        val ordered = records.filter { it.childID == childA }.sortedBy { it.createdAt }
        assertEquals(ParentingGrowthMilestone.FirstRecord, milestones[ordered[0].id])
        assertEquals(ParentingGrowthMilestone.RecordCount(10), milestones[ordered[9].id])
        assertNull(milestones[ordered[1].id])
        assertEquals(ParentingGrowthMilestone.FirstRecord, milestones[records.last().id])
        assertEquals(3, milestones.size)
    }

    @Test fun journeyMilestoneSurvivesFilteringAndGoalStartedOnlyInAllView() {
        val g = goal(1, childA, daysAgo(4))
        val first = record(Comp, childA, g.id, daysAgo(4, 12)); val second = record(child = childA, goal = g.id, at = daysAgo(1))
        val ws = workspace(children = oneChild, goals = listOf(g), records = listOf(first, second))
        val all = ParentingGrowthJourney.build(ws, childA, calendar = calendar)
        assertEquals(2, all.days.size)
        assertEquals(2, all.days.last().items.size)
        assertEquals(ParentingGrowthDay.Item.Record(first, ParentingGrowthMilestone.FirstRecord), all.days.last().items.first())
        val obs = ParentingGrowthJourney.build(ws, childA, ParentingGrowthTimeline.Filter.Observation, calendar = calendar)
        assertEquals(1, obs.days.size)
        assertEquals(listOf<ParentingGrowthDay.Item>(ParentingGrowthDay.Item.Record(second, null)), obs.days[0].items)
        val comp = ParentingGrowthJourney.build(ws, childA, ParentingGrowthTimeline.Filter.Completion, calendar = calendar)
        assertEquals(listOf<ParentingGrowthDay.Item>(ParentingGrowthDay.Item.Record(first, ParentingGrowthMilestone.FirstRecord)), comp.days[0].items)
        assertEquals(2, ParentingGrowthJourney.build(ws, childA, goalID = g.id, calendar = calendar).recordCount)
    }

    // ---- 目标进度 ----
    @Test fun goalProgressCountsLinkedRecordsAndArrangements() {
        val g = goal(1, childA, daysAgo(20)); val other = goal(2, childA, daysAgo(10))
        val rs = listOf(record(child = childA, goal = g.id, at = daysAgo(3)), record(Comp, childA, g.id, daysAgo(1)),
            record(child = childA, goal = other.id, at = daysAgo(30)), record(child = childA, at = daysAgo(0)))
        val P = ParentingArrangementState.Planned
        val arrangements = listOf(
            arrangement(g.id, childA, ParentingArrangementState.Completed, daysAgo(1)),
            arrangement(g.id, childA, P, daysAgo(-2)), arrangement(g.id, childA, P, daysAgo(-5)),
            arrangement(g.id, childA, ParentingArrangementState.Draft, daysAgo(-6)),
            arrangement(g.id, childA, ParentingArrangementState.Cancelled, daysAgo(-7)),
            arrangement(other.id, childA, ParentingArrangementState.Completed, daysAgo(2)),
        )
        val ws = workspace(children = oneChild, goals = listOf(other, g), arrangements = arrangements, records = rs)
        val progress = ParentingGoalProgress.build(ws, childA, now, calendar)
        assertEquals(listOf(g.id, other.id), progress.map { it.goal.id }, "creation order")
        val p = progress[0]
        assertEquals(1, p.observationCount); assertEquals(1, p.completionCount); assertEquals(2, p.recordCount)
        assertEquals(1, p.doneArrangements); assertEquals(3, p.openArrangements)
        assertEquals(0.25, assertNotNull(p.fraction), 1e-9)
        assertEquals(1, p.daysSinceActivity)
        assertEquals(ParentingGoalProgress.Activity.Recent, p.activity)
        assertEquals(daysAgo(-2), p.nextDue)
        assertEquals(listOf(true, false, false, false), p.marks.map { it.reached })
        assertEquals(1, p.reachedMarks); assertEquals(5, p.nextMark)
        assertEquals(ParentingGoalProgress.Activity.Quiet, progress[1].activity)
        assertEquals(1.0, progress[1].fraction)
    }

    @Test fun goalWithoutAnythingIsNotStartedAndHasNoRing() {
        val g = goal(1, childA, daysAgo(1))
        val p = ParentingGoalProgress.build(workspace(children = oneChild, goals = listOf(g)), childA, now, calendar)[0]
        assertEquals(ParentingGoalProgress.Activity.NotStarted, p.activity)
        assertNull(p.fraction); assertNull(p.lastActivity); assertNull(p.nextDue)
        assertEquals(0, p.reachedMarks); assertEquals(1, p.nextMark)
    }

    @Test fun goalQuietBoundaryIsMoreThanSevenDays() {
        val g = goal(1, childA, daysAgo(30))
        fun activity(days: Int) = ParentingGoalProgress.build(
            workspace(children = oneChild, goals = listOf(g), records = listOf(record(child = childA, goal = g.id, at = daysAgo(days)))), childA, now, calendar,
        )[0].activity
        assertEquals(ParentingGoalProgress.Activity.Recent, activity(7))
        assertEquals(ParentingGoalProgress.Activity.Quiet, activity(8))
    }

    @Test fun allMarksReachedLeavesNoNextMark() {
        val g = goal(1, childA, daysAgo(30))
        val rs = (0 until 20).map { record(child = childA, goal = g.id, at = daysAgo(it % 5)) }
        val p = ParentingGoalProgress.build(workspace(children = oneChild, goals = listOf(g), records = rs), childA, now, calendar)[0]
        assertEquals(4, p.reachedMarks); assertNull(p.nextMark)
    }

    // ---- 起手动作 ----
    private fun start(ws: ParentingWorkspace, child: UUID? = null) = ParentingGrowthStart.choose(ws, child, now, calendar)
    private fun K(kind: ParentingGrowthStart.Kind, id: UUID?) = ParentingGrowthStart(kind, id)

    @Test fun startWithoutChildAsksToAddOne() = assertEquals(K(ParentingGrowthStart.Kind.AddChild, null), start(workspace()))

    @Test fun startWithEmptyLedgerAsksForFirstObservation() =
        assertEquals(K(ParentingGrowthStart.Kind.FirstObservation, childA), start(workspace(children = oneChild)))

    @Test fun startWithGoalButNoRecordsPointsAtTheOldestGoal() {
        val older = goal(1, childA, daysAgo(5)); val newer = goal(2, childA, daysAgo(1))
        assertEquals(ParentingGrowthStart.Kind.RecordForGoal(older.id), start(workspace(children = oneChild, goals = listOf(newer, older))).kind)
    }

    @Test fun startOffersAGoalAfterFiveObservationsWithoutOne() {
        fun ws(n: Int) = workspace(children = oneChild, records = (0 until n).map { record(child = childA, at = daysAgo(3 + it)) })
        assertEquals(ParentingGrowthStart.Kind.RecordObservation, start(ws(4)).kind)
        assertEquals(ParentingGrowthStart.Kind.MakeGoal(5), start(ws(5)).kind)
        val mixed = workspace(children = oneChild, records = (0 until 5).map { record(Comp, childA, at = daysAgo(3 + it)) })
        assertEquals(ParentingGrowthStart.Kind.RecordObservation, start(mixed).kind)
    }

    @Test fun startDoesNotNagRightAfterARecordEvenWithAQuietGoal() {
        val g = goal(1, childA, daysAgo(30))
        val ws = workspace(children = oneChild, goals = listOf(g), records = listOf(record(child = childA, at = daysAgo(0, 8))))
        assertEquals(ParentingGrowthStart.Kind.ReviewWeek, start(ws).kind)
    }

    @Test fun startPrefersAGoalWithNoRecordThenTheLongestQuietOne() {
        val quiet9 = goal(1, childA, daysAgo(40)); val quiet20 = goal(2, childA, daysAgo(40))
        val fresh = goal(3, childA, daysAgo(40)); val empty = goal(4, childA, daysAgo(2))
        val base = listOf(record(child = childA, goal = quiet9.id, at = daysAgo(9)), record(child = childA, goal = quiet20.id, at = daysAgo(20)),
            record(child = childA, goal = fresh.id, at = daysAgo(2)))
        assertEquals(ParentingGrowthStart.Kind.RecordForGoal(empty.id), start(workspace(children = oneChild, goals = listOf(quiet9, quiet20, fresh, empty), records = base)).kind)
        assertEquals(ParentingGrowthStart.Kind.RecordForGoal(quiet20.id), start(workspace(children = oneChild, goals = listOf(quiet9, quiet20, fresh), records = base)).kind)
        val recent = record(child = childA, goal = fresh.id, at = daysAgo(1))
        assertEquals(ParentingGrowthStart.Kind.ReviewWeek, start(workspace(children = oneChild, goals = listOf(fresh), records = listOf(recent))).kind)
        assertEquals(ParentingGrowthStart.Kind.RecordObservation, start(workspace(children = oneChild, goals = listOf(fresh), records = listOf(base[2]))).kind, "all goals recent, but two silent days")
    }

    @Test fun startAsksForAnObservationAfterTwoDaysOfSilenceElseReviewsTheWeek() {
        fun ws(daysSince: Int) = workspace(children = oneChild, records = listOf(record(child = childA, at = daysAgo(daysSince))))
        assertEquals(ParentingGrowthStart.Kind.ReviewWeek, start(ws(1)).kind)
        assertEquals(ParentingGrowthStart.Kind.RecordObservation, start(ws(2)).kind)
    }

    @Test fun startIsScopedToTheRequestedChildAndFallsBackToTheEarliest() {
        val kids = listOf(child(childB, "B", daysAgo(10)), child(childA, "A", daysAgo(60)))
        val ws = workspace(children = kids, records = listOf(record(child = childA, at = daysAgo(0, 7))))
        assertEquals(K(ParentingGrowthStart.Kind.FirstObservation, childB), start(ws, childB))
        assertEquals(K(ParentingGrowthStart.Kind.ReviewWeek, childA), start(ws, null))
        assertEquals(childA, start(ws, UUID.randomUUID()).childID, "an unknown child falls back to the earliest")
    }

    @Test fun startIsDeterministic() {
        val g = goal(1, childA, daysAgo(30))
        val ws = workspace(children = oneChild, goals = listOf(g), records = listOf(record(child = childA, goal = g.id, at = daysAgo(12))))
        assertEquals(start(ws), start(ws))
    }

    // ---- 强调色槽 ----
    @Test fun accentSlotFollowsCreationOrderAndWraps() {
        val ids = (1..7).map { id(it) }
        val kids = ids.mapIndexed { i, u -> child(u, "k$i", daysAgo(100 - i)) }
        val ws = workspace(children = kids.reversed())
        assertEquals(listOf(0, 1, 2, 3, 4, 0, 1), ids.map { ParentingGrowthInsights.accentSlot(it, ws) })
        assertEquals(0, ParentingGrowthInsights.accentSlot(null, ws))
        assertEquals(0, ParentingGrowthInsights.accentSlot(UUID.randomUUID(), ws))
    }
}
