import Foundation

/// Contract for the optional understanding model that only runs when local rules are unsure.
/// Model proposes; local code still owns dates, child IDs, confirmation, and writes.
public enum ParentingIntentModelContract {
    public static let name = "mimi.intent.v1"
    public static let schemaVersion = 1
}

/// Confidence buckets from the model. Prefer enums over self-reported probabilities.
public enum ParentingIntentModelConfidence: String, Codable, Sendable, CaseIterable {
    case high, medium, low
}

public enum ParentingIntentModelIntent: String, Codable, Sendable, CaseIterable {
    case record
    case addArrangement = "add_arrangement"
    case reschedule
    case view
    case unsure
}

/// Child reference after on-device redaction: cloud only ever sees c1/c2/… or ambiguous/null.
public enum ParentingIntentModelChildRef: String, Codable, Sendable, Equatable {
    case c1, c2, c3, c4, c5, c6, c7, c8
    case ambiguous
    case none = "null"
}

public struct ParentingIntentModelProposal: Codable, Sendable, Equatable {
    public var schemaVersion: Int
    public var contract: String
    public var requestID: String
    public var intent: ParentingIntentModelIntent
    public var intentConfidence: ParentingIntentModelConfidence
    public var childRef: ParentingIntentModelChildRef
    public var childEvidence: String?
    public var title: String?
    public var dateText: String?
    public var timeText: String?
    public var clarification: String?

    public init(
        schemaVersion: Int = ParentingIntentModelContract.schemaVersion,
        contract: String = ParentingIntentModelContract.name,
        requestID: String,
        intent: ParentingIntentModelIntent,
        intentConfidence: ParentingIntentModelConfidence,
        childRef: ParentingIntentModelChildRef,
        childEvidence: String? = nil,
        title: String? = nil,
        dateText: String? = nil,
        timeText: String? = nil,
        clarification: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.contract = contract
        self.requestID = requestID
        self.intent = intent
        self.intentConfidence = intentConfidence
        self.childRef = childRef
        self.childEvidence = childEvidence
        self.title = title
        self.dateText = dateText
        self.timeText = timeText
        self.clarification = clarification
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case contract
        case requestID = "request_id"
        case intent
        case intentConfidence = "intent_confidence"
        case childRef = "child_ref"
        case childEvidence = "child_evidence"
        case title
        case dateText = "date_text"
        case timeText = "time_text"
        case clarification
    }
}

/// Why the local rule path wants (or does not want) a model call.
public enum ParentingIntentEscalateReason: String, Sendable, Equatable {
    case none
    case ambiguousIntent
    case unknownIntent
    case unclearChild
    case thinEvidence
}

public struct ParentingIntentEscalateDecision: Sendable, Equatable {
    public var shouldEscalate: Bool
    public var reason: ParentingIntentEscalateReason
    public init(shouldEscalate: Bool, reason: ParentingIntentEscalateReason) {
        self.shouldEscalate = shouldEscalate
        self.reason = reason
    }
}

/// Maps real child names → c1/c2 before any cloud call. Reverse map stays on device.
public struct ParentingIntentChildRedaction: Sendable, Equatable {
    public var aliases: [(code: ParentingIntentModelChildRef, childID: UUID, displayLabel: String)]
    public var selectedCode: ParentingIntentModelChildRef?

    public init(aliases: [(code: ParentingIntentModelChildRef, childID: UUID, displayLabel: String)],
                selectedCode: ParentingIntentModelChildRef? = nil) {
        self.aliases = aliases
        self.selectedCode = selectedCode
    }

    /// Build codes in creation order. Real nicknames never leave the device in the cloud payload.
    public static func make(from context: ParentingNaturalContext) -> ParentingIntentChildRedaction {
        let codes: [ParentingIntentModelChildRef] = [.c1, .c2, .c3, .c4, .c5, .c6, .c7, .c8]
        let ordered = context.children.enumerated()
            .sorted { ($0.element.createdAt, $0.offset) < ($1.element.createdAt, $1.offset) }
            .map(\.element)
        var aliases: [(ParentingIntentModelChildRef, UUID, String)] = []
        for (index, child) in ordered.prefix(codes.count).enumerated() {
            let label: String
            switch index {
            case 0: label = "老大"
            case 1: label = "老二"
            case 2: label = "老三"
            default: label = "孩子\(index + 1)"
            }
            aliases.append((codes[index], child.id, label))
        }
        let selected = context.activeChildID.flatMap { id in aliases.first { $0.1 == id }?.0 }
        return .init(aliases: aliases, selectedCode: selected)
    }

    /// Replace each known nickname with its code so outbound text has no real names.
    public func redact(_ text: String, namesByID: [UUID: String]) -> String {
        var pairs: [(String, String)] = []
        for alias in aliases {
            guard let name = namesByID[alias.childID], !name.isEmpty else { continue }
            pairs.append((name, alias.code.rawValue))
        }
        pairs.sort { $0.0.count > $1.0.count }
        var out = text
        for (name, code) in pairs {
            out = out.replacingOccurrences(of: name, with: code)
        }
        return out
    }

    public func resolve(_ ref: ParentingIntentModelChildRef) -> UUID? {
        aliases.first { $0.code == ref }?.childID
    }

    /// Compact context lines for a future model prompt: `c1=老大 (selected)`.
    public var promptLines: [String] {
        aliases.map { alias in
            let mark = (alias.code == selectedCode) ? " selected=true" : ""
            return "\(alias.code.rawValue)=\(alias.displayLabel)\(mark)"
        }
    }
}

public struct ParentingIntentModelRequest: Sendable, Equatable {
    public var requestID: UUID
    public var redactedText: String
    public var childLines: [String]
    public var nowISO8601: String
    public var timeZoneID: String

    public init(requestID: UUID = UUID(), redactedText: String, childLines: [String], now: Date, timeZone: TimeZone = .current) {
        self.requestID = requestID
        self.redactedText = redactedText
        self.childLines = childLines
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = timeZone
        self.nowISO8601 = formatter.string(from: now)
        self.timeZoneID = timeZone.identifier
    }
}

public enum ParentingIntentModelError: Error, Equatable, Sendable {
    case notConfigured
    case unavailable
    case timedOut
    case invalidResponse
    case consentRequired
}

/// Understanding-model adapter. P0/P1 skeleton: no live network in this PR.
public protocol ParentingIntentModel: Sendable {
    func understand(_ request: ParentingIntentModelRequest) async throws -> ParentingIntentModelProposal
}

/// Placeholder used until a real Edge/on-device adapter is wired. Always fails closed to local rules.
public struct ParentingIntentModelStub: ParentingIntentModel {
    public init() {}
    public func understand(_ request: ParentingIntentModelRequest) async throws -> ParentingIntentModelProposal {
        _ = request
        throw ParentingIntentModelError.notConfigured
    }
}

public enum ParentingIntentEscalate {
    /// Local rules stay the fast path. Escalate only on conflict / low confidence / unclear child.
    public static func decide(for proposal: ParentingNaturalProposal) -> ParentingIntentEscalateDecision {
        if proposal.ambiguousIntent {
            return .init(shouldEscalate: true, reason: .ambiguousIntent)
        }
        if proposal.kind == .unknown, proposal.missing.contains(.intent) {
            return .init(shouldEscalate: true, reason: .unknownIntent)
        }
        if proposal.missing.contains(.child) {
            return .init(shouldEscalate: true, reason: .unclearChild)
        }
        // Thin arrangement-only evidence already marked ambiguousIntent above; nothing else escalates.
        return .init(shouldEscalate: false, reason: .none)
    }

    /// Build a redacted request when escalate says yes. Caller still owns consent / timeout / fallback.
    public static func makeRequest(
        proposal: ParentingNaturalProposal,
        context: ParentingNaturalContext,
        now: Date,
        timeZone: TimeZone = .current
    ) -> ParentingIntentModelRequest? {
        let decision = decide(for: proposal)
        guard decision.shouldEscalate else { return nil }
        let redaction = ParentingIntentChildRedaction.make(from: context)
        let names = Dictionary(uniqueKeysWithValues: context.children.map { ($0.id, $0.name) })
        let redacted = redaction.redact(proposal.sourceText, namesByID: names)
        return ParentingIntentModelRequest(
            redactedText: redacted,
            childLines: redaction.promptLines,
            now: now,
            timeZone: timeZone
        )
    }

    /// Validate a model proposal: evidence snippets must be substrings of the redacted source.
    public static func validate(_ model: ParentingIntentModelProposal, against request: ParentingIntentModelRequest) -> Bool {
        guard model.schemaVersion == ParentingIntentModelContract.schemaVersion,
              model.contract == ParentingIntentModelContract.name,
              model.requestID == request.requestID.uuidString.lowercased() else { return false }
        if let title = model.title, title.count > 40 { return false }
        for fragment in [model.childEvidence, model.dateText, model.timeText].compactMap({ $0 }) {
            guard request.redactedText.contains(fragment) else { return false }
        }
        return true
    }
}
