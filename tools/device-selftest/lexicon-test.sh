#!/usr/bin/env bash
set -euo pipefail
mkdir -p .scratch/lexicon-test/classes
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1/bin
"$JDK/javac.exe" -encoding UTF-8 -d .scratch/lexicon-test/classes app/src/main/java/cn/xianhua/pocketkit/core/MiniJson.java app/src/main/java/cn/xianhua/pocketkit/core/ToolException.java app/src/main/java/cn/xianhua/pocketkit/device/Http.java app/src/main/java/cn/xianhua/pocketkit/device/LexiconTool.java app/src/main/java/cn/xianhua/pocketkit/device/MoeLexicon.java tools/device-selftest/MoeLexiconTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp .scratch/lexicon-test/classes cn.xianhua.pocketkit.device.MoeLexiconTest
