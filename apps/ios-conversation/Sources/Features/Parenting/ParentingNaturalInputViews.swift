import SwiftUI

// MARK: - Floating glass chrome

struct ParentingFloatingBarHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Floating chrome surface: Liquid Glass on iOS 26, a material with hairline and soft shadow before that.
struct ParentingGlassSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    func body(content: Content) -> some View { content.catGlass(in: shape, fallback: .ultraThinMaterial, shadowed: true) }
}
extension View {
    func parentingGlass<S: InsettableShape>(in shape: S) -> some View { modifier(ParentingGlassSurface(shape: shape)) }
}

// MARK: - Bottom composer (Today / Growth)

/// One input box. The parent says what is on their mind; sorting it into 录入 / 规划 / 复盘 happens behind the scenes.
@MainActor struct ParentingComposerBar: View {
    @ObservedObject var input: ParentingNaturalInputModel
    let context: ParentingNaturalContext
    let now: Date
    let busy: Bool
    let onSubmit: () -> Void
    /// Escape hatches to the manual forms (the "+" menu lists them last).
    var onManualArrangement: (() -> Void)?
    var onManualObservation: (() -> Void)?
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @FocusState private var focused: Bool
    /// Voice-first input (S1b): idle is hold-to-talk; see ParentingVoiceControls.swift.
    @StateObject private var voice = ParentingVoiceState()

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private func ui(_ key: String, _ arguments: [String]) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }

    /// The keyboard goes away when the words are handed to the card sheet, so the page and floating bars are visible again afterwards.
    private func handOver() { voice.dictation.stopNow(); focused = false; onSubmit() }

    private struct Starter: Identifiable { let id: String; let label: String; let text: String; let sendsNow: Bool }
    private var starters: [Starter] {
        [.init(id: "reschedule", label: "调课", text: "游泳改到", sendsNow: false),
         .init(id: "note", label: "记一笔", text: "记一下，", sendsNow: false),
         .init(id: "bring", label: "带什么", text: "周一要带", sendsNow: false),
         .init(id: "today", label: "今天有什么", text: "今天有什么安排", sendsNow: true)]
    }

    // MARK: Pieces

    private var chipsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(starters) { starter in
                    Button {
                        input.draft = starter.text
                        if starter.sendsNow { handOver() } else { voice.setMode(.keyboard); focused = true }
                    } label: {
                        Text(ui(starter.label)).font(catTypography.Role.caption.font.weight(.medium))
                            .padding(.horizontal, 12).frame(minHeight: 36)
                            .foregroundStyle(catColors.ink)
                            .background(catColors.fillSelected, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                    .accessibilityIdentifier("parenting.natural.chip." + starter.id)
                }
            }
        }
    }
    @ViewBuilder private var suggestionRow: some View {
        if let line = understanding, !dynamicTypeSize.isAccessibilitySize {
            Button(action: handOver) {
                HStack(spacing: 6) {
                    Image(systemName: "sparkles").accessibilityHidden(true)
                    Text(verbatim: line).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(ui("点这里继续")).font(catTypography.Role.caption.font.weight(.medium))
                }
                .font(catTypography.Role.caption.font)
                .foregroundStyle(catColors.calmSecondary)
                .frame(minHeight: 32)
            }
            .buttonStyle(.plain)
            .disabled(busy)
            .accessibilityIdentifier("parenting.natural.suggestion")
        }
    }
    private var textField: some View {
        TextField(ui("说说想让小咪记的事，例如：团团周五游泳改到六点了"), text: $input.draft, axis: .vertical)
            .lineLimit(1...(dynamicTypeSize.isAccessibilitySize ? 1 : 3))
            .focused($focused)
            .submitLabel(.send)
            .onSubmit { if input.canSubmit && !busy { handOver() } }
            .padding(.horizontal, 14).padding(.vertical, 11)
            .accessibilityIdentifier("parenting.natural.field")
    }

    // MARK: Layout: one glass card; idle is a single line (+ · hold-to-talk · keyboard)

    private var keyboardActive: Bool { voice.mode == .keyboard }
    private var expanded: Bool { keyboardActive && (focused || !input.draft.isEmpty) && !dynamicTypeSize.isAccessibilitySize }

    private var sendButton: some View {
        Button(action: handOver) {
            Image(systemName: "arrow.up").font(.body.weight(.semibold)).frame(width: 40, height: 40)
                .foregroundStyle(catColors.calmGlyph)
                .background(input.canSubmit && !busy ? catColors.calmCTATop : catColors.fillSelected, in: Circle())
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .disabled(!input.canSubmit || busy)
        .accessibilityLabel(ui("交给小咪"))
        .accessibilityIdentifier("parenting.natural.send")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if expanded {
                chipsRow
                suggestionRow
            }
            HStack(alignment: .bottom, spacing: 6) {
                plusMenu
                ParentingVoiceInputRow(input: input, state: voice, busy: busy, focused: $focused, onSend: handOver, field: {
                    textField
                        .background(catColors.fillSelected.opacity(0.7), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                }, sendButton: { sendButton })
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 8)
        .catGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous), fallback: .ultraThinMaterial, shadowed: true)
        .animation(.snappy(duration: 0.22), value: expanded)
        .animation(.snappy(duration: 0.22), value: keyboardActive)
        .frame(maxWidth: 520)
        // At accessibility sizes the capsule is too small to hold: keyboard only.
        .onAppear { if dynamicTypeSize.isAccessibilitySize { voice.setMode(.keyboard) } }
        .onChange(of: dynamicTypeSize) { _, size in if size.isAccessibilitySize { voice.setMode(.keyboard) } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.natural.bar")
    }

    private func switchToKeyboard() {
        voice.setMode(.keyboard)
        DispatchQueue.main.async { focused = true }
    }

    private var plusMenu: some View {
        Menu {
            ForEach(starters) { starter in
                Button(ui(starter.label)) {
                    input.draft = starter.text
                    if starter.sendsNow { handOver() } else { switchToKeyboard() }
                }
                .accessibilityIdentifier("parenting.natural.chip." + starter.id)
            }
            // Manual forms come last: the natural input is the default path, the forms are the escape hatch.
            Divider()
            Button { onManualArrangement?() } label: { Label(ui("手动添加安排"), systemImage: "calendar.badge.plus") }
                .accessibilityIdentifier("parenting.plus.manualArrangement")
            Button { onManualObservation?() } label: { Label(ui("手动记一笔"), systemImage: "square.and.pencil") }
                .accessibilityIdentifier("parenting.plus.manualObservation")
        } label: {
            Image(systemName: "plus").font(.title3.weight(.semibold)).foregroundStyle(catColors.ink)
                .frame(width: 44, height: 44).contentShape(Circle())
        }
        .disabled(busy)
        .accessibilityLabel(ui("更多"))
        .accessibilityIdentifier("parenting.plus.menu")
    }

    /// Grey, Tab-style read-back of what the draft would become. It is a suggestion only; nothing is saved until the card is confirmed.
    private var understanding: String? {
        guard let p = input.preview(context: context, now: now) else { return nil }
        var parts = [ui("小咪理解为：{0}", [ui(p.kind.title)])]
        if let id = p.childID, let name = context.children.first(where: { $0.id == id })?.name { parts.append(name) }
        if let title = p.title, p.kind == .newArrangement { parts.append(title) }
        if let due = p.dueAt { parts.append(due.formatted(.dateTime.month().day().weekday(.abbreviated).hour().minute())) }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Understanding sheet: one question at a time, then the card

@MainActor struct ParentingNaturalSheet: View {
    @ObservedObject var input: ParentingNaturalInputModel
    @ObservedObject var workspaceModel: ParentingWorkspaceModel
    let context: ParentingNaturalContext
    let now: Date
    let onNavigate: (ParentingIntentKind) -> Void
    let onManual: (ParentingNaturalProposal) -> Void
    let onAddChild: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var sheetTypeSize

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var savedReceipt: String? { if case .saved(let receipt) = input.phase { return receipt } else { return nil } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let proposal = input.proposal {
                        understood(proposal)
                        if input.canSwitchType { typeSwitch(proposal) }
                        switch input.phase {
                        case .asking: asking(proposal)
                        case .confirming, .saving, .failed: confirming(proposal)
                        case .saved(let receipt): saved(receipt)
                        case .idle: EmptyView()
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: 680).frame(maxWidth: .infinity)
            }
            .catBottomBar { confirmActions }
            .catSheetCanvas()
            .foregroundStyle(catColors.ink)
            .navigationTitle(ui("小咪来整理"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(ui("关闭")) { input.reset(); dismiss() }
                        .accessibilityIdentifier("parenting.natural.close")
                }
            }
        }
        .tint(catColors.actionText)
        // Saved: the receipt shows briefly and the sheet closes itself (never under VoiceOver or in UI tests that tap 好的).
        .task(id: savedReceipt) {
            guard savedReceipt != nil, !UIAccessibility.isVoiceOverRunning,
                  ProcessInfo.processInfo.environment["CF_UI_TEST_NO_AUTODISMISS"] != "1" else { return }
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            guard !Task.isCancelled else { return }
            input.reset(); dismiss()
        }
        .accessibilityIdentifier("parenting.natural.sheet")
    }

    private func understood(_ p: ParentingNaturalProposal) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: p.sourceText).font(.body).padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(catColors.userMessageBackground, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .accessibilityIdentifier("parenting.natural.source")
            HStack(spacing: 8) {
                if let mode = p.mode {
                    Text(ui(mode.title)).font(catTypography.Role.caption.font.weight(.semibold))
                        .padding(.horizontal, 10).frame(minHeight: 26)
                        .background(catColors.action.opacity(0.12), in: Capsule())
                        .foregroundStyle(catColors.actionText)
                }
                Text(ui(p.kind == .unknown ? (p.ambiguousIntent ? "这句话有两种理解" : "小咪还没听明白") : p.kind.title)).font(catTypography.Role.caption.font)
                    .foregroundStyle(catColors.calmSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("parenting.natural.understood")
        }
    }

    /// 记一笔 | 加安排: one tap re-reads the same words as the other kind. The card (or the next question) follows at once.
    private func typeSwitch(_ p: ParentingNaturalProposal) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                typeSegment("记一笔", kind: .observation, current: p.kind)
                typeSegment("加安排", kind: .newArrangement, current: p.kind)
            }
            .padding(4)
            .background(catColors.fillSelected.opacity(0.7), in: Capsule())
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("parenting.natural.typeSwitch")
            Text(ui(p.kind == .observation ? "只记录，不提醒。要提醒就点“加安排”。" : "到点提醒你。只想记下来就点“记一笔”。"))
                .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
        }
    }

    private func typeSegment(_ title: String, kind: ParentingIntentKind, current: ParentingIntentKind) -> some View {
        let selected = kind == current
        return Button { input.switchType(to: kind, context: context, now: now) } label: {
            Text(ui(title)).font(.subheadline.weight(selected ? .semibold : .regular))
                .frame(maxWidth: .infinity, minHeight: 36)
                .foregroundStyle(selected ? catColors.actionText : catColors.ink)
                .background { if selected { Capsule().fill(catColors.card) } }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("parenting.natural.switch." + kind.rawValue)
    }

    @ViewBuilder private func asking(_ p: ParentingNaturalProposal) -> some View {
        if let ask = ParentingNaturalInput.followUp(for: p, context: context) {
            VStack(alignment: .leading, spacing: 14) {
                Label(ui(ask.question), systemImage: "questionmark.bubble").font(.headline)
                    .accessibilityIdentifier("parenting.natural.question")
                if ask.field == .intent {
                    intentChoice(ask.options)
                } else if ask.field == .child && !ask.options.isEmpty {
                    // The one child picker (same as 今天/成长), nothing pre-selected: the parent taps who it is about.
                    ParentingChildSwitcher(children: p.childChoices.compactMap { id in context.children.first { $0.id == id } },
                                           selectedID: nil, fallsBackToFirst: false, accent: childAccent,
                                           onSelect: { id in if let id { input.answer(.child(id), context: context, now: now) } },
                                           identifier: "parenting.natural.childPicker", chipIdentifierPrefix: "parenting.natural.child.")
                } else if !ask.options.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 8)], alignment: .leading, spacing: 8) {
                        ForEach(ask.options) { option in
                            Button { input.answer(option.answer, context: context, now: now) } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    optionLabel(option.label).font(.subheadline.weight(.semibold))
                                    if let detail = option.detail {
                                        Text(verbatim: detail.formatted(.dateTime.month().day().weekday(.abbreviated).hour().minute()))
                                            .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .padding(.horizontal, 12)
                                .background(catColors.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(catColors.hairline, lineWidth: 0.5))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("parenting.natural." + option.id)
                        }
                    }
                }
                if ask.field == .child && ask.options.isEmpty {
                    Button(ui("添加孩子")) { dismiss(); onAddChild() }
                        .parentingPrimaryButton()
                        .accessibilityIdentifier("parenting.natural.addChild")
                }
                if ask.field == .target && ask.options.isEmpty && p.kind == .reschedule {
                    Button(ui("改为加安排")) { input.answer(.intent(.newArrangement), context: context, now: now) }
                        .parentingSecondaryButton()
                }
                if ask.allowsText {
                    HStack(spacing: 10) {
                        TextField(ui(ask.textHint ?? "补充一句"), text: $input.reply)
                            .padding(.horizontal, 14).padding(.vertical, 12)
                            .background(catColors.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(catColors.hairline, lineWidth: 0.5))
                            .submitLabel(.done)
                            .onSubmit { input.answerWithReply(context: context, now: now) }
                            .accessibilityIdentifier("parenting.natural.reply")
                        Button(ui("好")) { input.answerWithReply(context: context, now: now) }
                            .parentingPrimaryButton()
                            .disabled(input.reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("parenting.natural.replySend")
                    }
                }
                Text(ui("只问还缺的这一项；其余已经听明白的不会再问。"))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
            .parentingCard()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("parenting.natural.asking")
        }
    }

    /// The one question for an unclear sentence: two big buttons side by side (stacked only at accessibility sizes).
    private func intentChoice(_ options: [ParentingFollowUp.Option]) -> some View {
        let buttons = ForEach(options) { option in
            Button { input.answer(option.answer, context: context, now: now) } label: {
                VStack(spacing: 4) {
                    optionLabel(option.label).font(.headline)
                    if let subtitle = option.subtitle {
                        Text(ui(subtitle)).font(catTypography.Role.caption.font).multilineTextAlignment(.center)
                            .foregroundStyle(catColors.calmSecondary)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 72)
                .padding(.horizontal, 8)
                .background(catColors.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(catColors.hairline, lineWidth: 0.5))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("parenting.natural." + option.id)
        }
        // Side by side unless the text is at an accessibility size. ViewThatFits would stack them, because a subtitle's one-line ideal width never fits.
        return Group {
            if sheetTypeSize.isAccessibilitySize { VStack(spacing: 10) { buttons } }
            else { HStack(spacing: 10) { buttons } }
        }
    }

    @ViewBuilder private func optionLabel(_ label: ParentingFollowUp.Label) -> some View {
        switch label {
        case .key(let key): Text(ui(key))
        case .verbatim(let text): Text(verbatim: text)
        case .dateTime(let date): Text(verbatim: date.formatted(.dateTime.month().day().weekday(.abbreviated).hour().minute()))
        }
    }

    @ViewBuilder private func confirming(_ p: ParentingNaturalProposal) -> some View {
        let card = input.card(workspace: workspaceModel.workspace, remindersEnabled: workspaceModel.remindersAvailable ? workspaceModel.reminderSettings.enabled : nil, now: now)
        if let card {
            if input.canChangeChild && context.children.count > 1 {
                VStack(alignment: .leading, spacing: 6) {
                    Text(ui("是谁的事")).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                    ParentingChildSwitcher(children: context.children, selectedID: p.childID, fallsBackToFirst: false, accent: childAccent,
                                           onSelect: { id in if let id { input.changeChild(to: id, context: context, now: now) } },
                                           identifier: "parenting.natural.cardChildPicker", chipIdentifierPrefix: "parenting.natural.cardChild.")
                }
            }
            ParentingConfirmCardView(card: card)
            if case .failed(let message) = input.phase {
                Label(ui(message), systemImage: "exclamationmark.circle").font(.subheadline)
                    .foregroundStyle(catColors.failure).frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("parenting.natural.failure")
            }
        }
    }

    private func childAccent(_ id: UUID) -> Color {
        ParentingChildAccent.color(slot: ParentingGrowthInsights.accentSlot(for: id, in: workspaceModel.workspace))
    }

    private var currentCard: ParentingConfirmCard? {
        input.card(workspace: workspaceModel.workspace, remindersEnabled: workspaceModel.remindersAvailable ? workspaceModel.reminderSettings.enabled : nil, now: now)
    }
    /// Confirm / manual actions stay pinned at the bottom of the sheet so they are visible at the medium detent.
    @ViewBuilder private var confirmActions: some View {
        if let p = input.proposal, let card = currentCard {
            switch input.phase {
            case .confirming, .saving, .failed:
                let saving = input.phase == .saving
                let confirmAction: () -> Void = {
                    if card.writes {
                        Task { await input.confirm(model: workspaceModel) }
                    } else {
                        let kind = p.kind
                        input.reset(); dismiss(); onNavigate(kind)
                    }
                }
                let confirmLabel = HStack { if saving { ProgressView() }; Text(ui(card.confirmTitle)) }
                Group {
                    if card.writes {
                        // 手动调整 on the left, the save on the right (ParentingActionPair: equal width, stacked at accessibility sizes).
                        ParentingActionPair(secondaryAction: { onManual(p); dismiss() }, primaryAction: confirmAction,
                                            secondaryDisabled: saving, primaryDisabled: saving || workspaceModel.busy,
                                            secondaryID: "parenting.natural.manual", primaryID: "parenting.natural.confirm",
                                            secondaryLabel: { Text(ui("手动调整")) }, primaryLabel: { confirmLabel })
                    } else {
                        Button(action: confirmAction) { confirmLabel.frame(maxWidth: .infinity, minHeight: 28) }
                            .parentingTodayPrimaryGlassButton()
                            .disabled(saving || workspaceModel.busy)
                            .accessibilityIdentifier("parenting.natural.confirm")
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 10)
                .frame(maxWidth: 680).frame(maxWidth: .infinity)
                .background(.bar)
            default: EmptyView()
            }
        }
    }

    private func saved(_ receipt: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                // 记一笔: the plush cat with its notebook confirms a saved record. Decorative; hidden at accessibility sizes.
                if !sheetTypeSize.isAccessibilitySize { PlushMascot(pose: .note, size: 38) }
                Label(ui(receipt), systemImage: "checkmark.circle.fill").font(.title3.weight(.semibold))
                    .foregroundStyle(catColors.success)
                    .accessibilityIdentifier("parenting.natural.receipt")
            }
            Text(ui("已保存在这台设备上；今天页和成长页会同步显示。")).font(.subheadline).foregroundStyle(catColors.calmSecondary)
            Button(ui("好的")) { input.reset(); dismiss() }
                .parentingPrimaryButton()
                .accessibilityIdentifier("parenting.natural.done")
        }
        .parentingCard()
    }
}

// MARK: - Typed card catalogue renderer (the only way a card is drawn)

struct ParentingConfirmCardView: View {
    let card: ParentingConfirmCard
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private func when(_ date: Date) -> String { date.formatted(.dateTime.month().day().weekday(.abbreviated).hour().minute()) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(card.blocks.enumerated()), id: \.offset) { _, block in blockView(block) }
        }
        .parentingCard()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.confirmCard." + card.kind.rawValue)
    }

    @ViewBuilder private func blockView(_ block: ParentingConfirmCard.Block) -> some View {
        switch block {
        case .headline(let title, let symbol):
            HStack(spacing: 10) {
                Image(systemName: symbol).foregroundStyle(catColors.actionText).frame(width: 36, height: 36)
                    .background(catColors.action.opacity(0.12), in: Circle()).accessibilityHidden(true)
                Text(ui(title)).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            }
        case .fields(let fields):
            VStack(spacing: 0) {
                ForEach(Array(fields.enumerated()), id: \.offset) { index, field in
                    HStack(alignment: .firstTextBaseline) {
                        Text(ui(field.label)).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                        Spacer(minLength: 12)
                        value(field.value).font(.subheadline.weight(.semibold)).multilineTextAlignment(.trailing)
                            .foregroundStyle(tint(field.tone))
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("parenting.confirmCard.field." + String(index))
                    if index < fields.count - 1 { Divider() }
                }
            }
        case .change(let label, let before, let after):
            VStack(alignment: .leading, spacing: 8) {
                Text(ui(label)).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                HStack(spacing: 10) {
                    Text(verbatim: when(before)).strikethrough().foregroundStyle(catColors.calmSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "arrow.right").foregroundStyle(catColors.actionText).accessibilityHidden(true)
                    Text(verbatim: when(after)).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.subheadline)
                .padding(12)
                .background(catColors.action.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("parenting.confirmCard.change")
            }
        case .quote(let label, let text):
            VStack(alignment: .leading, spacing: 6) {
                Text(ui(label)).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                HStack(alignment: .top, spacing: 10) {
                    Rectangle().fill(catColors.action.opacity(0.5)).frame(width: 3)
                    Text(verbatim: text).font(.subheadline).textSelection(.enabled)
                }.fixedSize(horizontal: false, vertical: true)
            }
        case .conflicts(let title, let items):
            VStack(alignment: .leading, spacing: 6) {
                Label(ui(title), systemImage: "exclamationmark.triangle").font(.subheadline.weight(.semibold))
                    .foregroundStyle(catColors.attention)
                ForEach(items) { item in
                    Text(verbatim: item.title + " · " + when(item.at)).font(.subheadline)
                }
                Text(ui("没有时长信息，暂不能判断是否重叠或能否同时进行。")).font(catTypography.Role.caption.font)
                    .foregroundStyle(catColors.calmSecondary)
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(catColors.attention.opacity(0.10), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("parenting.confirmCard.conflicts")
        case .note(let key, let tone):
            Label {
                Text(ui(key)).font(catTypography.Role.caption.font).fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: tone == .warning ? "exclamationmark.circle" : "info.circle")
            }
            .foregroundStyle(tone == .warning ? catColors.attention : catColors.calmSecondary)
        }
    }

    @ViewBuilder private func value(_ value: ParentingConfirmCard.Value) -> some View {
        switch value {
        case .text(let text): Text(verbatim: text)
        case .date(let date): Text(verbatim: when(date))
        case .key(let key): Text(ui(key))
        }
    }
    private func tint(_ tone: ParentingConfirmCard.Tone) -> Color {
        switch tone {
        case .normal: catColors.ink
        case .changed: catColors.actionText
        case .unknown: catColors.calmSecondary
        case .warning: catColors.attention
        }
    }
}
