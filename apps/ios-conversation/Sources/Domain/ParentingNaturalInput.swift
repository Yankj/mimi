import Foundation

/// 录入 / 规划 / 复盘. A back-stage label only: it is never a navigation gate and never shown as a form to fill.
public enum ParentingIntentMode: String, Sendable, CaseIterable {
    case capture, plan, review
    public var title: String {
        switch self { case .capture: "录入"; case .plan: "规划"; case .review: "复盘" }
    }
}

public enum ParentingIntentKind: String, Sendable, CaseIterable {
    case newArrangement, reschedule, cancel, observation, viewToday, viewPlan, reviewGrowth, unknown
    public var mode: ParentingIntentMode? {
        switch self {
        case .newArrangement, .reschedule, .cancel, .observation: .capture
        case .viewToday, .viewPlan: .plan
        case .reviewGrowth: .review
        case .unknown: nil
        }
    }
    /// Only these kinds can ever produce a ledger command, and only after the parent confirms a card.
    public var writes: Bool { self == .newArrangement || self == .reschedule || self == .cancel || self == .observation }
    public var title: String {
        switch self {
        case .newArrangement: "加安排"
        case .reschedule: "改期"
        case .cancel: "取消安排"
        case .observation: "记一笔"
        case .viewToday: "看今天"
        case .viewPlan: "看计划"
        case .reviewGrowth: "看成长回顾"
        case .unknown: "还不确定"
        }
    }
}

/// Declaration order is the order the parent is asked: one question at a time, most decisive first.
public enum ParentingMissingField: Int, Sendable, Comparable, CaseIterable {
    case intent, child, target, date, time, title, content
    public static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

public struct ParentingNaturalContext: Sendable {
    public var children: [ParentingChild]
    public var arrangements: [ParentingArrangement]
    public var activeChildID: UUID?
    public init(children: [ParentingChild], arrangements: [ParentingArrangement], activeChildID: UUID? = nil) {
        self.children = children; self.arrangements = arrangements; self.activeChildID = activeChildID
    }
    public init(workspace: ParentingWorkspace, activeChildID: UUID? = nil) {
        self.init(children: workspace.children, arrangements: workspace.arrangements, activeChildID: activeChildID)
    }
}

/// Answers the parent gave to a follow-up. They are re-applied on every re-read so nothing already settled is asked twice.
public struct ParentingNaturalOverrides: Sendable, Equatable {
    public var kind: ParentingIntentKind?
    public var childID: UUID?
    public var targetID: UUID?
    public var dueAt: Date?
    public init(kind: ParentingIntentKind? = nil, childID: UUID? = nil, targetID: UUID? = nil, dueAt: Date? = nil) {
        self.kind = kind; self.childID = childID; self.targetID = targetID; self.dueAt = dueAt
    }
}

public enum ParentingNaturalAnswer: Sendable, Equatable {
    case intent(ParentingIntentKind)
    case child(UUID)
    case target(UUID)
    case dueAt(Date)
    /// Free text for a missing date, time, title or content; it is read together with what was said before.
    case text(String)
}

public struct ParentingFollowUp: Equatable, Sendable {
    public enum Label: Equatable, Sendable {
        case key(String)          // a resource key shown through ui(...)
        case verbatim(String)     // a name or title typed by the parent
        case dateTime(Date)       // formatted by the view in the parent's locale
    }
    public struct Option: Equatable, Sendable, Identifiable {
        public let id: String
        public let label: Label
        public let detail: Date?
        public let answer: ParentingNaturalAnswer
        /// Resource key of a second line under the label (the two big "记下来 / 安排提醒" buttons use it).
        public var subtitle: String? = nil
    }
    public let field: ParentingMissingField
    public let question: String      // resource key
    public let options: [Option]
    public let allowsText: Bool
    public let textHint: String?     // resource key
}

public struct ParentingNaturalProposal: Equatable, Sendable {
    public var sourceText: String
    public var kind: ParentingIntentKind
    public var childID: UUID?
    public var targetID: UUID?
    public var title: String?
    public var dueAt: Date?
    /// True when a reschedule kept the original day because the parent only named a new hour.
    public var inheritedDate = false
    public var observationText: String?
    /// What was assumed about the time when the parent did not say it. The card says so.
    public enum AssumedTime: Equatable, Sendable {
        case none
        case nineOClock        // a day, no clock, no part of the day: 9:00
        case dayPart           // 早上8 上午9 中午12 下午15 晚上19
        case laterThisPart     // today's usual hour has passed: the next half hour of the same part of the day
        case nextDay           // today's time has passed: tomorrow
    }
    /// What was assumed about the day when only a clock or a part of the day was said: today, or tomorrow when today's time has passed.
    public enum AssumedDay: Equatable, Sendable { case none, today, tomorrow }
    public var assumedTime: AssumedTime = .none
    public var assumedDay: AssumedDay = .none
    public var defaultedTime: Bool { assumedTime != .none }
    /// True when the words carry both record and arrangement evidence (or only a weak arrangement hint): ask once, two big buttons.
    public var ambiguousIntent = false
    public var missing: [ParentingMissingField]
    public var overrides: ParentingNaturalOverrides
    var childChoices: [UUID] = []
    var targetChoices: [UUID] = []
    var ambiguousHour: Int?
    var ambiguousDay: Date?

    public var mode: ParentingIntentMode? { kind.mode }
    public var isReadyToConfirm: Bool { missing.isEmpty && kind != .unknown }
}

public enum ParentingNaturalInput {
    public static let maxLength = 3000

    public static func classify(_ rawText: String, context: ParentingNaturalContext, now: Date,
                                calendar: Calendar = .autoupdatingCurrent,
                                overrides: ParentingNaturalOverrides = .init()) -> ParentingNaturalProposal {
        let text = ParentingNaturalLexicon.normalized(rawText)
        var proposal = ParentingNaturalProposal(sourceText: text, kind: .unknown, missing: [], overrides: overrides)
        guard !text.isEmpty, text.count <= maxLength, !text.unicodeScalars.contains(where: { $0.value == 0 }) else {
            proposal.missing = [.intent]; return proposal
        }
        let reading = ParentingNaturalLexicon.read(text, now: now, calendar: calendar)
        var kind = overrides.kind ?? .unknown
        if overrides.kind == nil {
            let verdict = detectVerdict(text, reading: reading, now: now, calendar: calendar)
            kind = verdict.kind; proposal.ambiguousIntent = verdict.ambiguous
        }
        proposal.kind = kind
        switch kind {
        case .unknown: proposal.missing = [.intent]
        case .viewToday, .viewPlan, .reviewGrowth: break
        case .newArrangement: fillArrangement(&proposal, text: text, reading: reading, context: context, now: now, calendar: calendar)
        case .observation: fillObservation(&proposal, text: text, context: context)
        case .reschedule: fillReschedule(&proposal, text: text, context: context, now: now, calendar: calendar)
        case .cancel: fillCancel(&proposal, text: text, context: context, now: now, calendar: calendar)
        }
        proposal.missing = Array(Set(proposal.missing)).sorted()
        return proposal
    }

    // MARK: Intent
    /// What the words stand for. `ambiguous` means "ask once": the kind is `.unknown` and the parent picks 记下来 or 安排提醒.
    ///
    /// Rules, in order: hard intents (review, query, reschedule) first; chit-chat has nothing to save; then evidence.
    /// A bare 今天/今晚 is no evidence for an arrangement (an arrangement needs a future anchor), a 了 / 昨天 / 刚 / 第一次 leans to a record,
    /// no evidence at all defaults to a record, and only conflicting (or very thin arrangement-only) evidence asks.
    static func detectVerdict(_ text: String, reading: ParentingNaturalLexicon.TimeReading, now: Date, calendar: Calendar) -> (kind: ParentingIntentKind, ambiguous: Bool) {
        let lexicon = ParentingNaturalLexicon.self
        func has(_ words: [String]) -> Bool { words.contains { text.contains($0) } }
        if has(lexicon.reviewMarkers) { return (.reviewGrowth, false) }
        if has(lexicon.todayQueryMarkers)
            || (has(lexicon.todayWords) && has(lexicon.queryCues) && !reading.hasClock && !has(lexicon.rescheduleTriggers) && !lexicon.hasCompletedParticle(text)) {
            return (.viewToday, false)
        }
        if has(lexicon.planQueryMarkers) { return (.viewPlan, false) }
        if has(lexicon.rescheduleTriggers) { return (.reschedule, false) }
        if text.range(of: lexicon.cancelPattern, options: .regularExpression) != nil { return (.cancel, false) }

        let hasLetters = text.unicodeScalars.contains { (0x4E00...0x9FFF).contains($0.value) || ("0"..."9").contains(Character($0)) }
        guard hasLetters else { return (.unknown, false) }
        // Only a lead ("记一下"): it is a record, and the card asks what to write.
        if lexicon.contentWithoutLeads(text).isEmpty, lexicon.observationLeads.contains(where: { text.hasPrefix($0) }) { return (.observation, false) }

        let e = lexicon.intentEvidence(text, reading: reading, now: now, calendar: calendar)
        if lexicon.isChitchatOrQuestion(text, evidence: e) { return (.unknown, false) }
        if e.arrangement == 0 { return (.observation, false) }          // no arrangement evidence: a record, also with no evidence at all
        if e.record <= 1 { return e.arrangement >= 2 ? (.newArrangement, false) : (.unknown, true) }
        let d = e.arrangement - e.record
        if d >= 3 { return (.newArrangement, false) }
        if d <= -2 { return (.observation, false) }
        return (.unknown, true)
    }

    static func detect(_ text: String, reading: ParentingNaturalLexicon.TimeReading, now: Date, calendar: Calendar) -> ParentingIntentKind {
        detectVerdict(text, reading: reading, now: now, calendar: calendar).kind
    }

    // MARK: Fields
    /// Children the words point at: a nickname first (several nicknames = several children); otherwise sibling words by creation order.
    private static func mentionedChildren(_ text: String, context: ParentingNaturalContext) -> [ParentingChild] {
        let named = context.children.filter { !$0.name.isEmpty && text.contains($0.name) }
        if !named.isEmpty { return named }
        let ordered = context.children.enumerated()
            .sorted { ($0.element.createdAt, $0.offset) < ($1.element.createdAt, $1.offset) }.map(\.element)
        return ParentingNaturalLexicon.siblingPositions(in: text, childCount: ordered.count).map { ordered[$0] }
    }

    /// Who the words are about. Order: a child answered on the card, a nickname or sibling word in the sentence, the child selected on
    /// the page, the only child. Several mentioned children, or none to go on, are never guessed: one question lists the choices.
    private static func resolveChild(_ p: inout ParentingNaturalProposal, text: String, context: ParentingNaturalContext) {
        if let forced = p.overrides.childID, context.children.contains(where: { $0.id == forced }) { p.childID = forced; return }
        let named = mentionedChildren(text, context: context)
        if named.count == 1 { p.childID = named[0].id; return }
        if named.count > 1 { p.missing.append(.child); p.childChoices = named.map(\.id); return }
        if context.children.count == 1 { p.childID = context.children[0].id; return }
        if let active = context.activeChildID, context.children.contains(where: { $0.id == active }) { p.childID = active; return }
        p.missing.append(.child); p.childChoices = context.children.map(\.id)
    }

    private static func fillArrangement(_ p: inout ParentingNaturalProposal, text: String, reading: ParentingNaturalLexicon.TimeReading,
                                        context: ParentingNaturalContext, now: Date, calendar: Calendar) {
        resolveChild(&p, text: text, context: context)
        var residual = ParentingNaturalLexicon.removing(reading.consumed, from: text)
        for child in context.children { residual = residual.replacingOccurrences(of: child.name, with: " ") }
        residual = ParentingNaturalLexicon.removingSiblingReferences(residual)
        let title = ParentingNaturalLexicon.cleanTitle(residual)
        if title.isEmpty { p.missing.append(.title) } else { p.title = String(title.prefix(160)) }

        let part = ParentingNaturalLexicon.dayPart(in: text)
        let hasAnchor = reading.day != nil || reading.hasClock || part != nil
        let today = calendar.startOfDay(for: now)
        func tomorrow(_ date: Date) -> Date? { calendar.date(byAdding: .day, value: 1, to: date) }

        if let forced = p.overrides.dueAt {
            // The parent picked the half of the day for a clock that came without a date: today, or tomorrow if that moment has passed.
            if reading.day == nil, forced <= now, let next = tomorrow(forced) { p.dueAt = next; p.assumedDay = .tomorrow }
            else { p.dueAt = forced; if reading.day == nil && hasAnchor { p.assumedDay = .today } }
            return
        }
        // Neither a date, a clock nor a part of the day: the day has to be asked.
        guard hasAnchor else { p.missing.append(.date); return }
        let statedDay = reading.day != nil
        let day = reading.day ?? today
        if !statedDay { p.assumedDay = .today }

        // A clock without a half of the day ("2点") is asked as 上午 / 下午, on the day assumed so far.
        if reading.hour == nil, reading.ambiguousHour != nil {
            p.missing.append(.time); p.ambiguousHour = reading.ambiguousHour; p.ambiguousDay = day
            if !statedDay { p.assumedDay = .none }
            return
        }
        if let hour = reading.hour, var date = ParentingNaturalLexicon.date(on: day, hour: hour, minute: reading.minute, calendar: calendar) {
            // A stated clock stays as said (the card warns if it is in the past); only an assumed day moves to tomorrow.
            if !statedDay, date <= now, let next = tomorrow(date) { date = next; p.assumedDay = .tomorrow }
            p.dueAt = date
            return
        }
        // No clock: the usual hour of the part of the day, 9:00 when no part was said.
        let usualHour = part?.hour ?? ParentingNaturalInput.defaultHour
        guard var date = ParentingNaturalLexicon.date(on: day, hour: usualHour, minute: 0, calendar: calendar) else {
            p.missing.append(.time); p.ambiguousDay = day; return
        }
        p.assumedTime = part == nil ? .nineOClock : .dayPart
        if calendar.isDate(day, inSameDayAs: now), date <= now {
            // Today's usual moment has passed: the next half hour while the same part of the day lasts, else tomorrow.
            let minutes = (calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now)) / 30 * 30 + 30
            if let part, minutes < part.windowEnd * 60,
               let later = calendar.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: day) {
                date = later; p.assumedTime = .laterThisPart
            } else if let next = tomorrow(date) {
                date = next
                if statedDay { p.assumedTime = .nextDay } else { p.assumedDay = .tomorrow }
            }
        }
        p.dueAt = date
    }

    /// The hour filled in for an arrangement that has a day but no clock and no part of the day.
    public static let defaultHour = 9

    private static func fillCancel(_ p: inout ParentingNaturalProposal, text: String, context: ParentingNaturalContext, now: Date, calendar: Calendar) {
        let lexicon = ParentingNaturalLexicon.self
        let rest = lexicon.removingCancelWords(text)
        let reading = lexicon.read(rest, now: now, calendar: calendar)
        var pool = context.arrangements.filter { [.draft, .planned].contains($0.state) }
        let named = mentionedChildren(text, context: context)
        if let forced = p.overrides.childID { pool = pool.filter { $0.childID == forced } }
        else if named.count == 1 { pool = pool.filter { $0.childID == named[0].id } }
        var target: ParentingArrangement?
        if let forced = p.overrides.targetID { target = context.arrangements.first { $0.id == forced && [.draft, .planned].contains($0.state) } }
        if target == nil { target = matchTarget(&p, pre: rest, preReading: reading, pool: pool, context: context, calendar: calendar) }
        guard let target else { return }
        p.targetID = target.id; p.childID = target.childID; p.title = target.title; p.dueAt = target.dueAt
    }

    /// The saved arrangement the words point at: the best title overlap (plus the same day); several equally good ones are asked.
    private static func matchTarget(_ p: inout ParentingNaturalProposal, pre: String, preReading: ParentingNaturalLexicon.TimeReading,
                                    pool: [ParentingArrangement], context: ParentingNaturalContext, calendar: Calendar) -> ParentingArrangement? {
        let lexicon = ParentingNaturalLexicon.self
        var residual = lexicon.removing(preReading.consumed, from: pre)
        for child in context.children { residual = residual.replacingOccurrences(of: child.name, with: " ") }
        residual = lexicon.removingSiblingReferences(residual)
        let wanted = lexicon.bigrams(lexicon.cleanTitle(residual))
        let scored: [(ParentingArrangement, Int)] = pool.map { item in
            var name = item.title
            for child in context.children { name = name.replacingOccurrences(of: child.name, with: " ") }
            let overlap = lexicon.bigrams(name).intersection(wanted).count
            let sameDay = preReading.day.map { calendar.isDate($0, inSameDayAs: item.dueAt) } ?? false
            return (item, overlap * 10 + (sameDay ? 5 : 0))
        }.sorted { ($0.1, $1.0.dueAt) > ($1.1, $0.0.dueAt) }
        if let best = scored.first, best.1 >= 10, scored.dropFirst().first.map({ $0.1 < best.1 }) ?? true { return best.0 }
        p.missing.append(.target)
        p.targetChoices = scored.isEmpty ? [] : scored.prefix(4).map { $0.0.id }
        return nil
    }

    private static func fillObservation(_ p: inout ParentingNaturalProposal, text: String, context: ParentingNaturalContext) {
        resolveChild(&p, text: text, context: context)
        let subjectless = ParentingNaturalLexicon.removingLeadingChildSubject(text, names: context.children.map(\.name))
        let body = ParentingNaturalLexicon.stripLead(subjectless)
        if body.isEmpty { p.missing.append(.content) } else { p.observationText = String(body.prefix(8000)) }
    }

    private static func fillReschedule(_ p: inout ParentingNaturalProposal, text: String, context: ParentingNaturalContext, now: Date, calendar: Calendar) {
        let lexicon = ParentingNaturalLexicon.self
        let trigger = lexicon.rescheduleTriggers.compactMap { word in text.range(of: word).map { (word, $0) } }
            .min { $0.1.lowerBound < $1.1.lowerBound }
        let pre = trigger.map { String(text[..<$0.1.lowerBound]) } ?? text
        let post = trigger.map { String(text[$0.1.upperBound...]) } ?? ""
        let preReading = lexicon.read(pre, now: now, calendar: calendar)
        let postReading = lexicon.read(post, now: now, calendar: calendar)
        let named = mentionedChildren(text, context: context)
        var pool = context.arrangements.filter { [.draft, .planned].contains($0.state) }
        if let forced = p.overrides.childID { pool = pool.filter { $0.childID == forced } }
        else if named.count == 1 { pool = pool.filter { $0.childID == named[0].id } }

        var target: ParentingArrangement?
        if let forced = p.overrides.targetID { target = context.arrangements.first { $0.id == forced && [.draft, .planned].contains($0.state) } }
        if target == nil { target = matchTarget(&p, pre: pre, preReading: preReading, pool: pool, context: context, calendar: calendar) }
        guard let target else { return }
        p.targetID = target.id; p.childID = target.childID; p.title = target.title

        if let forced = p.overrides.dueAt { p.dueAt = forced; p.inheritedDate = postReading.day == nil; checkChanged(&p, target); return }
        let day = postReading.day ?? calendar.startOfDay(for: target.dueAt)
        p.inheritedDate = postReading.day == nil
        if let hour = postReading.hour, let date = lexicon.date(on: day, hour: hour, minute: postReading.minute, calendar: calendar) {
            p.dueAt = date; checkChanged(&p, target)
        } else {
            p.missing.append(.time); p.ambiguousHour = postReading.ambiguousHour; p.ambiguousDay = day
        }
    }

    private static func checkChanged(_ p: inout ParentingNaturalProposal, _ target: ParentingArrangement) {
        if let due = p.dueAt, floor(due.timeIntervalSince1970 / 60) == floor(target.dueAt.timeIntervalSince1970 / 60) {
            p.dueAt = nil; p.missing.append(.time)
        }
    }

    // MARK: Follow-up
    /// Exactly one question: the first missing field. Fields that are already clear are never asked again.
    public static func followUp(for p: ParentingNaturalProposal, context: ParentingNaturalContext, calendar: Calendar = .autoupdatingCurrent) -> ParentingFollowUp? {
        guard let field = p.missing.min() else { return nil }
        switch field {
        case .intent:
            // Two big buttons, never a question about the time: 记下来 (already happened, no reminder) / 安排提醒 (a reminder).
            return .init(field: field, question: p.ambiguousIntent ? "这条怎么记？" : "小咪没太听明白，这条怎么记？",
                         options: [.init(id: "intent." + ParentingIntentKind.observation.rawValue, label: .key("记下来"), detail: nil,
                                         answer: .intent(.observation), subtitle: "已经发生，只记录，不提醒"),
                                   .init(id: "intent." + ParentingIntentKind.newArrangement.rawValue, label: .key("安排提醒"), detail: nil,
                                         answer: .intent(.newArrangement), subtitle: "还没发生，到点提醒我")],
                         allowsText: false, textHint: nil)
        case .child:
            let choices = p.childChoices.compactMap { id in context.children.first { $0.id == id } }
            return .init(field: field, question: choices.isEmpty ? "还没有孩子。请先添加孩子的昵称，再记这件事。" : "这是哪位孩子的事？",
                         options: choices.map { .init(id: "child." + $0.id.uuidString, label: .verbatim($0.name), detail: nil, answer: .child($0.id)) },
                         allowsText: false, textHint: nil)
        case .target:
            let choices = p.targetChoices.compactMap { id in context.arrangements.first { $0.id == id } }
            let none = p.kind == .cancel ? "没有找到要取消的安排。说具体一点试试，例如：取消周五的游泳。" : "还没有可以改期的安排。先记一件安排吧。"
            return .init(field: field, question: choices.isEmpty ? none : (p.kind == .cancel ? "要取消哪一项安排？" : "要改哪一项安排？"),
                         options: choices.map { .init(id: "target." + $0.id.uuidString, label: .verbatim($0.title), detail: $0.dueAt, answer: .target($0.id)) },
                         allowsText: false, textHint: nil)
        case .date:
            return .init(field: field, question: "哪一天？", options: [], allowsText: true, textHint: "例如：周五、明天、10月30日")
        case .time:
            if let hour = p.ambiguousHour, let day = p.ambiguousDay {
                var hours = hour == 12 ? [12] : [hour, hour + 12]
                if let target = p.targetID.flatMap({ id in context.arrangements.first { $0.id == id } }),
                   calendar.component(.hour, from: target.dueAt) >= 12, hours.count == 2 { hours.reverse() }
                let minute = ParentingNaturalLexicon.read(p.sourceText, now: day, calendar: calendar).minute
                let options: [ParentingFollowUp.Option] = hours.compactMap { h in
                    guard let date = ParentingNaturalLexicon.date(on: day, hour: h, minute: minute, calendar: calendar) else { return nil }
                    return .init(id: "time.\(h)", label: .dateTime(date), detail: nil, answer: .dueAt(date))
                }
                return .init(field: field, question: "是上午还是下午？", options: options, allowsText: false, textHint: nil)
            }
            return .init(field: field, question: p.kind == .reschedule ? "改到几点？" : "几点开始？",
                         options: [], allowsText: true, textHint: "例如：下午5点、18:30")
        case .title:
            return .init(field: field, question: "是什么事？", options: [], allowsText: true, textHint: "一句话就行，例如：游泳课")
        case .content:
            return .init(field: field, question: "想记下什么？", options: [], allowsText: true, textHint: "例如：今天他自己收好了书包")
        }
    }

    /// Re-reads the original words together with one answer. Earlier answers stay applied.
    public static func applying(_ answer: ParentingNaturalAnswer, to p: ParentingNaturalProposal, context: ParentingNaturalContext,
                                now: Date, calendar: Calendar = .autoupdatingCurrent) -> ParentingNaturalProposal {
        var overrides = p.overrides
        var text = p.sourceText
        switch answer {
        case .intent(let kind): overrides.kind = kind
        case .child(let id): overrides.childID = id
        case .target(let id): overrides.targetID = id
        case .dueAt(let date): overrides.dueAt = date
        case .text(let extra):
            let trimmed = extra.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { text = p.sourceText + " " + trimmed }
            // A settled intent stays settled: a later answer must not turn an observation into an arrangement.
            if p.kind != .unknown { overrides.kind = p.kind }
        }
        return classify(text, context: context, now: now, calendar: calendar, overrides: overrides)
    }

    // MARK: Writing
    public struct WriteIDs: Sendable, Equatable {
        public var record: UUID
        public init(record: UUID = UUID()) { self.record = record }
    }

    /// The commands a confirmed card stands for, in order. Empty when anything is still missing or the kind only views.
    public static func commands(for p: ParentingNaturalProposal, workspace: ParentingWorkspace, ids: WriteIDs) -> [ParentingCommand] {
        guard p.isReadyToConfirm else { return [] }
        switch p.kind {
        case .newArrangement:
            guard let child = p.childID, let title = p.title, let due = p.dueAt else { return [] }
            return [.createDraft(id: ids.record, childID: child, title: title, dueAt: due, goalID: nil, sourceText: p.sourceText),
                    .confirmArrangement(id: ids.record)]
        case .observation:
            guard let child = p.childID, let text = p.observationText else { return [] }
            return [.addObservation(id: ids.record, childID: child, goalID: nil, text: text)]
        case .reschedule:
            guard let id = p.targetID, let due = p.dueAt, let item = workspace.arrangements.first(where: { $0.id == id }) else { return [] }
            let conflicts = workspace.sameTimeConflicts(arrangementID: id, dueAt: due).map(\.id)
            return [.reviseArrangement(id: ids.record, target: .init(arrangement: item, workspaceRevision: workspace.revision),
                                       title: item.title, dueAt: due, goalID: item.goalID, sourceText: p.sourceText,
                                       acknowledgedConflictIDs: conflicts)]
        case .cancel:
            guard let id = p.targetID, workspace.arrangements.contains(where: { $0.id == id && [.draft, .planned].contains($0.state) }) else { return [] }
            return [.cancelArrangement(id: id)]
        default: return []
        }
    }
}
