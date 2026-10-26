import SwiftUI

/// At-a-glance strip: how many are waiting, how many are on today, how many are done, and what is next.
struct ParentingTodaySummaryView: View {
    let summary: ParentingTodayTimeline.Summary
    let childName: (UUID) -> String
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }
    private func ui(_ key: String, _ arguments: [String]) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                stat(value: summary.pending, label: "待确认", symbol: "hourglass", highlighted: summary.pending > 0)
                stat(value: summary.today, label: "今天安排", symbol: "calendar", highlighted: false)
                stat(value: summary.done, label: "已完成", symbol: "checkmark.circle", highlighted: false)
            }
            if let next = summary.next {
                Label {
                    Text(verbatim: ui("下一件：{0}", [next.title]) + " · " + childName(next.childID) + " · "
                         + next.dueAt.formatted(.dateTime.month().day().hour().minute()))
                        .font(.subheadline).fixedSize(horizontal: false, vertical: true)
                } icon: { Image(systemName: "arrow.forward.circle").foregroundStyle(catColors.actionText) }
                .accessibilityIdentifier("parenting.today.next")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.today.summary")
    }

    private func stat(value: Int, label: String, symbol: String, highlighted: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: symbol).foregroundStyle(highlighted ? catColors.attention : catColors.actionText).accessibilityHidden(true)
            Text(verbatim: String(value)).font(.title2.weight(.semibold).monospacedDigit())
            Text(ui(label)).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(12)
        .background(catColors.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(highlighted ? catColors.attention.opacity(0.6) : catColors.hairline, lineWidth: highlighted ? 1 : 0.5))
        .accessibilityElement(children: .combine)
    }
}

/// One stop on the day's rail: time on the left, a dot on the line, the item on the right.
enum ParentingTimelineRowStyle { case upcoming, done, observation, conflict }

struct ParentingTimelineRow<Content: View>: View {
    let time: Date
    let style: ParentingTimelineRowStyle
    let isLast: Bool
    @ViewBuilder let content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 6) {
                Text(verbatim: time.formatted(.dateTime.hour().minute())).font(catTypography.Role.caption.font.monospacedDigit())
                    .foregroundStyle(catColors.calmSecondary)
                marker
                if !isLast { Rectangle().fill(catColors.hairline).frame(width: 2).frame(maxHeight: .infinity) }
            }
            .frame(width: 46)
            .accessibilityHidden(true)
            content.padding(.bottom, isLast ? 0 : 12)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var marker: some View {
        switch style {
        case .upcoming: Circle().stroke(catColors.actionText, lineWidth: 2).frame(width: 12, height: 12)
        case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(catColors.success)
        case .observation: Image(systemName: "leaf.fill").foregroundStyle(catColors.actionText)
        case .conflict: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(catColors.attention)
        }
    }
}

/// The "now" line between what has passed and what is still ahead.
struct ParentingNowMarker: View {
    let now: Date
    @Environment(\.locale) private var locale
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(catColors.action).frame(width: 8, height: 8)
            Text(verbatim: AppLanguage(locale: locale).ui("现在") + " " + now.formatted(.dateTime.hour().minute()))
                .font(catTypography.Role.caption.font.weight(.semibold)).foregroundStyle(catColors.actionText)
            Rectangle().fill(catColors.action.opacity(0.4)).frame(height: 1)
        }
        .padding(.bottom, 10)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("parenting.today.nowMarker")
    }
}

/// A compact line for an observation written today; the full record stays on the growth page.
struct ParentingTimelineObservation: View {
    let record: ParentingRecord
    let childName: String
    @Environment(\.locale) private var locale
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Label(AppLanguage(locale: locale).ui("成长观察"), systemImage: "text.bubble")
                .font(catTypography.Role.caption.font.weight(.medium)).foregroundStyle(catColors.actionText)
            Text(verbatim: record.text).font(.subheadline).lineLimit(3)
            Text(verbatim: childName).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
        }
        .parentingCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("parenting.today.observation." + record.id.uuidString)
    }
}
