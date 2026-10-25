# 交互改造 v0.5 第一版：自然输入 / 确认卡 / 今天时间线 / 三栏导航

分支：`feat/interaction-natural-input`，基于 `feat/ios-family-usable`（#128），同时依赖 #126。本文件只记录实际做过的验证，Mock 不等于验收。

## 做了什么
- 一个输入框（今天页底部）。文本意图 v1：本地规则分类为 录入 / 规划 / 复盘，只作后台标签，不做导航门槛；缺字段时一次只问一个问题（孩子、日期、上/下午等用选项，其余自由回答）；写入前出确认卡，账本回报“已保存”后才显示成功。无网络、无模型。
- 确认卡：`ParentingConfirmCard` 封闭枚举块 + 固定 SwiftUI 组件渲染（无 HTML 沙盒），借鉴 OpenIntelligentUI 的组件注册表思路。
- 今天时间线 `ParentingTodayTimeline`；成长时间线 `ParentingGrowthTimeline`，记录并入成长。
- 底部导航：今天｜成长｜我们家；原办公对话降为“高级与历史”入口。
- 提醒：每天最多 2 条、21:00–08:00 静默（已存在于 `ParentingReminderPlanner`，本次新增测试钉住并在文案展示）。

## 已验证
- Linux 上 Swift 6.0.3 + XCTest 跑 Domain 子集：`ParentingNaturalInputTests` 20 个用例 0 失败（意图分类、缺字段追问、答案合并、改期绑定、时间线、提醒上限/静默）。
- `Scripts/check-localization.py`（含新增自测）、pbxproj 与 Sources 树成员一致性检查。

## 未验证（须如实看待）
- 本机没有 Xcode / 模拟器：`xcodebuild`（DeviceTrial scheme `cat`）未在本地运行；SwiftUI 视图、`ParentingHomeView` 改动、UI 测试调整均未在 Xcode 编译或运行。编译与测试证据以本 PR 的 CI 结果为准。
- 没有模拟器截图（确认卡、今天时间线）。
- `ParentingWorkspaceUITests` 的 tab 辅助函数已按新三栏改写，但未运行。

## 已知限制
- v1 只支持中文规则；图片/语音输入后置；无云端/模型路径。
- 确认卡“手动调整”暂不预填。
- 季节性/主动观察、英语阅读系列后置。
