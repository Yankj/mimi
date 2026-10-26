import Foundation

/// Read-only projections behind the 成长 tab visuals (v0.6 S4). Everything here is a pure function of the ledger, a clock and a calendar:
/// no storage, no UIKit/SwiftUI, no writes. It counts what the parent wrote down; it never scores or assesses a child.
public enum ParentingGrowthInsights {
    /// The density strip covers the last seven calendar days including today.
    public static let weekLength = 7
    /// Record-count marks on a goal's track (records linked to the goal). Counts of entries, not a developmental scale.
    public static let goalMarks = [1, 5, 10, 20]
    /// A goal with no linked record for more than this many days reads as "quiet".
    public static let quietAfterDays = 7
    /// Same threshold as the Today/Growth start-action resolver (#132): enough observations to offer naming a goal.
    public static let goalSuggestionThreshold = 5
    /// Without a record for this many calendar days the start card asks for a new observation.
    public static let observationGapDays = 2
    /// Record-count milestones on the timeline, besides the very first record.
    public static let recordMilestones = [10, 25, 50, 100]
    /// Children cycle through this many accent slots (avatar ring, dots, progress ring).
    public static let accentSlotCount = 5

    /// The requested child if it exists, else the earliest-created one.
    public static func scopedChild(in workspace: ParentingWorkspace, preferred: UUID?) -> ParentingChild? {
        if let preferred, let match = workspace.children.first(where: { $0.id == preferred }) { return match }
        return workspace.children.min { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
    }

    /// Stable accent slot for a child: its position in creation order, wrapped. Nil child (deleted) falls back to slot 0.
    public static func accentSlot(for childID: UUID?, in workspace: ParentingWorkspace) -> Int {
        guard let childID else { return 0 }
        let ordered = workspace.children.sorted { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
        guard let index = ordered.firstIndex(where: { $0.id == childID }) else { return 0 }
        return index % accentSlotCount
    }

    static func dayDistance(from earlier: Date, to later: Date, calendar: Calendar) -> Int {
        let a = calendar.startOfDay(for: earlier), b = calendar.startOfDay(for: later)
        return max(0, calendar.dateComponents([.day], from: a, to: b).day ?? 0)
    }
}

// MARK: - Week density

/// How many things were written down on each of the last seven days: the bars of the 本周 strip.
public struct ParentingWeekDensity: Equatable, Sendable {
    public struct Day: Equatable, Sendable, Identifiable {
        public var id: Date { date }
        public let date: Date
        public let observations: Int
        public let completions: Int
        public let isToday: Bool
        public var total: Int { observations + completions }
    }
    /// Oldest first; the last element is today.
    public let days: [Day]
    public var total: Int { days.reduce(0) { $0 + $1.total } }
    public var activeDays: Int { days.filter { $0.total > 0 }.count }
    public var peak: Int { days.map(\.total).max() ?? 0 }

    /// Bar height as a share of the busiest day. An empty day is 0; a non-empty day never drops below `minimumVisible` so one record stays visible.
    public func level(of day: Day, minimumVisible: Double = 0.18) -> Double {
        guard day.total > 0, peak > 0 else { return 0 }
        return min(1, max(minimumVisible, Double(day.total) / Double(peak)))
    }

    public static func build(workspace: ParentingWorkspace, childID: UUID?, now: Date,
                             calendar: Calendar = .autoupdatingCurrent) -> ParentingWeekDensity {
        let today = calendar.startOfDay(for: now)
        var counts: [Date: (obs: Int, done: Int)] = [:]
        for record in workspace.records where childID == nil || record.childID == childID {
            let day = calendar.startOfDay(for: record.createdAt)
            var entry = counts[day] ?? (0, 0)
            if record.kind == .observation { entry.obs += 1 } else { entry.done += 1 }
            counts[day] = entry
        }
        let days: [Day] = (0..<ParentingGrowthInsights.weekLength).reversed().compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            let day = calendar.startOfDay(for: date)
            let entry = counts[day] ?? (0, 0)
            return Day(date: day, observations: entry.obs, completions: entry.done, isToday: offset == 0)
        }
        return ParentingWeekDensity(days: days)
    }
}

// MARK: - Journey (day-grouped timeline with milestones)

public enum ParentingGrowthMilestone: Equatable, Sendable {
    case firstRecord
    case recordCount(Int)
}

/// One calendar day on the growth timeline, newest item first.
public struct ParentingGrowthDay: Equatable, Sendable, Identifiable {
    public enum Item: Equatable, Sendable, Identifiable {
        case record(ParentingRecord, milestone: ParentingGrowthMilestone?)
        case goalStarted(ParentingGoal)
        public var id: UUID {
            switch self { case .record(let record, _): return record.id; case .goalStarted(let goal): return goal.id }
        }
        public var time: Date {
            switch self { case .record(let record, _): return record.createdAt; case .goalStarted(let goal): return goal.createdAt }
        }
        public var childID: UUID {
            switch self { case .record(let record, _): return record.childID; case .goalStarted(let goal): return goal.childID }
        }
    }
    public let day: Date
    public let items: [Item]
    public var id: Date { day }
}

public struct ParentingGrowthJourney: Equatable, Sendable {
    /// Newest day first.
    public let days: [ParentingGrowthDay]
    public var isEmpty: Bool { days.isEmpty }
    public var recordCount: Int {
        days.reduce(0) { total, day in total + day.items.filter { if case .record = $0 { return true } else { return false } }.count }
    }

    /// - childID: nil lists every child; milestones are always counted per child.
    /// - goalID: keep only records linked to this goal (and the goal's own "started" node).
    /// - Goal-started nodes are listed only for the unfiltered kind, so a filtered list contains records only.
    public static func build(workspace: ParentingWorkspace, childID: UUID? = nil, filter: ParentingGrowthTimeline.Filter = .all,
                             goalID: UUID? = nil, calendar: Calendar = .autoupdatingCurrent) -> ParentingGrowthJourney {
        var milestones: [UUID: ParentingGrowthMilestone] = [:]
        for child in workspace.children {
            let ordered = workspace.records.filter { $0.childID == child.id }
                .sorted { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
            for (index, record) in ordered.enumerated() {
                let n = index + 1
                if n == 1 { milestones[record.id] = .firstRecord }
                else if ParentingGrowthInsights.recordMilestones.contains(n) { milestones[record.id] = .recordCount(n) }
            }
        }
        var items: [ParentingGrowthDay.Item] = workspace.records.filter {
            (childID == nil || $0.childID == childID) && (goalID == nil || $0.goalID == goalID)
                && (filter == .all || (filter == .observation ? $0.kind == .observation : $0.kind == .completion))
        }.map { .record($0, milestone: milestones[$0.id]) }
        if filter == .all {
            items += workspace.goals.filter { (childID == nil || $0.childID == childID) && (goalID == nil || $0.id == goalID) }
                .map { .goalStarted($0) }
        }
        items.sort { ($0.time, $0.id.uuidString) > ($1.time, $1.id.uuidString) }
        let grouped = Dictionary(grouping: items) { calendar.startOfDay(for: $0.time) }
        return ParentingGrowthJourney(days: grouped.keys.sorted(by: >).map { ParentingGrowthDay(day: $0, items: grouped[$0] ?? []) })
    }
}

// MARK: - Goal progress

/// What has been written against one goal. The ring is "finished arrangements / (finished + still open)"; with no arrangements it is absent.
public struct ParentingGoalProgress: Equatable, Sendable, Identifiable {
    public enum Activity: Equatable, Sendable { case notStarted, recent, quiet }
    public struct Mark: Equatable, Sendable, Identifiable {
        public let threshold: Int
        public let reached: Bool
        public var id: Int { threshold }
    }
    public let goal: ParentingGoal
    public let observationCount: Int
    public let completionCount: Int
    /// Draft or planned arrangements linked to the goal.
    public let openArrangements: Int
    public let doneArrangements: Int
    public let lastActivity: Date?
    public let daysSinceActivity: Int?
    /// The earliest planned arrangement still ahead.
    public let nextDue: Date?
    public let marks: [Mark]

    public var id: UUID { goal.id }
    public var recordCount: Int { observationCount + completionCount }
    public var fraction: Double? {
        let total = openArrangements + doneArrangements
        return total > 0 ? Double(doneArrangements) / Double(total) : nil
    }
    public var reachedMarks: Int { marks.filter(\.reached).count }
    public var nextMark: Int? { marks.first { !$0.reached }?.threshold }
    public var activity: Activity {
        guard let days = daysSinceActivity else { return .notStarted }
        return days > ParentingGrowthInsights.quietAfterDays ? .quiet : .recent
    }

    /// Goals of one child in creation order.
    public static func build(workspace: ParentingWorkspace, childID: UUID, now: Date,
                             calendar: Calendar = .autoupdatingCurrent) -> [ParentingGoalProgress] {
        workspace.goals.filter { $0.childID == childID }
            .sorted { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
            .map { goal in
                let records = workspace.records.filter { $0.goalID == goal.id }
                let linked = workspace.arrangements.filter { $0.goalID == goal.id }
                let last = records.map(\.createdAt).max()
                let due = linked.filter { $0.state == .planned && $0.dueAt >= now }.map(\.dueAt).min()
                return ParentingGoalProgress(
                    goal: goal,
                    observationCount: records.filter { $0.kind == .observation }.count,
                    completionCount: records.filter { $0.kind == .completion }.count,
                    openArrangements: linked.filter { $0.state == .draft || $0.state == .planned }.count,
                    doneArrangements: linked.filter { $0.state == .completed }.count,
                    lastActivity: last,
                    daysSinceActivity: last.map { ParentingGrowthInsights.dayDistance(from: $0, to: now, calendar: calendar) },
                    nextDue: due,
                    marks: ParentingGrowthInsights.goalMarks.map { Mark(threshold: $0, reached: records.count >= $0) })
            }
    }
}

// MARK: - The one start action

/// Exactly one recommended thing for the top of the 成长 tab. It only recommends; the parent decides and confirms in the usual sheets.
/// The thresholds match `ParentingStartAction.resolve(.growth, …)` (#132); once that lands this resolver can delegate to it and keep
/// only the per-goal refinement (`recordForGoal`).
public struct ParentingGrowthStart: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        /// No child yet.
        case addChild
        /// A child, no records and no goals: write the first small observation.
        case firstObservation
        /// A goal that has no record yet, or has been quiet: write something against it.
        case recordForGoal(goalID: UUID)
        /// At least five observations and no goal: offer to name one.
        case makeGoal(observationCount: Int)
        /// The last record is two or more days old.
        case recordObservation
        /// Nothing is due: look back at the week.
        case reviewWeek
    }
    public let kind: Kind
    public let childID: UUID?

    /// Order of rules (first match wins):
    /// 1. no child → addChild
    /// 2. no records → recordForGoal(oldest goal) if any goal exists, else firstObservation
    /// 3. ≥ 5 observations and no goal → makeGoal
    /// 4. something was written today → reviewWeek (no nagging right after a record)
    /// 5. a goal with no record yet, else the goal quiet the longest → recordForGoal
    /// 6. last record ≥ 2 days ago → recordObservation
    /// 7. otherwise → reviewWeek
    public static func choose(workspace: ParentingWorkspace, childID: UUID?, now: Date,
                              calendar: Calendar = .autoupdatingCurrent) -> ParentingGrowthStart {
        guard let child = ParentingGrowthInsights.scopedChild(in: workspace, preferred: childID) else {
            return ParentingGrowthStart(kind: .addChild, childID: nil)
        }
        let records = workspace.records.filter { $0.childID == child.id }
        let goals = ParentingGoalProgress.build(workspace: workspace, childID: child.id, now: now, calendar: calendar)
        func start(_ kind: Kind) -> ParentingGrowthStart { ParentingGrowthStart(kind: kind, childID: child.id) }

        guard let lastRecord = records.map(\.createdAt).max() else {
            if let first = goals.first { return start(.recordForGoal(goalID: first.goal.id)) }
            return start(.firstObservation)
        }
        let observations = records.filter { $0.kind == .observation }.count
        if goals.isEmpty, observations >= ParentingGrowthInsights.goalSuggestionThreshold {
            return start(.makeGoal(observationCount: observations))
        }
        let sinceLast = ParentingGrowthInsights.dayDistance(from: lastRecord, to: now, calendar: calendar)
        if sinceLast == 0 { return start(.reviewWeek) }
        if let needy = goals.filter({ $0.activity != .recent }).min(by: { a, b in
            let ka = (a.activity == .notStarted ? 0 : 1, -(a.daysSinceActivity ?? 0), a.goal.createdAt, a.goal.id.uuidString)
            let kb = (b.activity == .notStarted ? 0 : 1, -(b.daysSinceActivity ?? 0), b.goal.createdAt, b.goal.id.uuidString)
            return ka < kb
        }) {
            return start(.recordForGoal(goalID: needy.goal.id))
        }
        if sinceLast >= ParentingGrowthInsights.observationGapDays { return start(.recordObservation) }
        return start(.reviewWeek)
    }
}
