#!/usr/bin/env bash
# 目录合同测试：反射根 Activity 的 supports(String)/limitation(String)。
#
# 关键点：必须对 **DeviceToolsActivity** 反射，而不是各 helper 类 ——
# ToolRegistry 就是这么调的，只统计 helper 会得出"全部可用"的假结论。
#
# 运行需要编译产物。优先用 Gradle 的 classes 目录（父会话构建后就在那），
# 没有就现场用 javac 编一份（只编 device 范围，不需要 Gradle）。
set -u
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit"
AJAR=/f/mlbuild/sdk/platforms/android-36/android.jar
AJAR_WIN=$(cygpath -w "$AJAR" 2>/dev/null || echo "$AJAR")
GRADLE_CLASSES="$PROJ/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
OUT="$PROJ/.scratch/device/contract"
export LC_ALL=C.UTF-8
# 临时试件统一放项目 .scratch，避免写到用户 Temp。
TMP="$PROJ/.scratch/device/tmp"
mkdir -p "$TMP"
TMP_WIN=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")
JAVA_OPTS_COMMON="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMP_WIN"
export TMPDIR_LOCAL=1

win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

CLASSES=""
USE_GRADLE=0
if [ -d "$GRADLE_CLASSES" ] && [ -f "$GRADLE_CLASSES/cn/xianhua/pocketkit/DeviceToolsActivity.class" ]; then
  # 关键：Gradle 产物可能是旧的（源码改过但没重新构建）。用旧产物测会得出
  # "缺少 supports 方法" 的过时结论，所以先比时间戳，源码更新就用现编的。
  if [ "$SRC/DeviceToolsActivity.java" -nt "$GRADLE_CLASSES/cn/xianhua/pocketkit/DeviceToolsActivity.class" ]; then
    echo "Gradle 产物早于当前源码，改用现场编译（避免对旧 class 下结论）"
  else
    USE_GRADLE=1
    CLASSES="$GRADLE_CLASSES"
    echo "使用 Gradle 编译产物：$CLASSES"
  fi
fi

if [ "$USE_GRADLE" = "0" ]; then
  echo "现场编译 device 范围 + core 依赖（不调用 Gradle，不影响父会话构建）"
  ICU=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/com.ibm.icu -name 'icu4j-*.jar' 2>/dev/null | head -1)
  JSONJAR=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/org.json -name 'json-*.jar' 2>/dev/null | head -1)
  CP="$AJAR"
  [ -n "$ICU" ] && CP="$CP:$ICU"
  [ -n "$JSONJAR" ] && CP="$CP:$JSONJAR"
  rm -rf "$OUT/app"; mkdir -p "$OUT/app"
  FILES=("$SRC/ToolUi.java" "$SRC/BaseToolActivity.java" "$SRC/DeviceToolsActivity.java" "$SRC/core/SizeFmt.java"
         "$SRC/core/ToolException.java" "$SRC/core/PinyinTool.java" "$SRC/core/MiniJson.java")
  while IFS= read -r f; do FILES+=("$f"); done < <(find "$SRC/device" -name '*.java' | sort)
  "$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -cp "$CP" -d "$OUT/app" "${FILES[@]}" \
    2>&1 | grep -v '^Note:' | sed 's/^/  /'
  if [ ! -f "$OUT/app/cn/xianhua/pocketkit/DeviceToolsActivity.class" ]; then
    echo "编译失败，无法运行目录合同测试。"; exit 1
  fi
  CLASSES="$OUT/app"
fi

# 测试类本身要先编译出来（Gradle 不会编 tools/ 下的文件）。
rm -rf "$OUT/test"; mkdir -p "$OUT/test"
"$JDK/bin/javac.exe" -nowarn -encoding UTF-8 -cp "$AJAR:$(win "$CLASSES")" -d "$OUT/test" \
  "$PROJ/tools/device-selftest/RegistryContractTest.java" 2>&1 | sed 's/^/  /'
if [ ! -f "$OUT/test/cn/xianhua/pocketkit/RegistryContractTest.class" ]; then
  echo "测试类编译失败。"; exit 1
fi

CP_RUN="$(win "$OUT/test");$(win "$CLASSES");$AJAR_WIN"
echo "=== 运行目录合同测试（反射 DeviceToolsActivity）==="
"$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$CP_RUN" cn.xianhua.pocketkit.RegistryContractTest
exit $?
