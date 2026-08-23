#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$PROJECT_ROOT/test/logs/diagnostics-$STAMP"
mkdir -p "$REPORT_DIR"

adb shell getprop > "$REPORT_DIR/getprop.txt"
adb shell dumpsys device_state > "$REPORT_DIR/device-state.txt"
adb shell dumpsys display > "$REPORT_DIR/display.txt"
adb shell dumpsys package com.miui.fliphome > "$REPORT_DIR/fliphome-package.txt"
adb shell dumpsys package com.lucky.mixflipouter > "$REPORT_DIR/module-package.txt"
adb shell content call --uri content://com.lucky.mixflipouter.provider --method get_health \
  > "$REPORT_DIR/hook-health.txt" 2>&1 || true
adb logcat -d -v threadtime > "$REPORT_DIR/logcat.txt"
adb logcat -d -v threadtime | grep -Ei 'MixFlipCustom|FlipWidgetManager|FlipMaMl|FATAL EXCEPTION' \
  > "$REPORT_DIR/relevant-logcat.txt" || true

echo "$REPORT_DIR"
