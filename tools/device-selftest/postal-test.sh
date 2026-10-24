#!/usr/bin/env bash
set -euo pipefail
mkdir -p .scratch/postal-test/classes
JDK=/f/mlbuild/tools/jdk-17.0.20.1+1/bin
"$JDK/javac.exe" -encoding UTF-8 -d .scratch/postal-test/classes app/src/main/java/cn/xianhua/pocketkit/device/PostalCatalog.java tools/device-selftest/PostalCatalogTest.java
"$JDK/java.exe" -Dfile.encoding=UTF-8 -cp .scratch/postal-test/classes cn.xianhua.pocketkit.device.PostalCatalogTest
