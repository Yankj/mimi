# Mimi 今天页 · 毛绒 + 底栏一行双态 · 方案对比

对照真机 818（`f3d2f64` 一带）：`LaunchMarkView` 全屏门闸 → 登录 `FirstRunPlushHero` → 空态 `PlushEmptyBackdrop` → 今天 hero 小 `PlushMascot`≈52；底栏现状是 **上** `ParentingComposerBar` **下** 今天|成长|我们家 两排；提交后另叠 `ParentingNaturalSheet`（按 `input.phase`）。

用户口径（必须严格执行）：

- **禁止**「上输入 + 下 tab」两排布局
- **必须**「一行双态」：输入态 = `+` / 键盘 / 按住说话 + **已选 type 收起小标同排**；点小标 → 选 type 态（输入组件收起，**同区**展开今天|成长|我们家）；再点切回输入
- 输入时**不展示 detail**
- 毛绒：**角落小露头主推**，后台轻量备选

示意文件（日间线框）：

| 文件 | 内容 |
|------|------|
| `0-compare-two-row-vs-single-row.png` | 现状两排 ✕ vs 一行双态 ✓ |
| `A-corner-peek-single-row.png` | **主推** 角落小露头 + 一行双态 |
| `B-backdrop-light-single-row.png` | 备选 后台淡影毛绒 + 一行双态 |
| `C-logo-badge-single-row.png` | 变体 小 logo 角标 + 一行双态 |

路径根：`/workspace/mimi-layout-options/`

---

## 共同交互（A/B/C 底栏相同）

```
┌─ 输入态（默认）─────────────────────────────────────┐
│  [+] [键] [====== 按住说话 ======] [叶标]            │
│                              ↑ 已选 type 收成小芯片   │
└─────────────────────────────────────────────────────┘
         │ 点叶标                              ▲ 点选中项 / 空白
         ▼                                     │
┌─ 选 type 态（同区替换，不高出第二排）────────────────┐
│  [ 今天 ] [ 成长✓ ] [ 我们家 ]                       │
└─────────────────────────────────────────────────────┘
```

| 时机 | 规则 |
|------|------|
| 启动 | `LaunchMarkView`/`catLaunchMark` 全屏门闸照旧（≤1.1s、可点跳过）；进今天后按方案落毛绒形态 |
| 空闲 | 底栏仅一行；无第二排 tab；叶标显示当前 tab |
| 聚焦输入 | 仍一行；可短暂上浮 chips/建议；**不展示 detail**；不另开 tab 排 |
| 有 detail | 提交后同区状态机或短面板出确认/追问；**一旦重新聚焦输入即藏 detail** |
| 点叶标 | 切选 type 态：输入控件收起，同区展开三 tab |
| 点 tab / 空白 | 写回选中 type，回输入态 |

实现落点（示意级，未合 main）：收敛 `ParentingHomeView` 里 composer 与 tab chrome 为同一 `safeAreaInset` 状态机；`ParentingNaturalSheet` 改为同区 phase 或短 detent，输入 `focused` 时强制 dismiss/藏。

---

## 方案 A · 角落小露头（**推荐**）

**毛绒**：进页后右下角小露头（约 40–56pt 可见），贴在底栏上方外侧，不占主列宽；滚动/键盘不放大。

| | |
|--|--|
| 优点 | 保留毛绒温度；几乎不挡操作；与「一行双态」不抢高；三角倾向一致 |
| 缺点 | 角落仍有一点视觉噪声；需处理与键盘避让 |
| 适合 | 真机反馈「启动太大太久」的主修方向 |

## 方案 B · 后台轻量（备选）

**毛绒**：退成 `PlushEmptyBackdrop` 级淡影（≤10%），几乎不可点挡；hero 内 52pt 可保留或再缩。

| | |
|--|--|
| 优点 | 零占位、零挡手；实现可复用现有 backdrop |
| 缺点 | 品牌感弱；「小咪在场」几乎消失 |
| 适合 | 嫌角落仍挡、或要极净内容的人 |

## 方案 C · 小 logo 角标（变体）

**毛绒**：无；内容区右上 ≈32–40pt logo 角标。

| | |
|--|--|
| 优点 | 最简、无动画负担 |
| 缺点 | 温度最低；与登录 `FirstRunPlushHero` 落差大 |
| 适合 | 只要品牌标、不要吉祥物的实验版 |

---

## 推荐

1. **底栏一律按一行双态做**（与毛绒方案正交，A/B/C 共用）——对照图见 `0-compare-…png`
2. **毛绒先做 A（角落小露头）**；若真机仍觉得挡，再降级到 B
3. C 仅作极简备胎，不默认

## 与 #146 关系

- `#146` `design/visual-polish-1h`：分区层级 + 按钮玻璃（UI 测挂，本轮跳过合入）
- 本布局可开 `design/composer-presence-layout` draft，或用户拍板后并入 #146；**不合 main**
- 本回合：**示意 + OPTIONS 已交；SwiftUI 骨架 / draft PR 未开**（可后补）

## 现状组件对照（供实现）

| 现状 | 目标 |
|------|------|
| `ParentingComposerBar` 浮在 tab 上方（两排） | 与 type 合成一行状态机 |
| 下排 今天\|成长\|我们家 常驻 | 仅「选 type 态」同区展开；输入态收成叶标 |
| `ParentingNaturalSheet` 独立 sheet | 输入中不展示；确认走同区/短面板 |
| `LaunchMarkView` 全屏 | 保留；之后落到 A 露头 / B 淡影 / C 角标 |
| hero `PlushMascot`≈52 | A 可保留；B/C 建议缩小或去掉以免双毛绒 |

---

## 实现备注（draft `design/composer-presence-layout`）

已按方案 A + 三角状态机落壳（不合 main）：

- `ParentingChromeMode`: `.input` | `.pickTab` | `.detail`
- 浮动底栏改为 `dualChrome`：**禁止** `VStack{composer; tabBar}` 两排
- 输入态：`ParentingComposerBar` 同行叶标（当前 tab SF Symbol）
- 选 type：同区 `tabBar`；点 tab / 收起 → `.input`
- detail：仍用 `ParentingNaturalSheet`（medium/large detent）；composer `focused` → 强制 dismiss 回 `.input`
- 我们家：`showsComposer==false` 时只渲染一排 `tabBar`
- iOS 26：停用 native `TabView`+composer inset（会再现两排），统一自定义一排壳
- 角落小露头：今天页右下 `PlushMascot`≈48 clipped peek
- 启动门闸：`launchKeyframes` 缩短至 **0.85s**（仍可点跳过）
- **未改**认娃 / 档案 Model / ConfirmCard

### 结构疑点（PR 描述同步）

1. detail 仍是独立 sheet，未完全做成同区面板；聚焦输入会 dismiss，但视觉上仍是模态。后续可收成同区 phase 卡。
2. pickTab「点空白收回」目前靠收起按钮 + 点 tab；内容区空白点击尚未全局拦截。
3. iOS 26 系统 TabView 行为被关掉，Liquid Glass 系统 tab 动画不再用——换一排壳换的代价，需真机确认是否可接受。
