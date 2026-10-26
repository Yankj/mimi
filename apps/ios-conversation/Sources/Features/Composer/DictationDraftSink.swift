import Foundation
import Combine

/// Where dictated words land. `ComposerDictation` writes the live transcript through this seam, so one controller feeds
/// the chat draft (`ConversationFirstModel`) and the parenting natural-input draft (`ParentingNaturalInputModel`).
/// `editDraft` may refuse or normalize; the caller re-reads `draft` afterwards (that is how "the user typed" is detected).
@MainActor protocol DictationDraftSink: AnyObject {
    var draft: String { get }
    func editDraft(_ text: String)
}

/// The recognizer side of a dictation session (seam for a scripted fake in tests). `NativeVoiceController` is the device one.
/// Contract: `onEnd` fires exactly once per accepted start; `onText` delivers the whole transcript so far, not a delta.
@MainActor protocol DictationTranscribing: AnyObject {
    var dictationPhase: NativeVoiceController.DictationPhase { get }
    /// Live input level 0…1 for the waveform (0 when idle).
    var audioLevelPublisher: AnyPublisher<Float, Never> { get }
    @discardableResult
    func startDictation(onDeviceOnly: Bool, onText: @escaping @MainActor (String) -> Void,
                        onEnd: @escaping @MainActor (NativeVoiceController.DictationEnd) -> Void) -> Bool
    func stopDictation()
    func cancelDictation(_ reason: NativeVoiceController.DictationEnd)
}

extension NativeVoiceController: DictationTranscribing {
    var audioLevelPublisher: AnyPublisher<Float, Never> { $audioLevel.eraseToAnyPublisher() }
}
extension ConversationFirstModel: DictationDraftSink {}
extension ParentingNaturalInputModel: DictationDraftSink {
    func editDraft(_ text: String) { draft = text }
}
