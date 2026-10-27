package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.parenting.ParentingFollowUp.Label
import com.heydusk.mimi.domain.parenting.T.at
import com.heydusk.mimi.domain.parenting.T.calendar
import com.heydusk.mimi.domain.parenting.T.duo
import com.heydusk.mimi.domain.parenting.T.now
import com.heydusk.mimi.domain.parenting.T.swim
import com.heydusk.mimi.domain.parenting.T.tuan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 iOS `ParentingNaturalInputTests`（20 个）。纯合成数据：没有模型、网络、通知中心。 */
class NaturalInputTest {
    private fun ctx(children: List<ParentingChild>, arrangements: List<ParentingArrangement> = emptyList()) = ParentingNaturalContext(children, arrangements)
    private fun classify(text: String, c: ParentingNaturalContext) = ParentingNaturalInput.classify(text, c, now, calendar)
    private fun ws(children: List<ParentingChild>, arrangements: List<ParentingArrangement> = emptyList()) = T.workspace(children, arrangements)
    private fun apply(answer: ParentingNaturalAnswer, p: ParentingNaturalProposal, c: ParentingNaturalContext) =
        ParentingNaturalInput.applying(answer, p, c, now, calendar)

    private fun run(p: ParentingNaturalProposal, w: ParentingWorkspace): ParentingWorkspace {
        var next = w
        for (command in ParentingNaturalInput.commands(p, w, ParentingNaturalInput.WriteIDs())) next = next.applying(command, now)
        return next
    }

    @Test fun newArrangementIsClassifiedAsCaptureWithExtractedFields() {
        val p = classify("团团周五下午5点游泳", ctx(listOf(tuan, duo)))
        assertEquals(ParentingIntentKind.NewArrangement, p.kind)
        assertEquals(ParentingIntentMode.Capture, p.mode)
        assertEquals(tuan.id, p.childID)
        assertEquals("游泳", p.title)
        assertEquals(at(2026, 10, 30, 17, 0), p.dueAt)
        assertTrue(p.missing.isEmpty())
        assertTrue(p.isReadyToConfirm)
    }

    @Test fun observationIsClassifiedAndKeepsTheParentsWords() {
        val p = classify("记一下，今天他自己收好书包了", ctx(listOf(tuan)))
        assertEquals(ParentingIntentKind.Observation, p.kind)
        assertEquals(ParentingIntentMode.Capture, p.mode)
        assertEquals(tuan.id, p.childID, "one child: bound without asking")
        assertEquals("今天他自己收好书包了", p.observationText)
        assertTrue(p.missing.isEmpty())
    }

    @Test fun futurePlansWithADateAreNotMistakenForObservations() {
        val p = classify("记一下周一要带水彩笔", ctx(listOf(tuan)))
        assertEquals(ParentingIntentKind.NewArrangement, p.kind)
        assertEquals(listOf(ParentingMissingField.Time), p.missing, "a date without a clock asks only for the time")
    }

    @Test fun queriesAreClassifiedAsPlanAndReviewAndNeverWrite() {
        val w = ws(listOf(tuan))
        val c = ctx(listOf(tuan))
        val today = classify("今天团团有什么安排", c)
        assertEquals(ParentingIntentKind.ViewToday, today.kind); assertEquals(ParentingIntentMode.Plan, today.mode)
        val plan = classify("看看兴趣班怎么排", c)
        assertEquals(ParentingIntentKind.ViewPlan, plan.kind); assertEquals(ParentingIntentMode.Plan, plan.mode)
        val review = classify("回顾一下团团最近的阅读", c)
        assertEquals(ParentingIntentKind.ReviewGrowth, review.kind); assertEquals(ParentingIntentMode.Review, review.mode)
        for (p in listOf(today, plan, review)) {
            assertFalse(p.kind.writes)
            assertTrue(p.missing.isEmpty())
            assertTrue(ParentingNaturalInput.commands(p, w, ParentingNaturalInput.WriteIDs()).isEmpty())
            val card = ParentingConfirmCard.make(p, w, null, now)
            assertEquals(false, card?.writes)
        }
    }

    @Test fun rescheduleBindsTheExistingArrangementAndInheritsItsDay() {
        val existing = swim(at(2026, 10, 30, 17, 0))
        val p = classify("团团周五游泳改到下午6点了", ctx(listOf(tuan), listOf(existing)))
        assertEquals(ParentingIntentKind.Reschedule, p.kind)
        assertEquals(existing.id, p.targetID)
        assertEquals(at(2026, 10, 30, 18, 0), p.dueAt)
        assertTrue(p.inheritedDate)
        assertTrue(p.missing.isEmpty())
    }

    @Test fun intentDetectionIsStableWithoutAnyChild() {
        assertEquals(ParentingIntentKind.Unknown, classify("嗯", ctx(emptyList())).kind)
    }

    // 缺字段追问（一次一个，只问缺的）
    @Test fun askingForTheChildOnlyWhenSeveralChildrenAndNoneIsNamed() {
        val c = ctx(listOf(tuan, duo))
        val p = classify("周五下午5点游泳", c)
        assertEquals(listOf(ParentingMissingField.Child), p.missing)
        val ask = ParentingNaturalInput.followUp(p, c, calendar)
        assertEquals(ParentingMissingField.Child, ask?.field)
        assertEquals(listOf<Label>(Label.Verbatim("团团"), Label.Verbatim("朵朵")), ask?.options?.map { it.label })
        val answered = apply(ParentingNaturalAnswer.Child(duo.id), p, c)
        assertTrue(answered.isReadyToConfirm)
        assertEquals(duo.id, answered.childID)
        assertEquals(at(2026, 10, 30, 17, 0), answered.dueAt, "already clear fields are not asked or changed again")
        assertNull(ParentingNaturalInput.followUp(answered, c, calendar))
    }

    @Test fun namedChildIsNeverAsked() {
        val p = classify("朵朵明天下午3点钢琴课", ctx(listOf(tuan, duo)))
        assertEquals(duo.id, p.childID)
        assertTrue(p.missing.isEmpty())
    }

    @Test fun missingDateThenTimeAreAskedOneAtATime() {
        val c = ctx(listOf(tuan))
        var p = classify("游泳课", c)
        assertEquals(ParentingIntentKind.NewArrangement, p.kind)
        assertEquals(ParentingMissingField.Date, ParentingNaturalInput.followUp(p, c, calendar)?.field)
        p = apply(ParentingNaturalAnswer.Text("周五"), p, c)
        assertEquals(listOf(ParentingMissingField.Time), p.missing)
        assertEquals(ParentingMissingField.Time, ParentingNaturalInput.followUp(p, c, calendar)?.field)
        p = apply(ParentingNaturalAnswer.Text("下午5点"), p, c)
        assertTrue(p.isReadyToConfirm)
        assertEquals("游泳课", p.title)
        assertEquals(at(2026, 10, 30, 17, 0), p.dueAt)
    }

    @Test fun anUnclearHourOffersMorningAndAfternoonInsteadOfGuessing() {
        val c = ctx(listOf(tuan))
        val p = classify("周五5点游泳", c)
        assertNull(p.dueAt, "no silent default hour")
        assertEquals(listOf(ParentingMissingField.Time), p.missing)
        val ask = ParentingNaturalInput.followUp(p, c, calendar)!!
        assertEquals(listOf<Label>(Label.DateTime(at(2026, 10, 30, 5, 0)), Label.DateTime(at(2026, 10, 30, 17, 0))), ask.options.map { it.label })
        val done = apply(ask.options[1].answer, p, c)
        assertEquals(at(2026, 10, 30, 17, 0), done.dueAt)
        assertTrue(done.isReadyToConfirm)
    }

    @Test fun rescheduleWithAnUnclearHourOffersTheOriginalHalfOfTheDayFirst() {
        val existing = swim(at(2026, 10, 30, 17, 0))
        val c = ctx(listOf(tuan), listOf(existing))
        val p = classify("团团周五游泳改到六点", c)
        assertEquals(ParentingIntentKind.Reschedule, p.kind)
        assertEquals(listOf(ParentingMissingField.Time), p.missing)
        val ask = ParentingNaturalInput.followUp(p, c, calendar)
        assertEquals(Label.DateTime(at(2026, 10, 30, 18, 0)), ask?.options?.first()?.label)
    }

    @Test fun rescheduleToTheSameTimeAsksAgainAndTwoCandidatesAskWhichOne() {
        val a = swim(at(2026, 10, 30, 17, 0))
        val b = swim(at(2026, 10, 31, 9, 0), title = "游泳课")
        val c = ctx(listOf(tuan), listOf(a, b))
        val same = classify("游泳改到下午5点", ctx(listOf(tuan), listOf(a)))
        assertEquals(listOf(ParentingMissingField.Time), same.missing, "an unchanged time is not a reschedule")
        val ambiguous = classify("游泳改到下午6点", c)
        assertEquals(listOf(ParentingMissingField.Target), ambiguous.missing)
        assertEquals(2, ParentingNaturalInput.followUp(ambiguous, c, calendar)?.options?.size)
    }

    @Test fun unknownIntentAsksOnceAndKeepsTheAnswer() {
        val c = ctx(listOf(tuan))
        val p = classify("嗯", c)
        assertEquals(listOf(ParentingMissingField.Intent), p.missing)
        val ask = ParentingNaturalInput.followUp(p, c, calendar)
        assertEquals(2, ask?.options?.size)
        val obs = apply(ParentingNaturalAnswer.Intent(ParentingIntentKind.Observation), p, c)
        assertEquals(ParentingIntentKind.Observation, obs.kind)
        assertEquals("嗯", obs.observationText)
    }

    @Test fun emptyAndOversizedInputNeverBecomeAProposal() {
        assertFalse(classify("   ", ctx(listOf(tuan))).isReadyToConfirm)
        assertFalse(classify("游".repeat(3001), ctx(listOf(tuan))).isReadyToConfirm)
    }

    // 确认卡与写入路径
    @Test fun cardAppearsOnlyWhenReadyAndWritesNothingUntilCommandsAreApplied() {
        val c = ctx(listOf(tuan))
        val w = ws(listOf(tuan))
        val missing = classify("游泳课", c)
        assertNull(ParentingConfirmCard.make(missing, w, null, now))
        assertTrue(ParentingNaturalInput.commands(missing, w, ParentingNaturalInput.WriteIDs()).isEmpty())

        val ready = classify("团团周五下午5点游泳", c)
        val card = assertNotNull(ParentingConfirmCard.make(ready, w, true, now))
        assertTrue(card.writes)
        assertEquals("确认安排", card.confirmTitle)
        assertTrue(ParentingConfirmCard.Block.Quote("你说的原话", "团团周五下午5点游泳") in card.blocks)
        assertTrue(ParentingConfirmCard.Block.Note("提醒按你的设置发送：每天最多 2 条，21:00–08:00 不打扰。", ParentingConfirmCard.Tone.Normal) in card.blocks)
        assertEquals(0, w.arrangements.size, "building a card changes nothing")

        val next = run(ready, w)
        assertEquals(1, next.arrangements.size)
        assertEquals(ParentingArrangementState.Planned, next.arrangements[0].state)
        assertEquals(at(2026, 10, 30, 17, 0), next.arrangements[0].dueAt)
        assertEquals("团团周五下午5点游泳", next.arrangements[0].sourceText)
    }

    @Test fun observationCommandStoresOnlyAnObservation() {
        val w = ws(listOf(tuan))
        val p = classify("记一下，今天他自己收好书包了", ctx(listOf(tuan)))
        val next = run(p, w)
        assertEquals(1, next.records.size)
        assertEquals(ParentingRecordKind.Observation, next.records[0].kind)
        assertEquals("今天他自己收好书包了", next.records[0].text)
        assertTrue(next.arrangements.isEmpty())
    }

    @Test fun rescheduleCardShowsBeforeAfterAndSameTimeConflictsAreAcknowledgedOnConfirm() {
        val existing = swim(at(2026, 10, 30, 17, 0))
        val other = swim(at(2026, 10, 30, 18, 0), title = "钢琴课")
        val w = ws(listOf(tuan), listOf(existing, other))
        val p = classify("团团周五游泳改到下午6点", ParentingNaturalContext(w))
        val card = assertNotNull(ParentingConfirmCard.make(p, w, null, now))
        assertTrue(ParentingConfirmCard.Block.Change("时间", existing.dueAt, at(2026, 10, 30, 18, 0)) in card.blocks)
        assertTrue(card.blocks.any { it is ParentingConfirmCard.Block.Conflicts && it.items.map { i -> i.title } == listOf("钢琴课") })
        val next = run(p, w)
        assertEquals(at(2026, 10, 30, 18, 0), next.arrangements.first { it.id == existing.id }.dueAt)
        assertEquals(1, next.editHistory.size, "the change is recorded and can be undone")
        assertEquals(other.dueAt, next.arrangements.first { it.id == other.id }.dueAt, "other arrangements are untouched")
    }

    // 今天时间线与提醒上限
    @Test fun todayTimelineOrdersByTimeSeparatesPendingAndMarksNow() {
        val early = swim(at(2026, 10, 25, 8, 30)); val later = swim(at(2026, 10, 25, 17, 0))
        val twin = swim(at(2026, 10, 25, 17, 0), title = "钢琴课")
        val draft = swim(at(2026, 10, 27, 9, 0), ParentingArrangementState.Draft)
        val overdue = swim(at(2026, 10, 23, 9, 0))
        val tomorrow = swim(at(2026, 10, 26, 9, 0))
        val cancelled = swim(at(2026, 10, 25, 12, 0), ParentingArrangementState.Cancelled)
        val w = ws(listOf(tuan), listOf(later, twin, early, draft, overdue, tomorrow, cancelled))
        val timeline = ParentingTodayTimeline.build(w, null, now, calendar)
        assertEquals(listOf(draft.id), timeline.pending.map { it.id })
        assertEquals(listOf(overdue.id), timeline.overdue.map { it.id })
        assertEquals(listOf(tomorrow.id), timeline.upcoming.map { it.id })
        assertEquals(3, timeline.entries.size, "cancelled items never show")
        assertEquals(early.id, timeline.entries.first().id)
        assertEquals(1, timeline.nowIndex, "the now marker sits after the 08:30 item")
        assertEquals(2, timeline.entries.count { it.sameTimeConflict })
        assertEquals(1, timeline.summary.pending)
        assertEquals(3, timeline.summary.today)
        assertNotNull(timeline.summary.next)
    }

    @Test fun growthTimelineGroupsObservationsAndCompletionsByDay() {
        val d1 = at(2026, 10, 24, 20, 0); val d2 = at(2026, 10, 25, 9, 0)
        val w = ParentingWorkspace(
            revision = 1, children = listOf(tuan),
            records = listOf(
                T.record(ParentingRecordKind.Observation, d1, "a"), T.record(ParentingRecordKind.Observation, d2, "b"),
                T.record(ParentingRecordKind.Observation, d2.plusSeconds(60), "c"),
            ),
        )
        val all = ParentingGrowthTimeline.build(w, calendar = calendar)
        assertEquals(2, all.days.size)
        assertEquals(listOf("c", "b"), all.days.first().records.map { it.text }, "newest day and newest record first")
        assertTrue(ParentingGrowthTimeline.build(w, filter = ParentingGrowthTimeline.Filter.Completion, calendar = calendar).isEmpty)
    }

    @Test fun reminderLimitAndQuietHoursStayAtTwoAndTwentyOneToEight() {
        assertEquals(2, ParentingReminderPlanner.MAX_PER_DAY)
        assertEquals(21, ParentingReminderPlanner.QUIET_START_HOUR)
        assertEquals(8, ParentingReminderPlanner.QUIET_END_HOUR)
        val items = listOf(at(2026, 10, 26, 10, 0), at(2026, 10, 26, 14, 0), at(2026, 10, 26, 16, 0)).map { swim(it) }
        val w = ws(listOf(tuan), items)
        val plan = ParentingReminderPlanner.plan(w, ParentingReminderSettings(enabled = true, lead = ParentingReminderLead.Minutes30), now, calendar)
        assertEquals(2, plan.reminders.size)
        assertEquals(1, plan.skipped.values.count { it == ParentingReminderSkipReason.DailyLimit })
        assertTrue(plan.reminders.all { !ParentingReminderPlanner.isQuiet(it.fireAt, calendar) })
    }
}
