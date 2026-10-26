import Foundation

/// A typed, closed catalogue of blocks a confirmation card can show. The view layer renders each case with a fixed SwiftUI
/// component; there is no free-form markup or script path, so a card can never show something the catalogue does not define.
public struct ParentingConfirmCard: Equatable, Sendable {
    public enum Tone: String, Sendable { case normal, changed, unknown, warning }
    public enum Value: Equatable, Sendable {
        case text(String)   // typed by the parent or a saved name
        case date(Date)     // formatted by the view
        case key(String)    // a resource key shown through ui(...)
    }
    public struct Field: Equatable, Sendable, Identifiable {
        public var id: String { label }
        public let label: String   // resource key
        public let value: Value
        public let tone: Tone
    }
    public struct Conflict: Equatable, Sendable, Identifiable {
        public let id: UUID
        public let title: String
        public let at: Date
    }
    public enum Block: Equatable, Sendable {
        case headline(title: String, symbol: String)
        case fields([Field])
        /// Old and new value side by side.
        case change(label: String, before: Date, after: Date)
        case quote(label: String, text: String)
        case conflicts(title: String, items: [Conflict])
        case note(String, Tone)
    }

    public let kind: ParentingIntentKind
    public let blocks: [Block]
    /// Resource key of the primary button.
    public let confirmTitle: String
    /// True for cards whose button writes to the ledger.
    public let writes: Bool

    /// Builds the card from a ready proposal. Returns nil while anything is still missing: no card ever stands in for a question.
    public static func make(_ p: ParentingNaturalProposal, workspace: ParentingWorkspace, remindersEnabled: Bool?, now: Date) -> ParentingConfirmCard? {
        guard p.isReadyToConfirm else { return nil }
        let childName = p.childID.flatMap { id in workspace.children.first { $0.id == id }?.name }
        switch p.kind {
        case .newArrangement:
            guard let childName, let title = p.title, let due = p.dueAt else { return nil }
            var blocks: [Block] = [
                .headline(title: "新安排", symbol: "calendar.badge.plus"),
                .fields([.init(label: "孩子", value: .text(childName), tone: .normal),
                         .init(label: "事项", value: .text(title), tone: .normal),
                         .init(label: "时间", value: .date(due), tone: .normal)]),
            ]
            let conflicts = conflictItems(workspace, excluding: nil, at: due)
            if !conflicts.isEmpty { blocks.append(.conflicts(title: "同一时间已有安排，请核对", items: conflicts)) }
            if due < now { blocks.append(.note("这个时间已经过去，请确认。", .warning)) }
            blocks.append(.quote(label: "你说的原话", text: p.sourceText))
            blocks.append(.note("确认后才进入今天的安排。", .normal))
            if let line = reminderNote(remindersEnabled) { blocks.append(line) }
            return .init(kind: p.kind, blocks: blocks, confirmTitle: "确认安排", writes: true)
        case .reschedule:
            guard let childName, let title = p.title, let due = p.dueAt, let id = p.targetID,
                  let original = workspace.arrangements.first(where: { $0.id == id }) else { return nil }
            var blocks: [Block] = [
                .headline(title: "改期", symbol: "arrow.triangle.2.circlepath"),
                .fields([.init(label: "孩子", value: .text(childName), tone: .normal),
                         .init(label: "事项", value: .text(title), tone: .normal)]),
                .change(label: "时间", before: original.dueAt, after: due),
            ]
            let conflicts = conflictItems(workspace, excluding: id, at: due)
            if !conflicts.isEmpty { blocks.append(.conflicts(title: "同一时间已有安排，确认即表示已知悉", items: conflicts)) }
            if p.inheritedDate { blocks.append(.note("日期沿用原安排；不对可以点“手动调整”。", .unknown)) }
            if due < now { blocks.append(.note("这个时间已经过去，请确认。", .warning)) }
            blocks.append(.quote(label: "你说的原话", text: p.sourceText))
            blocks.append(.note("只改这一项安排，不影响其他安排。可以在安排里撤销这次调整。", .normal))
            if let line = reminderNote(remindersEnabled) { blocks.append(line) }
            return .init(kind: p.kind, blocks: blocks, confirmTitle: "确认改期", writes: true)
        case .observation:
            guard let childName, let text = p.observationText else { return nil }
            return .init(kind: p.kind, blocks: [
                .headline(title: "成长观察", symbol: "leaf"),
                .fields([.init(label: "孩子", value: .text(childName), tone: .normal)]),
                .quote(label: "记下的内容", text: text),
                .note("只保存这条观察，不会写入长期记忆；之后可以修改或删除。", .normal),
            ], confirmTitle: "保存观察", writes: true)
        case .viewToday, .viewPlan, .reviewGrowth:
            let title = p.kind == .reviewGrowth ? "看成长回顾" : (p.kind == .viewPlan ? "看计划" : "看今天")
            return .init(kind: p.kind, blocks: [
                .headline(title: title, symbol: p.kind == .reviewGrowth ? "leaf" : "sun.max"),
                .note("这是查看，不会写入或修改任何内容。", .normal),
            ], confirmTitle: "带我去看", writes: false)
        case .unknown: return nil
        }
    }

    private static func conflictItems(_ workspace: ParentingWorkspace, excluding: UUID?, at due: Date) -> [Conflict] {
        workspace.sameTimeConflicts(arrangementID: excluding ?? UUID(), dueAt: due).map { .init(id: $0.id, title: $0.title, at: $0.dueAt) }
    }
    private static func reminderNote(_ enabled: Bool?) -> Block? {
        guard let enabled else { return nil }
        return enabled ? .note("提醒按你的设置发送：每天最多 2 条，21:00–08:00 不打扰。", .normal)
                       : .note("提醒没有开启；可以在“我们家”里打开。", .unknown)
    }
}
