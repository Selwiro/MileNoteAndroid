#!/bin/bash
# 开发用 Gradle 封装：统一入口，默认使用本机环境（JAVA_HOME / ANDROID_HOME 或 local.properties）。
# 可选：设置 TOOLCHAIN=<目录> 时，从 <目录>/{jdk-*,sdk,gradle-home,android-user} 取环境（离线/容器环境用）。
# 可选：仓库根放一个不入库的 local.env，脚本会自动 source，用来固化本机路径。
# 用法：./dev-gradle.sh :domain:test --console=plain
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/local.env" ] && . "$ROOT/local.env"

if [ -n "${TOOLCHAIN:-}" ]; then
  # 自动找 JDK 17（macOS 是 <jdk>/Contents/Home，Linux 是 <jdk>）
  if [ -z "${JAVA_HOME:-}" ]; then
    for d in "$TOOLCHAIN"/jdk-*; do
      [ -d "$d" ] || continue
      if [ -x "$d/Contents/Home/bin/java" ]; then JAVA_HOME="$d/Contents/Home"; break; fi
      if [ -x "$d/bin/java" ]; then JAVA_HOME="$d"; break; fi
    done
    export JAVA_HOME
  fi
  export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/sdk}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLCHAIN/gradle-home}"
  export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$TOOLCHAIN/android-user}"
fi

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "!! 没找到 JDK：请安装 JDK 17 并设置 JAVA_HOME（或让 Android Studio 生成 local.properties）" >&2
  exit 1
fi

# 本机工具链里已有 Gradle 时直接复用（免去 wrapper 首次下载）；否则走仓库内 wrapper。
if [ -n "${TOOLCHAIN:-}" ]; then
  GRADLE_VERSION="$(ls -d "$TOOLCHAIN"/gradle-*/bin/gradle 2>/dev/null \
    | sed 's#.*/gradle-##; s#/bin/gradle##' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
  if [ -n "$GRADLE_VERSION" ] && [ -x "$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    exec "$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" "$@"
  fi
fi

exec "$ROOT/gradlew" "$@"
