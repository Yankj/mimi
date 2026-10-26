# 真机验收清单 v0.6（语音 / 玻璃 / 大标题 / 半屏确认卡 / 3 秒撤销 / 深色与大字号）

范围：iOS App `apps/ios-conversation` 的小咪育儿壳（今天｜成长｜我们家 + 底部输入卡）。**本清单只定义要在真机上验什么，不记录任何已通过的结果**；
模拟器 UI 测试不等于真机验收（尤其是玻璃观感、滚动手感、语音、系统权限）。涉及的新切片：`feat/native-liquid-glass`（#131）、`feat/voice-composer`、`feat/today-start-action`（#132）、`feat/family-settings-subpages`。某项依赖的切片没合入时，该项标「未就绪」，不算失败也不算通过。

## 怎么用
1. 设备：iPhone，iOS 26 及以上（玻璃相关项必须 26+；17–25 只验回退外观）。记录：机型 / 系统版本 / App 版本号与构建号（设置页可见）/ 日期 / 测试人。
2. 用测试账号和合成数据（昵称“团团”之类），不要用真实孩子资料。不要为验收清空 Keychain、不要覆盖安装现有版本。
3. 每项 4 列：前置条件、步骤、期望、结果（□ 通过　□ 不通过　□ 未就绪），并写出相关 accessibility id。id 只引用，不在这里改名。
4. 「自动化」列：**自**＝模拟器 XCUITest 已覆盖其中一部分（真机仍要看），**手**＝只能手动。测试名在 `ParentingWorkspaceUITests`。
5. 新 id 约定：`parenting.composer.*`、`parenting.plus.*`、`parenting.rail.*`、`parenting.family.*`、`parenting.settings.*`。**当前 main 里还没有 `parenting.composer.*`、`parenting.plus.*`、`parenting.rail.*`**（前两者在 #131，`rail` 没有任何分支用到）；`parenting.family.*` 与 `parenting.settings` 已有。标「（建议，未实现）」的 id 只是提议，不代表代码里已存在。

## A. 语音（听写开始 / 停止、权限、中断）
前置：#131 的语音优先输入卡 + 语音附件（`feat/voice-composer`，进行中）已装进构建；已登录；至少有一个孩子（输入卡才出现）；今天页或成长页。无语音附件时，“按住说话”只会切到键盘，本组全部标「未就绪」。

| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| A1 | 首次安装后第一次用语音，系统权限未决定 | 按住“按住说话” | 先弹麦克风与语音识别权限说明（文案是 Info.plist 里的说明）；授权前不录音、不出现“正在听…” | `parenting.composer.mic` | 手 | □ □ □ |
| A2 | 已授权 | 按住说一句“团团今天下午五点游泳”，松开 | 按住期间出现“正在听…”和实时文字；松开后文字交给确认卡（半屏），不会直接保存 | `parenting.composer.mic`、`parenting.composer.listening`、`parenting.composer.transcript` | 手（模拟器无麦克风） | □ □ □ |
| A3 | 已授权 | 按住后手指滑开取消（若实现取消手势） | 不产生确认卡，输入框无残留文字，“正在听…”消失 | `parenting.composer.listening` | 手 | □ □ □ |
| A4 | 已授权，正在听 | 来电 / 闹钟响 / 锁屏 / 切到别的 App | 录音立即停止，“正在听…”消失，已识别文字不会被偷偷保存；回到 App 状态干净 | `parenting.composer.listening` | 手 | □ □ □ |
| A5 | 已授权，正在听 | 拔掉或连上耳机 / 蓝牙，Siri 唤起 | 不崩溃；录音要么平稳续上要么平稳停止，有可读提示 | `parenting.composer.transcript` | 手 | □ □ □ |
| A6 | 权限被拒 | 在系统设置里关掉麦克风或语音识别，回 App 按住 | 有明确说明与去设置的指引；键盘输入仍可用；无空白卡死 | `parenting.composer.mic`、`parenting.composer.keyboardToggle` | 手 | □ □ □ |
| A7 | 语音优先布局 | 点键盘切换，打字，再切回语音 | 在语音布局与文字输入之间来回切换，草稿不丢；辅助功能大字号下只显示键盘 | `parenting.composer.keyboardToggle`、`parenting.natural.field` | 自（`testComposerVoiceFirstIdleLayoutAndPlusMenu`，模拟器，#131 后才会运行） | □ □ □ |

## B. 玻璃（Liquid Glass 外观、对比度、降低透明度）
前置：iOS 26+ 真机（观感只在真机上算数）；浅色模式；今天页有几条内容可滚动。

| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| B1 | 有孩子和若干安排 | 看底部系统标签栏和输入卡，慢速上下滚动页面 | 标签栏与输入卡是系统玻璃，内容从玻璃下透出；向下滚动时标签栏收起、向上恢复；最后一项能完整滚到玻璃上方 | `parenting.natural.bar`、系统标签栏（按位置取，无 id） | 部分自（`testInteractionScreenshotsConfirmCardTimelineAndFloatingTabBar` 量几何，不看观感） | □ □ □ |
| B2 | 同上 | 在深色壁纸 / 浅色内容上分别看玻璃上的字与图标 | 标签文字、“+”、“按住说话”、键盘图标清楚可读，没有字被内容吞掉 | `parenting.plus.menu`、`parenting.composer.mic` | 手 | □ □ □ |
| B3 | 设置 › 辅助功能 › 显示与文字大小 › 降低透明度：开 | 回 App 看三个 tab、输入卡、确认卡 | 玻璃变为不透明或近不透明底；边界清晰；文字对比仍足够 | `parenting.natural.bar` | 手 | □ □ □ |
| B4 | 增强对比度：开；粗体文本：开 | 同上 | 按钮与卡片边界更清晰，没有字被截断 | `parenting.natural.confirm`（确认卡里的按钮） | 手 | □ □ □ |
| B5 | iOS 17–25 设备（如有） | 看底部栏 | 回退为自绘悬浮胶囊 + 材质，所有功能一致 | `parenting.tabBar`、`parenting.tab.today` 等 | 自（模拟器，按 `parenting.tab.*`） | □ □ □ |

## C. 大标题
| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| C1 | 有孩子 | 依次点 今天 / 成长 / 我们家 | 每个 tab 顶部是大标题（今天 / 成长 / 我们家）；系统标签栏上三个按钮顺序固定、选中态正确 | 系统标签栏（按位置：今天 0、成长 1、我们家 2） | 部分自（`testThreeTabsAreReachableAndComposerBelongsToTodayAndGrowth`：标题存在、选中、输入卡只在今天与成长） | □ □ □ |
| C2 | 同上 | 向上滚动页面再回顶 | 大标题随滚动折叠为小标题，回顶后展开；无抖动或遮挡 | 无（系统导航栏） | 手 | □ □ □ |
| C3 | 我们家 | 看右上角 | 设置入口只在“我们家”出现（临时位置，待家庭页接管） | `parenting.settings`、`parenting.family.settings` | 手 | □ □ □ |
| C4 | 任一 tab | 点开表单页（如添加孩子） | 表单页是内联标题，不是大标题；取消 / 保存在导航栏 | `parenting.name.save`、`parenting.arrangement.save` | 自（各 `testSynthetic*` 旅程） | □ □ □ |

## D. 半屏确认卡
前置：有孩子；输入卡用文字或语音交一句完整的话，例如“团团今天下午5点游泳”。

| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| D1 | 同上 | 交出这句话 | 确认卡先以半屏（medium）升起，能看到“小咪理解为…”、字段和底部按钮；可拖到全屏 | `parenting.natural.sheet`、`parenting.natural.source`、`parenting.natural.understood` | 部分自（卡与按钮可达，`testInteractionScreenshots…`；半屏高度只能真机看） | □ □ □ |
| D2 | 半屏 | 不拖动，看底部 | “确认”和“手动调整”钉在底部，不被键盘或内容遮住；不点确认前账本没有任何新增 | `parenting.natural.confirm`、`parenting.natural.manual` | 手（确认后写入与回执在 `testInteractionScreenshots…` 里走过，“确认前不写入”没有自动断言） | □ □ □ |
| D3 | 半屏 | 点确认 | 先出现“已保存”回执，约 1.6 秒后自动收起；开启旁白（VoiceOver）时不自动收起，需点“好的” | `parenting.natural.receipt`、`parenting.natural.done` | 部分自（用 `CF_UI_TEST_NO_AUTODISMISS=1` 点“好的”走完保存；自动收起由 #131 分支自带的 `testComposerIsVoiceFirstWithPlusMenuAndAutoDismissingConfirmCard` 测，本分支未含） | □ □ □ |
| D4 | 缺字段（如没说几点） | 交出 | 一次只问一个缺的问题，选项可点；问完才出卡 | `parenting.natural.question`、`parenting.natural.asking` | 手 | □ □ □ |
| D5 | 半屏 | 点“手动调整” | 关闭确认卡并打开手动表单；输入没丢；关闭表单不留草稿 | `parenting.natural.manual`、`parenting.arrangement.source` | 部分自（`testComposerManualEntryOpensArrangementFormAndDiscardLeavesNoDraft` 走“+”或页面按钮这条手动路径） | □ □ □ |
| D6 | 半屏 | 点“关闭”或下拉 | 关闭，输入卡回到空闲，不保存 | `parenting.natural.close` | 手 | □ □ □ |

## E. 3 秒撤销
**现状**：逻辑层已有（`ParentingCompletionUndo`，窗口 3 秒；`completeOneTap` / `undoCompletion`，在 `feat/today-start-action` #132），**还没有任何视图和 id**。UI 切片落地前，本组全部「未就绪」。下面的 id 是（建议，未实现），落地后以代码为准。

| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| E1 | 今天页有一条已确认的安排 | 一键完成 | 立刻变为已完成，并出现带倒计时的“撤销”（只在账本回执后出现） | `parenting.complete.<id>`；撤销入口（建议，未实现）`parenting.undo.button` | 手（UI 未实现） | □ □ □ |
| E2 | 刚完成 | 3 秒内点撤销 | 安排回到“已确认”，自动写入的完成记录消失；家长写过的记录保留为观察 | `parenting.undo.button`（建议，未实现） | 手 | □ □ □ |
| E3 | 刚完成 | 等超过 3 秒 | 撤销入口消失，状态保持已完成；之后只能通过菜单重新打开（若提供） | `parenting.undo.toast`（建议，未实现） | 手 | □ □ □ |
| E4 | 刚完成 | 在 3 秒内切走再回来、旁白开启 | 撤销窗口不会因切换意外延长；旁白能读到并能操作撤销 | `parenting.undo.button`（建议，未实现） | 手 | □ □ □ |
| E5 | 已有的“改期后撤销”（不是 3 秒撤销） | 在安排菜单里撤销改期 | 弹确认；确认后时间回到改期前，历史保留 | `parenting.undoAdjustment.<id>`、`parenting.undoAdjustment.confirm` | 自（`testSyntheticSelectedArrangementRescheduleAndUndoPersistAcrossColdRelaunch`） | □ □ □ |

## F. 深色模式与大字号（Dynamic Type）
前置：分别在 浅色 / 深色 下，字号 默认、最大非辅助（XXXL）、辅助功能最大（AX5）各看一遍；有孩子和几条安排。

| # | 前置 | 步骤 | 期望 | 相关 id | 自动化 | 结果 |
|---|---|---|---|---|---|---|
| F1 | 深色 | 看三个 tab、输入卡、确认卡、表单 | 背景与卡片分层清楚，文字与按钮对比足够，没有硬编码的浅色块 | `parenting.natural.bar`、`parenting.natural.confirm` | 部分自（`testSyntheticDarkLargeTextEmptyAndEditorSmoke` 只截图与可达性） | □ □ □ |
| F2 | AX5 | 看今天页与标签栏 | 标签栏仍可点，标签文字不挤出屏幕（自动测试只断言可点，不量 44pt）；输入卡变为仅键盘，输入框可点 | `parenting.natural.field`、系统标签栏 | 自（`testInteractionFloatingTabBarAtLargestAccessibilityText`） | □ □ □ |
| F3 | AX5 | 打开手动表单，输入长文，再关闭 | 表单可滚动，保存按钮仍在导航栏，不被键盘遮；放弃后不留草稿 | `parenting.arrangement.source`、`parenting.arrangement.save`、`parenting.arrangement.cancel` | 自（`testSyntheticDarkLargeTextEmptyAndEditorSmoke`） | □ □ □ |
| F4 | XXXL 与 AX5 | 看半屏确认卡 | 半屏放不下时可拖到全屏，底部按钮仍可达，字段文字换行不截断 | `parenting.natural.confirm` | 手 | □ □ □ |
| F5 | 深色 + AX5 | 看语音聆听状态 | “正在听…”与文字不被玻璃吞掉，可读 | `parenting.composer.listening`、`parenting.composer.transcript` | 手（未就绪：需语音附件） | □ □ □ |

## 自动化与手动的分工
| 项 | 模拟器 UI 测试覆盖到的部分 | 只能真机 / 手动 |
|---|---|---|
| 语音 | 无（模拟器无麦克风）；仅 A7 的布局切换在 #131 合入后可测 | A1–A6 全部：权限、录音、中断、耳机、被拒 |
| 玻璃 | 标签栏与输入卡的几何位置、最后一项不被盖住 | 观感、对比度、降低透明度、增强对比度、滚动手感、标签栏收起 |
| 大标题 | 三个 tab 的标题存在与选中、输入卡只在今天与成长 | 折叠与展开的动画和手感、设置入口位置 |
| 半屏确认卡 | 确认前不写入、确认后写入、“手动调整”、保存回执（`CF_UI_TEST_NO_AUTODISMISS=1`） | 半屏高度、1.6 秒自动收起、旁白下不自动收起、追问选项 |
| 3 秒撤销 | 无（UI 未实现；逻辑层有单元测试，在 #132） | E1–E4 全部 |
| 深色 / 大字号 | AX5 + 深色下的空页、编辑器、标签栏可点 | 全部 tab 的肉眼对比与换行、半屏卡在大字号下的表现 |

测试基础设施：`ParentingUITestSupport.swift` 的 `ParentingUI.selectTab` 按位置取系统标签栏按钮（`app.tabBars.firstMatch.buttons.element(boundBy:)`，今天 0、成长 1、我们家 2），没有系统标签栏时回退到 `parenting.tab.*`。

## 结论栏（验收时填）
- 设备 / 系统 / 构建号 / 日期 / 测试人：
- A 语音：□ 通过　□ 有不通过项　□ 未就绪　　B 玻璃：□ □ □　　C 大标题：□ □ □
- D 半屏确认卡：□ □ □　　E 3 秒撤销：□ □ □　　F 深色与大字号：□ □ □
- 不通过项与截图 / 录屏位置：
