# 今天页 UI v0.6（S2）：一张主卡 + 横向 rail + 3 秒撤销 + 全部安排

分支：`feat/today-ui-v06`，基于 `feat/today-start-action`（PR #132，`068dd511`）。草稿 PR 的 base 也是 `feat/today-start-action`，因为这里直接调用它的逻辑层。**本切片在 Linux 上写成，没有编译过任何 SwiftUI 代码**（Mac 构建机离线）。下面「验证」一节只写实际跑过的东西，「诚实限制」一节列出没验证的。

## 范围
| 需求 | 做法 |
|---|---|
| 今天首页只有一张「最推荐的唯一起手动作」主卡 | `ParentingTodayView` 的 hero，内容完全来自 `ParentingStartAction.resolve(.today, …)`（经 `ParentingTodayDeck.build`），一次只显示一张 |
| 主卡下面横向 rail（接下来 2–3 件） | `ParentingTodayDeck.rail`：当前孩子、`planned`、时间在现在之后、不含主卡自己，今天的在前、以后日子在后，最多 3 条；不足 3 条就有几条显示几条，没有则显示一句「接下来没有别的安排。」 |
| 一键完成后 3 秒内可撤销 | 主卡「完成」调用 `model.completeOneTap`，**账本回执后**才出现撤销条；「撤销」调用 `model.undoCompletion`（#132 里的 `reopenArrangement`，单独保存的第二条命令）。到 `expiresAt` 绝对时间自动消失，切走再回来不会延长 |
| 次要内容进「全部安排」（push） | 主卡下面的「全部安排」按钮，以及「全部确认 / 逐件查看 / 去处理」里的查看入口，都用 `navigationDestination(isPresented:)` push；二级页内容就是原来的今天页（统计卡、待确认、时间轴、待重新安排、以后的安排、已完成/已取消、成长预览、回顾提示、提醒提示、孩子筛选）原样搬入，外加「周复盘」一行 |
| 手填表单只留一个很小的深层入口 | 「手动填写一件事」缩成 footnote 号的无边框小按钮，只在「全部安排」里（`ParentingTodayManualEntry`）。今天首页没有 |
| 原生 Liquid Glass，不自绘不透明块 | 卡片用 `glassEffect(.regular.tint(橙 10%))`；主按钮 `.glassProminent` + `.tint(catColors.action.opacity(0.55))`（半透明橙，不是实心）+ `catColors.ink` 文字；次按钮 `.glass`。颜色只用 `catColors`，没有新色值 |
| 文案进 UIStrings.json（中英） | 新增 41 条（见下），`check-localization.py` 前四段 PASS |
| 上次周复盘时间存 UI 层 | `ParentingWeeklyReviewStore`，UserDefaults，见下 |

## 主卡对每种启航操作怎么显示
| `ParentingStartAction` | 主卡内容 | 主按钮 | 次按钮 |
|---|---|---|---|
| `startWithNickname` | 「从孩子的昵称开始」（沿用旧文案） | 添加孩子 → 原有 `.newChild` 表单 | — |
| `confirmAll(ids)` | 「有 n 件待确认」+ 最多 3 条草稿预览（标题·时间） | 全部确认：逐条 `confirmArrangement`，首个失败即停，重试复用每条的 operationID | 逐件查看 → 全部安排 |
| `handleOverdue(ids)` | 「有 n 件以前的安排还没标记」，说明不会自动算完成 | 去处理 → 全部安排 | — |
| `nextWithComplete` | 标题、孩子（多孩子时）·时间；已过点时提示 | 完成（一键，无备注）→ 撤销条 | 调整安排 → 原有 `.editArrangement` 表单 |
| `recordTodaySmallThing` | 「今天有什么小事想记下？」 | 说一句 → 把「记一下，」放进输入栏草稿，**不提交** | — |
| `examplePrompt(text)` | 「试试这样说」+ 示例句 | 放进输入栏 → 写入输入栏草稿，**不提交** | — |
| 成长 / 我们家的操作 | 今天页不会返回；`default: EmptyView()` | | |

多个孩子时顶部有一个菜单切换孩子（写入原有的 `selectedChildID`）；主卡、rail 都只看这一个孩子（#132 的 `activeChild` 语义：未选时取最早创建的孩子）。「全部安排」里原有的「全部孩子」筛选保留。

## 改动文件
新增：
- `Sources/Domain/ParentingTodayDeck.swift`：纯逻辑。`ParentingTodayDeck`（hero + rail + hiddenOpenCount）、`ParentingCompletionUndo.remainingSeconds/timeUntilExpiry`（扩展）、`ParentingWeeklyReviewStatus`、`ParentingWeeklyReviewStore`。无任何面向用户的文字。
- `Sources/Features/Parenting/ParentingTodayUI.swift`：`ParentingTodayView`、`ParentingWeeklyReviewRow`、`ParentingTodayManualEntry`、玻璃样式扩展（`parentingToday*`，都带 Today 前缀，避免与 #131 的 `parentingPrimaryButton()` 等重名）。
- `Tests/ParentingTodayDeckTests.swift`：15 个纯逻辑用例。
- `docs/acceptance/family-companion/today-ui-v0.6.md`（本文）。

修改：
- `Sources/Features/Parenting/ParentingHomeView.swift`（最小接入，5 处）：`case .today: todayPage`；新增 `todayPage`、`allArrangementsPage` 两个计算属性；原 `todayContent` 去掉顶部的标题块（日期/大标题/小咪图标/「在下面直接说…」提示，今天页自己有日期行），并把「手动填写一件事」按钮换成 `ParentingTodayManualEntry`。其余原有视图函数一行未动，全部复用。
- `Resources/UIStrings.json`：+41 条（含 #132 漏登记的 9 条，见「依赖 #132」）。
- `cat.xcodeproj/project.pbxproj`：3 个新文件（App 2 个、测试 1 个）。

## 测试用 accessibilityIdentifier（全部 `parenting.today.*`）
`parenting.today.page`、`.date`、`.childMenu`、`.hero`、`.hero.title`、`.hero.detail`、`.hero.addChild`、`.hero.confirmAll`、`.hero.reviewEach`、`.hero.handleOverdue`、`.hero.complete`、`.hero.adjust`、`.hero.recordSmallThing`、`.hero.useExample`、`.hero.pendingPreview`、`.rail`、`.rail.empty`、`.rail.<arrangement uuid>`、`.allArrangements`（主卡下的按钮）、`.allPage`（二级页）、`.undo.toast`、`.undo.button`、`.weeklyReview`、`.weeklyReview.status`、`.weeklyReview.due`、`.weeklyReview.mark`。

沿用旧 id（未改名，便于旧 UI 测试多点一次「全部安排」后继续用）：二级页里的 `parenting.addArrangement`（现在是小按钮）、`parenting.childFilter`、`parenting.confirm.<id>`、`parenting.complete.<id>`、`parenting.arrangement.*`、`parenting.today.summary/timeline/nowMarker` 等。

## 「上次周复盘时间」
- 类型：`ParentingWeeklyReviewStore`（`UserDefaults`，不进加密账本，和提醒设置同一惯例）。
- **键名**：`mimi.parenting.weeklyReview.lastAt.v1.<当前登录 subject 的小写 UUID>`；值为 `timeIntervalSince1970`（Double）。每个 subject 一个键，换账号互不可见。
- 展示/使用：「全部安排」顶部「周复盘」一行：状态（还没有记录过 / 今天已复盘 / 上次 n 天前（M月D日））；若 `ParentingStartAction.resolve(.growth, …, context.lastWeeklyReviewAt)` 返回 `weeklyReview`，显示「周日晚上 / 已满 7 天」的提示；按钮「标记本周已复盘」只写时间。
- 没有登录 subject（夹具/测试）时不落盘，只在页面打开期间保存在内存。
- 逻辑测试：存取、按 subject 隔离、erase、按**日历日**算天数（不是 24 小时）、未来日期不出负数、存下的时间能让成长页解析器在 8 天后返回 `weeklyReview(.sevenDaysSinceLastReview)`、昨天复盘则不返回。

## 系统版本与 Liquid Glass
- 项目最低部署目标 **iOS 17.0**（`IPHONEOS_DEPLOYMENT_TARGET = 17.0`）。`.glass` / `.glassProminent` / `glassEffect` 需要 iOS 26 SDK，所以全部放在 `if #available(iOS 26.0, *)` 里（与 `ParentingGlassSurface`、#131 的做法一致）。
- iOS 17–25 回退：卡片 `.ultraThinMaterial` + 发丝描边；主按钮回退到现有 `ParentingPrimaryButtonStyle`（实心渐变，**不是**半透明橙，这是旧系统上唯一和需求不一致的地方）；次按钮回退到 `ParentingSecondaryButtonStyle`。
- 其余 API 都在 iOS 17：`navigationDestination(isPresented:)`、`scrollClipDisabled()`、`onChange(of:) { _, _ in }`、`Task.sleep(for:)`。

## 与 #131（玻璃外壳）、#135（输入栏）的边界
- 新代码全在新文件；`ParentingHomeView.swift` 只动 3 个位置：`case .today` 一行、`todayContent` 开头的标题块 + 手填按钮、新增两个计算属性（插在 `todayContent` 前）。没动 `body`、`tabBar`、`composerBar`、工具栏、状态属性。
- 预期冲突：#131 把 `todayContent` 里手填按钮的 `.buttonStyle(ParentingSecondaryButtonStyle())` 改成了 `.parentingSecondaryButton()`；本分支已把整个按钮替换成 `ParentingTodayManualEntry`，合并时保留本分支的写法即可（#131 的 `onManualArrangement` 在 + 菜单里，是另一处）。`UIStrings.json`、`project.pbxproj` 的单行大列表也会和 #131/#132/#135 机械冲突，按行合并。
- 不依赖 #131/#135 的任何符号。主卡「放进输入栏」只写 `natural.draft`（`ParentingNaturalInputModel` 已有的 `@Published var draft`），不提交、不聚焦；#135 如果改名或改成别的状态，只需改 `todayPage` 里 `onFillDraft` 那一行。
- #131 若把 Today 搬进原生 `TabView`，`todayPage` 仍在原来的 `ScrollView` 里，`navigationDestination` 挂在 `ParentingTodayView` 上，需要它所在的 `NavigationStack`；如果改成每个 tab 自带 stack，也应保持成立。

## 依赖 #132 的符号
`ParentingStartAction`（`resolve`、`activeChild`、各 case）、`ParentingStartContext`（`childID`、`lastWeeklyReviewAt`）、`ParentingWeeklyReviewReason`、`ParentingCompletionUndo`（`make`、`isActive`、`expiresAt`、`window`）、`ParentingWorkspaceModel.completeOneTap` / `undoCompletion`、`ParentingCommand.reopenArrangement`（间接）、`ParentingTodayTimeline`（原有）。#132 里这些都有，没有缺的符号，没有臆造任何 API。

#132 留下的一处问题，本分支顺手补上：`Scripts/check-localization.py` 在 #132 上本来就失败（9 条中文字面量没进 `UIStrings.json`：5 个示例句、`今天暂无安排`、`{0} 件·已完成 {1}`、2 条 reopen 错误）。这 9 条英文已补进 `UIStrings.json`。若 #132 自己也补了，合并时 JSON 会冲突，保留一份即可。

## 验证（2026-10-27，Linux box，Swift 6.0.3）
- `swiftc -parse`：`ParentingTodayUI.swift`、`ParentingTodayDeck.swift`、`ParentingHomeView.swift`、`ParentingTodayDeckTests.swift` 全部通过（只是语法，不是类型检查）。
- 纯逻辑单测：临时 SwiftPM 包（`/tmp/pkg-today`，不在仓库；符号链接 `ParentingWorkspace`、`ParentingArrangementEdit`、`ParentingTimelines`、`ParentingStartAction`、`ParentingTodayHome`、`ParentingTodayDeck` 和两个测试文件，`catError`/`stableID` 用垫片）。`swift test`：**48 个用例 0 失败**（本切片 15 个 + #132 的 33 个 `ParentingStartActionTests`）。
- `Scripts/validate-project.py`：PASS（255 个 App 源文件成员关系一致）。Linux 没有 `plutil`，用 /tmp 里的 Python 替身（只实现 `-convert json -o -`，不改仓库脚本）。
- `Scripts/check-localization.py`：UI literal scan、parenting audit、audit 自检、2277 条双语 + 占位符一致，PASS。后面的 InfoPlist 权限段在基线（#132）上就失败（`InfoPlist.strings` 里有 `CFBundleDisplayName`/`CFBundleName`，脚本只允许 `*UsageDescription`），与本切片无关，未处理。
- 仓库检查：`scripts/check-git-policy.py commit` PASS；`scripts/scan-repository.py` 的 blocking 命中全是仓库里原有的图片，本分支新增/修改的文件无命中；`scripts/check-repository.py` 在基线上就失败（冻结文件 `apps/ios/Sources/Domain/ReadoutPolicy.swift` 的 sha256 不符，本分支没有碰它），未处理。

## 诚实限制（没有验证的）
- **所有 SwiftUI 代码没有编译过，没有在模拟器或真机上看过。** Mac 回来后需要先 `xcodebuild build-for-testing`，再看下面「最可能的编译问题」。
- 玻璃观感没看过：`.glassProminent` + 半透明橙 tint + `catColors.ink` 文字在浅色/深色下的对比度、`.glass` 按钮外面再套 `foregroundStyle(ink)` 是否生效、玻璃卡片里放玻璃按钮是否好看，都需要真机/模拟器确认。iOS 26 上实际是否「带一点橙色的半透明」只能看图确认。
- **现有 UI 测试会受影响，本切片没改**：今天页不再直接有 `parenting.addArrangement`、`parenting.confirm.*`、`parenting.complete.*`、`parenting.childFilter`、`parenting.firstChild`（改为 `parenting.today.hero.addChild`）、`parenting.natural.hint`（已删）。`ParentingWorkspaceUITests` 里引用这些 id 的流程要先点 `parenting.today.allArrangements` 进二级页（或点主卡）。#131 上同名测试也有同样问题。
- 主卡完成后，下一条主卡会立刻出现在原位置：快速连点「完成」可能连续完成两件。目前只靠 `model.busy`/本地 `working` 防止重入，没有额外的冷却。
- 「全部确认」是逐条保存（没有批量命令）：中途失败时前面几条已保存、其余仍是草稿，错误条会显示，重试从未保存的开始。
- 示例句 / 「记一下，」放进输入栏用的是当前界面语言的文案；自然输入的解析词表是中文，英文界面下放进去的英文句子不一定能被识别。
- 周复盘时间没有接入「清除全部记录 / 退出登录」的清理流程（键按 subject 隔离，只是一个日期）；不进云同步。
- 没有埋点；没有无障碍（VoiceOver/Dynamic Type）实机检查。主卡按钮在最大字号下的换行没验证。
- 旧的今天页标题块（「把今天的照顾，留给以后回看」、小咪图标）被去掉了，相关 UIStrings 条目没有删（避免和别的分支冲突）。
