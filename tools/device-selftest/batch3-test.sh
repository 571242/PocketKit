#!/usr/bin/env bash
# 第 3 批纯逻辑自检（RSS 解析、平仄规则、词条检索、许可判定、新闻来源）。
# 编译整个 device 包 + 它依赖的 core，避免逐文件挑依赖挑漏。
set -u
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit"
AJAR=/f/mlbuild/sdk/platforms/android-36/android.jar
AJAR_WIN=$(cygpath -w "$AJAR" 2>/dev/null || echo "$AJAR")
OUT="$PROJ/.scratch/device/b3"
export LC_ALL=C.UTF-8
# 临时试件统一放项目 .scratch，避免写到用户 Temp。
TMP="$PROJ/.scratch/device/tmp"
mkdir -p "$TMP"
TMP_WIN=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")
JAVA_OPTS_COMMON="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMP_WIN"
export TMPDIR_LOCAL=1

rm -rf "$OUT"; mkdir -p "$OUT"

JSONJAR=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/org.json -name 'json-*.jar' 2>/dev/null | head -1)
ICU=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/com.ibm.icu -name 'icu4j-*.jar' 2>/dev/null | head -1)
CP="$AJAR"
[ -n "$JSONJAR" ] && CP="$CP:$JSONJAR"
[ -n "$ICU" ] && CP="$CP:$ICU"

FILES=("$SRC/ToolUi.java" "$SRC/BaseToolActivity.java" "$SRC/DeviceToolsActivity.java"
       "$SRC/core/SizeFmt.java" "$SRC/core/ToolException.java" "$SRC/core/PinyinTool.java" "$SRC/core/MiniJson.java")
while IFS= read -r f; do FILES+=("$f"); done < <(find "$SRC/device" -name '*.java' | sort)

"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "${FILES[@]}" "$PROJ/tools/device-selftest/Batch3LogicTest.java" \
  "$PROJ/tools/device-selftest/DuilianGenTest.java" \
  2>&1 | grep -v '^Note:' | sed 's/^/  /'
if [ ! -f "$OUT/Batch3LogicTest.class" ]; then
  echo "  编译失败，未运行本组自检。"
  exit 1
fi

CP_RUN="$(cygpath -w "$OUT");$AJAR_WIN"
[ -n "$JSONJAR" ] && CP_RUN="$CP_RUN;$(cygpath -w "$JSONJAR")"
rc=0
"$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$CP_RUN" Batch3LogicTest || rc=1
echo
echo "  --- 对联生成器（096 生成路径）---"
if [ -f "$OUT/DuilianGenTest.class" ]; then
  "$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$CP_RUN" DuilianGenTest || rc=1
else
  echo "  对联生成器自检编译失败"
  rc=1
fi
exit $rc
