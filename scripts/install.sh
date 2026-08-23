#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
  "$PROJECT_ROOT/scripts/build.sh"
fi

adb get-state >/dev/null
adb install -r "$APK"
sleep 2
adb shell am force-stop com.miui.fliphome
echo "Installed. FlipHome was stopped so LSPosed loads the updated module on next start."
