# PocketKit — Android 构建环境（已验证）

> 归档时间：2026-10-13
> 结论：**构建可行，已实测通过。无需下载或安装任何新工具。**
>
> **状态：环境准备已归档，开发已获用户授权（2026-10-13）。** 用户先要求只产出功能目录，
> 随后提交两轮功能选择，并明确要求自主寻找实现路径开始开发。现按 §4 / §10 的工具路径进行构建，
> 不安装新的工具链、不修改全局环境；当前尚未连接真实安卓设备。
> 本机已存在一套完整、可用的 JDK 17 + Android SDK + Gradle 工具链，位于 `F:\mlbuild`，
> 但它**不在 PATH 中**，因此 `where java` / `where adb` / `where gradle` 全部查不到 —— 这是"没装"的假象。

本文档只记录**经命令实测**得到的事实；未验证项在文末单独标注。

---

## 1. 关键结论（给实现者的最短路径）

| 项目 | 结论 |
| --- | --- |
| 是否缺 JDK | **不缺**。Temurin JDK 17.0.20.1+1 已存在 |
| 是否缺 Android SDK | **不缺**。SDK 完整，licenses 已接受 |
| 是否缺 Gradle | **不缺**。Gradle 8.14.3 已存在，发行包 zip 本地留档 |
| 是否需要联网 | **构建不需要**。离线 `--offline` 构建已实测通过 |
| 可否用纯 Java（无 Compose / 无 Kotlin） | **可以**，已验证。这是推荐路线 |
| 官方 `gradle wrapper` 默认下载 | **会失败**。见 §6「唯一真实阻碍」 |
| 打包链路 | **Gradle 与 手写 aapt2/javac/d8 两条都验证通过** |
| 设备/模拟器 | adb 可用；AVD `mianliu_test` 存在，但硬件加速不可用（见 §7） |

**没有发现任何必须新增下载的构建依赖。** 唯一受阻的是 Gradle wrapper 的默认下载地址
（`services.gradle.org` → 跳转 `github.com`，而 github.com 在本机不可达），已有两种绕过方案且均已实测。

---

## 2. 工具路径与版本（实测输出）

### 2.1 JDK
```
JAVA_HOME   F:\mlbuild\tools\jdk-17.0.20.1+1
java.exe    F:\mlbuild\tools\jdk-17.0.20.1+1\bin\java.exe
javac.exe   F:\mlbuild\tools\jdk-17.0.20.1+1\bin\javac.exe
```
```
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)
OpenJDK 64-Bit Server VM Temurin-17.0.20.1+1 (build 17.0.20.1+1, mixed mode, sharing)
```
- 发行方：Eclipse Adoptium / Temurin（`release` 文件：`IMPLEMENTOR="Eclipse Adoptium"`）
- 架构：`OS_ARCH="x86_64"`，`bin` 下 126 个可执行文件 → 是**完整 JDK**，非 JRE
- 模块数 71，jadx/反编译所需模块均存在：`jdk.compiler`、`jdk.jdeps`、`jdk.jartool`、
  `jdk.zipfs`、`jdk.crypto.ec`、`java.desktop`、`jdk.unsupported`
- 附带工具：`javap`、`jar`、`jdeps`、`jshell`、`jcmd`、`keytool` 均存在
- **可直接满足 jadx（要求 JDK 11+）**

### 2.2 Android SDK
```
ANDROID_HOME       F:\mlbuild\sdk
ANDROID_SDK_ROOT   F:\mlbuild\sdk
```

| 组件 | 版本 / 内容 |
| --- | --- |
| platforms | `android-36`（Android 16，ApiLevel=36，ExtensionLevel=17） |
| build-tools | `35.0.0`、`36.0.0` |
| platform-tools | adb 1.0.41 / 37.0.1-15733141 |
| cmdline-tools | `latest` → revision 12.0 |
| emulator | 37.2.12.0 (build_id 16428233) |
| system-images | `android-30/google_apis/x86_64`（rev 16） |
| licenses | 已接受（含 `android-sdk-license`、`google-gdk-license` 等） |

关键二进制（build-tools 36.0.0）实测版本：
```
aapt2     Android Asset Packaging Tool (aapt) 2.20-13193326
d8        D8 8.10.9-dev (a7ad18a70460b799d0482e497c109a75bf7f91de)
apksigner 0.9
zipalign  Zip alignment utility
adb       Android Debug Bridge version 1.0.41
sdkmanager 12.0
apkanalyzer 可用
```
`android.jar`：`F:\mlbuild\sdk\platforms\android-36\android.jar`（27,768,026 字节）

### 2.3 Gradle
```
gradle.bat   F:\mlbuild\tools\gradle-8.14.3\bin\gradle.bat
发行包留档    F:\mlbuild\dl\gradle.zip   (137,393,837 字节, 解包根目录 gradle-8.14.3/)
sha256       bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531
```
```
Gradle 8.14.3
Build time:    2025-07-04 13:15:44 UTC
Revision:      e5ee1df3d88b8ca3a8074787a94f373e3090e1db
Kotlin:        2.0.21
Launcher JVM:  17.0.20.1 (Eclipse Adoptium 17.0.20.1+1)
OS:            Windows 11 10.0 amd64
```

### 2.4 依赖缓存（离线构建的基础）
```
GRADLE_USER_HOME  未设置 → 默认 C:\Users\admin\.gradle
```
- `modules-2` 缓存约 **328 MB**，已含 Android 构建所需依赖族
- 关键项：AGP **8.13.0**（`com.android.tools.build:gradle`）、`androidx.*` 全族、
  `com.google.android.material:material:1.12.0`、`androidx.appcompat:appcompat:1.7.1`、
  `androidx.core:core:1.15.0`、`junit:junit:4.13.2`
- `~/.android/debug.keystore` **已存在**（2618 字节，CN=Android Debug）→ debug 签名开箱可用

---

## 3. 推荐构建方式（最少依赖）

**原生 Java + 无 Compose + 无 Kotlin + 无 AndroidX**（除非确有需要）。
理由是：不引入 Kotlin/Compose 编译器插件，构建面最小、离线最稳，且本机缓存已足够。

实测通过的探针工程使用：`compileSdk 36` / `minSdk 24` / `targetSdk 36`、
`sourceCompatibility 17`、AGP 8.13.0、Gradle 8.14.3、纯 `Activity` + `TextView`（framework 类，零依赖）。

---

## 4. 可直接复制的命令

所有命令均**不修改系统环境变量**，只在单次 shell 内 `export`。

### 4.1 bash（MSYS/Git Bash，即 `ops_bash`）
```bash
export JAVA_HOME='F:\mlbuild\tools\jdk-17.0.20.1+1'
export ANDROID_HOME='F:\mlbuild\sdk'
export ANDROID_SDK_ROOT='F:\mlbuild\sdk'
export PATH="/f/mlbuild/tools/jdk-17.0.20.1+1/bin:/f/mlbuild/sdk/platform-tools:$PATH"

# 直接使用已安装的 Gradle（无需 wrapper）
/f/mlbuild/tools/gradle-8.14.3/bin/gradle.bat --console=plain assembleDebug
```

### 4.2 PowerShell
```powershell
$env:JAVA_HOME='F:\mlbuild\tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME='F:\mlbuild\sdk'
$env:ANDROID_SDK_ROOT='F:\mlbuild\sdk'
$env:PATH="F:\mlbuild\tools\jdk-17.0.20.1+1\bin;F:\mlbuild\sdk\platform-tools;$env:PATH"
F:\mlbuild\tools\gradle-8.14.3\bin\gradle.bat --console=plain assembleDebug
```

### 4.3 离线（缓存已充分，推荐用于可重复构建）
```bash
/f/mlbuild/tools/gradle-8.14.3/bin/gradle.bat --console=plain --offline clean assembleDebug
```

---

## 5. 实测证据（探针工程，全部成功）

探针工程（已清理）：`F:\SPACE\PocketKit\.scratch\envprobe`（纯 Java，无 Compose/AndroidX/Kotlin）。
> **注**：该探针工程目录已在任务收尾时按指示删除，**其验证结论仍然有效**（结论来自实测过程，不依赖目录残留）。
> 功能清单阶段收尾后，本轮临时探针、脚本与过程日志已清理，不再作为保留文件提供。
> 工具链与本报告保留；如需复现，可按本文的工具路径和构建命令新建最小工程。

| # | 验证项 | 命令 | 结果 |
| --- | --- | --- | --- |
| 1 | debug 构建 | `gradle assembleDebug` | **BUILD SUCCESSFUL in 1m 24s**，34 tasks executed |
| 2 | release 构建 | `gradle assembleRelease` | **BUILD SUCCESSFUL in 25s**，产出 `app-release-unsigned.apk` |
| 3 | 离线构建 | `gradle --offline clean assembleDebug` | **BUILD SUCCESSFUL in 4s**，21 tasks from cache |
| 4 | 产物有效性 | `aapt2 dump badging` | `package: com.pocketkit.probe`，minSdk 24，targetSdk 36，launchable-activity 正常 |
| 5 | 签名 | `apksigner verify --print-certs` | 通过，`CN=Android Debug`，SHA-256 `2fd062b9…71a5` |
| 6 | 对齐 | `zipalign -c -v 4` | `classes.dex (OK - compressed)` 等全部 OK |
| 7 | dex 内容 | `apkanalyzer dex packages` | 正确解析出 `MainActivity` / `Activity` / `TextView` |
| 8 | wrapper + 本地 dist | `gradle wrapper --gradle-distribution-url file:///F:/mlbuild/dl/gradle.zip` | **BUILD SUCCESSFUL**，生成 `gradlew` / `gradlew.bat` |
| 9 | wrapper 引导 | `./gradlew --version` 后 `./gradlew --offline clean assembleDebug` | **EXIT=0**，从 `file://` 发行包自举成功，产出 APK |
| 10 | 免 Gradle 手写链路 | 见 §5.1 | **全链路 EXIT=0**，产出已签名 APK |

### 5.1 免 Gradle 的纯命令行打包链路（已完整跑通）
`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 注入 dex → `zipalign` → `apksigner`
全部返回 0，最终 APK 中确认含 `classes.dex`、`AndroidManifest.xml`、`resources.arsc`，
签名与对齐校验均通过。**顺序要求：dex 必须在 `zipalign` 之前注入。**

两个已验证的坑：
- `javac` **不要**对 `android.jar` 用 `-bootclasspath`（JDK 17 下会报"目标 17 不支持 --boot-class-path"），
  应直接用 `-classpath <android.jar>`。
- `d8.bat` / `apksigner.bat` 是启动脚本，**依赖 `JAVA_HOME`**；未 export 时报
  `JAVA_HOME is not set and no 'java' command could be found in your PATH`。

---

## 6. 唯一真实阻碍：Gradle wrapper 的默认发行地址

**现象**：`gradle wrapper --gradle-version 8.14.3` 失败：
```
Execution failed for task ':wrapper'.
> Test of distribution url https://services.gradle.org/distributions/gradle-8.14.3-bin.zip failed.
```

**根因（已定位）**：该地址返回 **307 重定向到 `github.com`**，而 github.com 在本机**不可达**：
```
services.gradle.org/distributions/gradle-8.14.3-bin.zip  →  307
  → https://github.com/gradle/gradle-distributions/releases/download/v8.14.3/gradle-8.14.3-bin.zip
github.com                       → HTTP 000 (超时, exit 28)
```
注意：`services.gradle.org` 本身是通的（HTTP 307），是**跳转目标**不通。这与"TLS/连接被重置"
一类环境问题同源，**不应重试或换通道循环**。

**两个已实测的绕过方案（任选其一）**：

- **方案 A（推荐，最简单）**：不用 wrapper，直接用已安装的 Gradle
  `F:\mlbuild\tools\gradle-8.14.3\bin\gradle.bat`。功能完全等价。
- **方案 B（需要 wrapper 时）**：把发行地址指向本地留档，已实测自举成功
  ```bash
  gradle wrapper --gradle-distribution-url 'file:///F:/mlbuild/dl/gradle.zip'
  ```
  生成的 `gradle/wrapper/gradle-wrapper.properties`：
  ```
  distributionUrl=file\:///F\:/mlbuild/dl/gradle.zip
  ```

> 影响面：仅"生成/引导 wrapper"这一步。**构建本身完全不受影响**，离线构建已通过。

---

## 7. 设备与模拟器可用性

### 7.1 adb（可用）
```
$ adb devices -l
List of devices attached
```
即：**当前没有连接的物理设备**（列表为空，非报错）。adb 服务可正常启动
（`daemon not running; starting now at tcp:5037` → `daemon started successfully`）。
本机无厂商 USB 驱动相关问题被观测到，但也未接入真机，故**真机部署未经实测**。

### 7.2 AVD（存在，但硬件加速不可用）
```
$ emulator -list-avds
mianliu_test

Name: mianliu_test    Target: Google APIs (Google Inc.)
Based on: Android 11.0 ("R")    Tag/ABI: google_apis/x86_64    Sdcard: 512 MB
```
```
$ emulator -accel-check
accel:
6
Android Emulator hypervisor driver is not installed on this machine
```
- CPU 虚拟化固件已启用（`VirtualizationFirmwareEnabled = True`）
- 但 **AEHD / HAXM / WHPX 均不可用**：未安装 `gvm`/`aehd`/`intelhaxm` 服务，
  `HypervisorPlatform` 特性查询报 COMException（无法以当前权限确认状态）
- **含义**：x86_64 模拟器**无法硬件加速**，纯软件模拟极慢或无法启动。
  → **模拟器验证不可作为本任务的可靠路径。**
- 按任务范围，**未启动模拟器、未安装任何第三方样本**。

> 若后续必须做运行时验证，现实选项是：接入真机（`adb install`）或另行安装
> AEHD/WHPX（属系统级改动，超出本子任务范围，需另行确认）。

---

## 8. 未验证项 / 未做的事（明确边界）

- **未修改任何系统环境变量**（`JAVA_HOME` / `PATH` / `ANDROID_HOME` 在系统中仍未设置），
  也未改动 DSH 配置；所有设置仅在单次 shell 内生效。
- **未安装、未升级任何软件**。全部复用 `F:\mlbuild` 既有工具链。
- 未验证：真机 `adb install` 实际安装、模拟器实际启动、release 正式签名（本机只有 debug keystore，
  无 release keystore → release 产物目前是 `unsigned`）。
- 未验证：多模块 / AndroidX / Kotlin / Compose 构建（推荐路线刻意避开，故未测）。
  AGP 8.13.0 与相关依赖已在缓存中，理论上可用，但**未实测**。
- 本任务不含 UI 实现、不含 APK 静态分析（由主会话负责）。

---

## 9. 给实现者的落地建议

1. 项目固定 `F:\SPACE\PocketKit`；构建脚本里**显式 export 上述三个变量**，不要依赖 PATH。
2. 优先**直接用 `F:\mlbuild\tools\gradle-8.14.3\bin\gradle.bat`**，跳过 wrapper，规避 §6 阻碍。
3. 若确需 wrapper，用方案 B 的 `file://` 发行地址，并把该属性文件纳入版本控制。
4. 采用**纯 Java + framework 类**，`compileSdk 36` / `minSdk 24` / Java 17。
5. 构建验证用 `--offline`，避免任何网络抖动影响可重复性。
6. 产物验证用 `aapt2 dump badging` + `apksigner verify`（本机均已可用）。
7. 不要在构建链路里引入对 github.com 的依赖（不可达）。

---

## 10. 环境速查（复制即用）

```bash
# --- PocketKit 构建环境 ---
export JAVA_HOME='F:\mlbuild\tools\jdk-17.0.20.1+1'
export ANDROID_HOME='F:\mlbuild\sdk'
export ANDROID_SDK_ROOT='F:\mlbuild\sdk'
export PATH="/f/mlbuild/tools/jdk-17.0.20.1+1/bin:/f/mlbuild/sdk/platform-tools:$PATH"
GRADLE='/f/mlbuild/tools/gradle-8.14.3/bin/gradle.bat'

# 构建
"$GRADLE" --console=plain --offline clean assembleDebug

# 校验产物
/f/mlbuild/sdk/build-tools/36.0.0/aapt2.exe dump badging \
  app/build/outputs/apk/debug/app-debug.apk
/f/mlbuild/sdk/build-tools/36.0.0/apksigner.bat verify --print-certs \
  app/build/outputs/apk/debug/app-debug.apk
```
