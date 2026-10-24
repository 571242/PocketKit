#!/usr/bin/env bash
# Off-device self-test for ProxyConfig parsing/runtime config, cloud rule bundles and Node persistence.
#
# ProxyConfig itself needs only SnakeYAML. The test harnesses read emitted JSON with org.json from
# the gradle cache. No device and no libbox native library is required: ProxyProfileManager is
# compiled against a ProxyState stub, so its persistence logic stays testable regardless of the VPN
# service code, and ProxyRulesStore is loaded only for its pure static URL check.
set -euo pipefail

# Windows javac/java want ";"-separated classpaths in native form; stop MSYS from rewriting them.
export MSYS2_ARG_CONV_EXCL='*'
export MSYS2_ENV_CONV_EXCL='*'

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -W 2>/dev/null || pwd)"
JDK="${JAVA_HOME:-F:/mlbuild/tools/jdk-17.0.20.1+1}"
ANDROID_JAR="${ANDROID_JAR:-F:/mlbuild/sdk/platforms/android-36/android.jar}"
JAVAC="$JDK/bin/javac.exe"
JAVA="$JDK/bin/java.exe"
# Scratch output only; never pollutes build/ or the app tree.
OUT="$ROOT/.scratch/proxy-repair/selftest-classes"

SNAKEYAML="$ROOT/app/libs/snakeyaml-2.0.jar"
JSON="$ROOT/.scratch/proxy-repair/org.json.jar"
PROXY_SRC="$ROOT/app/src/main/java/cn/xianhua/pocketkit/proxy"
TEST_SRC="$ROOT/tools/proxy-selftest/src/cn/xianhua/pocketkit/proxy"
STUB="$ROOT/tools/proxy-selftest/stub"

[ -x "$JAVAC" ] || { echo "javac not found at $JAVAC (set JAVA_HOME)" >&2; exit 1; }
[ -f "$SNAKEYAML" ] || { echo "missing app/libs/snakeyaml-2.0.jar" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "missing android.jar at $ANDROID_JAR (set ANDROID_JAR)" >&2; exit 1; }
mkdir -p "$ROOT/.scratch/proxy-repair"
if [ ! -f "$JSON" ]; then
  found="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" -name 'json-*.jar' 2>/dev/null | sort | tail -1)"
  [ -n "$found" ] || { echo "missing org.json jar in the gradle cache (test harness only)" >&2; exit 1; }
  cp "$found" "$JSON"
fi

run_java() { "$JAVA" -Djava.io.tmpdir="$OUT/tmp" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@"; }

mkdir -p "$OUT/tmp"

# --- 1. parsing + runtime config (no Android classes at all)
"$JAVAC" -encoding UTF-8 -nowarn -cp "$SNAKEYAML;$JSON" -d "$OUT" \
  "$PROXY_SRC/ProxyConfig.java" \
  "$PROXY_SRC/ProxyRules.java" \
  "$TEST_SRC/ProxyConfigSelfTest.java"
run_java -cp "$OUT;$SNAKEYAML;$JSON" cn.xianhua.pocketkit.proxy.ProxyConfigSelfTest

# --- 2. node persistence round-trip (manager + ProxyState stub; android.jar for Context/SharedPreferences)
"$JAVAC" -encoding UTF-8 -nowarn -cp "$SNAKEYAML;$JSON;$ANDROID_JAR" -d "$OUT" \
  "$STUB/cn/xianhua/pocketkit/proxy/ProxyState.java" \
  "$PROXY_SRC/ProxyConfig.java" \
  "$PROXY_SRC/ProxyRules.java" \
  "$PROXY_SRC/ProxyProfileManager.java" \
  "$TEST_SRC/NodePersistenceSelfTest.java"
run_java -cp "$OUT;$SNAKEYAML;$JSON;$ANDROID_JAR" cn.xianhua.pocketkit.proxy.NodePersistenceSelfTest

# --- 3. cloud rule bundle: parser, hash, atomic save, route/DNS ordering.
# ProxyRulesStore is compiled for its pure static URL check; its Context-based methods are never called.
"$JAVAC" -encoding UTF-8 -nowarn -cp "$SNAKEYAML;$JSON;$ANDROID_JAR" -d "$OUT" \
  "$PROXY_SRC/ProxyConfig.java" \
  "$PROXY_SRC/ProxyRules.java" \
  "$PROXY_SRC/ProxyRulesStore.java" \
  "$TEST_SRC/ProxyRulesSelfTest.java"
run_java -cp "$OUT;$SNAKEYAML;$JSON;$ANDROID_JAR" cn.xianhua.pocketkit.proxy.ProxyRulesSelfTest "$ROOT"

# --- 4. optional: validate emitted configs against sing-box's own schema (catches a misspelled
# rule field, which config-level tests cannot see). Offline unless SINGBOX_SCHEMA is exported:
#   curl -sSo schema.json https://raw.githubusercontent.com/SagerNet/sing-box/v1.14.3/docs/schema.json
#   SINGBOX_SCHEMA=schema.json bash tools/proxy-selftest/run.sh
"$JAVAC" -encoding UTF-8 -nowarn -cp "$SNAKEYAML;$JSON" -d "$OUT" \
  "$PROXY_SRC/ProxyConfig.java" \
  "$PROXY_SRC/ProxyRules.java" \
  "$TEST_SRC/RuntimeConfigExport.java"
CONFIGS="$ROOT/.scratch/proxy-repair/runtime-configs"
run_java -cp "$OUT;$SNAKEYAML;$JSON" cn.xianhua.pocketkit.proxy.RuntimeConfigExport "$CONFIGS" "$ROOT"
python "$ROOT/tools/proxy-selftest/schema-check.py" "$CONFIGS"/*.json
