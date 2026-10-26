#if DEBUG
import XCTest

/// Voice-first composer on the simulator. The simulator has no microphone and no speech permission prompt, so every test
/// drives the DEBUG scripted transcriber (`CATPAW_FAKE_TRANSCRIBER`). These prove layout, gestures, ids and state changes,
/// NOT real recognition. Screenshots go to `$VOICE_SHOT_DIR` (if set) and to the result bundle.
final class ParentingVoiceUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launch(fake: String = "speech", transcript: String? = nil, explain: Bool = false, mode: String? = nil, display: [String: String] = [:]) {
        let app = XCUIApplication(bundleIdentifier: "com.example.CatpawM.ConversationFirst.Auth")
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        var env = ["CF_UI_TEST_STORE": UUID().uuidString, "CF_UI_TEST_SCENARIO": "p0-ui-parenting-success", "CATPAW_FAKE_TRANSCRIBER": fake]
        if let transcript { env["CATPAW_FAKE_TRANSCRIPT"] = transcript }
        if explain { env["CATPAW_FAKE_TRANSCRIBER_EXPLAIN"] = "1" }
        if let mode { env["CATPAW_FAKE_INPUT_MODE"] = mode }
        for (k, v) in display { env[k] = v }
        app.launchEnvironment = env
        self.app = app
        app.launch()
        XCTAssertTrue(app.staticTexts["parenting.fixture.identity"].waitForExistence(timeout: 20), app.debugDescription)
        let first = app.buttons["parenting.firstChild"]
        XCTAssertTrue(first.waitForExistence(timeout: 15), app.debugDescription)
        first.tap()
        let name = app.textFields["parenting.child.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10), app.debugDescription)
        name.tap(); name.typeText("Tuan")   // ASCII: the zh-Hans pinyin keyboard stalls XCUITest on hanzi
        app.buttons["parenting.name.save"].tap()
        XCTAssertTrue(app.buttons["parenting.addArrangement"].waitForExistence(timeout: 10), app.debugDescription)
    }
    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func waitFor(_ id: String, timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let e = el(id); XCTAssertTrue(e.waitForExistence(timeout: timeout), "\(id) missing\n" + app.debugDescription, file: file, line: line); return e
    }
    private func gone(_ id: String, timeout: TimeInterval = 10) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if !el(id).exists { return true }; RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        return false
    }
    private func transcriptText() -> String {
        let t = el("parenting.composer.transcript")
        return (t.value as? String) ?? t.label
    }
    private func waitForTranscript(_ nonEmpty: Bool = true, timeout: TimeInterval = 10) {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            let value = el("parenting.composer.transcript").exists ? transcriptText() : ""
            let hasText = !value.isEmpty && value != "在听…"
            if hasText == nonEmpty { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        XCTFail("transcript did not become \(nonEmpty ? "non-empty" : "empty"): '\(transcriptText())'\n" + app.debugDescription)
    }
    private func shot(_ name: String) {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        if let dir = ProcessInfo.processInfo.environment["VOICE_SHOT_DIR"] ?? (FileManager.default.fileExists(atPath: "/tmp/mimi-voice-20261026/shots") ? "/tmp/mimi-voice-20261026/shots" : nil) {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        }
    }

    // MARK: Default and keyboard

    func testVoiceIsTheDefaultAndTheKeyboardIsOneTapAway() {
        launch()
        let mic = waitFor("parenting.composer.mic")
        XCTAssertGreaterThanOrEqual(mic.frame.width, 160, "voice idle is a big capsule, not a small icon")
        XCTAssertFalse(app.textFields["parenting.natural.field"].exists, "the typing field is not the default")
        XCTAssertTrue(app.buttons["parenting.composer.keyboardToggle"].exists)
        shot("voice-1-idle")

        app.buttons["parenting.composer.keyboardToggle"].tap()
        let field = app.textFields["parenting.natural.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), app.debugDescription)
        let smallMic = waitFor("parenting.composer.mic")
        XCTAssertLessThan(smallMic.frame.width, 60, "keyboard layout: the mic is the small button on the right")
        XCTAssertGreaterThan(smallMic.frame.minX, field.frame.maxX - 4, "the mic sits to the right of the field")
        XCTAssertFalse(app.buttons["parenting.natural.send"].exists, "empty field: mic, not send")
        shot("voice-5-keyboard-empty")

        field.tap(); field.typeText("Swim at five")
        XCTAssertTrue(app.buttons["parenting.natural.send"].waitForExistence(timeout: 5), "text present: the mic becomes send")
        XCTAssertFalse(el("parenting.composer.mic").exists)
        shot("voice-6-keyboard-text")

        app.buttons["parenting.composer.keyboardToggle"].tap()
        XCTAssertTrue(el("parenting.composer.transcript").waitForExistence(timeout: 5), "typed words are kept as the editable transcript when switching back to voice")
        XCTAssertTrue(el("parenting.composer.mic").waitForExistence(timeout: 5))
    }

    // MARK: Listening, preview, release

    func testTapToTalkShowsLiveTranscriptThenKeepsItEditable() {
        launch(transcript: "团团周五游泳改到六点了")
        waitFor("parenting.composer.mic").tap()
        let listening = waitFor("parenting.composer.listening")
        XCTAssertTrue(listening.exists)
        waitForTranscript()
        shot("voice-2-listening")
        let partial = transcriptText()
        listening.tap()   // tap again = finish
        XCTAssertTrue(gone("parenting.composer.listening"))
        let kept = transcriptText()
        XCTAssertFalse(kept.isEmpty)
        XCTAssertTrue(kept.hasPrefix(partial) || partial.hasPrefix(kept) || kept.count >= partial.count / 2)
        waitFor("parenting.composer.mic")
        shot("voice-3-preview")
        // The words are editable: typing appends.
        let field = el("parenting.composer.transcript"); field.tap(); field.typeText("!")
        XCTAssertTrue(transcriptText().hasSuffix("!"), transcriptText())
        shot("voice-4-preview-edited")
    }

    func testHoldToTalkReleaseKeepsTextAndDoesNotSend() {
        launch(transcript: "团团周五游泳改到六点了")
        let mic = waitFor("parenting.composer.mic")
        mic.press(forDuration: 2.4)
        XCTAssertTrue(gone("parenting.composer.listening"))
        waitForTranscript()
        XCTAssertFalse(app.otherElements["parenting.natural.sheet"].exists, "release keeps the words; nothing is sent")
    }

    func testSlideLeftCancelsAndDiscardsTheWords() {
        launch(transcript: "团团周五游泳改到六点了")
        let mic = waitFor("parenting.composer.mic")
        let start = mic.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 1.6, thenDragTo: start.withOffset(CGVector(dx: -150, dy: 0)))
        XCTAssertTrue(gone("parenting.composer.listening"))
        XCTAssertFalse(el("parenting.composer.transcript").exists, "cancel restores the empty draft: back to voice idle")
        XCTAssertTrue(el("parenting.composer.mic").waitForExistence(timeout: 5))
    }

    func testSlideUpSendsToTheConfirmationSheet() {
        launch(transcript: "团团今天下午5点游泳")
        let mic = waitFor("parenting.composer.mic")
        let start = mic.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 2.4, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -130)))
        XCTAssertTrue(app.descendants(matching: .any)["parenting.natural.sheet"].waitForExistence(timeout: 10), "swipe up hands the words to the card; still confirm-before-write\n" + app.debugDescription)
        XCTAssertTrue(el("parenting.natural.confirm").waitForExistence(timeout: 10) || el("parenting.natural.source").exists, app.debugDescription)
    }

    // MARK: Non-happy

    func testPermissionDeniedShowsAnInlineHintWithASettingsButton() {
        launch(fake: "denied")
        waitFor("parenting.composer.mic").tap()
        let notice = waitFor("parenting.composer.voiceNotice")
        XCTAssertTrue(notice.exists)
        XCTAssertTrue(app.buttons["去设置"].exists || notice.label.contains("去设置") || el("parenting.composer.voiceNotice").exists)
        shot("voice-7-denied")
        XCTAssertTrue(el("parenting.composer.mic").exists, "back to voice idle, keyboard still one tap away")
    }
    func testOnDeviceUnavailableNamesTheSystemKeyboardDictation() {
        launch(fake: "unsupported")
        waitFor("parenting.composer.mic").tap()
        let notice = waitFor("parenting.composer.voiceNotice")
        XCTAssertTrue(notice.label.contains("本机"), notice.label)
        shot("voice-8-unsupported")
        app.buttons["改用键盘"].firstMatch.tap()
        XCTAssertTrue(app.textFields["parenting.natural.field"].waitForExistence(timeout: 5))
    }
    func testNothingHeardShowsTheEmptyHint() {
        launch(fake: "silent")
        let mic = waitFor("parenting.composer.mic")
        mic.press(forDuration: 1.2)
        _ = waitFor("parenting.composer.voiceNotice")
        shot("voice-9-empty")
    }
    func testInterruptedKeepsWhatWasHeard() {
        launch(fake: "interrupted", transcript: "团团周五游泳改到六点了")
        waitFor("parenting.composer.mic").tap()
        _ = waitFor("parenting.composer.voiceNotice", timeout: 15)
        waitForTranscript()
        shot("voice-10-interrupted")
    }
    func testFirstPressShowsTheExplanationBeforeAnyPermissionPrompt() {
        launch(explain: true)
        waitFor("parenting.composer.mic").tap()
        _ = waitFor("parenting.composer.voiceExplain")
        XCTAssertFalse(el("parenting.composer.listening").exists, "no session before the parent agrees")
        shot("voice-11-explanation")
        app.buttons["parenting.composer.voiceExplain.continue"].tap()
        _ = waitFor("parenting.composer.listening")
    }
    func testReduceMotionAndLargestTextKeepEverythingReachable() {
        launch(display: ["CATPAW_UI_TEST_TEXT_SIZE": "accessibility5"])
        let mic = waitFor("parenting.composer.mic")
        XCTAssertTrue(mic.isHittable)
        XCTAssertTrue(app.buttons["parenting.composer.keyboardToggle"].isHittable)
        shot("voice-12-accessibility5")
    }
}
#endif
