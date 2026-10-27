import Foundation

/// Pages pushed on top of 我们家. Hashable so `NavigationStack(path:)` can hold them.
enum ParentingFamilyRoute: Hashable {
    case child(UUID)
    case reminders
    case settings
}

/// What one child profile shows. Only data the local ledger already stores: the nickname, the goals and the observations.
/// There is no photo, height, weight or milestone field, and none is invented here.
struct ParentingFamilyChildSummary: Identifiable, Equatable {
    let id: UUID
    let name: String
    /// First character of the nickname, shown on the avatar. "•" when the nickname is empty.
    let initial: String
    /// The child's accent slot (creation order, wrapped). The same slot colours the avatar ring on 今天, 成长 and 我们家
    /// through `ParentingChildAccent.color(slot:)`.
    let accentSlot: Int
    let goalCount: Int
    /// Observations only. Completion records are a different kind and are not counted here.
    let observationCount: Int
    let startedAt: Date
}

/// Pure projections of the saved ledger for the 我们家 page. No storage, no network, no new fields.
enum ParentingFamilySummary {
    static func initial(of name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return "•" }
        return String(first)
    }

    static func summary(of child: ParentingChild, in workspace: ParentingWorkspace) -> ParentingFamilyChildSummary {
        ParentingFamilyChildSummary(
            id: child.id,
            name: child.name,
            initial: initial(of: child.name),
            accentSlot: ParentingGrowthInsights.accentSlot(for: child.id, in: workspace),
            goalCount: workspace.goals.filter { $0.childID == child.id }.count,
            observationCount: workspace.records.filter { $0.childID == child.id && $0.kind == .observation }.count,
            startedAt: child.createdAt)
    }

    static func children(in workspace: ParentingWorkspace) -> [ParentingFamilyChildSummary] {
        workspace.children.map { summary(of: $0, in: workspace) }
    }
}

/// One step of the 启航 card.
struct FamilyLaunchStep: Identifiable, Equatable {
    let id: String
    /// Resource key shown to the parent through `ui(...)`.
    let title: String
    let isDone: Bool
}

struct FamilyLaunchChecklist: Equatable {
    let steps: [FamilyLaunchStep]
    var doneCount: Int { steps.filter { $0.isDone }.count }
    var totalCount: Int { steps.count }
}

/// 启航 n/3 is `ParentingStartAction.launchChecklist` (#132): the same rules that decide the 我们家 start action. Only the
/// wording of the three steps lives here.
extension FamilyLaunchChecklist {
    init(_ checklist: ParentingLaunchChecklist) {
        self.init(steps: [
            FamilyLaunchStep(id: "child", title: "添加孩子的昵称", isDone: checklist.done.contains(.addChild)),
            FamilyLaunchStep(id: "first-record", title: "记下第一件安排或观察", isDone: checklist.done.contains(.recordFirstThing)),
            FamilyLaunchStep(id: "reminders", title: "到点提醒", isDone: checklist.done.contains(.enableReminders)),
        ])
    }

    static func current(workspace: ParentingWorkspace, remindersEnabled: Bool) -> FamilyLaunchChecklist {
        FamilyLaunchChecklist(ParentingStartAction.launchChecklist(
            workspace: workspace, context: ParentingStartContext(remindersEnabled: remindersEnabled)))
    }
}
