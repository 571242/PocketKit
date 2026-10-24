#!/usr/bin/env bash
set -euo pipefail
PROJ=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$PROJ/.scratch/manager-permission-selftest/classes"
JDK=${JDK:-/f/mlbuild/tools/jdk-17.0.20.1+1}
GRADLE=${GRADLE:-/f/mlbuild/tools/gradle-8.14.3/bin/gradle}
mkdir -p "$OUT"
rm -rf "$OUT"/*
"$JDK/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 -d "$OUT" \
  "$PROJ/app/src/main/java/cn/xianhua/pocketkit/ManagerAlgorithms.java" \
  "$PROJ/tools/manager-permission-selftest/ManagerPermissionSelfTest.java"
CP=$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")
ROOT=$(cygpath -w "$PROJ" 2>/dev/null || echo "$PROJ")
"$JDK/bin/java.exe" -Dfile.encoding=UTF-8 -cp "$CP" cn.xianhua.pocketkit.ManagerPermissionSelfTest "$ROOT"

if [[ ${1:-} != --android ]]; then
  echo 'Android instrumentation not run; pass --android with one adb device connected.'
  exit 0
fi
command -v adb >/dev/null || { echo 'adb is required for --android' >&2; exit 2; }
[[ $(adb devices | awk 'NR>1 && $2=="device"{n++} END{print n+0}') == 1 ]] || { echo 'exactly one authorized adb device is required' >&2; exit 2; }
"$GRADLE" -p "$PROJ" -I "$PROJ/tools/manager-permission-selftest/instrumentation.init.gradle" :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r "$PROJ/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$PROJ/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb shell am instrument -w cn.xianhua.pocketkit.test/cn.xianhua.pocketkit.ManagerPermissionInstrumentation
