#!/usr/bin/env bash
# 设备子代理自检：编译检查 + 五组测试（不调用 Gradle，父会话统一负责 Gradle，避免并发）。
#
# 这里是语法/API 存在性检查 + 算法/协议自检 + 对真实公开接口的实际请求，
# **不代表真机运行验证**。
#
# 用法：bash tools/device-selftest/run.sh
#
# 注意两个本机坑（都已在此脚本内处理）：
#  1. java.exe / javac.exe 是 Windows 程序，运行时 classpath 必须用 Windows 路径形式，
#     否则 android.jar 等于没挂上，验证 Android 类时会抛 NoClassDefFoundError。
#  2. `cmd | sed` 的退出码是 sed 的退出码，所以不能用 `cmd | sed || fail=1` 判断失败，
#     必须取 PIPESTATUS[0]，否则"测试进程崩溃"会被误报成通过。
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1
AJAR=/f/mlbuild/sdk/platforms/android-36/android.jar
AJAR_WIN=$(cygpath -w "$AJAR" 2>/dev/null || echo "$AJAR")
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit"
OUT="$PROJ/.scratch/device/verify"
# 临时试件目录：FTP 测试用 Files.createTempDirectory，若不指定会落到用户 Temp。
# 统一指到项目 .scratch 下，避免在系统盘留测试残留。
TMP="$PROJ/.scratch/device/tmp"
export LC_ALL=C.UTF-8

mkdir -p "$OUT" "$TMP"
# 让所有被测 JVM 的 java.io.tmpdir 都指向项目内目录（含子进程）。
JAVA_OPTS_COMMON="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")"
export JAVA_OPTS_COMMON TMP

# core/PinyinTool 用到 icu4j（父会话已加入 app/build.gradle），编译检查需要它在 classpath 上。
ICU=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/com.ibm.icu -name 'icu4j-*.jar' 2>/dev/null | head -1)
CP="$AJAR"
[ -n "$ICU" ] && CP="$CP:$ICU"

fail=0
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

echo "=== 1/12 编译检查（device 子包 + DeviceToolsActivity + 公共基类 + 我实际用到的 core）==="
# 只编译设备模块真正依赖的 core 文件。core/ 整体归父会话所有，其中其它子代理
# 正在编写的文件可能暂时编译不过，那不应该掩盖我这里的检查结果。
# PinyinTool 是 DuilianTool（096 平仄判定）的依赖，必须一起编。
FILES=("$SRC/BaseToolActivity.java" "$SRC/DeviceToolsActivity.java"
       "$SRC/core/SizeFmt.java" "$SRC/core/ToolException.java" "$SRC/core/PinyinTool.java" "$SRC/core/MiniJson.java")
while IFS= read -r f; do FILES+=("$f"); done < <(find "$SRC/device" -name '*.java' | sort)
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -source 17 -target 17 \
  -classpath "$CP" -d "$OUT/classes" "${FILES[@]}" 2>&1 | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1
echo "  编译 ${#FILES[@]} 个文件，产出 $(find "$OUT/classes" -name '*.class' 2>/dev/null | wc -l) 个 class"

echo
echo "=== 2/12 传感器与测量算法自检（SensorMath）==="
rm -rf "$OUT/t1"; mkdir -p "$OUT/t1"
"$JDK/bin/javac.exe" -nowarn -encoding UTF-8 -d "$OUT/t1" \
  "$SRC/device/SensorMath.java" "$PROJ/tools/device-selftest/DeviceSelfTest.java" 2>&1 | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1
if [ -f "$OUT/t1/DeviceSelfTest.class" ]; then
  "$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$(win "$OUT/t1")" DeviceSelfTest | sed 's/^/  /'
  [ "${PIPESTATUS[0]}" != "0" ] && fail=1
else
  echo "  编译失败，未运行本组自检。"; fail=1
fi

echo
echo "=== 3/12 文件规则、局域网地址与 QQ 号码校验自检 ==="
rm -rf "$OUT/t2"; mkdir -p "$OUT/t2"
"$JDK/bin/javac.exe" -nowarn -encoding UTF-8 -cp "$AJAR" -d "$OUT/t2" \
  "$SRC/device/FileRules.java" "$SRC/device/WifiAddress.java" "$SRC/device/QqChatTool.java" \
  "$SRC/device/Ui.java" "$SRC/BaseToolActivity.java" "$SRC/core/SizeFmt.java" \
  "$PROJ/tools/device-selftest/DeviceLogicTest.java" 2>&1 | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1
if [ -f "$OUT/t2/DeviceLogicTest.class" ]; then
  "$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$(win "$OUT/t2");$AJAR_WIN" DeviceLogicTest | sed 's/^/  /'
  [ "${PIPESTATUS[0]}" != "0" ] && fail=1
else
  echo "  编译失败，未运行本组自检。"; fail=1
fi

echo
echo "=== 4/12 FTP 服务端真实协议测试（含路径穿越防护、导入去重）==="
rm -rf "$OUT/t3"; mkdir -p "$OUT/t3"
# FtpServerTest 还用到 FileRules.uniqueTarget（导入去重规则），一并编译。
"$JDK/bin/javac.exe" -nowarn -encoding UTF-8 -d "$OUT/t3" \
  "$SRC/device/FtpServer.java" "$SRC/device/FileRules.java" \
  "$PROJ/tools/device-selftest/FtpServerTest.java" 2>&1 | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1
if [ -f "$OUT/t3/FtpServerTest.class" ]; then
  "$JDK/bin/java.exe" $JAVA_OPTS_COMMON -cp "$(win "$OUT/t3")" FtpServerTest | sed 's/^/  /'
  [ "${PIPESTATUS[0]}" != "0" ] && fail=1
else
  echo "  编译失败，未运行本组自检。"; fail=1
fi

echo
echo "=== 5/12 联网查询真实请求测试 ==="
echo "（单独脚本：real org.json 与 android.jar 里的 stub 同名，必须隔离运行）"
bash "$PROJ/tools/device-selftest/network-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 6/12 目录合同测试（反射 RootActivity 的 supports/limitation）==="
echo "（父会话的 ToolRegistry 就是这样调的；只统计各 helper 会得出假结论）"
bash "$PROJ/tools/device-selftest/contract-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 7/12 第3批纯逻辑自检（RSS/平仄/词条检索/许可判定/对联生成）==="
bash "$PROJ/tools/device-selftest/batch3-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 8/12 裸引号扫描（中文里夹 ASCII 双引号会导致编译失败）==="
bash "$PROJ/tools/device-selftest/check-quotes.sh" | tail -2 | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 9/12 上海离线垃圾分类真实资产与边界测试 ==="
bash "$PROJ/tools/device-selftest/garbage-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 10/12 离线词典真实原文显示与同反义字段测试 ==="
bash "$PROJ/tools/device-selftest/offline-lexicon-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 11/12 GeoNames中国邮编实际原数据测试 ==="
bash "$PROJ/tools/device-selftest/postal-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 12/12 离线词典真实库安装及失败安全测试 ==="
bash "$PROJ/tools/device-selftest/installer-test.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
if [ "$fail" = "0" ]; then
  echo "全部通过。注意：以上是编译检查、算法/协议自检与真实接口请求；未在真机上运行验证。"
  echo "网络自检中如有『未验证』项，见 network-test.sh 输出末尾的说明。"
else
  echo "存在失败项，见上方输出。"
fi
exit $fail
