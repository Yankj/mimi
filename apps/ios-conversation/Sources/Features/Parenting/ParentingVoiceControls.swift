import SwiftUI
import Combine
#if os(iOS)
import AVFoundation
import Speech
import UIKit
#endif

// MARK: - State (voice-first composer)

/// Which input the composer shows. Voice is the default; the keyboard is one tap away; the manual form is not here at all.
enum ParentingInputMode: Equatable { case voice, keyboard }

/// What the composer row looks like right now (pure: derived from mode, dictation and draft; unit-tested).
enum ParentingVoicePresentation: Equatable {
    /// Big hold-to-talk capsule, nothing said yet.
    case voiceIdle
    /// Capsule shows the live level and timer; the transcript fills the editable text above it.
    case listening
    /// Words are in the editable transcript, the mic is not running: edit, speak more, or send.
    case preview
    /// Text field with the mic on the right (becomes send once there is text).
    case keyboard
    /// Keyboard layout while the mic is running (words land in the field).
    case keyboardListening

    static func make(mode: ParentingInputMode, dictating: Bool, draftIsEmpty: Bool) -> ParentingVoicePresentation {
        switch (mode, dictating) {
        case (.voice, true): return .listening
        case (.voice, false): return draftIsEmpty ? .voiceIdle : .preview
        case (.keyboard, true): return .keyboardListening
        case (.keyboard, false): return .keyboard
        }
    }
}

/// The last ~1.4 s of input level for the bars. Subscribes ONCE (a view-level `onReceive` on a computed publisher re-subscribed on
/// every render, and a replaying subject then re-rendered the view forever).
@MainActor final class ParentingLevelHistory: ObservableObject {
    @Published private(set) var samples: [Float] = Array(repeating: 0, count: 28)
    private var cancellable: AnyCancellable?
    init(source: AnyPublisher<Float, Never>) {
        cancellable = source.dropFirst().sink { [weak self] level in
            guard let self else { return }
            self.samples.removeFirst(); self.samples.append(level)
        }
    }
}

/// Owns the voice-first state of one composer: the input mode, the first-press explanation, and its own dictation
/// session (on-device only, never shared with chat). The draft it fills is `ParentingNaturalInputModel.draft`.
@MainActor final class ParentingVoiceState: ObservableObject {
    static let explainedKey = "mimi.voice.permissionExplained"
    @Published var mode: ParentingInputMode = .voice
    /// The in-app sentence shown BEFORE the first system permission prompt.
    @Published private(set) var explaining = false
    let dictation: ComposerDictation
    let levels: ParentingLevelHistory
    private let defaults: UserDefaults
    /// True while the system permission prompts have not been answered yet. Injectable for tests.
    var permissionsUndetermined: () -> Bool

    init(dictation: ComposerDictation? = nil, defaults: UserDefaults = .standard, permissionsUndetermined: (() -> Bool)? = nil) {
        let made = dictation ?? Self.makeDictation()
        self.dictation = made
        self.levels = ParentingLevelHistory(source: made.engine.audioLevelPublisher)
        self.defaults = defaults
        self.permissionsUndetermined = permissionsUndetermined ?? Self.systemPermissionsUndetermined
        #if DEBUG
        if self.dictation.engine is ScriptedDictationTranscriber, ProcessInfo.processInfo.environment["CATPAW_FAKE_TRANSCRIBER_EXPLAIN"] == "1" {
            self.permissionsUndetermined = { true }
        }
        if ProcessInfo.processInfo.environment["CATPAW_FAKE_INPUT_MODE"] == "keyboard" { mode = .keyboard }
        #endif
    }

    static func systemPermissionsUndetermined() -> Bool {
        #if os(iOS)
        return AVAudioSession.sharedInstance().recordPermission == .undetermined || SFSpeechRecognizer.authorizationStatus() == .notDetermined
        #else
        return false
        #endif
    }

    /// On-device only; reports empty results and interruptions; falls back to a scripted engine only in DEBUG UI tests.
    static func makeDictation() -> ComposerDictation {
        #if DEBUG
        if let raw = ProcessInfo.processInfo.environment["CATPAW_FAKE_TRANSCRIBER"] {
            let fake = ScriptedDictationTranscriber()
            fake.mode = ScriptedDictationTranscriber.Mode(rawValue: raw == "1" ? "speech" : raw) ?? .speech
            fake.autoplay = true
            fake.timedTranscript = ProcessInfo.processInfo.environment["CATPAW_FAKE_TRANSCRIPT"]
            let dictation = ComposerDictation(engine: fake, onDeviceOnly: true)
            dictation.permissionDenied = { false }
            dictation.reportsSilenceAndInterruption = true
            return dictation
        }
        #endif
        let dictation = ComposerDictation(voice: .shared, onDeviceOnly: true)
        dictation.reportsSilenceAndInterruption = true
        return dictation
    }

    func presentation(draftIsEmpty: Bool) -> ParentingVoicePresentation {
        .make(mode: mode, dictating: dictation.dictating, draftIsEmpty: draftIsEmpty)
    }

    /// Mic gesture entry. The very first press only shows the explanation sentence (no system prompt yet); returns false then.
    @discardableResult
    func requestStart(hold: Bool) -> Bool {
        if explaining { return false }
        if permissionsUndetermined() && !defaults.bool(forKey: Self.explainedKey) { explaining = true; return false }
        if hold { dictation.beginHold() } else { dictation.toggle() }
        return true
    }
    /// 继续: remember it, then start as tap-to-talk (the finger is long gone).
    func acceptExplanation() {
        defaults.set(true, forKey: Self.explainedKey); explaining = false
        dictation.toggle()
    }
    func declineExplanation() { explaining = false; mode = .keyboard }

    func setMode(_ next: ParentingInputMode) {
        guard next != mode else { return }
        dictation.stopNow()
        explaining = false
        mode = next
    }
    func toggleMode() { setMode(mode == .voice ? .keyboard : .voice) }
}

// MARK: - Press gesture shared by the capsule and the small mic

/// Tap = toggle, press and hold ≥ 0.3 s = hold-to-talk (up = send, left = cancel). Same thresholds as chat (`ComposerDictation`).
/// The modified view stays mounted while listening, so the finger is never lost.
private struct ParentingHoldToTalkGesture: ViewModifier {
    @ObservedObject var state: ParentingVoiceState
    @ObservedObject var dictation: ComposerDictation
    @State private var pressStart: Date?
    @State private var holding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var moved = false

    func body(content: Content) -> some View {
        content.gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { value in
                    if pressStart == nil {
                        pressStart = .now; moved = false
                        let alreadyDictating = dictation.dictating
                        holdTask?.cancel()
                        holdTask = Task { @MainActor in
                            try? await Task.sleep(nanoseconds: ComposerDictation.holdDelay)
                            guard !Task.isCancelled, pressStart != nil, !alreadyDictating, !moved else { return }
                            holding = state.requestStart(hold: true)
                        }
                    }
                    if holding { dictation.updateHold(value.translation) }
                    else if abs(value.translation.width) > 12 || abs(value.translation.height) > 12 { moved = true }
                }
                .onEnded { value in
                    holdTask?.cancel(); holdTask = nil
                    defer { pressStart = nil; holding = false; moved = false }
                    if holding { dictation.updateHold(value.translation); dictation.endHold(); return }
                    if !moved { if dictation.dictating { dictation.toggle() } else { state.requestStart(hold: false) } }
                }
        )
    }
}

// MARK: - Views

/// The row that replaces the single text field: keyboard toggle · (capsule | field) · (send | mic).
/// `field` and `sendButton` are the composer's own views, passed in unchanged so their ids and behaviour stay as they were.
@MainActor struct ParentingVoiceInputRow<Field: View, SendButton: View>: View {
    @ObservedObject var input: ParentingNaturalInputModel
    @ObservedObject var state: ParentingVoiceState
    let busy: Bool
    var focused: FocusState<Bool>.Binding
    let field: Field
    let sendButton: SendButton
    @ObservedObject private var dictation: ComposerDictation
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scene

    init(input: ParentingNaturalInputModel, state: ParentingVoiceState, busy: Bool, focused: FocusState<Bool>.Binding,
         @ViewBuilder field: () -> Field, @ViewBuilder sendButton: () -> SendButton) {
        self.input = input; self.state = state; self.busy = busy; self.focused = focused
        self.field = field(); self.sendButton = sendButton()
        _dictation = ObservedObject(wrappedValue: state.dictation)
    }
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var presentation: ParentingVoicePresentation { state.presentation(draftIsEmpty: input.draft.isEmpty) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ParentingVoiceNotice(state: state, dictation: dictation)
            if state.explaining { ParentingVoiceExplanation(state: state) }
            if presentation == .listening || presentation == .preview { transcript }
            HStack(alignment: .bottom, spacing: 8) {
                ParentingKeyboardToggle(state: state, focused: focused)
                switch presentation {
                case .voiceIdle, .listening, .preview:
                    ParentingHoldToTalkCapsule(state: state, dictation: dictation, voice: dictation.voice, meter: state.levels)
                    if presentation == .preview && input.canSubmit { sendButton }
                case .keyboard, .keyboardListening:
                    field
                    if input.canSubmit && presentation == .keyboard { sendButton } else { ParentingSmallMic(state: state, dictation: dictation) }
                }
            }
        }
        .onAppear {
            dictation.attach(input)
            dictation.focusInput = { focused.wrappedValue = true }
            dictation.voice.language = AppLanguage(locale: locale)
        }
        .onChange(of: locale.identifier) { _, _ in dictation.voice.language = AppLanguage(locale: locale) }
        // Only a real background stops (permission alerts make the scene .inactive).
        .onChange(of: scene) { _, value in if value == .background { dictation.stopNow() } }
        .onDisappear { dictation.stopNow() }
        .animation(.snappy, value: presentation)
        .animation(.snappy, value: state.explaining)
    }

    /// Live partial transcript, then the editable words. Typing here takes the draft over from dictation.
    private var transcript: some View {
        HStack(alignment: .top, spacing: 8) {
            TextField(ui("在听…"), text: $input.draft, axis: .vertical)
                .lineLimit(1...4)
                .focused(focused)
                .padding(.horizontal, 14).padding(.vertical, 11)
                .accessibilityIdentifier("parenting.composer.transcript")
            if !input.draft.isEmpty {
                Button { dictation.stopNow(); input.draft = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(catColors.secondary).frame(width: 36, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ui("清除"))
                .accessibilityIdentifier("parenting.composer.transcript.clear")
            }
        }
        .background(catColors.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(catColors.hairline, lineWidth: 0.5))
    }
}

/// 键盘 ⇄ 语音. Same id in both directions; the label says where it goes.
@MainActor private struct ParentingKeyboardToggle: View {
    @ObservedObject var state: ParentingVoiceState
    var focused: FocusState<Bool>.Binding
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    var body: some View {
        Button {
            let toKeyboard = state.mode == .voice
            state.toggleMode()
            if toKeyboard { Task { @MainActor in try? await Task.sleep(nanoseconds: 120_000_000); focused.wrappedValue = true } }
            else { focused.wrappedValue = false }
        } label: {
            Image(systemName: state.mode == .voice ? "keyboard" : "waveform")
                .font(catTypography.Role.body.font.weight(.medium))
                .foregroundStyle(catColors.ink)
                .frame(width: 44, height: 44)
                .background(catColors.fillSelected, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(ui(state.mode == .voice ? "切换到键盘输入" : "切换到语音输入"))
        .accessibilityIdentifier("parenting.composer.keyboardToggle")
    }
}

/// Voice-idle: the big capsule. Listening: live bars, timer and the slide hints. Same view, so the hold is not interrupted.
@MainActor private struct ParentingHoldToTalkCapsule: View {
    @ObservedObject var state: ParentingVoiceState
    @ObservedObject var dictation: ComposerDictation
    @ObservedObject var voice: NativeVoiceController
    @ObservedObject var meter: ParentingLevelHistory
    @State private var elapsed = 0
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var hold: Bool { dictation.mode == .hold }

    var body: some View {
        ZStack {
            if dictation.dictating { listening } else { idle }
        }
        .frame(maxWidth: .infinity, minHeight: 52)
        .background(background, in: Capsule())
        .overlay(Capsule().stroke(catColors.hairline, lineWidth: dictation.dictating ? 0 : 0.5))
        .contentShape(Capsule())
        .modifier(ParentingHoldToTalkGesture(state: state, dictation: dictation))
        .task(id: dictation.dictating) {
            elapsed = 0
            guard dictation.dictating else { return }
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000_000); elapsed += 1 }
        }
        // VoiceOver: tap only. Holding is never required; cancel / send are explicit actions while listening.
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(ui(dictation.dictating ? "停止说话" : "按住说话"))
        .accessibilityValue(dictation.dictating ? timer : "")
        .accessibilityHint(dictation.dictating ? "" : ui("双击开始说话，再双击完成"))
        .accessibilityAction { if dictation.dictating { dictation.toggle() } else { state.requestStart(hold: false) } }
        .accessibilityActions {
            if dictation.dictating {
                Button(ui("取消")) { dictation.cancel() }
                Button(ui("发送")) { dictation.confirmAndSend() }
            }
        }
        .accessibilityIdentifier(dictation.dictating ? "parenting.composer.listening" : "parenting.composer.mic")
    }

    private var timer: String { String(format: "%d:%02d", elapsed / 60, elapsed % 60) }
    private var background: Color {
        guard dictation.dictating else { return catColors.card }
        switch dictation.zone {
        case .cancel: return catColors.ink
        case .send: return catColors.calmCTATop
        case .none: return catBrand.paw
        }
    }

    private var idle: some View {
        HStack(spacing: 8) {
            Image(systemName: "mic.fill").accessibilityHidden(true)
            Text(ui("按住说话"))
        }
        .font(catTypography.Role.body.font.weight(.semibold))
        .foregroundStyle(catColors.ink)
    }

    private var listening: some View {
        VStack(spacing: 3) {
            HStack(spacing: 10) {
                if voice.preparingSpeechModel {
                    Text(ui("正在准备语音模型…"))
                } else {
                    ParentingLevelBars(levels: meter.samples, still: reduceMotion).frame(height: 22)
                    Text(timer).monospacedDigit()
                }
            }
            Text(hint).font(catTypography.Role.caption.font).opacity(0.9)
        }
        .font(catTypography.Role.body.font.weight(.semibold))
        .foregroundStyle(catColors.sendIcon)
        .padding(.horizontal, 16).padding(.vertical, 6)
    }
    private var hint: String {
        guard hold else { return ui("再点一下完成") }
        switch dictation.zone {
        case .send: return ui("松开发送")
        case .cancel: return ui("松开取消")
        case .none: return ui("上滑发送 · 左滑取消")
        }
    }
}

/// Reduce Motion: a fixed set of bars (the timer carries the "alive" signal). Otherwise the last ~1.4 s of input level.
private struct ParentingLevelBars: View {
    let levels: [Float]
    let still: Bool
    var body: some View {
        Canvas { context, size in
            let values: [Float] = still ? [0.35, 0.6, 0.45, 0.8, 0.5, 0.65, 0.4] : levels
            let count = values.count, step = size.width / CGFloat(max(count, 1)), bar: CGFloat = still ? 4 : 3
            for (i, level) in values.enumerated() {
                let h = max(bar, CGFloat(level) * size.height)
                let rect = CGRect(x: CGFloat(i) * step + (step - bar) / 2, y: (size.height - h) / 2, width: bar, height: h)
                context.fill(Path(roundedRect: rect, cornerRadius: bar / 2), with: .color(catColors.sendIcon.opacity(0.55 + Double(level) * 0.45)))
            }
        }
        .frame(width: still ? 56 : 112)
        .accessibilityHidden(true)
    }
}

/// Keyboard layout, empty field: the mic sits on the right (tap = toggle, hold = hold-to-talk). While listening it shows the wave.
@MainActor private struct ParentingSmallMic: View {
    @ObservedObject var state: ParentingVoiceState
    @ObservedObject var dictation: ComposerDictation
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    var body: some View {
        Image(systemName: dictation.dictating ? "waveform" : "mic.fill")
            .font(catTypography.Role.body.font.weight(.semibold))
            .foregroundStyle(dictation.dictating ? catColors.sendIcon : catColors.ink)
            .symbolEffect(.variableColor.iterative, isActive: dictation.dictating && !reduceMotion)
            .frame(width: 44, height: 44)
            .background(dictation.dictating ? catBrand.paw : catColors.fillSelected, in: Circle())
            .contentShape(Circle())
            .modifier(ParentingHoldToTalkGesture(state: state, dictation: dictation))
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits(.isButton)
            .accessibilityLabel(ui(dictation.dictating ? "停止说话" : "说话"))
            .accessibilityAction { if dictation.dictating { dictation.toggle() } else { state.requestStart(hold: false) } }
            .accessibilityActions {
                if dictation.dictating {
                    Button(ui("取消")) { dictation.cancel() }
                    Button(ui("发送")) { dictation.confirmAndSend() }
                }
            }
            .accessibilityIdentifier(dictation.dictating ? "parenting.composer.listening" : "parenting.composer.mic")
    }
}

/// The sentence BEFORE the first system permission prompt.
@MainActor private struct ParentingVoiceExplanation: View {
    @ObservedObject var state: ParentingVoiceState
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(ui("小咪的语音识别只在你的手机上完成，不会上传。接下来 iOS 会请你允许麦克风和语音识别。"))
                .font(catTypography.Role.caption.font).foregroundStyle(catColors.ink)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Button(ui("继续")) { state.acceptExplanation() }
                    .font(catTypography.Role.caption.font.weight(.semibold)).foregroundStyle(catColors.actionText)
                    .accessibilityIdentifier("parenting.composer.voiceExplain.continue")
                Button(ui("改用键盘")) { state.declineExplanation() }
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                    .accessibilityIdentifier("parenting.composer.voiceExplain.keyboard")
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(catColors.fillSelected, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.composer.voiceExplain")
    }
}

/// Inline hint for every non-happy state. Never a modal alert; the keyboard is always the way out.
@MainActor private struct ParentingVoiceNotice: View {
    @ObservedObject var state: ParentingVoiceState
    @ObservedObject var dictation: ComposerDictation
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    var body: some View {
        if let notice = dictation.notice {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(message(notice)).font(catTypography.Role.caption.font).foregroundStyle(catColors.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                switch notice {
                case .permissionDenied:
                    Button(ui("去设置")) { ComposerDictation.openSettings() }
                        .font(catTypography.Role.caption.font.weight(.semibold)).foregroundStyle(catColors.actionText)
                case .unsupported:
                    Button(ui("改用键盘")) { dictation.notice = nil; state.setMode(.keyboard) }
                        .font(catTypography.Role.caption.font.weight(.semibold)).foregroundStyle(catColors.actionText)
                default: EmptyView()
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(catColors.fillSelected, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .onTapGesture { dictation.notice = nil }
            .task(id: dictation.notice) {
                guard notice != .permissionDenied, notice != .unsupported else { return }
                try? await Task.sleep(nanoseconds: 5_000_000_000); dictation.notice = nil
            }
            .transition(.opacity.combined(with: .move(edge: .bottom)))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("parenting.composer.voiceNotice")
        }
    }

    private func message(_ notice: ComposerDictation.Notice) -> String {
        switch notice {
        case .permissionDenied: return ui("需要麦克风和语音识别权限才能说话。可以在“设置”里开启，或直接打字。")
        case .unsupported: return ui("这台手机暂时用不了本机语音识别，小咪不会改用在线识别。可以点键盘，用系统键盘上的麦克风听写。")
        case .failed: return ui("没听清，可能是周围太吵。再说一次，或点键盘打字。")
        case .empty: return ui("没听到内容。再说一次，或点键盘打字。")
        case .interrupted: return ui("语音被打断了，已听到的内容保留在上面，可以接着改或再说一次。")
        }
    }
}
