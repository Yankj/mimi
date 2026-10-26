import Foundation

/// Read-only projection of the local ledger for the Today page. It never writes and never invents an item.
public struct ParentingTodayTimeline: Equatable, Sendable {
    public enum Content: Equatable, Sendable {
        case arrangement(ParentingArrangement)
        case observation(ParentingRecord)
    }
    public struct Entry: Equatable, Sendable, Identifiable {
        public let id: UUID
        public let time: Date
        public let content: Content
        /// True when another confirmed arrangement starts in the same minute. Durations are unknown, so this is only a prompt to check.
        public let sameTimeConflict: Bool
        public let isPast: Bool
    }
    public struct Summary: Equatable, Sendable {
        public let pending: Int
        public let today: Int
        public let done: Int
        public let next: ParentingArrangement?
    }

    /// Drafts waiting for the parent, on any day.
    public let pending: [ParentingArrangement]
    /// Today's confirmed or finished arrangements and today's observations, in time order.
    public let entries: [Entry]
    /// Confirmed arrangements from earlier days that were never marked done.
    public let overdue: [ParentingArrangement]
    /// Confirmed arrangements from tomorrow on.
    public let upcoming: [ParentingArrangement]
    /// The "now" marker sits before this entry index.
    public let nowIndex: Int
    public let summary: Summary

    public static func build(workspace: ParentingWorkspace, childID: UUID? = nil, now: Date, calendar: Calendar = .autoupdatingCurrent) -> ParentingTodayTimeline {
        let scoped = workspace.arrangements.filter { childID == nil || $0.childID == childID }
        let startOfToday = calendar.startOfDay(for: now)
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: startOfToday) ?? startOfToday
        func order(_ a: ParentingArrangement, _ b: ParentingArrangement) -> Bool { (a.dueAt, a.id.uuidString) < (b.dueAt, b.id.uuidString) }

        let pending = scoped.filter { $0.state == .draft }.sorted(by: order)
        let overdue = scoped.filter { $0.state == .planned && $0.dueAt < startOfToday }.sorted(by: order)
        let upcoming = scoped.filter { $0.state == .planned && $0.dueAt >= tomorrow }.sorted(by: order)
        let todays = scoped.filter { [.planned, .completed].contains($0.state) && calendar.isDate($0.dueAt, inSameDayAs: now) }.sorted(by: order)

        var minutes: [Double: Int] = [:]
        for item in todays where item.state == .planned { minutes[floor(item.dueAt.timeIntervalSince1970 / 60), default: 0] += 1 }
        var entries = todays.map { item in
            Entry(id: item.id, time: item.dueAt, content: .arrangement(item),
                  sameTimeConflict: item.state == .planned && (minutes[floor(item.dueAt.timeIntervalSince1970 / 60)] ?? 0) > 1,
                  isPast: item.dueAt <= now)
        }
        let observations = workspace.records.filter {
            $0.kind == .observation && (childID == nil || $0.childID == childID) && calendar.isDate($0.createdAt, inSameDayAs: now)
        }
        entries += observations.map { Entry(id: $0.id, time: $0.createdAt, content: .observation($0), sameTimeConflict: false, isPast: $0.createdAt <= now) }
        entries.sort { ($0.time, $0.id.uuidString) < ($1.time, $1.id.uuidString) }

        let nowIndex = entries.filter { $0.time <= now }.count
        let plannedToday = todays.filter { $0.state == .planned }
        let next = plannedToday.first { $0.dueAt > now } ?? upcoming.first
        return ParentingTodayTimeline(
            pending: pending, entries: entries, overdue: overdue, upcoming: upcoming, nowIndex: nowIndex,
            summary: .init(pending: pending.count, today: plannedToday.count, done: todays.filter { $0.state == .completed }.count, next: next))
    }
}

/// Growth page timeline: observations and parent-marked completions grouped by the day they were written.
public struct ParentingGrowthTimeline: Equatable, Sendable {
    public enum Filter: String, Sendable, CaseIterable { case all, observation, completion }
    public struct Day: Equatable, Sendable, Identifiable {
        public var id: Date { day }
        public let day: Date
        public let records: [ParentingRecord]
    }
    public let days: [Day]
    public var isEmpty: Bool { days.isEmpty }

    public static func build(workspace: ParentingWorkspace, childID: UUID? = nil, filter: Filter = .all,
                             calendar: Calendar = .autoupdatingCurrent) -> ParentingGrowthTimeline {
        let records = workspace.records.filter {
            (childID == nil || $0.childID == childID) &&
            (filter == .all || (filter == .observation ? $0.kind == .observation : $0.kind == .completion))
        }.sorted { ($0.createdAt, $0.id.uuidString) > ($1.createdAt, $1.id.uuidString) }
        let grouped = Dictionary(grouping: records) { calendar.startOfDay(for: $0.createdAt) }
        return .init(days: grouped.keys.sorted(by: >).map { .init(day: $0, records: grouped[$0] ?? []) })
    }
}
