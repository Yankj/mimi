import SwiftUI

// MARK: - Glass styling for the Today page
//
// Native Liquid Glass on iOS 26+, the existing material / gradient styles before that (the project deploys to iOS 17).
// Colours come only from `catColors`; nothing here defines a new colour value.

private struct ParentingTodayGlassSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular.tint(catColors.action.opacity(0.10)), in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(catColors.hairline, lineWidth: 0.5))
        }
    }
}

extension View {
    /// A glass card (hero, rail items, undo bar). Not an opaque block: the page shows through.
    func parentingTodayGlassCard(cornerRadius: CGFloat = 28) -> some View {
        modifier(ParentingTodayGlassSurface(shape: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
    }
    func parentingTodayGlassCapsule() -> some View { modifier(ParentingTodayGlassSurface(shape: Capsule())) }

    /// Main action: system `.glassProminent` tinted with a translucent brand orange (not a solid fill), ink label.
    @ViewBuilder func parentingTodayPrimaryGlassButton() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent).tint(catColors.action.opacity(0.55)).foregroundStyle(catColors.ink).controlSize(.large)
        } else {
            self.buttonStyle(ParentingPrimaryButtonStyle())
        }
    }
    /// Secondary action: system `.glass`.
    @ViewBuilder func parentingTodaySecondaryGlassButton(size: ControlSize = .large) -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass).foregroundStyle(catColors.ink).controlSize(size)
        } else {
            self.buttonStyle(ParentingSecondaryButtonStyle())
        }
    }
}

// MARK: - Two-action row (confirm cards)

/// Where the pair sits: side by side, or stacked (accessibility Dynamic Type sizes).
enum ParentingActionPairLayout: Equatable {
    case sideBySide, stacked
    /// Side by side normally; stacked once the text size reaches the accessibility range, where two labels no longer fit a row.
    static func resolve(dynamicTypeSize: DynamicTypeSize) -> ParentingActionPairLayout {
        dynamicTypeSize.isAccessibilitySize ? .stacked : .sideBySide
    }
}

/// The one layout for a confirm card's two actions: secondary on the left (system `.glass`), primary on the right (translucent
/// orange `.glassProminent`, never a solid fill), equal width and equal height. Stacked with the primary on top when the text
/// size is an accessibility size. Used by the 「小咪来整理」 sheet and by the 待确认 / 待完成 arrangement cards, so every
/// two-button confirmation in the parenting area looks and behaves the same. Identifiers stay on the buttons.
struct ParentingActionPair<SecondaryLabel: View, PrimaryLabel: View>: View {
    let secondaryAction: () -> Void
    let primaryAction: () -> Void
    let secondaryDisabled: Bool
    let primaryDisabled: Bool
    let secondaryID: String
    let primaryID: String
    let secondaryLabel: SecondaryLabel
    let primaryLabel: PrimaryLabel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(secondaryAction: @escaping () -> Void, primaryAction: @escaping () -> Void,
         secondaryDisabled: Bool = false, primaryDisabled: Bool = false, secondaryID: String, primaryID: String,
         @ViewBuilder secondaryLabel: () -> SecondaryLabel, @ViewBuilder primaryLabel: () -> PrimaryLabel) {
        self.secondaryAction = secondaryAction; self.primaryAction = primaryAction
        self.secondaryDisabled = secondaryDisabled; self.primaryDisabled = primaryDisabled
        self.secondaryID = secondaryID; self.primaryID = primaryID
        self.secondaryLabel = secondaryLabel(); self.primaryLabel = primaryLabel()
    }

    private var secondary: some View {
        Button(action: secondaryAction) { secondaryLabel.frame(maxWidth: .infinity, minHeight: 28) }
            .parentingTodaySecondaryGlassButton()
            .disabled(secondaryDisabled)
            .accessibilityIdentifier(secondaryID)
    }
    private var primary: some View {
        Button(action: primaryAction) { primaryLabel.frame(maxWidth: .infinity, minHeight: 28) }
            .parentingTodayPrimaryGlassButton()
            .disabled(primaryDisabled)
            .accessibilityIdentifier(primaryID)
    }

    var body: some View {
        switch ParentingActionPairLayout.resolve(dynamicTypeSize: dynamicTypeSize) {
        case .sideBySide:
            HStack(spacing: 12) { secondary; primary }
        case .stacked:
            VStack(spacing: 10) { primary; secondary }
        }
    }
}

// MARK: - Today: one hero card, a short rail, undo

/// 今天: the one recommended start action as a hero card, what comes next as a horizontal rail, and a 3-second undo after a
/// one-tap complete. Everything else (stats, lists, history, forms) is behind 「全部安排」, supplied by the host as `allPage`.
/// Reads `ParentingTodayDeck` (pure) and writes only through `ParentingWorkspaceModel` commands the parent tapped.
@MainActor struct ParentingTodayView<AllPage: View>: View {
    @ObservedObject var model: ParentingWorkspaceModel
    let now: Date
    /// The child the page is scoped to; nil uses the earliest-created child (`ParentingStartAction.activeChild`).
    let childID: UUID?
    let onAddChild: () -> Void
    let onAdjust: (ParentingArrangement) -> Void
    /// Puts a sentence into the natural-input draft. Never submits it.
    let onFillDraft: (String) -> Void
    let onSelectChild: (UUID) -> Void
    /// The host's 「全部安排」 page, pushed with `NavigationStack` (`navigationDestination(isPresented:)`).
    let allPage: () -> AllPage

    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @State private var activeUndo: ActiveUndo?
    @State private var pendingTap: PendingTap?
    @State private var confirmOperations: [UUID: UUID] = [:]
    @State private var working = false
    @State private var tapGate = ParentingOneTapGate()
    @State private var showAll = false

    /// Reused when the same tap is retried after a failed save, so a retry can never save twice.
    private struct PendingTap { let arrangementID: UUID; let operationID: UUID; let recordID: UUID }
    private struct ActiveUndo: Equatable, Sendable { let handle: ParentingCompletionUndo; let title: String; let operationID: UUID }

    init(model: ParentingWorkspaceModel, now: Date, childID: UUID?, onAddChild: @escaping () -> Void,
         onAdjust: @escaping (ParentingArrangement) -> Void, onFillDraft: @escaping (String) -> Void,
         onSelectChild: @escaping (UUID) -> Void, @ViewBuilder allPage: @escaping () -> AllPage) {
        _model = ObservedObject(wrappedValue: model)
        self.now = now; self.childID = childID; self.onAddChild = onAddChild; self.onAdjust = onAdjust
        self.onFillDraft = onFillDraft; self.onSelectChild = onSelectChild; self.allPage = allPage
    }

    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private func ui(_ key: String, _ arguments: [String]) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }
    private var workspace: ParentingWorkspace { model.workspace }
    private var disabled: Bool { model.busy || working }

    var body: some View {
        let deck = ParentingTodayDeck.build(workspace: workspace, now: now, context: ParentingStartContext(childID: childID))
        VStack(alignment: .leading, spacing: 18) {
            Text(verbatim: now.formatted(.dateTime.month(.wide).day().weekday(.wide)))
                .font(catTypography.Role.caption.font.weight(.medium)).foregroundStyle(catColors.calmSecondary)
                .accessibilityIdentifier("parenting.today.date")
            if workspace.children.count > 1, let id = deck.childID { childMenu(activeID: id) }
            if let activeUndo { undoBar(activeUndo) }
            hero(deck)
            if deck.childID != nil {
                rail(deck)
                allArrangementsLink(deck)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.today.page")
        // Attached here (not on the host's NavigationStack) so the pushed page is rebuilt whenever the host re-renders with a fresh `allPage`.
        .navigationDestination(isPresented: $showAll) { allPage() }
        // The undo affordance disappears at the absolute expiry time, so leaving and returning never extends the window.
        .task(id: activeUndo) {
            guard let current = activeUndo else { return }
            try? await Task.sleep(for: .seconds(current.handle.timeUntilExpiry(from: Date())))
            if !Task.isCancelled, activeUndo == current { activeUndo = nil }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, let current = activeUndo, !current.handle.isActive(at: Date()) { activeUndo = nil }
        }
    }

    // MARK: Child switcher (only with more than one child)
    private func childMenu(activeID: UUID) -> some View {
        Menu {
            ForEach(workspace.children) { child in
                Button { onSelectChild(child.id) } label: {
                    if child.id == activeID { Label { Text(verbatim: child.name) } icon: { Image(systemName: "checkmark") } }
                    else { Text(verbatim: child.name) }
                }
            }
        } label: {
            Label { Text(verbatim: workspace.children.first { $0.id == activeID }?.name ?? "") } icon: { Image(systemName: "person.crop.circle") }
                .font(.subheadline.weight(.medium)).frame(minHeight: 44)
        }
        .accessibilityLabel(ui("查看哪个孩子"))
        .accessibilityIdentifier("parenting.today.childMenu")
    }

    // MARK: Hero
    @ViewBuilder private func hero(_ deck: ParentingTodayDeck) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            switch deck.hero {
            case .startWithNickname:
                heroText(symbol: "leaf", eyebrow: nil, title: ui("从孩子的昵称开始"),
                         detail: ui("不用填写生日和健康信息。添加昵称后，就能记录安排、观察与成长目标。"))
                Button(action: onAddChild) { heroButtonLabel(ui("添加孩子")) }
                    .parentingTodayPrimaryGlassButton().disabled(disabled)
                    .accessibilityIdentifier("parenting.today.hero.addChild")
            case .confirmAll(let ids):
                heroText(symbol: "hourglass", eyebrow: ui("先核对一下"), title: ui("有 {0} 件待确认", [String(ids.count)]),
                         detail: ui("确认后才会进入安排，也才会提醒。不确定的可以先逐件查看。"))
                pendingPreview(ids)
                Button { confirmAll(ids) } label: { heroButtonLabel(ui("全部确认")) }
                    .parentingTodayPrimaryGlassButton().disabled(disabled)
                    .accessibilityIdentifier("parenting.today.hero.confirmAll")
                Button { showAll = true } label: { heroButtonLabel(ui("逐件查看")) }
                    .parentingTodaySecondaryGlassButton()
                    .accessibilityIdentifier("parenting.today.hero.reviewEach")
            case .handleOverdue(let ids):
                heroText(symbol: "clock.badge.exclamationmark", eyebrow: ui("先看看这些"), title: ui("有 {0} 件以前的安排还没标记", [String(ids.count)]),
                         detail: ui("它们不会被自动算作完成。可以逐件完成、调整时间或取消。"))
                Button { showAll = true } label: { heroButtonLabel(ui("去处理")) }
                    .parentingTodayPrimaryGlassButton()
                    .accessibilityIdentifier("parenting.today.hero.handleOverdue")
            case .nextWithComplete(_, let dueAt, let hasPassed):
                if let item = deck.heroArrangement {
                    heroText(symbol: "checkmark.circle", eyebrow: ui(hasPassed ? "时间已过，做完了就点完成" : "下一件"), title: item.title,
                             detail: childLine(item.childID) + timeText(dueAt))
                    HStack(spacing: 12) {
                        Button { completeOneTap(item) } label: { heroButtonLabel(ui("完成")) }
                            .parentingTodayPrimaryGlassButton().disabled(disabled)
                            .accessibilityIdentifier("parenting.today.hero.complete")
                        Button { onAdjust(item) } label: { heroButtonLabel(ui("调整安排")) }
                            .parentingTodaySecondaryGlassButton().disabled(disabled)
                            .accessibilityIdentifier("parenting.today.hero.adjust")
                    }
                }
            case .recordTodaySmallThing:
                // 晚安: the evening prompt shows the sleepy plush cat in place of the moon symbol (still one hero, one action).
                heroText(symbol: "moon.stars", mascot: .night, eyebrow: ui("今晚"), title: ui("今天有什么小事想记下？"),
                         detail: ui("一句话就好，小咪会先给你一张确认卡。"))
                Button { onFillDraft(ui("记一下，")) } label: { heroButtonLabel(ui("说一句")) }
                    .parentingTodayPrimaryGlassButton().disabled(disabled)
                    .accessibilityIdentifier("parenting.today.hero.recordSmallThing")
            case .examplePrompt(let text):
                heroText(symbol: "text.bubble", eyebrow: nil, title: ui("试试这样说"), detail: ui(text))
                Button { onFillDraft(ui(text)) } label: { heroButtonLabel(ui("放进输入栏")) }
                    .parentingTodayPrimaryGlassButton().disabled(disabled)
                    .accessibilityIdentifier("parenting.today.hero.useExample")
            default:
                EmptyView() // Growth and family actions are never returned for the Today tab.
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .parentingTodayGlassCard(cornerRadius: 28)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.today.hero")
    }

    private func heroText(symbol: String, mascot: PlushMascotPose? = nil, eyebrow: String?, title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let mascot {
                PlushMascot(pose: mascot, size: 52).accessibilityHidden(true)
            } else {
                Image(systemName: symbol).font(.title2).foregroundStyle(catColors.actionText).accessibilityHidden(true)
            }
            if let eyebrow {
                Text(verbatim: eyebrow).font(catTypography.Role.caption.font.weight(.medium)).foregroundStyle(catColors.calmSecondary)
            }
            Text(verbatim: title).font(.title2.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader).accessibilityIdentifier("parenting.today.hero.title")
            if !detail.isEmpty {
                Text(verbatim: detail).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                    .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("parenting.today.hero.detail")
            }
        }
    }
    private func heroButtonLabel(_ text: String) -> some View {
        Text(verbatim: text).font(.body.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 28)
    }

    /// The drafts behind 「全部确认」, so one tap never confirms something the parent has not seen.
    @ViewBuilder private func pendingPreview(_ ids: [UUID]) -> some View {
        let items = ids.compactMap { id in workspace.arrangements.first { $0.id == id } }
        VStack(alignment: .leading, spacing: 6) {
            ForEach(items.prefix(3)) { item in
                Text(verbatim: item.title + " · " + timeText(item.dueAt)).font(.subheadline).lineLimit(1)
            }
            if items.count > 3 {
                Text(verbatim: ui("还有 {0} 件", [String(items.count - 3)])).font(catTypography.Role.caption.font)
                    .foregroundStyle(catColors.calmSecondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("parenting.today.hero.pendingPreview")
    }

    // MARK: Rail
    @ViewBuilder private func rail(_ deck: ParentingTodayDeck) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(ui("接下来")).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            if deck.rail.isEmpty {
                Text(ui("接下来没有别的安排。")).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                    .accessibilityIdentifier("parenting.today.rail.empty")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) { ForEach(deck.rail) { railCard($0) } }.padding(.vertical, 2)
                }
                .scrollClipDisabled()
                .accessibilityIdentifier("parenting.today.rail")
            }
        }
    }

    private func railCard(_ item: ParentingTodayDeck.RailItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: railTime(item)).font(catTypography.Role.caption.font.monospacedDigit().weight(.medium))
                .foregroundStyle(catColors.actionText)
            Text(verbatim: item.title).font(.headline).lineLimit(2).fixedSize(horizontal: false, vertical: true)
            if workspace.children.count > 1 {
                Text(verbatim: workspace.children.first { $0.id == item.childID }?.name ?? ui("孩子已删除"))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
        }
        .padding(14)
        .frame(width: 176, alignment: .leading)
        .parentingTodayGlassCard(cornerRadius: 20)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("parenting.today.rail." + item.id.uuidString)
    }

    private func railTime(_ item: ParentingTodayDeck.RailItem) -> String {
        item.when == .laterToday ? item.dueAt.formatted(.dateTime.hour().minute())
                                 : item.dueAt.formatted(.dateTime.month().day().hour().minute())
    }

    // MARK: 全部安排
    private func allArrangementsLink(_ deck: ParentingTodayDeck) -> some View {
        Button { showAll = true } label: {
            HStack(spacing: 8) {
                Text(ui("全部安排")).font(.body.weight(.semibold))
                if deck.hiddenOpenCount > 0 {
                    Text(verbatim: ui("还有 {0} 件", [String(deck.hiddenOpenCount)])).font(catTypography.Role.caption.font)
                        .foregroundStyle(catColors.calmSecondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 28)
        }
        .parentingTodaySecondaryGlassButton()
        .accessibilityIdentifier("parenting.today.allArrangements")
    }

    // MARK: Undo
    private func undoBar(_ current: ActiveUndo) -> some View {
        TimelineView(.periodic(from: Date(), by: 0.5)) { context in
            let seconds = current.handle.remainingSeconds(at: context.date)
            HStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(catColors.success).accessibilityHidden(true)
                Text(verbatim: ui("已完成：{0}", [current.title])).font(.subheadline).lineLimit(1)
                Spacer(minLength: 4)
                Button { undoCompletion(current) } label: {
                    Text(verbatim: ui("撤销") + " · " + String(seconds)).font(.subheadline.weight(.semibold)).frame(minHeight: 32)
                }
                .parentingTodaySecondaryGlassButton(size: .small).disabled(model.busy || seconds == 0)
                .accessibilityLabel(ui("撤销"))
                .accessibilityValue(ui("还剩 {0} 秒", [String(seconds)]))
                .accessibilityIdentifier("parenting.today.undo.button")
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .parentingTodayGlassCapsule()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.today.undo.toast")
    }

    // MARK: Actions (each is the parent's own tap; failures surface through `model.error`)
    private func completeOneTap(_ item: ParentingArrangement) {
        // The gate closes synchronously, before the next render: a second tap in the same moment, or on the next
        // arrangement that appears the instant this one is saved, must not complete anything else.
        guard !disabled, tapGate.begin(now: Date()) else { return }
        working = true
        let tap: PendingTap
        if let existing = pendingTap, existing.arrangementID == item.id { tap = existing }
        else { tap = PendingTap(arrangementID: item.id, operationID: UUID(), recordID: UUID()) }
        pendingTap = tap
        Task {
            let handle = await model.completeOneTap(item.id, operationID: tap.operationID, recordID: tap.recordID)
            tapGate.finish(completedAt: handle == nil ? nil : Date())
            working = false
            guard let handle else { return }
            pendingTap = nil
            activeUndo = ActiveUndo(handle: handle, title: item.title, operationID: UUID())
        }
    }

    private func undoCompletion(_ current: ActiveUndo) {
        guard !model.busy else { return }
        Task {
            let undone = await model.undoCompletion(current.handle, operationID: current.operationID)
            if undone || !current.handle.isActive(at: Date()) { activeUndo = nil }
        }
    }

    /// Confirms the drafts one command at a time and stops at the first failure; a retry reuses each command's operation id.
    private func confirmAll(_ ids: [UUID]) {
        guard !disabled else { return }
        working = true
        Task {
            for id in ids {
                let operationID = confirmOperations[id] ?? UUID()
                confirmOperations[id] = operationID
                let saved = await model.apply(.confirmArrangement(id: id), operationID: operationID)
                guard saved else { break }
                confirmOperations.removeValue(forKey: id)
            }
            working = false
        }
    }

    // MARK: Text helpers
    private func childLine(_ id: UUID) -> String {
        workspace.children.count > 1 ? (workspace.children.first { $0.id == id }?.name ?? ui("孩子已删除")) + " · " : ""
    }
    private func timeText(_ date: Date) -> String {
        Calendar.autoupdatingCurrent.isDate(date, inSameDayAs: now) ? date.formatted(.dateTime.hour().minute())
                                                                      : date.formatted(.dateTime.month().day().hour().minute())
    }
}

// MARK: - Inside 「全部安排」

/// The only hand-filled-form entry on the Today side: small, quiet, one level down (inside 「全部安排」).
struct ParentingTodayManualEntry: View {
    let busy: Bool
    let action: () -> Void
    @Environment(\.locale) private var locale
    var body: some View {
        Button(action: action) {
            Label(AppLanguage(locale: locale).ui("手动填写一件事"), systemImage: "square.and.pencil")
                .font(.footnote).frame(minHeight: 44, alignment: .leading)
        }
        .buttonStyle(.plain)
        .foregroundStyle(catColors.calmSecondary)
        .disabled(busy)
        .accessibilityIdentifier("parenting.addArrangement")
    }
}
