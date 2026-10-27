#!/usr/bin/env bash
# 构建环境：用法 `source apps/android-conversation/scripts/android-env.sh`（只设置当前 shell，不写任何文件）。
#
# 需要：
#   JDK 21         （AGP 9.4 / Gradle 9.7 / Kotlin 2.4 的工具链；JAVA_HOME 优先，其次 ~/jdk/jdk-21*，其次 PATH 上的 java 21）
#   Android SDK    （ANDROID_HOME 优先，其次 ~/android-sdk）。需要的组件：
#                   platforms;android-37.1（compileSdk 37.1，Compose 1.12 要求 ≥37）、platforms;android-36、
#                   build-tools;36.0.0、platform-tools、cmdline-tools;latest
# 不需要：Android Studio、模拟器、Google 账号、任何密钥。
#
# 装 SDK（一次性，已接受许可后）：
#   sdkmanager "platforms;android-37.1" "platforms;android-36" "build-tools;36.0.0" "platform-tools"
#
# 检查：source 之后会打印版本；缺项时返回非 0（但不退出你的 shell）。

_mimi_find_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then return 0; fi
  local c
  for c in "$HOME"/jdk/jdk-21* /usr/lib/jvm/*21* /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; return 0; fi
  done
  return 1
}

_mimi_find_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then return 0; fi
  local c
  for c in "$HOME/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$c/platforms" ]; then export ANDROID_HOME="$c"; return 0; fi
  done
  return 1
}

_mimi_status=0
if _mimi_find_jdk; then
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"
else
  echo "未找到 JDK 21：请安装并设置 JAVA_HOME" >&2; _mimi_status=1
fi
if _mimi_find_sdk; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  echo "ANDROID_HOME=$ANDROID_HOME"
  [ -d "$ANDROID_HOME/platforms/android-37.1" ] || { echo "缺 platforms;android-37.1（见脚本头部说明）" >&2; _mimi_status=1; }
else
  echo "未找到 Android SDK：请安装并设置 ANDROID_HOME" >&2; _mimi_status=1
fi
unset -f _mimi_find_jdk _mimi_find_sdk
return $_mimi_status 2>/dev/null || exit $_mimi_status
