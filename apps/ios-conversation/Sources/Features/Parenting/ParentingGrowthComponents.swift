import SwiftUI

// Building blocks for the 成长 tab (v0.6 S4): child accent, tinted-glass primary button, progress ring, week density bars,
// goal mark track, timeline dot. Colours come only from `catColors`; the child accent is the one place that maps a slot to a token,
// so the theme PR (B/C palettes) swaps it in one line. Child accents are used for avatar rings, small dots and progress rings only.

/// Five accent slots for children. They reuse existing semantic tokens until the theme PR introduces dedicated child tokens.
enum ParentingChildAccent {
    static func color(slot: Int) -> Color {
        let count = ParentingGrowthInsights.accentSlotCount
        switch ((slot % count) + count) % count {
        case 0: return catColors.secondary        // graphite
        case 1: return catColors.send             // fog blue
        case 2: return catColors.success          // sage
        case 3: return catColors.calmLavender     // lavender
        default: return catColors.calmPeach       // rose / peach
        }
    }
}

/// A translucent glass fill with a little orange in it. Never a solid orange block: iOS 26+ tints the system Liquid Glass,
/// earlier systems get a material (opaque card with Reduce Transparency) with a faint orange wash and a thin orange edge.
struct ParentingTintedGlass: ViewModifier {
    var enabled = true
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.catGlass(in: Capsule(), interactive: enabled, tint: enabled ? ParentingGrowthStyle.primaryTint : nil)
        } else {
            content
                .background {
                    ZStack {
                        if reduceTransparency { Capsule().fill(catColors.card) } else { Capsule().fill(.regularMaterial) }
                        if enabled { Capsule().fill(ParentingGrowthStyle.primaryTint) }
                    }
                }
                .overlay(Capsule().stroke(enabled ? catColors.action.opacity(0.4) : catColors.hairline, lineWidth: enabled ? 1 : 0.5))
        }
    }
}

enum ParentingGrowthStyle {
    /// Share of orange in the primary glass button (C day theme: 20%).
    static let primaryTintOpacity: Double = 0.20
    static var primaryTint: Color { catColors.action.opacity(primaryTintOpacity) }
}

/// Primary action of the growth screens: tinted glass capsule, ink label. Secondary actions keep `.parentingSecondaryButton()`.
struct ParentingGrowthPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(isEnabled ? catColors.ink : catColors.secondary)
            .padding(.horizontal, 22).padding(.vertical, 12)
            .frame(minHeight: 48)
            .modifier(ParentingTintedGlass(enabled: isEnabled))
            .contentShape(Capsule())
            .scaleEffect(configuration.isPressed && isEnabled ? 0.98 : 1)
    }
}

extension View {
    func parentingGrowthPrimaryButton() -> some View { buttonStyle(ParentingGrowthPrimaryButtonStyle()) }
}

/// Ring with a centre slot. `fraction == nil` draws a dashed empty track (nothing to measure yet).
struct ParentingProgressRing<Center: View>: View {
    let fraction: Double?
    let accent: Color
    var lineWidth: CGFloat = 7
    @ViewBuilder var center: Center
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().stroke(catColors.fillSelected, style: StrokeStyle(lineWidth: lineWidth, dash: fraction == nil ? [2, 6] : []))
            if let fraction {
                Circle()
                    .trim(from: 0, to: max(0.02, min(1, fraction)))
                    .stroke(accent, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            center
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.45), value: fraction)
    }
}

/// Seven bars for the last seven days. Bars are graphite; today is the single orange accent. No child colour here.
struct ParentingWeekDensityView: View {
    let density: ParentingWeekDensity
    var calendar: Calendar = .autoupdatingCurrent
    @Environment(\.locale) private var locale
    @ScaledMetric(relativeTo: .body) private var barHeight: CGFloat = 64

    private func ui(_ key: String, _ arguments: [String] = []) -> String { AppLanguage(locale: locale).ui(key, arguments: arguments) }

    private var summary: String {
        density.total == 0
            ? ui("这 7 天还没有记录")
            : ui("{0} 条记录 · {1} 天有记录", [String(density.total), String(density.activeDays)])
    }
    private func weekdayInitial(_ date: Date) -> String {
        var local = calendar
        local.locale = locale
        let symbols = local.veryShortWeekdaySymbols
        let index = local.component(.weekday, from: date) - 1
        return symbols.indices.contains(index) ? symbols[index] : ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text(ui("最近 7 天")).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Text(verbatim: summary).font(catTypography.Role.caption.font).foregroundStyle(catColors.calmSecondary)
            }
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(density.days) { day in
                    VStack(spacing: 6) {
                        ZStack(alignment: .bottom) {
                            Color.clear.frame(height: barHeight)
                            bar(for: day)
                        }
                        Text(verbatim: weekdayInitial(day.date))
                            .font(catTypography.Role.caption.font.weight(day.isToday ? .semibold : .regular))
                            .foregroundStyle(day.isToday ? catColors.ink : catColors.calmSecondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .parentingCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: ui("最近 7 天") + "，" + summary))
        .accessibilityIdentifier("parenting.growth.week")
    }

    @ViewBuilder private func bar(for day: ParentingWeekDensity.Day) -> some View {
        if day.total == 0 {
            Capsule().fill(catColors.fillSelected).frame(width: 8, height: 8)
        } else {
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(day.isToday ? catColors.action : catColors.ink.opacity(0.72))
                .frame(maxWidth: 24)
                .frame(height: max(10, barHeight * density.level(of: day)))
        }
    }
}

/// Four milestone nodes on a thin track. Reached nodes are filled with the child's accent; the numbers are record counts.
struct ParentingGoalMarksTrack: View {
    let marks: [ParentingGoalProgress.Mark]
    let accent: Color
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(marks.enumerated()), id: \.element.id) { index, mark in
                if index > 0 {
                    Rectangle()
                        .fill(mark.reached ? accent : catColors.fillSelected)
                        .frame(height: 2).frame(maxWidth: .infinity)
                        .padding(.top, 6)
                }
                VStack(spacing: 4) {
                    Circle()
                        .fill(mark.reached ? accent : catColors.card)
                        .overlay(Circle().stroke(mark.reached ? accent : catColors.separator, lineWidth: 1.5))
                        .frame(width: 14, height: 14)
                    Text(verbatim: String(mark.threshold))
                        .font(catTypography.Role.caption.font.monospacedDigit())
                        .foregroundStyle(mark.reached ? catColors.ink : catColors.calmSecondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: AppLanguage(locale: locale).ui(
            "记录节点 {0}/{1}", arguments: [String(marks.filter(\.reached).count), String(marks.count)])))
    }
}

/// The one child avatar (今天, 成长, 我们家): the existing plush cat in a circular mask on a faint wash of the child's accent,
/// with a ring in that accent. Only the mask and the ring differ between children; the cat image is the same asset.
struct ParentingChildAvatar: View {
    let accent: Color
    var size: CGFloat = 44
    var pose: PlushMascotPose = .idle
    private var ring: CGFloat { max(2, size * 0.065) }
    var body: some View {
        ZStack {
            Circle().fill(catColors.card)
            Circle().fill(accent.opacity(0.18))
            Image(pose.assetName)
                .resizable().interpolation(.high).scaledToFit()
                .padding(size * 0.14)
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(accent, lineWidth: ring))
        .accessibilityHidden(true)
    }
}

/// A dot on the timeline rail. Milestones get a ring around the dot.
struct ParentingTimelineDot: View {
    let accent: Color
    var milestone = false
    var body: some View {
        ZStack {
            if milestone { Circle().stroke(accent, lineWidth: 2).frame(width: 18, height: 18) }
            Circle().fill(accent).frame(width: milestone ? 8 : 10, height: milestone ? 8 : 10)
        }
        .frame(width: 18, height: 18)
        .accessibilityHidden(true)
    }
}

/// Scrolling page for growth sub screens: brand canvas, readable width, content scrolls under the system bars.
struct ParentingGrowthPageScaffold<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) { content }
                .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 28)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .catSoftScrollEdges()
        .background(catColors.background.ignoresSafeArea())
        .foregroundStyle(catColors.ink)
        .navigationBarTitleDisplayMode(.inline)
    }
}
