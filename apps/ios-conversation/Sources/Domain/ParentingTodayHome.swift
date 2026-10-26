import Foundation

/// How a confirmed arrangement relates to the clock. Marking is presentation only: nothing here (or anywhere in the
/// start-action logic) ever completes, cancels or moves an arrangement. Only the parent's own command can.
public enum ParentingOverdueMark: Equatable, Sendable {
    /// Not overdue: finished, cancelled, a draft, or still ahead.
    case none
    /// Planned for earlier today and not marked done yet.
    case passedToday
    /// Planned for an earlier day and not marked done.
    case previousDay

    public static func mark(for arrangement: ParentingArrangement, now: Date, calendar: Calendar = .autoupdatingCurrent) -> ParentingOverdueMark {
        guard arrangement.state == .planned, arrangement.dueAt < now else { return .none }
        return calendar.isDate(arrangement.dueAt, inSameDayAs: now) ? .passedToday : .previousDay
    }
}

/// Data for the new Today screen, derived read-only from the ledger. Rows are capped so the first screen stays short;
/// everything else is reachable from "all arrangements".
public struct ParentingTodayHome: Equatable, Sendable {
    public struct Row: Equatable, Sendable, Identifiable {
        public let id: UUID
        public let time: Date
        public let content: ParentingTodayTimeline.Content
        public let mark: ParentingOverdueMark
        public let sameTimeConflict: Bool
    }

    public static let defaultRowCap = 5

    /// e.g. "3 件·已完成 1"; "今天暂无安排" when nothing is planned or done today.
    public let subtitle: String
    /// At most `rowCap` rows in time order.
    public let rows: [Row]
    /// The "now" line is drawn before this row index (0...rows.count).
    public let nowIndex: Int
    /// Today's entries that did not fit in `rows`.
    public let hiddenTodayCount: Int
    public let upcomingCount: Int
    public let overdueCount: Int
    public let pendingCount: Int
    /// True when "全部安排" holds something the home does not show.
    public var hasMoreInAllArrangements: Bool { hiddenTodayCount > 0 || upcomingCount > 0 || overdueCount > 0 }

    public static func subtitle(planned: Int, done: Int) -> String {
        let total = planned + done
        return total == 0 ? "今天暂无安排" : "\(total) 件·已完成 \(done)"
    }

    public static func build(workspace: ParentingWorkspace, childID: UUID? = nil, now: Date, calendar: Calendar = .autoupdatingCurrent,
                             rowCap: Int = defaultRowCap) -> ParentingTodayHome {
        let timeline = ParentingTodayTimeline.build(workspace: workspace, childID: childID, now: now, calendar: calendar)
        let cap = max(rowCap, 0)
        let entries = timeline.entries
        // Choose what to show: entries still ahead come first (the parent acts on those), then the most recent past ones.
        let ahead = entries.filter { $0.time > now }
        let past = entries.filter { $0.time <= now }
        let chosenAhead = Array(ahead.prefix(cap))
        let chosenPast = Array(past.suffix(max(cap - chosenAhead.count, 0)))
        let chosenIDs = Set((chosenAhead + chosenPast).map(\.id))
        let visible = entries.filter { chosenIDs.contains($0.id) } // already time-ordered
        let rows = visible.map { entry -> Row in
            let mark: ParentingOverdueMark
            if case .arrangement(let item) = entry.content { mark = .mark(for: item, now: now, calendar: calendar) } else { mark = .none }
            return Row(id: entry.id, time: entry.time, content: entry.content, mark: mark, sameTimeConflict: entry.sameTimeConflict)
        }
        return ParentingTodayHome(
            subtitle: subtitle(planned: timeline.summary.today, done: timeline.summary.done), rows: rows,
            nowIndex: insertionIndex(of: now, in: rows.map(\.time)), hiddenTodayCount: entries.count - rows.count,
            upcomingCount: timeline.upcoming.count, overdueCount: timeline.overdue.count, pendingCount: timeline.pending.count)
    }

    /// Where the "now" line goes in a time-ordered list: before the first entry that is later than `now`.
    public static func insertionIndex(of now: Date, in times: [Date]) -> Int {
        times.firstIndex { $0 > now } ?? times.count
    }
}

/// One-tap complete followed by a short undo window. The completion is already saved (ledger acknowledged) when this exists;
/// undo is a second, separate saved command (`reopenArrangement`), never a rollback of the first.
public struct ParentingCompletionUndo: Equatable, Sendable {
    public static let window: TimeInterval = 3
    public let arrangementID: UUID
    public let recordID: UUID
    public let expiresAt: Date

    /// Nil unless the arrangement is currently completed by this record.
    public static func make(workspace: ParentingWorkspace, arrangementID: UUID, recordID: UUID, now: Date) -> ParentingCompletionUndo? {
        guard workspace.arrangements.first(where: { $0.id == arrangementID })?.state == .completed,
              workspace.records.contains(where: { $0.id == recordID && $0.arrangementID == arrangementID }) else { return nil }
        return .init(arrangementID: arrangementID, recordID: recordID, expiresAt: now.addingTimeInterval(window))
    }
    public func isActive(at now: Date) -> Bool { now < expiresAt }
}
