#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ -d "$ANDROID_STUDIO_JDK" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JDK"
fi

cd "$PROJECT_ROOT"
./gradlew assembleDebug lintDebug
echo "APK: $PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
