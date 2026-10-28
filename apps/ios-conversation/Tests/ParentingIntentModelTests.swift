import XCTest
@testable import cat

final class ParentingIntentModelTests: XCTestCase {
    private func context(names: [String], active: Int? = nil) -> ParentingNaturalContext {
        let base = Date(timeIntervalSince1970: 1_700_000_000)
        let children = names.enumerated().map { i, name in
            ParentingChild(id: UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", i + 1))!,
                           name: name, createdAt: base.addingTimeInterval(TimeInterval(i)))
        }
        let activeID = active.map { children[$0].id }
        return ParentingNaturalContext(children: children, arrangements: [], activeChildID: activeID)
    }

    func testRedactionReplacesRealNicknamesWithCodes() {
        let ctx = context(names: ["小石榴", "豆豆"], active: 0)
        let redaction = ParentingIntentChildRedaction.make(from: ctx)
        XCTAssertEqual(redaction.aliases.map(\.code), [.c1, .c2])
        XCTAssertEqual(redaction.selectedCode, .c1)
        let names = Dictionary(uniqueKeysWithValues: ctx.children.map { ($0.id, $0.name) })
        let out = redaction.redact("今天小石榴睡得很早，豆豆还在玩", namesByID: names)
        XCTAssertFalse(out.contains("小石榴"))
        XCTAssertFalse(out.contains("豆豆"))
        XCTAssertTrue(out.contains("c1"))
        XCTAssertTrue(out.contains("c2"))
        XCTAssertEqual(redaction.resolve(.c1), ctx.children[0].id)
        XCTAssertTrue(redaction.promptLines[0].contains("selected=true"))
    }

    func testEscalateOnAmbiguousIntentAndUnclearChild() {
        let one = context(names: ["团团"])
        let now = Date(timeIntervalSince1970: 1_729_872_000) // ~2024-10-25
        let ambiguous = ParentingNaturalInput.classify("游泳课", context: one, now: now)
        XCTAssertTrue(ambiguous.ambiguousIntent)
        let decision = ParentingIntentEscalate.decide(for: ambiguous)
        XCTAssertTrue(decision.shouldEscalate)
        XCTAssertEqual(decision.reason, .ambiguousIntent)

        let two = context(names: ["团团", "朵朵"])
        let unclearChild = ParentingNaturalInput.classify("周五下午5点游泳", context: two, now: now)
        XCTAssertTrue(unclearChild.missing.contains(.child))
        XCTAssertEqual(ParentingIntentEscalate.decide(for: unclearChild).reason, .unclearChild)

        let clear = ParentingNaturalInput.classify("明天下午三点团团去游泳", context: one, now: now)
        XCTAssertFalse(ParentingIntentEscalate.decide(for: clear).shouldEscalate)
        XCTAssertNil(ParentingIntentEscalate.makeRequest(proposal: clear, context: one, now: now))

        let request = ParentingIntentEscalate.makeRequest(proposal: ambiguous, context: one, now: now)
        XCTAssertNotNil(request)
        // Outbound payload uses c-codes and birth-order labels, never the real nickname.
        XCTAssertFalse(request!.redactedText.contains("团团"))
        XCTAssertFalse(request!.childLines.joined().contains("团团"))
        XCTAssertTrue(request!.childLines.contains(where: { $0.hasPrefix("c1=") }))
    }

    func testStubIsNotConfigured() async {
        let stub = ParentingIntentModelStub()
        let req = ParentingIntentModelRequest(redactedText: "c1 明天游泳", childLines: ["c1=老大"], now: Date())
        do {
            _ = try await stub.understand(req)
            XCTFail("stub must not succeed")
        } catch let error as ParentingIntentModelError {
            XCTAssertEqual(error, .notConfigured)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    func testValidateRequiresEvidenceSubstringAndMatchingRequestID() {
        let id = UUID()
        let req = ParentingIntentModelRequest(requestID: id, redactedText: "明天 c1 游泳", childLines: ["c1=老大"], now: Date())
        var ok = ParentingIntentModelProposal(
            requestID: id.uuidString.lowercased(),
            intent: .addArrangement,
            intentConfidence: .medium,
            childRef: .c1,
            childEvidence: "c1",
            title: "游泳",
            dateText: "明天",
            timeText: nil
        )
        XCTAssertTrue(ParentingIntentEscalate.validate(ok, against: req))
        ok.dateText = "下周三"
        XCTAssertFalse(ParentingIntentEscalate.validate(ok, against: req))
    }
}
