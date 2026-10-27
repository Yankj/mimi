#!/usr/bin/env bash
# 本地一键校验（Linux/macOS，不需要设备或模拟器）：
#   1. 主题令牌快照与 iOS CatTheme.swift 一致   2. 文案目录与 Kotlin 字面量检查
#   3. 全部 JVM 单测（含 Robolectric 的 Compose UI 测试）   4. 构建 debug APK
# 用法：apps/android-conversation/scripts/verify.sh [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

# shellcheck source=android-env.sh
source scripts/android-env.sh

python3 scripts/export-theme-tokens.py --check
python3 scripts/check-localization.py

if [ "${1:-}" = "--no-build" ]; then
  ./gradlew test
else
  ./gradlew test assembleDebug
  ls -la app/build/outputs/apk/debug/app-debug.apk
fi
echo "PASS android verify"
