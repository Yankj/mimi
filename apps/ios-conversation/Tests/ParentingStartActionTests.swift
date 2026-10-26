import Foundation
import XCTest
@testable import cat

/// Pure logic only: ledger value + injected clock + fixed Asia/Shanghai calendar. No UI, storage or network.
final class ParentingStartActionTests: XCTestCase {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return c
    }()
    private let child = UUID(uuidString: "D2000000-0000-4000-8000-000000000001")!
    private let goal = UUID(uuidString: "D4000000-0000-4000-8000-000000000001")!

    private func at(_ day: Int, _ hour: Int, _ minute: Int = 0, month: Int = 10) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }
    private var monday10: Date { at(26, 10) } // 2026-10-26 is a Monday
    private func id(_ n: Int) -> UUID { UUID(uuidString: String(format: "D3000000-0000-4000-8000-%012d", n))! }

    private func ledger(children: Bool = true, goals: Bool = false, arrangements: [ParentingArrangement] = [], records: [ParentingRecord] = []) -> ParentingWorkspace {
        ParentingWorkspace(children: children ? [.init(id: child, name: "小星", createdAt: at(1))] : [],
                           goals: goals ? [.init(id: goal, childID: child, title: "阅读", createdAt: at(1))] : [],
                           arrangements: arrangements, records: records)
    }
    private func item(_ n: Int, _ state: ParentingArrangementState, due: Date) -> ParentingArrangement {
        .init(id: id(n), childID: child, title: "事项\(n)", dueAt: due, goalID: nil, state: state, sourceText: "", createdAt: at(1), updatedAt: at(1))
    }
    private func record(_ n: Int, _ kind: ParentingRecordKind = .observation, at date: Date) -> ParentingRecord {
        .init(id: id(100 + n), childID: child, goalID: nil, arrangementID: nil, kind: kind, text: "观察\(n)", sourceText: "观察\(n)", createdAt: date, updatedAt: date)
    }
    private func today(_ ws: ParentingWorkspace, _ now: Date, _ ctx: ParentingStartContext = .init()) -> ParentingStartAction {
        ParentingStartAction.resolve(.today, workspace: ws, now: now, calendar: calendar, context: ctx)
    }
    private func growth(_ ws: ParentingWorkspace, _ now: Date, _ ctx: ParentingStartContext = .init()) -> ParentingStartAction {
        ParentingStartAction.resolve(.growth, workspace: ws, now: now, calendar: calendar, context: ctx)
    }

    // MARK: 今天 priority
    func testTodayNoChildStartsWithNickname() {
        XCTAssertEqual(today(ledger(children: false), monday10), .startWithNickname)
    }

    func testTodayPendingBeatsEverythingElseIncludingOverdue() {
        let ws = ledger(arrangements: [item(1, .draft, due: at(27, 9)), item(2, .planned, due: at(24, 9)), item(3, .planned, due: at(26, 15))])
        XCTAssertEqual(today(ws, monday10), .confirmAll(arrangementIDs: [id(1)]))
    }

    func testTodayOverdueBeatsNextAndNeverChangesTheLedger() {
        let ws = ledger(arrangements: [item(2, .planned, due: at(24, 9)), item(3, .planned, due: at(26, 15))])
        XCTAssertEqual(today(ws, monday10), .handleOverdue(arrangementIDs: [id(2)]))
        XCTAssertEqual(ws.arrangements.map(\.state), [.planned, .planned], "resolving must not complete anything")
    }

    func testTodayNextScheduledOffersOneTapCompleteAndPicksTheEarliestAhead() {
        let ws = ledger(arrangements: [item(3, .planned, due: at(26, 18)), item(4, .planned, due: at(26, 13)), item(5, .planned, due: at(27, 8))])
        XCTAssertEqual(today(ws, monday10), .nextWithComplete(arrangementID: id(4), dueAt: at(26, 13), hasPassed: false))
    }

    func testTodayPassedButUnfinishedStillOffersCompleteAndIsFlaggedPassed() {
        let ws = ledger(arrangements: [item(3, .planned, due: at(26, 8)), item(4, .planned, due: at(26, 9))])
        XCTAssertEqual(today(ws, monday10), .nextWithComplete(arrangementID: id(4), dueAt: at(26, 9), hasPassed: true))
    }

    func testTodayEveningWindowIsHalfOpen1800To2100() {
        let ws = ledger(arrangements: [item(1, .completed, due: at(26, 9))])
        XCTAssertEqual(today(ws, at(26, 17, 59)), .examplePrompt(text: ParentingStartAction.examplePrompt(now: at(26, 17, 59), calendar: calendar)))
        XCTAssertEqual(today(ws, at(26, 18)), .recordTodaySmallThing(childID: child))
        XCTAssertEqual(today(ws, at(26, 20, 59)), .recordTodaySmallThing(childID: child))
        guard case .examplePrompt = today(ws, at(26, 21)) else { return XCTFail("21:00 is outside the evening window") }
    }

    func testTodayEveningPromptSkippedWhenAnObservationWasAlreadyWrittenToday() {
        let ws = ledger(records: [record(1, at: at(26, 12))])
        guard case .examplePrompt = today(ws, at(26, 19)) else { return XCTFail("already recorded today") }
    }

    func testTodayOtherwiseExamplePromptIsStableWithinADayAndRotatesAcrossDays() {
        let ws = ledger()
        let a = today(ws, at(26, 9)), b = today(ws, at(26, 14))
        XCTAssertEqual(a, b)
        let texts = Set((0..<5).compactMap { d -> String? in
            if case .examplePrompt(let t) = today(ws, at(20 + d, 9)) { return t } else { return nil }
        })
        XCTAssertEqual(texts.count, ParentingStartAction.examplePrompts.count)
    }

    func testTodayCancelledAndFutureOnlyDoNotTriggerNext() {
        let ws = ledger(arrangements: [item(1, .cancelled, due: at(26, 15)), item(2, .planned, due: at(28, 9))])
        guard case .examplePrompt = today(ws, monday10) else { return XCTFail("only today's items count") }
    }

    func testTodayScopesToTheChosenChild() {
        let other = UUID()
        var ws = ledger(arrangements: [item(1, .draft, due: at(26, 15))])
        ws.children.append(.init(id: other, name: "小月", createdAt: at(2)))
        XCTAssertEqual(today(ws, monday10, .init(childID: other)), .examplePrompt(text: ParentingStartAction.examplePrompt(now: monday10, calendar: calendar)))
    }

    // MARK: 成长
    func testGrowthNoRecordsShowsExample() {
        guard case .examplePrompt = growth(ledger(), monday10) else { return XCTFail() }
        guard case .examplePrompt = growth(ledger(children: false), monday10) else { return XCTFail() }
    }

    func testGrowthWeeklyReviewOnSundayEveningFromEighteen() {
        let ws = ledger(records: [record(1, at: at(22, 9))])
        XCTAssertEqual(growth(ws, at(25, 18)), .weeklyReview(reason: .sundayEvening))
        XCTAssertEqual(growth(ws, at(25, 23, 30)), .weeklyReview(reason: .sundayEvening))
        XCTAssertEqual(growth(ws, at(25, 17, 59)), .recordObservation(childID: child))
    }

    func testGrowthSundayReviewNotRepeatedOnceReviewedToday() {
        let ws = ledger(records: [record(1, at: at(22, 9))])
        XCTAssertEqual(growth(ws, at(25, 20), .init(lastWeeklyReviewAt: at(25, 18, 30))), .recordObservation(childID: child))
    }

    func testGrowthSevenDaysSinceLastReviewOrSinceFirstRecord() {
        let ws = ledger(records: [record(1, at: at(20, 9)), record(2, at: at(24, 9))])
        XCTAssertEqual(growth(ws, at(27, 10), .init(lastWeeklyReviewAt: at(20, 9))), .weeklyReview(reason: .sevenDaysSinceLastReview))
        XCTAssertEqual(growth(ws, at(26, 8), .init(lastWeeklyReviewAt: at(20, 9))), .recordObservation(childID: child), "just under 7 days")
        XCTAssertEqual(growth(ws, at(28, 10)), .weeklyReview(reason: .sevenDaysSinceLastReview), "never reviewed: clock starts at the first record")
    }

    func testGrowthReviewNeedsSomethingFromTheLastWeek() {
        let ws = ledger(records: [record(1, at: at(1))])
        XCTAssertEqual(growth(ws, at(25, 19)), .recordObservation(childID: child))
    }

    func testGrowthFiveObservationsWithoutGoalOffersGoalButWeeklyReviewWins() {
        let five = (1...5).map { record($0, at: at(26, 6 + $0)) }
        XCTAssertEqual(growth(ledger(records: five), at(26, 12), .init(lastWeeklyReviewAt: at(25, 9))), .giveObservationsAGoal(childID: child, observationCount: 5))
        XCTAssertEqual(growth(ledger(goals: true, records: five), at(26, 12), .init(lastWeeklyReviewAt: at(25, 9))), .recordObservation(childID: child))
        let four = Array(five.prefix(4))
        XCTAssertEqual(growth(ledger(records: four), at(26, 12), .init(lastWeeklyReviewAt: at(25, 9))), .recordObservation(childID: child))
        XCTAssertEqual(growth(ledger(records: five), at(25, 19)), .weeklyReview(reason: .sundayEvening))
    }

    func testGrowthCompletionRecordsDoNotCountAsObservations() {
        let completions = (1...5).map { record($0, .completion, at: at(26, 6 + $0)) }
        XCTAssertEqual(growth(ledger(records: completions), at(26, 12), .init(lastWeeklyReviewAt: at(25, 9))), .recordObservation(childID: child))
    }

    // MARK: 我们家
    func testFamilyChecklistProgressAndDisappearance() {
        func family(_ ws: ParentingWorkspace, _ ctx: ParentingStartContext = .init()) -> ParentingStartAction {
            ParentingStartAction.resolve(.family, workspace: ws, now: monday10, calendar: calendar, context: ctx)
        }
        guard case .launchChecklist(let c0) = family(ledger(children: false)) else { return XCTFail() }
        XCTAssertEqual(c0.completedCount, 0); XCTAssertEqual(c0.nextStep, .addChild)
        guard case .launchChecklist(let c1) = family(ledger()) else { return XCTFail() }
        XCTAssertEqual(c1.completedCount, 1); XCTAssertEqual(c1.nextStep, .recordFirstThing)
        let two = ledger(records: [record(1, at: at(26, 9))])
        guard case .launchChecklist(let c2) = family(two) else { return XCTFail("optional reminders step is still offered") }
        XCTAssertEqual(c2.completedCount, 2); XCTAssertEqual(c2.nextStep, .enableReminders)
        XCTAssertEqual(family(two, .init(remindersEnabled: true)), .none)
        XCTAssertEqual(family(two, .init(remindersStepSkipped: true)), .none)
        // Skipping never counts as done, and never hides the required steps.
        guard case .launchChecklist(let c3) = family(ledger(), .init(remindersStepSkipped: true)) else { return XCTFail() }
        XCTAssertEqual(c3.completedCount, 1); XCTAssertEqual(c3.nextStep, .recordFirstThing)
    }

    func testFamilyAnArrangementCountsAsFirstThing() {
        let ws = ledger(arrangements: [item(1, .draft, due: at(27, 9))])
        XCTAssertTrue(ParentingStartAction.launchChecklist(workspace: ws).done.contains(.recordFirstThing))
    }

    // MARK: Time zone
    func testTimeZoneDecidesTheEveningWindow() {
        let ws = ledger(arrangements: [item(1, .completed, due: at(26, 9))])
        let instant = at(26, 19) // 19:00 Shanghai = 11:00 UTC
        var utc = calendar; utc.timeZone = TimeZone(identifier: "UTC")!
        XCTAssertEqual(ParentingStartAction.resolve(.today, workspace: ws, now: instant, calendar: calendar), .recordTodaySmallThing(childID: child))
        guard case .examplePrompt = ParentingStartAction.resolve(.today, workspace: ws, now: instant, calendar: utc) else { return XCTFail("same instant is 11:00 in UTC") }
    }

    // MARK: Today home helpers
    func testSubtitleFormat() {
        XCTAssertEqual(ParentingTodayHome.subtitle(planned: 2, done: 1), "3 件·已完成 1")
        XCTAssertEqual(ParentingTodayHome.subtitle(planned: 0, done: 0), "今天暂无安排")
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 15)), item(2, .planned, due: at(26, 16)), item(3, .completed, due: at(26, 8)), item(4, .cancelled, due: at(26, 9)), item(5, .draft, due: at(26, 9))])
        XCTAssertEqual(ParentingTodayHome.build(workspace: ws, now: monday10, calendar: calendar).subtitle, "3 件·已完成 1")
    }

    func testNowInsertionIndexIsBeforeTheFirstLaterEntry() {
        let times = [at(26, 8), at(26, 12), at(26, 18)]
        XCTAssertEqual(ParentingTodayHome.insertionIndex(of: at(26, 7), in: times), 0)
        XCTAssertEqual(ParentingTodayHome.insertionIndex(of: at(26, 12), in: times), 2, "an entry exactly at now is already past")
        XCTAssertEqual(ParentingTodayHome.insertionIndex(of: at(26, 23), in: times), 3)
        XCTAssertEqual(ParentingTodayHome.insertionIndex(of: monday10, in: []), 0)
    }

    func testHomeCapsAtFiveRowsKeepsUpcomingFirstAndRestliesInAllArrangements() {
        let planned = (0..<8).map { item($0 + 1, .planned, due: at(26, 8 + $0 * 2 % 14, $0)) } // mixed past and future today
        let ws = ledger(arrangements: planned + [item(20, .planned, due: at(27, 9))])
        let home = ParentingTodayHome.build(workspace: ws, now: monday10, calendar: calendar)
        XCTAssertEqual(home.rows.count, 5)
        XCTAssertEqual(home.hiddenTodayCount, 3)
        XCTAssertEqual(home.upcomingCount, 1)
        XCTAssertTrue(home.hasMoreInAllArrangements)
        XCTAssertEqual(home.rows.map(\.time), home.rows.map(\.time).sorted(), "rows stay in time order")
        XCTAssertEqual(home.nowIndex, home.rows.filter { $0.time <= monday10 }.count)
        for aheadItem in planned.filter({ $0.dueAt > monday10 }).sorted(by: { $0.dueAt < $1.dueAt }).prefix(5) {
            XCTAssertTrue(home.rows.contains { $0.id == aheadItem.id }, "items still ahead are kept before past ones")
        }
    }

    func testHomeUnderCapShowsEverythingAndNoMoreLink() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 15)), item(2, .completed, due: at(26, 8))])
        let home = ParentingTodayHome.build(workspace: ws, now: monday10, calendar: calendar)
        XCTAssertEqual(home.rows.map(\.id), [id(2), id(1)])
        XCTAssertEqual(home.nowIndex, 1)
        XCTAssertFalse(home.hasMoreInAllArrangements)
        XCTAssertEqual(ParentingTodayHome.build(workspace: ws, now: monday10, calendar: calendar, rowCap: 0).rows.count, 0)
    }

    func testOverdueMarksAreLabelsOnlyAndNeverChangeState() {
        let passed = item(1, .planned, due: at(26, 8)), earlier = item(2, .planned, due: at(25, 8)), ahead = item(3, .planned, due: at(26, 15))
        let done = item(4, .completed, due: at(25, 8)), cancelled = item(5, .cancelled, due: at(25, 8)), draft = item(6, .draft, due: at(25, 8))
        func mark(_ a: ParentingArrangement) -> ParentingOverdueMark { .mark(for: a, now: monday10, calendar: calendar) }
        XCTAssertEqual(mark(passed), .passedToday)
        XCTAssertEqual(mark(earlier), .previousDay)
        XCTAssertEqual([ahead, done, cancelled, draft].map(mark), [.none, .none, .none, .none])
        let ws = ledger(arrangements: [passed, earlier])
        let home = ParentingTodayHome.build(workspace: ws, now: monday10, calendar: calendar)
        XCTAssertEqual(home.rows.map(\.mark), [.passedToday])
        XCTAssertEqual(home.overdueCount, 1)
        XCTAssertEqual(ws.arrangements.map(\.state), [.planned, .planned])
    }

    func testCompletionUndoWindowIsThreeSecondsAndNeedsTheMatchingRecord() throws {
        let planned = ledger(arrangements: [item(1, .planned, due: at(26, 9))])
        let done = try planned.applying(.completeArrangement(id: id(1), recordID: id(200), note: ""), now: monday10)
        let undo = try XCTUnwrap(ParentingCompletionUndo.make(workspace: done, arrangementID: id(1), recordID: id(200), now: monday10))
        XCTAssertTrue(undo.isActive(at: monday10.addingTimeInterval(2.9)))
        XCTAssertFalse(undo.isActive(at: monday10.addingTimeInterval(3)))
        XCTAssertNil(ParentingCompletionUndo.make(workspace: done, arrangementID: id(1), recordID: id(201), now: monday10), "a retried tap that created no record has nothing to undo")
        XCTAssertNil(ParentingCompletionUndo.make(workspace: planned, arrangementID: id(1), recordID: id(200), now: monday10))
    }

    // MARK: reopenArrangement (pure domain)
    func testReopenRestoresPlannedAndRemovesTheAutoCompletionRecord() throws {
        var ws = try ledger(arrangements: [item(1, .planned, due: at(26, 9))]).applying(.completeArrangement(id: id(1), recordID: id(200), note: ""), now: monday10)
        XCTAssertEqual(ws.records.count, 1)
        let revision = ws.revision
        ws = try ws.applying(.reopenArrangement(id: id(1)), now: monday10.addingTimeInterval(2))
        XCTAssertEqual(ws.arrangements[0].state, .planned)
        XCTAssertEqual(ws.arrangements[0].updatedAt, monday10.addingTimeInterval(2))
        XCTAssertTrue(ws.records.isEmpty)
        XCTAssertEqual(ws.revision, revision + 1)
        try ws.validate()
    }

    func testCompleteReopenCompleteCycleIsRepeatableAndIdempotent() throws {
        var ws = ledger(arrangements: [item(1, .planned, due: at(26, 9))])
        for round in 0..<3 {
            ws = try ws.applying(.completeArrangement(id: id(1), recordID: id(300 + round), note: ""), now: monday10)
            XCTAssertEqual(ws.records.count, 1)
            ws = try ws.applying(.reopenArrangement(id: id(1)), now: monday10)
            let again = try ws.applying(.reopenArrangement(id: id(1)), now: monday10)
            XCTAssertEqual(again.arrangements, ws.arrangements, "reopening an open arrangement changes nothing")
            XCTAssertEqual(again.records, ws.records)
            XCTAssertTrue(ws.records.isEmpty)
        }
        XCTAssertEqual(ws.arrangements[0].state, .planned)
    }

    func testReopenKeepsWordsTheParentWroteAsAnObservation() throws {
        var ws = try ledger(arrangements: [item(1, .planned, due: at(26, 9))]).applying(.completeArrangement(id: id(1), recordID: id(200), note: "今天自己选了故事"), now: monday10)
        ws = try ws.applying(.reopenArrangement(id: id(1)), now: monday10)
        XCTAssertEqual(ws.arrangements[0].state, .planned)
        XCTAssertEqual(ws.records.count, 1)
        XCTAssertEqual(ws.records[0].kind, .observation)
        XCTAssertNil(ws.records[0].arrangementID)
        XCTAssertEqual(ws.records[0].text, "今天自己选了故事")
        try ws.validate()
        // A record the parent edited afterwards is kept too.
        var edited = try ledger(arrangements: [item(1, .planned, due: at(26, 9))]).applying(.completeArrangement(id: id(1), recordID: id(200), note: ""), now: monday10)
        edited = try edited.applying(.editRecord(id: id(200), text: "改过的话"), now: monday10.addingTimeInterval(1))
        edited = try edited.applying(.reopenArrangement(id: id(1)), now: monday10.addingTimeInterval(2))
        XCTAssertEqual(edited.records.map(\.text), ["改过的话"])
        XCTAssertEqual(edited.records.first?.kind, .observation)
    }

    func testReopenEdgeCases() throws {
        let planned = ledger(arrangements: [item(1, .planned, due: at(26, 9)), item(2, .cancelled, due: at(26, 9)), item(3, .draft, due: at(26, 9))])
        XCTAssertEqual(try planned.applying(.reopenArrangement(id: id(1)), now: monday10).arrangements, planned.arrangements, "already open is a no-op")
        XCTAssertThrowsError(try planned.applying(.reopenArrangement(id: id(2)), now: monday10), "cancelled stays cancelled")
        XCTAssertThrowsError(try planned.applying(.reopenArrangement(id: id(3)), now: monday10), "an unconfirmed draft was never completed")
        XCTAssertThrowsError(try planned.applying(.reopenArrangement(id: id(99)), now: monday10), "missing arrangement")
    }

    func testReopenAfterTheCompletionRecordWasDeleted() throws {
        var ws = try ledger(arrangements: [item(1, .planned, due: at(26, 9))]).applying(.completeArrangement(id: id(1), recordID: id(200), note: ""), now: monday10)
        ws = try ws.applying(.deleteRecord(id: id(200)), now: monday10)
        XCTAssertEqual(ws.arrangements[0].state, .completed, "deleteRecord alone does not reopen")
        ws = try ws.applying(.reopenArrangement(id: id(1)), now: monday10)
        XCTAssertEqual(ws.arrangements[0].state, .planned)
        XCTAssertTrue(ws.records.isEmpty)
        try ws.validate()
    }

    func testReopenNeverTouchesOtherRecordsAndEditHistoryStaysSuperseded() throws {
        let other = record(1, at: at(26, 7))
        let base = ledger(arrangements: [item(1, .planned, due: at(26, 9))], records: [other])
        let target = ParentingArrangementEditTarget(arrangement: base.arrangements[0], workspaceRevision: base.revision)
        var ws = try base.applying(.reviseArrangement(id: id(50), target: target, title: "新标题", dueAt: at(26, 10), goalID: nil, sourceText: "改", acknowledgedConflictIDs: []), now: monday10)
        ws = try ws.applying(.completeArrangement(id: id(1), recordID: id(200), note: ""), now: monday10)
        let supersededAt = ws.editHistory.first?.supersededAt
        XCTAssertNotNil(supersededAt)
        ws = try ws.applying(.reopenArrangement(id: id(1)), now: monday10)
        XCTAssertEqual(ws.records, [other])
        XCTAssertEqual(ws.editHistory.first?.supersededAt, supersededAt)
        XCTAssertFalse(ws.editHistory[0].canUndo(in: ws), "reopening must not revive an old edit-undo")
    }

    func testCommandRoundTripsThroughCodable() throws {
        let command = ParentingCommand.reopenArrangement(id: id(1))
        let data = try JSONEncoder().encode(command)
        XCTAssertEqual(try JSONDecoder().decode(ParentingCommand.self, from: data), command)
    }
}
