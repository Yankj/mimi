import XCTest
#if canImport(UIKit)
import SwiftUI
import UIKit
#endif
@testable import cat

/// Token tests for the selectable themes (CatTheme.swift). Pure value maths: no views, no UIKit rendering.
/// The WCAG 2.x relative-luminance / contrast implementation is local to this file on purpose, so the production
/// token code is not checked with its own arithmetic.
final class CatThemeTokenTests: XCTestCase {
    // MARK: WCAG 2.x (independent implementation)
    private func linear(_ channel: UInt32) -> Double {
        let c = Double(channel) / 255
        return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }
    private func luminance(_ hex: UInt32) -> Double {
        0.2126 * linear((hex >> 16) & 255) + 0.7152 * linear((hex >> 8) & 255) + 0.0722 * linear(hex & 255)
    }
    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (hi, lo) = (max(luminance(a), luminance(b)), min(luminance(a), luminance(b)))
        return (hi + 0.05) / (lo + 0.05)
    }
    private func hexString(_ v: UInt32) -> String { String(format: "#%06X", v) }

    private static let combos: [(name: String, palette: CatPalette, mode: CatMode)] = [
        ("C-day", .inkWhite, .light), ("C-night", .inkWhite, .dark), ("B-day", .softBlush, .light), ("B-night", .softBlush, .dark)]
    private func set(_ palette: CatPalette, _ mode: CatMode) -> CatColorSet { CatThemeTokens.tokens(for: palette).set(for: mode) }

    // MARK: defaults and preference separation
    func testDefaultPaletteIsInkWhiteAndAppearanceFollowsSystem() {
        XCTAssertEqual(CatPalette.default, .inkWhite)
        XCTAssertEqual(CatPalette.inkWhite.letter, "C")
        XCTAssertEqual(CatPalette.softBlush.letter, "B")
        XCTAssertEqual(CatAppearance.default, .system)
        XCTAssertEqual(CatPalette(preference: nil), .inkWhite)
        XCTAssertEqual(CatPalette(preference: "garbage"), .inkWhite)
        XCTAssertEqual(CatPalette(preference: "softBlush"), .softBlush)
        XCTAssertEqual(CatAppearance(preference: nil), .system)
        XCTAssertEqual(CatAppearance(preference: ""), .system)
        XCTAssertEqual(CatAppearance(preference: "dark"), .dark)
        XCTAssertEqual(CatAppearance(preference: "light"), .light)
        XCTAssertEqual(CatAppearance(preference: "system"), .system)
    }

    func testPaletteAndAppearanceUseSeparateKeysAndExistingAppearanceValues() {
        XCTAssertEqual(CatThemePreference.appearanceKey, "theme", "the stored appearance key must not change")
        XCTAssertEqual(CatThemePreference.paletteKey, "palette")
        XCTAssertNotEqual(CatThemePreference.paletteKey, CatThemePreference.appearanceKey)
        XCTAssertEqual(Set(CatAppearance.allCases.map(\.rawValue)), ["system", "light", "dark"])
    }

    // MARK: current palette
    func testCurrentPaletteSwitchesTokensAndNotifies() {
        let original = CatTheme.palette
        defer { CatTheme.palette = original }
        CatTheme.palette = .inkWhite
        XCTAssertEqual(CatTheme.colors.palette, .inkWhite)
        XCTAssertEqual(CatTheme.hex(.canvas, mode: .light), 0xFAF9F7)
        let posted = expectation(forNotification: CatTheme.paletteDidChange, object: nil)
        CatTheme.palette = .softBlush
        wait(for: [posted], timeout: 1)
        XCTAssertEqual(CatTheme.colors.palette, .softBlush)
        XCTAssertEqual(CatTheme.hex(.canvas, mode: .light), 0xFCF3EF)
        XCTAssertEqual(CatTheme.hex(.canvas, mode: .dark), 0x1C1714)
    }

    // MARK: token values equal the proposal JSON (mimi-brand-themes-tokens.json) — except ink3
    /// INTENTIONAL DEVIATION from the proposal JSON, for accessibility: the proposal's `ink3` misses 4.5:1 on at least one
    /// surface in every combination, so tertiary text (placeholders, captions) would fail WCAG AA. The shipped values are
    /// the nearest greys that pass 4.5:1 on canvas, card AND surface. Every other role must equal the JSON.
    private static let ink3ProposalToShipped: [String: (proposal: UInt32, shipped: UInt32)] = [
        "C-day": (0x8A8782, 0x6F6D69), "C-night": (0x76746F, 0x8F8E89), "B-day": (0x94857A, 0x73655A), "B-night": (0x9A8C80, 0x9C8E82)]
    private static let proposal: [String: [CatColorRole: UInt32]] = [
        "C-day": [.canvas: 0xFAF9F7, .card: 0xFFFFFF, .surface: 0xF2F0EC, .hairline: 0xE6E3DE, .track: 0xE6E3DE, .ink: 0x111111, .ink2: 0x5A5853, .ink3: 0x8A8782, .accentOn: 0xD15C22, .accentOnPressed: 0xAE4F1F, .orangeText: 0x111111, .success: 0x1E7B46, .successFill: 0xE7F4EC, .attention: 0x8A5A00, .attentionFill: 0xFFF3D6, .danger: 0xC0352B, .dangerFill: 0xFDECEA, .accent: 0xE85D1A],
        "C-night": [.canvas: 0x0B0B0C, .card: 0x1A1A1C, .surface: 0x262628, .hairline: 0x3A3A3D, .track: 0x3A3A3D, .ink: 0xF5F4F2, .ink2: 0xA9A7A2, .ink3: 0x76746F, .accentOn: 0xFF702A, .accentOnPressed: 0xFF945F, .orangeText: 0xF5F4F2, .success: 0x6FCF97, .successFill: 0x16281D, .attention: 0xF2C14E, .attentionFill: 0x2B2412, .danger: 0xFF8A80, .dangerFill: 0x331A18, .accent: 0xFF702A],
        "B-day": [.canvas: 0xFCF3EF, .card: 0xFFFCFA, .surface: 0xF2E6DC, .hairline: 0xEADBD0, .track: 0xF1E3D3, .ink: 0x33261D, .ink2: 0x6E5C4F, .ink3: 0x94857A, .accentOn: 0xC95821, .accentOnPressed: 0xAE4F20, .orangeText: 0xB8410A, .success: 0x3F6B3A, .successFill: 0xE6EFDA, .attention: 0x8F5200, .attentionFill: 0xFFEFCB, .danger: 0xB23B34, .dangerFill: 0xFBE0DC, .accent: 0xFF702A],
        "B-night": [.canvas: 0x1C1714, .card: 0x2F2721, .surface: 0x261F1A, .hairline: 0x3E342B, .track: 0x3E342B, .ink: 0xF7EEE5, .ink2: 0xCDBFB2, .ink3: 0x9A8C80, .accentOn: 0xFF8A52, .accentOnPressed: 0xFFA77D, .orangeText: 0xFFB48A, .success: 0xA9D49A, .successFill: 0x2C3A2A, .attention: 0xF0C46A, .attentionFill: 0x3C3018, .danger: 0xFF9C93, .dangerFill: 0x432522, .accent: 0xFF8A52],
    ]
    private static let proposalKids: [String: [UInt32]] = [
        "C-day": [0x3C3C43, 0x3E7CB1, 0x4A8A5F, 0x7566C2, 0xC2527A], "C-night": [0xD1D1D6, 0x6FA8DC, 0x6BB586, 0xA395E8, 0xE887A9],
        "B-day": [0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A], "B-night": [0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A]]

    func testEveryTokenMatchesTheProposal() {
        for combo in Self.combos {
            let s = set(combo.palette, combo.mode)
            let expected = Self.proposal[combo.name]!
            XCTAssertEqual(expected.count, CatColorRole.allCases.count, "\(combo.name): proposal table must cover every role")
            for role in CatColorRole.allCases {
                if role == .ink3 {
                    let pair = Self.ink3ProposalToShipped[combo.name]!
                    XCTAssertEqual(expected[.ink3], pair.proposal, "\(combo.name): the JSON table in this test holds the proposal ink3")
                    XCTAssertEqual(s.hex(.ink3), pair.shipped, "\(combo.name) ink3 is the deliberate accessibility override, not the JSON value")
                    continue
                }
                XCTAssertEqual(s.hex(role), expected[role], "\(combo.name) \(role.rawValue): \(hexString(s.hex(role)))")
            }
            XCTAssertEqual(s.kids, Self.proposalKids[combo.name], "\(combo.name) kid colours")
        }
    }

    // MARK: contrast — text ≥ 4.5:1 over all four combinations
    /// Text colour / background pairs of one combination (ink3 included: tertiary text must pass AA too).
    private func textPairs(_ s: CatColorSet) -> [(String, UInt32, UInt32)] {
        var pairs: [(String, UInt32, UInt32)] = []
        let surfaces: [(String, UInt32)] = [("canvas", s.canvas), ("card", s.card), ("surface", s.surface)]
        for (bgName, bg) in surfaces {
            pairs += [("ink/\(bgName)", s.ink, bg), ("ink2/\(bgName)", s.ink2, bg), ("ink3/\(bgName)", s.ink3, bg), ("orangeText/\(bgName)", s.orangeText, bg),
                      ("success/\(bgName)", s.success, bg), ("attention/\(bgName)", s.attention, bg), ("danger/\(bgName)", s.danger, bg)]
        }
        pairs += [("success/successFill", s.success, s.successFill), ("attention/attentionFill", s.attention, s.attentionFill),
                  ("danger/dangerFill", s.danger, s.dangerFill)]
        for (surfaceName, surface) in [("canvas", CatButtonSurface.canvas), ("card", .card)] {
            for state in [CatTintedButtonState.normal, .pressed, .voiceHold] {
                pairs.append(("ink/button-\(state)-on-\(surfaceName)", s.ink, s.tintedButtonFill(state, on: surface).hex))
            }
        }
        for (bgName, bg) in [("canvas", s.canvas), ("card", s.card)] {
            pairs.append(("ink/selected-tab-on-\(bgName)", s.ink, s.tabSelectedFill.composited(over: CatRGBA(bg)).hex))
        }
        return pairs
    }

    func testEveryTextPairIsAtLeast4_5() {
        for combo in Self.combos {
            for (name, fg, bg) in textPairs(set(combo.palette, combo.mode)) {
                let ratio = contrast(fg, bg)
                XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(combo.name) \(name) \(hexString(fg)) on \(hexString(bg)) = \(String(format: "%.2f", ratio))")
            }
        }
    }

    // MARK: contrast — graphics (button/tab icons) ≥ 3:1
    func testAccentIconsAreAtLeast3OnTheTintedButton() {
        for combo in Self.combos {
            let s = set(combo.palette, combo.mode)
            for surface in [CatButtonSurface.canvas, .card] {
                let normal = contrast(s.accentOn, s.tintedButtonFill(.normal, on: surface).hex)
                let pressed = contrast(s.accentOnPressed, s.tintedButtonFill(.pressed, on: surface).hex)
                XCTAssertGreaterThanOrEqual(normal, 3.0, "\(combo.name) accentOn on default fill = \(normal)")
                XCTAssertGreaterThanOrEqual(pressed, 3.0, "\(combo.name) accentOnPressed on pressed fill = \(pressed)")
            }
            for bg in [s.canvas, s.card] {
                let tab = contrast(s.accentOn, s.tabSelectedFill.composited(over: CatRGBA(bg)).hex)
                XCTAssertGreaterThanOrEqual(tab, 3.0, "\(combo.name) tab icon = \(tab)")
            }
        }
    }

    // MARK: ink3 — shipped value passes AA; the proposal value did not (why it was overridden)
    func testInk3IsAtLeast4_5OnEverySurfaceAndTheProposalValueWasNot() {
        for combo in Self.combos {
            let s = set(combo.palette, combo.mode)
            for (surface, bg) in [("canvas", s.canvas), ("card", s.card), ("surface", s.surface)] {
                let ratio = contrast(s.ink3, bg)
                XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(combo.name) ink3 \(hexString(s.ink3)) on \(surface) = \(String(format: "%.3f", ratio))")
            }
            let proposal = Self.ink3ProposalToShipped[combo.name]!.proposal
            let worst = [s.canvas, s.card, s.surface].map { contrast(proposal, $0) }.min()!
            XCTAssertLessThan(worst, 4.5, "\(combo.name): the proposal ink3 \(hexString(proposal)) fails 4.5 (worst \(worst)); that is the reason for the override")
        }
    }

    // MARK: tinted button — alphas and composited colours
    func testButtonAlphasPerState() {
        let cDay = set(.inkWhite, .light).button
        XCTAssertEqual(cDay.fillAlpha(.normal), 0.20, "C day default tint is 20 %")
        XCTAssertEqual(cDay.fillAlpha(.pressed), 0.30, "pressed tint is 30 % in every combination (JSON day: 26 %)")
        XCTAssertEqual(set(.softBlush, .light).button.fillAlpha(.normal), 0.16)
        XCTAssertEqual(set(.softBlush, .light).button.fillAlpha(.pressed), 0.30)
        for palette in CatPalette.allCases {
            let night = set(palette, .dark).button
            XCTAssertEqual(night.fillAlpha(.normal), 0.20); XCTAssertEqual(night.fillAlpha(.pressed), 0.30)
            XCTAssertEqual(night.borderAlpha(.normal), 0.40); XCTAssertEqual(night.borderAlpha(.pressed), 0.60)
        }
        for combo in Self.combos {
            let b = set(combo.palette, combo.mode).button
            XCTAssertEqual(b.baseAlpha, 0.85)
            XCTAssertEqual(b.fillAlpha(.disabled), 0, "disabled has no tint")
            XCTAssertEqual(b.borderAlpha(.disabled), 0, "disabled has no orange stroke (hairline instead)")
            XCTAssertEqual(b.haloAlpha(.disabled), 0); XCTAssertEqual(b.haloAlpha(.normal), 0); XCTAssertEqual(b.haloAlpha(.pressed), 0)
            XCTAssertEqual(b.fillAlpha(.voiceHold), 0.28, "voice-hold stays at the proposal 28 % (now slightly below the 30 % pressed tint — reported, not changed)"); XCTAssertEqual(b.borderAlpha(.voiceHold), 0.65); XCTAssertEqual(b.haloAlpha(.voiceHold), 0.14)
            XCTAssertGreaterThanOrEqual(b.fillAlpha(.normal), 0.16); XCTAssertLessThanOrEqual(b.fillAlpha(.normal), 0.20)
            XCTAssertLessThan(b.fillAlpha(.normal), b.fillAlpha(.pressed))
        }
    }

    func testCompositedButtonColoursMatchTheProposalTable() {
        // From compositedButtonFill in the proposal JSON, except: C day default recomputed at 20 % (JSON #FEE7DC / #FFE8DD) and the
        // day pressed fills recomputed at 30 % (JSON 26 %: B #FFD7C2/#FFD8C4, C #FED9C7/#FFDAC8). Night values are the JSON's.
        let expected: [String: (canvas: [UInt32], card: [UInt32])] = [   // [default, pressed, border]
            "B-day": ([0xFFE5D7, 0xFFD1BA, 0xFFC6AA], [0xFFE6D9, 0xFFD2BC, 0xFFC7AB]),
            "B-night": ([0x563421, 0x6B3C22, 0x804323], [0x593623, 0x6D3D24, 0x824425]),
            "C-day": ([0xFEE2D4, 0xFED3BE, 0xFEC8AD], [0xFFE2D4, 0xFFD4BF, 0xFFC9AE]),
            "C-night": ([0x462A1D, 0x5D321F, 0x743B20], [0x482B1F, 0x5F3420, 0x763C22])]
        for combo in Self.combos {
            let s = set(combo.palette, combo.mode)
            let e = expected[combo.name]!
            XCTAssertEqual(s.tintedButtonFill(.normal, on: .canvas).hex, e.canvas[0], "\(combo.name) default/canvas")
            XCTAssertEqual(s.tintedButtonFill(.pressed, on: .canvas).hex, e.canvas[1], "\(combo.name) pressed/canvas")
            XCTAssertEqual(s.tintedButtonBorder(.normal, on: .canvas).hex, e.canvas[2], "\(combo.name) border/canvas")
            XCTAssertEqual(s.tintedButtonFill(.normal, on: .card).hex, e.card[0], "\(combo.name) default/card")
            XCTAssertEqual(s.tintedButtonFill(.pressed, on: .card).hex, e.card[1], "\(combo.name) pressed/card")
            XCTAssertEqual(s.tintedButtonBorder(.normal, on: .card).hex, e.card[2], "\(combo.name) border/card")
            XCTAssertEqual(s.tintedButtonFill(.normal, on: .card).alpha, 1, "the Reduce-Transparency fill is opaque")
        }
    }

    func testRGBAOverRoundsToEightBitsLikeThePublishedValues() {
        XCTAssertEqual(CatRGBA(0xFF702A, alpha: 0).composited(over: CatRGBA(0x123456)).hex, 0x123456)
        XCTAssertEqual(CatRGBA(0xFF702A, alpha: 1).composited(over: CatRGBA(0x123456)).hex, 0xFF702A)
        XCTAssertEqual(CatRGBA(0xFFFFFF, alpha: 0.5).composited(over: CatRGBA(0x000000)).hex, 0x808080, "127.5 rounds to even (128)")
    }

    // MARK: structure
    func testTabSelectionStyleAndWash() {
        XCTAssertEqual(CatThemeTokens.inkWhite.tabSelectedStyle, .neutral)
        XCTAssertEqual(CatThemeTokens.softBlush.tabSelectedStyle, .orangeTint)
        XCTAssertEqual(CatThemeTokens.inkWhite.tabSelectedStyle == .neutral ? set(.inkWhite, .light).tabSelectedFill : CatRGBA(0), CatRGBA(0x111111, alpha: 0.07))
        XCTAssertEqual(set(.softBlush, .light).tabSelectedFill, CatRGBA(0xFF702A, alpha: 0.12))
        XCTAssertEqual(set(.softBlush, .dark).tabSelectedFill, CatRGBA(0xFF702A, alpha: 0.16))
        XCTAssertNil(set(.inkWhite, .light).wash); XCTAssertNil(set(.inkWhite, .dark).wash)
        // B night glow is misty pink (R > G,B), not orange; faint (≤ 0.16).
        let glow = set(.softBlush, .dark).wash!.spots[0]
        XCTAssertEqual(glow.hex, 0xE6968C); XCTAssertLessThanOrEqual(glow.alpha, 0.16)
    }

    #if canImport(UIKit)
    /// The legacy `catColors` names resolve to the current palette's tokens.
    func testLegacyCatColorsReadTheCurrentPalette() {
        let original = CatTheme.palette
        defer { CatTheme.palette = original }
        func resolved(_ color: Color, _ style: UIUserInterfaceStyle) -> UInt32 {
            let ui = UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            ui.getRed(&r, green: &g, blue: &b, alpha: &a)
            return UInt32((r * 255).rounded()) << 16 | UInt32((g * 255).rounded()) << 8 | UInt32((b * 255).rounded())
        }
        CatTheme.palette = .inkWhite
        XCTAssertEqual(resolved(catColors.background, .light), 0xFAF9F7)
        XCTAssertEqual(resolved(catColors.ink, .dark), 0xF5F4F2)
        XCTAssertEqual(resolved(catColors.card, .light), 0xFFFFFF)
        CatTheme.palette = .softBlush
        XCTAssertEqual(resolved(catColors.background, .light), 0xFCF3EF)
        XCTAssertEqual(resolved(catColors.ink, .dark), 0xF7EEE5)
        XCTAssertEqual(resolved(catColors.secondary, .light), 0x6E5C4F)
        XCTAssertEqual(resolved(catColors.failure, .dark), 0xFF9C93)
    }
    #endif
}
