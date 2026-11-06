#!/bin/bash
# 里程志 MileNote · Android 构建脚本
# 用法：./build.sh [debug|release]       默认 release
#
# 产物：${MILENOTE_OUT_DIR:-<仓库>/dist}/milenote-<versionName>-android.apk（release，附 .sha256）
#       ${MILENOTE_OUT_DIR:-<仓库>/dist}/milenote-<versionName>-android-debug-<时间戳>.apk（debug）
# 环境：走 ./dev-gradle.sh（本机 JAVA_HOME/ANDROID_HOME，或 TOOLCHAIN=...，或 local.env）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/local.env" ] && . "$ROOT/local.env"
VARIANT="${1:-release}"
OUT_DIR="${MILENOTE_OUT_DIR:-$ROOT/dist}"

case "$VARIANT" in
  release) TASK=":app:assembleRelease"; ;;
  debug)   TASK=":app:assembleDebug"; ;;
  *) echo "用法：$0 [debug|release]" >&2; exit 2 ;;
esac

# 归档名用 versionName：release 直接作为 GitHub Release 资产名，debug 另带时间戳区分本机重复构建。
VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || { echo "!! 无法从 app/build.gradle.kts 读取 versionName" >&2; exit 1; }

# 只读 git 信息，用于把 APK 对回提交；不在仓库里就跳过
if GIT_SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null)"; then
  GIT_DIRTY=""
  if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
    GIT_DIRTY=" (有未提交改动)"
  fi
  echo "==> git: $GIT_SHA$GIT_DIRTY"
fi

echo "==> $TASK"
"$ROOT/dev-gradle.sh" "$TASK" --console=plain

if [ "$VARIANT" = "release" ]; then
  # 有签名配置出 app-release.apk，没有则出 app-release-unsigned.apk
  APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
  [ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
else
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

[ -f "$APK" ] || { echo "!! 构建成功但没找到 APK：$APK" >&2; exit 1; }

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M)"
if [ "$VARIANT" = "release" ]; then
  DEST="$OUT_DIR/milenote-${VERSION}-android.apk"
else
  DEST="$OUT_DIR/milenote-${VERSION}-android-debug-${STAMP}.apk"
fi
cp "$APK" "$DEST"

echo "==> 已归档：$DEST"
if [ "$VARIANT" = "release" ]; then
  if command -v shasum >/dev/null 2>&1; then
    (cd "$OUT_DIR" && shasum -a 256 "$(basename "$DEST")" > "$(basename "$DEST").sha256")
  else
    (cd "$OUT_DIR" && sha256sum "$(basename "$DEST")" > "$(basename "$DEST").sha256")
  fi
  echo "==> sha256：$(cat "$DEST.sha256")"
  case "$APK" in
    *unsigned*) echo "!! 未提供发布签名（keystore/keystore.properties 或 MILENOTE_* 环境变量），产物为未签名 APK" >&2 ;;
  esac
fi
