#!/bin/bash
# 里程志 MileNote · Android 构建脚本
# 用法：./build.sh [debug|release] [--allow-unsigned]       默认 release
#
# 产物：${MILENOTE_OUT_DIR:-<仓库>/dist}/milenote-<versionName>-android.apk（release，附 .sha256）
#       ${MILENOTE_OUT_DIR:-<仓库>/dist}/milenote-<versionName>-android-debug-<时间戳>.apk（debug）
#
# 未签名时**不会**占用正式文件名，也不生成 .sha256（1.0 审查 #28）：
# 只能归档成 ...-android-unsigned-<时间戳>.apk，且必须显式 --allow-unsigned；
# 设了 MILENOTE_EXPECT_CERT_SHA256 时还会比对签名证书指纹，不一致直接失败。
# 环境：走 ./dev-gradle.sh（本机 JAVA_HOME/ANDROID_HOME，或 TOOLCHAIN=...，或 local.env）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/local.env" ] && . "$ROOT/local.env"
VARIANT="${1:-release}"
ALLOW_UNSIGNED=0
for arg in "$@"; do
  case "$arg" in
    --allow-unsigned) ALLOW_UNSIGNED=1 ;;
  esac
done
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

SIGNED=1
if [ "$VARIANT" = "release" ]; then
  # 有签名配置出 app-release.apk，没有则出 app-release-unsigned.apk
  APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
  if [ ! -f "$APK" ]; then
    APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
    SIGNED=0
  fi
else
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi

[ -f "$APK" ] || { echo "!! 构建成功但没找到 APK：$APK" >&2; exit 1; }

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M)"
if [ "$VARIANT" = "release" ] && [ "$SIGNED" = "0" ]; then
  if [ "$ALLOW_UNSIGNED" != "1" ]; then
    echo "!! 这次 release 没有签名（缺少 keystore/keystore.properties 或 MILENOTE_* 环境变量）。" >&2
    echo "   未签名包不能使用正式文件名：加 --allow-unsigned 只会归档成 ...-unsigned-<时间戳>.apk，且不生成 .sha256。" >&2
    exit 1
  fi
  DEST="$OUT_DIR/milenote-${VERSION}-android-unsigned-${STAMP}.apk"
elif [ "$VARIANT" = "release" ]; then
  DEST="$OUT_DIR/milenote-${VERSION}-android.apk"
else
  DEST="$OUT_DIR/milenote-${VERSION}-android-debug-${STAMP}.apk"
fi
cp "$APK" "$DEST"

echo "==> 已归档：$DEST"

if [ "$VARIANT" = "release" ] && [ "$SIGNED" = "1" ]; then
  # 签名证书指纹：能读到就打印（发布前与旧版比对），设了期望值时不一致直接失败。
  if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then ANDROID_HOME="$ANDROID_SDK_ROOT"; fi
  APKSIGNER="$(ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
  if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
    CERT_LINE="$("$APKSIGNER" verify --print-certs "$DEST" 2>/dev/null | grep -i 'SHA-256 digest' | head -1 || true)"
    echo "==> 签名：${CERT_LINE:-未取到证书指纹}"
    EXPECT="${MILENOTE_EXPECT_CERT_SHA256:-}"
    if [ -n "$EXPECT" ]; then
      if ! printf '%s' "$CERT_LINE" | tr 'A-Z' 'a-z' | grep -q "$(printf '%s' "$EXPECT" | tr 'A-Z' 'a-z' | tr -d ':')"; then
        echo "!! 签名证书指纹与 MILENOTE_EXPECT_CERT_SHA256 不一致，已停止归档流程" >&2
        exit 1
      fi
    fi
  else
    echo "!! 没找到 apksigner，跳过证书指纹校验（发布前请手工跑一次 apksigner verify --print-certs）" >&2
  fi

  if command -v shasum >/dev/null 2>&1; then
    (cd "$OUT_DIR" && shasum -a 256 "$(basename "$DEST")" > "$(basename "$DEST").sha256")
  else
    (cd "$OUT_DIR" && sha256sum "$(basename "$DEST")" > "$(basename "$DEST").sha256")
  fi
  echo "==> sha256：$(cat "$DEST.sha256")"
fi
