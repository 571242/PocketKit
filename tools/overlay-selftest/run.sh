#!/usr/bin/env bash
# Pure-JVM geometry self-check for the overlay edge ball. Not a device, WindowManager or touch verification.
set -u
PROJ="$(cd "$(dirname "$0")/../.." && pwd)"
JDK="${JDK:-/f/mlbuild/tools/jdk-17.0.20.1+1}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/f/mlbuild/sdk}}"
OUT="$PROJ/.scratch/overlay-selftest"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"
# android.jar is a stub: needed to resolve signatures on both compile and runtime classpath, never executed here.
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found at $ANDROID_JAR" >&2; exit 2; }
WJAR="$(cygpath -w "$ANDROID_JAR" 2>/dev/null || echo "$ANDROID_JAR")"
WOUT="$(cygpath -w "$OUT/classes" 2>/dev/null || echo "$OUT/classes")"
"$JDK/bin/javac.exe" -encoding UTF-8 -d "$OUT/classes" -cp "$WJAR" \
  "$PROJ/tools/overlay-selftest/stubs/cn/xianhua/pocketkit/automation/AutomationRuntime.java" \
  "$PROJ/app/src/main/java/cn/xianhua/pocketkit/automation/AutomationWindows.java" \
  "$PROJ/tools/overlay-selftest/cn/xianhua/pocketkit/automation/OverlayGeometrySelfTest.java" || exit $?
"$JDK/bin/java.exe" -cp "$WOUT;$WJAR" cn.xianhua.pocketkit.automation.OverlayGeometrySelfTest
