import Foundation

/// Which chip of the shared child switcher is selected.
enum ParentingChildSwitcherSelection: Equatable {
    case all
    case child(UUID)
}

/// Selection rules of the one child switcher used by 成长, 全部安排 and 今天.
/// - A stored choice that no longer exists falls back instead of showing nothing selected.
/// - 「全部」 only exists where it means something: the host allows it and there is more than one child.
/// - With a single child that child is always the selected one, so the page still says who it is about.
enum ParentingChildSwitcherRules {
    static func showsAllChip(childCount: Int, allowsAll: Bool) -> Bool { allowsAll && childCount > 1 }

    /// `fallsBackToFirst: false` is for a question ("这是哪位孩子的事？"): nothing is pre-selected, so no child is guessed.
    static func resolve(selected: UUID?, childIDs: [UUID], allowsAll: Bool, fallsBackToFirst: Bool = true) -> ParentingChildSwitcherSelection {
        if let selected, childIDs.contains(selected) { return .child(selected) }
        if showsAllChip(childCount: childIDs.count, allowsAll: allowsAll) { return .all }
        if fallsBackToFirst, let first = childIDs.first { return .child(first) }
        return .all
    }

    /// What tapping a chip tells the host: a child id, or nil for 「全部」.
    static func value(for selection: ParentingChildSwitcherSelection) -> UUID? {
        if case .child(let id) = selection { return id }
        return nil
    }
}

/// What removing a child deletes. `deleteChild` is a real delete, not an archive: the profile, goals, every arrangement
/// (any state), every record and the adjustment history of that child go, and the photo with them.
struct ParentingChildRemovalSummary: Equatable {
    let name: String
    let goalCount: Int
    let arrangementCount: Int
    let observationCount: Int
    let completionCount: Int
    var recordCount: Int { observationCount + completionCount }
    var isEmpty: Bool { goalCount == 0 && arrangementCount == 0 && recordCount == 0 }

    static func summary(of childID: UUID, in workspace: ParentingWorkspace) -> ParentingChildRemovalSummary? {
        guard let child = workspace.children.first(where: { $0.id == childID }) else { return nil }
        return ParentingChildRemovalSummary(
            name: child.name,
            goalCount: workspace.goals.filter { $0.childID == childID }.count,
            arrangementCount: workspace.arrangements.filter { $0.childID == childID }.count,
            observationCount: workspace.records.filter { $0.childID == childID && $0.kind == .observation }.count,
            completionCount: workspace.records.filter { $0.childID == childID && $0.kind == .completion }.count)
    }
}
