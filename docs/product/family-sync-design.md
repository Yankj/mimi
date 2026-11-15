# 家庭同步设计草案

状态：**草案**。日期：2026-11-15。用户已决定把家庭同步打开。本文只记录方案，不改 Swift、不改 Edge、不改迁移、不部署、不跑库、不改 Supabase 配置。

合并本文、在任何数据库执行 SQL、打开 `CATPAW_FAMILY_ENABLED`、发 iOS 包，都要另一次明确批准。实时进度仍只写在[主计划](../status/master-plan.md)。本文不把 M5 标成已开始或已通过。

对照入口：[issue #156](https://github.com/Yankj/mimi/issues/156)。Web 与小程序在同步获批并落地之前，继续只显示外壳，不另做一份浏览器本地账本。

本草案只核对仓库里的迁移文件和客户端代码，没有连接生产项目。各 `family_v1_*.sql` 文件头写明 `WRITTEN ONLY — NOT APPLIED`。生产库是否曾被手工执行，这里没有远程核对。

## 1. 现状与证据

### 1.1 iOS 育儿账本在本机

权威对象是 `ParentingWorkspace`（`apps/ios-conversation/Sources/Domain/ParentingWorkspace.swift`）。一份账本里有：

| 本机对象 | 字段（代码里真实存在的） | 写入方式 |
|---|---|---|
| `ParentingChild` | `id`、`name`（≤40）、`createdAt` | `addChild` / `renameChild` / `deleteChild` |
| `ParentingGoal` | `id`、`childID`、`title`（≤120）、`createdAt` | `addGoal` / `deleteGoal` |
| `ParentingArrangement` | `id`、`childID`、`title`（≤160）、`dueAt`、`goalID` 可空、`state`、`sourceText`（≤8000）、`createdAt`、`updatedAt` | 草稿、修改、确认、取消、完成、重开、删除 |
| `ParentingRecord` | `id`、`childID`、`goalID` 可空、`arrangementID` 可空、`kind`、`text`（≤8000）、`sourceText`、时间 | 观察或完成记录；可改、可删 |
| `editHistory` | 安排修改前的整份快照，供撤销 | 只随调整命令写入 |
| `suggestionsEnabled` | 回顾提示开关，默认关 | `setSuggestionsEnabled` |
| `profile` | 家庭 / 孩子画像。种类含睡眠、特点、需留意等 | 候选事实经确认后才可用 |

安排状态是 `draft` / `planned` / `cancelled` / `completed`。记录种类是 `observation` / `completion`。完成一条已确认安排时，会同时把安排标成 `completed` 并追加一条完成记录。删除孩子会从这份账本里直接移除该孩子及其目标、安排、记录、调整历史和孩子范围的画像（硬删除，不是墓碑）。`deleteAllRecords` 清空孩子及附属内容，**保留**回顾提示开关。

持久化在 `ParentingWorkspaceStore`（`apps/ios-conversation/Sources/Persistence/ParentingWorkspaceStore.swift`）：

- 整份账本加上操作回执打成一个信封，用 AES-GCM 加密后，放进按账号分区的 SwiftData preference。键名前缀 `parenting_workspace:v1:`。
- 密钥在钥匙串服务 `catpaw.parenting.v1`，`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`。注释写明：普通 L2 退出登录**不会**毁掉这把育儿账本钥匙，因此同一台手机重新登录仍能解开。
- 写入用账本 `revision` 做整本 CAS，并用 `operationID` 去重。
- iOS 源码里没有 `/catpaw/family/v1` 调用。原位整理走的是 `/catpaw/parenting-intake/v1/...`（`NativeChatHTTPBackend.swift`），确认之前不写入账本。

我们家文案写明当前边界（`ParentingHomeView.swift`）：「仅在此设备加密保存 · 尚未云同步」。设置页写明更换设备不会自动带回这些记录。

### 1.2 留在账本外面的本机数据

这些都不在加密账本信封里：

| 数据 | 位置 | 行为 |
|---|---|---|
| 到点提醒开关、提前量、锁屏隐藏内容 | `UserDefaults` 键 `mimi.parenting.reminders.v1.<账号>`（`ParentingReminderPlan.swift`、`ParentingReminderScheduler.swift`） | 默认关。锁屏隐藏时通知正文是「有一件孩子的安排即将开始，打开小咪查看。」，不含昵称和事项标题 |
| 本机已排程的提醒时刻 | `mimi.parenting.reminders.memory.v1.<账号>` | 只给这台设备的本地通知对账 |
| 上次周复盘时间 | `mimi.parenting.weeklyReview.lastAt.v1.<账号>` | 退出登录时清掉 |
| 孩子照片 | `Application Support/mimi-child-avatars/<孩子 id>.jpg`（`ParentingChildAvatarStore.swift`） | 文件保护、排除备份、不进账本字段。退出登录删掉整个目录。上限 512 KiB |
| 未确认的原位草稿 | `ParentingIntakePersistence` | 家长确认进账本之前只在本机 |

本地提醒计划（`ParentingReminderPlanner`）只给 `state == planned` 且未到期的安排排程。安静时段 21:00–08:00，每个本地日最多 2 条，系统待发上限按 48 条保守使用。排程发生在这台 iPhone 上，不经过 `family.reminders`。

设置里的「导出当前 workspace 本地数据」（`SettingsView.swift` → `Repository.exportProfile`）导出的是 workspace 的仓库 JSON。育儿账本在其中是 `parenting-sealed-v1:` 加上密文，不是一份可读的孩子清单。

### 1.3 仓库里已有、但未接到 iOS 账本的服务端结构

迁移在 `services/edge/supabase/migrations/`，均标注未应用到生产。`config.toml` 的 `project_id` 是 `catpaw-local`，没有把 schema `family` 写进 Data API 暴露列表。`CATPAW_FAMILY_ENABLED` 默认 `off`（`services/edge/supabase/functions/_shared/config.mjs`）。开关关闭时 `/catpaw/family/v1/` 返回 404（`api.mjs`）。

`20261023200000_family_v1_core.sql` 创建 schema `family` 和这些表：

| 已有表 | 代码里的形状 | 和 iOS 账本的关系 |
|---|---|---|
| `family.households` | 名称、IANA 时区（默认 `Asia/Shanghai`）、`owner_user_id`、`active`/`archived`、`revision`、`seq` | 可作家庭容器。后来的 `20261023400000` 改成「每个 owner 至多一个 active 家庭」 |
| `family.members` | `display_name` ≤40、`member_kind`、可选 `birth_year`、学校/班级/老师/地点标签、`notes` ≤500、`active`/`deleted` | 孩子昵称能对上。生日、学校等列 iOS 孩子模型没有，添加孩子文案写「不用填写生日和健康信息」 |
| `family.plans` | 标题 ≤120、`domain`、`draft/active/paused/completed/archived/deleted` | **不是** iOS 的成长目标。iOS 目标没有 domain、暂停、归档 |
| `family.items` | 状态只有 `needs_confirmation` / `scheduled` / `cancelled` / `deleted`；标题 ≤120；`local_date`+`local_time`；`notes` ≤500；`bring` | **不是** iOS 安排。没有 `completed`，没有 `goalID`，标题和原文长度都更短，时间是本地日+钟点而不是绝对 `dueAt` |
| `family.observations` | 正文 ≤500、`observed_on`、`recorded`/`deleted` | **不是** iOS 成长记录。没有完成种类、没有目标、没有安排关联，长度 500 对 8000 |
| `family.proposals` | 只指向 `item` | 服务端确认卡，不是 iOS 的 `editHistory` |
| `family.reminders` | `deadline_approaching` / `bring_item` / `conflict`，`fire_at`，`scheduled/cancelled/fired`，由 `claim_due_reminders` 领取 | **不是** iOS 的提醒设置，也不是锁屏隐藏开关。这是服务端待发队列 |
| `family.receipts` / `family.outbox` / `family.command_types` | 幂等回执与事件 | 同步命令可以沿用这套事务写法 |
| `family.memories` 及 `family.memory_sources` | v0.4 之后的家庭事实记忆 | 给模型回忆用，不是成长时间线 |
| `family.household_grants` | `(household_id, user_id)`、角色、`active`/`revoked`、可选 `member_scope` | 见下节。没有单独的 `memberships` 表 |

随后迁移还为 `family.households` 增加了 `memory_paused`、`reminders_paused`、`memory_generation`、`setting_sequence`、`membership_revision`。`member_kind` 放宽为 `child` / `adult` / `other`。这里的 adult **成员行**是记忆主体，不是登录账号。登录的大人是 `households.owner_user_id` 或 `household_grants.user_id`。

RLS（核心迁移）：默认拒绝；`authenticated` 只对 **owner** 有 `SELECT`；写入只经 `family.apply_command`（`SECURITY DEFINER`，仅 `service_role` 可执行）。函数内部再次按 owner 过滤。`20261023400100` 的 `family.reader()` 允许 active grant 的 viewer 读记忆，但 grant 的命令在 `20261023400300` 里被写成「受邀者的每条命令都拒绝」。紧接着 `20261023400400` **删掉** `grant.upsert` 和 `grant.revoke`，因此当前命令表无法创建授权。`household_grants` 会一直是空的。该表对 `authenticated` 没有 SELECT 策略（权限被 revoke）。

`households.owner_user_id` 和 `household_grants.user_id` 都 `references auth.users(id) on delete cascade`。账号删除会级联删掉此人**拥有**的家庭及其子行；照护者账号删除只会级联删掉自己的 grant 行。这是现有外键，不是本草案新增的行为。这些迁移尚未应用到生产，所以上述级联也尚未在生产生效。

冻结协议 `docs/design/family-companion/api-v1.md`（2026-10-23）写的是单成人、不提供第二位照护者邀请，事项状态没有「已完成」。`docs/architecture/family-companion-technical-plan.md` 里的 `memberships` 表在迁移里不存在，对应物是 `family.household_grants`。`docs/design/family-companion/parenting-milestones.md` 的 M5 已经写明：要先冻结本机到云的映射，并补上目标与完成的云合同。本草案按这个缺口设计，不把 `family.items` 当成已经能装下 iOS 安排。

家庭表上没有应用层字段加密（迁移里没有 `pgcrypto`）。iOS 的 AES-GCM 只保护手机上的信封。

### 1.4 Web 与小程序今天读到什么

两边都没有账本存储。

Web（`apps/web/components/app-shell.tsx`，文案在 `packages/mimi-core/src/copy.ts`）：

- 今天 / 成长 / 我们家是导航、空态和入口。「添加孩子」弹出说明，不写入。
- 我们家可以打开提醒页和设置页。提醒页正文是「这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。」
- 设置里的回顾提示、重新读取、清除全部记录在第一批是禁用的（见 #156）。
- 对话正文在本机 IndexedDB，AES-GCM，密钥不可导出。服务端没有对话列表。
- 登录只有 Google / GitHub PKCE。访问令牌在 HttpOnly cookie。`/api/edge` 由服务端代附 Authorization。

小程序（`apps/miniprogram`，`miniprogram/utils/ledger.ts`）：

- `createLocalLedger().addChild` 直接抛错，内存数组保持为空。
- 今天 / 成长 / 我们家使用小程序自己的说明：「孩子的资料只存在 iOS 版小咪里」。提醒页正文是「小程序这一步不发提醒，也不会往手机日历里加安排。」（`apps/miniprogram/miniprogram/utils/mp-copy.ts`）。与 Web 共用的句子来自 `packages/mimi-core`，提到网页或浏览器的句子在小程序里被替换。
- 登录和对话是模拟。`request` 默认不发 HTTP，并拒绝 `*.supabase.co`。
- 真实微信登录、AppID、备案域名都还没定（`apps/miniprogram/README.md`）。

`docs/development/repository-layout.json` 仍把两端的账本同步标成未获批前不做。本草案获批之前，这句继续有效。

仓库里没有名为 W02 的文件或符号。本决定里的提醒列表，就是 Web `ParentingShell` 的 reminders 页和小程序 `pages/reminders`。它将读取已同步的已确认安排，见第 5 节。

### 1.5 网页 15 分钟锁定（现状）

`apps/web/lib/lock.ts` 的 `IDLE_MS` 是 15 分钟。`app-shell.tsx` 的 `PrivacyGate`：

- 页面隐藏时先遮住内容；系统开启「减少动态效果」时不做模糊动画。
- 空闲或已锁定后，外壳 `inert`，盖上「重新确认登录」。继续要走原来的 Google 或 GitHub。
- 锁定不会自己到期。会话 cookie 可保留到同一账户重新登录或退出（`SESSION_MAX_AGE_SEC` 为 14 天），但锁定期间 `/api/edge` 返回 401。
- 缺少或非法的 `seenAt` 视为已空闲。点击、按键、滚动、触摸、回到前台，最多每 5 分钟向 `/api/session` 汇报一次。已经锁定的会话不会被这次汇报解开。令牌刷新不刷新活动时间。
- 同一账户解锁保留这台浏览器的加密对话；另一账户解锁会删掉本地库和密钥。

小程序 README 写的是大约 15 分钟无操作，在 `onHide` / `onShow` 里锁定，退出会清模拟会话。那是骨架行为，还没有账本可读。

## 2. 数据模型提案

原则：同步的是家长已经确认进账本的育儿记录。沿用 schema `family`、回执和 `apply_command` 这一种写入方式。不建第二套浏览器账本，也不把 iOS 对象硬塞进长度和状态对不上的旧表。

下面「已有」表示迁移文件里已经 `CREATE TABLE`。「提议」表示仓库里还没有，要等单独的 SQL PR，并且先只在预览库执行。

### 2.1 家庭

使用已有 `family.households`。一个账号至多拥有一个 `active` 家庭（已有部分唯一索引 `households_one_active_per_owner`）。名称默认「我们家」，时区在打开同步时取这台设备的 IANA 时区，缺省 `Asia/Shanghai`。归档后的家庭不复活；再次建立会是新 id（`20261023400000` 已有这个规则）。

不新增家庭表。

### 2.2 成员与角色

| 角色 | 身份 | 存储 | 本期 |
|---|---|---|---|
| 创建者（家长） | 登录用户 | `family.households.owner_user_id` | 第一期就有。一个人一个 active 家庭 |
| 受邀照护者 | 另一个已有的小咪账号 | 已有 `family.household_grants.role = caregiver` | 模型按此设计。邀请命令当前被删掉，第一期不开放邀请 UI |
| 只读家人 | 已有账号 | 已有 `role = viewer` | 同上，晚于照护者 |
| 孩子 | 没有 Auth 用户 | 已有 `family.members`，`member_kind = child` | 只同步昵称。`birth_year`、学校、班级、老师、地点、notes 保持空，界面不收集 |

`family.members` 里的 `adult` / `other` 继续只服务于既有记忆主体，不拿来表示能登录的照护者。

邀请在用户还没有 `auth.users` 行时，不能插入 `household_grants`（`user_id` 非空且外键到 `auth.users`）。提议新表 `family.invites`：只存邀请码的哈希、角色、过期时间、创建者、使用时间。不明文存邀请码，不写进日志。默认 48 小时、单次使用。受邀人必须已经能用 Google / GitHub 登录。小程序的微信绑定不走这张表，等登录方案另批。

提议把 grant 状态从现在的 `active` / `revoked` 扩展出产品流程需要的「待接受」——待接受行放在 `family.invites`，接受成功后才写 `household_grants.status = active`。不把待接受塞进现有 grant 行。

### 2.3 孩子、目标、安排、成长记录

孩子行复用 `family.members`。同步时保留 iOS 的 UUID。删除改成已有的墓碑：`status = deleted`，`deleted_at` 有值，同一 id 不能复活（与 `cmd_child_delete` 一致）。iOS 投影里不再显示该行。晚到的、仍指向这个孩子的离线创建会被拒绝。

目标、安排、成长记录用**提议的新表**，因为已有 `plans` / `items` / `observations` 装不下，硬映射会截断标题、丢掉完成状态和 8000 字原文。旧表保留给已经写好的 `catpaw.family.v1` 文字闭环，第一期账本同步不往这三张表写 iOS 数据。

提议（均含 `household_id`、从 1 起的 `revision`、`created_at`、`updated_at`、软删除列）：

| 提议表 | 对应 iOS | 要点 |
|---|---|---|
| `family.goals` | `ParentingGoal` | `member_id`、`title` ≤120、`active` / `deleted`。删除目标时安排和记录上的 `goal_id` 置空，与 iOS `deleteGoal` 一致 |
| `family.arrangements` | `ParentingArrangement` | `member_id`、`goal_id` 可空、`title` ≤160、`due_at timestamptz`、`source_text` ≤8000、状态 `draft` / `planned` / `cancelled` / `completed` / `deleted` |
| `family.growth_records` | `ParentingRecord` | `kind` = `observation` 或 `completion`、`text` ≤8000、`source_text` ≤8000、`goal_id` 可空、`arrangement_id` 可空、`recorded` / `deleted` |

`due_at` 用绝对时间，另存家庭时区，避免把「下午三点」编成 UTC 零点。今天视图用家庭时区把 `due_at` 折成本地日。这与 `family.items` 的 `local_date` + `local_time` 不同，所以不复用那张表。

完成安排：一条命令同时把安排改为 `completed`，并插入 `kind = completion` 的成长记录。重开则按 iOS 规则处理那条完成记录（家长没写过的自动完成记录删除，家长写过的改成观察并解开安排关联）。

子表用 `(household_id, parent_id)` 复合外键，避免把别的家庭的孩子或目标挂过来。写入仍进同一个 `apply_command` 事务：领域行、revision、回执、outbox 一起提交。

`family.plans`、`family.items`、`family.observations`、`family.memories`、`family.reminders` 保持原合同，本提案不改它们的状态机。

### 2.4 附件

第一期没有云端附件。孩子照片继续只在 `mimi-child-avatars`。已有的聊天附件桶（迁移 `20261015140000_chat_attachments_bucket.sql`）只服务对话，不拿来存孩子照片。

不提议在第一期增加 `family.attachments`。

### 2.5 同步什么、留在本机什么

**打开同步并经家长确认后上传：**

- 孩子昵称（`family.members`）
- 目标、安排（含原文和状态）、观察与完成记录
- 回顾提示开关。它今天在加密账本里，不含孩子内容。提议放在新表 `family.sync_enrollments.review_hints`，让网页设置能读写同一个开关

**留在本机，不上传：**

- 钥匙串里的 `catpaw.parenting.v1` 密钥
- 锁屏隐藏开关、提醒提前量、提醒总开关（`ParentingReminderSettings`）
- 本机通知标识、已排程时刻（reminder memory）
- 周复盘「上次看过」的时间
- 孩子照片
- 家庭画像 `profile`（含睡眠、特点、需留意，以及未确认候选、摘录、对话 id）
- `editHistory`（本机撤销用；跨设备撤销不在第一期）
- 尚未确认进账本的原位草稿
- 对话正文（本来就是各设备自己的加密历史）

`family.sync_enrollments`（提议）：`user_id`、`household_id`、状态 `off` / `pending_upload` / `active` / `paused`、`review_hints`、打开与暂停时间、快照命令 id。没有这行，或状态不是 `active`，服务器就没有这份账本的同步副本。

## 3. 隐私与安全

### 3.1 最小化

孩子身份只收昵称。不收生日、学校、健康、精确位置、照片。成长记录是家长自己写的正文，长度沿用 iOS 上限，不另加推断字段。模型整理出的候选在家长点确认之前只留在手机上，不进同步表。

画像里的敏感种类继续只在手机上。第一期不把它们映射进 `family.memories`。

### 3.2 加密边界（同意文案必须写清）

| 位置 | 保护 |
|---|---|
| iOS 账本信封 | AES-GCM，钥匙只在该设备钥匙串。退出登录不删这把钥匙 |
| 传输 | HTTPS |
| 云端 `family.*` 行 | 迁移里没有列级加密。`service_role` 和通过 RLS / `apply_command` 获准的账号能读到明文昵称、标题和记录 |
| 网页对话 | 仍是浏览器 AES-GCM，与账本同步分开 |
| 平台磁盘加密 | 本仓库不能把它写成产品承诺。同意文案不把「云厂商磁盘加密」说成「只有这台手机能解开」 |

因此打开同步的含义是：这些字段离开设备，成为按家庭授权可读的账本。这和今天「仅在此设备加密保存」不是同一种保护。若以后要求家长自持密钥的端到端加密，网页和第二位照护者就无法直接阅读，第一期共享不做。默认见第 7 节问题 6。

### 3.3 RLS

沿用现有写法，并补上提议表：

- schema `family` 不暴露给浏览器的 anon / publishable key。网页和小程序只走已登录会话的 `/catpaw/family/v1`。
- 每张账本表启用 RLS。`anon` 无权限。`authenticated` 无直接 `INSERT` / `UPDATE` / `DELETE`。
- `SELECT`：`owner_user_id = auth.uid()`，或存在自己的 `household_grants.status = active`。viewer 与 caregiver 都能读家庭可见的孩子、目标、安排、记录。读策略必须存在，否则更新会静默匹配 0 行。
- 写入只经 `apply_command`。函数用调用方传入的、已由 Edge 从 JWT 解析的 actor，忽略请求体里的 user / owner。内部再查 owner 或 active caregiver。`service_role` 不能只靠「绕过 RLS」。
- 第一期 caregiver 命令仍不注册（与 `20261023400400` 一致），所以即使 SELECT 策略预留了 grant，也没有行、没有写入口。
- 以后开放时：caregiver 可以改昵称、目标、安排、记录；不能归档家庭、不能邀请、不能删账号、不能读创建者的私人对话。viewer 的每条命令拒绝。
- 角色放在 `household_grants.role`，不放进用户可改的 `user_metadata`。
- 孩子没有登录态。不给孩子签发会话。
- 两个家庭、两个大人、撤权之后的回归，沿用现有 family PG 测试的矩阵，用合成数据。撤权后的回执、变更流和投影都不再返回标题。

### 3.4 邀请、加入、离开、移除

设计现在写清，界面晚于「只有创建者」的第一期：

1. 创建者生成邀请。服务端只存哈希。界面上的码只显示一次。
2. 受邀人用已有小咪账号接受。接受写成 grant，邀请行标记已使用。
3. 照护者离开：只能改自己的 grant 为 `revoked`，然后本机删掉该家庭的投影。
4. 创建者移除某人：同一结果。对方下一笔命令是 `not_found`，变更流给墓碑。
5. 创建者不能离开自己拥有的家庭。要退出，先另批「移交」或归档整个家庭。第一期没有移交命令。

### 3.5 停用同步、导出、删账号

三个动作分开，不能合成一个按钮：

- **停止同步**：`sync_enrollments` 改为 `paused`。手机保留本地账本，不再上传、不再拉取。云端行还在。
- **删除云端账本**：创建者再次确认后，家庭归档，账本行墓碑化。手机上的副本还在，除非同时做下面的本地清除。
- **清除这台手机**：沿用今天的 `deleteAllRecords`，并可选是否删掉钥匙串里的育儿密钥。只清本地时，不自动删云端。

导出：提议创建者在重新确认登录后，下载一份可读 JSON（昵称、目标、安排、记录、时间）。这和今天的 workspace 密文导出不是同一个按钮。导出不含照片、不含画像、不含其他大人的对话。

删登录账号时，现有外键会级联删除此人拥有的 `family.households`。同意文案要写：删除账号会删除你拥有的云端家庭账本。若当时已有其他 active grant，删除账号前提示先移除或移交；在移交做成之前，默认拒绝删除，避免外键把全家数据一起级联掉。照护者删除自己的登录账号，只级联自己的 grant。

### 3.6 建议同意文案

尚未写入 `UIStrings.json` 或 `packages/mimi-core`。获批实现时再落字。

- 打开家庭同步：「孩子的昵称、目标、安排和你写下的记录，会保存到小咪现在使用的云端数据库。已登录的这台手机和网页可以阅读。这和只存在这台手机钥匙串里的加密账本不同：云端按家庭权限可读。」
- 不上传：「不会上传孩子照片、锁屏是否隐藏事项、本机提前多久提醒、睡眠和特点这类画像，也不会上传还没确认的草稿。」
- 不点就不传：「不点上传，现有记录就留在这台手机。从不打开同步，网页和小程序仍只显示说明，不保存孩子资料。」
- 停止与删除：「停止同步不会删掉已经上传的内容。删除云端账本需要再确认一次。」
- 网页锁定：「大约 15 分钟没有操作，网页会锁上，需要重新登录才能再看这些内容。这个浏览器不发送系统通知，也不写入系统日历。」

### 3.7 网页锁定对账本的要求

账本页面放在现有 `PrivacyGate` 里面。锁定或页面隐藏时，除了遮罩，还要清掉内存里的昵称和记录，与对话正文同一规则。锁定期间账本请求走 `/api/edge`，得到 401，不能靠定时器继续拉取。另一账户解锁时，不保留上一账户的账本内存。15 分钟的阈值继续用 `IDLE_MS`，不另做一套。

## 4. 同步语义

### 4.1 权威

- 从未打开，或状态是 `off`：这台 iPhone 的加密账本是唯一副本。服务器没有对应行。网页和小程序保持第 1.4 节的外壳。
- 状态 `active` 之后：云端账本行是权威。iPhone 保存加密投影和未提交命令，供离线阅读和编辑。投影不是第二份权威。
- 状态 `paused`：停止双向同步。手机继续用暂停前的本地账本。云端保持暂停当时的行，直到创建者删除或再次打开。

### 4.2 打开时的迁移

由家长发起，可中途放弃。任何保存动作本身都不上传。

1. 设置里显示将要上传的条数：几个孩子、几条目标、几条安排、几条记录。列出第 2.5 节留在本机的项目。
2. 本地账本为空：可以建立一个空的云端家庭。
3. 本地账本非空：唯一的打开方式是「上传这台手机上的现有账本」。不提供「云端从空的开始、手机继续各记各的」。那会重新变成 #156 要避免的两套账本。
4. 上传是一个快照命令（提议 `ledger.snapshot`），在一个事务里写入孩子、目标、安排、记录，保留 UUID。全部回执 `applied` 之后，enrollment 才变成 `active`。中途失败则保持 `pending_upload`，本地仍是权威；家长可以丢弃这个未完成的家庭（归档），不会留下半份 active 账本。
5. 第二台已经有另一份本地账本的设备，若云端已是 `active`，不自动上传、不自动合并。界面说明云端已有账本，默认按钮是「先不覆盖」。要用云端替换这台手机，需要第二次确认，并先提供本机导出。第一台成功上传的那份为准。

关闭同步是第 3.5 节的「停止」，不自动反向删除。再次打开时，若云端已有 active 账本，走下载；不把暂停期间的本地分叉静默覆盖上去。分叉要家长逐条确认，第一期可以做成「暂停期间的本地修改留在手机上，标成未同步」，而不是字段级自动合并。

### 4.3 离线与冲突

iOS 今天用整本 `revision` 做 CAS。云端已有对象用各自的 `expected_revision`。同步后以**每条对象的版本**为准，不用字段级后写覆盖。一次调整同时改标题和时间，是一条命令；拆成字段后写会把两次编辑拼成家长没确认过的组合。

离线编辑进入本机队列：`command_id`（重试不变）、对象 id、`expected_revision`、类型化 payload。连上后按顺序提交。同一 `(actor, household, command_id)` 且请求哈希相同，返回原回执（已有 `family.receipts` 规则）。哈希不同则冲突，不执行第二次。

版本不符：保留云端当前行，本机这条改动标成待处理，请家长再看一遍后决定是否按新版本重做。不自动用本地覆盖，也不把墓碑行救回。

软删除：云端写 `deleted` 和 `deleted_at`。iOS 投影隐藏这些行。今天 iOS 的硬删除在同步层翻译成墓碑命令，不在云端 `DELETE` 物理行。迟到的更新碰到墓碑就拒绝。

### 4.4 从不打开的人

不创建 `sync_enrollments`，不调用 `household.ensure`，不在保存、完成、重装或换机时后台上传。网页和小程序文案保持「孩子的信息只存在手机上的小咪里」。换手机不会出现这份账本。这是预期结果，不是故障。

## 5. 各端影响

实现都在本草案获批之后的独立 PR 里。这里只定范围。

### 5.1 iOS

- 我们家设置增加「家庭同步」。默认关。关时网络路径为零，现有加密账本、提醒排程、照片、画像不变。
- 开时增加同步客户端：快照、命令队列、按 outbox 拉取投影。放在应用服务层，不放进 SwiftUI 视图。
- 本地提醒继续由 `ParentingReminderScheduler` 根据投影里的 `planned` 安排和**本机**设置排程。云端不替这台手机调用系统通知。
- 硬删除在投影里变成墓碑。未同步时的磁盘格式保持能读；不强制旧用户迁移。
- 不改 Bundle、钥匙串服务名和 SwiftData 容器身份。

### 5.2 Web 可写账本

不使用 IndexedDB 存第二份账本。可读数据只来自同步投影；可写只发与 iOS 相同的命令。未打开同步的账号仍看到今天的空态和说明。

获批后按小节交付，而不是一次做完 #156 的整张清单：

- 读：今天、成长、我们家展示已同步的孩子、安排、目标和记录。空云端账本显示空态，而不是「只在手机里」那句；从未打开同步仍显示那句。
- 写：添加 / 改名 / 删除孩子，目标，手填安排，确认 / 调整 / 取消 / 完成 / 重开，记下观察。完成仍带短时间撤销，撤销是一条命令。
- 原位整理：家长确认之后，写入同一套云端命令。确认前不写。
- 设置：回顾提示读写 `review_hints`。重新读取拉投影。清除全部记录走第 3.5 节，按钮写明会处理云端还是只处理本机。

设备能力继续不做：Live Activity、小组件、Siri、EventKit、本机语音识别、本机通知排程、保存到照片。朗读仍用浏览器 `speechSynthesis`。

### 5.3 提醒列表

Web 提醒页和小程序提醒页读取已同步、状态为 `planned` 的安排（孩子昵称、标题、`due_at`）。这就是本决定说的提醒列表。

它不读 `family.reminders`，不调用 `reminder.schedule`，不领取、不发送。文案写明：这个浏览器不发送系统通知，也不添加系统日历。iPhone 是否响铃，只看那台手机上的提醒开关、提前量和系统权限。

服务端 `claim_due_reminders` / `fire_reminder` 保持不启用。打开家庭同步不等于打开云端推送。

### 5.4 小程序

第一期小程序仍不连接生产，不写本地账本。在网页读写于预览环境证明可用，并且微信登录绑定另批之后，才做只读的今天 / 成长 / 我们家 / 提醒列表。写操作再下一节。订阅消息不在本草案里。

锁定沿用大约 15 分钟无操作就遮住并停止读取；退出清掉内存中的账本投影。

## 6. 分批与批准闸门

每一批都是独立 PR。前一批的预览证据不代替下一批。合成测试和真机、生产库分开记录。

| 批次 | 内容 | 闸门 |
|---|---|---|
| 0 | 本文 | 草案。合并要用户批准。合并不等于可以写代码 |
| 1 | 只增加提议表和 RLS 的 SQL 文件，文件头仍写未应用。本地临时 Postgres 跑双家庭、撤权、墓碑、快照回滚 | 不连远程库 |
| 2 | 把第 1 批迁移应用到 **Supabase 预览 / 分支库** | **要批准**。不碰生产，不改生产 Auth 重定向，不开生产功能开关 |
| 3 | iOS 选择加入，默认关，只指向预览。未打开的人行为与现在相同 | 不提交 App Store / TestFlight，除非另批 |
| 4 | Web 只读投影和提醒列表。未同步账号保持外壳 | 预览站点。生产 Web 部署另批 |
| 5 | Web 写入第 5.2 节的命令，仍对预览 | 不另造本地账本 |
| 6 | 小程序只读 | **要批准**微信登录、AppID 和域名之后 |
| 7 | 照护者邀请 UI | 创建者单人路径稳定之后，另批 |
| 8 | 生产迁移、生产 Edge 部署、打开 `CATPAW_FAMILY_ENABLED`、App 安装包 | **每一步单独批准** |

必须点头才能做的步骤：

- 合并本 PR
- 开始第 1 批之后的任何实现 PR
- 在预览库或生产库执行迁移
- 部署 Edge，或在任何环境把 `CATPAW_FAMILY_ENABLED` 从 `off` 打开
- 修改生产 Supabase 配置（含 Auth 重定向允许列表）
- 安装到手机、TestFlight 或 App Store 包
- 小程序真实登录、正式 AppID、请求域名
- 开放邀请第二位照护者
- 启用 `family.reminders` 的领取 / 发送，或任何 APNs / 微信订阅消息

本草案不授权上述任何一项。

## 7. 待确认问题

每条给出建议默认。未改口就按默认实现后续 PR，仍受第 6 节的批准闸门约束。

1. **一个账号能拥有几个家庭？** 建议默认：至多一个 active 家庭，与已有索引 `households_one_active_per_owner` 一致。第一期不能同时经营两个家。以后以照护者身份加入别人的家庭，不额外再拥有第二个。
2. **打开同步时，手机上已有的账本怎么处理？** 建议默认：非空账本必须由家长点「上传这台手机上的现有账本」才上传昵称、目标、安排和记录。不点就保持仅本机。空账本可以建立空的云端家庭。不提供云端空白、手机继续单独记账。
3. **两台设备同时改同一条，听谁的？** 建议默认：整条对象的 `expected_revision`。冲突时留下云端版本，本机修改进入待处理，请家长确认后再提交。不用按字段后写覆盖。
4. **照片和家庭画像同步吗？** 建议默认：不同步。照片、睡眠、特点、需留意、未确认候选都留在本机。
5. **第一期就邀请第二位照护者吗？** 建议默认：表和 RLS 按 owner / caregiver / viewer 留好，第一期只有创建者自己的 iPhone 和网页。邀请界面单独一批。这与当前 `grant.upsert` 已从命令表删除的事实一致。
6. **云端要不要再做只有家长持有密钥的加密？** 建议默认：不做。使用 HTTPS 和按家庭的 RLS。同意文案写明云端账本按权限可读。若改为端到端加密，网页和照护者不能直接阅读，第一期就不做跨设备阅读。

## 8. 与现有文档不一致的事实

这些是核对代码后的差异。本文不改写那些日期文档的原文。

- #156、Web README、小程序 README 和 `repository-layout.json` 说账本还没有可同步的来源、`family_v1` 未决。仓库里其实已有 schema `family`、`/catpaw/family/v1` 和一整套命令。同时：迁移头写明未应用到生产，功能开关默认关，iOS 没有调用该路由。所以「手机账本尚未同步」成立，「仓库里没有任何家庭后端」不成立。
- `api-v1.md` 写单成人、不提供邀请，事项没有已完成。`20261023400100` 起出现 `household_grants`，角色后来包含 `caregiver`，但 `20261023400400` 删掉了邀请和撤权命令。技术方案里的 `memberships` 表不存在。
- M5 差距表说要补目标与完成的云合同。`family.plans` 不是 iOS 目标，`family.items` 没有 `completed` 和 `goal_id`，`family.observations` 最长 500 字且没有完成种类。iOS 标题最长 160、原文和记录最长 8000。本草案因此提议新表，而不是宣称旧表已经够用。
- `family.members` 有出生年、学校、班级、老师、地点。iOS 的 `ParentingChild` 只有 id、昵称、创建时间。添加孩子文案拒绝收集生日和健康信息。这些列保持空。
- `family.reminders` 是服务端领取队列。iOS 提醒设置在 UserDefaults，锁屏隐藏没有对应列。打开同步不应启动 `claim_due_reminders`。
- 退出登录会删孩子照片和周复盘时间，但故意不删育儿账本钥匙。`deleteAllRecords` 保留回顾提示开关。设置里的导出是含密封文的 workspace JSON，不是可读的育儿导出。
- `docs/acceptance/family-companion/family-settings-v0.6.md` 里「启航 n/3」曾记为占位。后来的 `ParentingStartAction` 已接到我们家；该验收文是当时分支的记录，不是今天源码的全部行为。本文不把它当作同步已完成的证据。
