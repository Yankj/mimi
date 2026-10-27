import Foundation

/// What the Today page shows: one hero (the recommended start action) and a short rail of what comes next.
/// Read-only and deterministic (ledger + clock + calendar in, deck out). It never writes, completes or sends anything,
/// and it contains no user-facing text: the view maps cases to localized strings.
public struct ParentingTodayDeck: Equatable, Sendable {
    public struct RailItem: Equatable, Sendable, Identifiable {
        public enum When: Equatable, Sendable { case laterToday, anotherDay }
        public let id: UUID
        public let title: String
        public let dueAt: Date
        public let childID: UUID
        public let when: When
    }

    /// The rail holds at most this many items.
    public static let railLimit = 3

    /// The child the hero and rail are scoped to (`ParentingStartAction.activeChild`). Nil when there is no child yet.
    public let childID: UUID?
    /// The single recommended start action. Exactly what `ParentingStartAction.resolve(.today, …)` returns.
    public let hero: ParentingStartAction
    /// The arrangement behind `.nextWithComplete`; nil for every other hero.
    public let heroArrangement: ParentingArrangement?
    /// Confirmed arrangements still ahead of `now` (today first, then later days), earliest first, never the hero's own.
    public let rail: [RailItem]
    /// Open (draft or confirmed) arrangements of this child that neither the hero card nor the rail shows.
    public let hiddenOpenCount: Int

    public static func build(workspace: ParentingWorkspace, now: Date, calendar: Calendar = .autoupdatingCurrent,
                             context: ParentingStartContext = .init(), railLimit: Int = railLimit) -> ParentingTodayDeck {
        let hero = ParentingStartAction.resolve(.today, workspace: workspace, now: now, calendar: calendar, context: context)
        guard let child = ParentingStartAction.activeChild(in: workspace, preferred: context.childID) else {
            return .init(childID: nil, hero: hero, heroArrangement: nil, rail: [], hiddenOpenCount: 0)
        }
        var heroArrangement: ParentingArrangement?
        if case .nextWithComplete(let id, _, _) = hero { heroArrangement = workspace.arrangements.first { $0.id == id } }

        let ahead = workspace.arrangements
            .filter { $0.childID == child.id && $0.state == .planned && $0.dueAt > now && $0.id != heroArrangement?.id }
            .sorted { ($0.dueAt, $0.id.uuidString) < ($1.dueAt, $1.id.uuidString) }
        let rail = ahead.prefix(max(railLimit, 0)).map { item in
            RailItem(id: item.id, title: item.title, dueAt: item.dueAt, childID: item.childID,
                     when: calendar.isDate(item.dueAt, inSameDayAs: now) ? .laterToday : .anotherDay)
        }
        let shown = Set(rail.map(\.id) + [heroArrangement?.id].compactMap { $0 })
        let open = workspace.arrangements.filter { $0.childID == child.id && ($0.state == .draft || $0.state == .planned) }
        return .init(childID: child.id, hero: hero, heroArrangement: heroArrangement, rail: Array(rail),
                     hiddenOpenCount: open.filter { !shown.contains($0.id) }.count)
    }
}

extension ParentingCompletionUndo {
    /// Whole seconds left, rounded up, never negative. 3 right after the save, 0 once the window has passed.
    public func remainingSeconds(at now: Date) -> Int {
        max(0, Int(expiresAt.timeIntervalSince(now).rounded(.up)))
    }
    /// How long a timer should wait before dropping the undo affordance. Absolute (`expiresAt`), so leaving the
    /// screen and coming back never extends the window.
    public func timeUntilExpiry(from now: Date) -> TimeInterval { max(0, expiresAt.timeIntervalSince(now)) }
}

/// How long ago the parent last marked a weekly review done. The date is a UI-layer preference, not ledger data.
public enum ParentingWeeklyReviewStatus: Equatable, Sendable {
    case never
    case today
    case daysAgo(Int)
    /// A stored date in the future (clock change): treated as today, never as a negative number of days.
    public static func make(lastAt: Date?, now: Date, calendar: Calendar = .autoupdatingCurrent) -> ParentingWeeklyReviewStatus {
        guard let lastAt else { return .never }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: lastAt), to: calendar.startOfDay(for: now)).day ?? 0
        return days <= 0 ? .today : .daysAgo(days)
    }
}

/// 「上次周复盘时间」. Lives beside the encrypted ledger in UserDefaults, one key per signed-in subject, like the
/// reminder settings. It only feeds `ParentingStartContext.lastWeeklyReviewAt`; it is not part of the ledger.
public struct ParentingWeeklyReviewStore: @unchecked Sendable {
    public static let keyPrefix = "mimi.parenting.weeklyReview.lastAt.v1."
    private let defaults: UserDefaults
    public let key: String
    public init(defaults: UserDefaults = .standard, subjectID: UUID) {
        self.defaults = defaults
        key = Self.keyPrefix + subjectID.uuidString.lowercased()
    }
    public func load() -> Date? {
        guard defaults.object(forKey: key) != nil else { return nil }
        let value = defaults.double(forKey: key)
        return value.isFinite && value > 0 ? Date(timeIntervalSince1970: value) : nil
    }
    public func save(_ date: Date) { defaults.set(date.timeIntervalSince1970, forKey: key) }
    public func erase() { defaults.removeObject(forKey: key) }
}

/// Re-entry guard for the Today hero's one-tap 「完成」. It is a plain value so the rule can be tested without a view:
/// - only one completion is in flight at a time, and
/// - right after a completion the hero already shows the next arrangement, so taps in the next `cooldown` seconds are ignored
///   (a double tap must complete one item, never two). A failed save does not start the cooldown, so the parent can retry at once.
public struct ParentingOneTapGate: Equatable, Sendable {
    public static let cooldown: TimeInterval = 0.8
    public private(set) var inFlight = false
    public private(set) var lastCompletedAt: Date?
    public init() {}

    /// True when this tap may start a completion. Closes the gate until `finish`.
    public mutating func begin(now: Date) -> Bool {
        guard !inFlight else { return false }
        if let last = lastCompletedAt, now.timeIntervalSince(last) < Self.cooldown, now >= last { return false }
        inFlight = true
        return true
    }

    /// `completedAt` is nil when the save failed or was refused.
    public mutating func finish(completedAt: Date?) {
        inFlight = false
        if let completedAt { lastCompletedAt = completedAt }
    }
}
