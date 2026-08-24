#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TAP_SCRIPT="$PROJECT_DIR/test/device/tap-image-widget.script"

restore_device() {
  adb shell cmd device_state state reset >/dev/null 2>&1 || true
  adb shell am force-stop com.miui.gallery >/dev/null 2>&1 || true
}
trap restore_device EXIT INT TERM

adb install -r "$APK"
adb shell am force-stop com.miui.fliphome
adb push "$TAP_SCRIPT" /data/local/tmp/tap-image-widget.script >/dev/null
adb shell cmd device_state state 0
sleep 5
adb logcat -c
adb shell monkey -f /data/local/tmp/tap-image-widget.script 1 >/dev/null
sleep 3

ACTIVITIES="$(adb shell dumpsys activity activities)"
LOGS="$(adb logcat -d -v brief)"
grep -q 'com.miui.gallery/.activity.ExternalPhotoPageActivity' <<<"$ACTIVITIES"
if grep -q 'ConfigProvider.*SecurityException\|Caller is not FlipHome' <<<"$LOGS"; then
  echo "Gallery launched, but media permission was denied" >&2
  exit 1
fi

echo "PASS: image widget opened Xiaomi Gallery and the provider reported no permission denial"
