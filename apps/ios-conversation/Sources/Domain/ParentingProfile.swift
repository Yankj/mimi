import Foundation

/// Local structured family archive. Candidates never affect planning; only confirmed facts do.
public enum ParentingProfileFactKind: String, Codable, Sendable, CaseIterable {
    case alias
    case recurring
    case routine
    case pickup
    case preference
    case member
    case ageStage
    case sleep
    case interest
    case trait
    case sensitive

    /// Resource key for the archive row. Shown only after the parent has confirmed the fact.
    public var archiveTitle: String {
        switch self {
        case .alias: "称呼"
        case .recurring: "重复安排"
        case .routine: "习惯"
        case .pickup: "接送"
        case .preference: "偏好"
        case .member: "家人"
        case .ageStage: "年龄阶段"
        case .sleep: "睡眠"
        case .interest: "兴趣"
        case .trait: "特点"
        case .sensitive: "需留意"
        }
    }
}

public enum ParentingProfileFactStatus: String, Codable, Sendable, CaseIterable {
    case candidate
    case confirmed
    case dismissed
    case stale
}

public enum ParentingProfileSourceChannel: String, Codable, Sendable, CaseIterable {
    case explicitStatement
    case confirmCard
    case patternFromArrangements
    case manualEdit
    case aiIntake
}

public enum ParentingProfileConfidence: String, Codable, Sendable, CaseIterable {
    case low, medium, high
}

public enum ParentingProfileScope: Codable, Sendable, Equatable {
    case family
    case child(UUID)

    private enum CodingKeys: String, CodingKey { case type, childID }
    private enum ScopeType: String, Codable { case family, child }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        switch try c.decode(ScopeType.self, forKey: .type) {
        case .family: self = .family
        case .child: self = .child(try c.decode(UUID.self, forKey: .childID))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .family:
            try c.encode(ScopeType.family, forKey: .type)
        case .child(let id):
            try c.encode(ScopeType.child, forKey: .type)
            try c.encode(id, forKey: .childID)
        }
    }
}

public struct ParentingProfileSource: Codable, Sendable, Equatable {
    public var channel: ParentingProfileSourceChannel
    public var arrangementIDs: [UUID]
    public var recordIDs: [UUID]
    public var intakeConversationIDs: [UUID]
    public var excerpt: String?
    public var firstSeenAt: Date

    public init(
        channel: ParentingProfileSourceChannel,
        arrangementIDs: [UUID] = [],
        recordIDs: [UUID] = [],
        intakeConversationIDs: [UUID] = [],
        excerpt: String? = nil,
        firstSeenAt: Date
    ) {
        self.channel = channel
        self.arrangementIDs = arrangementIDs
        self.recordIDs = recordIDs
        self.intakeConversationIDs = intakeConversationIDs
        self.excerpt = excerpt
        self.firstSeenAt = firstSeenAt
    }
}

/// One structured fact. Payload stays a short free-form string for P0 skeleton; typed payloads land later.
public struct ParentingProfileFact: Identifiable, Codable, Sendable, Equatable {
    public var id: UUID
    public var scope: ParentingProfileScope
    public var kind: ParentingProfileFactKind
    public var payload: String
    public var text: String
    public var status: ParentingProfileFactStatus
    public var source: ParentingProfileSource
    public var confidence: ParentingProfileConfidence
    public var evidenceCount: Int
    public var lastSeenAt: Date
    public var lastConfirmedAt: Date?
    public var userConfirmed: Bool
    public var suppressFingerprint: String?
    public var createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID,
        scope: ParentingProfileScope,
        kind: ParentingProfileFactKind,
        payload: String,
        text: String,
        status: ParentingProfileFactStatus,
        source: ParentingProfileSource,
        confidence: ParentingProfileConfidence,
        evidenceCount: Int = 1,
        lastSeenAt: Date,
        lastConfirmedAt: Date? = nil,
        userConfirmed: Bool = false,
        suppressFingerprint: String? = nil,
        createdAt: Date,
        updatedAt: Date
    ) {
        self.id = id
        self.scope = scope
        self.kind = kind
        self.payload = payload
        self.text = text
        self.status = status
        self.source = source
        self.confidence = confidence
        self.evidenceCount = evidenceCount
        self.lastSeenAt = lastSeenAt
        self.lastConfirmedAt = lastConfirmedAt
        self.userConfirmed = userConfirmed
        self.suppressFingerprint = suppressFingerprint
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /// Only confirmed + user-acked facts may drive planning / intent / reminders.
    public var isUsable: Bool { userConfirmed && status == .confirmed }
}

public struct ParentingProfile: Codable, Sendable, Equatable {
    public static let maxFamilyFacts = 40
    public static let maxChildFacts = 60
    public static let maxTextLength = 120
    public static let maxPayloadLength = 240
    public static let maxExcerptLength = 40

    public var facts: [ParentingProfileFact]
    public var learningEnabled: Bool

    public static let empty = ParentingProfile(facts: [], learningEnabled: true)
    public init(facts: [ParentingProfileFact] = [], learningEnabled: Bool = true) {
        self.facts = facts
        self.learningEnabled = learningEnabled
    }

    public var usableFacts: [ParentingProfileFact] { facts.filter(\.isUsable) }

    /// Confirmed facts for one scope, oldest first. Candidates, dismissals and low-confidence rows are absent.
    public func usableFacts(for scope: ParentingProfileScope) -> [ParentingProfileFact] {
        usableFacts.filter { $0.scope == scope }.sorted { $0.createdAt < $1.createdAt }
    }

    public func usableAliases(for childID: UUID) -> [String] {
        usableFacts.compactMap { fact in
            guard case .child(let id) = fact.scope, id == childID, fact.kind == .alias else { return nil }
            return fact.payload
        }
    }
}

/// A structured family or child fact proposed on this device. It is not a ledger row.
/// Low confidence is uncertain: it must not be offered on a writing card and must not become long-term.
public struct ParentingProfileFactProposal: Equatable, Sendable {
    public var id: UUID
    public var scope: ParentingProfileScope
    public var kind: ParentingProfileFactKind
    public var payload: String
    public var text: String
    public var confidence: ParentingProfileConfidence
    public var excerpt: String?
    public var seenAt: Date

    public init(
        id: UUID,
        scope: ParentingProfileScope,
        kind: ParentingProfileFactKind,
        payload: String,
        text: String,
        confidence: ParentingProfileConfidence,
        excerpt: String? = nil,
        seenAt: Date
    ) {
        self.id = id
        self.scope = scope
        self.kind = kind
        self.payload = payload
        self.text = text
        self.confidence = confidence
        self.excerpt = excerpt
        self.seenAt = seenAt
    }

    public var trimmedText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    public var trimmedPayload: String { payload.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Certain enough to show on the confirm card. Uncertain proposals never reach it.
    public var canOfferConfirmCard: Bool {
        confidence != .low
            && !trimmedText.isEmpty
            && trimmedText.count <= ParentingProfile.maxTextLength
            && trimmedPayload.count <= ParentingProfile.maxPayloadLength
    }

    /// Commands that land a long-term fact. Empty unless the parent confirmed the card and the proposal is certain.
    /// The first command stores a candidate; only the second marks it confirmed. Neither runs for a low-confidence proposal.
    public func longTermCommands(userConfirmed: Bool) -> [ParentingCommand] {
        // Alias binding (老大 / 二宝) is a separate flow and is not written here.
        guard userConfirmed, canOfferConfirmCard, kind != .alias else { return [] }
        let excerpt = self.excerpt.map { String($0.prefix(ParentingProfile.maxExcerptLength)) }.flatMap { $0.isEmpty ? nil : $0 }
        let fact = ParentingProfileFact(
            id: id,
            scope: scope,
            kind: kind,
            payload: String(trimmedPayload.prefix(ParentingProfile.maxPayloadLength)),
            text: String(trimmedText.prefix(ParentingProfile.maxTextLength)),
            status: .confirmed,
            source: ParentingProfileSource(channel: .confirmCard, excerpt: excerpt, firstSeenAt: seenAt),
            confidence: confidence,
            lastSeenAt: seenAt,
            userConfirmed: true,
            createdAt: seenAt,
            updatedAt: seenAt
        )
        return [.addProfileFact(fact), .confirmProfileFact(id: id)]
    }
}

/// On-device structuring of an explicit archive statement. No network, no model, no sibling-to-child resolution.
public enum ParentingProfileFactReader {
    public static func read(
        _ raw: String,
        context: ParentingNaturalContext,
        now: Date,
        id: UUID = UUID()
    ) -> ParentingProfileFactProposal? {
        let text = ParentingNaturalLexicon.normalized(raw)
        guard ParentingNaturalLexicon.hasArchiveLead(text) else { return nil }
        let body = cleanBody(text)
        let clipped = String(body.prefix(ParentingProfile.maxTextLength))
        let payload = String(body.prefix(ParentingProfile.maxPayloadLength))
        let excerpt = String(text.prefix(ParentingProfile.maxExcerptLength))
        let (scope, confidence) = resolve(text, context: context, hasBody: !clipped.isEmpty)
        return ParentingProfileFactProposal(
            id: id,
            scope: scope,
            kind: kind(of: body),
            payload: payload,
            text: clipped,
            confidence: confidence,
            excerpt: excerpt,
            seenAt: now
        )
    }

    /// Nickname first. A sibling label is never mapped to a child. One unnamed child may be offered on the card; several may not.
    private static func resolve(_ text: String, context: ParentingNaturalContext, hasBody: Bool) -> (ParentingProfileScope, ParentingProfileConfidence) {
        let named = context.children.filter { !$0.name.isEmpty && text.contains($0.name) }
        if named.count == 1, hasBody { return (.child(named[0].id), .high) }
        if named.count > 1 || ParentingNaturalLexicon.mentionsSiblingLabel(text) || !hasBody {
            return (.family, .low)
        }
        if ParentingNaturalLexicon.archiveFamilyWords.contains(where: { text.contains($0) }) {
            return (.family, .high)
        }
        if context.children.count == 1 { return (.child(context.children[0].id), .medium) }
        return (.family, .low)
    }

    private static func kind(of body: String) -> ParentingProfileFactKind {
        let lexicon = ParentingNaturalLexicon.self
        func has(_ words: [String]) -> Bool { words.contains { body.contains($0) } }
        if has(lexicon.archiveSensitiveWords) { return .sensitive }
        if has(lexicon.archivePickupWords) { return .pickup }
        if has(lexicon.archiveSleepWords) { return .sleep }
        if has(lexicon.archiveInterestWords) { return .interest }
        if has(lexicon.archiveRoutineWords) { return .routine }
        if has(lexicon.archiveAgeWords) { return .ageStage }
        if has(lexicon.archiveTraitWords) { return .trait }
        if has(lexicon.archiveMemberWords) { return .member }
        return .preference
    }

    private static func cleanBody(_ text: String) -> String {
        var body = text
        for lead in ParentingNaturalLexicon.archiveLeads.sorted(by: { $0.count > $1.count }) {
            body = body.replacingOccurrences(of: lead, with: " ")
        }
        body = ParentingNaturalLexicon.removingSiblingReferences(body)
        body = body.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        let extra = CharacterSet(charactersIn: "，。！？、；：,.!?;:\"'“”‘’（）()[]【】")
        return body.trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters).union(extra))
    }
}
