#!/usr/bin/env bash
# 联网查询真实请求自检。
#
# 为什么单独一个脚本：real org.json 与 android.jar 里的 org.json 同名，
# classpath 顺序决定用哪一个 —— 必须让真实 jar 在前，且**完全不挂 android.jar**，
# 否则会加载到 stub 并抛 NoClassDefFoundError。
set -u
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit"
AJAR=/f/mlbuild/sdk/platforms/android-36/android.jar
OUT="$PROJ/.scratch/device/net"
export LC_ALL=C.UTF-8
# 临时试件统一放项目 .scratch，避免写到用户 Temp。
TMP="$PROJ/.scratch/device/tmp"
mkdir -p "$TMP"
TMP_WIN=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")
JAVA_OPTS_COMMON="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMP_WIN"
export TMPDIR_LOCAL=1

JSONJAR=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/org.json -name 'json-*.jar' 2>/dev/null | head -1)
if [ -z "$JSONJAR" ]; then
  echo "未找到 org.json jar（只影响本机自检，不影响 Android 构建）"
  exit 0
fi
JSONJAR_WIN=$(cygpath -w "$JSONJAR")
AJAR_WIN=$(cygpath -w "$AJAR")
OUT_WIN=$(cygpath -w "$OUT")

rm -rf "$OUT"; mkdir -p "$OUT"

# 编译时需要 android.jar（Ui/BaseToolActivity 引用 Android 类），运行时**不能**带上它。
# DeviceToolsActivity 会 import 整个 device 子包，所以整包编译；PinyinTool 是 DuilianTool 的依赖。
ICU=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/com.ibm.icu -name 'icu4j-*.jar' 2>/dev/null | head -1)
CPC="$AJAR:$JSONJAR"
[ -n "$ICU" ] && CPC="$CPC:$ICU"
FILES=("$SRC/BaseToolActivity.java" "$SRC/DeviceToolsActivity.java"
       "$SRC/core/SizeFmt.java" "$SRC/core/ToolException.java" "$SRC/core/PinyinTool.java" "$SRC/core/MiniJson.java")
while IFS= read -r f; do FILES+=("$f"); done < <(find "$SRC/device" -name '*.java' | sort)
"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 \
  -cp "$CPC" -d "$OUT" "${FILES[@]}" \
  "$PROJ/tools/device-selftest/NetworkQueryTest.java" 2>&1 | grep -v '^Note:' | sed 's/^/  /'
# javac 失败就别往下跑，否则会把"编译不过"误报成运行结果。
if [ ! -f "$OUT/cn/xianhua/pocketkit/device/NetworkQueryTest.class" ]; then
  echo "  编译失败，未运行联网自检。"
  exit 1
fi

echo "=== 运行（真实 org.json 在前，不带 android.jar）==="
"$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$OUT_WIN;$JSONJAR_WIN" \
  cn.xianhua.pocketkit.device.NetworkQueryTest
exit $?
