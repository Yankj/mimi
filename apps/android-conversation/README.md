# mimi Android（`apps/android-conversation`）

mimi 的 Android 版：Kotlin + Jetpack Compose + Material 3 原生实现，产品设计与 iOS 版保持一致，
后端沿用同一套 Supabase Edge Functions（本目录不改后端、不放任何密钥）。
选型理由、模块规划与里程碑见 [docs/android/PLAN.md](../../docs/android/PLAN.md)。

**当前状态：M2b（M2 之上补齐改期/调整历史/删除/重命名/带备注完成/详情页/大标题折叠 + 真实账本旅程测试 + 有数据态截图基线）。** 能构建并通过全部 JVM 测试（285 个）。
App 启动后在本机生成匿名“体验账号”（`AccountEpoch` 驱动，每个账号一个隔离的 Keystore 加密账本）；可手动添加孩子、目标、安排与观察
（只有点“保存”才写账本，放弃零写入），确认草稿、一键完成并 3 秒内撤销；设置页有外观/配色、退出、清除全部数据、关于。
M2b 另有：改期（同一安排改时间/标题/目标，同一分钟冲突需勾选确认）+ 调整历史与撤销、孩子/目标/安排/记录的删除与重命名（先确认）、带备注的完成表单、孩子详情与目标回顾页、大标题折叠、孩子筛选。
还没有：语音/输入卡（M3）、提醒（M4）、账号与云整理（M5）、M7、M8。真机验收等手机接入后再做。

## 版本（2026-10-28 复核）

| 组件 | 版本 | 备注 |
|---|---|---|
| Android Gradle Plugin | 9.4.0 | 需要 Gradle ≥ 9.6；内置 Kotlin，不再单独应用 `kotlin-android` |
| Gradle | 9.7.0 | Kotlin 2.4.21 官方测过的最高 Gradle 版本；最新的 9.8.x 暂不用 |
| Kotlin | 2.4.21 | Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` |
| Compose BOM | 2026.09.00 | Compose 1.12.1、Material 3 1.4.0；Compose 1.12 要求 `compileSdk ≥ 37` |
| compileSdk / targetSdk / minSdk | 37.1 / 36 / 29 | 最低 Android 10；minSdk 29 之上 Haze 模糊走 RenderEffect（Android 12+）或降级色罩 |
| Haze | 2.0.1 | 背景模糊（玻璃底栏）；不支持模糊的系统自动降级为纯色罩 |
| Robolectric | 4.17 | 在 Linux JVM 上跑 Compose UI 测试 |
| applicationId | `com.heydusk.mimi` | 与计划一致，debug 与 release 同名 |

所有第三方版本只写在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

## 目录

```
apps/android-conversation
├── app/                    # 应用壳：MainActivity、启动页、桌面图标、manifest；Hilt 只在这里（di/ 里的两个模块）
├── core/
│   ├── domain/             # 纯 JVM：主题偏好 + 育儿领域（工作区归约器、自然语言输入、改期/提醒规则、今日/成长、canonical JSON、AccountEpoch…）
│   ├── data/               # Android 库：Keystore AES-GCM 加密账本（ParentingWorkspaceStore）、DataStore 偏好；无 Hilt
│   ├── designsystem/       # 令牌（tokens/）、MimiTheme、着色玻璃按钮、页面色晕、吉祥物、Tab 图标
│   └── i18n/               # UIStrings.json 加载（构建时从 iOS Resources 同步）+ ui(key, args)
├── feature/parenting/      # 三 Tab 空壳（后续里程碑填内容）
├── scripts/                # android-env.sh、verify.sh、check-localization.py、export-theme-tokens.py、test-counts.py
├── gradle/libs.versions.toml
└── version.properties      # versionName / versionCode 的唯一来源
```

## 环境

构建只需要 JDK 21 和 Android SDK（命令行即可，不需要 Android Studio、模拟器或任何账号）：

```bash
source apps/android-conversation/scripts/android-env.sh   # 设置并检查 JAVA_HOME / ANDROID_HOME，缺项会提示
```

SDK 组件：`platforms;android-37.1`、`platforms;android-36`、`build-tools;36.0.0`、`platform-tools`。
脚本头部有安装命令。仓库自带 Gradle Wrapper（9.7.0，已固定校验和），首次构建会下载 Gradle 和依赖。

## 构建与测试

```bash
cd apps/android-conversation
source scripts/android-env.sh

./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk（约 30 MB）
./gradlew test                 # 全部 JVM 单测（含 Robolectric 的 Compose UI 测试），不需要设备
scripts/verify.sh              # 一键：令牌快照校验 + 文案检查 + 全部单测（按模块汇总计数）+ 构建 APK + 组装设备侧测试 APK
scripts/verify.sh --no-build   # 同上但不出 APK
```

测试内容：

| 模块 | 测试 | 内容 |
|---|---|---|
| `core:domain` | `ThemePreferencesTest` | 默认值（C + 跟随系统）、偏好键、未知值回落、外观解析 |
| `core:designsystem` | `ThemeTokensTest` | 对应 iOS `CatThemeTokenTests`：全部令牌 = 提案（ink3 为有意偏离）、全部文字对比度 ≥ 4.5（WCAG，测试内独立实现）、图标 ≥ 3、按钮各态透明度与合成色；对比度表写到 `core/designsystem/build/reports/contrast-table.md` |
| `core:designsystem` | `TokenSnapshotTest` | 用 `docs/design/mimi-theme-tokens.json`（由 iOS `CatTheme.swift` 导出）逐值核对 Kotlin 令牌 |
| `core:designsystem` | `MimiThemeTest` | Robolectric：四种组合、默认、跟随系统（night 限定符）、显式浅色压过夜间系统 |
| `core:i18n` | `UiCatalogTest` | 占位符、回落规则、语言选择、共享目录 2348 键一致性、补充目录不遮蔽共享目录 |
| `feature:parenting` | `ParentingHomeScreenTest` | 三 Tab 空壳；测试标签 `parenting.tabBar`、`parenting.tab.today/growth/family` 与 iOS 的 accessibilityIdentifier 相同；中英文标题；配色与外观切换互相独立 |
| `feature:parenting` | `ShellScreenshotTest` | 用 Robolectric 原生图形渲染 B/C × 浅/深 四张“我们家”页 PNG 到 `feature/parenting/build/screenshots/`（不入库） |
| `feature:parenting` | `WorkspaceJourneyTest`（5） | 对标 iOS `ParentingWorkspaceUITests` 的账本旅程：真实 Compose 界面 + 真实 AES-GCM 文件账本；「冷启动」= 同目录同密钥上重建会话与整棵 UI。含创建→确认→调整→带备注完成→记录链接→冷启动、改期与撤销、取消、观察编辑/删除、写失败保留输入后重试 |
| `feature:parenting` | `LayoutJourneyTest`（5） | 三 Tab 各自标题、大标题折叠/复位、悬浮底栏不遮末项、手动表单放弃无草稿、深色 + 200% 字体空态与编辑器冒烟 |
| `feature:parenting` | `ManagementTest`（6） | 重命名孩子、删除安排/目标/孩子（先确认）、同一时间冲突确认、孩子筛选 |
| `feature:parenting` | `PopulatedBaselineTest` | 有数据态 10 个画面 × 浅/深的亮度指纹基线（`src/test/resources/screenshot-baselines.json`；仓库禁提交 PNG，故存指纹）。重录：`MIMI_RECORD_BASELINES=1 ./gradlew :feature:parenting:testDebugUnitTest --tests '*PopulatedBaselineTest*'`，PNG 在 `build/screenshots/` 供目测 |
| `app` | `MainActivityTest` | Hilt + Robolectric：真实 `MainActivity` 起得来；applicationId 固定；点配色/外观后写入 DataStore（键 `palette`/`theme`）；Hilt 图能给出可用的加密账本 |
| `app` | `ThemeViewModelTest` | 主题偏好冷启动后仍在 |
| `core:domain` | 育儿领域（164 个，含主题 4） | 移植自 iOS 领域测试，断言值不改；对照表见 PLAN 8.2 |
| `core:data` | `ParentingWorkspaceStoreTest` 等（43 个） | 账本语义（确认前零写入、CAS、重复操作、并发对账、损坏保留、账号过期）、密钥提供者与文件存储（JCA PKCS12 代替 AndroidKeyStore）、DataStore |
| `core:data` androidTest | `AndroidKeystoreInstrumentedTest` | 真 AndroidKeyStore 往返；**只编译、未运行**（需要设备） |

### 主题令牌与 iOS 保持一致

iOS 的 [`CatTheme.swift`](../ios-conversation/Sources/DesignSystem/CatTheme.swift) 是令牌的唯一权威来源，
Kotlin 的 `ThemeTokens.kt` 是手工移植。为防止两端漂移：

```bash
python3 scripts/export-theme-tokens.py          # 从 Swift 源重新导出 docs/design/mimi-theme-tokens.json
python3 scripts/export-theme-tokens.py --check  # 只检查快照是否与 Swift 源一致（verify.sh 会跑）
```

`TokenSnapshotTest` 再用这份快照逐值核对 Kotlin。iOS 改了令牌：重新导出快照、改 `ThemeTokens.kt`，测试会指出漏改的地方。

### 文案

界面文案与 iOS 共用 `apps/ios-conversation/Resources/UIStrings.json`（中文原句做键，值为 `{en, zh-Hans}`）。
`:core:i18n` 的 `syncUiStrings` 任务在构建时把它复制进 assets，**仓库里不保留第二份副本**；取词用
`ui("今天")` / `ui("共 {0} 条", count.toString())`，规则与 iOS `UICatalog` 相同（缺键回落为键本身，{n} 按下标替换）。
共享目录里暂时没有的 Android 专有文案放在 `core/i18n/src/main/assets/UIStringsAndroid.json`（M0 只有主题演示用的 7 条；
共享目录优先，设置页上线时并回 `UIStrings.json`）。`scripts/check-localization.py` 检查两份目录的完整性，
并保证 Kotlin 生产代码里没有硬编码的中文字面量。

## 在手机上运行（真机接入后）

```bash
adb devices                                   # 手机开启开发者选项 + USB 调试后应能看到设备
./gradlew installDebug                        # 或 adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.heydusk.mimi/.MainActivity
```

首次真机验收清单（M0）：四种主题切换（我们家 → 配色/外观）；底栏 Haze 模糊在 Android 12+ 上是否真的模糊、
在 Android 10/11 上是否退回纯色罩而不是空白；着色按钮按下态；启动页与桌面图标（含 Android 13 主题图标）。

## 已知限制（M2b）

- **没有模拟器验证**：开发机的 `/dev/kvm` 不可用，模拟器起不来；M0 的视觉验证靠 Robolectric 原生渲染截图 + 真机。
  Robolectric 里 Haze 走降级色罩，真实模糊只能在真机上看。
- 真 AndroidKeyStore 没在任何设备上跑过（JVM 里用 JCA PKCS12 代替）；`AndroidKeystoreInstrumentedTest` 只编译。
- 身份是本机匿名的，没有 Supabase 登录（M5）；退出会销毁本机账本与密钥，数据不会上云。
- 表单是同窗口浮层而非 `Dialog` 窗口（Robolectric 里窗口内输入框不收敛）；真机软键盘表现未验证。
- 时间选择是步进器（日期/小时/分钟 ±），不是系统日期/时间选择器（Material 的选择器走 `Dialog` 窗口，Robolectric 不收敛）；溢出菜单用 `DropdownMenu`，真机弹出位置与 TalkBack 读序未验证。
- 14 条 iOS 旅程里 4 条依赖 M3/M5（语音输入卡、确认卡、「让小咪整理」）而推迟，另 3 条只有布局/表单部分（见 PLAN §8.4）。
- 截图基线是 Robolectric 原生图形（Linux）下的亮度指纹，容差为平均差 ≤0.6/15、最大差 ≤6；换 OS/字体/Compose 大版本后可能需重录。Haze 真实模糊不在其中。
- 补充文案 `UIStringsAndroid.json`（50 条）仍未并回共享目录。
- 备份：`allowBackup=false` 并排除全部数据（Keystore 密钥不迁移）。
- “降低透明度”目前只有 `LocalReduceTransparency` 应用内开关；系统信号尚未接入（设置页只有应用内开关）。
- 暂无 CI（GitHub Actions 额度已用完），本地 `scripts/verify.sh` 就是门禁。
- Release 构建未配置签名与混淆验证（M8）。
