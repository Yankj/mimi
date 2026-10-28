import XCTest
@testable import cat

final class ParentingProfileTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_729_872_000)
    private let childA = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
    private let childB = UUID(uuidString: "00000000-0000-4000-8000-000000000002")!
    private let factID = UUID(uuidString: "00000000-0000-4000-8000-0000000000aa")!

    private func workspaceWithChild() throws -> ParentingWorkspace {
        var ws = ParentingWorkspace.empty
        ws = try ws.applying(.addChild(id: childA, name: "团团"), now: now)
        return ws
    }

    private func candidateAlias(id: UUID = UUID(uuidString: "00000000-0000-4000-8000-0000000000aa")!, child: UUID) -> ParentingProfileFact {
        ParentingProfileFact(
            id: id,
            scope: .child(child),
            kind: .alias,
            payload: "老大",
            text: "团团也叫老大",
            status: .candidate,
            source: .init(channel: .explicitStatement, excerpt: "我们都叫他老大", firstSeenAt: now),
            confidence: .high,
            lastSeenAt: now,
            createdAt: now,
            updatedAt: now
        )
    }

    func testOldLedgerWithoutProfileKeyDecodesAsEmpty() throws {
        let legacy = ParentingWorkspace(schemaVersion: 1, revision: 1, children: [
            .init(id: childA, name: "团团", createdAt: now)
        ], suggestionsEnabled: false)
        // Encode then strip profile key to simulate an older app write.
        let data = try JSONEncoder().encode(legacy)
        var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        object.removeValue(forKey: "profile")
        let stripped = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(ParentingWorkspace.self, from: stripped)
        XCTAssertEqual(decoded.profile, .empty)
        XCTAssertEqual(decoded.children.map(\.name), ["团团"])
    }

    func testCandidateIsNotUsableUntilConfirmed() throws {
        var ws = try workspaceWithChild()
        ws = try ws.applying(.addProfileFact(candidateAlias(child: childA)), now: now)
        XCTAssertEqual(ws.profile.facts.count, 1)
        XCTAssertEqual(ws.profile.facts[0].status, .candidate)
        XCTAssertFalse(ws.profile.facts[0].userConfirmed)
        XCTAssertTrue(ws.profile.usableFacts.isEmpty)
        XCTAssertTrue(ws.profile.usableAliases(for: childA).isEmpty)

        ws = try ws.applying(.confirmProfileFact(id: factID), now: now.addingTimeInterval(60))
        XCTAssertEqual(ws.profile.facts[0].status, .confirmed)
        XCTAssertTrue(ws.profile.facts[0].userConfirmed)
        XCTAssertEqual(ws.profile.usableAliases(for: childA), ["老大"])
    }

    func testDismissRemembersFingerprintAndDeleteChildCascades() throws {
        var ws = try workspaceWithChild()
        ws = try ws.applying(.addChild(id: childB, name: "朵朵"), now: now)
        ws = try ws.applying(.addProfileFact(candidateAlias(child: childA)), now: now)
        ws = try ws.applying(.confirmProfileFact(id: factID), now: now)
        ws = try ws.applying(.dismissProfileFact(id: factID, fingerprint: "alias:老大"), now: now)
        XCTAssertEqual(ws.profile.facts[0].status, .dismissed)
        XCTAssertEqual(ws.profile.facts[0].suppressFingerprint, "alias:老大")
        XCTAssertTrue(ws.profile.usableFacts.isEmpty)

        ws = try ws.applying(.deleteChild(id: childA), now: now)
        XCTAssertTrue(ws.profile.facts.isEmpty, "child-scoped facts cascade with the child")
    }

    func testDeleteAllRecordsClearsProfile() throws {
        var ws = try workspaceWithChild()
        ws = try ws.applying(.addProfileFact(candidateAlias(child: childA)), now: now)
        ws = try ws.applying(.confirmProfileFact(id: factID), now: now)
        ws = try ws.applying(.deleteAllRecords, now: now)
        XCTAssertEqual(ws.profile, .empty)
        XCTAssertTrue(ws.children.isEmpty)
    }

    func testClearProfileScopes() throws {
        var ws = try workspaceWithChild()
        let familyID = UUID(uuidString: "00000000-0000-4000-8000-0000000000bb")!
        var family = ParentingProfileFact(
            id: familyID,
            scope: .family,
            kind: .routine,
            payload: "workday-0730",
            text: "工作日 7:30 出门",
            status: .candidate,
            source: .init(channel: .manualEdit, firstSeenAt: now),
            confidence: .medium,
            lastSeenAt: now,
            createdAt: now,
            updatedAt: now
        )
        ws = try ws.applying(.addProfileFact(candidateAlias(child: childA)), now: now)
        ws = try ws.applying(.addProfileFact(family), now: now)
        ws = try ws.applying(.clearProfile(.child(childA)), now: now)
        XCTAssertEqual(ws.profile.facts.map(\.id), [familyID])
        ws = try ws.applying(.clearProfile(.all), now: now)
        XCTAssertTrue(ws.profile.facts.isEmpty)
    }
}
