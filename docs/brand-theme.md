# mimi brand & theme

Single source of truth for palette and logo. Brand sheet refs live in `docs/refs/brand/`.

## Architecture

| Layer | Where | Role |
| --- | --- | --- |
| Hex tokens | `Sources/DesignSystem/catBrandTokens.swift` | Raw brand sheet values (`primary`, `deep`, `background`, `ink`, `grey`, derived) |
| Semantic colors | `catColors` in `Components.swift` | `ink`, `secondary`, `fill`/`action`/`primary`, `attention`, calm/orb roles — **use these at call sites** |
| Identity alias | `catBrand` in `catMotion.swift` | `paw` → `catColors.fill`; clay roles for presence animation |
| Mark view | `BrandMark` | Cat-head asset wrapper (template / fill / onOrange) |
| Assets | `Resources/Assets.xcassets/catBrand/` | `BrandMark*`, `LaunchFirstFrame` (orange orb + white mark) |
| App icon | `Assets.xcassets/AppIcon.appiconset` | Soft 3D white-on-orange hero (stays at catalog root) |
| Launch canvas | `LaunchCanvas.colorset` + storyboard | Warm background `#FFFCF8` |

Composer send/stop morph and voice-capsule chrome are owned by the Mac worker — do not reshape them here. They may keep reading `catBrand.paw` (now an alias of `fill`).

## Brand sheet (2026-10)

- Primary `#FF702A` → fills / icons only (never body text)
- Deep `#C64A0B` → `actionText` when orange text is unavoidable
- Background `#FFFCF8`
- Ink `#292622`
- Grey `#929298`
- Mark: soft 3D cat-head for App Icon / splash; flat fill / reverse for in-app chrome
- Composer placeholder:「有什么我可以帮你？」
- Subtitle:「你的个人 AI 助手」
- Slogan (string ready, not a new screen):「把交代的事，变成可用成果。」

## Accessibility mapping

| Sheet swatch | Semantic role (light) | Why |
| --- | --- | --- |
| Primary `#FF702A` | App Icon, `BrandMarkFill`, `orbDeep` / glow | Exact brand mark |
| Deep `#C64A0B` | `catColors.fill` / `action` (`fillLight`) | ≥3:1 (non-text) on Background |
| Grey `#929298` | `catColors.tertiary` (muted) | Placeholders / chrome |
| Grey → `#6E6A66` | `catColors.secondary` (`secondaryLight`) | ≥4.5:1 secondary text on Background/panel |
| Ink `#292622` | `catColors.ink` | Primary text |

## Header cat

- Soft-3D clay head beside the chat title: `catPresence` (`docs/refs/header-cat/six-states.png`).
- Do **not** use flat `BrandMark` in the header capsule.
- Connection: offline → ears-down; error → wobble; thinking/working → orbit-load; streaming → spin; done → happy; else idle.

## Launch splash

- Static `LaunchScreen.storyboard` shows `LaunchFirstFrame`: cream `LaunchCanvas` + orange orb with white cat-head (`BrandMarkOnOrange`).
- Animated `LaunchMarkView` uses `BrandMark` only (`.fill` → merge `.onOrange`). No jelly / 4-pad paw on splash.
- Obsolete imagesets removed: `LaunchPaw`, `LaunchPawMark`, `LaunchGlow`. Do not regenerate them.
- Regenerate the static frame: `python3 apps/ios-conversation/design/brand/render_brand.py apps/ios-conversation/Resources/Assets.xcassets`.

## Selectable palettes (C 素白墨黑 default / B 雾粉+燕麦)

Proposal: `mimi-brand-themes-proposal.md` + `mimi-brand-themes-tokens.json` (4 combinations: palette × light/dark).

| Layer | Where | Role |
| --- | --- | --- |
| Palette + appearance axes | `CatPalette` (`inkWhite` = C default, `softBlush` = B), `CatAppearance` (`system` default / `light` / `dark`) in `Sources/DesignSystem/CatTheme.swift` | Two independent preferences: key `palette` (new) and the existing key `theme` (appearance). Appearance default is now **system** (was dark). |
| Tokens | `CatThemeTokens` → `CatColorSet` (light/dark) | canvas, card, surface, hairline, track, ink/ink2/ink3, accent, accentOn(+Pressed), orangeText, success/attention/danger (+fills), kid colours, tinted-button alphas, tab fill, B wash |
| Current theme | `CatTheme.palette` / `CatTheme.colors` / `CatTheme.color(_:)` | Dynamic colours re-read the palette each time they resolve; rebuild the view tree on `CatTheme.paletteDidChange` |
| Legacy names | `catColors.background/panel/input/drawer/ink/secondary/tertiary/card/hairline/separator/fillSelected/success/attention/failure/destructive/userMessage*/calm{Page,Secondary,Surface}` | Same names, now theme-driven. Orange action roles (`action`, `actionText`, `onInk`, `send*`, `voice*`, calm accents) are unchanged until the call-site PR. |
| Button | `CatTintedGlassButtonStyle` / `.catTintedGlass(in:state:)` in `CatButtonStyles.swift` | Orange 16–20 % tint (30 % pressed) over glass + 1 pt orange stroke, ink text; pressed / disabled / voice-hold; iOS 26 `glassEffect`, material fallback, opaque under Reduce Transparency |

Contrast: `Tests/CatThemeTokenTests.swift` asserts every text pair (including `ink3`) ≥ 4.5:1 and icon pairs ≥ 3:1 for all four combinations (the WCAG formula is implemented in the test).

### Deliberate deviations from the proposal JSON

| What | Proposal | Shipped | Why |
| --- | --- | --- | --- |
| `ink3` C-day | `#8A8782` | `#6F6D69` | accessibility: the proposal grey misses 4.5:1 on at least one surface in every combination (min 2.91:1), so tertiary text / placeholders would fail AA |
| `ink3` C-night | `#76746F` | `#8F8E89` | same |
| `ink3` B-day | `#94857A` | `#73655A` | same |
| `ink3` B-night | `#9A8C80` | `#9C8E82` | same (proposal 4.49 on card) |
| C-day default tint | 16 % | 20 % | user decision (proposal §7 risk 3) |
| Pressed tint, all four combinations | day 26 % / night 30 % | **30 %** | deeper press feedback; night was already 30 % |

Voice-hold stays at the proposal's 28 % tint, which is now slightly **below** the 30 % pressed tint (unchanged on purpose; revisit if the held state should read stronger than a tap).

Pressed button colours (30 % orange over the 85 % card base): C-day `#FED3BE` canvas / `#FFD4BF` card · C-night `#5D321F` / `#5F3420` · B-day `#FFD1BA` / `#FFD2BC` · B-night `#6B3C22` / `#6D3D24`.

### Contrast table (WCAG 2.x ratios; text ≥ 4.5, icons ≥ 3)

| Pair | C-day | C-night | B-day | B-night |
|---|---|---|---|---|
| ink/canvas | 17.95 | 17.90 | 13.38 | 15.50 |
| ink2/canvas | 6.75 | 8.18 | 5.81 | 9.89 |
| ink3/canvas | 4.91 | 5.99 | 5.14 | 5.59 |
| orangeText/canvas | 17.95 | 17.90 | 5.06 | 10.28 |
| success/canvas | 5.02 | 10.35 | 5.69 | 10.64 |
| attention/canvas | 5.63 | 11.72 | 5.69 | 10.84 |
| danger/canvas | 5.27 | 8.62 | 5.38 | 8.82 |
| ink/card | 18.88 | 15.81 | 14.32 | 12.78 |
| ink2/card | 7.11 | 7.23 | 6.22 | 8.16 |
| ink3/card | 5.16 | 5.29 | 5.50 | 4.61 |
| orangeText/card | 18.88 | 15.81 | 5.41 | 8.48 |
| success/card | 5.28 | 9.14 | 6.09 | 8.77 |
| attention/card | 5.93 | 10.35 | 6.09 | 8.94 |
| danger/card | 5.55 | 7.61 | 5.76 | 7.28 |
| ink/surface | 16.59 | 13.74 | 11.93 | 14.16 |
| ink2/surface | 6.24 | 6.28 | 5.18 | 9.04 |
| ink3/surface | 4.54 | 4.60 | 4.58 | 5.11 |
| orangeText/surface | 16.59 | 13.74 | 4.51 | 9.39 |
| success/surface | 4.64 | 7.95 | 5.07 | 9.72 |
| attention/surface | 5.21 | 9.00 | 5.08 | 9.90 |
| danger/surface | 4.87 | 6.62 | 4.80 | 8.06 |
| success/successFill | 4.66 | 8.15 | 5.25 | 7.20 |
| attention/attentionFill | 5.37 | 9.18 | 5.47 | 7.87 |
| danger/dangerFill | 4.85 | 7.07 | 4.71 | 6.83 |
| ink/button-normal-on-canvas | 15.33 | 11.87 | 12.15 | 9.60 |
| icon accentOn/button-normal-on-canvas (>=3) | 3.24 | 4.72 | 3.56 | 4.72 |
| ink/button-pressed-on-canvas | 13.74 | 9.84 | 10.50 | 7.98 |
| icon accentOnPressed/button-pressed-on-canvas (>=3) | 3.88 | 4.97 | 3.83 | 4.83 |
| ink/button-voiceHold-on-canvas | 14.05 | 10.15 | 10.74 | 8.31 |
| ink/button-normal-on-card | 15.36 | 11.64 | 12.24 | 9.27 |
| icon accentOn/button-normal-on-card (>=3) | 3.24 | 4.63 | 3.59 | 4.56 |
| ink/button-pressed-on-card | 13.87 | 9.56 | 10.58 | 7.82 |
| icon accentOnPressed/button-pressed-on-card (>=3) | 3.92 | 4.83 | 3.86 | 4.73 |
| ink/button-voiceHold-on-card | 14.17 | 9.99 | 10.82 | 8.13 |
| ink/selected-tab-on-canvas | 15.56 | 13.40 | 11.92 | 12.24 |
| icon accentOn/selected-tab-on-canvas (>=3) | 3.29 | 5.33 | 3.49 | 6.02 |
| ink/selected-tab-on-card | 16.28 | 11.13 | 12.69 | 9.97 |
| icon accentOn/selected-tab-on-card (>=3) | 3.44 | 4.43 | 3.72 | 4.90 |

Lowest text ratio 4.51 (B-day orangeText on surface); lowest icon ratio 3.24 (C-day accentOn on the default tinted button). Raw numbers: `contrast-table.txt` next to the patches.

## How to swap the palette later

1. Edit hex constants in `catBrandTokens` only.
2. Rebuild. `catColors` / `catBrand` pick up the new values automatically.
3. If contrast fails WCAG for `actionText`, adjust `deep` / `deepDark` — do not recolor body copy to orange.
4. Run `Scripts/check-native-design.py` (and typography / localization / repository checks).

## How to swap the logo later

1. Drop new masters into `docs/refs/brand/` (3D hero, flat-on-black, brand sheet).
2. Regenerate PNGs into `Assets.xcassets/catBrand/` and `AppIcon.appiconset` (see script notes in that folder or re-run the brand asset generator used for this PR).
3. Keep asset **names** (`BrandMark`, `BrandMarkFill`, `BrandMarkOnOrange`) so `BrandMark` view stays unchanged.
4. Prefer `BrandMark` over `PawMark` for identity surfaces. `PawMark` remains for pastel category markers only.

## Do / don’t

- Do: `catColors.ink`, `.secondary`, `.fill`, `.attention`, `BrandMark(...)`.
- Don’t: raw `Color(UIColor(rgb: 0x…))` / magic oranges at feature call sites.
- Don’t: orange body text; orange is fill/icon only.

## App Icon regeneration

Master: `docs/refs/brand/01-icon-hero-3d.png` (soft 3D white cat-head on `#FF702A`).

```bash
# light + dark (1024×1024)
sips -s format png -z 1024 1024 docs/refs/brand/01-icon-hero-3d.png \
  --out apps/ios-conversation/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-light.png
cp apps/ios-conversation/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-light.png \
   apps/ios-conversation/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-dark.png
# tinted = white BrandMarkOnOrange on black (see Scripts/regenerate-appicon.py)
python3 apps/ios-conversation/design/brand/regenerate_appicon.py
```

After installing a build that changes `AppIcon.appiconset`, **delete the app from the simulator/device before reinstalling**. SpringBoard caches the home-screen / app-switcher icon; a plain reinstall often keeps the old jelly paw.

## Obsolete jelly 4-toe assets (removed)

Do **not** restore these imagesets or design SVGs:

- `Assets.xcassets/catpaw.paw*.imageset` (app + Widgets)
- `design/symbols/catpaw.paw*.svg`
- `LaunchPaw` / `LaunchPawMark` / `LaunchGlow`

In-app identity: `BrandMark` / `LaunchFirstFrame` / soft-3D `catPresence` only. `PawMark` / `CategoryPaw` remain as **pastel category markers** (not the App Icon).

