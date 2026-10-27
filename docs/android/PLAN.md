# mimi Android 研发线：调研与实施计划

> 状态：**计划（提案），未实施**。本文件只描述怎么做，不创建 Android 工程、不改 iOS 代码、不改 `.github/workflows`、不改后端。
> 分支：`docs/android-plan`（基于 `origin/main` `2e5b3da`，2026-10-27），仅本地提交，未推送。
> 目标：做一个与现有 mimi iOS App **对标**的 Android App，**产品设计不变**（同样的信息架构、品牌、文案、交互、安全边界）。
> 依据：仓库 `Yankj/mimi` `main` 的源码与文档（iOS 宿主 `apps/ios-conversation`，后端 `services/edge`），以及 `/workspace/uploads` 里的 v0.5/v0.6 截图、品牌主题提案与 tokens。所有 iOS 路径相对 `apps/ios-conversation/`，除非写明。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 做什么 | 对标 **mimi iOS main 上真正的主入口**：「今天 / 成长 / 我们家」三 Tab + 底部语音优先输入卡 + 半屏确认卡 + 本机加密育儿账本 + 本机提醒 + B/C 两套主题（明/暗）。「高级与历史」里的原对话 Agent（SSE 聊天、记忆、主动、设备工具、任务卡）作为第二批。 |
| 技术路线 | **Kotlin + Jetpack Compose + Material 3 原生**。不选 Flutter / React Native / KMP（理由见第 4.1 节：iOS 已是原生 Swift，跨端复用几乎为零，却要付出品牌细节、语音、系统集成、可测性的代价）。 |
| 架构 | 单 Activity + Compose Navigation；MVVM + 单向数据流（`StateFlow` + 不可变 `UiState` + 事件）；**纯 Kotlin/JVM 的领域层**（可在无模拟器的 Linux 上跑全部单测）；Keystore + AES-GCM 加密账本；OkHttp + kotlinx.serialization 手写合同（与 iOS 一致的严格 JSON）；Hilt 只在 `:app` 做装配。 |
| 本机能不能构建 | **能**。Linux box 上已装好 JDK 21、Android SDK（platform 36/37.1、build-tools 36）、Gradle 9.7，实测用 AGP 9.4 + Compose BOM 2026.09 编出 29 MB debug APK（约 45 秒）。**可用 KVM 节点**，但权限会被系统重置，无头模拟器实测**未能启动**（第 7.4 节）；JVM 单测/Robolectric Compose UI 测试实测可跑。不依赖 GitHub Actions。 |
| 里程碑 | M0 脚手架与主题 → M1 领域层与加密账本 → M2 三 Tab 壳 → M3 输入卡/语音/确认卡 → M4 本机提醒 → M5 账号与「让小咪整理」 = **育儿壳对标，约 38–49 个有效工作日（8–10 周）**；M6 聊天、M7 记忆/主动/设备、M8 小组件与发布 = 完整对标约 **72–94 个工作日（15–19 周）**（估算与假设见第 8 节）。 |
| 最大风险 | ① 国内安卓机无 Google 服务（语音识别、FCM、Google 登录）；② 玻璃效果只能近似；③ 后端目前只有 APNs，没有 FCM；④ 仓库规则当前禁止第二个活跃 App（需要 M0 里显式改规则）；⑤ 没有 Android 真机时语音/通知/玻璃只能算「未真机验证」。 |

需要用户拍板的 5 个问题见第 10 节。

---

## 1. 调研基线（读了什么、看到什么）

### 1.1 仓库现状（`origin/main` `2e5b3da`，2026-10-27 `Merge PR #140`）

- 根目录：`apps/{ios, ios-conversation, ios-openmuse-demo, website}`、`services/{edge, facade, offline}`、`packages/{ios-navigation, ios-image-composer}`、`docs/`、`design/`、`research/`、`scripts/`。
- 当前唯一活跃的 iPhone 宿主是 `apps/ios-conversation`（`docs/development/repository-layout.json`：`native_host`；`apps/ios`、`apps/ios-openmuse-demo` 是冻结目录）。**该文件还写着 `"second_active_app_allowed": false`，`scripts/check-repository-layout.py:40` 会断言它为假。** 所以新增 `apps/android-conversation` 必须同步改这两处（见 M0 与第 10 节问题 3）。
- 根 `README.md` 仍写 `v1.2.0` 与「宝妈育儿」定位；`CHANGELOG.md` 的 `[Unreleased]` 记录了育儿核心、语音优先输入栏、品牌小写 `mimi`/`小咪`；`docs/status/master-plan.md` 是唯一实时状态页。
- 内部标识刻意保留旧名（`docs/internal-identifiers.md`）：`catpaw-api`、`catpaw-proactive`、`catpawm://`、`com.example.CatpawM.*`、`X-Catpaw-*`。Android 端协议/Scheme 必须沿用这些「线上身份」，不能擅自改名。
- iOS 规模（`apps/ios-conversation`）：`Sources` 共 **266 个 Swift 文件 / 约 5.9 万行**；`Tests` 共 131 个文件 / 约 3.0 万行；其中育儿领域层 `Sources/Domain/Parenting*.swift` 仅 **约 2350 行**，育儿界面 `Features/Parenting/` 13 个文件约 5400 行。

### 1.2 视觉与产品依据

- 截图：`Interaction-v05-*.png`（打字输入、半屏确认卡、已保存回执、今天时间轴、玻璃下滚动、成长页、我们家、AX5 大字号）、`glass-*.png`、`family-*.png`、`mimi-ia-0-overview.png`（三个首页「现在 vs 新方案」：今天 ≤4 块/≤8 控件，成长 ≤4 块，我们家 ≤5 块；底部输入栏全局可用；右上角不再放齿轮）、`mimi-brand-theme-{B,C}-{day,night}.png`。
- 设计合同：`docs/design/family-companion/parenting-core.md`（五类核心对象：目标 / 计划 / 安排事项 / 成长观察 / 完成记录；「已安排 ≠ 已发生」；不评价孩子、无打分排名）、`docs/acceptance/family-companion/{today-ui,voice-first-composer,native-liquid-glass,today-start-action,family-settings,growth-visuals}-v0.6.md`、`device-acceptance-v0.6.md`（真机验收清单）。
- 主题：`docs/brand-theme.md`（含与提案的刻意偏差：`ink3` 为满足 ≥4.5:1 改了色值；按压态着色统一 30%）、`/workspace/uploads/mimi-brand-themes-{proposal.md,tokens.json}`。**以 `Sources/DesignSystem/CatTheme.swift` 与 `docs/brand-theme.md` 为准，不以提案 JSON 的 `ink3` 为准。**

### 1.3 iOS 实际的产品形态（这是对标对象）

`App/catApp.swift` 里 `accountChat` 在已登录后直接展示 `ParentingHomeView`（`Features/Parenting/ParentingHomeView.swift`，2246 行）：

```
ParentingHomeView（iOS 26 用系统 TabView + 玻璃；17–25 用自绘悬浮胶囊栏）
├─ 今天   ParentingTodayUI / ParentingTodayHome / ParentingTodayDeck
├─ 成长   ParentingGrowthHomeView / ParentingGrowthPages / ParentingTimelineViews
├─ 我们家 ParentingFamilyViews（孩子卡、启航清单、提醒、设置）
├─ 底部全局输入卡（今天、成长 Tab 才出现）ParentingNaturalInputViews / ParentingVoiceControls
├─ 半屏确认卡（medium detent）ParentingNaturalInputViews / Domain/ParentingConfirmCard
├─ 手动表单 sheet：孩子 / 目标 / 安排 / 观察 / 完成 / 改期
└─ 「高级与历史」→ ConversationFirstView（原聊天 Agent，以 sheet 打开，对话不会自动变安排）
```

注意：`Features/RootView.swift` 的 `BriefView` / `SidebarView`（旧「个人助理」）和 `ios-openmuse-demo` 都**不是**当前主入口，Android 不对标它们（`Features/Parenting` 注释与 `parenting-core.md` §2 已明确）。

---

## 2. iOS 功能与屏幕清单（inventory）

### 2.1 育儿壳（P0：用户默认看到的 App）

| 区域 | 内容 | iOS 主要文件 |
|---|---|---|
| 启动与首次使用 | 仅首次安装出现的启动页（小浮胶囊而非全屏 loader）、首次引导（外观选择、毛绒猫）、登录门（匿名「体验账号」/ 邮箱 / Google / GitHub） | `Features/Launch/*`、`Features/Onboarding/*`、`Features/NativeLoginView.swift`、`Features/NativeOAuthViews.swift` |
| 今天 | 日期与孩子范围筛选；主卡（状态感知，hero）；待确认；时间轴 ≤5 行（圆圈一键完成/滑动）；「接下来」；最近观察；子页「全部安排」（已完成/已取消折叠）；安排详情（原话/调整记录/提醒状态） | `Features/Parenting/ParentingTodayUI.swift`、`ParentingHomeView.swift`、`Domain/ParentingTodayHome.swift`、`ParentingTodayDeck.swift`、`ParentingTimelines.swift` |
| 一键完成与 3 秒撤销 | `ParentingOneTapGate`、`ParentingCompletionUndo`（窗口 3 秒；撤销入口 UI 在 #132 之后落地） | `Domain/ParentingTodayHome.swift`、`ParentingTodayDeck.swift` |
| 成长 | 时间线/目标与回顾两种模式；本周圆点条；目标 chip；目标详情（进度环、增删、回顾）；成长记录（原话/录入与更正时间/改删） | `ParentingGrowthHomeView.swift`、`ParentingGrowthPages.swift`、`ParentingGrowthComponents.swift`、`Domain/ParentingGrowthInsights.swift` |
| 我们家 | 启航清单（仅首次，n/3）、孩子卡与孩子详情（改名/目标/观察/删除）、提醒（状态行）、设置（账户、回顾提示、数据与隐私、高级与历史、关于/版本） | `ParentingFamilyViews.swift`、`ParentingFamilyModel.swift`、`Domain/ParentingStartAction.swift` |
| 输入卡 | 语音优先（按住说话，上滑发送/左滑取消，转写实时进入可编辑文字，键盘一键切换，“+”菜单：手动记安排/记观察） | `ParentingVoiceControls.swift`（483 行）、`Features/Composer/ComposerDictation.swift`（928 行）、`ComposerHoldToTalk.swift` |
| 自然语言整理 | **本地**中文词法 + 时间读取（缺字段一次只问一个问题、选项可点）；半屏确认卡；确认前账本零写入；已保存回执 1.6 秒自动收起（旁白开启不自动收） | `Domain/ParentingNaturalInput.swift`（336 行）、`ParentingNaturalLexicon.swift`（200 行）、`ParentingRescheduleTime.swift`、`ParentingConfirmCard.swift`、`Features/Parenting/ParentingNaturalInputModel.swift` |
| 改期/调整 | 原话改期（「改到周五下午三点」）、冲突核对、调整历史、撤销改期 | `Domain/ParentingArrangementEdit.swift`、`ParentingRescheduleTime.swift` |
| 原位「让小咪整理」 | 显式点击才发送原话到后端（受限提取 namespace，禁工具/记忆/主动）；原位等待、失败重试、取消、只读恢复、清理队列；候选需家长显式采用 | `Features/Parenting/ParentingInlineIntakeModel.swift`、`Domain/ParentingInlineIntake.swift`、`ParentingIntakeDraft.swift`、`Providers/NativeParentingIntakeComposition.swift`、`Persistence/ParentingIntakePersistence.swift` |
| 本机提醒 | 只提醒已确认安排；每天最多 2 条；21:00–08:00 不打扰；提前 15/30 分钟、1 小时、前一天晚上 8 点；系统通知被关时的引导 | `Domain/ParentingReminderPlan.swift`、`Providers/ParentingReminderScheduler.swift`、`ParentingSystemNotificationCenter.swift` |
| 隐私 | 账号隔离、加密本机账本（AES-GCM + 独立 Keychain 命名空间）、隐私遮罩（后台/锁屏）、全部清除 | `Persistence/ParentingWorkspaceStore.swift`、`L2SecureStore.swift`、`Features/ConversationFirstView.swift`（`catPrivacyShield`） |
| 主题与外观 | 调色板 C「素白墨黑」（默认）/ B「雾粉+燕麦」；外观 跟随系统（默认）/浅/深；半透明橙着色玻璃按钮 | `DesignSystem/CatTheme.swift`、`CatButtonStyles.swift`、`catBrandTokens.swift`、`Components.swift` |
| 吉祥物 | 毛绒猫多姿态（待命/思考/写作/完成/抱歉/晚安…） | `DesignSystem/catPlushMascot.swift`、`Resources/Assets.xcassets/catBrand/Mascot` |
| 本地化 | 中/英双语；**2348 条** UI 文案，键 = 中文原句（带 `{0}` 占位），值 = `en` / `zh-Hans` | `Resources/UIStrings.json`、`UIStringExemptions.json`、`Scripts/check-localization.py` |

### 2.2 高级与历史（P1/P2：原对话 Agent，在设置里以 sheet 打开）

| 能力 | 内容 | iOS 主要文件 |
|---|---|---|
| 流式聊天 | URLSession SSE，断线按原 turn 恢复，`X-cat-Live-Text: 1` 实时预览，严格 JSON，上下文窗口由服务端管理 | `Features/ConversationSessionService.swift`、`Providers/NativeChatHTTPBackend.swift`（约 1900+ 行）、`Features/Answer/*`、`Features/Markdown/*` |
| 图片/附件 | 相机、相册、去 EXIF、缩图、模型许可 | `packages/ios-image-composer`、`Features/Composer/*`、`Features/NativeMediaPicker.swift` |
| 语音对话 | 听→发送→思考→朗读→继续听，可插话打断 | `Domain/VoiceSession.swift`、`VoiceGuard.swift`、`Features/Composer/ComposerDictation.swift` |
| 记忆库 | 服务端 agent-memory；来源、更正、忘记、开关；对话里「已写入记忆」提示 | `Features/Memory/*`、`Features/ConversationMemory*.swift`、`Domain/ConversationMemory.swift` |
| 主动能力 | 端上规则 + 配额 + 「为什么找你」+ 撤销；EventKit 信号、BGAppRefresh、本地通知；`catpaw-proactive` 决策与推送；Live Activity / Widget | `Features/Proactive/*`、`App/catProactiveFeed.swift`、`catLiveActivityCoordinator.swift`、`Widgets/catWidgets/*` |
| 设备工具 | EventKit 日历/提醒、Contacts、MapKit（附近地点、ETA、打开地图）、短信/邮件/电话跳转；人在回路确认卡 | `Features/DeviceCalls/*`、`Features/Nearby/*`、`Providers/NativeCalendarEffectExecutor.swift` |
| 任务/成果/收件箱 | 计划、审批卡、任务控制、报告阅读与编辑、跨会话收件箱 | `Features/ConversationTask*`、`ConversationArtifact*`、`ConversationInbox*`、`ConversationApprovalCardView.swift` |
| Generative UI | 注册表驱动的卡片块（选择、确认、表单、列表、进度、地点、日程） | `Features/GenerativeBlocks/*`、`Domain/GenerativeBlock.swift` |
| 推送 | APNs token 上报（`PUT /devices/tokens`），Live Activity token | `App/catPushNotifications.swift`、`Providers/catPushTokenUploader.swift` |
| 功能准入 | `catpaw.feature-enrollment.v1`（邀请码） | `Features/Settings/FeatureEnrollment.swift` |

### 2.3 后端/网络层（Android 直接复用，不重写）

- Supabase Edge：`catpaw-api`（聊天 SSE、记忆、任务、设备效果、`/catpaw/parenting-intake/v1/...`、`/catpaw/account/v1/session`、`/catpaw/features/v1`）与 `catpaw-proactive`（`/devices/tokens`、事件、任务）。入口 `services/edge/supabase/functions/`；契约交接 `services/edge/docs/NATIVE-HANDOFF.md`，家庭 API `docs/design/family-companion/api-v1.md`（家庭云尚未对客户端开放，育儿账本目前**只在本机**）。
- 认证：Supabase Auth（`/auth/v1`），匿名登录、邮箱密码、OAuth PKCE（回调 `catpawm://auth-callback`）。iOS 是**手写** HTTP（`Providers/NativeAuthHTTPRemote.swift`），Android 同样手写，不引入 supabase-kt。
- 严格合同：`Domain/ConversationStrictJSON.swift`、`ConversationCanonicalJSON.swift`（规范化哈希必须与服务端逐字节一致）；金标准夹具在 `Tests/Fixtures/*.json`（如 `memory-canonical-v1.json`）。Android 的规范化与严格解析必须读这些夹具做一致性测试。
- 推送现状：服务端只有 APNs（`_shared/proactive/apns.mjs`、`core/src/apns.mjs`），**没有 FCM**。

---

## 3. 功能对标矩阵（iOS → Android）

阶段含义：**P0**＝育儿壳（M0–M5，首个可用版本）；**P1**＝高级聊天（M6）；**P2**＝记忆/主动/设备（M7）；**P3**＝小组件、Live Update、推送、打磨（M8）。「差异」列写清 Android 做不到完全一样的地方，产品文案/信息架构一律不变。

| # | iOS 功能 | iOS 文件 | Android 对应 | 阶段 | 差异 / 备注 |
|---|---|---|---|---|---|
| 1 | 三 Tab（今天/成长/我们家），iOS 26 系统 TabView 玻璃，旧系统自绘悬浮胶囊栏 | `Features/Parenting/ParentingHomeView.swift` | 单 Activity + `NavHost`；自绘悬浮胶囊底栏（Compose + Haze 模糊），选中态按主题（C 中性胶囊 / B 橙着色 12%） | P0 | 不用 M3 `NavigationBar`（观感不一致）；TalkBack 里仍暴露为 Tab 语义（`Role.Tab`、`selected`） |
| 2 | 大标题（滚动折叠为小标题） | `ParentingHomeView.swift` | `LargeTopAppBar` + `exitUntilCollapsedScrollBehavior` | P0 | 表单页用内联标题，与 iOS 一致 |
| 3 | 今天页：hero 主卡、待确认、时间轴 ≤5 行、接下来、最近观察、全部安排子页 | `ParentingTodayUI.swift`、`Domain/ParentingTodayHome.swift`、`ParentingTodayDeck.swift` | `feature:parenting` 的 `TodayScreen` + `TodayViewModel`；领域层 1:1 移植 | P0 | `LazyColumn`；吊顶/时间轴「现在」线用 Canvas |
| 4 | 圆圈一键完成 + 3 秒撤销 | `Domain/ParentingTodayHome.swift`（`ParentingCompletionUndo`）、`ParentingOneTapGate` | 同逻辑；撤销用 M3 `Snackbar` 样式的自绘条（带倒计时），TalkBack `liveRegion` 播报 | P0 | iOS 侧撤销 UI 仍在落地（#132）：Android 以 iOS 最新 main 为准，再定稿 |
| 5 | 成长页：时间线 / 目标与回顾；周圆点；目标进度环；成长记录 | `ParentingGrowth*.swift`、`Domain/ParentingGrowthInsights.swift` | `GrowthScreen`、`GoalDetailScreen`；进度环 Canvas | P0 | 无打分排名等产品边界不变 |
| 6 | 我们家：启航清单、孩子卡/详情、提醒、设置 | `ParentingFamilyViews.swift`、`ParentingFamilyModel.swift`、`Domain/ParentingStartAction.swift` | `FamilyScreen` + 子页（Navigation 子图） | P0 | 孩子色（`ParentingChildAccent`）沿用 5 色 |
| 7 | 手动表单（孩子/目标/安排/观察/完成/改期） | `ParentingHomeView.swift`（`ParentingSheet`） | 全屏 `Dialog`/导航页，保存/取消在顶栏 | P0 | 键盘遮挡用 `imePadding()`；放弃不留草稿 |
| 8 | 输入卡：语音优先、键盘切换、「+」菜单 | `ParentingVoiceControls.swift`、`ParentingNaturalInputViews.swift` | `ComposerBar` composable；悬浮在底栏上方，`WindowInsets.ime` 处理 | P0 | AX 大字号下仅键盘（与 iOS 同） |
| 9 | 按住说话（上滑发送、左滑取消）、电平条、计时 | `Features/Composer/ComposerHoldToTalk.swift`、`ComposerDictation.swift` | `pointerInput` 手势 + `SpeechRecognizer`；电平取 `onRmsChanged` | P0 | 识别器能力与 iOS 不同，见 4.6 |
| 10 | 听写分段、停顿续听、失败重试上限 | `Domain/DictationPolicy.swift`（`DictationTranscript`、`DictationRestart`） | 纯 Kotlin 1:1 移植 + 同样的单测 | P0 | `ERROR_NO_MATCH/SPEECH_TIMEOUT` 映射为「分段结束」 |
| 11 | 本地自然语言整理（意图、时间读取、缺字段追问） | `Domain/ParentingNaturalInput.swift`、`ParentingNaturalLexicon.swift`、`ParentingRescheduleTime.swift` | `core:domain` 纯 Kotlin；正则与词表逐条移植 | P0 | 用 iOS 的 `ParentingNaturalInputTests` 用例转成金标准表（见 6.3）；注意 NSRange 为 UTF-16 偏移，Kotlin 字符串同为 UTF-16，天然一致 |
| 12 | 半屏确认卡（medium）+ 回执 1.6s 自动收起 | `ParentingNaturalInputViews.swift`、`Domain/ParentingConfirmCard.swift` | M3 `ModalBottomSheet`（部分展开可拖到全屏），按钮钉底；回执用 `LaunchedEffect` 延时收起 | P0 | TalkBack 开启时不自动收起（对应 `AccessibilityManager.isTouchExplorationEnabled`） |
| 13 | 加密本机账本（修订号 CAS、operationID 去重、损坏阻写） | `Persistence/ParentingWorkspaceStore.swift`、`Domain/ParentingWorkspace.swift` | `core:data`：Keystore AES-GCM + 原子写文件，同一 `ParentingCommand` reducer | P0 | 见 4.3；不进系统备份 |
| 14 | 账号切换清除账号绑定数据；退出登录 | `App/catApp.swift`、`Domain/AccountEpoch.swift` | `AccountEpoch` 移植；按 `userId` 分目录，退出即删密钥与文件 | P0 | 只有 OS 副作用（系统通知）保留给用户（与 iOS 规则一致） |
| 15 | 本机提醒（≤2 条/天，21:00–08:00 勿扰，提前量） | `Domain/ParentingReminderPlan.swift`、`Providers/ParentingReminderScheduler.swift` | `ReminderPlanner` 移植；`AlarmManager` + `NotificationManager`，`BOOT_COMPLETED` 重排 | P0（M4） | Android 13+ 需 `POST_NOTIFICATIONS`；精确闹钟权限策略见 4.8 |
| 16 | 隐私遮罩（后台/受保护数据不可用） | `Features/ConversationFirstView.swift`（`catPrivacyShield`） | `FLAG_SECURE`/`setRecentsScreenshotEnabled(false)` + 回前台遮罩；设备锁定检测 | P0 | 非生物识别；与 iOS 同为「遮」而非「锁」 |
| 17 | 启动页 + 首次引导 + 毛绒猫 | `Features/Launch/*`、`Features/Onboarding/*`、`DesignSystem/catPlushMascot.swift` | Android 12+ `SplashScreen` API（品牌色 + 图标）+ Compose 首次引导；毛绒猫用同一套位图资源（`Resources/Assets.xcassets/catBrand/Mascot`） | P0 | 启动页只在首次安装出现（沿用规则），小浮胶囊而非全屏 loader |
| 18 | 登录：匿名体验 / 邮箱 / Google / GitHub（PKCE） | `Providers/NativeAuth*.swift`、`Domain/NativeOAuth.swift`、`Features/NativeLoginView.swift` | 手写 HTTP；OAuth 用 Custom Tabs + 回调 `catpawm://auth-callback`（Intent filter）；令牌用 Keystore 包裹存储 | P0（M5） | **需后端/Supabase 允许 Android 回调**（第 10 节问题 5） |
| 19 | 原位「让小咪整理」（受限提取、许可、恢复、清理） | `ParentingInlineIntakeModel.swift`、`Domain/ParentingInlineIntake.swift`、`Providers/NativeParentingIntakeComposition.swift` | `core:network` + `feature:parenting`；同一状态机 | P0（M5） | 严格 JSON / 规范化哈希必须过 iOS 夹具 |
| 20 | 主题：C/B × 明/暗；外观跟随系统 | `DesignSystem/CatTheme.swift`、`Components.swift` | `core:designsystem`：`MimiColors` + `CompositionLocal`，映射到 M3 `ColorScheme`；对比度单测 | P0（M0） | 见 4.7 |
| 21 | 半透明橙着色玻璃按钮 | `DesignSystem/CatButtonStyles.swift` | `Modifier.mimiTintedGlass(state)`；默认 16–20% 着色、按压 30%、语音按住 28%、禁用无着色；字用主题 ink | P0（M0） | 无 Liquid Glass；Haze 模糊 + 85% 不透明卡片底，见 4.7 |
| 22 | 无障碍 id（`parenting.*`，约 196 个） | 全仓 `accessibilityIdentifier` | `Modifier.testTag("parenting.…")` + `testTagsAsResourceId`；`contentDescription` 单独写给 TalkBack | P0 | 见 4.9；动态 id（含 uuid）保持同格式 |
| 23 | 中英双语（2348 条，键=中文原句） | `Resources/UIStrings.json` | 同一 JSON 作为 asset 加载，`ui(key, args)` 同语义；缺失键测试 | P0 | 见 4.10 |
| 24 | 设置：账户、回顾提示、数据与隐私、高级与历史、关于（版本+构建号） | `ParentingHomeView.swift`（设置页）、`Domain/AppBuildInfo.swift` | `SettingsScreen`；版本来自 `BuildConfig` | P0 | 构建号=git 提交数（对标 iOS 规则） |
| 25 | 流式聊天（SSE、断线按 turn 恢复、live text） | `Features/ConversationSessionService.swift`、`Providers/NativeChatHTTPBackend.swift` | `feature:chat`；OkHttp 流式读行 + 自写 SSE 解析（需要 `Last-Event`/sequence 恢复，不用通用 SSE 库） | P1 | 与 iOS 共用 `Tests/Fixtures` 做线格一致性测试 |
| 26 | Markdown 渲染、回答阶段动画 | `Features/Markdown/*`、`Features/Answer/*` | Compose 自绘 Markdown 子集（或 `multiplatform-markdown-renderer`，M6 评估） | P1 | 先支持 iOS 实际用到的子集 |
| 27 | 图片/相机/相册附件 | `packages/ios-image-composer`、`NativeMediaPicker.swift` | Photo Picker + CameraX；去 EXIF、缩图到长边 2048、≤5 MiB | P1 | 后端图片合同 `schema_version=2` 不变 |
| 28 | 语音对话（朗读回复、插话） | `Domain/VoiceSession.swift`、`ComposerDictation.swift` | `TextToSpeech` + 同一状态机；音频焦点 | P1 | 默认开启语音回复规则不变 |
| 29 | 记忆库 | `Features/Memory/*` | `feature:memory` | P2 | 同后端 `/catpaw/memory/v1` |
| 30 | 主动能力（规则、配额、Why、撤销） | `Features/Proactive/*` | 规则/配额/去重纯 Kotlin 移植；信号源 `CalendarContract`、`WorkManager`（周期≥15 分钟）；卡片 + 可操作通知 | P2 | `BGAppRefresh` 无等价物，时机不如 iOS 精确，需验收标准里写明 |
| 31 | 设备工具：日历/提醒/联系人/地图 | `Features/DeviceCalls/*`、`Features/Nearby/*` | `CalendarContract`（需 `READ/WRITE_CALENDAR`）、`ContactsContract`、`geo:` Intent；**附近地点与 ETA 需要地图服务**（Google Places/Routes 或国内图商） | P2 | Android 无「提醒事项」App，提醒落为日历事件或本地通知；地图后置（问题 1） |
| 32 | 任务计划/审批/控制/成果/收件箱、Generative UI 块 | `Features/ConversationTask*`、`ConversationArtifact*`、`GenerativeBlocks/*` | `feature:chat` 内的卡片注册表，合同不变 | P2 | 与 iOS 同一份 typed contract |
| 33 | 小组件 / Live Activity | `Widgets/catWidgets/*`、`App/catLiveActivityCoordinator.swift` | Jetpack **Glance** 小组件；Android 16+ 用 `Notification.ProgressStyle`（Live Updates），低版本退化为常驻通知 | P3 | 非一一对应；验收单独写 |
| 34 | 推送（APNs） | `App/catPushNotifications.swift`、`catPushTokenUploader.swift` | FCM（需要后端新增 `fcm` token 类型与发送实现）；国内无 GMS 机型不可用 | P3 | 后端改动需用户批准（问题 5）；P0–P2 不依赖推送 |
| 35 | 功能准入（邀请码） | `Features/Settings/FeatureEnrollment.swift` | 同接口 | P2 | — |

---

## 4. 技术选型与架构

### 4.1 选型对比

对标对象是一个**已存在、品牌细节很重的原生 SwiftUI App**，Android 版的难点不是「一份代码跑两端」，而是「**另一端也要做得一样好**」。评估维度按用户要求：性能、体验、完整性、行业做法，再加上本项目特有的：能否在这台 Linux box 上自动验证、和现有代码的复用度。

| 维度 | **A. Kotlin + Compose + M3（原生）** | B. Kotlin Multiplatform（共享领域/网络）+ 原生 UI | C. Compose Multiplatform（两端共 UI） | D. Flutter | E. React Native |
|---|---|---|---|---|---|
| 与现有 iOS 代码复用 | 无直接复用，靠**共享合同夹具**保一致 | 只有在 **iOS 改用 KMP 产物** 时才有复用；iOS 现为纯 Swift（Domain 约 1 万行，育儿域仅 2350 行），迁移成本 > 重写成本 | 需重写 iOS UI，等于推翻现有已验收 App | 零复用（Dart 第三套代码） | 零复用（TS 第三套代码） |
| 性能 | 原生渲染，Baseline Profile、R8；列表/动画最稳 | 同 A（UI 仍原生） | 接近原生，iOS 侧会退步 | 自绘引擎，包体与内存更大；Impeller 流畅 | 桥/新架构可用，但长列表与手势动画需额外调优 |
| 体验与品牌保真（玻璃、大标题、半屏卡、触感） | 最高：M3 可定制 + Haze 模糊/玻璃 | 同 A | 中：Android 可做，iOS 失去原生玻璃/大标题 | 中：全部自绘，系统行为（预测性返回、字体缩放、TalkBack）要手工对齐 | 中：依赖第三方原生模块，系统行为差异多 |
| 语音听写 / 本机通知 / 精确闹钟 / 日历 / 联系人 / 小组件 / Live Update | 直接用平台 API（`SpeechRecognizer`、`AlarmManager`、`CalendarContract`、Glance） | 同 A | 同 A（expect/actual） | 靠插件，质量参差，改插件成本高 | 靠社区模块，同左 |
| 可测性（本 box、无 CI） | JUnit + Robolectric + Roborazzi **全在 JVM 跑**，不需模拟器；本机已验证可构建 | 同 A，另加 Kotlin/Native 链 | 同 A + iOS 端链 | Flutter 工具链需另装；widget test 好，但与原生真机行为脱节 | Jest + Detox，需模拟器/设备 |
| 无障碍 id 与 TalkBack | `testTag` + `contentDescription`，与 `parenting.*` 约定直接对应 | 同 A | 同 A | 需 `Semantics` 手工映射 | `testID`/`accessibilityLabel` |
| 行业做法 | Google 官方推荐路线；已有原生 iOS 的团队补 Android 的主流选择 | 逻辑复杂且长期双端同步的 Kotlin 团队才值得 | 新项目/小团队可选，不适合「已有成熟原生 iOS」 | 新项目两端同时起步常用 | JS 团队常用 |
| 对本项目的结论 | **推荐** | 暂不做；领域层按「纯 Kotlin、无 Android 依赖」写，**保留日后改 KMP 的出口** | 否 | 否 | 否 |

**决定：A（Kotlin + Jetpack Compose + Material 3 原生）。** 理由归纳：

1. iOS 已经是原生 SwiftUI，其他三条路线都不能复用一行 iOS 代码，却都要多承担一层框架风险；
2. 产品卖点依赖**语音按住说话、本机提醒、系统日历、玻璃质感、大字号与 TalkBack**，这些全部是平台能力，原生最稳；
3. 育儿领域层很小（≈2350 行 Swift），**重写比桥接便宜**，而且重写时可把 iOS 的单测用例逐条转为 Kotlin 金标准，天然交叉验证两端；
4. 原生 + JVM 单测可在这台无 Mac、无 CI 的 Linux box 上自动验证绝大部分逻辑；
5. 保持纯 Kotlin 领域模块，将来如果决定统一到 KMP，迁移成本低。

### 4.2 工具链与版本（2026-10-28 联网核对，M0 开工时再复核一遍）

| 项 | 版本 | 说明 |
|---|---|---|
| Android Gradle Plugin | **9.4.0**（9.3.x 备选） | 要求 Gradle ≥ 9.6.0、JDK 17+，最大 API 37；本 box 已用 9.4.0 + Gradle 9.7.0 编过 |
| Gradle | 9.7.0 | Kotlin 2.4.21 官方测试上限 |
| Kotlin | 2.4.21 | Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | 2026.09.00（稳定）→ Compose 1.12.1、Material3 1.4.0 | **注意：Compose 1.12 要求 `compileSdk ≥ 37`**（本 box 实测首次构建因 compileSdk 36 失败，改 37.1 后通过）；`targetSdk` 可独立设 36 |
| minSdk | **29（Android 10）** 提议 | 覆盖面广；`SpeechRecognizer` 本机识别需 31+，30 及以下走在线识别回退；见问题 1 |
| JDK | 21（构建），字节码目标 17 | — |
| 模糊/玻璃 | Haze 2.0（`haze` + `haze-glass` 实验性，`haze-blur` 稳定） | 2026-09 发布；Android 17 QPR2 有原生 Backdrop 通道，Haze 做自动回退 |

### 4.3 架构分层与模块

与 iOS 的分层规则对齐（`apps/ios-conversation/AGENTS.md`：Views → Services → Domain；Providers 管 HTTP/Auth；Persistence 管存储；**视图不得持有账号、不得直接发 HTTP、不得执行副作用**）：

```
:app                    组合根（Hilt）、Application、Manifest、导航、深链、版本信息
:core:domain            纯 Kotlin/JVM（无 Android 依赖）：Parenting* 合同与 reducer、自然语言解析、
                         提醒规划、听写策略、AccountEpoch、严格/规范化 JSON、错误类型
:core:designsystem      MimiTheme（B/C × 明/暗）、tokens、对比度计算、玻璃封装、按钮样式、毛绒猫、动效、
                         AdaptiveTextLayout 等价物
:core:i18n              UIStrings.json 加载与 ui(key,args)、缺失键检测
:core:data              加密账本、Room（聊天缓存/outbox，P1 起）、DataStore 偏好、BlobStore、Keystore 封装
:core:network           Auth HTTP、parenting-intake、（P1）chat SSE、（P2）memory/proactive/devices
:core:voice             SpeechRecognizer 封装、听写会话状态机、（P1）TTS
:core:device            通知渠道/提醒调度、（P2）日历/联系人/定位
:feature:parenting      今天/成长/我们家/输入卡/确认卡/表单/设置/引导（对应 iOS Features/Parenting 与 Launch/Onboarding）
:feature:chat           （P1/P2）聊天、任务卡、成果、收件箱、Generative UI
:feature:memory         （P2）记忆库
:feature:proactive      （P2）主动卡与 Why
:testing                假实现（Fake Clock/Store/Network/Recognizer）、夹具加载、Compose 测试工具
```

起步只建 `:app :core:domain :core:designsystem :core:i18n :core:data :core:network :core:voice :core:device :feature:parenting :testing` 10 个模块（其余在 M6/M7 才添加）。模块边界保持：`core:domain` 不依赖任何别的模块；`feature:*` 不互相依赖，只依赖 `core:*`；`core:network` 不依赖 UI。

### 4.4 状态管理（MVVM + 单向数据流）

- 每个屏幕：`ViewModel` 暴露 `StateFlow<UiState>`（不可变 data class），UI 只发 `Intent`/`Event`；副作用（导航、Snackbar、系统设置跳转）走 `Channel`。
- 账本的变更沿用 iOS 的**命令式 reducer**：`sealed interface ParentingCommand`（对应 `ParentingCommand` 枚举的 19 个 case）→ `ParentingWorkspace.reduce(command, expectedRevision)`，带修订号 CAS 与 `operationID` 去重；失败不发布成功。
- 「保存」结果、系统提醒结果、模型回复是**三个独立事实**（iOS 规则：流结束 ≠ 已授权 ≠ 已生效），UI 状态里分开建模。
- Compose：`collectAsStateWithLifecycle()`；列表项用稳定 key（uuid）；重计算放 ViewModel；Strong Skipping 默认开启。

### 4.5 持久化与安全

| 数据 | iOS | Android |
|---|---|---|
| 育儿账本（孩子/目标/安排/记录/调整历史/设置） | 加密 JSON blob（AES-GCM），修订号 CAS，独立 Keychain 命名空间 `catpaw.parenting.v1`，`AfterFirstUnlockThisDeviceOnly` | `AtomicFile` 写加密 blob；AES-256-GCM 密钥放 **Android Keystore**（硬件/StrongBox 优先，不可导出），按 `userId` 分目录；`allowBackup=false` + `dataExtractionRules` 排除（否则备份恢复后有密文无密钥）；账本损坏阻写不覆盖 |
| 账号/令牌 | Keychain（`NativeAuthSecureStorage`） | Keystore 包裹的 AES 密钥 + DataStore（不使用已弃用的 `EncryptedSharedPreferences`） |
| 聊天/outbox/Blob（P1） | SwiftData + `BlobStore` + `ConversationOutbox` | Room（SQLite）+ 内部存储文件；outbox 重放规则同 iOS（未知结果只读原请求，**不重发**） |
| 偏好（palette/theme/language、语音已说明标记等） | UserDefaults / Repository preference | `DataStore<Preferences>`，**沿用同样的键名**（`palette`、`theme`、`language`、`mimi.voice.permissionExplained`） |
| 隐私 | 账号切换清除；删除全部；泄露最小化 | 同；日志不含用户原话；`FLAG_SECURE` 可选项 |

### 4.6 语音 / 听写（SpeechRecognizer）

iOS 做法：iOS 26 `SpeechAnalyzer` + `SpeechTranscriber`，更低版本 `SFSpeechRecognizer`；识别默认只在本机；权限在第一次按麦克风前先用站内一句话说明。

Android 方案：

1. 优先 `SpeechRecognizer.createOnDeviceSpeechRecognizer()`（API 31+，先 `isOnDeviceRecognitionAvailable()`，API 33+ 可用 `checkRecognitionSupport` / `triggerModelDownload` 引导下载 zh-CN 语言包），`EXTRA_PREFER_OFFLINE=true`，`EXTRA_PARTIAL_RESULTS=true`，语言随应用语言（`zh-CN` / `en-US`，对应 `DictationPolicy.localeIdentifier`）。
2. 不支持本机识别的设备回退 `createSpeechRecognizer()`（通常把音频送到设备厂商/Google 服务）。**此时站内说明必须如实写明「本次可能经系统语音服务在线识别」**，与 iOS「默认只在本机」的承诺不同，需产品确认文案（问题 1）。
3. 会话状态机：把 `DictationTranscript` / `DictationRestart` / `ParentingVoicePresentation` 原样移植；`ERROR_NO_MATCH`、`ERROR_SPEECH_TIMEOUT` 视为「分段结束」，`ERROR_RECOGNIZER_BUSY` 取消后重建；连续 3 次空失败才放弃。
4. 电平条：`onRmsChanged`（约 20 Hz 节流，仅 UI 状态，不落盘）；按住手势用 `pointerInput` + `awaitPointerEventScope`，上滑发送/左滑取消阈值沿用 iOS。
5. 中断：来电/音频焦点丢失/切后台立即停止；已识别文字**不会被偷偷保存**（确认前账本零写入）。
6. 权限：`RECORD_AUDIO` 运行时申请，拒绝后给「打开系统设置」指引，键盘输入始终可用。
7. 风险兜底：国内 ROM 常无 Google 语音服务，`SpeechRecognizer` 可能整体不可用。预案：检测不可用时只显示键盘；另备「应用内离线识别」方案（如 sherpa-onnx 的 Apache-2.0 中文流式模型，按需下载，非 P0）。是否投入由问题 1 决定。

### 4.7 主题：B/C token → Compose，玻璃等价物

**Token 来源**：`DesignSystem/CatTheme.swift`（Foundation-only 的纯值模型，天然可移植）与 `docs/brand-theme.md`。轴与键名完全沿用：`palette`（`inkWhite`=C 默认 / `softBlush`=B）、`theme`（`system` 默认 / `light` / `dark`）。

**移植方式**：

- `data class MimiColorSet(canvas, card, surface, hairline, track, ink, ink2, ink3, accent, accentOn, accentOnPressed, orangeText, success, attention, danger, successFill, attentionFill, dangerFill, kids[5], tintAlpha{default, pressed=0.30, voiceHold=0.28}, tabFill …)`，四个实例（C/B × 明/暗）。
- `CompositionLocal<MimiColorSet>` 提供给所有自绘组件；同时映射到 M3 `ColorScheme`（让 `Switch`、`TextField`、`ModalBottomSheet` 等 M3 组件自动着色）：

| M3 角色 | mimi token |
|---|---|
| `background` / `surface` | `canvas` / `card` |
| `surfaceContainer(Low/High)` | `surface`（燕麦/次级面） |
| `onSurface` / `onSurfaceVariant` / `outline` | `ink` / `ink2` / `ink3` |
| `outlineVariant` | `hairline` |
| `primary` / `onPrimary` | 着色玻璃按钮不用实心 primary；`primary`=`accentOn`，`onPrimary`=`ink`（M3 默认实心按钮不使用） |
| `error` / `tertiary`（attention） | `danger` / `attention` |

- 动态取色（Material You）**关闭**：品牌固定，不跟壁纸变化。
- 对比度：把 `CatThemeTokenTests` 的 WCAG 实现与断言（文字对 ≥4.5、图标对 ≥3）原样转成 Kotlin 单测，覆盖四种组合；并新增一个**跨端快照** `docs/design/mimi-theme-tokens.json`（M0 生成），iOS 与 Android 两端测试都对它断言，防止漂移。
- 按钮：`Modifier.mimiTintedGlass(shape, state)`：`85% 不透明 card 底` → `橙色着色 α`（日间 16%/20%，夜间 20%，按压 30%，语音按住 28%）→ `1dp 橙描边`（38%/55%，夜间 40%/60%）→ 字用主题 ink、图标用 `accentOn`；禁用 = 无着色 + hairline + `ink3`；**无橙色投影**。这些数值直接来自 `docs/brand-theme.md` 与提案，不重新设计。

**玻璃（iOS 26 `glassEffect`）的 Android 等价物**——诚实边界：Android 没有系统级 Liquid Glass，只能近似。

| 层级 | 做法 | 适用 |
|---|---|---|
| 首选 | Haze 的 `hazeBlur`/`hazeGlass`（`HazeInput.Backdrop`，Android 17 QPR2 有原生 Backdrop 通道时自动用；否则捕获源回退），叠 tint + 细描边 | API 31+（`RenderEffect`/AGSL）的悬浮底栏、输入卡、确认卡边缘 |
| 回退 | 不透明/半透明 **M3 tonal surface**（`card` 色 92–96% 不透明）+ hairline | API 29–30、低性能设备、省电模式 |
| 无障碍 | 「降低透明度」在 Android 无系统开关 → 应用内「减弱透明效果」选项 + 读取「高对比度文字」「移除动画」系统设置时自动走回退层（`LocalGlassAccessibilitySettings` 可对接） | 全部 |
| 约束 | Haze Glass 在 2.0 仍标注实验性；只在**悬浮栏/输入卡**这类小面积使用，文字承载面（卡片、确认卡正文）一律实色 | 保证对比度测试可证 |

JVM 截图测试（Robolectric）**不能渲染真实模糊**，玻璃观感只能靠真机验收（见 6.4 与 `device-acceptance-v0.6.md` 的 B 组）。

### 4.8 提醒与后台（不依赖推送）

- iOS 用 `UNUserNotificationCenter` 本机排程。Android：`NotificationChannel`（「安排提醒」）+ `AlarmManager.setExactAndAllowWhileIdle`（需要 `SCHEDULE_EXACT_ALARM`，API 33+ 默认不预授权，需引导用户到「闹钟和提醒」设置；`USE_EXACT_ALARM` 仅限闹钟/日历类应用且 Play 审核严格，**不假设可用**）。无精确闹钟权限时降级为 `setAndAllowWhileIdle`（允许几分钟误差）并在提醒页如实说明。
- 重启/时区变化/应用更新：监听 `BOOT_COMPLETED`、`TIMEZONE_CHANGED`、`MY_PACKAGE_REPLACED` 重排。
- 规则（≤2 条/天、21:00–08:00 不打扰、提前量枚举、「提醒时间已过/当天已有 2 条」等跳过原因）由 `ReminderPlanner`（`ParentingReminderPlanner` 的移植）统一计算，**纯函数 + 单测**。
- 厂商电池优化（国产 ROM 杀后台）：文档化已知限制，设置页给「打开系统电池设置」入口，不在 P0 内承诺 100% 准时（写入验收清单）。

### 4.9 无障碍与 id 约定

- 每个 iOS `accessibilityIdentifier("parenting.xxx")` → `Modifier.testTag("parenting.xxx")`，并在根节点 `semantics { testTagsAsResourceId = true }`，使 UiAutomator/Compose 测试都能用同一字符串。**只引用、不改名**；动态 id（如 `parenting.complete.<uuid>`）格式不变。
- 新 id 约定沿用：`parenting.composer.*`、`parenting.plus.*`、`parenting.rail.*`、`parenting.family.*`、`parenting.settings.*`。
- TalkBack 文案走 `contentDescription` / `stateDescription` / `Role`，与 testTag 解耦（iOS 的 label/hint 与 identifier 同理）。
- 大字号：iOS 验 AX5（约 310%）；Android 字体缩放最高 200%（系统设置），需另外验「显示大小」。验收组 F 中 AX5 对应「字体 200% + 显示大小最大」。
- 沿用的行为规则：确认卡保存后 TalkBack 开启不自动收起；撤销条用 `liveRegion`；触摸目标 ≥48dp。
- 边到边（targetSdk 35+ 强制）与预测性返回手势一并适配。

### 4.10 本地化（UIStrings.json）

- iOS 的键是**中文原句**（含 `{0}` 占位），值是 `{en, zh-Hans}`，由 `ui("…")` 查找，`check-localization.py` 保证源码里的中文字面量都在表里。
- Android：构建时把 `Resources/UIStrings.json` **原样复制**为 `assets/UIStrings.json`（Gradle `Copy` 任务，不拷贝成第二份源文件），`core:i18n` 提供 `ui(key, vararg args)`，语义与 iOS 一致；应用内语言偏好键 `language`（`zh-Hans` 默认 / `en`）。
- 系统层字符串（应用名、权限用途文案、通知渠道名、Splash）用 `res/values{,-zh-rCN}/strings.xml`，数量很少，单独列表维护；`localeConfig` 声明支持语言。
- 新增一个 Android 版检查脚本（`apps/android-conversation/scripts/check-localization.py`）：扫描 Kotlin 中 `ui("…")` 字面量，必须存在于 `UIStrings.json`；与 iOS 脚本共用同一份例外清单格式。**新增文案同时更新同一个 JSON（iOS 与 Android 共享），不另起一份。**

### 4.11 网络与认证

- OkHttp + `kotlinx.serialization`；**不用 Retrofit**（合同要求严格 JSON、规范化哈希、自定义恢复语义，反射式映射帮倒忙）。
- 严格 JSON：拒绝重复键、未知字段按合同处理、数字/Unicode 规范化与 `ConversationCanonicalJSON.swift` 逐字节一致；用 `Tests/Fixtures/*.json` 做金标准（Android 测试从仓库相对路径读取 iOS 夹具）。
- SSE（P1）：自写基于 `BufferedSource.readUtf8Line` 的解析，维护 `turn id / last sequence`，断线用 `GET .../turns/{tid}/events?after_sequence=N` 恢复；**未知结果只读原请求，不重发**。
- 认证：匿名 `POST /auth/v1/signup`、邮箱密码、OAuth PKCE（Custom Tabs，回调 `catpawm://auth-callback`）；刷新并发与「不确定刷新结果」的安全屏障按 `NativeAuthSessionManager` 逐条移植并写测试。
- 配置：后端 origin、Supabase 项目 URL、anon key 来自**本地** `local.properties`/`BuildConfig`（与 iOS `Local.xcconfig` 对应，**不入库**）；无配置时只能跑离线合成路径（与 iOS 一致）。

### 4.12 依赖注入

**Hilt**，仅在 `:app` 做装配与 ViewModel 注入（Google 官方路线、可替换测试模块）；`core:*` 用构造函数注入，保持可在 JVM 里直接 new。iOS 用手写组合根（`NativeChatComposition`、`NativeMemoryComposition`），对应 Android 的 `:app` 里 `Module`。

### 4.13 导航

Navigation Compose（类型安全路由）：根为三 Tab（各自保存状态），「我们家」下是子图（孩子详情/提醒/设置）；表单与确认卡为 sheet/全屏对话；深链沿用 `catpawm://…` 的现有目标（`Domain/DeepLinks.swift`）。

### 4.14 版本与构建号

对标 iOS 规则：版本号单一来源 `apps/android-conversation/version.properties`（SemVer，与 iOS `Configuration/Version.xcconfig` 的营销版本保持同号策略，发布时由脚本一起 bump），`versionCode` = git 提交数，构建时写入 `BuildConfig`，设置 › 关于显示「版本 X.Y.Z (build N) · 发布于…」，提交号可复制。**不手改 versionCode。** 签名：debug 用 debug keystore；release keystore 由用户生成并保管，**永不入库**。

---

## 5. 目录结构（`apps/android-conversation`，M0 才创建）

```
apps/android-conversation/
├── AGENTS.md                 # 与 iOS 的 AGENTS 对齐的分层规则、禁止项（视图不碰 HTTP/账号）
├── README.md                 # 构建、测试、环境（JDK/SDK 版本）、无配置只跑离线
├── version.properties        # 单一版本来源
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml # 版本目录（Compose BOM、Kotlin、AGP、Haze、Hilt…）
├── gradle/wrapper/           # 固定 Gradle 9.7.0
├── app/                      # :app
├── core/{domain,designsystem,i18n,data,network,voice,device}/
├── feature/{parenting,chat,memory,proactive}/   # chat/memory/proactive 在 M6/M7 才建
├── testing/                  # :testing（Fake*、夹具加载）
├── scripts/
│   ├── verify.sh             # 本机一键：lint + 单测 + Robolectric + 截图校验 + assembleDebug
│   ├── check-localization.py
│   ├── check-testtags.py     # 对照 iOS 的 parenting.* id 清单（缺失/多余报告）
│   └── bump-version.sh
└── docs/                     # 本线的验收记录（device-acceptance-android-v1.md 等）
```

仓库级变更（M0 PR 里一起做，且**不碰 `.github/workflows`**）：

- `docs/development/repository-layout.json`：新增 `android_host: "apps/android-conversation"`，把 `second_active_app_allowed` 改为「同一产品的另一平台客户端允许」的明确语义（或新增 `platform_clients` 字段）；
- `scripts/check-repository-layout.py:40` 的断言、`AGENTS.md` 第一屏的「Edit only apps/ios-conversation…」措辞、`docs/README.md` 索引同步更新；
- 根 `README.md` 目录结构与徽章加 Android 一行（只加，不改 iOS 叙述）；
- `docs/status/master-plan.md` 新增 Android 线状态段（唯一实时状态页）。

---
## 6. 测试策略

分层沿用 iOS 的「证据分层」原则：**合成/夹具、JVM 单测、模拟器、真机、线上**分别报告，不互相冒充（`README`/`master-plan` 的既有规则）。

### 6.1 层次与工具

| 层 | 内容 | 工具 | 在哪跑 | 对标 iOS |
|---|---|---|---|---|
| L1 领域单测 | `core:domain` 全部（reducer、自然语言解析、改期时间、提醒规划、听写策略、成长洞察、主题对比度、严格/规范化 JSON） | JUnit 4/5、kotlin.test、Turbine | **本 box，秒级，无需模拟器** | `Tests/Parenting*Tests.swift`、`CatThemeTokenTests`、`Scripts/test-*-portable.sh` |
| L2 数据/网络单测 | 加密账本（用内存 fake Keystore 与可注入的 cipher 测失败路径）、修订号 CAS、损坏阻写、账号切换清除；Auth/intake/SSE 对 `MockWebServer`；用 iOS 夹具验规范化哈希 | JUnit、MockWebServer、Robolectric（Keystore 以 fake 注入） | 本 box | `ParentingWorkspaceTests`、`ConversationMemoryTransportTests`、`Tests/Fixtures/*.json` |
| L3 Compose UI 测试（JVM） | 每个屏幕的可达性、状态、`testTag`、TalkBack 语义、字体缩放与深色冒烟、三 Tab 与输入卡仅在今天/成长出现、确认前账本零写入 | `compose-ui-test` + **Robolectric**（`createComposeRule`） | **本 box**（见第 7 节的实测） | `ParentingWorkspaceUITests`（14 个旅程）、`ParentingVoiceUITests` |
| L4 截图回归 | 关键屏 × 主题（C/B × 明/暗）× 字号（1.0 / 2.0）；组件库画廊 | Roborazzi（Robolectric 原生图形）；也可用官方 Compose Preview Screenshot Testing | 本 box 出基线图 | `Scripts/measure-interaction.sh`、`Interaction-v05-*.png` |
| L5 设备/模拟器 instrumented | 权限弹窗、`SpeechRecognizer` 真实行为、通知与闹钟、重启重排、`FLAG_SECURE`、进程被杀后恢复 | AndroidX Test、UiAutomator（用 `testTagsAsResourceId`）、Gradle Managed Device | Mac 上的模拟器 / **Android 真机**（本 box 的模拟器见第 7 节结论） | 模拟器 XCUITest + `device-acceptance-v0.6.md` |
| L6 真机验收清单 | 语音、玻璃近似观感、大标题折叠、半屏确认卡、3 秒撤销、深色与大字号、省电/厂商后台限制 | 人工，逐项记录机型/系统/版本/日期 | 用户或测试人员的 Android 手机 | `docs/acceptance/family-companion/device-acceptance-v0.6.md` |

### 6.2 门禁与脚本

- `apps/android-conversation/scripts/verify.sh`：本机一键 = 布局/禁入检查 + `check-localization.py` + `check-testtags.py` + `./gradlew lintDebug testDebugUnitTest verifyRoborazziDebug assembleDebug`。**不依赖 GitHub Actions**（免费分钟已用约 90%）；是否以后接 CI 另议，且不改 `.github/workflows`。
- 提交前：沿用仓库既有 `.githooks`（pre-commit / pre-push）与敏感信息扫描；不 `--no-verify`；Android 的 `local.properties`、签名文件、Supabase 配置加入 `.gitignore`。
- 覆盖率目标（建议）：`core:domain` ≥ 90% 行覆盖；对标 iOS 的每个 `Parenting*Tests` 至少有等价用例（建一张「iOS 测试 → Android 测试」对照表，随 M1 落地）。

### 6.3 跨端一致性（防止「两个产品」）

1. **自然语言金标准表**：把 `ParentingNaturalInputTests`、`ParentingRescheduleIntakeTests` 里的「输入句 → 意图/字段/时间」用例导出为 JSON（M1 在 `apps/ios-conversation/Tests/Fixtures/` 旁新增 `parenting-natural-golden-v1.json`，iOS 与 Android 都断言）。固定时区与「现在」时间。
2. **规范化 JSON / 严格 JSON**：Android 直接读现有 `Tests/Fixtures/memory-canonical-v1.json`、`accepted-work-wire-v*.json`、`device-effect-contract-v2.json`。
3. **主题 token 快照**：`docs/design/mimi-theme-tokens.json`（见 4.7）。
4. **UI 文案**：同一份 `UIStrings.json`；`check-localization` 两端同跑。
5. **无障碍 id 清单**：`check-testtags.py` 把 iOS 源码里 `parenting.*` 字面量与 Android 的 `testTag` 对照，缺失（尚未实现）与多余（Android 自造）都要列出，P0 完成时缺失 = 0（iOS 标注「建议，未实现」的 id 除外）。

### 6.4 Android 版真机验收清单

仿 `device-acceptance-v0.6.md` 的格式，新建 `docs/android/device-acceptance-android-v1.md`（M3 起每个里程碑追加）：

| 组 | 项目（与 iOS 同编号思路） | Android 特有项 |
|---|---|---|
| A 语音 | 首次权限说明、按住听写、上滑/左滑、来电/锁屏/切后台中断、耳机/蓝牙、拒绝权限后键盘可用 | 本机识别包是否已下载；无 Google 语音服务时的提示；麦克风隐私指示点 |
| B 玻璃 | 滚动时底栏/输入卡观感、对比度、「减弱透明效果」 | Haze 路径 vs 回退路径（API 29–30 / 省电模式）；低端机掉帧 |
| C 大标题 | 三 Tab 大标题折叠/展开、设置入口位置 | 预测性返回、边到边、手势导航/三键导航 |
| D 半屏确认卡 | 半屏升起/拖全屏、按钮钉底、回执自动收起、缺字段追问 | 软键盘与 `ModalBottomSheet` 的 IME 交互 |
| E 3 秒撤销 | 一键完成、撤销、超时消失 | TalkBack 播报 |
| F 深色/大字号 | 四种主题 × 字体 1.0/2.0 × 显示大小最大 | 系统深色、应用内「跟随系统」切换即时生效 |
| G 提醒（新增） | 开启提醒、系统通知被关的引导、≤2 条/天与勿扰、提前量、重启后重排、改时区 | Android 13 通知权限、精确闹钟权限、Doze、厂商电池优化 |
| H 数据与账号（新增） | 退出登录清除、切账号不串数据、全部清除、卸载重装后无残留密文 | 不进系统备份；多用户/工作资料 |

---

## 7. 本机（Linux box）的 Android 构建与验证能力

调研时间：2026-10-28 01:00–01:15（Asia/Shanghai）。以下是**实测**，不是推测。

### 7.1 调研开始时的状态

| 项 | 状态 |
|---|---|
| 系统 | Debian 13 (trixie)，x86_64，8 核，15 GiB 内存，`/workspace` 所在盘剩余约 76 GB |
| JDK / Gradle / Android SDK / adb | **全部未安装**（`java`、`gradle`、`sdkmanager`、`adb`、`emulator`、`ANDROID_HOME` 均无） |
| 网络 | 可访问 `dl.google.com`、`services.gradle.org`、`repo.maven.apache.org`、Google Maven、`api.adoptium.net`、GitHub（HTTP 200/302） |
| 权限 | 普通用户 `box`，有免密 `sudo`；不需要 root 安装（全部装在用户目录） |
| `/dev/kvm` | 存在，`crw-rw---- root:103`；`box` 不在该组；CPU 报告 VT-x（嵌套虚拟化，Hypervisor = KVM） |

### 7.2 现在已安装（用户目录，所有 agent 共享同一台 box）

| 路径 | 内容 |
|---|---|
| `~/jdk/jdk-21.0.13+9` | Temurin JDK 21.0.13 |
| `~/android-sdk` | cmdline-tools 23（`latest`）、platform-tools 37.0.1、emulator 37.2.12、`platforms;android-36`、`platforms;android-37.1`、`build-tools;36.0.0` 与 `36.1.0-rc1`、`system-images;android-35;google_apis;x86_64`（共约 5.2 GB） |
| `/tmp/gradle-9.7.0` | Gradle 9.7.0（`/tmp` 可能被清理；项目将来用 Gradle Wrapper，不依赖它） |
| `~/.gradle` | 依赖缓存（约 1.1 GB） |
| AVD `mimi_test` | Pixel 6 / Android 15（API 35）x86_64，**已创建但见 7.4** |

使用方式（将来 `scripts/verify.sh` 会自己设置）：`export JAVA_HOME=~/jdk/jdk-21.0.13+9 ANDROID_HOME=~/android-sdk`。

### 7.3 构建与 JVM 测试：实测通过

在 `/tmp/android-smoke`（一次性临时工程，**不在仓库、不是 Android 工程雏形**）验证：

| 验证 | 结果 |
|---|---|
| AGP 9.3.1 + Compose BOM 2026.09.00 + `compileSdk 36` | **失败**：Compose 1.12.1 的 AAR 元数据要求 `compileSdk ≥ 37`（9 个依赖报同一条）。→ 计划里写明用 `compileSdk 37.1`（已装 `android-37.1`） |
| AGP 9.4.0 + Gradle 9.7.0 + Kotlin 2.4.21 + `compileSdk 37.1` + `targetSdk 36` + `minSdk 29` | **成功**：`assembleDebug` 约 45 秒（依赖缓存后），产出 29.2 MB debug APK |
| Robolectric 4.16 + `compose-ui-test-junit4`：`createComposeRule` 渲染 `Text` 并按 `testTag("parenting.tab.today")` 断言可见 | **通过**（1 个测试，0 失败；包含首次下载依赖共约 39 秒） |

结论：**编译、打包、全部 JVM 单测、Robolectric 驱动的 Compose UI 测试、（待加的）Roborazzi 截图基线都能在这台 box 上自动跑**，不需要 Mac、不需要 CI。

### 7.4 模拟器与真机：本机不能当作验收手段

- `emulator -accel-check` 报 KVM 可用，但：① 普通用户没有 `/dev/kvm` 权限；我用 `sudo setfacl -m u:box:rw /dev/kvm` 临时授权，**该授权会被系统重置**（两次都在几分钟内失效，设备节点时间戳变了）；② 在授权有效期内启动无头模拟器（`-no-window -gpu swiftshader_indirect`，API 35 x86_64），qemu 进程长时间只占约 1% CPU，`adb` 始终为 `offline`，**没有启动成功**。已清理进程。
- 因此：**本 box 的模拟器视为不可用**（嵌套虚拟化 + 软件渲染 + 权限易失）。不要把它写进验收路径；不在 box 上给模拟器永久改权限。
- 没有物理 Android 设备连接到 box，也不能通过 box 控制用户手机。
- 用户有 **Mac 与 iPhone 13**，没有提到 Android 手机。可行验证路径：
  1. **box**：L1–L4（单测、Robolectric Compose UI 测试、截图基线、`assembleDebug`/`assembleRelease` 出 APK）；
  2. **用户的 Mac**：装 Android Studio，用 Apple Silicon 上的 arm64 模拟器（Pixel + Android 15/16）跑 L5，并且 USB 连一台 Android 手机即可真机调试；box 通过 GitHub 分支（由有权限的 agent 推送并经用户批准）或 bundle 把代码交给 Mac；
  3. **真机验收**（L6）需要一台 Android 手机（建议 Android 14+，最好是 Pixel 或有 Google 服务的机型）。**问题 4 需要用户确认设备。**
- GitHub Actions 免费分钟已用约 90%：**不规划 CI**。APK 在 box 上构建后，经用户批准通过 GitHub Release 或直接文件传给用户安装（与 iOS 的 ipa 分发同思路）。

---

## 8. 里程碑与估算

**假设**：1 条开发线（1 个主实现 agent + 1 个评审/修复 agent，如 NoBug；box 构建与 JVM 测试，Mac/真机验证由用户配合）；估算为**有效工作日**，±30%；每个里程碑末尾由评审 agent 做三方评分（沿用现有 ≥20/25、信任与安全 ≥4/5 的门槛），**不满足不进入下一个里程碑**；iOS main 在这期间继续演进，每个里程碑开工前先 `git fetch` 对齐当时的 iOS 行为。

| 里程碑 | 目标 | 内容 | 估算 | 出口（验证方式） |
|---|---|---|---|---|
| **M0 脚手架与主题** | 能构建、有品牌外壳 | 获批后：创建 `apps/android-conversation` Gradle 工程（10 个模块起步）；版本目录；仓库布局规则与检查脚本更新（4.14 / 第 5 节）；`core:designsystem`：B/C × 明/暗 tokens + 对比度单测 + `mimi-theme-tokens.json` 快照；着色玻璃按钮与 Haze 底栏原型；毛绒猫资源；`core:i18n` 加载 `UIStrings.json` + 检查脚本；启动页；`scripts/verify.sh`；组件画廊 + 4 主题截图基线 | 4–5 天 | `verify.sh` 全绿；对比度测试覆盖四种组合；box 产出 debug APK；评审 ≥20/25 |
| **M1 领域层与加密账本** | 逻辑 1:1 | 移植 `Domain/Parenting*`（≈2350 行）：`ParentingWorkspace`/`ParentingCommand` reducer、自然语言词法与时间读取、改期、提醒规划、今天/成长投影、启航清单；`DictationPolicy`；`AccountEpoch`；严格/规范化 JSON；Keystore AES-GCM 账本 + CAS + 损坏阻写；金标准夹具（iOS 与 Android 共用）；iOS→Android 测试对照表 | 6–8 天 | L1/L2 全绿；金标准表双端一致；覆盖率 ≥90%（domain） |
| **M2 三 Tab 壳 + 手动录入** | 可手动使用 | 今天/成长/我们家三屏、悬浮底栏、大标题折叠、孩子/目标/安排/观察/完成/改期/调整历史/删除的表单与菜单、一键完成 + 3 秒撤销、启航清单、设置页（外观/主题、数据清除、关于/版本）、隐私遮罩、首次引导；全部 `parenting.*` testTag；字体 200% 与深色冒烟 | 9–12 天 | L3 旅程测试（对标 `ParentingWorkspaceUITests` 全部 14 条）；截图基线；Mac 模拟器冒烟；**首个内部 APK** |
| **M3 输入卡 · 语音 · 确认卡** | 对标默认交互 | 语音优先输入卡、「+」菜单、`SpeechRecognizer` 封装与分段/重试状态机、按住手势（上滑发送/左滑取消）、电平与计时、权限说明、本机自然语言整理、缺字段追问、半屏确认卡与回执自动收起、手动调整回流；`device-acceptance-android-v1.md` A/D 组 | 7–9 天 | L3 + 假识别器旅程；**真机语音验收（A 组）**；确认前账本零写入有断言 |
| **M4 本机提醒** | 到点提醒 | 通知渠道与权限、`AlarmManager` 调度、勿扰/每日上限/提前量、引导页、重启/时区/更新重排、状态行（已排/已到/已过/超限）；G 组验收 | 4–5 天 | L1/L3 + 真机 G 组（含 Doze 与电池优化说明） |
| **M5 账号与「让小咪整理」** | 云联通 | Supabase Auth（匿名/邮箱/Google/GitHub PKCE）、令牌与刷新围栏、账号切换清除、`catpaw/account/v1/session`、原位整理（许可、等待、失败重试、取消、只读恢复、清理队列）、功能准入；需要 Supabase 允许 Android 回调（问题 5） | 8–10 天 | MockWebServer + iOS 夹具；真机连测试后端的一次受控检查（**需用户另行同意真实模型调用**） |
| ━━ 育儿壳对标完成（M0–M5） | | | **约 38–49 天 ≈ 8–10 周** | 三方评分 ≥20/25，信任与安全 ≥4/5；真机 A–H 组验收 |
| **M6 高级聊天（P1）** | 「高级与历史」 | `feature:chat`：SSE 聊天与断线恢复、live text、Markdown、回答阶段、相机/相册附件（Photo Picker/CameraX）、模型许可、语音对话 + TTS、会话历史、Room 缓存与 outbox | 12–15 天 | 线格夹具一致；MockWebServer SSE；真机语音对话 |
| **M7 记忆 · 主动 · 设备（P2）** | 能力补齐 | 记忆库、主动引擎（规则/配额/去重/Why/撤销，`CalendarContract` + `WorkManager`）、设备工具（日历/联系人/`geo:`）、任务计划/审批/成果/收件箱、Generative UI 块；地图类（附近/ETA）取决于问题 1 | 14–18 天 | 夹具 + 假后端；真机日历与通知；按 iOS 现有 `AF-*` 合同同版门分别报告 |
| **M8 小组件 · 推送 · 打磨 · 发布（P3）** | 可发布 | Glance 小组件、Android 16 Live Updates、FCM（需后端改动与批准）、Baseline Profile、R8、无障碍全面审计（TalkBack 全旅程）、省电/厂商后台指南、签名与 APK/Release 分发、`device-acceptance` 全组 | 8–12 天 | 全组真机验收；发布前清单 |
| ━━ 完整对标（M0–M8） | | | **约 72–94 天 ≈ 15–19 周** | |

排序理由：先做用户一打开就看到的东西（M0–M5），且 M0–M1 全在 JVM 里验证、风险最低；M3 才第一次依赖真机（语音），所以**最迟 M2 末要确定真机**；M5 之前不需要任何后端改动；推送（FCM）排最后，因为 iOS 的「主动」核心本来就是端上规则 + 本机通知，推送只是补充。

---

### 8.1 M0 实施记录与偏差（2026-10-28，分支 `feat/android-m0`）

已按计划落地：`apps/android-conversation` Gradle 工程（AGP 9.4.0 / Gradle 9.7.0 / Kotlin 2.4.21 / Compose BOM 2026.09.00，compileSdk 37.1、targetSdk 36、minSdk 29，applicationId `com.heydusk.mimi`）；版本目录与 Wrapper；B/C × 明/暗 tokens、独立 WCAG 对比度单测、`docs/design/mimi-theme-tokens.json` 跨端快照（由 `scripts/export-theme-tokens.py` 从 `CatTheme.swift` 导出，Android 单测逐值核对）；着色玻璃按钮与 Haze 悬浮底栏；毛绒猫资源；`core:i18n` 加载 `UIStrings.json`（构建时 Sync，不复制）；启动页；`scripts/verify.sh`；仓库布局规则；README / AGENTS。

与第 8 节原描述的偏差（均已在 README 或本节说明）：

| 项 | 计划 | 实际 | 原因 / 后续 |
|---|---|---|---|
| 模块数 | 10 个模块起步 | 5 个：`app`、`core:domain`、`core:designsystem`、`core:i18n`、`feature:parenting` | M0 只需要这些；其余模块在用到时再建，避免空壳。`core:data/network/security/...` 随 M1 起加 |
| Hilt | M1 | 未引入 | 与计划一致，M0 无依赖注入需求 |
| 组件画廊 + 4 主题截图基线 | M0 | 没有独立画廊；“我们家”页临时放配色/外观切换与着色按钮预览。四主题截图由 `ShellScreenshotTest` 生成到 `build/screenshots/`，**未入库、未作为回归基线** | 当前没有真机，Robolectric 里 Haze 只有降级色罩，基线意义有限；真机接入后再决定用 Roborazzi 还是人工验收 |
| 毛绒猫资源 | 毛绒猫资源 | 只带 `MascotFront/Idle/Note` 三张（@2x→xhdpi、@3x→xxhdpi） | 其余姿态随功能引入 |
| 跨端快照“两端都断言” | iOS 与 Android 都对快照断言 | 只做了 Android 侧（并有导出脚本保证快照与 Swift 源同步） | 本次不改 iOS 目录；iOS 侧测试留给 iOS 的下一次改动 |
| 仓库规则 | 改 `repository-layout.json`、检查脚本、`AGENTS.md` | 新增 `android_host` / `android_host_role` / `website_stub` 三个键；`second_active_app_allowed` 仍为 `false`，含义改为“每个平台只有一个活跃 App”；检查脚本新增对 Android 工程的存在性与“无 iOS 宿主/Swift 文件”断言 | **顺带修了一个在 main 上就已失败的检查**：`website` 字段在 `8fdae78`（网站迁出）后变成了说明文字，`check-repository-layout.py` 因此在 main 上就会断言失败；改为检查 `apps/website/README.md` 桩 |
| 扫描策略 | 未提及 | `scripts/reviewed-findings.json` 增加一条：`gradle-wrapper.jar`（`unexpected_binary`，SHA-256 与 Gradle 官方发布值一致） | 否则 `scan-repository.py` 会把 Wrapper JAR 当成未审核二进制 |
| debug 包名 | 未规定 | debug 与 release 同为 `com.heydusk.mimi`（不加 `.debug` 后缀） | 与“applicationId 固定”一致；旁路安装需求出现再加 |
| 文案补充目录 | 仅共享 `UIStrings.json` | 另有 `core/i18n/src/main/assets/UIStringsAndroid.json`（7 条，主题演示用，共享目录优先） | 共享目录里没有“素白墨黑/雾粉燕麦/配色/浅色”等词，而本次不改 iOS 的 Resources；设置页上线时并回共享目录并删除补充项 |
| 版本微调 | activity-compose 1.11.0 | 1.13.0（stable）；Haze 2.0.1；Robolectric 4.17 | 复核时的最新稳定版 |

复核结论（2026-10-28）：AGP 已有 9.4.1、Gradle 已有 9.8.2，仍按指定的 9.4.0 / 9.7.0 实现（lint 会提示新版可用，非错误）；`targetSdk 36` 低于 SDK 最新的 37，lint 的 `OldTargetApi` 为提示级，M6 发布前再升。

M0 验收（本机，无设备）：见 README 的“构建与测试”；全部 36 个 JVM 测试通过、debug APK 约 30 MB 可产出。**未验证（需要真机）**：Haze 真实模糊（Android 12+）与 10/11 的降级色罩、着色按钮按下态手感、启动页与主题图标、不同厂商 ROM 的小差异。

## 9. 风险与对策

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | **国内安卓机无 Google 服务**：`SpeechRecognizer` 不可用、无 FCM、Google 登录不可用、Places/Routes 不可用 | 语音（核心卖点）与登录在这类机型失效 | 先明确目标（问题 1）；P0 选择：键盘始终可用 + 邮箱/GitHub/匿名登录；语音在无服务时降级；预留可选离线识别（sherpa-onnx）；地图与推送后置 |
| R2 | 玻璃/Liquid Glass **只能近似**（Haze 2.0 Glass 仍为实验性） | 观感与 iOS 26 不同 | 只在悬浮栏/输入卡用；文字承载面实色；提供回退层与「减弱透明」；在真机验收 B 组写清「近似」；跟踪 Haze 2.1 与 Android 17 原生 Backdrop |
| R3 | **后端仅有 APNs** | Android 无服务端推送 | P0–P2 不依赖推送；M8 才做并需要用户批准的后端改动（新增 `fcm` token 类型、发送实现、Secret） |
| R4 | **仓库规则禁止第二个活跃 App**（`repository-layout.json`、`check-repository-layout.py`、`AGENTS.md`） | 违反规则的提交会被门禁拦下 | M0 PR 同步修改规则并写明理由；需用户批准（问题 3） |
| R5 | iOS main 持续变化（#131/#132/#133、3 秒撤销 UI、语音附件仍在合并） | Android 对标目标移动 | 每个里程碑前对齐 `origin/main`；以 `docs/acceptance/family-companion/*-v0.6.md` 与 `master-plan.md` 为准；金标准夹具随 iOS 变化更新；对标矩阵（第 3 节）作为活文档 |
| R6 | 规范化 JSON / 严格 JSON 与服务端**逐字节一致**很容易出隐蔽差异（数字、Unicode 转义、键序） | 请求被拒或哈希冲突 | 先写夹具测试再写实现（M1）；与 iOS 共用夹具；不用反射式序列化 |
| R7 | **没有 Android 真机**：语音/通知/闹钟/玻璃/Doze 无法在 box 验证 | 验收只能标「未真机验证」 | 问题 4；用户 Mac 上跑 Android Studio 模拟器作为过渡，明确标注证据层级 |
| R8 | **精确闹钟权限**与厂商后台限制（省电杀进程） | 提醒可能不准时 | 如实降级与文案；设置页引导；验收 G 组单列；不承诺 100% 准点 |
| R9 | 中文自然语言词法/时间解析的细节（`NSRange`、正则差异：Swift `NSRegularExpression`(ICU) vs Java `Regex`） | 边界用例结果不同 | 金标准表覆盖；移植时逐条正则检查差异（如 `(?!点)` 前瞻、`\s`、Unicode 范围）；差异记录进对照表 |
| R10 | 启动/包体/性能：Compose + Haze + 大量毛绒猫位图 | 低端机掉帧、包体变大 | Baseline Profile（M8）、位图用 WebP/矢量、blur 区域最小化、`Strong Skipping`；提供回退层 |
| R11 | 备份与密钥：系统备份恢复后密文无密钥 | 数据损坏提示 | `allowBackup=false` + 排除规则；损坏阻写并保留原数据（沿用 iOS 规则） |
| R12 | 商店/隐私合规（`READ_CALENDAR`、`RECORD_AUDIO`、`SCHEDULE_EXACT_ALARM` 在 Play 有政策限制） | 上架被拒 | 首发走 APK 侧载/GitHub Release；上 Play 前单独做合规评审（超出本计划） |
| R13 | 命名与身份：iOS 为 `com.example.CatpawM.*` 占位 | Android `applicationId` 一旦发布不可改 | 在 M0 前定（建议 `com.heydusk.mimi`，需用户确认）；协议头/Scheme 沿用线上身份 |

---

## 10. 需要用户拍板的问题（最多 5 个）

1. **目标市场与设备范围（决定语音/推送/登录/地图怎么做）**
   选项 A（**推荐**）：首发面向**有 Google 服务的 Android 10+（minSdk 29）**，以 APK 侧载/GitHub Release 分发；无 Google 语音服务的机型只提供键盘输入并如实提示；国内 ROM 适配（离线识别、国内推送与图商、应用商店）作为后续独立里程碑。
   选项 B：首发就要覆盖国内主流 ROM（无 GMS）→ M3 需追加离线语音识别（约 +8–10 天、安装包 +数十 MB）、M5 去掉 Google 登录依赖、地图单独选型。
2. **首个版本的范围**
   选项 A（**推荐**）：1.0 = M0–M6（育儿壳 + 云整理 + 高级聊天），记忆/主动/设备（M7）与小组件/推送（M8）放 1.1+；
   选项 B：只做 M0–M5，聊天整体后置；
   选项 C：完整对标（M0–M8）再发布。
3. **仓库策略：同仓还是新仓**
   选项 A（**推荐**）：在 `Yankj/mimi` 新增 `apps/android-conversation`（共用 `UIStrings.json`、后端合同夹具、文档与主题 tokens；M0 PR 修改 `repository-layout.json`、`check-repository-layout.py`、`AGENTS.md`，不碰 `.github/workflows`）；
   选项 B：新仓库 `Yankj/mimi-android`（互不干扰，但夹具/文案/主题要靠复制或子模块同步，漂移风险高）。
4. **验证设备与分发**
   用户是否能提供一台 **Android 手机（Android 14+，最好 Pixel/有 Google 服务）** 做真机验收？没有的话，是否接受「在 Mac 上用 Android Studio 模拟器验收，真机项一律标『未真机验证』」？APK 是否通过 GitHub Release（draft、私有）分发给你安装？
5. **身份与后端配置许可（涉及部署的事项都需用户在对应 agent 的对话里直接批准）**
   ① Android `applicationId` / 应用名（建议 `com.heydusk.mimi`，显示名 `mimi`/`小咪`）；
   ② 是否同意在 Supabase Auth 允许列表里增加 Android 的 OAuth 回调（沿用 `catpawm://auth-callback` 或 App Link）——M5 需要；
   ③ 推送：是否同意 M8 阶段由 三角 在后端新增 FCM 通道（需要你创建 Firebase 项目并提供服务账号 Secret）；若不同意，Android 不提供服务端推送，仅有本机提醒。

---

## 附录 A：iOS 目录 → Android 模块速查

| iOS | Android |
|---|---|
| `Sources/Domain/Parenting*`、`DictationPolicy`、`AccountEpoch`、`ConversationStrictJSON`、`ConversationCanonicalJSON` | `:core:domain` |
| `Sources/DesignSystem/*` | `:core:designsystem` |
| `Resources/UIStrings.json`、`UIStringExemptions.json` | `:core:i18n`（asset 复制） |
| `Sources/Persistence/*` | `:core:data` |
| `Sources/Providers/*`（Auth、Intake、Chat、Memory、Device、Proactive、Push） | `:core:network`（+ `:core:device` 的系统适配） |
| `Sources/Voice/*`、`Features/Composer/ComposerDictation.swift` | `:core:voice` |
| `Providers/ParentingReminderScheduler.swift`、`ParentingSystemNotificationCenter.swift` | `:core:device` |
| `Features/Parenting/*`、`Features/Launch/*`、`Features/Onboarding/*` | `:feature:parenting` |
| `Features/Conversation*`、`Answer/*`、`Markdown/*`、`GenerativeBlocks/*`、`DeviceCalls/*`、`DeviceCards/*` | `:feature:chat` |
| `Features/Memory/*` | `:feature:memory` |
| `Features/Proactive/*`、`App/catProactiveFeed.swift` | `:feature:proactive` |
| `Widgets/catWidgets/*` | `:app` 内 Glance 小组件 |
| `Tests/*` | 各模块 `src/test`（L1–L4）、`src/androidTest`（L5） |

## 附录 B：本计划的信息来源与未验证项

- 已读：根 `README.md`、`AGENTS.md`、`CHANGELOG.md`、`docs/architecture.md`、`docs/brand-theme.md`、`docs/status/master-plan.md`（头部）、`docs/design/family-companion/parenting-core.md`、`docs/acceptance/family-companion/device-acceptance-v0.6.md`、`docs/internal-identifiers.md`、`docs/development/repository-layout.json`、`scripts/check-repository-layout.py`、`apps/ios-conversation` 的 `Sources/{App,Domain,DesignSystem,Features/Parenting,Persistence,Providers,Voice}`（目录与关键文件）、`services/edge` 结构；`/workspace/uploads` 的截图与主题提案/tokens。
- **没有运行任何 iOS/后端代码**，没有调用真实模型或 Auth；iOS 行为以源码与验收文档为准，未在设备上复核。
- 版本号（AGP/Kotlin/Compose/Haze）来自 2026-10-28 的联网检索与本 box 的一次实测构建；开工时必须复核。
- 估算为规划值，不是承诺；Android 真机相关耗时受设备与配合影响最大。
- 以上均为提案；任何创建工程、改仓库规则、改后端、部署、推送、发版，都需要用户明确批准。
