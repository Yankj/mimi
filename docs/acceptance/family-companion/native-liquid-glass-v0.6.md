# Native Liquid Glass (iOS 26+) – v0.6 slice S1

Branch `feat/native-liquid-glass` (draft PR #131). Deployment target stays iOS 17; everything iOS 26 is behind `#available`.
**Evidence level: simulator only.** The Xcode on the build Mac (27.1) ships iOS 27.0 / 27.1 simulators, no iOS 26 runtime, so the
screenshots show the iOS 27 glass renderer. Nothing here is real-device acceptance of look, scroll feel or voice.

## Audit (parenting shell and shared chrome)

| File | Component | Before | Now |
|---|---|---|---|
| Parenting/ParentingHomeView | tab bar | custom capsule of buttons (`parentingGlass`) | iOS 26: system `TabView` + `Tab`, `.tabBarMinimizeBehavior(.onScrollDown)`; iOS 17-25: previous custom bar |
| ParentingHomeView | nav chrome | inline title, brand name + gear on every tab | large title, no brand/gear; gear only on 我们家 (temporary) |
| ParentingNaturalInputViews | composer | glass card of chips + field + send | one glass card via `safeAreaBar` (iOS 26) / inset overlay; idle = one line (+ · hold-to-talk · keyboard) |
| ParentingNaturalInputViews | confirm sheet | `.large`, opaque canvas | `.medium/.large`, system glass canvas on iOS 26, confirm actions pinned in a bottom bar, auto-closes 1.6 s after save |
| ParentingHomeView + NaturalInputViews | primary/secondary buttons (~25) | custom gradient / fill styles | iOS 26: `.buttonStyle(.glassProminent)` / `.glass`; before: old styles |
| Components.swift | `catPrimaryButtonStyle`, `catSheetPrimaryButtonStyle` | solid ink | iOS 26: tinted interactive `glassEffect` |
| Composer/ComposerAttachPopoverView, ComposerChrome (jump-to-latest), ComposerDictation, ComposerHoldToTalk, MessageLongPress, ImageViewer, InAppBannerView, ConversationInboxView (progress pill) | floating pills/cards | `.regularMaterial` / `.ultraThinMaterial` + hand-made shadow | `catGlass(in:)` |
| ParentingHomeView | scroll under bars | contentMargins + overlay | `scrollEdgeEffectStyle(.soft)`; bottom bar via `safeAreaBar` |

About 25 components changed (3 shell, 2 sheet/composer, ~25 call sites of button styles, 8 floating material views).

## One glass API

`Components.swift`: `catGlass(in:interactive:tint:fallback:shadowed:)` (`glassEffect` on iOS 26; material + hairline + optional shadow before;
opaque card when Reduce Transparency is on before iOS 26 – the system handles it on 26), `catGlassGroup` (`GlassEffectContainer`),
`catBottomBar` (`safeAreaBar`/`safeAreaInset`), `catSheetCanvas`, `catSoftScrollEdges`; `parentingPrimaryButton()` / `parentingSecondaryButton()`
pick `.glassProminent` / `.glass`. Views never write `#available` for glass themselves (the tab shell is the one place that
switches containers). Colours only come from `catColors` tokens, so a later palette swap is a token change.

## Composer (voice-first layout, voice not wired)

Idle: `+` menu (`parenting.plus.menu`: starters, then `parenting.plus.manualArrangement`, `parenting.plus.manualObservation` last), a
"Hold to talk" capsule (`parenting.composer.mic`) and `parenting.composer.keyboardToggle`. Without a voice accessory the capsule only
opens the keyboard. A voice feature plugs in through `ParentingComposerAccessory`
(`.environment(\.parentingComposerAccessory, impl)`): `micControl(finish:)` replaces the capsule, `voiceState` drives
`parenting.composer.listening` and `parenting.composer.transcript`. At accessibility text sizes the composer is keyboard-only.

## Intentionally custom

- The iOS 17-25 tab bar and the brand canvas of large form sheets (warm cream, not system glass).
- Bottom action areas (`catBottomActionArea`, task plan, inbox return button) still use `.regularMaterial`; follow-up.
- Chat sheet chrome (`ConversationFirstView` headers, drawer) and `catPrivacyShield` blur (a privacy blur, not a control).
- Composer chips/field/send sit inside the one glass card as flat fills: glass is never stacked on glass.

## Tests (iOS 27.0 simulator, iPhone 18 Pro, ad-hoc signed with SimulatorFixtureAcceptance.entitlements)

Run on 2026-10-26 (UTC+8) on a Mac under load average 100+ (other agents building), so long journeys time out for environmental reasons.

- Build for testing (Debug, simulator): succeeded.
- Unit: `ParentingNaturalInputTests` and `ParentingWorkspaceTests` all passed (run before the last UI-only edits; no domain code touched).
- UI passed: `testComposerIsVoiceFirstWithPlusMenuAndAutoDismissingConfirmCard` (new: idle layout, + menu with manual items, auto-dismiss),
  `testInteractionScreenshotsConfirmCardTimelineAndFloatingTabBar`, `testSyntheticDarkLargeTextEmptyAndEditorSmoke`,
  `testInteractionFloatingTabBarAtLargestAccessibilityText` (earlier run, before the composer rewrite).
- UI not verified: `testSyntheticParentingJourneyPersistsAcrossColdRelaunch` timed out (snapshot timeout after 607 s, load 107); the rest of
  `ParentingWorkspaceUITests` was not re-run to completion after the composer rewrite. No baseline of main on the iOS 27 simulator was taken.
- iOS 26 behaviour is unverified: only iOS 27.0 simulator was available. The iOS 17-25 fallback path compiles but was not run.
- Phone: Kunjie的iPhone was `unavailable` to devicectl, so no install was attempted.

## Not done / follow-ups

Multi-image send/receive; voice (S1b); settings/family screens (S3); real iOS 26 device acceptance; `catBottomActionArea` glass.
