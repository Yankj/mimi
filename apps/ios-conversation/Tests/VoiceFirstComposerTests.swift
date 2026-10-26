#if DEBUG
import Foundation
import XCTest
@testable import cat

/// Voice-first composer (S1b). Everything here runs on `ScriptedDictationTranscriber`: no microphone, no speech permission,
/// no network. It proves the state machine, the draft hand-off and the copy/permission rules, NOT real recognition quality.
@MainActor final class VoiceFirstComposerTests: XCTestCase {
    @MainActor final class SpySink: DictationDraftSink {
        var draft = ""
        private(set) var edits: [String] = []
        /// Models a sink that normalizes (e.g. a length cap).
        var cap: Int?
        func editDraft(_ text: String) { edits.append(text); draft = cap.map { String(text.prefix($0)) } ?? text }
    }

    private var fake: ScriptedDictationTranscriber!
    private var sink: SpySink!
    private var dictation: ComposerDictation!
    private var sent = 0

    override func setUp() {
        super.setUp()
        fake = ScriptedDictationTranscriber(); sink = SpySink(); sent = 0
        dictation = makeDictation(onDeviceOnly: true, reports: true)
    }
    private func makeDictation(onDeviceOnly: Bool, reports: Bool) -> ComposerDictation {
        let d = ComposerDictation(engine: fake, onDeviceOnly: onDeviceOnly)
        d.permissionDenied = { false }
        d.reportsSilenceAndInterruption = reports
        d.focusInput = {}
        d.attach(sink)
        d.onSend = { [unowned self] in sent += 1 }
        return d
    }

    // MARK: DictationPolicy / transcript (pure)

    func testJoinPutsNoSpaceBetweenChineseAndOneSpaceBetweenLatinWords() {
        XCTAssertEqual(DictationPolicy.join("记一下，", "团团游泳"), "记一下，团团游泳")
        XCTAssertEqual(DictationPolicy.join("团团", "swim at 5"), "团团swim at 5")
        XCTAssertEqual(DictationPolicy.join("hello", "world"), "hello world")
        XCTAssertEqual(DictationPolicy.join("hello ", "world"), "hello world")
        XCTAssertEqual(DictationPolicy.join("hello", "，world"), "hello，world")
        XCTAssertEqual(DictationPolicy.join("", "团团"), "团团")
        XCTAssertEqual(DictationPolicy.join("团团", ""), "团团")
    }
    func testTranscriptKeepsCommittedSegmentsWhenTheRecognizerRestarts() {
        var t = DictationTranscript()
        t.update("团团周五"); XCTAssertEqual(t.text, "团团周五")
        t.commit(); t.update("游泳改到六点"); XCTAssertEqual(t.text, "团团周五游泳改到六点")
        t.update("游泳改到六点了"); XCTAssertEqual(t.text, "团团周五游泳改到六点了", "a partial replaces only the current segment")
    }
    func testOnDeviceOnlyTierNeverAllowsTheServerTier() {
        XCTAssertTrue(DictationEngineTier.serverRecognizer.usesNetwork)
        for tier in [DictationEngineTier.speechTranscriber, .dictationTranscriber, .onDeviceRecognizer] { XCTAssertFalse(tier.usesNetwork) }
        XCTAssertEqual(DictationEngineTier.choose(analyzerOS: false, speechTranscriberReady: false, dictationTranscriberReady: false,
                                                  recognizerAvailable: true, recognizerOnDevice: false), .serverRecognizer,
                       "the tier chooser itself still offers the server; NativeVoiceController refuses it when onDeviceOnly")
    }

    // MARK: Hold-to-talk through the sink

    func testLivePartialsFillTheDraftAfterWhatWasAlreadyTyped() {
        sink.draft = "记一下，"
        dictation.beginHold()
        XCTAssertTrue(dictation.dictating); XCTAssertEqual(dictation.mode, .hold)
        fake.emit("团团"); XCTAssertEqual(sink.draft, "记一下，团团")
        fake.emit("团团游泳"); XCTAssertEqual(sink.draft, "记一下，团团游泳")
        XCTAssertEqual(fake.lastOnDeviceOnly, true, "the parenting composer starts on-device only")
    }
    func testReleaseKeepsTheTextEditableAndDoesNotSend() {
        dictation.beginHold(); fake.emit("团团游泳")
        dictation.endHold()
        XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "团团游泳"); XCTAssertEqual(sent, 0)
        XCTAssertEqual(fake.dictationPhase, .idle)
        XCTAssertNil(dictation.notice)
    }
    func testSlideUpSendsOnceAfterTheFinalTextLanded() {
        dictation.beginHold(); fake.emit("团团游泳")
        dictation.updateHold(CGSize(width: 0, height: -ComposerDictation.sendThreshold - 10)); XCTAssertEqual(dictation.zone, .send)
        dictation.endHold()
        XCTAssertEqual(sent, 1); XCTAssertEqual(sink.draft, "团团游泳")
    }
    func testSlideUpWithNothingSaidSendsNothing() {
        dictation.beginHold()
        dictation.updateHold(CGSize(width: 0, height: -200)); dictation.endHold()
        XCTAssertEqual(sent, 0)
    }
    func testSlideLeftCancelsAndRestoresThePreviousDraftWithoutAHint() {
        sink.draft = "先写的"
        dictation.beginHold(); fake.emit("要丢掉的话")
        dictation.updateHold(CGSize(width: -ComposerDictation.cancelThreshold - 10, height: -200)); XCTAssertEqual(dictation.zone, .cancel, "left beats up")
        dictation.endHold()
        XCTAssertEqual(sink.draft, "先写的"); XCTAssertEqual(sent, 0); XCTAssertNil(dictation.notice, "a deliberate cancel is not an error")
        XCTAssertFalse(dictation.dictating)
    }

    // MARK: Tap-to-toggle, typing, one owner

    func testTapTogglesOnAndOff() {
        dictation.toggle(); XCTAssertTrue(dictation.dictating); XCTAssertEqual(dictation.mode, .tap)
        fake.emit("你好"); dictation.toggle()
        XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "你好")
    }
    func testTypingDuringDictationTakesTheDraftOver() {
        dictation.toggle(); fake.emit("团团")
        sink.draft = "团团手打"   // the parent typed into the transcript
        fake.emit("团团游泳")
        XCTAssertFalse(dictation.dictating, "dictation hands the composer back")
        XCTAssertEqual(sink.draft, "团团手打", "later partials never overwrite typed text")
        XCTAssertEqual(fake.dictationPhase, .idle)
    }
    func testASecondStartWhileListeningIsRejected() {
        dictation.toggle(); dictation.beginHold()
        XCTAssertEqual(fake.starts, 1)
    }
    func testASinkThatNormalizesIsFollowedNotFought() {
        sink.cap = 4
        dictation.toggle(); fake.emit("团团周五游泳")
        XCTAssertEqual(sink.draft, "团团周五")
        fake.emit("团团周五游泳改期")   // lastWritten is what the sink stored, so this is not mistaken for typing
        XCTAssertTrue(dictation.dictating)
    }
    func testBackgroundStopKeepsTheWordsHeardSoFar() {
        dictation.toggle(); fake.emit("团团游泳")
        dictation.stopNow()
        XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "团团游泳"); XCTAssertEqual(fake.cancels, 1)
    }

    // MARK: Non-happy endings

    func testPermissionDeniedShowsTheHintAndNeverStartsTheEngine() {
        dictation.permissionDenied = { true }
        dictation.beginHold()
        XCTAssertEqual(dictation.notice, .permissionDenied); XCTAssertFalse(dictation.dictating); XCTAssertEqual(fake.starts, 0)
    }
    func testPermissionDeniedDuringStartEndsCleanly() {
        fake.mode = .denied
        dictation.toggle()
        XCTAssertEqual(dictation.notice, .permissionDenied); XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "")
    }
    func testOnDeviceUnavailableEndsUnsupportedWithoutAFallback() {
        fake.mode = .unsupported
        dictation.toggle()
        XCTAssertEqual(dictation.notice, .unsupported); XCTAssertFalse(dictation.dictating)
        XCTAssertEqual(fake.lastOnDeviceOnly, true)
    }
    func testNothingHeardIsAHintInParentingButSilentInChat() {
        dictation.beginHold(); dictation.endHold()
        XCTAssertEqual(dictation.notice, .empty)
        let chat = makeDictation(onDeviceOnly: false, reports: false)
        chat.beginHold(); chat.endHold()
        XCTAssertNil(chat.notice, "chat dictation behavior is unchanged")
        XCTAssertEqual(fake.lastOnDeviceOnly, false, "chat keeps the tiered engine")
    }
    func testNoisyFailureWithoutTextShowsTheFailureHint() {
        dictation.toggle(); fake.finish(.failed("noise"))
        XCTAssertEqual(dictation.notice, .failed); XCTAssertFalse(dictation.dictating)
    }
    func testFailureAfterTextKeepsTheTextAndStaysQuiet() {
        dictation.toggle(); fake.emit("团团游泳"); fake.finish(.failed("lost"))
        XCTAssertEqual(sink.draft, "团团游泳"); XCTAssertNil(dictation.notice)
    }
    func testInterruptionKeepsTextAndSaysSoInParentingOnly() {
        dictation.toggle(); fake.emit("团团游泳"); fake.finish(.interrupted)
        XCTAssertEqual(dictation.notice, .interrupted); XCTAssertEqual(sink.draft, "团团游泳"); XCTAssertFalse(dictation.dictating)
        let chat = makeDictation(onDeviceOnly: false, reports: false)
        chat.toggle(); fake.finish(.interrupted)
        XCTAssertNil(chat.notice)
    }
    func testInterruptedSessionNeverAutoSends() {
        dictation.beginHold(); fake.emit("团团游泳")
        dictation.updateHold(CGSize(width: 0, height: -200)); dictation.confirmAndSend()
        fake.finish(.interrupted)
        XCTAssertEqual(sent, 0)
    }
    func testFinalResultTimeoutAfterReleaseStillEndsAndKeepsText() {
        fake.finishesImmediately = false
        dictation.beginHold(); fake.emit("团团游泳"); dictation.endHold()
        XCTAssertEqual(fake.dictationPhase, .finishing)
        fake.finish(.timedOut)
        XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "团团游泳"); XCTAssertNil(dictation.notice)
    }
    func testLateTextAfterTheEndIsIgnored() {
        dictation.toggle(); fake.emit("团团"); dictation.toggle()
        fake.emit("迟到的字")
        XCTAssertEqual(sink.draft, "团团")
    }

    // MARK: Parenting model as the sink

    func testTheSameControllerFeedsTheParentingNaturalInputDraft() {
        let model = ParentingNaturalInputModel()
        let d = makeDictation(onDeviceOnly: true, reports: true); d.attach(model)
        d.beginHold(); fake.emit("团团今天下午5点游泳")
        XCTAssertEqual(model.draft, "团团今天下午5点游泳"); XCTAssertTrue(model.canSubmit)
        d.endHold()
        XCTAssertEqual(model.draft, "团团今天下午5点游泳", "words stay until the parent sends; nothing is submitted by dictation")
        XCTAssertEqual(model.phase, .idle)
    }

    // MARK: ParentingVoiceState

    private func state(undetermined: Bool, defaults: UserDefaults? = nil) -> ParentingVoiceState {
        let suite = "voice-test-\(UUID().uuidString)"
        let d = defaults ?? UserDefaults(suiteName: suite)!
        return ParentingVoiceState(dictation: dictation, defaults: d, permissionsUndetermined: { undetermined })
    }
    func testPresentationMatrix() {
        typealias P = ParentingVoicePresentation
        XCTAssertEqual(P.make(mode: .voice, dictating: false, draftIsEmpty: true), .voiceIdle)
        XCTAssertEqual(P.make(mode: .voice, dictating: true, draftIsEmpty: true), .listening)
        XCTAssertEqual(P.make(mode: .voice, dictating: true, draftIsEmpty: false), .listening)
        XCTAssertEqual(P.make(mode: .voice, dictating: false, draftIsEmpty: false), .preview)
        XCTAssertEqual(P.make(mode: .keyboard, dictating: false, draftIsEmpty: true), .keyboard)
        XCTAssertEqual(P.make(mode: .keyboard, dictating: false, draftIsEmpty: false), .keyboard)
        XCTAssertEqual(P.make(mode: .keyboard, dictating: true, draftIsEmpty: true), .keyboardListening)
    }
    func testTheComposerDefaultsToVoice() {
        XCTAssertEqual(state(undetermined: false).mode, .voice)
    }
    func testFirstMicPressShowsTheExplanationAndDoesNotTouchThePermissionPrompts() {
        let s = state(undetermined: true)
        XCTAssertFalse(s.requestStart(hold: true))
        XCTAssertTrue(s.explaining); XCTAssertEqual(fake.starts, 0, "no engine start, hence no system prompt, before the sentence")
        XCTAssertFalse(s.requestStart(hold: false), "a second press while the sentence is up does nothing")
        s.acceptExplanation()
        XCTAssertFalse(s.explaining); XCTAssertEqual(fake.starts, 1); XCTAssertTrue(dictation.dictating)
    }
    func testTheExplanationIsShownOnlyOnce() {
        let defaults = UserDefaults(suiteName: "voice-test-\(UUID().uuidString)")!
        let first = state(undetermined: true, defaults: defaults); first.requestStart(hold: false); first.acceptExplanation()
        dictation.stopNow()
        let again = state(undetermined: true, defaults: defaults)
        XCTAssertTrue(again.requestStart(hold: false)); XCTAssertFalse(again.explaining)
        XCTAssertEqual(fake.starts, 2)
    }
    func testNoExplanationWhenThePermissionIsAlreadyDecided() {
        let s = state(undetermined: false)
        XCTAssertTrue(s.requestStart(hold: false)); XCTAssertFalse(s.explaining); XCTAssertEqual(fake.starts, 1)
    }
    func testDecliningTheExplanationSwitchesToTheKeyboard() {
        let s = state(undetermined: true); s.requestStart(hold: false); s.declineExplanation()
        XCTAssertEqual(s.mode, .keyboard); XCTAssertFalse(s.explaining); XCTAssertEqual(fake.starts, 0)
    }
    func testSwitchingToTheKeyboardWhileListeningStopsTheMicAndKeepsTheWords() {
        let s = state(undetermined: false); s.requestStart(hold: false); fake.emit("团团游泳")
        s.toggleMode()
        XCTAssertEqual(s.mode, .keyboard); XCTAssertFalse(dictation.dictating); XCTAssertEqual(sink.draft, "团团游泳")
        s.toggleMode(); XCTAssertEqual(s.mode, .voice)
    }
    func testTheParentingDictationIsOnDeviceOnlyAndReportsSilence() {
        let real = ParentingVoiceState.makeDictation()
        XCTAssertTrue(real.onDeviceOnly); XCTAssertTrue(real.reportsSilenceAndInterruption)
        XCTAssertTrue(real.engine is NativeVoiceController)
        XCTAssertFalse(ComposerDictation.shared.onDeviceOnly, "chat dictation keeps the tiered engine")
    }

    // MARK: Guards for the existing "deaf dictation" protections

    func testNativeVoiceControllerKeepsItsProtectionsAndTheOnDeviceSwitchIsOptIn() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Sources/Voice/NativeVoiceController.swift")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(NativeVoiceController.recognitionSessionMode.rawValue, "AVAudioSessionModeDefault")
        XCTAssertFalse(text.contains("mode: .voiceChat"))
        XCTAssertTrue(text.contains("startDictation(onDeviceOnly: false, onText: onText, onEnd: onEnd)"), "the old entry point stays tiered (chat unchanged)")
        XCTAssertTrue(text.contains("if networkTierForbidden && dictationTier.usesNetwork"))
        XCTAssertTrue(text.contains("dictationOnDeviceOnly = false\n        dictationEpoch += 1") == false, "the switch is set per session, before the epoch bump, and reset in endDictation")
        XCTAssertTrue(text.contains("dictationOnDeviceOnly = false\n        let onEnd") || text.contains("dictationOnEnd = nil; dictationOnDeviceOnly = false"))
    }
}
#endif
