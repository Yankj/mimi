import SwiftUI

/// The face of one child card on the 我们家 page. The card itself is a `NavigationLink` built by the caller.
struct ParentingFamilyChildRow: View {
    let summary: ParentingFamilyChildSummary
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(spacing: 12) {
            ParentingChildAvatar(accent: ParentingChildAccent.color(slot: summary.accentSlot), size: 48, childID: summary.id)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: summary.name).font(.headline)
                Text(verbatim: AppLanguage(locale: locale).ui(
                    "{0} 个目标 · {1} 条观察",
                    arguments: [String(summary.goalCount), String(summary.observationCount)]))
                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(catTypography.Role.caption.font)
                .foregroundStyle(catColors.calmSecondary).accessibilityHidden(true)
        }
        .foregroundStyle(catColors.ink)
        .frame(minHeight: 50)
    }
}

/// A one-line entry on the 我们家 page (提醒, 设置). Pushes a sub page; carries no settings of its own.
struct ParentingFamilyEntryRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(catColors.actionText).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: title).font(.subheadline.weight(.medium))
                Text(verbatim: subtitle).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(catTypography.Role.caption.font)
                .foregroundStyle(catColors.calmSecondary).accessibilityHidden(true)
        }
        .foregroundStyle(catColors.ink)
        .frame(minHeight: 50)
    }
}

/// 启航 n/3. Display only; the steps come from `ParentingStartAction.launchChecklist`.
struct ParentingFamilyLaunchCard: View {
    let checklist: FamilyLaunchChecklist
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(AppLanguage(locale: locale).ui(
                "启航 {0}/{1}", arguments: [String(checklist.doneCount), String(checklist.totalCount)]),
                  systemImage: "flag")
                .font(.headline).foregroundStyle(catColors.ink)
            ProgressView(value: Double(checklist.doneCount), total: Double(max(checklist.totalCount, 1)))
                .tint(catColors.action)
            ForEach(checklist.steps) { step in
                Label {
                    Text(ui(step.title)).font(.subheadline).foregroundStyle(catColors.ink)
                } icon: {
                    Image(systemName: step.isDone ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(step.isDone ? catColors.actionText : catColors.calmSecondary)
                }
            }
        }
        .parentingCard()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.family.launch")
    }
}

/// Child profile page, pushed from a child card. Shows only what the ledger already stores.
/// Rename and delete reuse the existing editor sheet and delete confirmation owned by the home view.
@MainActor struct ParentingFamilyChildDetailView: View {
    @ObservedObject var model: ParentingWorkspaceModel
    let childID: UUID
    let onRename: (ParentingChild) -> Void
    let onDelete: (UUID) -> Void
    var onChangeAvatar: (ParentingChild) -> Void = { _ in }
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var child: ParentingChild? { model.workspace.children.first { $0.id == childID } }

    var body: some View {
        Group {
            if let current = child {
                detail(current)
            } else {
                Text(ui("孩子已删除")).font(.subheadline).foregroundStyle(catColors.calmSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("parenting.family.childDetail.deleted")
            }
        }
        .background(catColors.background.ignoresSafeArea())
        .navigationTitle(Text(verbatim: child?.name ?? ui("孩子已删除")))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detail(_ child: ParentingChild) -> some View {
        let summary = ParentingFamilySummary.summary(of: child, in: model.workspace)
        return Form {
            Section {
                HStack(spacing: 16) {
                    ParentingChildAvatar(accent: ParentingChildAccent.color(slot: summary.accentSlot), size: 48, childID: summary.id)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: summary.name).font(.title3.weight(.semibold))
                        Text(verbatim: ui("开始记录于") + " " + summary.startedAt.formatted(date: .abbreviated, time: .omitted))
                            .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("parenting.family.childDetail.header")
            }
            Section {
                LabeledContent(ui("成长目标")) { Text(verbatim: String(summary.goalCount)) }
                    .accessibilityIdentifier("parenting.family.childDetail.goals")
                LabeledContent(ui("日常观察")) { Text(verbatim: String(summary.observationCount)) }
                    .accessibilityIdentifier("parenting.family.childDetail.observations")
            } header: { Text(ui("已有记录")) }
            footer: { Text(ui("这里只显示已经记下的昵称、目标和观察。")) }
            Section {
                Button(ui("修改昵称")) { onRename(child) }
                    .disabled(model.busy)
                    .accessibilityIdentifier("parenting.family.childDetail.rename")
                Button(ui("更换头像")) { onChangeAvatar(child) }
                    .disabled(model.busy)
                    .accessibilityIdentifier("parenting.family.childDetail.avatar")
            }
            Section {
                Button(ui("移除孩子资料"), role: .destructive) { onDelete(child.id) }
                    .disabled(model.busy)
                    .accessibilityIdentifier("parenting.family.childDetail.delete")
            } footer: { Text(ui("会一并删除这个孩子的目标、安排、记录和头像，且无法恢复。移除前会再确认一次。")) }
        }
        .scrollContentBackground(.hidden)
        .foregroundStyle(catColors.ink)
        .accessibilityIdentifier("parenting.family.childDetail")
    }
}

/// 提醒 sub page. The reminder settings that used to sit inside the settings sheet, unchanged: the same switches,
/// the same identifiers, and the same rule of at most 2 reminders a day with quiet hours from 21:00 to 08:00.
@MainActor struct ParentingRemindersView: View {
    @ObservedObject var model: ParentingWorkspaceModel
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private var authorization: ParentingNotificationAuthorization? { model.reminderStatus?.authorization }

    var body: some View {
        Form {
            if model.remindersAvailable {
                Section {
                    Toggle(ui("到点提醒"), isOn: Binding(
                        get: { model.reminderSettings.enabled },
                        set: { value in Task { await model.setReminders(enabled: value) } }))
                        .disabled(model.busy || !model.loaded)
                        .accessibilityIdentifier("parenting.reminders.toggle")
                    if model.reminderSettings.enabled {
                        Picker(ui("提醒时间"), selection: Binding(
                            get: { model.reminderSettings.lead },
                            set: { value in Task { await model.setReminderLead(value) } })) {
                            ForEach(ParentingReminderLead.allCases) { lead in Text(ui(lead.title)).tag(lead) }
                        }
                        .disabled(model.busy || !model.loaded)
                        .accessibilityIdentifier("parenting.reminders.lead")
                        Toggle(ui("锁屏上隐藏孩子和事项"), isOn: Binding(
                            get: { model.reminderSettings.hideContent },
                            set: { value in Task { await model.setReminderContentHidden(value) } }))
                            .disabled(model.busy || !model.loaded)
                            .accessibilityIdentifier("parenting.reminders.hideContent")
                        if authorization == .denied {
                            Label(ui("系统通知已关闭，小咪现在无法提醒你"), systemImage: "bell.slash")
                                .foregroundStyle(catColors.failure)
                            Button(ui("打开系统设置")) { if let url = URL(string: "app-settings:") { openURL(url) } }
                                .frame(minHeight: 44)
                        } else if let status = model.reminderStatus, authorization == .authorized {
                            Text(AppLanguage(locale: locale).ui("已为 {0} 件安排在本机排好提醒", arguments: [String(status.scheduledCount)]))
                                .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                                .accessibilityIdentifier("parenting.reminders.count")
                            if status.systemRefused {
                                Text(ui("有提醒未能确认已排好，请重新打开小咪后再看。"))
                                    .font(catTypography.Role.caption.font).foregroundStyle(catColors.attention)
                            }
                        }
                    }
                    Text(ui("只提醒已确认的安排；每天最多 2 条，晚 9 点到早 8 点不提醒（会改在前一晚 8:30）。提醒只在本机发送，不经过服务器，也不会添加系统日历。"))
                        .font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
                }
            } else {
                Section {
                    Text(ui("这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。"))
                        .font(.subheadline).foregroundStyle(catColors.calmSecondary)
                        .accessibilityIdentifier("parenting.family.reminders.unavailable")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(catColors.background.ignoresSafeArea())
        .foregroundStyle(catColors.ink)
        .navigationTitle(ui("提醒"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("parenting.family.reminders.page")
    }
}
