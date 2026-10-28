#!/usr/bin/env bash
# 本地一键校验（Linux/macOS，不需要设备或模拟器）：
#   1. 主题令牌快照与 iOS CatTheme.swift 一致   2. 文案目录与 Kotlin 字面量检查
#   3. 全部 JVM 单测（含 Robolectric 的 Compose UI / Hilt 测试）   4. 构建 debug APK 并编译设备端测试（androidTest，不运行）
#   5. 汇总各模块测试数（scripts/test-counts.py）
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
  # assembleDebugAndroidTest：真机 Keystore 用例（core/data/src/androidTest）与识别器可用性用例（core/voice/src/androidTest）只编译，箱子上没有可用的模拟器去运行它。
  ./gradlew test assembleDebug :core:data:assembleDebugAndroidTest :core:voice:assembleDebugAndroidTest
  ls -la app/build/outputs/apk/debug/app-debug.apk
fi
python3 scripts/test-counts.py
echo "PASS android verify"
