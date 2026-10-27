# apps/android-conversation 工作说明

- 这是 mimi 的 Android 原生工程（Kotlin + Compose），与 iOS（`apps/ios-conversation`）产品设计一致、后端共用；先读 [README](README.md) 与 [docs/android/PLAN.md](../../docs/android/PLAN.md)。
- 只改本目录；不要动 iOS 目录与 `services/`。需要共享的文案走 `apps/ios-conversation/Resources/UIStrings.json`（构建时同步，不复制），令牌以 iOS `CatTheme.swift` 为准（用 `scripts/export-theme-tokens.py` 对齐）。
- 版本只写在 `gradle/libs.versions.toml` 与 `version.properties`；`applicationId` 固定 `com.heydusk.mimi`。
- 界面里不得硬编码中文：用 `ui("…")`；`scripts/check-localization.py` 会拦。测试标签沿用 iOS 的 accessibilityIdentifier（如 `parenting.tab.today`）。
- 不要把密钥、keystore、`local.properties`、APK 或 `build/` 提交进来（`.gitignore` 已排除）。
- 提交前跑 `scripts/verify.sh`；它是本地唯一门禁（暂无 CI）。
