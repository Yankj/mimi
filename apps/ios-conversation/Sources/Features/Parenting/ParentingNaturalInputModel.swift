import Foundation
import Combine

/// State of one natural-input exchange: words -> understanding -> (one question at a time) -> confirm card -> saved.
/// Nothing here writes until `confirm` runs the commands through the ledger model and each one returns saved.
@MainActor
final class ParentingNaturalInputModel: ObservableObject {
    enum Phase: Equatable {
        case idle, asking, confirming, saving
        /// A receipt resource key. Only set after the ledger reported the write as saved.
        case saved(String)
        case failed(String)
    }

    @Published var draft = ""
    @Published var reply = ""
    @Published private(set) var proposal: ParentingNaturalProposal?
    @Published private(set) var phase: Phase = .idle

    private var recordID = UUID()
    private var operationIDs = [UUID(), UUID()]
    private var completedSteps = 0

    var trimmedDraft: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }
    var canSubmit: Bool { !trimmedDraft.isEmpty && trimmedDraft.count <= ParentingNaturalInput.maxLength }

    /// Cheap live read of the draft: the grey "Tab-style" suggestion under the input box. Never saved, never sent anywhere.
    func preview(context: ParentingNaturalContext, now: Date) -> ParentingNaturalProposal? {
        guard canSubmit else { return nil }
        let p = ParentingNaturalInput.classify(trimmedDraft, context: context, now: now)
        if p.kind == .unknown || (p.kind == .profileFact && !p.isReadyToConfirm) { return nil }
        return p
    }

    func submit(context: ParentingNaturalContext, now: Date) {
        guard canSubmit else { return }
        startOver()
        adopt(ParentingNaturalInput.classify(trimmedDraft, context: context, now: now))
    }

    func answer(_ answer: ParentingNaturalAnswer, context: ParentingNaturalContext, now: Date) {
        guard let current = proposal, phase == .asking else { return }
        reply = ""
        adopt(ParentingNaturalInput.applying(answer, to: current, context: context, now: now))
    }

    /// 记一笔 | 加安排 on the card: re-reads the same words with the other kind locked in. Works while a question is open or the card
    /// shows, never after something was partly saved (that record would then belong to the other kind).
    var canSwitchType: Bool {
        guard let kind = proposal?.kind, completedSteps == 0 else { return false }
        guard kind == .observation || kind == .newArrangement else { return false }
        switch phase { case .asking, .confirming, .failed: return true; default: return false }
    }

    func switchType(to kind: ParentingIntentKind, context: ParentingNaturalContext, now: Date) {
        guard canSwitchType, let current = proposal, current.kind != kind, kind == .observation || kind == .newArrangement else { return }
        reply = ""
        adopt(ParentingNaturalInput.applying(.intent(kind), to: current, context: context, now: now))
    }

    /// The child on the card can still be changed before saving (same picker as the question). Never after a step was saved.
    var canChangeChild: Bool {
        guard let p = proposal, completedSteps == 0, p.childID != nil else { return false }
        guard p.kind == .observation || p.kind == .newArrangement else { return false }
        switch phase { case .confirming, .failed: return true; default: return false }
    }

    func changeChild(to id: UUID, context: ParentingNaturalContext, now: Date) {
        guard canChangeChild, let current = proposal, current.childID != id, context.children.contains(where: { $0.id == id }) else { return }
        adopt(ParentingNaturalInput.applying(.child(id), to: current, context: context, now: now))
    }

    func answerWithReply(context: ParentingNaturalContext, now: Date) {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        answer(.text(text), context: context, now: now)
    }

    /// Back to the question or card the parent last saw, e.g. after a failed save.
    func dismissFailure() { if case .failed = phase { phase = .confirming } }

    func reset() { startOver(); draft = "" }

    func card(workspace: ParentingWorkspace, remindersEnabled: Bool?, now: Date) -> ParentingConfirmCard? {
        proposal.flatMap { ParentingConfirmCard.make($0, workspace: workspace, remindersEnabled: remindersEnabled, now: now) }
    }

    /// Runs the confirmed card. A step that already saved is never repeated on retry.
    func confirm(model: ParentingWorkspaceModel) async {
        guard let proposal, proposal.isReadyToConfirm, proposal.kind.writes, phase == .confirming || isFailed else { return }
        guard !model.busy else { return }
        phase = .saving
        let commands = ParentingNaturalInput.commands(for: proposal, workspace: model.workspace, ids: .init(record: recordID))
        guard !commands.isEmpty else { phase = .failed("这张卡已经过期，请重新说一次。"); return }
        var index = completedSteps
        // After a partial save the first command (create) is already in the ledger and must not run again.
        let pending = Array(commands.dropFirst(index))
        for command in pending {
            let ok = await model.apply(command, operationID: operationIDs[min(index, operationIDs.count - 1)])
            guard ok else {
                let pendingNote = proposal.kind == .profileFact
                    ? "已先存为待确认，还没有记成长期事实。请重试确认。"
                    : "已先存为待确认，还没有进入安排。请在今天页确认，或重试。"
                phase = .failed(index > 0 ? pendingNote : (model.error ?? "没有保存成功。已填写的内容保留，可以重试。"))
                return
            }
            index += 1; completedSteps = index
        }
        switch proposal.kind {
        case .newArrangement: phase = .saved("已安排好了")
        case .reschedule: phase = .saved("已改期")
        case .cancel: phase = .saved("已取消这项安排")
        case .observation: phase = .saved("已保存这条观察")
        case .profileFact: phase = .saved("已记入档案")
        default: break
        }
    }

    private var isFailed: Bool { if case .failed = phase { return true } else { return false } }

    private func adopt(_ p: ParentingNaturalProposal) {
        proposal = p
        phase = p.missing.isEmpty && p.kind != .unknown ? .confirming : .asking
    }

    private func startOver() {
        proposal = nil; phase = .idle; reply = ""
        recordID = UUID(); operationIDs = [UUID(), UUID()]; completedSteps = 0
    }
}
