#!/usr/bin/env bash
set -euo pipefail
mkdir -p .scratch/installer-test/classes
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1/bin
"$JDK/javac.exe" -encoding UTF-8 -d .scratch/installer-test/classes app/src/main/java/cn/xianhua/pocketkit/core/MiniJson.java app/src/main/java/cn/xianhua/pocketkit/core/ToolException.java app/src/main/java/cn/xianhua/pocketkit/device/LexiconInstaller.java tools/device-selftest/InstallerTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp .scratch/installer-test/classes cn.xianhua.pocketkit.device.InstallerTest
