import SwiftUI

/// What the growth home can ask its host to do. The host owns sheets, deletion and navigation; this view never writes the ledger.
struct ParentingGrowthHomeActions {
    var selectChild: (UUID) -> Void
    var addChild: () -> Void
    /// child, goal (nil = unlinked observation)
    var addObservation: (UUID, UUID?) -> Void
    var addGoal: (UUID) -> Void
    var openGoals: () -> Void
    var openTimeline: () -> Void
    var renameChild: (ParentingChild) -> Void
    var deleteChild: (UUID) -> Void
}

/// 成长首页: one recommended starting action, one picture of the last seven days, and two quiet doors to the sub pages.
/// Goals, the full timeline and every secondary action live one level down.
struct ParentingGrowthHomeView: View {
    let workspace: ParentingWorkspace
    let childID: UUID?
    let now: Date
    let busy: Bool
    let actions: ParentingGrowthHomeActions
    var calendar: Calendar = .autoupdatingCurrent
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private func ui(_ key: String, _ arguments: [String] = []) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }
    private func accent(_ id: UUID) -> Color { ParentingChildAccent.color(slot: ParentingGrowthInsights.accentSlot(for: id, in: workspace)) }

    var body: some View {
        if let child = ParentingGrowthInsights.scopedChild(in: workspace, preferred: childID) {
            let start = ParentingGrowthStart.choose(workspace: workspace, childID: child.id, now: now, calendar: calendar)
            let density = ParentingWeekDensity.build(workspace: workspace, childID: child.id, now: now, calendar: calendar)
            let goals = ParentingGoalProgress.build(workspace: workspace, childID: child.id, now: now, calendar: calendar)
            let recordCount = workspace.records.filter { $0.childID == child.id }.count
            VStack(alignment: .leading, spacing: 20) {
                if workspace.children.count > 1 { chips(selected: child.id) }
                header(child)
                startCard(start, child: child, goals: goals)
                ParentingWeekDensityView(density: density, calendar: calendar)
                doors(child: child, goalCount: goals.count, recordCount: recordCount)
            }
            .accessibilityIdentifier("parenting.growth.home")
        }
    }

    // MARK: Child

    private func chips(selected: UUID) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(workspace.children) { child in
                    Button { actions.selectChild(child.id) } label: {
                        HStack(spacing: 8) {
                            ParentingChildRingAvatar(name: child.name, accent: accent(child.id), size: 28)
                            Text(verbatim: child.name).font(.subheadline.weight(selected == child.id ? .semibold : .regular))
                        }
                        .padding(.horizontal, 12).frame(minHeight: 44)
                        .background(selected == child.id ? catColors.card : catColors.fillSelected.opacity(0.6), in: Capsule())
                        .overlay(Capsule().stroke(selected == child.id ? catColors.ink.opacity(0.35) : Color.clear, lineWidth: 1))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(catColors.ink)
                    .accessibilityAddTraits(selected == child.id ? [.isSelected] : [])
                    .accessibilityIdentifier("parenting.growth.child." + child.id.uuidString)
                }
            }
            .padding(.vertical, 1)
        }
        .accessibilityIdentifier("parenting.growth.childChips")
    }

    private func header(_ child: ParentingChild) -> some View {
        HStack(alignment: .center, spacing: 12) {
            ParentingChildRingAvatar(name: child.name, accent: accent(child.id), size: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: child.name).font(.title3.weight(.semibold))
                Text(verbatim: ui("开始记录于") + " " + child.createdAt.formatted(date: .abbreviated, time: .omitted))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
            Spacer(minLength: 0)
            Menu {
                Button(ui("修改昵称")) { actions.renameChild(child) }
                Button(ui("删除孩子"), role: .destructive) { actions.deleteChild(child.id) }
            } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
            .accessibilityLabel(ui("孩子资料操作")).disabled(busy)
        }
    }

    // MARK: The one start action

    private struct StartCopy {
        let symbol: String
        let title: String
        let why: String
        let button: String
    }

    private func copy(for start: ParentingGrowthStart, child: ParentingChild, goals: [ParentingGoalProgress]) -> StartCopy {
        switch start.kind {
        case .addChild:
            return StartCopy(symbol: "person.badge.plus", title: ui("先添加孩子的昵称"), why: ui("不用填写生日和健康信息。"), button: ui("添加孩子"))
        case .firstObservation:
            return StartCopy(symbol: "text.bubble", title: ui("记下今天的第一条观察"),
                             why: ui("一句话就够，也可以直接对下面的小咪说。"), button: ui("记下观察"))
        case .recordForGoal(let goalID):
            let progress = goals.first { $0.goal.id == goalID }
            let why: String
            if let days = progress?.daysSinceActivity { why = ui("已经 {0} 天没有新记录了。", [String(days)]) }
            else { why = ui("这个目标还没有记录。") }
            return StartCopy(symbol: "leaf", title: ui("给「{0}」添一笔", [progress?.goal.title ?? ""]), why: why, button: ui("记下观察"))
        case .makeGoal(let count):
            return StartCopy(symbol: "flag", title: ui("把这些观察变成一个小目标"),
                             why: ui("已经记下 {0} 条观察，可以给它们起个名字。", [String(count)]), button: ui("添加目标"))
        case .recordObservation:
            let last = workspace.records.filter { $0.childID == child.id }.map(\.createdAt).max()
            let days = last.map { max(0, calendar.dateComponents([.day], from: calendar.startOfDay(for: $0), to: calendar.startOfDay(for: now)).day ?? 0) }
            return StartCopy(symbol: "text.bubble", title: ui("今天有什么想留下的小事？"),
                             why: days.map { ui("上一条记录是 {0} 天前。", [String($0)]) } ?? ui("一句话就够，也可以直接对下面的小咪说。"),
                             button: ui("记下观察"))
        case .reviewWeek:
            return StartCopy(symbol: "calendar", title: ui("这一周的轨迹"),
                             why: ui("最近没有需要补记的事，翻翻这几天吧。"), button: ui("看时间线"))
        }
    }

    private func perform(_ start: ParentingGrowthStart, child: ParentingChild) {
        switch start.kind {
        case .addChild: actions.addChild()
        case .firstObservation, .recordObservation: actions.addObservation(child.id, nil)
        case .recordForGoal(let goalID): actions.addObservation(child.id, goalID)
        case .makeGoal: actions.addGoal(child.id)
        case .reviewWeek: actions.openTimeline()
        }
    }

    private func startCard(_ start: ParentingGrowthStart, child: ParentingChild, goals: [ParentingGoalProgress]) -> some View {
        let text = copy(for: start, child: child, goals: goals)
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Label(ui("先从这里开始"), systemImage: text.symbol)
                        .font(catTypography.Role.caption.font.weight(.medium)).foregroundStyle(catColors.calmSecondary)
                    Text(verbatim: text.title).font(.title3.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: text.why).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !dynamicTypeSize.isAccessibilitySize {
                    BrandMark(size: 36, style: .fill).accessibilityHidden(true)
                }
            }
            Button { perform(start, child: child) } label: { Text(verbatim: text.button) }
                .parentingGrowthPrimaryButton()
                .disabled(busy)
                .accessibilityIdentifier("parenting.growth.start.action")
        }
        .parentingCard()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.growth.start")
    }

    // MARK: Doors to the sub pages

    private func doors(child: ParentingChild, goalCount: Int, recordCount: Int) -> some View {
        VStack(spacing: 0) {
            door(symbol: "flag", title: ui("成长目标"),
                 subtitle: goalCount == 0 ? ui("还没有目标") : ui("{0} 个目标", [String(goalCount)]),
                 identifier: "parenting.growth.mode.goals", action: actions.openGoals)
            Divider().overlay(catColors.hairline).padding(.leading, 52)
            door(symbol: "clock.arrow.circlepath", title: ui("成长时间线"),
                 subtitle: recordCount == 0 ? ui("还没有记录") : ui("{0} 条记录", [String(recordCount)]),
                 identifier: "parenting.growth.mode.timeline", action: actions.openTimeline)
        }
        .padding(.horizontal, 17)
        .background(catColors.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(catColors.hairline, lineWidth: 0.5))
    }

    private func door(symbol: String, title: String, subtitle: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.title3).foregroundStyle(catColors.ink).frame(width: 24).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: title).font(.subheadline.weight(.semibold))
                    Text(verbatim: subtitle).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(catColors.ink)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(identifier)
    }
}
