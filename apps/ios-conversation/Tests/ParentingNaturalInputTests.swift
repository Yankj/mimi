import Foundation
import XCTest
@testable import cat

/// Pure, synthetic tests: no model, network, notification centre or simulator state. They prove the local rules only.
final class ParentingNaturalInputTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Shanghai")!; return c
    }()
    /// Sunday 2026-10-25 10:00 (Asia/Shanghai).
    private var now: Date { at(2026, 10, 25, 10, 0) }
    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }
    private let tuan = ParentingChild(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000A1")!, name: "团团", createdAt: Date(timeIntervalSince1970: 1_700_000_000))
    private let duo = ParentingChild(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000A2")!, name: "朵朵", createdAt: Date(timeIntervalSince1970: 1_700_000_001))

    private func swim(due: Date, state: ParentingArrangementState = .planned, child: ParentingChild? = nil) -> ParentingArrangement {
        let c = child ?? tuan
        return ParentingArrangement(id: UUID(), childID: c.id, title: "游泳课", dueAt: due, goalID: nil, state: state,
                                    sourceText: "手动", createdAt: Date(timeIntervalSince1970: 1_700_000_100), updatedAt: Date(timeIntervalSince1970: 1_700_000_100))
    }
    private func context(children: [ParentingChild], arrangements: [ParentingArrangement] = []) -> ParentingNaturalContext {
        .init(children: children, arrangements: arrangements)
    }
    private func classify(_ text: String, _ ctx: ParentingNaturalContext) -> ParentingNaturalProposal {
        ParentingNaturalInput.classify(text, context: ctx, now: now, calendar: calendar)
    }
    private func workspace(children: [ParentingChild], arrangements: [ParentingArrangement] = []) -> ParentingWorkspace {
        ParentingWorkspace(revision: 1, children: children, arrangements: arrangements)
    }

    // MARK: Intent classification
    func testNewArrangementIsClassifiedAsCaptureWithExtractedFields() {
        let p = classify("团团周五下午5点游泳", context(children: [tuan, duo]))
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertEqual(p.mode, .capture)
        XCTAssertEqual(p.childID, tuan.id)
        XCTAssertEqual(p.title, "游泳")
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertTrue(p.missing.isEmpty)
        XCTAssertTrue(p.isReadyToConfirm)
    }

    func testObservationIsClassifiedAndKeepsTheParentsWords() {
        let p = classify("记一下，今天他自己收好书包了", context(children: [tuan]))
        XCTAssertEqual(p.kind, .observation)
        XCTAssertEqual(p.mode, .capture)
        XCTAssertEqual(p.childID, tuan.id, "one child: bound without asking")
        XCTAssertEqual(p.observationText, "今天他自己收好书包了")
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testFuturePlansWithADateAreNotMistakenForObservations() {
        let p = classify("记一下周一要带水彩笔", context(children: [tuan]))
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertEqual(p.missing, [.time], "a date without a clock asks only for the time")
    }

    func testQueriesAreClassifiedAsPlanAndReviewAndNeverWrite() {
        let ws = workspace(children: [tuan])
        let ctx = context(children: [tuan])
        let today = classify("今天团团有什么安排", ctx)
        XCTAssertEqual(today.kind, .viewToday); XCTAssertEqual(today.mode, .plan)
        let plan = classify("看看兴趣班怎么排", ctx)
        XCTAssertEqual(plan.kind, .viewPlan); XCTAssertEqual(plan.mode, .plan)
        let review = classify("回顾一下团团最近的阅读", ctx)
        XCTAssertEqual(review.kind, .reviewGrowth); XCTAssertEqual(review.mode, .review)
        for p in [today, plan, review] {
            XCTAssertFalse(p.kind.writes)
            XCTAssertTrue(p.missing.isEmpty)
            XCTAssertTrue(ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()).isEmpty)
            let card = ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now)
            XCTAssertEqual(card?.writes, false)
        }
    }

    func testRescheduleBindsTheExistingArrangementAndInheritsItsDay() {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        let ctx = context(children: [tuan], arrangements: [existing])
        let p = classify("团团周五游泳改到下午6点了", ctx)
        XCTAssertEqual(p.kind, .reschedule)
        XCTAssertEqual(p.targetID, existing.id)
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 18, 0))
        XCTAssertTrue(p.inheritedDate)
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testIntentDetectionIsStableWithoutAnyChild() {
        XCTAssertEqual(classify("嗯", context(children: [])).kind, .unknown)
    }

    // MARK: Missing-field follow-up (one question, only what is missing)
    func testAskingForTheChildOnlyWhenSeveralChildrenAndNoneIsNamed() {
        let ctx = context(children: [tuan, duo])
        let p = classify("周五下午5点游泳", ctx)
        XCTAssertEqual(p.missing, [.child])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.field, .child)
        XCTAssertEqual(ask?.options.map(\.label), [.verbatim("团团"), .verbatim("朵朵")])
        let answered = ParentingNaturalInput.applying(.child(duo.id), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertTrue(answered.isReadyToConfirm)
        XCTAssertEqual(answered.childID, duo.id)
        XCTAssertEqual(answered.dueAt, at(2026, 10, 30, 17, 0), "already clear fields are not asked or changed again")
        XCTAssertNil(ParentingNaturalInput.followUp(for: answered, context: ctx, calendar: calendar))
    }

    func testNamedChildIsNeverAsked() {
        let p = classify("朵朵明天下午3点钢琴课", context(children: [tuan, duo]))
        XCTAssertEqual(p.childID, duo.id)
        XCTAssertTrue(p.missing.isEmpty)
    }

    func testMissingDateThenTimeAreAskedOneAtATime() {
        let ctx = context(children: [tuan])
        var p = classify("游泳课", ctx)
        XCTAssertEqual(p.kind, .newArrangement)
        XCTAssertEqual(ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)?.field, .date)
        p = ParentingNaturalInput.applying(.text("周五"), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(p.missing, [.time])
        XCTAssertEqual(ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)?.field, .time)
        p = ParentingNaturalInput.applying(.text("下午5点"), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertTrue(p.isReadyToConfirm)
        XCTAssertEqual(p.title, "游泳课")
        XCTAssertEqual(p.dueAt, at(2026, 10, 30, 17, 0))
    }

    func testAnUnclearHourOffersMorningAndAfternoonInsteadOfGuessing() {
        let ctx = context(children: [tuan])
        let p = classify("周五5点游泳", ctx)
        XCTAssertNil(p.dueAt, "no silent default hour")
        XCTAssertEqual(p.missing, [.time])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.map(\.label), [.dateTime(at(2026, 10, 30, 5, 0)), .dateTime(at(2026, 10, 30, 17, 0))])
        let done = ParentingNaturalInput.applying(ask!.options[1].answer, to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(done.dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertTrue(done.isReadyToConfirm)
    }

    func testRescheduleWithAnUnclearHourOffersTheOriginalHalfOfTheDayFirst() {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        let ctx = context(children: [tuan], arrangements: [existing])
        let p = classify("团团周五游泳改到六点", ctx)
        XCTAssertEqual(p.kind, .reschedule)
        XCTAssertEqual(p.missing, [.time])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.first?.label, .dateTime(at(2026, 10, 30, 18, 0)))
    }

    func testRescheduleToTheSameTimeAsksAgainAndTwoCandidatesAskWhichOne() {
        let a = swim(due: at(2026, 10, 30, 17, 0))
        var b = swim(due: at(2026, 10, 31, 9, 0)); b.title = "游泳课"
        let ctx = context(children: [tuan], arrangements: [a, b])
        let same = classify("游泳改到下午5点", context(children: [tuan], arrangements: [a]))
        XCTAssertEqual(same.missing, [.time], "an unchanged time is not a reschedule")
        let ambiguous = classify("游泳改到下午6点", ctx)
        XCTAssertEqual(ambiguous.missing, [.target])
        XCTAssertEqual(ParentingNaturalInput.followUp(for: ambiguous, context: ctx, calendar: calendar)?.options.count, 2)
    }

    func testUnknownIntentAsksOnceAndKeepsTheAnswer() {
        let ctx = context(children: [tuan])
        let p = classify("嗯", ctx)
        XCTAssertEqual(p.missing, [.intent])
        let ask = ParentingNaturalInput.followUp(for: p, context: ctx, calendar: calendar)
        XCTAssertEqual(ask?.options.count, 2)
        let obs = ParentingNaturalInput.applying(.intent(.observation), to: p, context: ctx, now: now, calendar: calendar)
        XCTAssertEqual(obs.kind, .observation)
        XCTAssertEqual(obs.observationText, "嗯")
    }

    func testEmptyAndOversizedInputNeverBecomeAProposal() {
        XCTAssertFalse(classify("   ", context(children: [tuan])).isReadyToConfirm)
        XCTAssertFalse(classify(String(repeating: "游", count: 3001), context(children: [tuan])).isReadyToConfirm)
    }

    // MARK: Confirm card and write path
    func testCardAppearsOnlyWhenReadyAndWritesNothingUntilCommandsAreApplied() throws {
        let ctx = context(children: [tuan])
        let ws = workspace(children: [tuan])
        let missing = classify("游泳课", ctx)
        XCTAssertNil(ParentingConfirmCard.make(missing, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(ParentingNaturalInput.commands(for: missing, workspace: ws, ids: .init()).isEmpty)

        let ready = classify("团团周五下午5点游泳", ctx)
        let card = try XCTUnwrap(ParentingConfirmCard.make(ready, workspace: ws, remindersEnabled: true, now: now))
        XCTAssertTrue(card.writes)
        XCTAssertEqual(card.confirmTitle, "确认安排")
        XCTAssertTrue(card.blocks.contains(.quote(label: "你说的原话", text: "团团周五下午5点游泳")))
        XCTAssertTrue(card.blocks.contains(.note("提醒按你的设置发送：每天最多 2 条，21:00–08:00 不打扰。", .normal)))
        XCTAssertEqual(ws.arrangements.count, 0, "building a card changes nothing")

        let ids = ParentingNaturalInput.WriteIDs()
        var next = ws
        for command in ParentingNaturalInput.commands(for: ready, workspace: ws, ids: ids) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.arrangements.count, 1)
        XCTAssertEqual(next.arrangements[0].state, .planned)
        XCTAssertEqual(next.arrangements[0].dueAt, at(2026, 10, 30, 17, 0))
        XCTAssertEqual(next.arrangements[0].sourceText, "团团周五下午5点游泳")
    }

    func testObservationCommandStoresOnlyAnObservation() throws {
        let ws = workspace(children: [tuan])
        let p = classify("记一下，今天他自己收好书包了", context(children: [tuan]))
        var next = ws
        for command in ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.records.count, 1)
        XCTAssertEqual(next.records[0].kind, .observation)
        XCTAssertEqual(next.records[0].text, "今天他自己收好书包了")
        XCTAssertTrue(next.arrangements.isEmpty)
    }

    func testRescheduleCardShowsBeforeAfterAndSameTimeConflictsAreAcknowledgedOnConfirm() throws {
        let existing = swim(due: at(2026, 10, 30, 17, 0))
        var other = swim(due: at(2026, 10, 30, 18, 0)); other.title = "钢琴课"
        let ws = workspace(children: [tuan], arrangements: [existing, other])
        let p = classify("团团周五游泳改到下午6点", ParentingNaturalContext(workspace: ws))
        let card = try XCTUnwrap(ParentingConfirmCard.make(p, workspace: ws, remindersEnabled: nil, now: now))
        XCTAssertTrue(card.blocks.contains(.change(label: "时间", before: existing.dueAt, after: at(2026, 10, 30, 18, 0))))
        XCTAssertTrue(card.blocks.contains { if case .conflicts(_, let items) = $0 { return items.map(\.title) == ["钢琴课"] } else { return false } })
        var next = ws
        for command in ParentingNaturalInput.commands(for: p, workspace: ws, ids: .init()) { next = try next.applying(command, now: now) }
        XCTAssertEqual(next.arrangements.first { $0.id == existing.id }?.dueAt, at(2026, 10, 30, 18, 0))
        XCTAssertEqual(next.editHistory.count, 1, "the change is recorded and can be undone")
        XCTAssertEqual(next.arrangements.first { $0.id == other.id }?.dueAt, other.dueAt, "other arrangements are untouched")
    }

    // MARK: Today timeline and reminder limits
    func testTodayTimelineOrdersByTimeSeparatesPendingAndMarksNow() {
        let early = swim(due: at(2026, 10, 25, 8, 30)), later = swim(due: at(2026, 10, 25, 17, 0))
        var twin = swim(due: at(2026, 10, 25, 17, 0)); twin.title = "钢琴课"
        let draft = swim(due: at(2026, 10, 27, 9, 0), state: .draft)
        let overdue = swim(due: at(2026, 10, 23, 9, 0))
        let tomorrow = swim(due: at(2026, 10, 26, 9, 0))
        let cancelled = swim(due: at(2026, 10, 25, 12, 0), state: .cancelled)
        let ws = workspace(children: [tuan], arrangements: [later, twin, early, draft, overdue, tomorrow, cancelled])
        let timeline = ParentingTodayTimeline.build(workspace: ws, now: now, calendar: calendar)
        XCTAssertEqual(timeline.pending.map(\.id), [draft.id])
        XCTAssertEqual(timeline.overdue.map(\.id), [overdue.id])
        XCTAssertEqual(timeline.upcoming.map(\.id), [tomorrow.id])
        XCTAssertEqual(timeline.entries.count, 3, "cancelled items never show")
        XCTAssertEqual(timeline.entries.first?.id, early.id)
        XCTAssertEqual(timeline.nowIndex, 1, "the now marker sits after the 08:30 item")
        XCTAssertEqual(timeline.entries.filter(\.sameTimeConflict).count, 2)
        XCTAssertEqual(timeline.summary.pending, 1)
        XCTAssertEqual(timeline.summary.today, 3)
        XCTAssertNotNil(timeline.summary.next)
    }

    func testGrowthTimelineGroupsObservationsAndCompletionsByDay() {
        let d1 = at(2026, 10, 24, 20, 0), d2 = at(2026, 10, 25, 9, 0)
        func record(_ kind: ParentingRecordKind, _ when: Date, _ text: String) -> ParentingRecord {
            .init(id: UUID(), childID: tuan.id, goalID: nil, arrangementID: nil, kind: kind, text: text, sourceText: text, createdAt: when, updatedAt: when)
        }
        let ws = ParentingWorkspace(revision: 1, children: [tuan], records: [record(.observation, d1, "a"), record(.observation, d2, "b"), record(.observation, d2.addingTimeInterval(60), "c")])
        let all = ParentingGrowthTimeline.build(workspace: ws, calendar: calendar)
        XCTAssertEqual(all.days.count, 2)
        XCTAssertEqual(all.days.first?.records.map(\.text), ["c", "b"], "newest day and newest record first")
        XCTAssertTrue(ParentingGrowthTimeline.build(workspace: ws, filter: .completion, calendar: calendar).isEmpty)
    }

    func testReminderLimitAndQuietHoursStayAtTwoAndTwentyOneToEight() {
        XCTAssertEqual(ParentingReminderPlanner.maxPerDay, 2)
        XCTAssertEqual(ParentingReminderPlanner.quietStartHour, 21)
        XCTAssertEqual(ParentingReminderPlanner.quietEndHour, 8)
        let items = [at(2026, 10, 26, 10, 0), at(2026, 10, 26, 14, 0), at(2026, 10, 26, 16, 0)].map { swim(due: $0) }
        let ws = workspace(children: [tuan], arrangements: items)
        let plan = ParentingReminderPlanner.plan(workspace: ws, settings: .init(enabled: true, lead: .minutes30), now: now, calendar: calendar)
        XCTAssertEqual(plan.reminders.count, 2)
        XCTAssertEqual(plan.skipped.values.filter { $0 == .dailyLimit }.count, 1)
        XCTAssertTrue(plan.reminders.allSatisfy { !ParentingReminderPlanner.isQuiet($0.fireAt, calendar: calendar) })
    }
}
