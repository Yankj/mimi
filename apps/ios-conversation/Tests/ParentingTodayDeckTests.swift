import Foundation
import SwiftUI
import XCTest
@testable import cat

/// Pure logic for the Today UI slice: hero + rail selection, undo countdown helpers and the weekly-review preference.
/// Ledger value + injected clock + fixed Asia/Shanghai calendar. No UI, storage of the ledger or network.
final class ParentingTodayDeckTests: XCTestCase {
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return c
    }()
    private let child = UUID(uuidString: "D2000000-0000-4000-8000-000000000001")!
    private let sibling = UUID(uuidString: "D2000000-0000-4000-8000-000000000002")!

    private func at(_ day: Int, _ hour: Int = 0, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: day, hour: hour, minute: minute))!
    }
    private var monday10: Date { at(26, 10) }
    private func id(_ n: Int) -> UUID { UUID(uuidString: String(format: "D3000000-0000-4000-8000-%012d", n))! }
    private func item(_ n: Int, _ state: ParentingArrangementState, due: Date, child owner: UUID? = nil) -> ParentingArrangement {
        .init(id: id(n), childID: owner ?? child, title: "事项\(n)", dueAt: due, goalID: nil, state: state, sourceText: "", createdAt: at(1), updatedAt: at(1))
    }
    private func ledger(children: Bool = true, arrangements: [ParentingArrangement] = []) -> ParentingWorkspace {
        var kids: [ParentingChild] = []
        if children { kids = [.init(id: child, name: "小星", createdAt: at(1)), .init(id: sibling, name: "小月", createdAt: at(2))] }
        return ParentingWorkspace(children: kids, arrangements: arrangements)
    }
    private func deck(_ ws: ParentingWorkspace, now: Date? = nil, context: ParentingStartContext = .init(), limit: Int = ParentingTodayDeck.railLimit) -> ParentingTodayDeck {
        ParentingTodayDeck.build(workspace: ws, now: now ?? monday10, calendar: calendar, context: context, railLimit: limit)
    }

    // MARK: hero
    func testHeroIsExactlyTheStartActionResolverResult() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 13)), item(2, .draft, due: at(27, 9))])
        let expected = ParentingStartAction.resolve(.today, workspace: ws, now: monday10, calendar: calendar, context: .init())
        XCTAssertEqual(deck(ws).hero, expected)
        XCTAssertEqual(deck(ws).hero, .confirmAll(arrangementIDs: [id(2)]), "drafts come first")
    }

    func testNoChildMeansNickNameHeroAndEmptyRail() {
        let d = deck(ledger(children: false))
        XCTAssertEqual(d.hero, .startWithNickname)
        XCTAssertNil(d.childID)
        XCTAssertNil(d.heroArrangement)
        XCTAssertTrue(d.rail.isEmpty)
        XCTAssertEqual(d.hiddenOpenCount, 0)
    }

    func testHeroArrangementIsOnlySetForNextWithComplete() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 13))])
        XCTAssertEqual(deck(ws).heroArrangement?.id, id(1))
        let overdue = ledger(arrangements: [item(1, .planned, due: at(24, 9))])
        XCTAssertEqual(deck(overdue).hero, .handleOverdue(arrangementIDs: [id(1)]))
        XCTAssertNil(deck(overdue).heroArrangement)
    }

    // MARK: rail
    func testRailListsTheNextThingsAfterTheHeroInTimeOrderAndTodayBeforeLaterDays() {
        let ws = ledger(arrangements: [
            item(1, .planned, due: at(26, 13)),   // hero
            item(2, .planned, due: at(27, 8)),
            item(3, .planned, due: at(26, 18)),
            item(4, .planned, due: at(26, 15))])
        let d = deck(ws)
        XCTAssertEqual(d.heroArrangement?.id, id(1))
        XCTAssertEqual(d.rail.map(\.id), [id(4), id(3), id(2)])
        XCTAssertEqual(d.rail.map(\.when), [.laterToday, .laterToday, .anotherDay])
        XCTAssertEqual(d.rail.map(\.title), ["事项4", "事项3", "事项2"])
    }

    func testRailIsCappedAtThreeAndCountsTheRestAsHidden() {
        let many = (1...7).map { item($0, .planned, due: at(26, 12 + $0)) }
        let d = deck(ledger(arrangements: many), now: at(26, 9))
        XCTAssertEqual(d.heroArrangement?.id, id(1))
        XCTAssertEqual(d.rail.map(\.id), [id(2), id(3), id(4)])
        XCTAssertEqual(d.hiddenOpenCount, 3, "items 5, 6 and 7 are only in 全部安排")
        XCTAssertEqual(deck(ledger(arrangements: many), now: at(26, 9), limit: 2).rail.count, 2)
        XCTAssertEqual(deck(ledger(arrangements: many), now: at(26, 9), limit: 0).rail.count, 0)
    }

    func testRailNeverRepeatsTheHeroAndSkipsFinishedCancelledPassedAndOtherChildren() {
        let ws = ledger(arrangements: [
            item(1, .planned, due: at(26, 13)),                  // hero
            item(2, .completed, due: at(26, 14)),                // finished
            item(3, .cancelled, due: at(26, 15)),                // cancelled
            item(4, .planned, due: at(26, 9)),                   // already passed today
            item(5, .planned, due: at(26, 16), child: sibling),  // another child
            item(6, .planned, due: at(26, 17))])
        let d = deck(ws)
        XCTAssertEqual(d.heroArrangement?.id, id(1))
        XCTAssertEqual(d.rail.map(\.id), [id(6)])
        XCTAssertFalse(d.rail.contains { $0.id == d.heroArrangement?.id })
    }

    func testRailStillShowsWhatIsNextWhenTheHeroIsConfirmAllOrOverdue() {
        let pending = ledger(arrangements: [item(1, .draft, due: at(27, 9)), item(2, .planned, due: at(26, 15)), item(3, .planned, due: at(26, 12))])
        XCTAssertEqual(deck(pending).rail.map(\.id), [id(3), id(2)])
        XCTAssertEqual(deck(pending).hiddenOpenCount, 1, "the draft is behind the confirm-all hero, not on screen as a card")
        let overdue = ledger(arrangements: [item(1, .planned, due: at(24, 9)), item(2, .planned, due: at(26, 15))])
        XCTAssertEqual(deck(overdue).rail.map(\.id), [id(2)])
    }

    func testPassedHeroLeavesTheRailEmptyBecauseNothingIsAhead() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 8)), item(2, .planned, due: at(26, 9))])
        let d = deck(ws)
        XCTAssertEqual(d.hero, .nextWithComplete(arrangementID: id(2), dueAt: at(26, 9), hasPassed: true))
        XCTAssertEqual(d.heroArrangement?.id, id(2))
        XCTAssertTrue(d.rail.isEmpty)
        XCTAssertEqual(d.hiddenOpenCount, 1, "the earlier passed item is only in 全部安排")
    }

    func testRailFollowsTheChosenChild() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 13)), item(2, .planned, due: at(26, 15), child: sibling), item(3, .planned, due: at(26, 16), child: sibling)])
        let d = deck(ws, context: .init(childID: sibling))
        XCTAssertEqual(d.childID, sibling)
        XCTAssertEqual(d.heroArrangement?.id, id(2))
        XCTAssertEqual(d.rail.map(\.id), [id(3)])
    }

    func testRailTreatsTheMinuteOfNowAsNotAheadAndMidnightBoundaryAsAnotherDay() {
        let ws = ledger(arrangements: [item(1, .planned, due: monday10), item(2, .planned, due: at(26, 23, 59)), item(3, .planned, due: at(27, 0, 0))])
        let d = deck(ws)
        XCTAssertEqual(d.heroArrangement?.id, id(2), "due exactly now counts as passed, so the next one ahead is the hero")
        XCTAssertEqual(d.rail.map(\.id), [id(3)])
        XCTAssertEqual(d.rail.first?.when, .anotherDay)
    }

    func testBuildingTheDeckNeverChangesTheLedger() {
        let ws = ledger(arrangements: [item(1, .planned, due: at(26, 13)), item(2, .draft, due: at(27, 9))])
        _ = deck(ws)
        XCTAssertEqual(ws, ledger(arrangements: [item(1, .planned, due: at(26, 13)), item(2, .draft, due: at(27, 9))]))
    }

    // MARK: undo countdown helpers
    func testUndoRemainingSecondsRoundUpAndNeverGoNegative() {
        let ws = ParentingWorkspace(children: [.init(id: child, name: "小星", createdAt: at(1))],
                                    arrangements: [item(1, .completed, due: at(26, 9))],
                                    records: [.init(id: id(100), childID: child, goalID: nil, arrangementID: id(1), kind: .completion, text: "事项1", sourceText: "", createdAt: monday10, updatedAt: monday10)])
        guard let undo = ParentingCompletionUndo.make(workspace: ws, arrangementID: id(1), recordID: id(100), now: monday10) else { return XCTFail("completed by this record") }
        XCTAssertEqual(undo.remainingSeconds(at: monday10), 3)
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(0.2)), 3)
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(1.0)), 2)
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(2.01)), 1)
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(3)), 0)
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(60)), 0)
        XCTAssertEqual(undo.timeUntilExpiry(from: monday10), 3, accuracy: 0.0001)
        XCTAssertEqual(undo.timeUntilExpiry(from: monday10.addingTimeInterval(10)), 0)
        // The window is absolute: coming back later does not extend it.
        XCTAssertTrue(undo.isActive(at: monday10.addingTimeInterval(2.99)))
        XCTAssertFalse(undo.isActive(at: monday10.addingTimeInterval(3)))
        XCTAssertEqual(undo.remainingSeconds(at: monday10.addingTimeInterval(2.99)), 1)
    }

    // MARK: weekly review preference
    func testWeeklyReviewStatusCountsCalendarDaysNotHours() {
        XCTAssertEqual(ParentingWeeklyReviewStatus.make(lastAt: nil, now: monday10, calendar: calendar), .never)
        XCTAssertEqual(ParentingWeeklyReviewStatus.make(lastAt: at(26, 0, 5), now: at(26, 23, 59), calendar: calendar), .today)
        XCTAssertEqual(ParentingWeeklyReviewStatus.make(lastAt: at(25, 23, 59), now: at(26, 0, 1), calendar: calendar), .daysAgo(1))
        XCTAssertEqual(ParentingWeeklyReviewStatus.make(lastAt: at(19, 20), now: monday10, calendar: calendar), .daysAgo(7))
        XCTAssertEqual(ParentingWeeklyReviewStatus.make(lastAt: at(28, 9), now: monday10, calendar: calendar), .today, "a future date from a clock change is never negative")
    }

    func testWeeklyReviewStoreRoundTripsPerSubjectAndErases() throws {
        let suite = "ParentingTodayDeckTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let a = ParentingWeeklyReviewStore(defaults: defaults, subjectID: id(1))
        let b = ParentingWeeklyReviewStore(defaults: defaults, subjectID: id(2))
        XCTAssertNil(a.load())
        a.save(at(25, 20))
        XCTAssertEqual(a.load(), at(25, 20))
        XCTAssertNil(b.load(), "another signed-in subject has its own value")
        XCTAssertEqual(a.key, "mimi.parenting.weeklyReview.lastAt.v1." + id(1).uuidString.lowercased())
        a.erase()
        XCTAssertNil(a.load())
    }

    func testStoredReviewDateFeedsTheGrowthResolverThroughTheContext() {
        // Observations in the last week and a review stored 8 days ago: growth recommends a weekly review.
        let record = ParentingRecord(id: id(100), childID: child, goalID: nil, arrangementID: nil, kind: .observation, text: "观察", sourceText: "观察", createdAt: at(24, 9), updatedAt: at(24, 9))
        let ws = ParentingWorkspace(children: [.init(id: child, name: "小星", createdAt: at(1))], records: [record])
        let stale = ParentingStartContext(lastWeeklyReviewAt: at(18, 9))
        XCTAssertEqual(ParentingStartAction.resolve(.growth, workspace: ws, now: monday10, calendar: calendar, context: stale), .weeklyReview(reason: .sevenDaysSinceLastReview))
        let fresh = ParentingStartContext(lastWeeklyReviewAt: at(25, 9))
        XCTAssertEqual(ParentingStartAction.resolve(.growth, workspace: ws, now: monday10, calendar: calendar, context: fresh), .recordObservation(childID: child))
    }

    // MARK: 确认卡的两个按钮: 左右布局，辅助功能字号回退为上下

    func testActionPairIsSideBySideUntilAccessibilitySizes() {
        for size in [DynamicTypeSize.xSmall, .small, .medium, .large, .xLarge, .xxLarge, .xxxLarge] {
            XCTAssertEqual(ParentingActionPairLayout.resolve(dynamicTypeSize: size), .sideBySide, "\(size)")
        }
        for size in [DynamicTypeSize.accessibility1, .accessibility2, .accessibility3, .accessibility4, .accessibility5] {
            XCTAssertEqual(ParentingActionPairLayout.resolve(dynamicTypeSize: size), .stacked, "\(size)")
        }
    }

    /// 全部安排 no longer shows the 周复盘 card, but its stored date and the per-subject key keep working (sign-out erases them).
    func testWeeklyReviewKeyPrefixStaysStableForSignOutPurge() {
        XCTAssertEqual(ParentingWeeklyReviewStore.keyPrefix, "mimi.parenting.weeklyReview.lastAt.v1.")
    }
}
