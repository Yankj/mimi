import Foundation

/// The three home tabs. (The view layer has its own private tab enum; this one is only for start-action resolution.)
public enum ParentingStartTab: String, Sendable, CaseIterable { case today, growth, family }

/// Facts that are not part of the ledger but change which start action is recommended.
/// The UI persists them (preferences) and passes them in; the resolver never reads storage or the clock.
public struct ParentingStartContext: Equatable, Sendable {
    /// When the parent last finished a weekly review. Nil means never.
    public var lastWeeklyReviewAt: Date?
    /// Reminder permission and the parent's toggle are both on.
    public var remindersEnabled: Bool
    /// The parent tapped "skip" on the optional reminders step of the launch checklist.
    public var remindersStepSkipped: Bool
    /// Scope every tab to one child. Nil uses the earliest-created child.
    public var childID: UUID?
    public init(lastWeeklyReviewAt: Date? = nil, remindersEnabled: Bool = false, remindersStepSkipped: Bool = false, childID: UUID? = nil) {
        self.lastWeeklyReviewAt = lastWeeklyReviewAt; self.remindersEnabled = remindersEnabled
        self.remindersStepSkipped = remindersStepSkipped; self.childID = childID
    }
}

/// 「启航 n/3」 for the family tab. Steps one and two are required; step three is optional but is offered once.
public struct ParentingLaunchChecklist: Equatable, Sendable {
    public enum Step: String, Sendable, CaseIterable { case addChild, recordFirstThing, enableReminders }
    public let done: Set<Step>
    public let skipped: Set<Step>
    public var completedCount: Int { done.count }
    public static let total = 3
    /// Next step in order that is neither done nor skipped.
    public var nextStep: Step? { Step.allCases.first { !done.contains($0) && !skipped.contains($0) } }
    /// The card disappears when every step is done or (for the optional step only) skipped.
    public var isFinished: Bool { nextStep == nil }
}

public enum ParentingWeeklyReviewReason: Equatable, Sendable { case sundayEvening, sevenDaysSinceLastReview }

/// Exactly one recommended start action per tab. Pure and deterministic: ledger + clock + calendar in, action out.
/// It only recommends; it never writes the ledger, never completes anything, never sends anything.
public enum ParentingStartAction: Equatable, Sendable {
    // 今天
    /// No child yet: ask for a nickname inline.
    case startWithNickname
    /// Drafts wait for the parent: one tap confirms all of them.
    case confirmAll(arrangementIDs: [UUID])
    /// Confirmed items from earlier days were never marked done. The parent decides; nothing is auto-completed.
    case handleOverdue(arrangementIDs: [UUID])
    /// The next unfinished arrangement today with a one-tap complete. `hasPassed` means its time is already past today.
    case nextWithComplete(arrangementID: UUID, dueAt: Date, hasPassed: Bool)
    /// 18:00–21:00 and nothing recorded yet today.
    case recordTodaySmallThing(childID: UUID)
    /// Nothing else applies: show a sentence the parent can say or edit.
    case examplePrompt(text: String)
    // 成长
    case weeklyReview(reason: ParentingWeeklyReviewReason)
    /// At least five observations and not a single goal: offer to name one.
    case giveObservationsAGoal(childID: UUID, observationCount: Int)
    case recordObservation(childID: UUID)
    // 我们家
    case launchChecklist(ParentingLaunchChecklist)
    /// Family tab after the checklist is finished: no start action, only the steady profile.
    case none

    public static let eveningStartHour = 18
    public static let eveningEndHour = 21 // exclusive
    public static let growthGoalObservationThreshold = 5
    public static let weeklyReviewIntervalDays = 7

    public static let examplePrompts: [String] = [
        "明天早上8点带宝宝打疫苗",
        "今天下午宝宝第一次自己拿勺子吃饭",
        "周六上午10点带宝宝去公园",
        "晚上8点半给宝宝读绘本",
        "宝宝今天自己扶着沙发走了几步"
    ]

    public static func resolve(_ tab: ParentingStartTab, workspace: ParentingWorkspace, now: Date,
                               calendar: Calendar = .autoupdatingCurrent, context: ParentingStartContext = .init()) -> ParentingStartAction {
        switch tab {
        case .today: return resolveToday(workspace: workspace, now: now, calendar: calendar, context: context)
        case .growth: return resolveGrowth(workspace: workspace, now: now, calendar: calendar, context: context)
        case .family: return resolveFamily(workspace: workspace, context: context)
        }
    }

    /// The child every tab is scoped to: the requested one if it exists, else the earliest-created.
    public static func activeChild(in workspace: ParentingWorkspace, preferred: UUID?) -> ParentingChild? {
        if let preferred, let match = workspace.children.first(where: { $0.id == preferred }) { return match }
        return workspace.children.min { ($0.createdAt, $0.id.uuidString) < ($1.createdAt, $1.id.uuidString) }
    }

    // MARK: 今天
    private static func resolveToday(workspace: ParentingWorkspace, now: Date, calendar: Calendar, context: ParentingStartContext) -> ParentingStartAction {
        guard let child = activeChild(in: workspace, preferred: context.childID) else { return .startWithNickname }
        let timeline = ParentingTodayTimeline.build(workspace: workspace, childID: child.id, now: now, calendar: calendar)
        if !timeline.pending.isEmpty { return .confirmAll(arrangementIDs: timeline.pending.map(\.id)) }
        if !timeline.overdue.isEmpty { return .handleOverdue(arrangementIDs: timeline.overdue.map(\.id)) }
        let planned = timeline.entries.compactMap { entry -> ParentingArrangement? in
            if case .arrangement(let item) = entry.content, item.state == .planned { return item }
            return nil
        }
        // Entries are time-ordered. Prefer what is still ahead today; otherwise the most recent one that has passed.
        if let ahead = planned.first(where: { $0.dueAt > now }) {
            return .nextWithComplete(arrangementID: ahead.id, dueAt: ahead.dueAt, hasPassed: false)
        }
        if let passed = planned.last { return .nextWithComplete(arrangementID: passed.id, dueAt: passed.dueAt, hasPassed: true) }
        let hour = calendar.component(.hour, from: now)
        let wroteToday = timeline.entries.contains { if case .observation = $0.content { return true } else { return false } }
        if (eveningStartHour..<eveningEndHour).contains(hour), !wroteToday { return .recordTodaySmallThing(childID: child.id) }
        return .examplePrompt(text: examplePrompt(now: now, calendar: calendar))
    }

    /// Rotates by day so the sentence is stable within a day and not random.
    public static func examplePrompt(now: Date, calendar: Calendar = .autoupdatingCurrent) -> String {
        let day = calendar.ordinality(of: .day, in: .era, for: now) ?? 0
        return examplePrompts[((day % examplePrompts.count) + examplePrompts.count) % examplePrompts.count]
    }

    // MARK: 成长
    private static func resolveGrowth(workspace: ParentingWorkspace, now: Date, calendar: Calendar, context: ParentingStartContext) -> ParentingStartAction {
        guard let child = activeChild(in: workspace, preferred: context.childID) else {
            return .examplePrompt(text: examplePrompt(now: now, calendar: calendar))
        }
        let records = workspace.records.filter { $0.childID == child.id }
        guard !records.isEmpty else { return .examplePrompt(text: examplePrompt(now: now, calendar: calendar)) }

        if let reason = weeklyReviewReason(records: records, now: now, calendar: calendar, lastReviewAt: context.lastWeeklyReviewAt) {
            return .weeklyReview(reason: reason)
        }
        let observations = records.filter { $0.kind == .observation }.count
        let hasGoal = workspace.goals.contains { $0.childID == child.id }
        if observations >= growthGoalObservationThreshold, !hasGoal {
            return .giveObservationsAGoal(childID: child.id, observationCount: observations)
        }
        return .recordObservation(childID: child.id)
    }

    /// Sunday from 18:00 (if not reviewed yet today), or at least seven days since the last review.
    /// With no review on file the clock starts at the child's earliest record. A review needs something from the last 7 days to look at.
    static func weeklyReviewReason(records: [ParentingRecord], now: Date, calendar: Calendar, lastReviewAt: Date?) -> ParentingWeeklyReviewReason? {
        guard let weekAgo = calendar.date(byAdding: .day, value: -weeklyReviewIntervalDays, to: now),
              records.contains(where: { $0.createdAt > weekAgo && $0.createdAt <= now }) else { return nil }
        let reviewedToday = lastReviewAt.map { calendar.isDate($0, inSameDayAs: now) } ?? false
        let isSunday = calendar.component(.weekday, from: now) == 1
        if isSunday, calendar.component(.hour, from: now) >= eveningStartHour, !reviewedToday { return .sundayEvening }
        let baseline = lastReviewAt ?? records.map(\.createdAt).min()
        if let baseline, baseline <= weekAgo { return .sevenDaysSinceLastReview }
        return nil
    }

    // MARK: 我们家
    public static func launchChecklist(workspace: ParentingWorkspace, context: ParentingStartContext = .init()) -> ParentingLaunchChecklist {
        var done = Set<ParentingLaunchChecklist.Step>(), skipped = Set<ParentingLaunchChecklist.Step>()
        if !workspace.children.isEmpty { done.insert(.addChild) }
        if !workspace.arrangements.isEmpty || !workspace.records.isEmpty { done.insert(.recordFirstThing) }
        if context.remindersEnabled { done.insert(.enableReminders) } else if context.remindersStepSkipped { skipped.insert(.enableReminders) }
        return .init(done: done, skipped: skipped)
    }

    private static func resolveFamily(workspace: ParentingWorkspace, context: ParentingStartContext) -> ParentingStartAction {
        let checklist = launchChecklist(workspace: workspace, context: context)
        return checklist.isFinished ? .none : .launchChecklist(checklist)
    }
}
