import Foundation
import XCTest
@testable import cat

/// Pure projections behind the 我们家 page. These check the numbers, the avatar tone and the launch placeholder;
/// they say nothing about how the page looks or navigates.
final class ParentingFamilySummaryTests: XCTestCase {
    private let day = Date(timeIntervalSince1970: 1_800_000_000)

    private func child(_ id: String, _ name: String) -> ParentingChild {
        ParentingChild(id: UUID(uuidString: id)!, name: name, createdAt: day)
    }
    private func goal(_ id: String, child: ParentingChild) -> ParentingGoal {
        ParentingGoal(id: UUID(uuidString: id)!, childID: child.id, title: "Synthetic goal", createdAt: day)
    }
    private func record(_ id: String, child: ParentingChild, kind: ParentingRecordKind) -> ParentingRecord {
        ParentingRecord(id: UUID(uuidString: id)!, childID: child.id, goalID: nil, arrangementID: nil, kind: kind,
                        text: "Synthetic text", sourceText: "", createdAt: day, updatedAt: day)
    }
    private func arrangement(_ id: String, child: ParentingChild) -> ParentingArrangement {
        ParentingArrangement(id: UUID(uuidString: id)!, childID: child.id, title: "Synthetic arrangement", dueAt: day,
                             goalID: nil, state: .planned, sourceText: "", createdAt: day, updatedAt: day)
    }

    func testAccentSlotFollowsCreationOrderAndIsStable() {
        let older = ParentingChild(id: UUID(uuidString: "FF000000-0000-0000-0000-000000000000")!, name: "A", createdAt: day)
        let newer = ParentingChild(id: UUID(uuidString: "01000000-0000-0000-0000-000000000000")!, name: "B",
                                   createdAt: day.addingTimeInterval(60))
        let workspace = ParentingWorkspace(children: [newer, older])
        let slots = ParentingFamilySummary.children(in: workspace).map(\.accentSlot)
        XCTAssertEqual(slots, [1, 0], "the older child is slot 0 whatever the array order")
        XCTAssertEqual(ParentingFamilySummary.children(in: workspace).map(\.accentSlot), slots)
        XCTAssertTrue(slots.allSatisfy { (0..<ParentingGrowthInsights.accentSlotCount).contains($0) })
    }

    func testInitialUsesTheFirstCharacterOfTheTrimmedNickname() {
        XCTAssertEqual(ParentingFamilySummary.initial(of: "  Mimi "), "M")
        XCTAssertEqual(ParentingFamilySummary.initial(of: "小米"), "小")
        XCTAssertEqual(ParentingFamilySummary.initial(of: "   "), "•")
        XCTAssertEqual(ParentingFamilySummary.initial(of: ""), "•")
    }

    func testSummaryCountsOnlyThisChildsGoalsAndObservations() {
        let a = child("00000000-0000-0000-0000-0000000000A1", "Synthetic A")
        let b = child("00000000-0000-0000-0000-0000000000B1", "Synthetic B")
        let workspace = ParentingWorkspace(
            children: [a, b],
            goals: [goal("00000000-0000-0000-0000-0000000000C1", child: a),
                    goal("00000000-0000-0000-0000-0000000000C2", child: a),
                    goal("00000000-0000-0000-0000-0000000000C3", child: b)],
            arrangements: [arrangement("00000000-0000-0000-0000-0000000000D1", child: a)],
            records: [record("00000000-0000-0000-0000-0000000000E1", child: a, kind: .observation),
                      record("00000000-0000-0000-0000-0000000000E2", child: a, kind: .completion),
                      record("00000000-0000-0000-0000-0000000000E3", child: b, kind: .observation)])
        let summaries = ParentingFamilySummary.children(in: workspace)
        XCTAssertEqual(summaries.map(\.id), [a.id, b.id])
        XCTAssertEqual(summaries[0].goalCount, 2)
        XCTAssertEqual(summaries[0].observationCount, 1, "A completion record is not an observation.")
        XCTAssertEqual(summaries[1].goalCount, 1)
        XCTAssertEqual(summaries[1].observationCount, 1)
        XCTAssertEqual(summaries[0].name, "Synthetic A")
        XCTAssertEqual(summaries[0].initial, "S")
        XCTAssertEqual(summaries[0].startedAt, day)
    }

    func testNoChildrenGivesNoCards() {
        XCTAssertTrue(ParentingFamilySummary.children(in: .empty).isEmpty)
    }

    private func launch(_ workspace: ParentingWorkspace, reminders: Bool = false) -> FamilyLaunchChecklist {
        FamilyLaunchChecklist.current(workspace: workspace, remindersEnabled: reminders)
    }

    func testLaunchChecklistFollowsTheStartActionRules() {
        let empty = launch(.empty)
        XCTAssertEqual(empty.totalCount, 3)
        XCTAssertEqual(empty.doneCount, 0)

        let a = child("00000000-0000-0000-0000-0000000000A1", "Synthetic A")
        XCTAssertEqual(launch(ParentingWorkspace(children: [a])).doneCount, 1)

        let withArrangement = ParentingWorkspace(
            children: [a], arrangements: [arrangement("00000000-0000-0000-0000-0000000000D1", child: a)])
        XCTAssertEqual(launch(withArrangement).doneCount, 2)
        XCTAssertEqual(launch(withArrangement, reminders: true).doneCount, 3)

        let withObservation = ParentingWorkspace(
            children: [a], records: [record("00000000-0000-0000-0000-0000000000E1", child: a, kind: .observation)])
        XCTAssertEqual(launch(withObservation).doneCount, 2)
        XCTAssertEqual(launch(withObservation).steps.map(\.id), ["child", "first-record", "reminders"])
    }

    func testRoutesAreHashableAndDistinct() {
        let id = UUID()
        XCTAssertEqual(ParentingFamilyRoute.child(id), ParentingFamilyRoute.child(id))
        XCTAssertNotEqual(ParentingFamilyRoute.child(id), ParentingFamilyRoute.child(UUID()))
        XCTAssertEqual(Set([ParentingFamilyRoute.settings, .reminders, .settings]).count, 2)
    }
}
