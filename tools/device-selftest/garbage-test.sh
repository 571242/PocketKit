#!/usr/bin/env bash
set -euo pipefail
mkdir -p .scratch/garbage-test/classes
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1/bin
"$JDK/javac.exe" -encoding UTF-8 -d .scratch/garbage-test/classes app/src/main/java/cn/xianhua/pocketkit/device/GarbageCatalog.java tools/device-selftest/GarbageCatalogTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp .scratch/garbage-test/classes cn.xianhua.pocketkit.device.GarbageCatalogTest
