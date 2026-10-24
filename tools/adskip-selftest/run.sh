#!/usr/bin/env bash
set -euo pipefail
out=.scratch/adskip-selftest
jdk="${JDK:-/f/mlbuild/tools/jdk-17.0.20.1+1}"
rm -rf "$out"
mkdir -p "$out"
"$jdk/bin/javac" -encoding UTF-8 -d "$out" \
  app/src/main/java/cn/xianhua/pocketkit/core/automation/AdSkipRules.java \
  app/src/main/java/cn/xianhua/pocketkit/core/automation/AdSkipSession.java \
  tools/adskip-selftest/AdSkipRulesSelfTest.java
"$jdk/bin/java" -cp "$out" AdSkipRulesSelfTest
