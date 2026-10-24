#!/usr/bin/env bash
set -euo pipefail
mkdir -p .scratch/offline-formatter/classes
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1/bin
"$JDK/javac.exe" -encoding UTF-8 -d .scratch/offline-formatter/classes app/src/main/java/cn/xianhua/pocketkit/core/MiniJson.java app/src/main/java/cn/xianhua/pocketkit/core/ToolException.java app/src/main/java/cn/xianhua/pocketkit/device/OfflineLexiconFormatter.java tools/device-selftest/OfflineLexiconFormatterTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp .scratch/offline-formatter/classes cn.xianhua.pocketkit.device.OfflineLexiconFormatterTest
