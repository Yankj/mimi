# 今天首页逻辑层 v0.6（S2a）：启航操作解析 / reopenArrangement / 首页数据助手

分支：`feat/today-start-action`，基于 main `db41ad37`。本切片只有逻辑层和测试，**没有视觉改动**，也没有改任何 SwiftUI 视图；UI 切片随后调用这里的 API。Mock 不等于验收：下面只记录实际跑过的验证。

## 新增 API（供 UI 切片调用）
| 名称 | 位置 | 说明 |
|---|---|---|
| `ParentingStartAction.resolve(_ tab:, workspace:, now:, calendar:, context:)` | `Domain/ParentingStartAction.swift` | 纯函数，每个 tab 返回唯一推荐操作；时钟和 `Calendar` 注入（默认设备时区，设备在上海即 Asia/Shanghai） |
| `ParentingStartTab` / `ParentingStartContext` / `ParentingLaunchChecklist` | 同上 | `Context` 放账本之外的事实：上次周回顾时间、提醒是否开启、提醒步骤是否跳过、当前孩子 |
| `ParentingStartAction.launchChecklist(workspace:context:)` | 同上 | 「启航 n/3」进度，单独可调 |
| `ParentingTodayHome.build(workspace:childID:now:calendar:rowCap:)` | `Domain/ParentingTodayHome.swift` | 副标题、最多 5 行、“现在”线位置、其余计数 |
| `ParentingTodayHome.subtitle(planned:done:)` / `insertionIndex(of:in:)` | 同上 | 「3 件·已完成 1」；“现在”线插入索引 |
| `ParentingOverdueMark.mark(for:now:calendar:)` | 同上 | 仅标记：`none / passedToday / previousDay` |
| `ParentingCompletionUndo.make(...)` / `isActive(at:)` / `window = 3` | 同上 | 一键完成后的 3 秒撤销句柄 |
| `ParentingCommand.reopenArrangement(id:)` | `Domain/ParentingWorkspace.swift` | `completeArrangement` 的撤销 |
| `ParentingWorkspaceModel.completeOneTap(_:operationID:recordID:)` / `undoCompletion(_:operationID:)` | `Features/Parenting/ParentingWorkspaceModel.swift` | 账本回执后才返回撤销句柄；撤销是单独保存的第二条命令 |

## 启航规则表（自上而下，第一条命中即返回）
### 今天
| # | 条件 | 返回 |
|---|---|---|
| 1 | 没有孩子 | `startWithNickname` |
| 2 | 有待确认草稿（任意日期） | `confirmAll(arrangementIDs)` |
| 3 | 有以前日期、已确认但没标完成的安排 | `handleOverdue(arrangementIDs)` |
| 4 | 今天有未完成的已确认安排 | `nextWithComplete(id, dueAt, hasPassed)`：优先今天还没到点的最早一条；都已过点则取最近过点的一条（`hasPassed = true`） |
| 5 | 本地时间 18:00 ≤ 时刻 < 21:00，且今天还没有观察 | `recordTodaySmallThing(childID)` |
| 6 | 其余 | `examplePrompt(text)`：5 句示例按“第几天”轮换，同一天稳定 |

### 成长
| # | 条件 | 返回 |
|---|---|---|
| 1 | 当前孩子没有任何记录（或没有孩子） | `examplePrompt` |
| 2 | 近 7 天内至少有一条记录，且 (a) 周日 18:00 起且今天还没回顾过，或 (b) 距上次回顾 ≥ 7 天（从未回顾则以最早一条记录为起点） | `weeklyReview(reason)` |
| 3 | 观察 ≥ 5 条且该孩子没有任何目标 | `giveObservationsAGoal` |
| 4 | 其余 | `recordObservation(childID)` |

### 我们家
「启航 n/3」：① 添加孩子 ② 记下第一件事（有任意安排或记录即算）③ 开启提醒（可选，可跳过）。①②必做；③做了或跳过后清单消失，返回 `.none`。跳过不计入完成数，也不会隐藏必做步骤。

与方案相比的两处解释（请 UI 切片按此显示）：
- 规则 5 增加“今天还没写过观察”，避免写完后仍被催；
- 成长规则 2 要求近 7 天内有记录，避免对空周提示回顾。

## 状态机：`reopenArrangement`
```
draft --confirm--> planned --complete--> completed
                      ^                     |
                      +----- reopen --------+
cancelled：不能完成，也不能重新打开（报错）
```
| 当前状态 | `reopenArrangement` |
|---|---|
| completed | 状态回 planned，`updatedAt = now`；自动写入的完成记录（`sourceText` 为空且未被编辑）删除；家长写过或改过的记录改为 `observation`，断开与安排的关联，文字保留 |
| planned | 无操作（幂等），账本版本仍 +1，与重复 `completeArrangement` 一致 |
| draft | 报错（从未完成过） |
| cancelled | 报错 |
| 安排不存在 | `missing` |

- 按现有命令模式走 `applying` 纯转换 + `ParentingWorkspaceStore` 加密落盘；`model.apply` 在落盘成功后才发布。撤销句柄也只在完成已落盘后才产生。同一个 `operationID` 重试返回已保存结果，不会再多一个版本。
- 完成时对编辑历史的 superseded 标记保持不变，重新打开不会复活旧的“撤销调整”。
- 重新打开后不会自动重新提醒或完成：下次提醒由 `ParentingReminderScheduler.reconcile` 按账本重算。
- 本地账本不属于 `catpaw.family.v1`：该协议现无目标和完成命令（见 `parenting-core.md`）。本切片**没有改协议、没有改后端**；接云时需把 `reopen` 一并列入版本化合同扩展。

## 诚实限制
- 没有埋点，规则阈值（18–21 点、5 条观察、7 天）按已确认的方案，不是数据结论。
- `deleteRecord` 仍然只删记录、不改安排状态：删掉完成记录后安排仍是 completed；之后仍可 `reopenArrangement`（会得到 planned，且无记录可删）。UI 若要“删记录同时重新打开”，需调用两条命令。
- 上次周回顾时间、提醒开关来自 `ParentingStartContext`，当前没有存储；UI 切片需要自行持久化“已完成周回顾”。
- 示例句子、副标题文案是中文固定文本，未做本地化表。
- 3 秒窗口用模型时钟判断；账本时间由存储时钟写入，二者在同一设备上，未做跨设备时钟处理。
- “逾期”只是标记。逾期安排从不自动完成、取消或改期。
- 仅逻辑层与测试，没有 UI；没有真机验收。

## 验证
见 PR 描述中的实际命令与结果。
