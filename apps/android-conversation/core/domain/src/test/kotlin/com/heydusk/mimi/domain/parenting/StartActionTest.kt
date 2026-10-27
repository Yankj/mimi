package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.common.MimiCalendar
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 iOS `ParentingStartActionTests`（33 个）。只有纯逻辑：账本值 + 注入的时钟 + 固定的 Asia/Shanghai 日历。 */
class StartActionTest {
    private val calendar = T.calendar
    private val child = UUID.fromString("D2000000-0000-4000-8000-000000000001")
    private val goal = UUID.fromString("D4000000-0000-4000-8000-000000000001")

    private fun at(day: Int, hour: Int = 0, minute: Int = 0, month: Int = 10): Instant = T.at(2026, month, day, hour, minute)
    private val monday10 get() = at(26, 10) // 2026-10-26 是周一
    private fun id(n: Int): UUID = UUID.fromString("D3000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

    private fun ledger(
        children: Boolean = true, goals: Boolean = false, arrangements: List<ParentingArrangement> = emptyList(), records: List<ParentingRecord> = emptyList(),
    ) = ParentingWorkspace(
        children = if (children) listOf(ParentingChild(child, "小星", at(1))) else emptyList(),
        goals = if (goals) listOf(ParentingGoal(goal, child, "阅读", at(1))) else emptyList(),
        arrangements = arrangements, records = records,
    )

    private fun item(n: Int, state: ParentingArrangementState, due: Instant) =
        ParentingArrangement(id(n), child, "事项$n", due, null, state, "", at(1), at(1))

    private fun record(n: Int, kind: ParentingRecordKind = ParentingRecordKind.Observation, date: Instant) =
        ParentingRecord(id(100 + n), child, null, null, kind, "观察$n", "观察$n", date, date)

    private fun today(ws: ParentingWorkspace, now: Instant, ctx: ParentingStartContext = ParentingStartContext()) =
        ParentingStartAction.resolve(ParentingStartTab.Today, ws, now, calendar, ctx)

    private fun growth(ws: ParentingWorkspace, now: Instant, ctx: ParentingStartContext = ParentingStartContext()) =
        ParentingStartAction.resolve(ParentingStartTab.Growth, ws, now, calendar, ctx)

    private fun prompt(now: Instant) = ParentingStartAction.ExamplePrompt(ParentingStartAction.examplePrompt(now, calendar))
    private val planned = ParentingArrangementState.Planned
    private val draft = ParentingArrangementState.Draft
    private val done = ParentingArrangementState.Completed
    private val cancelled = ParentingArrangementState.Cancelled

    // ---- 今天 优先级 ----
    @Test fun todayNoChildStartsWithNickname() {
        assertEquals(ParentingStartAction.StartWithNickname, today(ledger(children = false), monday10))
    }

    @Test fun todayPendingBeatsEverythingElseIncludingOverdue() {
        val ws = ledger(arrangements = listOf(item(1, draft, at(27, 9)), item(2, planned, at(24, 9)), item(3, planned, at(26, 15))))
        assertEquals(ParentingStartAction.ConfirmAll(listOf(id(1))), today(ws, monday10))
    }

    @Test fun todayOverdueBeatsNextAndNeverChangesTheLedger() {
        val ws = ledger(arrangements = listOf(item(2, planned, at(24, 9)), item(3, planned, at(26, 15))))
        assertEquals(ParentingStartAction.HandleOverdue(listOf(id(2))), today(ws, monday10))
        assertEquals(listOf(planned, planned), ws.arrangements.map { it.state }, "resolving must not complete anything")
    }

    @Test fun todayNextScheduledOffersOneTapCompleteAndPicksTheEarliestAhead() {
        val ws = ledger(arrangements = listOf(item(3, planned, at(26, 18)), item(4, planned, at(26, 13)), item(5, planned, at(27, 8))))
        assertEquals(ParentingStartAction.NextWithComplete(id(4), at(26, 13), false), today(ws, monday10))
    }

    @Test fun todayPassedButUnfinishedStillOffersCompleteAndIsFlaggedPassed() {
        val ws = ledger(arrangements = listOf(item(3, planned, at(26, 8)), item(4, planned, at(26, 9))))
        assertEquals(ParentingStartAction.NextWithComplete(id(4), at(26, 9), true), today(ws, monday10))
    }

    @Test fun todayEveningWindowIsHalfOpen1800To2100() {
        val ws = ledger(arrangements = listOf(item(1, done, at(26, 9))))
        assertEquals(prompt(at(26, 17, 59)), today(ws, at(26, 17, 59)))
        assertEquals(ParentingStartAction.RecordTodaySmallThing(child), today(ws, at(26, 18)))
        assertEquals(ParentingStartAction.RecordTodaySmallThing(child), today(ws, at(26, 20, 59)))
        assertIs<ParentingStartAction.ExamplePrompt>(today(ws, at(26, 21)), "21:00 is outside the evening window")
    }

    @Test fun todayEveningPromptSkippedWhenAnObservationWasAlreadyWrittenToday() {
        val ws = ledger(records = listOf(record(1, date = at(26, 12))))
        assertIs<ParentingStartAction.ExamplePrompt>(today(ws, at(26, 19)), "already recorded today")
    }

    @Test fun todayOtherwiseExamplePromptIsStableWithinADayAndRotatesAcrossDays() {
        val ws = ledger()
        assertEquals(today(ws, at(26, 9)), today(ws, at(26, 14)))
        val texts = (0 until 5).mapNotNull { d -> (today(ws, at(20 + d, 9)) as? ParentingStartAction.ExamplePrompt)?.text }.toSet()
        assertEquals(ParentingStartAction.examplePrompts.size, texts.size)
    }

    @Test fun todayCancelledAndFutureOnlyDoNotTriggerNext() {
        val ws = ledger(arrangements = listOf(item(1, cancelled, at(26, 15)), item(2, planned, at(28, 9))))
        assertIs<ParentingStartAction.ExamplePrompt>(today(ws, monday10), "only today's items count")
    }

    @Test fun todayScopesToTheChosenChild() {
        val other = UUID.randomUUID()
        val base = ledger(arrangements = listOf(item(1, draft, at(26, 15))))
        val ws = base.copy(children = base.children + ParentingChild(other, "小月", at(2)))
        assertEquals(prompt(monday10), today(ws, monday10, ParentingStartContext(childID = other)))
    }

    // ---- 成长 ----
    @Test fun growthNoRecordsShowsExample() {
        assertIs<ParentingStartAction.ExamplePrompt>(growth(ledger(), monday10))
        assertIs<ParentingStartAction.ExamplePrompt>(growth(ledger(children = false), monday10))
    }

    @Test fun growthWeeklyReviewOnSundayEveningFromEighteen() {
        val ws = ledger(records = listOf(record(1, date = at(22, 9))))
        assertEquals(ParentingStartAction.WeeklyReview(ParentingWeeklyReviewReason.SundayEvening), growth(ws, at(25, 18)))
        assertEquals(ParentingStartAction.WeeklyReview(ParentingWeeklyReviewReason.SundayEvening), growth(ws, at(25, 23, 30)))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ws, at(25, 17, 59)))
    }

    @Test fun growthSundayReviewNotRepeatedOnceReviewedToday() {
        val ws = ledger(records = listOf(record(1, date = at(22, 9))))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ws, at(25, 20), ParentingStartContext(lastWeeklyReviewAt = at(25, 18, 30))))
    }

    @Test fun growthSevenDaysSinceLastReviewOrSinceFirstRecord() {
        val ws = ledger(records = listOf(record(1, date = at(20, 9)), record(2, date = at(24, 9))))
        val seven = ParentingStartAction.WeeklyReview(ParentingWeeklyReviewReason.SevenDaysSinceLastReview)
        assertEquals(seven, growth(ws, at(27, 10), ParentingStartContext(lastWeeklyReviewAt = at(20, 9))))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ws, at(26, 8), ParentingStartContext(lastWeeklyReviewAt = at(20, 9))), "just under 7 days")
        assertEquals(seven, growth(ws, at(28, 10)), "never reviewed: clock starts at the first record")
    }

    @Test fun growthReviewNeedsSomethingFromTheLastWeek() {
        val ws = ledger(records = listOf(record(1, date = at(1))))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ws, at(25, 19)))
    }

    @Test fun growthFiveObservationsWithoutGoalOffersGoalButWeeklyReviewWins() {
        val five = (1..5).map { record(it, date = at(26, 6 + it)) }
        val ctx = ParentingStartContext(lastWeeklyReviewAt = at(25, 9))
        assertEquals(ParentingStartAction.GiveObservationsAGoal(child, 5), growth(ledger(records = five), at(26, 12), ctx))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ledger(goals = true, records = five), at(26, 12), ctx))
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ledger(records = five.take(4)), at(26, 12), ctx))
        val earlier = (1..5).map { record(it, date = at(23, 6 + it)) }
        assertEquals(ParentingStartAction.WeeklyReview(ParentingWeeklyReviewReason.SundayEvening), growth(ledger(records = earlier), at(25, 19)))
    }

    @Test fun growthCompletionRecordsDoNotCountAsObservations() {
        val completions = (1..5).map { record(it, ParentingRecordKind.Completion, at(26, 6 + it)) }
        assertEquals(ParentingStartAction.RecordObservation(child), growth(ledger(records = completions), at(26, 12), ParentingStartContext(lastWeeklyReviewAt = at(25, 9))))
    }

    // ---- 我们家 ----
    @Test fun familyChecklistProgressAndDisappearance() {
        fun family(ws: ParentingWorkspace, ctx: ParentingStartContext = ParentingStartContext()) =
            ParentingStartAction.resolve(ParentingStartTab.Family, ws, monday10, calendar, ctx)
        val c0 = assertIs<ParentingStartAction.LaunchChecklist>(family(ledger(children = false))).checklist
        assertEquals(0, c0.completedCount); assertEquals(ParentingLaunchChecklist.Step.AddChild, c0.nextStep)
        val c1 = assertIs<ParentingStartAction.LaunchChecklist>(family(ledger())).checklist
        assertEquals(1, c1.completedCount); assertEquals(ParentingLaunchChecklist.Step.RecordFirstThing, c1.nextStep)
        val two = ledger(records = listOf(record(1, date = at(26, 9))))
        val c2 = assertIs<ParentingStartAction.LaunchChecklist>(family(two), "optional reminders step is still offered").checklist
        assertEquals(2, c2.completedCount); assertEquals(ParentingLaunchChecklist.Step.EnableReminders, c2.nextStep)
        assertEquals(ParentingStartAction.None, family(two, ParentingStartContext(remindersEnabled = true)))
        assertEquals(ParentingStartAction.None, family(two, ParentingStartContext(remindersStepSkipped = true)))
        val c3 = assertIs<ParentingStartAction.LaunchChecklist>(family(ledger(), ParentingStartContext(remindersStepSkipped = true))).checklist
        assertEquals(1, c3.completedCount); assertEquals(ParentingLaunchChecklist.Step.RecordFirstThing, c3.nextStep)
        assertEquals(3, ParentingLaunchChecklist.TOTAL)
    }

    @Test fun familyAnArrangementCountsAsFirstThing() {
        val ws = ledger(arrangements = listOf(item(1, draft, at(27, 9))))
        assertTrue(ParentingLaunchChecklist.Step.RecordFirstThing in ParentingStartAction.launchChecklist(ws).done)
    }

    // ---- 时区 ----
    @Test fun timeZoneDecidesTheEveningWindow() {
        val ws = ledger(arrangements = listOf(item(1, done, at(26, 9))))
        val instant = at(26, 19) // 上海 19:00 = UTC 11:00
        val utc = MimiCalendar(ZoneId.of("UTC"))
        assertEquals(ParentingStartAction.RecordTodaySmallThing(child), ParentingStartAction.resolve(ParentingStartTab.Today, ws, instant, calendar))
        assertIs<ParentingStartAction.ExamplePrompt>(ParentingStartAction.resolve(ParentingStartTab.Today, ws, instant, utc), "same instant is 11:00 in UTC")
    }

    // ---- 今天首页辅助 ----
    @Test fun subtitleFormat() {
        assertEquals("3 件·已完成 1", ParentingTodayHome.subtitle(2, 1))
        assertEquals("今天暂无安排", ParentingTodayHome.subtitle(0, 0))
        val ws = ledger(arrangements = listOf(item(1, planned, at(26, 15)), item(2, planned, at(26, 16)), item(3, done, at(26, 8)), item(4, cancelled, at(26, 9)), item(5, draft, at(26, 9))))
        assertEquals("3 件·已完成 1", ParentingTodayHome.build(ws, null, monday10, calendar).subtitle)
    }

    @Test fun nowInsertionIndexIsBeforeTheFirstLaterEntry() {
        val times = listOf(at(26, 8), at(26, 12), at(26, 18))
        assertEquals(0, ParentingTodayHome.insertionIndex(at(26, 7), times))
        assertEquals(2, ParentingTodayHome.insertionIndex(at(26, 12), times), "an entry exactly at now is already past")
        assertEquals(3, ParentingTodayHome.insertionIndex(at(26, 23), times))
        assertEquals(0, ParentingTodayHome.insertionIndex(monday10, emptyList()))
    }

    @Test fun homeCapsAtFiveRowsKeepsUpcomingFirstAndRestliesInAllArrangements() {
        val plannedItems = (0 until 8).map { item(it + 1, planned, at(26, 8 + it * 2 % 14, it)) }
        val ws = ledger(arrangements = plannedItems + item(20, planned, at(27, 9)))
        val home = ParentingTodayHome.build(ws, null, monday10, calendar)
        assertEquals(5, home.rows.size)
        assertEquals(3, home.hiddenTodayCount)
        assertEquals(1, home.upcomingCount)
        assertTrue(home.hasMoreInAllArrangements)
        assertEquals(home.rows.map { it.time }, home.rows.map { it.time }.sorted(), "rows stay in time order")
        assertEquals(home.rows.count { it.time <= monday10 }, home.nowIndex)
        for (ahead in plannedItems.filter { it.dueAt > monday10 }.sortedBy { it.dueAt }.take(5)) {
            assertTrue(home.rows.any { it.id == ahead.id }, "items still ahead are kept before past ones")
        }
    }

    @Test fun homeUnderCapShowsEverythingAndNoMoreLink() {
        val ws = ledger(arrangements = listOf(item(1, planned, at(26, 15)), item(2, done, at(26, 8))))
        val home = ParentingTodayHome.build(ws, null, monday10, calendar)
        assertEquals(listOf(id(2), id(1)), home.rows.map { it.id })
        assertEquals(1, home.nowIndex)
        assertFalse(home.hasMoreInAllArrangements)
        assertEquals(0, ParentingTodayHome.build(ws, null, monday10, calendar, rowCap = 0).rows.size)
    }

    @Test fun overdueMarksAreLabelsOnlyAndNeverChangeState() {
        val passed = item(1, planned, at(26, 8)); val earlier = item(2, planned, at(25, 8)); val ahead = item(3, planned, at(26, 15))
        val doneItem = item(4, done, at(25, 8)); val canc = item(5, cancelled, at(25, 8)); val dr = item(6, draft, at(25, 8))
        fun mark(a: ParentingArrangement) = ParentingOverdueMark.mark(a, monday10, calendar)
        assertEquals(ParentingOverdueMark.PassedToday, mark(passed))
        assertEquals(ParentingOverdueMark.PreviousDay, mark(earlier))
        assertEquals(List(4) { ParentingOverdueMark.None }, listOf(ahead, doneItem, canc, dr).map(::mark))
        val ws = ledger(arrangements = listOf(passed, earlier))
        val home = ParentingTodayHome.build(ws, null, monday10, calendar)
        assertEquals(listOf(ParentingOverdueMark.PassedToday), home.rows.map { it.mark })
        assertEquals(1, home.overdueCount)
        assertEquals(listOf(planned, planned), ws.arrangements.map { it.state })
    }

    @Test fun completionUndoWindowIsThreeSecondsAndNeedsTheMatchingRecord() {
        val plannedWs = ledger(arrangements = listOf(item(1, planned, at(26, 9))))
        val doneWs = plannedWs.applying(ParentingCommand.CompleteArrangement(id(1), id(200), ""), monday10)
        val undo = assertNotNull(ParentingCompletionUndo.make(doneWs, id(1), id(200), monday10))
        assertTrue(undo.isActive(monday10.plusMillis(2900)))
        assertFalse(undo.isActive(monday10.plusSeconds(3)))
        assertNull(ParentingCompletionUndo.make(doneWs, id(1), id(201), monday10), "a retried tap that created no record has nothing to undo")
        assertNull(ParentingCompletionUndo.make(plannedWs, id(1), id(200), monday10))
    }

    // ---- reopenArrangement（纯领域）----
    @Test fun reopenRestoresPlannedAndRemovesTheAutoCompletionRecord() {
        var ws = ledger(arrangements = listOf(item(1, planned, at(26, 9)))).applying(ParentingCommand.CompleteArrangement(id(1), id(200), ""), monday10)
        assertEquals(1, ws.records.size)
        val revision = ws.revision
        ws = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10.plusSeconds(2))
        assertEquals(planned, ws.arrangements[0].state)
        assertEquals(monday10.plusSeconds(2), ws.arrangements[0].updatedAt)
        assertTrue(ws.records.isEmpty())
        assertEquals(revision + 1, ws.revision)
        ws.validate()
    }

    @Test fun completeReopenCompleteCycleIsRepeatableAndIdempotent() {
        var ws = ledger(arrangements = listOf(item(1, planned, at(26, 9))))
        for (round in 0 until 3) {
            ws = ws.applying(ParentingCommand.CompleteArrangement(id(1), id(300 + round), ""), monday10)
            assertEquals(1, ws.records.size)
            ws = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10)
            val again = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10)
            assertEquals(ws.arrangements, again.arrangements, "reopening an open arrangement changes nothing")
            assertEquals(ws.records, again.records)
            assertTrue(ws.records.isEmpty())
        }
        assertEquals(planned, ws.arrangements[0].state)
    }

    @Test fun reopenKeepsWordsTheParentWroteAsAnObservation() {
        var ws = ledger(arrangements = listOf(item(1, planned, at(26, 9)))).applying(ParentingCommand.CompleteArrangement(id(1), id(200), "今天自己选了故事"), monday10)
        ws = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10)
        assertEquals(planned, ws.arrangements[0].state)
        assertEquals(1, ws.records.size)
        assertEquals(ParentingRecordKind.Observation, ws.records[0].kind)
        assertNull(ws.records[0].arrangementID)
        assertEquals("今天自己选了故事", ws.records[0].text)
        ws.validate()
        var edited = ledger(arrangements = listOf(item(1, planned, at(26, 9)))).applying(ParentingCommand.CompleteArrangement(id(1), id(200), ""), monday10)
        edited = edited.applying(ParentingCommand.EditRecord(id(200), "改过的话"), monday10.plusSeconds(1))
        edited = edited.applying(ParentingCommand.ReopenArrangement(id(1)), monday10.plusSeconds(2))
        assertEquals(listOf("改过的话"), edited.records.map { it.text })
        assertEquals(ParentingRecordKind.Observation, edited.records.first().kind)
    }

    @Test fun reopenEdgeCases() {
        val ws = ledger(arrangements = listOf(item(1, planned, at(26, 9)), item(2, cancelled, at(26, 9)), item(3, draft, at(26, 9))))
        assertEquals(ws.arrangements, ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10).arrangements, "already open is a no-op")
        assertFailsWith<DomainError.Invalid>("cancelled stays cancelled") { ws.applying(ParentingCommand.ReopenArrangement(id(2)), monday10) }
        assertFailsWith<DomainError.Invalid>("an unconfirmed draft was never completed") { ws.applying(ParentingCommand.ReopenArrangement(id(3)), monday10) }
        assertFailsWith<DomainError.Missing>("missing arrangement") { ws.applying(ParentingCommand.ReopenArrangement(id(99)), monday10) }
    }

    @Test fun reopenAfterTheCompletionRecordWasDeleted() {
        var ws = ledger(arrangements = listOf(item(1, planned, at(26, 9)))).applying(ParentingCommand.CompleteArrangement(id(1), id(200), ""), monday10)
        ws = ws.applying(ParentingCommand.DeleteRecord(id(200)), monday10)
        assertEquals(done, ws.arrangements[0].state, "deleteRecord alone does not reopen")
        ws = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10)
        assertEquals(planned, ws.arrangements[0].state)
        assertTrue(ws.records.isEmpty())
        ws.validate()
    }

    @Test fun reopenNeverTouchesOtherRecordsAndEditHistoryStaysSuperseded() {
        val other = record(1, date = at(26, 7))
        val base = ledger(arrangements = listOf(item(1, planned, at(26, 9))), records = listOf(other))
        val target = ParentingArrangementEditTarget(base.arrangements[0], base.revision)
        var ws = base.applying(ParentingCommand.ReviseArrangement(id(50), target, "新标题", at(26, 10), null, "改", emptyList()), monday10)
        ws = ws.applying(ParentingCommand.CompleteArrangement(id(1), id(200), ""), monday10)
        val supersededAt = ws.editHistory.first().supersededAt
        assertNotNull(supersededAt)
        ws = ws.applying(ParentingCommand.ReopenArrangement(id(1)), monday10)
        assertEquals(listOf(other), ws.records)
        assertEquals(supersededAt, ws.editHistory.first().supersededAt)
        assertFalse(ws.editHistory[0].canUndo(ws), "reopening must not revive an old edit-undo")
    }

    // ---- 成长规则只在一处 ----
    @Test fun growthBaseHasOneThresholdForBothResolvers() {
        assertEquals(ParentingStartAction.GROWTH_GOAL_OBSERVATION_THRESHOLD, ParentingGrowthInsights.GOAL_SUGGESTION_THRESHOLD)
        assertEquals(ParentingStartAction.OBSERVATION_GAP_DAYS, ParentingGrowthInsights.OBSERVATION_GAP_DAYS)
        assertEquals(ParentingGrowthBase.NoRecords, ParentingStartAction.growthBase(emptyList(), false))
        val n = ParentingStartAction.GROWTH_GOAL_OBSERVATION_THRESHOLD
        val few = (0 until n - 1).map { record(it, date = at(20)) }
        val enough = (0 until n).map { record(it, date = at(20)) }
        assertEquals(ParentingGrowthBase.ContinueRecording, ParentingStartAction.growthBase(few, false))
        assertEquals(ParentingGrowthBase.GiveGoal(n), ParentingStartAction.growthBase(enough, false))
        assertEquals(ParentingGrowthBase.ContinueRecording, ParentingStartAction.growthBase(enough, true))
        val completions = (0 until n).map { record(it, ParentingRecordKind.Completion, at(20)) }
        assertEquals(ParentingGrowthBase.ContinueRecording, ParentingStartAction.growthBase(completions, false), "completions are not observations")
    }

    @Test fun growthStartCardAndTodayResolverAgreeOnOfferingAGoal() {
        val n = ParentingStartAction.GROWTH_GOAL_OBSERVATION_THRESHOLD
        // 记录都早于一周，不会触发周复盘，由共享规则决定。
        for ((count, hasGoal) in listOf(0 to false, (n - 1) to false, n to false, (n + 3) to false, n to true)) {
            val ws = ledger(goals = hasGoal, records = (0 until count).map { record(it, date = at(5)) })
            val card = ParentingGrowthStart.choose(ws, null, monday10, calendar)
            val todayAction = ParentingStartAction.resolve(ParentingStartTab.Growth, ws, monday10, calendar)
            val cardKind = card.kind
            val cardOffers = cardKind is ParentingGrowthStart.Kind.MakeGoal
            val todayOffers = todayAction is ParentingStartAction.GiveObservationsAGoal
            if (cardKind is ParentingGrowthStart.Kind.MakeGoal) assertEquals(count, cardKind.observationCount)
            if (todayAction is ParentingStartAction.GiveObservationsAGoal) assertEquals(count, todayAction.observationCount)
            assertEquals(cardOffers, todayOffers, "count $count, goal $hasGoal")
            assertEquals(count >= n && !hasGoal, cardOffers)
        }
    }

    @Test fun scopedChildIsTheStartActionActiveChild() {
        val older = UUID.randomUUID(); val newer = UUID.randomUUID()
        val ws = ParentingWorkspace(children = listOf(ParentingChild(newer, "B", at(10)), ParentingChild(older, "A", at(2))))
        for (preferred in listOf(null, older, newer, UUID.randomUUID())) {
            assertEquals(ParentingGrowthInsights.scopedChild(ws, preferred)?.id, ParentingStartAction.activeChild(ws, preferred)?.id)
        }
        assertEquals(older, ParentingStartAction.activeChild(ws, null)?.id)
    }
}
