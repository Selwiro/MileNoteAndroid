#!/bin/bash
# 里程志 MileNote · Android 模拟器封装（headless）
# 用法：./dev-emulator.sh [start|stop|status]       默认 start
#
# 环境解析顺序：本机 ANDROID_HOME / local.properties（本脚本不读后者）→ TOOLCHAIN=/path/to/toolchain → local.env
# AVD 名默认 milenote，可用 MILENOTE_AVD=... 覆盖；本地没有该 AVD 时按下面「准备模拟器」创建。
#
# 为什么必须走这个脚本：
# - `-grpc 0`：默认 gRPC-JWT 会写临时目录，在只读/受限沙箱里会
#   `FATAL | Failed to create jwk directory` 退出；`-grpc-use-token` 无效。
# - `-no-window`：headless，不弹窗口；`-no-metrics`：跳过崩溃/指标同意弹窗。
# - `TMPDIR` 指向可写目录，且不要用管道启动（SIGPIPE 会杀掉 emulator）。
#
# 准备模拟器（首次）：
#   sdkmanager "platform-tools" "platforms;android-34" "system-images;android-34;google_apis;arm64-v8a" "emulator"
#   avdmanager create avd -n milenote -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_5
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -f "$ROOT/local.env" ] && . "$ROOT/local.env"

if [ -n "${TOOLCHAIN:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  export ANDROID_HOME="$TOOLCHAIN/sdk"
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "!! 未设置 ANDROID_HOME（或 TOOLCHAIN）；见脚本头部「准备模拟器」" >&2; exit 1; }
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -n "${TOOLCHAIN:-}" ] && [ -z "${ANDROID_AVD_HOME:-}" ] && [ -d "$TOOLCHAIN/avd" ]; then
  export ANDROID_AVD_HOME="$TOOLCHAIN/avd"
fi

ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVD="${MILENOTE_AVD:-milenote}"
LOG="${EMU_LOG:-${TMPDIR:-/tmp}/milenote-emulator.log}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"

[ -x "$ADB" ] || { echo "!! 找不到 adb：$ADB（安装 platform-tools 或设置 ANDROID_HOME）" >&2; exit 1; }

emulator_running() {
  "$ADB" devices | grep -Eq '^emulator-[0-9]+[[:space:]]+device$'
}

boot_completed() {
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

case "${1:-start}" in
  start)
    [ -x "$EMU" ] || { echo "!! 找不到 emulator：$EMU（sdkmanager \"emulator\"，或用 Android Studio 创建 AVD）" >&2; exit 1; }

    if emulator_running && boot_completed; then
      echo "==> 模拟器已在线（复用，不重复启动 qemu）"
      "$ADB" devices
      exit 0
    fi

    if emulator_running; then
      echo "==> 模拟器进程已在，等待 boot 完成..."
    else
      echo "==> 启动 AVD '$AVD'（headless，日志：$LOG）"
      export TMPDIR="${TMPDIR:-/tmp}/milenote-emulator-tmp"
      mkdir -p "$TMPDIR"
      nohup "$EMU" -avd "$AVD" -no-window -no-audio -no-snapshot -no-boot-anim \
        -gpu swiftshader_indirect -grpc 0 -no-metrics > "$LOG" 2>&1 &
    fi

    for _ in $(seq 1 "$BOOT_TIMEOUT"); do
      if emulator_running && boot_completed; then
        echo "==> 模拟器就绪"
        "$ADB" devices
        exit 0
      fi
      sleep 1
    done

    echo "!!! 模拟器 ${BOOT_TIMEOUT}s 内未就绪，日志尾部：" >&2
    tail -20 "$LOG" >&2 || true
    echo "提示：若出现 'Failed to create jwk directory' 说明漏了 -grpc 0；若 AVD '$AVD' 不存在，见脚本头部「准备模拟器」。" >&2
    exit 1
    ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true
    echo "==> 已请求停止模拟器"
    ;;
  status)
    "$ADB" devices
    ;;
  *)
    echo "用法：$0 [start|stop|status]" >&2
    exit 2
    ;;
esac
