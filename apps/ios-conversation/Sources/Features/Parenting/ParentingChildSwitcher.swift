import SwiftUI

/// The one child picker for 成长, 全部安排 and 今天: a horizontal strip of avatar capsules.
/// - Selected: the child's own accent as a 2 pt outline and a faint fill, bold name, full-strength avatar.
/// - Not selected: no outline, regular secondary-colour name, softened avatar. Selection is never colour alone (weight and outline too).
/// - One child is still shown, selected, so the page always says who it is about.
/// - VoiceOver reads each chip as a button named after the child with the selected trait.
struct ParentingChildSwitcher: View {
    let children: [ParentingChild]
    /// The host's current choice; nil means 「全部」 where that exists.
    let selectedID: UUID?
    /// Adds a 「全部」 chip (only shown when there is more than one child).
    var allowsAll = false
    /// False on a question: with no selection, no chip is highlighted (never a silent guess).
    var fallsBackToFirst = true
    let accent: (UUID) -> Color
    let onSelect: (UUID?) -> Void
    var identifier = "parenting.childSwitcher"
    var chipIdentifierPrefix = "parenting.childSwitcher.chip."

    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    private var resolved: ParentingChildSwitcherSelection {
        ParentingChildSwitcherRules.resolve(selected: selectedID, childIDs: children.map(\.id), allowsAll: allowsAll, fallsBackToFirst: fallsBackToFirst)
    }

    var body: some View {
        let current = resolved
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if ParentingChildSwitcherRules.showsAllChip(childCount: children.count, allowsAll: allowsAll) {
                    chip(selected: current == .all, accent: catColors.ink, label: ui("全部"), childID: nil, identifier: chipIdentifierPrefix + "all")
                }
                ForEach(children) { child in
                    chip(selected: current == .child(child.id), accent: accent(child.id), label: child.name, childID: child.id,
                         identifier: chipIdentifierPrefix + child.id.uuidString)
                }
            }
            .padding(.vertical, 2).padding(.horizontal, 1)
        }
        .scrollClipDisabled()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }

    private func chip(selected: Bool, accent: Color, label: String, childID: UUID?, identifier: String) -> some View {
        Button { onSelect(childID) } label: {
            HStack(spacing: 8) {
                if let childID {
                    ParentingChildAvatar(accent: accent, size: 30, childID: childID)
                        .opacity(selected ? 1 : 0.7)
                } else {
                    Image(systemName: "person.2.fill").font(.footnote)
                        .foregroundStyle(selected ? catColors.ink : catColors.calmSecondary)
                        .frame(width: 30, height: 30)
                        .accessibilityHidden(true)
                }
                Text(verbatim: label)
                    .font(.subheadline.weight(selected ? .bold : .regular))
                    .foregroundStyle(selected ? catColors.ink : catColors.calmSecondary)
                    .lineLimit(1)
            }
            .padding(.leading, 7).padding(.trailing, 14)
            .frame(minHeight: 44)
            .background(selected ? accent.opacity(0.16) : catColors.card.opacity(0.6), in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? accent : catColors.hairline, lineWidth: selected ? 2 : 0.5))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier(identifier)
    }
}

/// The one 「添加孩子」 empty state. 今天, 成长 and 我们家 show this same card (same words, same style, same entry)
/// while there is no child, so the first-run guidance is said once and looks the same everywhere.
struct ParentingAddChildCard: View {
    let busy: Bool
    let onAdd: () -> Void
    @Environment(\.locale) private var locale
    private func ui(_ key: String) -> String { AppLanguage(locale: locale).ui(key) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "leaf").font(.title2).foregroundStyle(catColors.actionText).accessibilityHidden(true)
            Text(ui("从孩子的昵称开始")).font(.title2.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(ui("不用填写生日和健康信息。添加昵称后，就能记录安排、观察与成长目标。"))
                .font(.subheadline).foregroundStyle(catColors.calmSecondary).fixedSize(horizontal: false, vertical: true)
            Button(action: onAdd) {
                Text(ui("添加孩子")).font(.body.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 28)
            }
            .parentingTodayPrimaryGlassButton()
            .disabled(busy)
            .accessibilityIdentifier("parenting.firstChild")
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .parentingTodayGlassCard(cornerRadius: 28)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("parenting.emptyChild")
    }
}
