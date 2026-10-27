#if DEBUG
import Foundation
import Combine

/// A scripted stand-in for the recognizer: no microphone, no speech permission, no network. Mirrors the contract of
/// `NativeVoiceController` dictation (idle → starting → recording → finishing → idle, `onEnd` once per accepted start,
/// one session at a time, late events after the end are ignored). DEBUG only: unit tests drive it by hand, and the UI
/// fixture (`CATPAW_FAKE_TRANSCRIBER`) plays a timed script so the voice states can be reviewed on a simulator that has
/// no microphone. It is NEVER evidence that real recognition works.
@MainActor final class ScriptedDictationTranscriber: DictationTranscribing {
    typealias End = NativeVoiceController.DictationEnd
    enum Mode: String { case speech, denied, unsupported, silent, interrupted, noisyFail }

    private(set) var dictationPhase: NativeVoiceController.DictationPhase = .idle
    private(set) var starts = 0
    private(set) var lastOnDeviceOnly: Bool?
    private(set) var cancels = 0
    /// When true `stopDictation()` ends the session at once with `.finished` (a final result arrives immediately);
    /// when false the test calls `finish(_:)` itself (e.g. to model the 2.5 s final-result timeout).
    var finishesImmediately = true
    private var onText: (@MainActor (String) -> Void)?
    private var onEnd: (@MainActor (End) -> Void)?
    private var task: Task<Void, Never>?
    private let levels = CurrentValueSubject<Float, Never>(0)
    var audioLevelPublisher: AnyPublisher<Float, Never> { levels.eraseToAnyPublisher() }

    // Timed script (UI fixture). Manual tests leave `timedTranscript` nil and call `emit` / `finish`.
    var mode: Mode = .speech
    /// Play the timed script on start (UI fixture). Unit tests leave this off and drive `emit` / `finish` by hand.
    var autoplay = false
    var timedTranscript: String?
    var stepNanoseconds: UInt64 = 350_000_000

    init() {}

    @discardableResult
    func startDictation(onDeviceOnly: Bool, onText: @escaping @MainActor (String) -> Void, onEnd: @escaping @MainActor (End) -> Void) -> Bool {
        guard dictationPhase == .idle else { return false }
        starts += 1; lastOnDeviceOnly = onDeviceOnly
        self.onText = onText; self.onEnd = onEnd
        switch mode {
        case .denied: dictationPhase = .starting; finish(.denied); return true
        case .unsupported: dictationPhase = .starting; finish(.unsupported); return true
        default: break
        }
        dictationPhase = .recording
        playTimed()
        return true
    }

    func stopDictation() {
        switch dictationPhase {
        case .idle: return
        case .starting: finish(.cancelled)
        case .recording:
            dictationPhase = .finishing; task?.cancel(); levels.send(0)
            if finishesImmediately { finish(.finished) }
        case .finishing: finish(.finished)
        }
    }

    func cancelDictation(_ reason: End) { cancels += 1; if dictationPhase != .idle { finish(reason) } }

    // MARK: Manual control (unit tests)

    /// A partial or final transcript so far (whole text, as the real controller delivers it).
    func emit(_ text: String) { guard dictationPhase == .recording || dictationPhase == .finishing else { return }; onText?(text) }
    /// End the session with `reason`. Idempotent: a second end, or text after the end, does nothing.
    func finish(_ reason: End) {
        guard dictationPhase != .idle else { return }
        task?.cancel(); task = nil; levels.send(0)
        dictationPhase = .idle
        let end = onEnd; onText = nil; onEnd = nil
        end?(reason)
    }

    // MARK: Timed script (UI fixture)

    private func playTimed() {
        guard autoplay else { return }
        let text = timedTranscript ?? "团团周五游泳改到六点了"
        let mode = mode, step = stepNanoseconds
        task = Task { @MainActor [weak self] in
            var tick = 0.0
            let characters = Array(text)
            let chunk = max(1, characters.count / 5)
            var shown = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: step / 3)
                guard let self, self.dictationPhase == .recording else { return }
                tick += 1
                self.levels.send(Float(0.45 + 0.4 * sin(tick * 0.9)))
                if Int(tick) % 3 == 0, mode != .silent, mode != .noisyFail, shown < characters.count {
                    shown = min(characters.count, shown + chunk)
                    self.onText?(String(characters[0..<shown]))
                    if mode == .interrupted && shown >= characters.count / 2 { self.finish(.interrupted); return }
                }
                if mode == .noisyFail && tick > 9 { self.finish(.failed("")); return }
            }
        }
    }
}
#endif
