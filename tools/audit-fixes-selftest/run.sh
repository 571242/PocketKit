#!/usr/bin/env bash
# 审计修复自检（docs/critical-audit.md #2 #3 #4 #5）。
#
#   #2 Base64 输入上限   media/ImageConvertTool.decodeBase64
#   #3 LocalStore 静默改写换行（含新旧记录格式兼容） local/LocalStore
#   #4 FTP accept 遇异常即退出（服务假活） device/FtpServer.acceptLoop
#   #5 FTP 认证无限次尝试          device/FtpServer PASS
#
# 只编译被测到的真实源码，**不调用 Gradle** —— 主会话统一负责 Gradle。
#
# 两个本机坑（同 tools/device-selftest/run.sh）：
#  1. javac/java 是 Windows 程序，classpath 必须是 Windows 路径，否则 android.jar 静默失效。
#  2. `cmd | sed` 的退出码是 sed 的，判失败必须取 PIPESTATUS[0]。
set -u
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1
AJAR=/f/mlbuild/sdk/platforms/android-36/android.jar
AJAR_WIN=$(cygpath -w "$AJAR")
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit"
OUT="$PROJ/.scratch/audit-fixes/verify"
LIB="$PROJ/.scratch/audit-fixes/libs"
TMP="$PROJ/.scratch/audit-fixes/tmp"
export LC_ALL=C.UTF-8
mkdir -p "$OUT" "$LIB" "$TMP"
JAVA_OPTS="-Dfile.encoding=UTF-8 -Djava.io.tmpdir=$(cygpath -w "$TMP")"
win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
fail=0

# media/ImageIo 依赖 androidx.exifinterface：从 Gradle 缓存里的 aar 取 classes.jar
EXIF_AAR=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/androidx.exifinterface \
  -name 'exifinterface-*.aar' 2>/dev/null | head -1)
EXIF_JAR="$LIB/exifinterface.jar"
if [ -n "$EXIF_AAR" ] && [ ! -f "$EXIF_JAR" ]; then
  rm -rf "$LIB/exif"; mkdir -p "$LIB/exif"
  ( cd "$LIB/exif" && unzip -q "$EXIF_AAR" classes.jar && mv classes.jar "$EXIF_JAR" )
  rm -rf "$LIB/exif"
fi
# core/PinyinTool 用 icu4j
ICU=$(find /c/Users/admin/.gradle/caches/modules-2/files-2.1/com.ibm.icu \
  -name 'icu4j-*.jar' 2>/dev/null | head -1)
CP="$AJAR_WIN"
[ -f "$EXIF_JAR" ] && CP="$CP;$(win "$EXIF_JAR")"
[ -n "$ICU" ] && CP="$CP;$(win "$ICU")"

echo "=== 1/3 编译被测源码（真实编译检查，50+ 个 core 文件一并编）==="
# 只编被测到的类，不整包编 media/：OcrTool 等依赖 ML Kit，与本次修复无关，
# 拿不到那些 jar 时整包编必然失败，只会掩盖这里的结果。
FILES=()
for f in media/ImageConvertTool media/ImageCompressTool media/MediaIo media/ImageIo \
         media/ImgSizing media/EditCore media/Cancel media/Host media/Tool local/LocalStore; do
  FILES+=("$SRC/$f.java")
done
FILES+=("$SRC/BaseToolActivity.java" "$SRC/ToolUi.java")
# core 整包一起编：LedgerBook 依赖 MoneyTool、CsvSafe 等，单抽几个编不过
while IFS= read -r f; do FILES+=("$f"); done < <(find "$SRC/core" -name '*.java' | sort)
rm -rf "$OUT/main"; mkdir -p "$OUT/main"
"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -source 17 -target 17 \
  -classpath "$CP" -d "$OUT/main" "${FILES[@]}" 2>&1 | sed 's/^/  /'
if [ "${PIPESTATUS[0]}" != "0" ]; then
  echo "  编译失败，后续自检跳过。"; exit 1
fi
echo "  编译 ${#FILES[@]} 个文件通过"

echo
echo "=== 2/3 #2 Base64 上限 + #3 LocalStore 新旧格式兼容（自检 22 项）==="
rm -rf "$OUT/t"; mkdir -p "$OUT/t"
"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -d "$OUT/t" \
  -classpath "$(win "$OUT/main");$CP" \
  "$PROJ/tools/audit-fixes-selftest/AuditFixesSelfTest.java" 2>&1 | sed 's/^/  /'
if [ "${PIPESTATUS[0]}" != "0" ]; then
  echo "  自检编译失败。"; exit 1
fi
"$JDK/bin/java.exe" $JAVA_OPTS -cp "$(win "$OUT/t");$(win "$OUT/main");$CP" \
  AuditFixesSelfTest | sed 's/^/  /'
[ "${PIPESTATUS[0]}" != "0" ] && fail=1

echo
echo "=== 3/3 #4 accept 假活 + #5 认证限次（FtpServerTest 真实套接字，65 项）==="
rm -rf "$OUT/ftp"; mkdir -p "$OUT/ftp"
# FtpServerTest 还用到 FileRules.uniqueTarget（导入去重规则），一并编译。
"$JDK/bin/javac.exe" -nowarn -J-Duser.language=en -encoding UTF-8 -d "$OUT/ftp" \
  "$SRC/device/FtpServer.java" "$SRC/device/FileRules.java" \
  "$PROJ/tools/device-selftest/FtpServerTest.java" 2>&1 | sed 's/^/  /'
if [ "${PIPESTATUS[0]}" != "0" ]; then
  echo "  编译失败，未运行本组自检。"; fail=1
else
  timeout 180 "$JDK/bin/java.exe" $JAVA_OPTS -cp "$(win "$OUT/ftp")" FtpServerTest | sed 's/^/  /'
  [ "${PIPESTATUS[0]}" != "0" ] && fail=1
fi

echo
if [ "$fail" = "0" ]; then
  echo "全部通过：编译检查 + 纯逻辑自检 + 真实套接字协议测试（未在真机运行）。"
else
  echo "存在失败项，见上方输出。"
fi
exit $fail
