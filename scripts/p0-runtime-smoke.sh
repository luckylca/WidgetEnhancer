#!/usr/bin/env bash
set -euo pipefail

restore_state() {
  adb shell cmd device_state state reset >/dev/null 2>&1 || true
}
trap restore_state EXIT INT TERM

adb logcat -c
adb shell cmd device_state state 0
sleep 5
adb shell content call --uri content://com.lucky.mixflipouter.provider --method get_health
adb logcat -d -v brief | grep -Ei 'MixFlipCustom|FlipWidgetManager|mixflip_custom_widget|FATAL EXCEPTION' || true
