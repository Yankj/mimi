# 我们家 v0.6：首页收敛 + 设置/提醒/孩子档案二级页

分支：`feat/family-settings-subpages`，从 main `db41ad37` 新开，只开草稿 PR，不合并。本文件只记录实际做过的验证；没有 Mac 编译，Mock 不等于验收。

## 范围
1. 我们家首页只放四样：孩子档案卡（每个孩子一张）、「启航 n/3」卡、提醒入口一行、设置入口一行。原来的标题说明、孩子标题与「添加孩子」按钮、提醒提示、高级与历史入口、本机保存说明都收进二级页。
2. 设置只有一个入口：我们家 › 设置，用 `NavigationStack` push 二级页（不再是 sheet）。今天、成长、我们家三个首页的工具栏不再有品牌名和齿轮，品牌名的位置让给原生大标题（`.navigationBarTitleDisplayMode(.large)`）。设置页沿用现有设置项，不新增字段，不改后端，不改 `catpaw.family.v1` 协议。
3. 孩子档案详情页：push 二级页，展示昵称、头像底色、已有的目标数和观察数，以及原有的「修改昵称」「删除孩子」（沿用原来的编辑 sheet 和删除确认）。
4. 提醒子页：原来在设置 sheet 里的提醒设置（到点提醒、提醒时间、锁屏隐藏内容、系统通知状态）原样挪到这里。每天最多 2 条、晚 9 点到早 8 点不提醒的规则没有改（`ParentingReminderPlanner` 未动）。
5. 今天页的「手动填写一件事」按钮、成长时间线页的「手动记下观察」按钮已移除。手动入口以后放到「＋」菜单最后一项，那部分归输入栏那条线，本分支不碰。
6. 只用原生 SwiftUI：`NavigationStack` push、大标题、`Form` / `LabeledContent` / `ProgressView` 默认样式。没有自绘玻璃，颜色全部来自 `catColors`，没有新色值。
7. 「启航 n/3」读 `ParentingStartAction.launchChecklist`（#132，已在 integration/v06 接上）：添加孩子、记下第一件事、到点提醒；「跳过提醒步骤」的持久化尚未接入（后续事项）。

## 改动文件
新增：
- `Sources/Features/Parenting/ParentingFamilyModel.swift`：路由 `ParentingFamilyRoute`、孩子摘要 `ParentingFamilySummary`、启航接口与占位实现（纯逻辑）。
- `Sources/Features/Parenting/ParentingFamilyViews.swift`：头像、孩子卡、入口行、启航卡、孩子档案详情页、提醒子页。
- `Tests/ParentingFamilySummaryTests.swift`：上述纯逻辑的单元测试。
- 本文件。

修改：
- `Sources/Features/Parenting/ParentingHomeView.swift`：根 `NavigationStack(path:)`；工具栏；`navigationDestination`；我们家首页；今天/成长页移除手动按钮；`ParentingSettingsView` 改为 push 页（去掉自带 NavigationStack 和「完成」按钮，去掉提醒段，加入「高级与历史」入口）；`init` 增加带默认值的 `launchChecklist` 参数，现有调用方不用改。
- `Resources/UIStrings.json`：新增 12 条中英文案。
- `cat.xcodeproj/project.pbxproj`：登记 2 个 App 源文件和 1 个测试文件。

## 测试 id 列表（accessibilityIdentifier）
新增，均在 `parenting.family.*` / `parenting.settings.*` 下，没有使用 `parenting.composer.*`、`parenting.plus.*`、`parenting.rail.*`：
- `parenting.family.child.<孩子 id>`：孩子档案卡（沿用原前缀，现在是可点的 `NavigationLink`）
- `parenting.family.launch`：启航卡
- `parenting.family.reminders`：提醒入口行
- `parenting.family.settings`：设置入口行（沿用原 id，现在 push 二级页）
- `parenting.family.addChild`：工具栏「添加孩子」（只在我们家、已有孩子时出现）
- `parenting.family.childDetail`、`.childDetail.header`、`.childDetail.goals`、`.childDetail.observations`、`.childDetail.rename`、`.childDetail.delete`、`.childDetail.deleted`：孩子档案页
- `parenting.family.reminders.page`、`parenting.family.reminders.unavailable`：提醒子页
- `parenting.settings.page`：设置页

沿用不变：`parenting.reminders.toggle` / `.lead` / `.hideContent` / `.count`（现在在提醒子页）、`parenting.suggestions.toggle`、`parenting.eraseAll`、`parenting.eraseAll.confirm`、`parenting.openChat`（现在在设置页）。

已删除：`parenting.settings`（原工具栏齿轮）、`parenting.addArrangement`、`parenting.addObservation`。

## 已验证
- `Scripts/validate-project.py` 通过（253 个 App 源文件成员与 Sources 树一致；box 上没有 `plutil`，用等价的 Python 替身把 pbxproj 转成 JSON 后运行，脚本本身未改）。
- `Scripts/check-localization.py` 的 UI 字面量、parenting 审计、双语条目与插值一致性检查通过。
- `swiftc -frontend -parse`（Swift 6.0.3 Linux）：4 个改动/新增的 Swift 文件和新测试文件语法解析通过。
- Linux 上用 `swift test`（Swift 6.0.3 + XCTest）单独编译 `ParentingWorkspace.swift`、`ParentingFamilyModel.swift`、`ParentingArrangementEdit.swift`，跑 `ParentingFamilySummaryTests`：7 个用例 0 失败。（`catError` 和哈希函数用了替身，因为 `CryptoKit` 在 Linux 上不可用。）
- `check-typography.py`、`check-native-design.py`、`check-repository.py`、`check-repository-layout.py`、`scan-repository.py` 在 box 上本来就失败（环境差异），改动前后的输出完全一致，没有新增问题。

## 诚实限制
- **没有在 Mac 编译**。SwiftUI 视图（`ParentingFamilyViews.swift`、`ParentingHomeView.swift` 的改动）只做了语法解析和人工核对，没有类型检查，没有跑 XCTest 和 UI 测试，没有模拟器截图。
- 「启航 n/3」是占位：只读本地账本，三步为「添加孩子昵称」「记下第一件安排或观察」「写下一个成长目标」。它不代表最终的启航规则，等 `ParentingStartAction.launchChecklist` 合入后换成真实实现。
- 头像底色不是存储字段（账本里没有）。它由孩子 id 的第一个字节在 4 种现有颜色令牌里取一个，所以同一个孩子永远同一个颜色，但用户不能选。头像里的字是昵称第一个字，不是照片。
- 孩子档案页没有照片、身高体重、里程碑，也没有这些字段的入口。
- 添加孩子：首页只有四样，所以「添加孩子」放在我们家工具栏右上角（`person.badge.plus`）。没有孩子时仍显示原来的「从孩子的昵称开始」引导卡。这是对「首页只放四样」的一处小偏离，需要产品确认。
- 今天、成长页还保留「查看全部」「添加目标」「记一件事」「记下观察」等目标回顾里的按钮；只移除了名为「手动填写」的两个首页按钮。
- 我们家首页不再显示「仅在此设备加密保存…」说明，同样的话在设置页「数据与边界」里；今天、成长页保持原样。
- **UI 测试会失败**：`ParentingWorkspaceUITests` 里有 7 个用例（10 处）通过 `parenting.addArrangement` 打开安排编辑器，该按钮已按需求移除，要等输入栏的「＋」菜单加入手动入口后再改这些用例。本分支没有改 UI 测试。受影响用例：`testSyntheticParentingJourneyPersistsAcrossColdRelaunch`、`testSyntheticSelectedArrangementRescheduleAndUndoPersistAcrossColdRelaunch`、`testSyntheticPreparationFailureCancelCleanupAndNewIntake`、`testSyntheticDarkLargeTextEmptyAndEditorSmoke`、`testSyntheticPlannedCancellationPersistsAcrossColdRelaunch`、`testSyntheticSwiftDataRollbackRetainsInputRetriesAndColdReadsOneChild`、`testSyntheticUnknownIntakeRequiresOriginalRequestReadback`。
- 英文文案为人工翻译，未经审校。
