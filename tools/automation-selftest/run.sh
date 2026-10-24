#!/usr/bin/env bash
# Run from the project root with JDK 17 on PATH. Argument: an existing org.json jar.
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo 'Usage: bash tools/automation-selftest/run.sh /path/to/existing/json.jar' >&2
  exit 2
fi
out=.scratch/automation-selftest
mkdir -p "$out"
json="$1"
case "$OSTYPE" in
  msys*|cygwin*) json=$(cygpath -w "$json"); classpath="$(cygpath -w "$out");$json" ;;
  *) classpath="$out:$json" ;;
esac
javac -encoding UTF-8 -cp "$json" -d "$out" \
  app/src/main/java/cn/xianhua/pocketkit/core/automation/{Match,Step,Script,Runner,RecordingSession}.java \
  app/src/main/java/cn/xianhua/pocketkit/automation/ScriptCodec.java \
  tools/automation-selftest/{AutomationCoreSelfTest,RecordingSessionSelfTest,ScriptCodecSelfTest}.java \
  app/src/test/java/cn/xianhua/pocketkit/{AutomationUiContractTest,AutoclickerUiContractTest}.java
for name in AutomationCoreSelfTest RecordingSessionSelfTest ScriptCodecSelfTest; do
  java -cp "$classpath" "tools.automation_selftest.$name"
done
java -cp "$classpath" cn.xianhua.pocketkit.AutomationUiContractTest .
java -cp "$classpath" cn.xianhua.pocketkit.AutoclickerUiContractTest .
