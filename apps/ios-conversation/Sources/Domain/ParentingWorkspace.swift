import Foundation

/// A parent's local ledger. These plans and records do not represent agent tasks or external effects.
public struct ParentingWorkspace: Codable, Sendable, Equatable {
    public var schemaVersion: Int
    public var revision: Int
    public var children: [ParentingChild]
    public var goals: [ParentingGoal]
    public var arrangements: [ParentingArrangement]
    public var records: [ParentingRecord]
    public var editHistory: [ParentingArrangementEdit]
    public var suggestionsEnabled: Bool
    /// Optional local family archive. Missing key decodes as empty so old ledgers need no migration.
    public var profile: ParentingProfile

    public static let empty = ParentingWorkspace()
    public init(schemaVersion: Int = 1, revision: Int = 0, children: [ParentingChild] = [], goals: [ParentingGoal] = [], arrangements: [ParentingArrangement] = [], records: [ParentingRecord] = [], editHistory: [ParentingArrangementEdit] = [], suggestionsEnabled: Bool = false, profile: ParentingProfile = .empty) {
        self.schemaVersion = schemaVersion; self.revision = revision; self.children = children; self.goals = goals
        self.arrangements = arrangements; self.records = records; self.editHistory = editHistory; self.suggestionsEnabled = suggestionsEnabled
        self.profile = profile
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, revision, children, goals, arrangements, records, editHistory, suggestionsEnabled, profile
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        revision = try container.decode(Int.self, forKey: .revision)
        children = try container.decode([ParentingChild].self, forKey: .children)
        goals = try container.decode([ParentingGoal].self, forKey: .goals)
        arrangements = try container.decode([ParentingArrangement].self, forKey: .arrangements)
        records = try container.decode([ParentingRecord].self, forKey: .records)
        if container.contains(.editHistory) {
            editHistory = try container.decode([ParentingArrangementEdit].self, forKey: .editHistory)
        } else {
            editHistory = []
        }
        suggestionsEnabled = try container.decode(Bool.self, forKey: .suggestionsEnabled)
        if container.contains(.profile) {
            profile = try container.decode(ParentingProfile.self, forKey: .profile)
        } else {
            profile = .empty
        }
    }

    public func validate() throws {
        guard schemaVersion == 1, revision >= 0 else { throw catError.corrupt("育儿账本版本不支持，已保留原数据。") }
        let allIDs = children.map(\.id) + goals.map(\.id) + arrangements.map(\.id) + records.map(\.id) + editHistory.map(\.id) + profile.facts.map(\.id)
        guard Set(allIDs).count == allIDs.count else { throw catError.corrupt("育儿账本标识重复，已停止写入。") }
        let childIDs = Set(children.map(\.id))
        let goalsByID = Dictionary(uniqueKeysWithValues: goals.map { ($0.id, $0) })
        let arrangementsByID = Dictionary(uniqueKeysWithValues: arrangements.map { ($0.id, $0) })
        for child in children { try Self.text(child.name, limit: 40); try Self.date(child.createdAt) }
        for goal in goals {
            guard childIDs.contains(goal.childID) else { throw catError.corrupt("育儿目标的孩子关联无效。") }
            try Self.text(goal.title, limit: 120); try Self.date(goal.createdAt)
        }
        for arrangement in arrangements {
            try Self.references(childID: arrangement.childID, goalID: arrangement.goalID, children: childIDs, goals: goalsByID)
            try Self.text(arrangement.title, limit: 160); try Self.text(arrangement.sourceText, limit: 8000, allowEmpty: true)
            try Self.date(arrangement.dueAt); try Self.dates(arrangement.createdAt, arrangement.updatedAt)
        }
        var completionArrangementIDs = Set<UUID>()
        for record in records {
            try Self.references(childID: record.childID, goalID: record.goalID, children: childIDs, goals: goalsByID)
            try Self.text(record.text, limit: 8000); try Self.text(record.sourceText, limit: 8000, allowEmpty: true)
            try Self.dates(record.createdAt, record.updatedAt)
            if let id = record.arrangementID {
                guard record.kind == .completion, let arrangement = arrangementsByID[id], arrangement.childID == record.childID,
                      arrangement.state == .completed, completionArrangementIDs.insert(id).inserted else {
                    throw catError.corrupt("育儿记录的安排关联无效。")
                }
            }
        }
        for edit in editHistory {
            let before = edit.before, after = edit.after
            guard arrangementsByID[before.id]?.childID == before.childID, before.id == after.id, before.childID == after.childID,
                  before.state == after.state, [.draft, .planned].contains(before.state),
                  before.sourceText == after.sourceText, before.createdAt == after.createdAt,
                  after.updatedAt == edit.createdAt, edit.createdAt >= before.updatedAt,
                  before.title != after.title || before.dueAt != after.dueAt || before.goalID != after.goalID else {
                throw catError.corrupt("育儿调整历史关联无效，已保留原数据。")
            }
            for arrangement in [before, after] {
                try Self.references(childID: arrangement.childID, goalID: arrangement.goalID, children: childIDs, goals: goalsByID)
                try Self.text(arrangement.title, limit: 160); try Self.text(arrangement.sourceText, limit: 8000, allowEmpty: true)
                try Self.date(arrangement.dueAt); try Self.dates(arrangement.createdAt, arrangement.updatedAt)
            }
            try Self.text(edit.sourceText, limit: 8000, allowEmpty: true); try Self.date(edit.createdAt)
            if let revertedAt = edit.revertedAt { try Self.dates(edit.createdAt, revertedAt) }
            if let supersededAt = edit.supersededAt { try Self.dates(edit.createdAt, supersededAt) }
        }
        try validateProfile(childIDs: childIDs)
    }

    private func validateProfile(childIDs: Set<UUID>) throws {
        let familyCount = profile.facts.filter { if case .family = $0.scope { return true }; return false }.count
        guard familyCount <= ParentingProfile.maxFamilyFacts else { throw catError.corrupt("家庭画像条目过多。") }
        var perChild: [UUID: Int] = [:]
        for fact in profile.facts {
            switch fact.scope {
            case .family: break
            case .child(let id):
                guard childIDs.contains(id) else { throw catError.corrupt("画像条目的孩子关联无效。") }
                perChild[id, default: 0] += 1
            }
            try Self.text(fact.text, limit: ParentingProfile.maxTextLength)
            try Self.text(fact.payload, limit: ParentingProfile.maxPayloadLength, allowEmpty: true)
            if let excerpt = fact.source.excerpt {
                try Self.text(excerpt, limit: ParentingProfile.maxExcerptLength, allowEmpty: true)
            }
            try Self.date(fact.source.firstSeenAt)
            try Self.dates(fact.createdAt, fact.updatedAt)
            try Self.date(fact.lastSeenAt)
            if let confirmed = fact.lastConfirmedAt { try Self.dates(fact.createdAt, confirmed) }
            guard fact.evidenceCount >= 0 else { throw catError.corrupt("画像证据次数无效。") }
            if fact.userConfirmed { guard fact.status == .confirmed || fact.status == .stale else { throw catError.corrupt("画像确认状态无效。") } }
        }
        if perChild.values.contains(where: { $0 > ParentingProfile.maxChildFacts }) {
            throw catError.corrupt("孩子画像条目过多。")
        }
    }

    /// Pure transition: callers persist the resulting value atomically before presenting it.
    public func applying(_ command: ParentingCommand, now: Date = Date()) throws -> ParentingWorkspace {
        try validate(); try Self.date(now)
        guard revision < Int.max else { throw catError.invalid("育儿账本版本已达上限。") }
        var next = self
        switch command {
        case .addChild(let id, let name):
            try next.unused(id); try Self.text(name, limit: 40)
            next.children.append(.init(id: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines), createdAt: now))
        case .renameChild(let id, let name):
            let i = try next.childIndex(id); try Self.text(name, limit: 40)
            next.children[i].name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        case .deleteChild(let id):
            _ = try next.childIndex(id)
            next.children.removeAll { $0.id == id }; next.goals.removeAll { $0.childID == id }
            next.arrangements.removeAll { $0.childID == id }; next.records.removeAll { $0.childID == id }
            next.editHistory.removeAll { $0.before.childID == id }
            next.profile.facts.removeAll { if case .child(let childID) = $0.scope { return childID == id }; return false }
        case .addGoal(let id, let childID, let title):
            try next.unused(id); _ = try next.childIndex(childID); try Self.text(title, limit: 120)
            next.goals.append(.init(id: id, childID: childID, title: title.trimmingCharacters(in: .whitespacesAndNewlines), createdAt: now))
        case .deleteGoal(let id):
            guard next.goals.contains(where: { $0.id == id }) else { throw catError.missing }
            next.goals.removeAll { $0.id == id }
            for i in next.arrangements.indices where next.arrangements[i].goalID == id { next.arrangements[i].goalID = nil }
            for i in next.records.indices where next.records[i].goalID == id { next.records[i].goalID = nil }
            next.editHistory.removeAll { $0.before.goalID == id || $0.after.goalID == id }
        case .createDraft(let id, let childID, let title, let dueAt, let goalID, let sourceText):
            try next.unused(id); try next.checkReferences(childID: childID, goalID: goalID)
            try Self.text(title, limit: 160); try Self.text(sourceText, limit: 8000, allowEmpty: true); try Self.date(dueAt)
            next.arrangements.append(.init(id: id, childID: childID, title: title.trimmingCharacters(in: .whitespacesAndNewlines), dueAt: dueAt, goalID: goalID, state: .draft, sourceText: sourceText, createdAt: now, updatedAt: now))
        case .editArrangement(let id, let title, let dueAt, let goalID):
            let i = try next.arrangementIndex(id)
            guard [.draft, .planned].contains(next.arrangements[i].state) else { throw catError.invalid("仅待确认或已安排的内容可以修改。") }
            try next.checkReferences(childID: next.arrangements[i].childID, goalID: goalID)
            try Self.text(title, limit: 160); try Self.date(dueAt)
            next.arrangements[i].title = title.trimmingCharacters(in: .whitespacesAndNewlines)
            next.arrangements[i].dueAt = dueAt; next.arrangements[i].goalID = goalID; next.arrangements[i].updatedAt = now
            next.supersedeEdits(arrangementID: id, now: now)
        case .reviseArrangement(let id, let target, let title, let dueAt, let goalID, let sourceText, let acknowledgedConflictIDs):
            guard target.matches(workspace: self) else { throw catError.conflict }
            try next.unused(id)
            let i = try next.arrangementIndex(target.arrangement.id)
            let before = next.arrangements[i]
            guard [.draft, .planned].contains(before.state) else { throw catError.invalid("仅待确认或已安排的内容可以修改。") }
            try next.checkReferences(childID: before.childID, goalID: goalID)
            try Self.text(title, limit: 160); try Self.text(sourceText, limit: 8000, allowEmpty: true); try Self.date(dueAt)
            let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard before.title != trimmedTitle || before.dueAt != dueAt || before.goalID != goalID else {
                throw catError.invalid("请先修改安排，再确认调整。")
            }
            try next.checkSameTimeAcknowledgement(acknowledgedConflictIDs, arrangementID: before.id, dueAt: dueAt)
            var after = before
            after.title = trimmedTitle; after.dueAt = dueAt; after.goalID = goalID; after.updatedAt = now
            next.arrangements[i] = after
            next.supersedeEdits(arrangementID: before.id, now: now)
            next.editHistory.append(.init(id: id, before: before, after: after, sourceText: sourceText, createdAt: now))
        case .undoArrangementEdit(let id, let acknowledgedConflictIDs):
            guard let historyIndex = next.editHistory.firstIndex(where: { $0.id == id }) else { throw catError.missing }
            let edit = next.editHistory[historyIndex]
            let i = try next.arrangementIndex(edit.after.id)
            guard edit.canUndo(in: next) else {
                throw catError.invalid("安排已发生其他修改，不能撤销这次调整。")
            }
            try next.checkReferences(childID: edit.before.childID, goalID: edit.before.goalID)
            try next.checkSameTimeAcknowledgement(acknowledgedConflictIDs, arrangementID: edit.before.id, dueAt: edit.before.dueAt)
            next.arrangements[i].title = edit.before.title
            next.arrangements[i].dueAt = edit.before.dueAt; next.arrangements[i].goalID = edit.before.goalID
            next.arrangements[i].updatedAt = now
            next.editHistory[historyIndex].revertedAt = now
        case .confirmArrangement(let id):
            let i = try next.arrangementIndex(id)
            guard [.draft, .planned].contains(next.arrangements[i].state) else { throw catError.invalid("已取消或已完成的安排不能确认。") }
            if next.arrangements[i].state == .draft {
                next.arrangements[i].state = .planned; next.arrangements[i].updatedAt = now
                next.supersedeEdits(arrangementID: id, now: now)
            }
        case .cancelArrangement(let id):
            let i = try next.arrangementIndex(id)
            guard next.arrangements[i].state != .completed else { throw catError.invalid("已完成的安排不能取消。") }
            if next.arrangements[i].state != .cancelled {
                next.arrangements[i].state = .cancelled; next.arrangements[i].updatedAt = now
                next.supersedeEdits(arrangementID: id, now: now)
            }
        case .completeArrangement(let id, let recordID, let note):
            let i = try next.arrangementIndex(id)
            try Self.text(note, limit: 8000, allowEmpty: true)
            guard next.arrangements[i].state != .cancelled else { throw catError.invalid("已取消的安排不能完成。") }
            if next.arrangements[i].state == .completed { break }
            guard next.arrangements[i].state == .planned else { throw catError.invalid("请先确认安排，再记录完成。") }
            try next.unused(recordID)
            let arrangement = next.arrangements[i]
            let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
            next.records.append(.init(id: recordID, childID: arrangement.childID, goalID: arrangement.goalID, arrangementID: id, kind: .completion, text: trimmed.isEmpty ? arrangement.title : trimmed, sourceText: note, createdAt: now, updatedAt: now))
            next.arrangements[i].state = .completed; next.arrangements[i].updatedAt = now
            next.supersedeEdits(arrangementID: id, now: now)
        case .reopenArrangement(let id):
            // Undo of completeArrangement. Already planned is a no-op, so a double tap or retry cannot fail or duplicate.
            let i = try next.arrangementIndex(id)
            switch next.arrangements[i].state {
            case .planned: break
            case .draft: throw catError.invalid("尚未确认的安排不需要重新打开。")
            case .cancelled: throw catError.invalid("已取消的安排不能重新打开。")
            case .completed:
                for r in next.records.indices.reversed() where next.records[r].arrangementID == id {
                    let record = next.records[r]
                    // The untouched, automatically written completion goes away. Words the parent typed or edited are kept as an observation.
                    let parentWrote = !record.sourceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || record.updatedAt != record.createdAt
                    if parentWrote {
                        next.records[r].kind = .observation; next.records[r].arrangementID = nil; next.records[r].updatedAt = now
                    } else {
                        next.records.remove(at: r)
                    }
                }
                next.arrangements[i].state = .planned; next.arrangements[i].updatedAt = now
            }
        case .deleteArrangement(let id):
            _ = try next.arrangementIndex(id)
            next.arrangements.removeAll { $0.id == id }
            for i in next.records.indices where next.records[i].arrangementID == id { next.records[i].arrangementID = nil }
            next.editHistory.removeAll { $0.before.id == id }
        case .addObservation(let id, let childID, let goalID, let text):
            try next.unused(id); try next.checkReferences(childID: childID, goalID: goalID); try Self.text(text, limit: 8000)
            next.records.append(.init(id: id, childID: childID, goalID: goalID, arrangementID: nil, kind: .observation, text: text.trimmingCharacters(in: .whitespacesAndNewlines), sourceText: text, createdAt: now, updatedAt: now))
        case .editRecord(let id, let text):
            guard let i = next.records.firstIndex(where: { $0.id == id }) else { throw catError.missing }
            try Self.text(text, limit: 8000)
            next.records[i].text = text.trimmingCharacters(in: .whitespacesAndNewlines); next.records[i].updatedAt = now
        case .deleteRecord(let id):
            guard next.records.contains(where: { $0.id == id }) else { throw catError.missing }
            next.records.removeAll { $0.id == id }
        case .setSuggestionsEnabled(let enabled): next.suggestionsEnabled = enabled
        case .deleteAllRecords:
            // The parent's own erase: every child and everything attached to a child. The review setting stays.
            next.children = []; next.goals = []; next.arrangements = []; next.records = []; next.editHistory = []
            next.profile = .empty
        case .addProfileFact(let fact):
            try next.unused(fact.id)
            var candidate = fact
            candidate.status = .candidate
            candidate.userConfirmed = false
            candidate.lastConfirmedAt = nil
            next.profile.facts.append(candidate)
            try next.validateProfile(childIDs: Set(next.children.map(\.id)))
        case .confirmProfileFact(let id):
            guard let i = next.profile.facts.firstIndex(where: { $0.id == id }) else { throw catError.missing }
            guard next.profile.facts[i].status == .candidate || next.profile.facts[i].status == .stale else {
                throw catError.invalid("只能确认待确认或需再确认的画像。")
            }
            guard next.profile.facts[i].confidence != .low else {
                throw catError.invalid("还不确定的画像不能记成长期事实。")
            }
            next.profile.facts[i].status = .confirmed
            next.profile.facts[i].userConfirmed = true
            next.profile.facts[i].lastConfirmedAt = now
            next.profile.facts[i].updatedAt = now
        case .dismissProfileFact(let id, let fingerprint):
            guard let i = next.profile.facts.firstIndex(where: { $0.id == id }) else { throw catError.missing }
            next.profile.facts[i].status = .dismissed
            next.profile.facts[i].userConfirmed = false
            next.profile.facts[i].suppressFingerprint = fingerprint
            next.profile.facts[i].updatedAt = now
        case .deleteProfileFact(let id):
            guard next.profile.facts.contains(where: { $0.id == id }) else { throw catError.missing }
            next.profile.facts.removeAll { $0.id == id }
        case .clearProfile(let scope):
            switch scope {
            case .all:
                next.profile.facts = []
            case .family:
                next.profile.facts.removeAll { if case .family = $0.scope { return true }; return false }
            case .child(let id):
                _ = try next.childIndex(id)
                next.profile.facts.removeAll { if case .child(let childID) = $0.scope { return childID == id }; return false }
            }
        case .setProfileLearningEnabled(let enabled):
            next.profile.learningEnabled = enabled
        }
        next.revision += 1
        try next.validate()
        return next
    }

    private func unused(_ id: UUID) throws {
        guard !children.contains(where: { $0.id == id }), !goals.contains(where: { $0.id == id }),
              !arrangements.contains(where: { $0.id == id }), !records.contains(where: { $0.id == id }),
              !editHistory.contains(where: { $0.id == id }),
              !profile.facts.contains(where: { $0.id == id }) else { throw catError.conflict }
    }
    private mutating func supersedeEdits(arrangementID: UUID, now: Date) {
        for i in editHistory.indices where editHistory[i].after.id == arrangementID && editHistory[i].revertedAt == nil && editHistory[i].supersededAt == nil {
            editHistory[i].supersededAt = now
        }
    }
    private func checkSameTimeAcknowledgement(_ acknowledgedIDs: [UUID], arrangementID: UUID, dueAt: Date) throws {
        let conflicts = Set(sameTimeConflicts(arrangementID: arrangementID, dueAt: dueAt).map(\.id))
        guard Set(acknowledgedIDs).count == acknowledgedIDs.count, Set(acknowledgedIDs) == conflicts else {
            throw catError.invalid("同一时间的安排已变化，请重新核对可能冲突的事项。")
        }
    }
    private func childIndex(_ id: UUID) throws -> Int {
        guard let i = children.firstIndex(where: { $0.id == id }) else { throw catError.missing }; return i
    }
    private func arrangementIndex(_ id: UUID) throws -> Int {
        guard let i = arrangements.firstIndex(where: { $0.id == id }) else { throw catError.missing }; return i
    }
    private func checkReferences(childID: UUID, goalID: UUID?) throws {
        try Self.references(childID: childID, goalID: goalID, children: Set(children.map(\.id)), goals: Dictionary(uniqueKeysWithValues: goals.map { ($0.id, $0) }))
    }
    private static func references(childID: UUID, goalID: UUID?, children: Set<UUID>, goals: [UUID: ParentingGoal]) throws {
        guard children.contains(childID) else { throw catError.invalid("请先选择当前账本中的孩子。") }
        if let goalID { guard goals[goalID]?.childID == childID else { throw catError.invalid("目标必须属于当前孩子。") } }
    }
    private static func text(_ value: String, limit: Int, allowEmpty: Bool = false) throws {
        guard value.count <= limit, allowEmpty || !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !value.unicodeScalars.contains(where: { $0.value == 0 }) else { throw catError.invalid("内容不能为空，且不能超过\(limit)字。") }
    }
    private static func date(_ value: Date) throws {
        guard value.timeIntervalSince1970.isFinite else { throw catError.invalid("时间无效。") }
    }
    private static func dates(_ created: Date, _ updated: Date) throws {
        try date(created); try date(updated)
        guard updated >= created else { throw catError.corrupt("育儿账本时间无效，已保留原数据。") }
    }
}

public struct ParentingChild: Identifiable, Codable, Sendable, Equatable {
    public var id: UUID; public var name: String; public var createdAt: Date
    public init(id: UUID, name: String, createdAt: Date) { self.id = id; self.name = name; self.createdAt = createdAt }
}
public struct ParentingGoal: Identifiable, Codable, Sendable, Equatable {
    public var id: UUID; public var childID: UUID; public var title: String; public var createdAt: Date
    public init(id: UUID, childID: UUID, title: String, createdAt: Date) { self.id = id; self.childID = childID; self.title = title; self.createdAt = createdAt }
}
public enum ParentingArrangementState: String, Codable, Sendable, CaseIterable { case draft, planned, cancelled, completed }
public struct ParentingArrangement: Identifiable, Codable, Sendable, Equatable {
    public var id: UUID; public var childID: UUID; public var title: String; public var dueAt: Date; public var goalID: UUID?
    public var state: ParentingArrangementState; public var sourceText: String; public var createdAt: Date; public var updatedAt: Date
    public init(id: UUID, childID: UUID, title: String, dueAt: Date, goalID: UUID?, state: ParentingArrangementState, sourceText: String, createdAt: Date, updatedAt: Date) {
        self.id = id; self.childID = childID; self.title = title; self.dueAt = dueAt; self.goalID = goalID; self.state = state
        self.sourceText = sourceText; self.createdAt = createdAt; self.updatedAt = updatedAt
    }
}
public enum ParentingRecordKind: String, Codable, Sendable, CaseIterable { case observation, completion }
public struct ParentingRecord: Identifiable, Codable, Sendable, Equatable {
    public var id: UUID; public var childID: UUID; public var goalID: UUID?; public var arrangementID: UUID?
    public var kind: ParentingRecordKind; public var text: String; public var sourceText: String; public var createdAt: Date; public var updatedAt: Date
    public init(id: UUID, childID: UUID, goalID: UUID?, arrangementID: UUID?, kind: ParentingRecordKind, text: String, sourceText: String, createdAt: Date, updatedAt: Date) {
        self.id = id; self.childID = childID; self.goalID = goalID; self.arrangementID = arrangementID; self.kind = kind
        self.text = text; self.sourceText = sourceText; self.createdAt = createdAt; self.updatedAt = updatedAt
    }
}

public enum ParentingCommand: Codable, Sendable, Equatable {
    case addChild(id: UUID, name: String)
    case renameChild(id: UUID, name: String)
    case deleteChild(id: UUID)
    case addGoal(id: UUID, childID: UUID, title: String)
    case deleteGoal(id: UUID)
    case createDraft(id: UUID, childID: UUID, title: String, dueAt: Date, goalID: UUID?, sourceText: String)
    case editArrangement(id: UUID, title: String, dueAt: Date, goalID: UUID?)
    case reviseArrangement(id: UUID, target: ParentingArrangementEditTarget, title: String, dueAt: Date, goalID: UUID?, sourceText: String, acknowledgedConflictIDs: [UUID])
    case undoArrangementEdit(id: UUID, acknowledgedConflictIDs: [UUID])
    case confirmArrangement(id: UUID)
    case cancelArrangement(id: UUID)
    case completeArrangement(id: UUID, recordID: UUID, note: String)
    case reopenArrangement(id: UUID)
    case deleteArrangement(id: UUID)
    case addObservation(id: UUID, childID: UUID, goalID: UUID?, text: String)
    case editRecord(id: UUID, text: String)
    case deleteRecord(id: UUID)
    case setSuggestionsEnabled(Bool)
    case deleteAllRecords
    /// Candidate only: never becomes usable until confirmProfileFact.
    case addProfileFact(ParentingProfileFact)
    case confirmProfileFact(id: UUID)
    case dismissProfileFact(id: UUID, fingerprint: String?)
    case deleteProfileFact(id: UUID)
    /// `.all` clears the whole archive; family/child scopes clear that slice.
    case clearProfile(ParentingProfileClearScope)
    case setProfileLearningEnabled(Bool)
}

public enum ParentingProfileClearScope: Codable, Sendable, Equatable {
    case all
    case family
    case child(UUID)
}
