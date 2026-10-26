import SwiftUI

// Translucent orange "tinted glass" button (proposal mimi-brand-themes §2). Replaces the solid orange fill with:
//   system glass / material  →  85 % card-coloured base  →  logo-orange tint (16–20 %)  →  1 pt orange stroke,
// text in the theme's ink colour, icon in `accentOn` (via `.tint`). No orange shadow, no orange text, no white-on-orange.
//
// States: normal · pressed (30 % tint, 55–60 % stroke) · disabled (no tint, hairline stroke, ink3) · voice-hold
// (press-and-hold dictation: 28 % tint, 65 % stroke, 14 % halo ring that pulses unless Reduce Motion is on).
// iOS 26+: `glassEffect` tinted with the orange. Earlier: `.regularMaterial` + the same layers.
// Reduce Transparency: the opaque colour the layers composite to (`CatColorSet.tintedButtonFill`) — no material, no glass.
// The numbers live in `CatTheme.swift` (`CatTintedButtonSpec`) and are unit-tested; this file is only the rendering.

/// Tinted-glass background for any control — used by `CatTintedGlassButtonStyle` and, directly, by gesture-driven
/// controls that are not a `Button` (the press-and-hold dictation control passes `.voiceHold` while the finger is down).
struct CatTintedGlassSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    let state: CatTintedButtonState
    /// Page colour the button sits on: decides the Reduce-Transparency fill (`.card` for buttons inside cards/bars).
    var surface: CatButtonSurface = .card
    /// iOS 26 only: the system's own press shimmer. Off by default because `catPressEffect` already answers presses.
    var interactive = false

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var set: CatColorSet { CatTheme.colors.set(for: scheme == .dark ? .dark : .light) }
    private var orange: Color { CatTheme.color(CatRGBA(CatTheme.logoOrange)) }
    private var tint: Color { orange.opacity(set.button.fillAlpha(state)) }
    private var hairline: Color { CatTheme.color(CatRGBA(set.hairline)) }
    private var strokeColor: Color { state == .disabled ? hairline : orange.opacity(set.button.borderAlpha(state)) }

    func body(content: Content) -> some View {
        let labelled = content
            .foregroundStyle(CatTheme.color(state == .disabled ? .ink3 : .ink))
            .tint(CatTheme.color(state == .pressed ? .accentOnPressed : .accentOn))
        if reduceTransparency {
            // Opaque: exactly the composited colours from the proposal table.
            labelled
                .background(CatTheme.color(set.tintedButtonFill(state, on: surface)), in: shape)
                .overlay(shape.strokeBorder(state == .disabled ? hairline : CatTheme.color(set.tintedButtonBorder(state, on: surface)), lineWidth: 1))
                .overlay { halo }
        } else if #available(iOS 26.0, *) {
            labelled
                .glassEffect(glass, in: shape)
                .overlay(shape.strokeBorder(strokeColor, lineWidth: 1))
                .overlay { halo }
        } else {
            // `.background` stacks behind earlier ones: tint (nearest), then the 85 % card base, then the material.
            labelled
                .background(tint, in: shape)
                .background(CatTheme.color(CatRGBA(set.card, alpha: set.button.baseAlpha)), in: shape)
                .background(.regularMaterial, in: shape)
                .overlay(shape.strokeBorder(strokeColor, lineWidth: 1))
                .overlay { halo }
        }
    }

    @available(iOS 26.0, *)
    private var glass: Glass {
        let tinted = Glass.regular.tint(tint)
        return interactive && state != .disabled ? tinted.interactive() : tinted
    }

    @ViewBuilder private var halo: some View {
        if state == .voiceHold {
            CatVoiceHoldHalo(shape: shape, color: orange.opacity(set.button.haloAlpha(state)))
        }
    }
}

/// 14 % orange ring just outside the control while dictation is held; pulses unless Reduce Motion is on.
private struct CatVoiceHoldHalo<S: InsettableShape>: View {
    let shape: S
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false
    private var pulse: Animation? { reduceMotion ? nil : Animation.easeInOut(duration: 0.9).repeatForever(autoreverses: true) }
    var body: some View {
        shape.stroke(color, lineWidth: 6)
            .padding(-3)
            .scaleEffect(expanded && !reduceMotion ? 1.06 : 1)
            .opacity(expanded && !reduceMotion ? 0.55 : 1)
            .animation(pulse, value: expanded)
            .onAppear { expanded = true }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

extension View {
    /// Tinted-glass surface for a control that is not a `Button` (e.g. press-and-hold dictation: pass `.voiceHold` while held).
    func catTintedGlass<S: InsettableShape>(in shape: S, state: CatTintedButtonState = .normal,
                                             surface: CatButtonSurface = .card, interactive: Bool = false) -> some View {
        modifier(CatTintedGlassSurface(shape: shape, state: state, surface: surface, interactive: interactive))
    }
}

/// The primary action button of the themed app. Same metrics as `catPrimaryButtonStyle` (24 pt content, 20/14 padding).
struct CatTintedGlassButtonStyle<S: InsettableShape>: ButtonStyle {
    let shape: S
    var surface: CatButtonSurface = .card
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let state: CatTintedButtonState = !isEnabled ? .disabled : (configuration.isPressed ? .pressed : .normal)
        configuration.label
            .font(catTypography.Role.body.font.weight(.semibold))
            .fixedSize(horizontal: false, vertical: true)
            .frame(minHeight: 24).padding(.horizontal, 20).padding(.vertical, 14)
            .catTintedGlass(in: shape, state: state, surface: surface)
            .contentShape(shape)
            // Press-down answers in the touch frame with scale + soft haptic (no dimming: the tint itself deepens).
            .modifier(catPressEffect(pressed: configuration.isPressed && isEnabled, scale: catInteractionMotion.primaryPressScale, dimmed: 1))
    }
}

extension ButtonStyle where Self == CatTintedGlassButtonStyle<RoundedRectangle> {
    /// Rounded-rectangle tinted-glass button (control radius, like `catPrimaryButtonStyle`).
    static var catTintedGlass: CatTintedGlassButtonStyle<RoundedRectangle> {
        CatTintedGlassButtonStyle(shape: RoundedRectangle(cornerRadius: catLayout.controlRadius, style: .continuous))
    }
}

extension ButtonStyle where Self == CatTintedGlassButtonStyle<Capsule> {
    /// Capsule tinted-glass button (sheet footers, the voice control).
    static var catTintedGlassCapsule: CatTintedGlassButtonStyle<Capsule> { CatTintedGlassButtonStyle(shape: Capsule()) }
}
