#!/usr/bin/env bash
set -euo pipefail

adb shell am start \
  -a android.intent.action.MAIN \
  -c org.lsposed.manager.LAUNCH_MANAGER \
  -d 'module://com.lucky.mixflipouter:0/' \
  -n com.android.shell/.BugreportWarningActivity
