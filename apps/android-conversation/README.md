# mimi Android（`apps/android-conversation`）

mimi 的 Android 版：Kotlin + Jetpack Compose + Material 3 原生实现，产品设计与 iOS 版保持一致，
后端沿用同一套 Supabase Edge Functions（本目录不改后端、不放任何密钥）。
选型理由、模块规划与里程碑见 [docs/android/PLAN.md](../../docs/android/PLAN.md)。

**当前状态：M0（工程脚手架 + 主题）。** 能构建并通过全部 JVM 测试；界面只有“今天｜成长｜我们家”三 Tab 空壳，
“我们家”页临时放了配色/外观切换和着色按钮预览，用来目测 B/C × 浅/深四种主题。真机验收等手机接入后再做。

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
├── app/                    # 应用壳：MainActivity、启动页、桌面图标、manifest（Hilt 等从 M1 起加）
├── core/
│   ├── domain/             # 纯 JVM：Palette / Appearance / ThemePreferences（键 "palette"、"theme"，与 iOS 同名）
│   ├── designsystem/       # 令牌（tokens/）、MimiTheme、着色玻璃按钮、页面色晕、吉祥物、Tab 图标
│   └── i18n/               # UIStrings.json 加载（构建时从 iOS Resources 同步）+ ui(key, args)
├── feature/parenting/      # 三 Tab 空壳（后续里程碑填内容）
├── scripts/                # android-env.sh、verify.sh、check-localization.py、export-theme-tokens.py
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
scripts/verify.sh              # 一键：令牌快照校验 + 文案检查 + 全部单测 + 构建 APK
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
| `app` | `MainActivityTest` | 真实 `MainActivity`（含启动页主题）起得来；applicationId 固定 |

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

## 已知限制（M0）

- **没有模拟器验证**：开发机的 `/dev/kvm` 不可用，模拟器起不来；M0 的视觉验证靠 Robolectric 原生渲染截图 + 真机。
  Robolectric 里 Haze 走降级色罩，真实模糊只能在真机上看。
- 主题偏好只在内存里（旋转保留）；DataStore 持久化、Hilt、导航、网络与账号从 M1 起。
- “降低透明度”目前只有 `LocalReduceTransparency` 应用内开关；系统信号在 M2 的设置页接入。
- 暂无 CI（GitHub Actions 额度已用完），本地 `scripts/verify.sh` 就是门禁。
- Release 构建未配置签名与混淆验证（M6）。
