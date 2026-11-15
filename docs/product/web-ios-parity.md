# iOS / Web / 小程序 / Android 对照

记录日期：2026-11-15。代码基线是当时的 `origin/main` `080a5181`（「抽出 mimi-core 并搭微信小程序骨架」）。本文件随 W02 草稿一起提交，**还没进 main**。单元格里的状态说的是 main 上已经有的行为；某一格如果只存在于未合并分支或本草稿，状态仍按 main 写，并在同一格写明草稿位置。

这不是验收通过声明，也不授权合并、部署或迁移。产品方向仍以 [主计划](../status/master-plan.md) 为准。[能力全景](capability-panorama.md) 写于 2026-10-29，里面的 Android / iOS 进度已经落后于 `080a5181`，不能拿来当本表的现状。

状态只用这几个词：已有、部分、缺、mock、平台不做、待用户定。

Android 单独说明：`080a5181` 的 `apps/` 没有 `android-conversation`，[仓库布局](../development/repository-layout.json) 也没有声明 Android 根。草稿 [#152](https://github.com/Yankj/mimi/pull/152) 的底栏在 `cursor/android-chrome-ix-v2-5cbb`，base 是 `feat/android-align-147-148`，不是 main。那条分支上有 `apps/android-conversation`，其 README 自述到 M4（本机加密账本、确认后写入、本机 AlarmManager 通知）。下面 Android 列的状态一律是 **main 上缺**；README 声称已有的，写在证据里，不改状态。

## 认证

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| Google 登录 | 已有。`apps/ios-conversation/Sources/Features/NativeOAuthViews.swift` | 已有。`apps/web/app/api/auth/start/route.ts`，[#155](https://github.com/Yankj/mimi/pull/155) | 平台不做。[#157](https://github.com/Yankj/mimi/issues/157) 写明不能用 Google | 缺。main 无目录 |
| GitHub 登录 | 已有。同上 Native OAuth | 已有。同上 start 路由 | 平台不做。#157 | 缺。main 无目录。未合并分支 README 把 Google/GitHub 放在未做的 M5 |
| 微信登录 | 平台不做。原生壳没有微信登录 | 平台不做 | mock。`apps/miniprogram/miniprogram/pages/login/login.wxml` 的按钮只建模拟会话。真实 `wx.login` 绑定待用户定（#157） | 缺。main 无目录 |
| 匿名「开始使用」 | 已有。`NativeOAuthViews.swift` 把「开始使用」当作体验入口 | 平台不做。`apps/web/app/api/auth/start/route.ts` 拒绝体验登录；#155 的产品决定 | 平台不做。#157 不接受匿名身份 | 缺。main 无目录。未合并分支 README 写的是本机匿名体验账号，未进 main |
| DEBUG 邮箱密码与钥匙串重试 | 部分。`apps/ios-conversation/Sources/Providers/NativeAuthHTTPRemote.swift` 有 password grant；#155 写明这是 DEBUG。钥匙串重试见 `Sources/App/NativeAuthApplicationModel.swift` | 平台不做。#155 A05 / A06 | 缺。登录页没有这条路径 | 缺。main 无目录 |
| 约 15 分钟无操作后重新确认登录 | 缺。在 `apps/ios-conversation` 里没有找到「重新确认登录」或同等的 15 分钟空闲锁。#156 把它写成对话基线，和代码不一致，见文末 | 已有。`apps/web/lib/lock.ts` 的 `IDLE_MS`，锁定页在 `apps/web/components/app-shell.tsx` | 已有。`apps/miniprogram/miniprogram/utils/idle-watch.ts` 与 `pages/lock/`。对话只在内存里 | 缺。main 无目录 |
| 退出并清掉本机会话 | 已有。`NativeAuthApplicationModel.swift` 的退出路径；育儿账本密钥与普通会话分开，见 `ParentingWorkspaceStore.swift` | 已有。`apps/web/lib/browser-wipe.ts` | 已有。`apps/miniprogram/README.md`：退出清空模拟会话和对话草稿 | 缺。main 无目录。未合并分支 README 写退出会销毁本机账本，未进 main |

## 对话

#155 自称对话 C01–C24、C27、C28 已做，C25、C26、C29、C30 是设备能力、没做。仓库里没有单独的 C 编号文件。下表按代码里能对上的行为写，不把编号当成已复核清单。

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 发送与 SSE 流式回复 | 已有。`apps/ios-conversation/Sources/Providers/NativeChatHTTPBackend.swift` | 已有。`apps/web/components/conversation-workspace.tsx`，`/api/edge` 把 abort signal 传给上游 | mock。`apps/miniprogram/miniprogram/utils/mock-chat.ts` 在本机造增量，不发到线上 | 缺。main 无目录。未合并分支 README 未写办公对话；全景里的 Android M6 标为不做 |
| 本机加密对话历史 | 已有。`apps/ios-conversation/Sources/Features/ConversationSessionService.swift` 按会话 id 读写本机历史；#155 写明 edge 没有对话列表 | 已有。`apps/web/lib/chat-vault.ts`（IndexedDB、AES-GCM） | 待用户定。#157：没有 IndexedDB，加密方案未选；这一步对话只在内存 | 缺。main 无目录 |
| 侧栏搜索与每页 40 条 | 部分。侧栏在 `ConversationFirstView.swift`。源码里没有与 `PAGE_SIZE = 40` 对应的分页常量。#155 把「每页 40 条」写成已对齐，代码对不上 | 已有。`packages/mimi-core/src/wire.ts` 的 `PAGE_SIZE` 与 `nextVisibleCount` | 缺。模拟对话没有侧栏分页 | 缺。main 无目录 |
| 发送许可、邀请码、记忆命令 | 已有。`Sources/Features/Settings/FeatureEnrollment.swift`；记忆在 `Sources/Features/Memory/` | 已有。`conversation-workspace.tsx` 接许可、邀请码和记忆 URL | 缺。请求头构造带审批合同名，界面没有这些卡片 | 缺。main 无目录 |
| JPEG/PNG 签名上传（点选文件） | 已有。`Sources/Features/Composer/ComposerAttachPopoverView.swift` | 已有。`conversation-workspace.tsx` 与 `uploadUrlAllowed`。拖拽不在 main，见 W08 | 缺 | 缺。main 无目录 |
| 审批、计划、成果卡片 | 已有。`ConversationTaskPlanView.swift`、`ConversationGenerativeUIView.swift` | 部分。`conversation-workspace.tsx` 接了审批 / 计划 / 成果的 URL。#155 自称整组已做，#156 写明还没逐条对着 iOS 界面复核 | 缺。只有模拟文本流 | 缺。main 无目录 |

## 今天 / 成长 / 我们家

iOS 账本是设备本地 AES-GCM，不是 SwiftData 表名本身那一层加密包装：`apps/ios-conversation/Sources/Persistence/ParentingWorkspaceStore.swift`。界面在 `ParentingHomeView.swift`、`ParentingGrowthHomeView.swift`、`ParentingFamilyViews.swift`。

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 三个 Tab 的外壳和空态 | 已有。上述三个界面 | 已有。`apps/web/components/app-shell.tsx` | 已有。`apps/miniprogram/miniprogram/pages/` 下的 today、growth、family | 缺。main 无目录。未合并分支有 `feature/parenting`，未进 main |
| 添加孩子并写入 | 已有。`ParentingWorkspaceStore.swift` | 缺。添加孩子只弹出说明，不写入。`packages/mimi-core/src/copy.ts` 的 `addChildBlocked` | 缺。`apps/miniprogram/miniprogram/utils/ledger.ts` 的 `addChild` 直接抛错 | 缺。main 无目录。未合并分支 README 称点保存才写入，未进 main |
| 今天的安排：主卡、接下来、全部安排、完成与撤销 | 已有。`ParentingHomeView.swift` | 缺。今天页只有空态文案 | 缺。`pages/today` 只有空态 | 缺。main 无目录。未合并分支 README 称有这些旅程，未进 main |
| 成长：目标、时间线、观察 | 已有。`ParentingGrowthHomeView.swift` | 缺。时间线和目标页只有空态 | 缺。`pages/timeline`、`pages/goals` 只有空态 | 缺。main 无目录。未合并分支 README 称有，未进 main |
| 我们家档案，以及回顾提示 / 重新读取 / 清除 | 已有。`ParentingFamilyViews.swift` | 部分。`app-shell.tsx` 的设置页在，回顾提示、重新读取、清除全部是禁用 | 部分。`pages/settings/settings.wxml` 同样把回顾提示和清除做成不能改 | 缺。main 无目录。未合并分支 README 称设置里可以清除，未进 main |

## 原位整理

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 说一件事，确认后写入账本 | 已有。`Sources/Domain/ParentingNaturalInput.swift`，`ParentingNaturalInputModel.swift` | 缺。对话走 edge，结果不进今天 / 目标 / 安排。#156 | 缺。#157 明确不做未同步账本 | 缺。main 无目录。未合并分支 README 称本机确认后写入，云端「让小咪整理」仍是 M5，未进 main |

## 设备能力

#156 把 Live Activity、小组件、Siri、EventKit / 通讯录、仅设备上的语音识别、本机提醒排程、保存到照片列为 Web 难以等价。#157 对小程序的对应降级写的是待用户定，不能写成已经决定不做。

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| Live Activity | 已有。`Sources/Shared/catWorkAttributes.swift` | 平台不做。#156，`apps/web/README.md` | 待用户定。#157 | 缺。main 无目录 |
| 小组件 | 已有。`apps/ios-conversation/Widgets/catWidgets/catHomeWidgets.swift` | 平台不做。同上 | 待用户定。#157 | 缺。main 无目录 |
| Siri / 快捷指令 | 部分。`Sources/App/catApp.swift` 有 `AppIntent`。没有把整套捷径产品对完 | 平台不做。#156 | 待用户定。#157 | 缺。main 无目录 |
| 日历、提醒事项、通讯录、电话、邮件、地图 | 已有。`Sources/Features/DeviceCalls/DeviceToolRouter.swift`（EventKit、`CNContactStore`、电话邮件地图等），`Sources/Providers/NativeCalendarEffectExecutor.swift` | 平台不做。main 的 `packages/mimi-core` 测试断言不发送 `X-Catpaw-Device-Tools`。电话 / 邮件 / 地图 / `.ics` 在草稿 [#159](https://github.com/Yankj/mimi/pull/159)（W06），未进 main | 待用户定。#157。当前 `mock-chat.ts` 不带设备头 | 缺。main 无目录 |
| 保存到照片 | 已有。`Sources/Features/MessageActions/ImageViewer.swift` 调用 `PHPhotoLibrary` | 平台不做。#155 / #156。main 没有相册写入 | 待用户定。#157 | 缺。main 无目录 |
| 拖到对话区上传（W08） | 部分。iOS 是相册点选，不是桌面拖拽。`ComposerAttachPopoverView.swift` | 缺。main 已有点选上传（上一节），拖拽只在草稿 #159，未进 main | 缺 | 缺。main 无目录 |

## 提醒与通知（含 W02）

iOS 育儿提醒的规则在 `Sources/Domain/ParentingReminderPlan.swift`：只提醒已确认且未完成的安排，每天最多 2 条，21:00–08:00 不提醒。调度器 `Sources/Providers/ParentingReminderScheduler.swift` 写明不经过服务器、不用 APNs、不写日历。安排卡上的一行字在 `ParentingHomeView.swift` 的 `reminderLine`。提醒子页是开关、提前量和锁屏隐藏，不是一份标题叫「今日提醒」的列表（`ParentingFamilyViews.swift`）。

`services/edge/supabase/migrations/20261023200000_family_v1_core.sql` 里的 `family.reminders`，以及 `services/edge/supabase/functions/_shared/api.mjs` 的 `/catpaw/family/v1/`，iOS 客户端没有调用。不能把这张表当成手机上的今日提醒。

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 本机系统通知排程 | 已有。`ParentingReminderScheduler.swift` | 平台不做。不调用 Notification API，不申请权限，不做系统横幅。`apps/web/README.md` | 待用户定。#157 备选是微信订阅消息。`pages/reminders` 现在只写「这一步不发提醒」 | 缺。main 无目录。未合并分支 README 称 M4 用 AlarmManager 发本机通知，未进 main |
| 页内「今日提醒」列表（W02） | 部分。没有这个标题的列表。已确认安排上有 `reminderLine`，提醒页只显示已排条数 | 部分。本草稿在「今天」和「我们家 → 提醒」放页内列表（`apps/web/lib/today-reminders.ts`）。共享来源是空的，列表说明读不到手机上的提醒，也不另记一份。`080a5181` 上还没有这段 | 缺。`pages/reminders/reminders.wxml` 只有说明，没有列表 | 缺。main 无目录。未合并分支做的是系统通知，不是这份页内列表 |
| 到点提醒开关、提前量、锁屏隐藏内容 | 已有。`ParentingRemindersView` | 平台不做。这些开关管的是系统通知。网页不摆一套不能排程的开关 | 待用户定。#157 | 缺。main 无目录。未合并分支 README 称提醒页有这些开关，未进 main |

## 语音与朗读

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 朗读 | 已有。`Sources/Voice/NativeVoiceController.swift` 使用 `AVSpeechSynthesizer` | 已有。`conversation-workspace.tsx` 使用 `speechSynthesis`。#156 允许继续用浏览器朗读 | 待用户定。#157 | 缺。main 无目录。未合并分支 README 写了语音识别，没有把朗读对完 |
| 听写 / 按住说话（W04） | 已有。`Sources/Voice/AnalyzerDictationEngine.swift`，`Sources/Features/Composer/ComposerDictation.swift` | 缺。main 没有听写。草稿 #159 是可选的点按听写，不是按住说，未进 main | 待用户定。#157 | 缺。main 无目录。未合并分支 README 称有点按和按住说，未进 main |

## 卡通电子稿

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 画作电子稿 | 已有。`Sources/Features/CartoonECopy/CartoonECopyViews.swift`，成长页入口在 `ParentingGrowthHomeView.swift`。#156 提到的 [#154](https://github.com/Yankj/mimi/pull/154) 代码已经在 `080a5181` | 缺。#156 默认不进 Web | 待用户定。#157 | 缺。main 无目录 |

## 家庭同步

| 功能 | iOS | Web | 小程序 | Android |
| --- | --- | --- | --- | --- |
| 家庭账本同步（里程碑 M5） | 缺。`ParentingHomeView.swift` 的存储边界写着尚未云同步。客户端不调用 `/catpaw/family/v1` | 缺。`docs/development/repository-layout.json` 写明要等 `family_v1` 再定。网页不保存孩子资料 | 缺。`apps/miniprogram/miniprogram/utils/ledger.ts` 拒绝写入 | 缺。main 无目录。未合并分支 README 把账号和云整理放在未做的 M5 |

edge 源码里已经有 family 命令和 `family.projection` 的 SQL。那是维护树里的合同，不是 iOS 正在用的账本，也不是已经上线的同步。本表不把它算成任一端的「已有」。

## 草稿里有、main 上没有

- [#159](https://github.com/Yankj/mimi/pull/159)（base `main`，草稿）：W04 可选听写、W06 电话邮件地图与 `.ics`、W08 拖拽上传。未进 main。
- [#152](https://github.com/Yankj/mimi/pull/152)（base `feat/android-align-147-148`，草稿）：Android 一行双态底栏。不合 main。底栏之下的 Android 育儿代码也只在那条未合并分支上。
- 本草稿：W02 页内列表。合并前 main 没有它。

## 和 issue / PR 正文不一致的地方

- #156 写「#155 自称 C01–C24、C27、C28 已做」，这一点和 #155 正文一致。#156 后面又写「补核 C01–C30 里自称已做的项」。#155 正文把 C25、C26、C29、C30 标成设备能力、没做。main 上的 Web 也不发送 `X-Catpaw-Device-Tools`（`packages/mimi-core` 的 wire 测试）。C01–C30 并不是一整包都自称做完。
- #155 的 C 项没有逐条对着 iOS 对话界面复核。本表只把代码里能对上的对话行为标成已有或是部分，不把 C01–C28 当成已经验收。
- #155 写「每页 40 条」。Web 的 `PAGE_SIZE` 确实是 40。iOS `ConversationFirstView.swift` 里没有找到同一个分页常量。
- `packages/mimi-core/src/copy.ts` 的文件头写文案逐字来自 iOS。 「重新确认登录」只出现在 Web / 小程序文案和锁定页，没有出现在 `apps/ios-conversation`。15 分钟空闲锁是 Web 和小程序有、iOS 源码里没找到的行为。
- #156 把 Siri 列进 iOS 设备能力。代码里能对上的是 `catApp.swift` 的一个 `AppIntent`，所以本表写部分，不写已有整套 Siri。
- [capability-panorama.md](capability-panorama.md) 仍写 Android 本地 `feat/android-m5` 未推远程、iOS main 停在 #145。以 `080a5181` 为准：Web 和小程序骨架已经在 main，Android 目录仍然不在 main。

## 还没定、因此挡住后续的事

- 家庭同步没定。它挡住 Web 和小程序上可写的今天 / 成长 / 我们家账本。不能先在浏览器或小程序里各记一份和手机对不上的账本。W02 因此只做页内空列表，不读 `family.reminders`，也不写本地账本。
- 小程序登录、正式 AppID、已备案并配进后台白名单的 HTTPS 域名，都还没定（#157）。定之前不接真实后端。
- Web 预览部署、Supabase 重定向允许列表里的 `/auth/callback`、测试账号，#156 列在第二批，还没做。本草稿不部署，也不改线上配置。
- Android 进不进 main 还没定。#152 保持草稿，而且 base 不是 main。

## 各端计数

只数上面功能表单元格开头的状态词，共 32 行、128 格。证据句子里再次出现的状态词不重复计数。Android 32 格全是「缺」，因为 main 上没有 Android 目录；未合并分支 README 里声称已有的能力没有算进「已有」。Web 的 W02 计为「部分」，那一格属于本草稿，`080a5181` 上还没有。

| 状态 | iOS | Web | 小程序 | Android | 合计 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 已有 | 24 | 11 | 3 | 0 | 38 |
| 部分 | 5 | 3 | 1 | 0 | 9 |
| 缺 | 2 | 8 | 12 | 32 | 54 |
| mock | 0 | 0 | 2 | 0 | 2 |
| 平台不做 | 1 | 10 | 3 | 0 | 14 |
| 待用户定 | 0 | 0 | 11 | 0 | 11 |
| 合计 | 32 | 32 | 32 | 32 | 128 |
