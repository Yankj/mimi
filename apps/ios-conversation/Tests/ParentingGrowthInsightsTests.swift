import Foundation
import XCTest
@testable import cat

/// Pure-logic tests for the 成长 tab visuals (week density, journey, goal progress, start action).
/// No UIKit and no storage, so the same file also runs on Linux against the Domain sources.
final class ParentingGrowthInsightsTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return c
    }()
    /// Monday 2026-10-26 20:00 (UTC+8).
    private lazy var now: Date = date(2026, 10, 26, 20, 0)

    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 9, _ min: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }
    private func daysAgo(_ n: Int, hour: Int = 9) -> Date {
        let day = calendar.date(byAdding: .day, value: -n, to: calendar.startOfDay(for: now))!
        return calendar.date(byAdding: .hour, value: hour, to: day)!
    }
    private func id(_ n: Int) -> UUID { UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", n))! }

    private let childA = UUID(uuidString: "AAAAAAAA-0000-4000-8000-000000000001")!
    private let childB = UUID(uuidString: "BBBBBBBB-0000-4000-8000-000000000002")!
    private var counter = 100

    private func nextID() -> UUID { counter += 1; return id(counter) }
    private func child(_ uuid: UUID, _ name: String, created: Date) -> ParentingChild { ParentingChild(id: uuid, name: name, createdAt: created) }
    private func record(_ kind: ParentingRecordKind = .observation, child: UUID, goal: UUID? = nil, at: Date) -> ParentingRecord {
        ParentingRecord(id: nextID(), childID: child, goalID: goal, arrangementID: nil, kind: kind, text: "r", sourceText: "", createdAt: at, updatedAt: at)
    }
    private func goal(_ n: Int, child: UUID, created: Date) -> ParentingGoal { ParentingGoal(id: id(n), childID: child, title: "g\(n)", createdAt: created) }
    private func arrangement(goal: UUID?, child: UUID, state: ParentingArrangementState, due: Date) -> ParentingArrangement {
        ParentingArrangement(id: nextID(), childID: child, title: "a", dueAt: due, goalID: goal, state: state, sourceText: "", createdAt: due, updatedAt: due)
    }
    private func workspace(children: [ParentingChild] = [], goals: [ParentingGoal] = [], arrangements: [ParentingArrangement] = [],
                           records: [ParentingRecord] = []) -> ParentingWorkspace {
        ParentingWorkspace(children: children, goals: goals, arrangements: arrangements, records: records)
    }
    private var oneChild: [ParentingChild] { [child(childA, "A", created: daysAgo(60))] }

    // MARK: Week density

    func testWeekDensityCoversSevenDaysEndingToday() {
        let density = ParentingWeekDensity.build(workspace: workspace(children: oneChild), childID: childA, now: now, calendar: calendar)
        XCTAssertEqual(density.days.count, 7)
        XCTAssertEqual(density.days.last?.date, calendar.startOfDay(for: now))
        XCTAssertEqual(density.days.first?.date, calendar.startOfDay(for: daysAgo(6)))
        XCTAssertEqual(density.days.filter(\.isToday).count, 1)
        XCTAssertEqual(density.total, 0)
        XCTAssertEqual(density.peak, 0)
        XCTAssertEqual(density.level(of: density.days[0]), 0)
    }

    func testWeekDensityCountsKindsPerDayAndIgnoresOtherChildrenAndOlderRecords() {
        let records = [
            record(child: childA, at: daysAgo(0, hour: 8)), record(.completion, child: childA, at: daysAgo(0, hour: 18)),
            record(child: childA, at: daysAgo(2)), record(child: childA, at: daysAgo(6, hour: 23)),
            record(child: childA, at: daysAgo(7)),            // outside the window
            record(child: childB, at: daysAgo(0))             // another child
        ]
        let ws = workspace(children: oneChild + [child(childB, "B", created: daysAgo(30))], records: records)
        let density = ParentingWeekDensity.build(workspace: ws, childID: childA, now: now, calendar: calendar)
        XCTAssertEqual(density.days.map(\.total), [1, 0, 0, 0, 1, 0, 2])
        XCTAssertEqual(density.days.last?.observations, 1)
        XCTAssertEqual(density.days.last?.completions, 1)
        XCTAssertEqual(density.total, 4)
        XCTAssertEqual(density.activeDays, 3)
        XCTAssertEqual(density.peak, 2)
        let all = ParentingWeekDensity.build(workspace: ws, childID: nil, now: now, calendar: calendar)
        XCTAssertEqual(all.total, 5)
    }

    func testWeekDensityLevelsAreRelativeToPeakWithAVisibleFloor() {
        let records = (0..<10).map { _ in record(child: childA, at: daysAgo(0)) } + [record(child: childA, at: daysAgo(3))]
        let density = ParentingWeekDensity.build(workspace: workspace(children: oneChild, records: records), childID: childA, now: now, calendar: calendar)
        XCTAssertEqual(density.level(of: density.days[6]), 1, accuracy: 1e-9)
        XCTAssertEqual(density.level(of: density.days[3]), 0.18, accuracy: 1e-9)   // 0.1 raised to the floor
        XCTAssertEqual(density.level(of: density.days[0]), 0)
    }

    func testWeekDensityWindowFollowsCalendarDaysAcrossTheMidnightBoundary() {
        let late = date(2026, 10, 26, 0, 5)
        let early = date(2026, 10, 25, 23, 55)
        let ws = workspace(children: oneChild, records: [record(child: childA, at: late), record(child: childA, at: early)])
        let density = ParentingWeekDensity.build(workspace: ws, childID: childA, now: late, calendar: calendar)
        XCTAssertEqual(density.days[6].total, 1)
        XCTAssertEqual(density.days[5].total, 1)
    }

    // MARK: Journey

    func testJourneyGroupsByDayNewestFirstAndKeepsFilters() {
        let a = record(child: childA, at: daysAgo(0, hour: 8))
        let b = record(.completion, child: childA, at: daysAgo(0, hour: 21))
        let c = record(child: childA, at: daysAgo(3))
        let ws = workspace(children: oneChild, records: [a, b, c])
        let all = ParentingGrowthJourney.build(workspace: ws, childID: childA, calendar: calendar)
        XCTAssertEqual(all.days.count, 2)
        XCTAssertEqual(all.days[0].items.map(\.id), [b.id, a.id])
        XCTAssertEqual(all.days[1].items.map(\.id), [c.id])
        XCTAssertEqual(all.recordCount, 3)
        let onlyObservations = ParentingGrowthJourney.build(workspace: ws, childID: childA, filter: .observation, calendar: calendar)
        XCTAssertEqual(onlyObservations.recordCount, 2)
        let onlyCompletions = ParentingGrowthJourney.build(workspace: ws, childID: childA, filter: .completion, calendar: calendar)
        XCTAssertEqual(onlyCompletions.days.count, 1)
        XCTAssertTrue(ParentingGrowthJourney.build(workspace: workspace(children: oneChild), childID: childA, calendar: calendar).isEmpty)
    }

    func testJourneyMarksFirstRecordAndCountMilestonesPerChild() {
        var records = (0..<12).map { record(child: childA, at: daysAgo(40 - $0, hour: 7)) }
        records.append(record(child: childB, at: daysAgo(5)))
        let ws = workspace(children: oneChild + [child(childB, "B", created: daysAgo(30))], records: records)
        let journey = ParentingGrowthJourney.build(workspace: ws, childID: nil, calendar: calendar)
        var milestones: [UUID: ParentingGrowthMilestone] = [:]
        for day in journey.days { for case .record(let r, let m?) in day.items { milestones[r.id] = m } }
        let ordered = records.filter { $0.childID == childA }.sorted { $0.createdAt < $1.createdAt }
        XCTAssertEqual(milestones[ordered[0].id], .firstRecord)
        XCTAssertEqual(milestones[ordered[9].id], .recordCount(10))
        XCTAssertNil(milestones[ordered[1].id])
        XCTAssertEqual(milestones[records.last!.id], .firstRecord)      // child B has its own first record
        XCTAssertEqual(milestones.count, 3)
    }

    func testJourneyMilestoneSurvivesFilteringAndGoalStartedOnlyInAllView() {
        let g = goal(1, child: childA, created: daysAgo(4))
        let first = record(.completion, child: childA, goal: g.id, at: daysAgo(4, hour: 12))
        let second = record(child: childA, goal: g.id, at: daysAgo(1))
        let ws = workspace(children: oneChild, goals: [g], records: [first, second])
        let all = ParentingGrowthJourney.build(workspace: ws, childID: childA, calendar: calendar)
        XCTAssertEqual(all.days.count, 2)
        XCTAssertEqual(all.days.last?.items.count, 2)                   // goal started + first record on the same day
        XCTAssertEqual(all.days.last?.items.first, .record(first, milestone: .firstRecord))   // 12:00 sorts after 09:00
        let observationsOnly = ParentingGrowthJourney.build(workspace: ws, childID: childA, filter: .observation, calendar: calendar)
        XCTAssertEqual(observationsOnly.days.count, 1)
        XCTAssertEqual(observationsOnly.days[0].items, [.record(second, milestone: nil)])
        let completionsOnly = ParentingGrowthJourney.build(workspace: ws, childID: childA, filter: .completion, calendar: calendar)
        XCTAssertEqual(completionsOnly.days[0].items, [.record(first, milestone: .firstRecord)])
        let forGoal = ParentingGrowthJourney.build(workspace: ws, childID: childA, goalID: g.id, calendar: calendar)
        XCTAssertEqual(forGoal.recordCount, 2)
    }

    // MARK: Goal progress

    func testGoalProgressCountsLinkedRecordsAndArrangements() {
        let g = goal(1, child: childA, created: daysAgo(20))
        let other = goal(2, child: childA, created: daysAgo(10))
        let rs = [record(child: childA, goal: g.id, at: daysAgo(3)), record(.completion, child: childA, goal: g.id, at: daysAgo(1)),
                  record(child: childA, goal: other.id, at: daysAgo(30)), record(child: childA, at: daysAgo(0))]
        let arrangements = [
            arrangement(goal: g.id, child: childA, state: .completed, due: daysAgo(1)),
            arrangement(goal: g.id, child: childA, state: .planned, due: daysAgo(-2)),
            arrangement(goal: g.id, child: childA, state: .planned, due: daysAgo(-5)),
            arrangement(goal: g.id, child: childA, state: .draft, due: daysAgo(-6)),
            arrangement(goal: g.id, child: childA, state: .cancelled, due: daysAgo(-7)),
            arrangement(goal: other.id, child: childA, state: .completed, due: daysAgo(2))
        ]
        let ws = workspace(children: oneChild, goals: [other, g], arrangements: arrangements, records: rs)
        let progress = ParentingGoalProgress.build(workspace: ws, childID: childA, now: now, calendar: calendar)
        XCTAssertEqual(progress.map(\.goal.id), [g.id, other.id], "creation order")
        let p = progress[0]
        XCTAssertEqual(p.observationCount, 1)
        XCTAssertEqual(p.completionCount, 1)
        XCTAssertEqual(p.recordCount, 2)
        XCTAssertEqual(p.doneArrangements, 1)
        XCTAssertEqual(p.openArrangements, 3)
        XCTAssertEqual(try XCTUnwrap(p.fraction), 0.25, accuracy: 1e-9)
        XCTAssertEqual(p.daysSinceActivity, 1)
        XCTAssertEqual(p.activity, .recent)
        XCTAssertEqual(p.nextDue, daysAgo(-2))
        XCTAssertEqual(p.marks.map(\.reached), [true, false, false, false])
        XCTAssertEqual(p.reachedMarks, 1)
        XCTAssertEqual(p.nextMark, 5)
        XCTAssertEqual(progress[1].activity, .quiet)
        XCTAssertEqual(progress[1].fraction, 1)
    }

    func testGoalWithoutAnythingIsNotStartedAndHasNoRing() {
        let g = goal(1, child: childA, created: daysAgo(1))
        let progress = ParentingGoalProgress.build(workspace: workspace(children: oneChild, goals: [g]), childID: childA, now: now, calendar: calendar)
        XCTAssertEqual(progress[0].activity, .notStarted)
        XCTAssertNil(progress[0].fraction)
        XCTAssertNil(progress[0].lastActivity)
        XCTAssertNil(progress[0].nextDue)
        XCTAssertEqual(progress[0].reachedMarks, 0)
        XCTAssertEqual(progress[0].nextMark, 1)
    }

    func testGoalQuietBoundaryIsMoreThanSevenDays() {
        let g = goal(1, child: childA, created: daysAgo(30))
        func activity(_ days: Int) -> ParentingGoalProgress.Activity {
            let ws = workspace(children: oneChild, goals: [g], records: [record(child: childA, goal: g.id, at: daysAgo(days))])
            return ParentingGoalProgress.build(workspace: ws, childID: childA, now: now, calendar: calendar)[0].activity
        }
        XCTAssertEqual(activity(7), .recent)
        XCTAssertEqual(activity(8), .quiet)
    }

    func testAllMarksReachedLeavesNoNextMark() {
        let g = goal(1, child: childA, created: daysAgo(30))
        let rs = (0..<20).map { record(child: childA, goal: g.id, at: daysAgo($0 % 5)) }
        let p = ParentingGoalProgress.build(workspace: workspace(children: oneChild, goals: [g], records: rs), childID: childA, now: now, calendar: calendar)[0]
        XCTAssertEqual(p.reachedMarks, 4)
        XCTAssertNil(p.nextMark)
    }

    // MARK: Start action

    private func start(_ ws: ParentingWorkspace, child: UUID? = nil) -> ParentingGrowthStart {
        ParentingGrowthStart.choose(workspace: ws, childID: child, now: now, calendar: calendar)
    }

    func testStartWithoutChildAsksToAddOne() {
        XCTAssertEqual(start(workspace()), ParentingGrowthStart(kind: .addChild, childID: nil))
    }

    func testStartWithEmptyLedgerAsksForFirstObservation() {
        XCTAssertEqual(start(workspace(children: oneChild)), ParentingGrowthStart(kind: .firstObservation, childID: childA))
    }

    func testStartWithGoalButNoRecordsPointsAtTheOldestGoal() {
        let older = goal(1, child: childA, created: daysAgo(5)), newer = goal(2, child: childA, created: daysAgo(1))
        XCTAssertEqual(start(workspace(children: oneChild, goals: [newer, older])).kind, .recordForGoal(goalID: older.id))
    }

    func testStartOffersAGoalAfterFiveObservationsWithoutOne() {
        func ws(_ n: Int) -> ParentingWorkspace { workspace(children: oneChild, records: (0..<n).map { record(child: childA, at: daysAgo(3 + $0)) }) }
        XCTAssertEqual(start(ws(4)).kind, .recordObservation)
        XCTAssertEqual(start(ws(5)).kind, .makeGoal(observationCount: 5))
        // Completion records do not count as observations.
        let mixed = workspace(children: oneChild, records: (0..<5).map { record(.completion, child: childA, at: daysAgo(3 + $0)) })
        XCTAssertEqual(start(mixed).kind, .recordObservation)
    }

    func testStartDoesNotNagRightAfterARecordEvenWithAQuietGoal() {
        let g = goal(1, child: childA, created: daysAgo(30))
        let ws = workspace(children: oneChild, goals: [g], records: [record(child: childA, at: daysAgo(0, hour: 8))])
        XCTAssertEqual(start(ws).kind, .reviewWeek)
    }

    func testStartPrefersAGoalWithNoRecordThenTheLongestQuietOne() {
        let quiet9 = goal(1, child: childA, created: daysAgo(40)), quiet20 = goal(2, child: childA, created: daysAgo(40))
        let fresh = goal(3, child: childA, created: daysAgo(40)), empty = goal(4, child: childA, created: daysAgo(2))
        let base = [record(child: childA, goal: quiet9.id, at: daysAgo(9)), record(child: childA, goal: quiet20.id, at: daysAgo(20)),
                    record(child: childA, goal: fresh.id, at: daysAgo(2))]
        XCTAssertEqual(start(workspace(children: oneChild, goals: [quiet9, quiet20, fresh, empty], records: base)).kind, .recordForGoal(goalID: empty.id))
        XCTAssertEqual(start(workspace(children: oneChild, goals: [quiet9, quiet20, fresh], records: base)).kind, .recordForGoal(goalID: quiet20.id))
        let recentGoalRecord = record(child: childA, goal: fresh.id, at: daysAgo(1))
        XCTAssertEqual(start(workspace(children: oneChild, goals: [fresh], records: [recentGoalRecord])).kind, .reviewWeek)
        XCTAssertEqual(start(workspace(children: oneChild, goals: [fresh], records: [base[2]])).kind, .recordObservation, "all goals recent, but two silent days")
    }

    func testStartAsksForAnObservationAfterTwoDaysOfSilenceElseReviewsTheWeek() {
        func ws(daysSince: Int) -> ParentingWorkspace { workspace(children: oneChild, records: [record(child: childA, at: daysAgo(daysSince))]) }
        XCTAssertEqual(start(ws(daysSince: 1)).kind, .reviewWeek)
        XCTAssertEqual(start(ws(daysSince: 2)).kind, .recordObservation)
    }

    func testStartIsScopedToTheRequestedChildAndFallsBackToTheEarliest() {
        let kids = [child(childB, "B", created: daysAgo(10)), child(childA, "A", created: daysAgo(60))]
        let ws = workspace(children: kids, records: [record(child: childA, at: daysAgo(0, hour: 7))])
        XCTAssertEqual(start(ws, child: childB), ParentingGrowthStart(kind: .firstObservation, childID: childB))
        XCTAssertEqual(start(ws, child: nil), ParentingGrowthStart(kind: .reviewWeek, childID: childA))
        XCTAssertEqual(start(ws, child: UUID()).childID, childA, "an unknown child falls back to the earliest")
    }

    func testStartIsDeterministic() {
        let g = goal(1, child: childA, created: daysAgo(30))
        let ws = workspace(children: oneChild, goals: [g], records: [record(child: childA, goal: g.id, at: daysAgo(12))])
        XCTAssertEqual(start(ws), start(ws))
    }

    // MARK: Accent slots

    func testAccentSlotFollowsCreationOrderAndWraps() {
        let ids = (1...7).map { id($0) }
        let kids = ids.enumerated().map { child($1, "k\($0)", created: daysAgo(100 - $0)) }
        let ws = workspace(children: kids.reversed())
        XCTAssertEqual(ids.map { ParentingGrowthInsights.accentSlot(for: $0, in: ws) }, [0, 1, 2, 3, 4, 0, 1])
        XCTAssertEqual(ParentingGrowthInsights.accentSlot(for: nil, in: ws), 0)
        XCTAssertEqual(ParentingGrowthInsights.accentSlot(for: UUID(), in: ws), 0)
    }
}
