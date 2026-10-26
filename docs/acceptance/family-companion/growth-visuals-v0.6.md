# 成长 tab visuals – v0.6 slice S4

Branch `feat/growth-visuals-v06`, draft PR based on `main`; **it builds on #131 (native Liquid Glass, S1) and must be merged after it.**
Deployment target stays iOS 17; everything iOS 26 sits behind `#available` inside the shared glass helpers.

**Evidence level: parsed only.** The build Mac was offline while this was written. The Swift files pass `swiftc -parse`; the pure logic was compiled
and its 21 unit tests were run on Linux (details below). Nothing here was compiled against SwiftUI/iOS, run in a simulator, or seen on a device.

## Information hierarchy

```
成长 (home)                              ← one recommended action, one picture, two doors
├─ [child chips]  only with 2+ children   (avatar ring in the child's accent)
├─ header         avatar ring · nickname · 开始记录于 · ⋯ (rename / delete, as before)
├─ START CARD     "先从这里开始" · title · one-line reason · ONE tinted-glass button
├─ 最近 7 天      seven bars, today in the single orange accent
└─ doors          成长目标  /  成长时间线    (pushed sub pages)

成长目标 (pushed)                         成长时间线 (pushed)
├─ goal cards: progress ring + title       ├─ child filter · 记录类型 segmented
│  + record-count marks (1·5·10·20)        ├─ day groups newest first, items on a rail
│  + next planned arrangement              │  dot = child accent · milestone = ringed dot + 「第 10 条记录」
├─ 添加目标 (secondary glass)              ├─ "开始了目标「…」" nodes (unfiltered view only)
└─ 成长回顾 for the selected goal          └─ 手动记下观察 (last, secondary glass)
   (the existing review, unchanged)
```

Secondary features (add goal, manual observation, rename/delete child, per-record edit/delete/provenance) are all one level down or in the
⋯ menu. The home has exactly one button-styled primary action.

## The one start action (`ParentingGrowthStart.choose`)

Pure and deterministic: ledger + clock + calendar in, one action out. First match wins, for the active child (the picked one, else the earliest-created):

| # | Condition | Action | Button |
|---|---|---|---|
| 1 | no child | `addChild` | 添加孩子 |
| 2 | no records at all | a goal exists → `recordForGoal(oldest goal)`; else `firstObservation` | 记下观察 |
| 3 | ≥ 5 observations and no goal | `makeGoal` | 添加目标 |
| 4 | a record was written today | `reviewWeek` (no nagging right after a record) | 看时间线 |
| 5 | a goal with no record, else the goal quiet the longest (> 7 days) | `recordForGoal` | 记下观察 |
| 6 | last record ≥ 2 calendar days ago | `recordObservation` | 记下观察 |
| 7 | otherwise | `reviewWeek` | 看时间线 |

The 5-observation threshold is the same one `ParentingStartAction.resolve(.growth, …)` uses in #132. Where the two disagree, #132 is the product
rule: it adds a Sunday-evening / 7-day weekly review that needs a persisted `lastWeeklyReviewAt`, which this branch does not have. After #132 lands,
`ParentingGrowthStart.choose` should delegate to it and keep only rule 5 (per-goal). It only recommends: the button opens the usual editor sheet or the timeline;
nothing is written without the parent's confirmation. Voice/typing stays in the composer below (not changed here).

## Goal and timeline visuals

- **Progress ring** (goal card): `finished arrangements / (finished + still open)` for arrangements linked to the goal; centre shows `done/total`.
  With no linked arrangement the ring is a dashed empty track with a leaf (nothing to measure), never an invented percentage.
- **Marks track**: four nodes at 1 / 5 / 10 / 20 records linked to the goal. They count entries the parent wrote; they are not a developmental scale and never score the child.
- **Week bars**: observations + completion records per calendar day for the last seven days, height relative to the busiest day with a visible floor for a single record.
  Graphite bars; today is the only orange. Child colours are not used on bars.
- **Timeline rail**: records grouped by calendar day (今天 / 昨天 / date), newest first; one dot per item in the child's accent. Milestones: the child's first record and
  every 10th/25th/50th/100th record, computed per child over all of that child's records (so a filter does not move a milestone).
- **Child accent**: five slots by creation order (wrapping). Used only for avatar rings, small dots and progress rings, per the design rule.

## Glass and colour

- Primary button on the growth screens: `ParentingGrowthPrimaryButtonStyle` = translucent glass tinted with `catColors.action` at 20 % and an ink label
  (iOS 26: `glassEffect(.regular.tint(…).interactive())` through `catGlass`; before: material or opaque card with Reduce Transparency, faint orange wash, thin orange edge). No solid orange.
  Secondary actions keep `.parentingSecondaryButton()` (`.glass` on iOS 26).
- Cards stay flat (`parentingCard`): glass is for floating controls only and is never stacked on glass. The tab bar and composer are #131's.
- No hex values in the new views. The child accent maps slots to existing `catColors` tokens (`secondary`, `send`, `success`, `calmLavender`, `calmPeach`) in one function,
  `ParentingChildAccent.color(slot:)`; the B/C theme PR replaces that function body with its five child tokens, and `ParentingGrowthStyle.primaryTintOpacity` with the theme's value.
- Reduce Transparency: system handles it on iOS 26; the fallback path uses an opaque card. Reduce Motion: the ring animation is off.
- Accessibility text sizes: goal card stacks vertically, the logo mark on the start card is hidden; week bars and the marks track are single labelled elements.

## Data sources

Only the local ledger (`ParentingWorkspace`: children, goals, arrangements, records). No new storage, no network, no schema change.
Projections live in `Sources/Domain/ParentingGrowthInsights.swift` (Foundation only). Strings are in `Resources/UIStrings.json` (en + zh-Hans).

## Not done / out of scope

- Mascot expressions, plush art and the B/C theme tokens (separate PRs). The start card uses the existing `BrandMark`.
- Starting the action from the composer (voice/typing): the button opens the existing manual sheets; the composer is unchanged.
- Today and 我们家 are untouched. Weekly-review persistence (#132) is not duplicated here.
- Pre-iOS 26 custom floating tab bar hides while a growth sub page is pushed (it is an overlay of the stack root); the back button returns. iOS 26 keeps the system tab bar.
- Photos/attachments on records are not shown on the timeline (the ledger has none).

## Verification

Run on the Linux box (Swift 6.0.3, x86_64) with a throwaway SwiftPM package that symlinks `ParentingWorkspace.swift`, `ParentingArrangementEdit.swift`,
`ParentingTimelines.swift`, `ParentingGrowthInsights.swift` and `Tests/ParentingGrowthInsightsTests.swift` plus a 4-line `catError` shim (the real one needs CryptoKit):
`swift test` → **21 tests, 0 failures** (week density incl. midnight boundary and level floor, journey grouping/filters/milestones per child, goal progress incl. quiet boundary
at 7/8 days, every start-action rule and its ordering, child scoping/fallback, accent wrap). The harness is not in the repo.

- `swiftc -parse` on every new/changed Swift file: OK.
- `project.pbxproj` consistency (no plutil on Linux): app target membership equals `Sources/**/*.swift`, no duplicates, test file in the test target (checked with a script).
- **Not run:** Xcode build, SwiftUI type-check, unit tests in the real target, UI tests, simulator (iOS 26 or 27), iPhone, VoiceOver, Dynamic Type, dark mode screenshots.

## Expected conflicts

See the PR description (`ParentingHomeView.swift` growth section vs #133; `UIStrings.json` and `project.pbxproj` are append-only conflicts; UI tests vs #134).
