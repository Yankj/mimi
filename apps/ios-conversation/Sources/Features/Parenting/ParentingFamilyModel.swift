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
    /// Index into the avatar background tones (see `ParentingFamilySummary.avatarToneCount`).
    let avatarToneIndex: Int
    let goalCount: Int
    /// Observations only. Completion records are a different kind and are not counted here.
    let observationCount: Int
    let startedAt: Date
}

/// Pure projections of the saved ledger for the 我们家 page. No storage, no network, no new fields.
enum ParentingFamilySummary {
    /// The avatar has no stored colour. A background tone is derived from the child id so it never changes between launches.
    static let avatarToneCount = 4

    static func avatarToneIndex(for id: UUID) -> Int {
        // `uuid.0` is the first byte of the identifier. `hashValue` is not used: it changes on every launch.
        Int(id.uuid.0) % avatarToneCount
    }

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
            avatarToneIndex: avatarToneIndex(for: child.id),
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

/// Where the 启航 n/3 card gets its steps. The real checklist (`ParentingStartAction.launchChecklist`) lives on the
/// unmerged today-start-action branch. This seam lets 我们家 ship without depending on that branch: once it merges,
/// add a provider that wraps it and pass it to `ParentingHomeView(launchChecklist:)`.
protocol FamilyLaunchChecklistProviding {
    func checklist(for workspace: ParentingWorkspace) -> FamilyLaunchChecklist
}

/// Placeholder used until the real checklist is available. It reads only what the local ledger already holds and
/// makes no claim about the final 启航 rules.
struct PlaceholderFamilyLaunchChecklist: FamilyLaunchChecklistProviding {
    func checklist(for workspace: ParentingWorkspace) -> FamilyLaunchChecklist {
        FamilyLaunchChecklist(steps: [
            FamilyLaunchStep(id: "child", title: "添加孩子的昵称", isDone: !workspace.children.isEmpty),
            FamilyLaunchStep(id: "first-record", title: "记下第一件安排或观察",
                             isDone: !workspace.arrangements.isEmpty || workspace.records.contains { $0.kind == .observation }),
            FamilyLaunchStep(id: "goal", title: "写下一个成长目标", isDone: !workspace.goals.isEmpty),
        ])
    }
}
