# PocketKit 关键缺陷审计（只读，非 proxy / 非 update）

审计范围：`app/src/main/java/cn/xianhua/pocketkit/` 下除 `proxy/`、`update/` 外的模块
（manager / media / device / automation / local / voice / voiceoverlay / core）。
跳过：`build/`、`.gradle/`、`evidence/`、`dist/`。
基线：`compileSdk 36`、`targetSdk 36`、`minSdk 26`（`app/build.gradle`）。

本文件只做**只读**记录，未修改任何源码。每条只写：位置、触发条件、最小修法、验证方式。

---

## 汇总

| # | 优先级 | 类别 | 位置 | 一句话 |
|---|--------|------|------|--------|
| 1 | P0 | Android 36 兼容 / 崩溃 | `libonnxruntime4j_jni.so`（经 `app/build.gradle` 依赖引入） | arm64 只有 4 KB 页对齐，16 KB 页设备上加载失败 |
| 2 | P1 | 崩溃 / OOM | `media/ImageConvertTool.java:199` | Base64 粘贴无长度上限，解码前已膨胀数倍 |
| 3 | P1 | 数据损坏 | `local/LocalStore.java:332` | `escape()` 把换行/分隔符**静默改写为空格**，用户输入被永久篡改 |
| 4 | P2 | 可用性 / 功能静默失效 | `device/FtpServer.java:143-147` | accept 线程遇任一 IOException 即 `return`，服务假活 |
| 5 | P2 | 安全（局域网）| `device/FtpServer.java:229-238` | 凭证可无限次尝试，无失败计数/延迟，且全程明文 |

---

## 1. P0 — `libonnxruntime4j_jni.so` 未按 16 KB 页对齐（Android 16 直接加载失败）

**位置**
- 依赖声明：`app/build.gradle` → `implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.20.0'`
- 受影响二进制：`lib/arm64-v8a/libonnxruntime4j_jni.so`（`x86_64` 同样问题）
- 调用点：`voice/ai/OpenVoiceConverter.java:28`（`OrtEnvironment.getEnvironment()`）、`voice/ai/OpenVoiceProcessor.java:80`

**证据（非猜测，逐段实测）**
直接从上游 AAR 解包验证，排除构建产物污染：
`~/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/1.20.0/*/onnxruntime-android-1.20.0.aar`

```
jni/arm64-v8a/libonnxruntime.so        LOAD align=[16384]   ← OK
jni/arm64-v8a/libonnxruntime4j_jni.so  LOAD align=[4096]    ← 不达标
jni/x86_64/libonnxruntime4j_jni.so     LOAD align=[4096]    ← 不达标
```

同工程其余原生库四个 ML Kit 库均为 `16384`，所以这是**这一个依赖**的特例，不是配置问题。

**触发条件**
- 设备使用 16 KB 页内核（Android 15 部分机型、Android 16 起更多设备），且用户打开「AI 音色」相关功能。
- 结果：`.so` 无法映射 → `UnsatisfiedLinkError`。这是加载期失败，`catch (Exception | LinkageError)` 只能兜住不崩进程，但**功能整条不可用**，用户看到的是「AI 音色永远起不来」。

**最小修法**
- 升级到已修复 16 KB 对齐的 `onnxruntime-android` 版本（1.20 之后的上游修复），或改用不打包 ORT 的构建变体。
- **不要**自己 patch `.so`，也不要尝试用 `useLegacyPackaging` 绕过——那解决的是压缩问题，不是页对齐。

**验证建议**
- `llvm-readelf -l libonnxruntime4j_jni.so`，确认所有 `LOAD` 段 `Align >= 0x4000`。
- `zipalign -c -P 16 -v 4 app-release.apk` 必须通过。
- 在 16 KB 页设备（或模拟器 `-page-size 16k`）实机跑一次 AI 音色入口。

---

## 2. P1 — Base64 解码无输入上限，贴大文本即 OOM

**位置**
- `media/ImageConvertTool.java:199` `decodeBase64(String text)`
- 入口：`media/ImageConvertTool.java:128` → `:139`

**触发条件**
- 用户在 Base64 输入框粘贴一段超长文本（几百 MB 级，或几十 MB 也足以在低内存机触发）。
- 路径上没有任何长度闸门：`:200` 只 `trim()`，`:209` 先做 `replaceAll("\\s+","")`（此处已复制一份完整字符串），`:214` 再 `Base64.getDecoder().decode(body)`（第三份完整字节数组）。
- 一次操作在峰值同时持有「原文 + 去空白副本 + 解码结果」，约为输入的 2～2.3 倍。低端机上直接 `OutOfMemoryError`，且**不在 `catch(Exception)` 覆盖范围内**。

**最小修法**
在 `decodeBase64` 开头加一个上限判断，一行即可：

```java
// 输入文本超过上限直接拒绝，避免解码前就撑爆内存
if (text.length() > MAX_BASE64_CHARS) throw new Exception("Base64 文本过长，请先压缩图片");
```

`MAX_BASE64_CHARS` 取与 `MediaIo.MAX_BYTES` 对齐的量级（约 `MAX_BYTES * 4 / 3 + 余量`）。位置放在 `trim()` 之前，先于 `replaceAll`。

**验证建议**
- 单元自检：传入 `MAX_BASE64_CHARS + 1` 的字符串，断言抛出且**未**分配解码数组。
- 手工：粘贴 ~1 GB 文本，观察应为友好报错而非进程崩溃。

---

## 3. P1 — `LocalStore.escape()` 静默改写用户数据

**位置**
- `local/LocalStore.java:332`

```java
private static String escape(String s) {
    return s == null ? "" : s.replace("\u0001", " ").replace("\n", " ");
}
```

**触发条件**
- 用户在「纪念日名称」或「记分牌」中输入含换行（多行粘贴）或 `\u0001` 的内容，保存后再读回。
- 换行被替换成空格，**原样永久丢失**，且没有任何提示。用户看到的是「我明明换行了，怎么变成一行」。

**为什么这是高优先级**
- 不是格式损坏（`LedgerBook.esc()` 用的是 `\\n` 转义，是对的），而是**静默篡改**：写入成功、读取成功、数据已不同。
- 对比即可看出不一致：`core/LedgerBook.java:331-332` 做了正确转义，`LocalStore.escape()` 没有。

**最小修法**
把 `LedgerBook` 已有的转义复用过来（不新增抽象），`escape` 改成与 `unesc` 对称的实现；若暂时不引入 `unesc`，最低限度应**拒绝**含换行的输入并提示，而不是替换成空格。

**验证建议**
- 自检：`escape("a\nb")` 必须能经对应 `unesc` 还原为 `a\nb`；或断言含换行输入被拒绝。
- 回归：保存一条含多行的纪念日，重启应用后读回比对。

---

## 4. P2 — FTP `acceptLoop` 遇异常即永久退出，服务「假活」

**位置**
- `device/FtpServer.java:135-150`，关键行 `:147` 的 `return;`

```java
} catch (IOException ex) {
    if (running) { log("接受连接失败：" + ex.getMessage()); }
    return;   // ← running 仍为 true
}
```

**触发条件**
- 监听 socket 出现一次瞬时错误（如系统回收、并发 accept 抖动、`ServerSocket` 被外部关闭）。
- 结果：`running` 仍为 `true`、`isRunning()` 仍返回真、界面轮询依旧显示「运行中，端口 xxxx」，但**再没有线程在 accept**，新连接全部失败。
- 与 `:118 stop()` 的设计冲突：`stop()` 才是唯一应设置 `running=false` 的地方，这里却让状态与事实脱节。

**最小修法**
只在真正停止时退出，其它异常继续循环：

```java
} catch (IOException ex) {
    if (!running) return;
    log("接受连接失败：" + ex.getMessage());
    // 瞬时错误继续监听，避免状态显示运行中但实际已死
}
```

（若 `serverSocket` 已关闭，`running` 会在 `stop()` 中先被置 false，循环自然结束。）

**验证建议**
- 自检/手工：启动共享后，用反射或调试手段强制一次 accept 抛错，确认 `isRunning()` 与实际可连接性一致。
- 手工：连续建立/断开 20 次连接，服务仍可接受新连接。

---

## 5. P2 — FTP 凭证可无限次尝试（局域网明文 + 无失败限制）

**位置**
- `device/FtpServer.java:229-238`（`PASS` 处理）

```java
case "PASS":
    if (constantTimeEquals(pendingUser, username) && constantTimeEquals(argument, password)) { ... }
    else { authenticated = false; reply(530, "用户名或密码错误"); }
    return true;
```

**触发条件**
- 同一局域网内任意主机连上端口后，可**不限次数**发送 `USER`/`PASS`。
- 代码里没有任何失败计数、延迟、断开或封禁逻辑（全文检索 `attempt|fail|limit|lock|throttle` 无命中）。
- 口令空间为 `randomToken(10)`，字母表 32 个字符（`FtpShareTool.java:414`），本身足够强；但「无限次 + 明文 + 局域网」叠加后，风险在于**同网段被动抓包**，而不是爆破。

**定性说明（避免误报）**
- 明文传输是**已在类注释里明确声明的设计限制**（`FtpServer.java:35-38`），不算隐藏缺陷。
- 真正的缺陷是**缺少失败限制**：这让「已经抓到一次握手」与「持续重放试探」之间没有任何摩擦成本。

**最小修法**
在 `Session` 内加一个计数，超过阈值直接断开：

```java
private int authFailures;
// PASS 失败分支：
if (++authFailures >= 5) { reply(421, "尝试次数过多，连接关闭"); return false; }
```

**验证建议**
- 自检：连续 5 次错误 `PASS`，断言第 6 次收到 `421` 且会话结束。
- 手工：`ftp` 客户端手工连发错误口令，确认连接在第 5 次后被服务端主动断开。

---

## 已排查但**判定不成立**的点（避免后续重复劳动）

这些看起来像高危，实测有正确防护，记录结论供复核：

| 疑点 | 结论 |
|------|------|
| `FtpServer` 路径穿越（`../`、符号链接逃逸） | 有防护：`resolve()` + `isInsideRoot()` 做 canonical 校验，且带 `File.separator` 前缀判断（`:554-563`） |
| FTP 上传中断毁掉原文件 | 有防护：临时文件 + `Files.move(ATOMIC_MOVE)`，失败不降级为 copy（`:633-651`） |
| `ManagerShareProvider` 任意文件读取 | 有防护：`safeName` + canonical 父目录比对 + 只读（`ManagerShareProvider.java:15-18`） |
| `ManagerActivity` 外部 `Intent` 越权 | 有防护：外部调用时剥离 `tool_id`（`:44`），incoming 校验 `file` 方案必须落在共享存储且非 `Android/data`（`:215-216`） |
| `StorageCleanTool` 空目录误删 / 竞态 | 有防护：删除前重新 canonical 化、比对 `canonicalPath`、重新 `listFiles()` 判空（`StorageScanner.java:94-113`） |
| `ScriptStore` 导入导致栈溢出 | 有防护：`decodeStep` 限 32 层，步骤数与字节数均有上限（`ScriptCodec.java:30,39`） |
| 模板图片解压炸弹 | 有防护：PNG 魔数 + 像素上限 12M + 字节上限 8 MB（`TemplateStore.java`） |
| `Http` / `HttpGet` 无超时、无响应上限 | 有防护：10s/20s/15s 超时 + 4 MB / 512 KB 上限 |
| CSV 公式注入 | 有防护：`CsvSafe.cell()` 中和 `= + - @ \t \r` |
| 前台服务异常导致进程崩溃 | 有防护：`onStartCommand` 内 `try/catch` + `stopSelf()`（Overlay/Picker/Recorder/VoiceOverlay） |
| `targetSdk 36` 预测性返回导致返回键失效 | 已适配：`OnBackInvokedCallback`（`AutomationToolsActivity.java:78`） |
| 返回键丢草稿 | 已适配：`finish()` 与 back callback 都会 `saveDraftBeforeLeaving()` |
| 备份泄漏账本/脚本 | 已防护：`data_extraction_rules.xml` 全域 exclude |
| SDK 常量类日志泄漏凭证 | FTP 凭证只在界面显示，不写日志、不进通知 |

---

## 建议的修复顺序

1. **先修 #1**：这是唯一会让整条功能在真实新机型上不可用的项，且只能靠升级依赖解决。
2. **再修 #2 / #3**：一条是崩溃，一条是静默数据篡改，都属于「用户会丢东西」。
3. **#4 / #5 可一并做**：都在 `FtpServer` 里，改动量各只有几行，一起改一起验。

未覆盖范围：`proxy/`、`update/`（按要求排除）；`voice/DspVoiceProcessor` 的 DSP 算法正确性、`media` 各滤镜的数值效果、UI 视觉一致性均未在此审计（不属于「安全 / 数据损坏 / 崩溃 / Android 36 兼容」四类）。
