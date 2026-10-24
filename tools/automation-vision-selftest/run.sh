#!/usr/bin/env bash
# B 视觉纯 JVM 自检：算法/边界/全屏规模；不代表 Android 真机或 ML Kit 推理已验证。
set -u
PROJ="$(cd "$(dirname "$0")/../.." && pwd)"
JDK="/f/mlbuild/tools/jdk-17.0.20.1+1"
OUT="$PROJ/.scratch/automation-vision-selftest"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"
"$JDK/bin/javac.exe" -encoding UTF-8 -d "$OUT/classes"   "$PROJ/tools/automation-vision-selftest/android/graphics/Bitmap.java"   "$PROJ/tools/automation-vision-selftest/android/graphics/Rect.java"   "$PROJ/tools/automation-vision-selftest/cn/xianhua/pocketkit/core/automation/Match.java"   "$PROJ/app/src/main/java/cn/xianhua/pocketkit/core/automation/TemplateMatcher.java"   "$PROJ/tools/automation-vision-selftest/TemplateMatcherSelfTest.java" || exit $?
"$JDK/bin/java.exe" -cp "$(cygpath -w "$OUT/classes" 2>/dev/null || echo "$OUT/classes")" TemplateMatcherSelfTest
