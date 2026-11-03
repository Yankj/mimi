import SwiftUI

// Sub pages of the 成长 tab: goals (progress ring + record-count marks) and the timeline (day groups on a rail, milestone nodes).
// The host (ParentingHomeView) supplies record cards and the goal review, so edit/delete/provenance keep working unchanged.

// MARK: - Goals

struct ParentingGrowthGoalsPage<Review: View>: View {
    let workspace: ParentingWorkspace
    let child: ParentingChild
    let now: Date
    let selectedGoalID: UUID?
    let busy: Bool
    let onSelect: (UUID?) -> Void
    let onAdd: () -> Void
    let onDelete: (UUID) -> Void
    var calendar: Calendar = .autoupdatingCurrent
    private let review: () -> Review
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .title2) private var ringSize: CGFloat = 64

    init(workspace: ParentingWorkspace, child: ParentingChild, now: Date, selectedGoalID: UUID?, busy: Bool,
         onSelect: @escaping (UUID?) -> Void, onAdd: @escaping () -> Void, onDelete: @escaping (UUID) -> Void,
         calendar: Calendar = .autoupdatingCurrent, @ViewBuilder review: @escaping () -> Review) {
        self.workspace = workspace; self.child = child; self.now = now; self.selectedGoalID = selectedGoalID; self.busy = busy
        self.onSelect = onSelect; self.onAdd = onAdd; self.onDelete = onDelete; self.calendar = calendar; self.review = review
    }

    private func ui(_ key: String, _ arguments: [String] = []) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }
    private var accent: Color { ParentingChildAccent.color(slot: ParentingGrowthInsights.accentSlot(for: child.id, in: workspace)) }

    var body: some View {
        let goals = ParentingGoalProgress.build(workspace: workspace, childID: child.id, now: now, calendar: calendar)
        // Active / recent first, quiet second — same orange-rail header as 今天 so goals are not a flat list.
        let active = goals.filter { $0.activity != .quiet }
        let quiet = goals.filter { $0.activity == .quiet }
        ParentingGrowthPageScaffold {
            HStack(spacing: 12) {
                ParentingChildAvatar(accent: accent, size: 40, childID: child.id)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: child.name).font(.title3.weight(.semibold))
                    Text(ui("目标由你定义。安排是打算做的事，观察和完成记录是已经记下的事。"))
                        .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if goals.isEmpty {
                VStack(alignment: .leading, spacing: 14) {
                    Text(ui("还没有成长目标。也可以先记观察，之后再建立目标。"))
                        .font(.subheadline).foregroundStyle(catColors.calmSecondary)
                    Button(action: onAdd) { Label(ui("添加目标"), systemImage: "plus") }
                        .parentingGrowthPrimaryButton().disabled(busy)
                        .accessibilityIdentifier("parenting.addGoal")
                }.parentingCard()
            } else {
                if !active.isEmpty {
                    ParentingSectionHeader(title: ui("成长目标"), count: active.count)
                    ForEach(active) { progress in goalCard(progress) }
                }
                if !quiet.isEmpty {
                    ParentingSectionHeader(title: ui("有一阵没记了"), count: quiet.count)
                    ForEach(quiet) { progress in goalCard(progress) }
                }
                Button(action: onAdd) { Label(ui("添加目标"), systemImage: "plus").frame(maxWidth: .infinity) }
                    .parentingSecondaryButton().disabled(busy)
                    .accessibilityIdentifier("parenting.addGoal")
            }
            review()
        }
        .navigationTitle(ui("成长目标"))
    }

    private func recency(_ progress: ParentingGoalProgress) -> String {
        if progress.recordCount == 0 { return ui("还没有记录") }
        let count = ui("已记 {0} 条", [String(progress.recordCount)])
        switch progress.daysSinceActivity ?? 0 {
        case 0: return count + " · " + ui("今天有新记录")
        case let days: return count + " · " + ui("{0} 天前", [String(days)])
        }
    }

    private func goalCard(_ progress: ParentingGoalProgress) -> some View {
        let selected = selectedGoalID == progress.goal.id
        let ringContent = Group {
            if progress.fraction != nil {
                Text(verbatim: "\(progress.doneArrangements)/\(progress.openArrangements + progress.doneArrangements)")
                    .font(catTypography.Role.caption.font.weight(.semibold).monospacedDigit())
                    .accessibilityHidden(true)
            } else {
                Image(systemName: "leaf").font(.title3).foregroundStyle(catColors.calmSecondary).accessibilityHidden(true)
            }
        }
        return VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 8) {
                Button { onSelect(selected ? nil : progress.goal.id) } label: {
                    Group {
                        if dynamicTypeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 12) { ring(progress, content: ringContent); texts(progress, selected: selected) }
                        } else {
                            HStack(alignment: .center, spacing: 14) { ring(progress, content: ringContent); texts(progress, selected: selected) }
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("parenting.goal.\(progress.goal.id)")
                Button(role: .destructive) { onDelete(progress.goal.id) } label: {
                    Image(systemName: "trash").frame(width: 44, height: 44)
                }
                .accessibilityLabel(ui("删除目标")).disabled(busy)
            }
            ParentingGoalMarksTrack(marks: progress.marks, accent: accent)
            if let due = progress.nextDue {
                Label(ui("下一件 {0}", [due.formatted(.dateTime.month().day().hour().minute())]), systemImage: "calendar")
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
        }
        .parentingCard()
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(selected ? catColors.ink.opacity(0.4) : Color.clear, lineWidth: 1.5))
        .accessibilityElement(children: .contain)
    }

    private func ring<C: View>(_ progress: ParentingGoalProgress, content: C) -> some View {
        ParentingProgressRing(fraction: progress.fraction, accent: accent) { content }
            .frame(width: ringSize, height: ringSize)
    }

    private func texts(_ progress: ParentingGoalProgress, selected: Bool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(verbatim: progress.goal.title).font(.headline).foregroundStyle(catColors.ink)
                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
            Text(verbatim: recency(progress)).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            if progress.activity == .quiet {
                Text(ui("有一阵没记了")).font(catTypography.Role.caption.font.weight(.medium)).foregroundStyle(catColors.attention)
            } else {
                Text(ui(selected ? "正在回顾这个目标" : "查看相关安排与记录"))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
        }
    }
}

// MARK: - Timeline

struct ParentingGrowthTimelinePage<Header: View, RecordCard: View>: View {
    let workspace: ParentingWorkspace
    let childID: UUID?
    @Binding var filter: ParentingGrowthTimeline.Filter
    let now: Date
    let busy: Bool
    let onAddObservation: () -> Void
    var calendar: Calendar = .autoupdatingCurrent
    private let header: () -> Header
    private let recordCard: (ParentingRecord) -> RecordCard
    @Environment(\.locale) private var locale

    init(workspace: ParentingWorkspace, childID: UUID?, filter: Binding<ParentingGrowthTimeline.Filter>, now: Date, busy: Bool,
         onAddObservation: @escaping () -> Void, calendar: Calendar = .autoupdatingCurrent,
         @ViewBuilder header: @escaping () -> Header, @ViewBuilder recordCard: @escaping (ParentingRecord) -> RecordCard) {
        self.workspace = workspace; self.childID = childID; self._filter = filter; self.now = now; self.busy = busy
        self.onAddObservation = onAddObservation; self.calendar = calendar; self.header = header; self.recordCard = recordCard
    }

    private func ui(_ key: String, _ arguments: [String] = []) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }
    private func accent(_ id: UUID) -> Color { ParentingChildAccent.color(slot: ParentingGrowthInsights.accentSlot(for: id, in: workspace)) }

    private func filterTitle(_ value: ParentingGrowthTimeline.Filter) -> String {
        switch value { case .all: "全部记录"; case .observation: "日常观察"; case .completion: "家长完成" }
    }

    var body: some View {
        let journey = ParentingGrowthJourney.build(workspace: workspace, childID: childID, filter: filter, calendar: calendar)
        ParentingGrowthPageScaffold {
            header()
            Picker(ui("记录类型"), selection: $filter) {
                ForEach(ParentingGrowthTimeline.Filter.allCases, id: \.self) { value in Text(ui(filterTitle(value))).tag(value) }
            }
            .pickerStyle(.segmented)
            if journey.isEmpty {
                Text(ui("这里还没有记录。观察可以直接保存；安排完成后会生成独立的完成记录。"))
                    .font(.subheadline).foregroundStyle(catColors.calmSecondary).parentingCard()
            }
            ForEach(journey.days) { day in daySection(day) }
            Button(action: onAddObservation) {
                Label(ui("手动记下观察"), systemImage: "square.and.pencil").frame(maxWidth: .infinity)
            }
            .parentingSecondaryButton().disabled(busy)
            .accessibilityIdentifier("parenting.addObservation")
        }
        .navigationTitle(ui("成长时间线"))
    }

    private func dayTitle(_ day: Date) -> String {
        if calendar.isDate(day, inSameDayAs: now) { return ui("今天") }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(day, inSameDayAs: yesterday) { return ui("昨天") }
        return day.formatted(.dateTime.month().day().weekday(.wide))
    }

    private func daySection(_ day: ParentingGrowthDay) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // Same rail + count chip as 今天 sections, so day groups read as levels not a flat list.
            ParentingSectionHeader(title: dayTitle(day.day), count: day.items.count)
            VStack(alignment: .leading, spacing: 14) {
                ForEach(day.items) { item in
                    HStack(alignment: .top, spacing: 10) {
                        ParentingTimelineDot(accent: accent(item.childID), milestone: milestone(of: item) != nil).padding(.top, 4)
                        itemContent(item)
                    }
                }
            }
            .background(alignment: .topLeading) {
                Rectangle().fill(catColors.separator).frame(width: 2).padding(.leading, 8).padding(.vertical, 10)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.growth.day." + String(Int(day.day.timeIntervalSince1970)))
    }

    private func milestone(of item: ParentingGrowthDay.Item) -> ParentingGrowthMilestone? {
        if case .record(_, let milestone) = item { return milestone }
        return nil
    }

    @ViewBuilder private func itemContent(_ item: ParentingGrowthDay.Item) -> some View {
        switch item {
        case .record(let record, let milestone):
            VStack(alignment: .leading, spacing: 8) {
                if let milestone {
                    Label(milestoneText(milestone), systemImage: "sparkles")
                        .font(catTypography.Role.caption.font.weight(.semibold)).foregroundStyle(catColors.ink)
                        .accessibilityIdentifier("parenting.growth.milestone." + record.id.uuidString)
                }
                recordCard(record)
            }
        case .goalStarted(let goal):
            Label { Text(verbatim: ui("开始了目标「{0}」", [goal.title])) } icon: { Image(systemName: "flag") }
                .font(.subheadline).foregroundStyle(catColors.calmSecondary)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                .accessibilityElement(children: .combine)
        }
    }

    private func milestoneText(_ milestone: ParentingGrowthMilestone) -> String {
        switch milestone {
        case .firstRecord: return ui("留下了第一条记录")
        case .recordCount(let count): return ui("第 {0} 条记录", [String(count)])
        }
    }
}
