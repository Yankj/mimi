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

    public func usableAliases(for childID: UUID) -> [String] {
        usableFacts.compactMap { fact in
            guard case .child(let id) = fact.scope, id == childID, fact.kind == .alias else { return nil }
            return fact.payload
        }
    }
}
