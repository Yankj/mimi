import Foundation
#if canImport(SwiftUI)
import SwiftUI
#endif
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// Selectable colour themes (docs/proposals: mimi-brand-themes B 雾粉+燕麦 / C 素白墨黑, each with a light and a dark variant).
//
// Two independent axes, never merged:
//   • palette    (`CatPalette`, preference key "palette")  — which colour language: C 素白墨黑 (default) or B 雾粉+燕麦.
//   • appearance (`CatAppearance`, preference key "theme") — system / light / dark. The key and values are the ones the app
//                already stored; only the default changes from "dark" to "system".
//
// The token model below is deliberately Foundation-only (SwiftUI/UIKit are bridged at the bottom of the file) so the
// token values and their WCAG contrast can be unit-tested without a view hierarchy.
// `catColors.<role>` (Components.swift) keeps its names and reads these tokens through `CatTheme.color(_:)`.

// MARK: - Axes

/// Colour language. Raw values are stable preference strings.
enum CatPalette: String, CaseIterable, Sendable, Codable {
    /// C — 素白墨黑 (default).
    case inkWhite
    /// B — 雾粉 + 燕麦.
    case softBlush

    static let `default`: CatPalette = .inkWhite
    /// Single-letter name used by the proposal.
    var letter: String { self == .inkWhite ? "C" : "B" }
    var displayName: String { self == .inkWhite ? "素白墨黑" : "雾粉燕麦" }
    /// Unknown / missing stored value → the default palette (a bad preference must never leave the app unthemed).
    init(preference raw: String?) { self = raw.flatMap(CatPalette.init(rawValue:)) ?? .default }
}

/// Light / dark / follow-system. Raw values are the strings already stored under the existing "theme" key.
enum CatAppearance: String, CaseIterable, Sendable, Codable {
    case system, light, dark
    /// Was "dark" before the theme work; first-run and unset preferences now follow the system.
    static let `default`: CatAppearance = .system
    init(preference raw: String?) { self = raw.flatMap(CatAppearance.init(rawValue:)) ?? .default }
}

enum CatThemePreference {
    /// New key (separate from the appearance key). NOTE: to be profile-scoped it must also be added to
    /// `ProfilePreferences.keys` (Domain/LocalPolicies.swift) — left to the settings PR, see docs/design notes.
    static let paletteKey = "palette"
    /// Existing key; unchanged.
    static let appearanceKey = "theme"
}

/// Light / dark resolution of a theme.
enum CatMode: Sendable { case light, dark }

// MARK: - Pure colour maths

/// An sRGB colour as 0xRRGGBB plus alpha. Value type, no UI framework.
struct CatRGBA: Equatable, Sendable {
    let hex: UInt32
    let alpha: Double
    init(_ hex: UInt32, alpha: Double = 1) { self.hex = hex; self.alpha = alpha }
    var red: Double { Double((hex >> 16) & 255) / 255 }
    var green: Double { Double((hex >> 8) & 255) / 255 }
    var blue: Double { Double(hex & 255) / 255 }

    /// Source-over onto an **opaque** background. The result is opaque and each channel is rounded to 8 bits
    /// (ties to even) so it equals the hex values published in the proposal.
    func composited(over background: CatRGBA) -> CatRGBA {
        func mix(_ foreground: UInt32, _ back: UInt32) -> UInt32 {
            UInt32((Double(foreground) * alpha + Double(back) * (1 - alpha)).rounded(.toNearestOrEven))
        }
        let r = mix((hex >> 16) & 255, (background.hex >> 16) & 255)
        let g = mix((hex >> 8) & 255, (background.hex >> 8) & 255)
        let b = mix(hex & 255, background.hex & 255)
        return CatRGBA(r << 16 | g << 8 | b)
    }
}

// MARK: - Tokens

/// Every named colour of one palette in one appearance.
enum CatColorRole: String, CaseIterable, Sendable {
    case canvas, card, surface, hairline, track
    case ink, ink2, ink3
    /// Graphic accent (rings, "now" line, dots) — never body text.
    case accent
    /// Icon colour on the tinted button / selected tab (≥3:1, graphic).
    case accentOn, accentOnPressed
    /// Small-area link colour. C uses ink (no orange text).
    case orangeText
    case success, successFill, attention, attentionFill, danger, dangerFill
}

/// Tinted-glass primary button parameters (proposal §2). Alphas are 0…1.
struct CatTintedButtonSpec: Equatable, Sendable {
    var fill: Double
    var fillPressed: Double
    var border: Double
    var borderPressed: Double
    /// Opacity of the card-coloured base under the tint (keeps contrast when glass sits on busy content).
    var baseAlpha: Double
    /// Press-and-hold dictation (voice) — same for every theme: tint 28 %, stroke 65 %, 14 % halo ring.
    var voiceHoldFill: Double = 0.28
    var voiceHoldBorder: Double = 0.65
    var voiceHoldHalo: Double = 0.14

    func fillAlpha(_ state: CatTintedButtonState) -> Double {
        switch state {
        case .normal: return fill
        case .pressed: return fillPressed
        case .disabled: return 0
        case .voiceHold: return voiceHoldFill
        }
    }
    func borderAlpha(_ state: CatTintedButtonState) -> Double {
        switch state {
        case .normal: return border
        case .pressed: return borderPressed
        case .disabled: return 0       // disabled uses the hairline colour instead of an orange stroke
        case .voiceHold: return voiceHoldBorder
        }
    }
    func haloAlpha(_ state: CatTintedButtonState) -> Double { state == .voiceHold ? voiceHoldHalo : 0 }
}

enum CatTintedButtonState: Equatable, Sendable { case normal, pressed, disabled, voiceHold }

/// Which surface the button sits on (decides the colour the opaque fallback is composited onto).
enum CatButtonSurface: Sendable { case canvas, card }

/// A decorative page wash (spots of colour over a vertical gradient). Cosmetic only; no text sits on it
/// without the card/surface colours underneath, so it is not part of the contrast pairs.
struct CatCanvasWash: Equatable, Sendable {
    struct Spot: Equatable, Sendable {
        let hex: UInt32
        let alpha: Double
        /// Centre and radii as fractions of the view's width/height.
        let centerX: Double, centerY: Double, radiusX: Double, radiusY: Double
    }
    /// Colour at the very top; fades to `canvas` by `fadeEnd` (0…1 of the height).
    let topHex: UInt32
    let fadeEnd: Double
    let spots: [Spot]
}

/// One palette in one appearance.
struct CatColorSet: Equatable, Sendable {
    let canvas, card, surface, hairline, track: UInt32
    let ink, ink2, ink3: UInt32
    let accent, accentOn, accentOnPressed, orangeText: UInt32
    let success, successFill, attention, attentionFill, danger, dangerFill: UInt32
    /// Per-child ring/dot colours (first = default for child 1, second for child 2).
    let kids: [UInt32]
    let button: CatTintedButtonSpec
    /// Selected tab capsule fill (alpha included).
    let tabSelectedFill: CatRGBA
    let wash: CatCanvasWash?

    func hex(_ role: CatColorRole) -> UInt32 {
        switch role {
        case .canvas: return canvas
        case .card: return card
        case .surface: return surface
        case .hairline: return hairline
        case .track: return track
        case .ink: return ink
        case .ink2: return ink2
        case .ink3: return ink3
        case .accent: return accent
        case .accentOn: return accentOn
        case .accentOnPressed: return accentOnPressed
        case .orangeText: return orangeText
        case .success: return success
        case .successFill: return successFill
        case .attention: return attention
        case .attentionFill: return attentionFill
        case .danger: return danger
        case .dangerFill: return dangerFill
        }
    }

    /// Page colour behind a button on the given surface.
    func background(of surface: CatButtonSurface) -> UInt32 { surface == .canvas ? canvas : card }

    /// Opaque colour of the tinted button: `brand orange @ alpha` over `card @ baseAlpha` over the surface.
    /// This is the Reduce-Transparency fill and the figure the contrast checks use.
    func tintedButtonFill(_ state: CatTintedButtonState, on surface: CatButtonSurface) -> CatRGBA {
        let base = CatRGBA(card, alpha: button.baseAlpha).composited(over: CatRGBA(background(of: surface)))
        return CatRGBA(CatTheme.logoOrange, alpha: button.fillAlpha(state)).composited(over: base)
    }
    /// Opaque stroke colour of the tinted button (Reduce-Transparency stroke).
    func tintedButtonBorder(_ state: CatTintedButtonState, on surface: CatButtonSurface) -> CatRGBA {
        let base = CatRGBA(card, alpha: button.baseAlpha).composited(over: CatRGBA(background(of: surface)))
        return CatRGBA(CatTheme.logoOrange, alpha: button.borderAlpha(state)).composited(over: base)
    }
}

enum CatTabSelectedStyle: Equatable, Sendable {
    /// C: neutral capsule (ink 7 % / white 12 %) with an accent icon.
    case neutral
    /// B: orange-tinted capsule (12 % / 16 %) with an accent icon.
    case orangeTint
}

/// A palette's light and dark colour sets.
struct CatThemeTokens: Equatable, Sendable {
    let palette: CatPalette
    let light: CatColorSet
    let dark: CatColorSet
    let tabSelectedStyle: CatTabSelectedStyle

    func set(for mode: CatMode) -> CatColorSet { mode == .dark ? dark : light }
    static func tokens(for palette: CatPalette) -> CatThemeTokens { palette == .inkWhite ? .inkWhite : .softBlush }

    // ---- C 素白墨黑 ----------------------------------------------------------------------------------------------
    static let inkWhite = CatThemeTokens(
        palette: .inkWhite,
        light: CatColorSet(
            canvas: 0xFAF9F7, card: 0xFFFFFF, surface: 0xF2F0EC, hairline: 0xE6E3DE, track: 0xE6E3DE,
            ink: 0x111111, ink2: 0x5A5853, ink3: 0x8A8782,
            accent: 0xE85D1A, accentOn: 0xD15C22, accentOnPressed: 0xAE4F1F, orangeText: 0x111111,
            success: 0x1E7B46, successFill: 0xE7F4EC, attention: 0x8A5A00, attentionFill: 0xFFF3D6, danger: 0xC0352B, dangerFill: 0xFDECEA,
            kids: [0x3C3C43, 0x3E7CB1, 0x4A8A5F, 0x7566C2, 0xC2527A],
            // Day tint is 20 % here (proposal §7 risk 3, user decision) — the JSON has 16 %.
            button: CatTintedButtonSpec(fill: 0.20, fillPressed: 0.26, border: 0.38, borderPressed: 0.55, baseAlpha: 0.85),
            tabSelectedFill: CatRGBA(0x111111, alpha: 0.07), wash: nil),
        dark: CatColorSet(
            canvas: 0x0B0B0C, card: 0x1A1A1C, surface: 0x262628, hairline: 0x3A3A3D, track: 0x3A3A3D,
            ink: 0xF5F4F2, ink2: 0xA9A7A2, ink3: 0x76746F,
            accent: 0xFF702A, accentOn: 0xFF702A, accentOnPressed: 0xFF945F, orangeText: 0xF5F4F2,
            success: 0x6FCF97, successFill: 0x16281D, attention: 0xF2C14E, attentionFill: 0x2B2412, danger: 0xFF8A80, dangerFill: 0x331A18,
            kids: [0xD1D1D6, 0x6FA8DC, 0x6BB586, 0xA395E8, 0xE887A9],
            button: CatTintedButtonSpec(fill: 0.20, fillPressed: 0.30, border: 0.40, borderPressed: 0.60, baseAlpha: 0.85),
            tabSelectedFill: CatRGBA(0xFFFFFF, alpha: 0.12), wash: nil),
        tabSelectedStyle: .neutral)

    // ---- B 雾粉 + 燕麦 ------------------------------------------------------------------------------------------------
    static let softBlush = CatThemeTokens(
        palette: .softBlush,
        light: CatColorSet(
            canvas: 0xFCF3EF, card: 0xFFFCFA, surface: 0xF2E6DC, hairline: 0xEADBD0, track: 0xF1E3D3,
            ink: 0x33261D, ink2: 0x6E5C4F, ink3: 0x94857A,
            accent: 0xFF702A, accentOn: 0xC95821, accentOnPressed: 0xAE4F20, orangeText: 0xB8410A,
            success: 0x3F6B3A, successFill: 0xE6EFDA, attention: 0x8F5200, attentionFill: 0xFFEFCB, danger: 0xB23B34, dangerFill: 0xFBE0DC,
            kids: [0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A],
            button: CatTintedButtonSpec(fill: 0.16, fillPressed: 0.26, border: 0.38, borderPressed: 0.55, baseAlpha: 0.85),
            tabSelectedFill: CatRGBA(0xFF702A, alpha: 0.12),
            wash: CatCanvasWash(topHex: 0xFAE6E1, fadeEnd: 0.40, spots: [
                .init(hex: 0xE6AAA2, alpha: 0.55, centerX: 0.90, centerY: 0.00, radiusX: 1.20, radiusY: 0.55),
                .init(hex: 0xEFE2C8, alpha: 0.90, centerX: 0.00, centerY: 0.45, radiusX: 0.90, radiusY: 0.40),
                .init(hex: 0xD5E4C6, alpha: 0.50, centerX: 1.00, centerY: 0.88, radiusX: 0.90, radiusY: 0.40)])),
        dark: CatColorSet(
            canvas: 0x1C1714, card: 0x2F2721, surface: 0x261F1A, hairline: 0x3E342B, track: 0x3E342B,
            ink: 0xF7EEE5, ink2: 0xCDBFB2, ink3: 0x9A8C80,
            accent: 0xFF8A52, accentOn: 0xFF8A52, accentOnPressed: 0xFFA77D, orangeText: 0xFFB48A,
            success: 0xA9D49A, successFill: 0x2C3A2A, attention: 0xF0C46A, attentionFill: 0x3C3018, danger: 0xFF9C93, dangerFill: 0x432522,
            kids: [0xE6AAA2, 0xA9C48E, 0x9CC4D8, 0xBDB1E6, 0xFFB48A],
            button: CatTintedButtonSpec(fill: 0.20, fillPressed: 0.30, border: 0.40, borderPressed: 0.60, baseAlpha: 0.85),
            tabSelectedFill: CatRGBA(0xFF702A, alpha: 0.16),
            // B night: a faint misty-pink glow (no orange halo), per the proposal.
            wash: CatCanvasWash(topHex: 0x2A211C, fadeEnd: 0.45, spots: [
                .init(hex: 0xE6968C, alpha: 0.16, centerX: 0.90, centerY: 0.00, radiusX: 1.20, radiusY: 0.50),
                .init(hex: 0x96785A, alpha: 0.14, centerX: 0.00, centerY: 0.45, radiusX: 0.90, radiusY: 0.40),
                .init(hex: 0x648C5A, alpha: 0.12, centerX: 1.00, centerY: 0.90, radiusX: 0.90, radiusY: 0.40)])),
        tabSelectedStyle: .orangeTint)
}

// MARK: - Current theme

/// Process-wide current palette. Thread-safe because dynamic colour providers resolve on any thread.
enum CatTheme {
    /// Logo orange #FF702A — the tint source of the tinted button in every palette.
    static let logoOrange: UInt32 = 0xFF702A
    static let paletteDidChange = Notification.Name("CatThemePaletteDidChange")

    private static let lock = NSLock()
    private static var storedPalette: CatPalette = .default

    static var palette: CatPalette {
        get { lock.lock(); defer { lock.unlock() }; return storedPalette }
        set {
            lock.lock(); let changed = storedPalette != newValue; storedPalette = newValue; lock.unlock()
            if changed { NotificationCenter.default.post(name: paletteDidChange, object: nil) }
        }
    }
    /// The tokens of the current palette (both appearances).
    static var colors: CatThemeTokens { CatThemeTokens.tokens(for: palette) }
    static func hex(_ role: CatColorRole, mode: CatMode) -> UInt32 { colors.set(for: mode).hex(role) }
}

// MARK: - SwiftUI / UIKit bridge

#if canImport(SwiftUI)
extension CatTheme {
    /// Colour that re-reads the current palette every time it is resolved (light/dark follow the trait collection).
    /// Views must be rebuilt after a palette change (the settings PR re-identifies the root on `paletteDidChange`).
    static func color(_ role: CatColorRole) -> Color {
        #if canImport(UIKit)
        return Color(uiColor(role))
        #else
        return Color(nsColor(role))
        #endif
    }
    #if canImport(UIKit)
    static func uiColor(_ role: CatColorRole) -> UIColor {
        UIColor { traits in rgba(CatRGBA(hex(role, mode: traits.userInterfaceStyle == .dark ? .dark : .light))) }
    }
    static func rgba(_ c: CatRGBA) -> UIColor { UIColor(cgColor: CGColor(srgbRed: c.red, green: c.green, blue: c.blue, alpha: c.alpha)) }
    #else
    static func nsColor(_ role: CatColorRole) -> NSColor {
        NSColor(name: nil) { appearance in
            let c = CatRGBA(hex(role, mode: appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? .dark : .light))
            return NSColor(srgbRed: c.red, green: c.green, blue: c.blue, alpha: 1)
        }
    }
    #endif
    /// A fixed (non-dynamic) SwiftUI colour from a resolved token.
    static func color(_ c: CatRGBA) -> Color { Color(.sRGB, red: c.red, green: c.green, blue: c.blue, opacity: c.alpha) }
}

extension CatAppearance {
    /// `nil` = follow the system.
    var colorScheme: ColorScheme? { switch self { case .system: return nil; case .light: return .light; case .dark: return .dark } }
}

/// Page background: canvas plus the palette's decorative wash (B only; C is flat). Not yet applied at call sites —
/// that is the call-site PR; the root can adopt it with `.background { CatCanvasBackground() }`.
struct CatCanvasBackground: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let set = CatTheme.colors.set(for: scheme == .dark ? .dark : .light)
        ZStack {
            CatTheme.color(CatRGBA(set.canvas))
            if let wash = set.wash {
                LinearGradient(stops: [.init(color: CatTheme.color(CatRGBA(wash.topHex)), location: 0),
                                       .init(color: CatTheme.color(CatRGBA(set.canvas)).opacity(0), location: wash.fadeEnd)],
                               startPoint: .top, endPoint: .bottom)
                // Spots approximate the proposal's CSS radial gradients (circular here); decorative only.
                GeometryReader { proxy in
                    ZStack {
                        ForEach(0..<wash.spots.count, id: \.self) { index in
                            let spot = wash.spots[index]
                            let color = CatTheme.color(CatRGBA(spot.hex, alpha: spot.alpha))
                            Rectangle().fill(RadialGradient(colors: [color, color.opacity(0)],
                                                            center: UnitPoint(x: spot.centerX, y: spot.centerY),
                                                            startRadius: 0, endRadius: proxy.size.width * spot.radiusX * 0.5))
                        }
                    }
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
#endif
