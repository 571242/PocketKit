# N01 局域网互传 —— 实现与集成契约

> 归属：本子代理。仅新增下列文件，**未改动** `AndroidManifest.xml`、`app/build.gradle`、
> 目录结构、`ToolRegistry`、`ToolCatalog`、`MainActivity` 或其他模块。
> 界面语言中文，跟随系统深浅色，沿用 `BaseToolActivity` / `ToolUi` 的雾白深蓝配色。

**验证状态声明**：当前**无真机、无可用硬件加速模拟器**（见 `docs/build-environment.md` §7）。
下文所有「通过」指：本机 `javac` 对 `android.jar` 的真实编译检查 + 纯 JVM 上真实开端口、
真实发 HTTP 的传输测试 + 页面脚本的 Node 静态检查。
**真机行为（前台服务、通知、SAF 授权、二维码显示、不同厂商热点）尚未实测。**

---

## 1. 交付文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/cn/xianhua/pocketkit/LanShareActivity.java` | 入口 Activity。`public static boolean supports(String)` / `limitation(String)`，供 `ToolRegistry` 反射调用 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanServer.java` | HTTP/1.1 服务端（纯 `java.*`）。认证、路由、上传/下载/文字、全部边界与上限 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanSession.java` | 会话单例：状态、令牌、URL、消息、观察者；Activity 与服务共享同一份 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanShareService.java` | 前台服务（`dataSync`），通知一键停止，闲置超时自动停 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/SafStore.java` | SAF 接收目录适配：新建、列名、打开、删除本次会话新建的文档 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanAddress.java` | 地址打分：排除 VPN 隧道与蜂窝，选私有 IPv4（纯逻辑） |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanAddresses.java` | 从 `ConnectivityManager` 收集候选地址 |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanNames.java` | 文件名清洗与重名 `(1)(2)`（纯逻辑） |
| `app/src/main/java/cn/xianhua/pocketkit/lanshare/LanQr.java` | 地址二维码（复用已有 ZXing，无新依赖） |
| `app/src/main/assets/lanshare/index.html` | 网页：中文、响应式、拖拽上传、下载、文字互传、进度与取消。零外网资源 |
| `app/src/test/java/cn/xianhua/pocketkit/lanshare/LanServerTest.java` | 100 项：认证、头部边界、路径穿越、重名、取消、并发、文字、删除、地址与命名 |
| `app/src/test/java/cn/xianhua/pocketkit/lanshare/LanLifecycleTest.java` | 16 项：停止后端口真的释放、可重新绑定、闲置判定穷举 |
| `app/src/test/java/cn/xianhua/pocketkit/lanshare/LanEndToEndTest.java` | 21 项：标准 HTTP 客户端 + 仓库真实页面，走用户正常路径 |
| `docs/lan-share-011.md` | 本文件 |
| `.scratch/lan-share/` | 测试与编译脚本（临时，可删） |

**没有新增任何第三方依赖。** 二维码复用 `com.google.zxing:core:3.5.3`（`app/build.gradle` 已有）。
HTTP 服务端手写，约 700 行，纯 JDK，因此可以在普通 JVM 上被真实验证。

---

## 2. Lead 需要做的集成（仅此四步，均在公共文件里）

### 2.1 `app/src/main/AndroidManifest.xml`

在 `<application>` 内加入（`MainActivity` 之后任意位置都可以）：

```xml
<activity android:name=".LanShareActivity" android:exported="false"
    android:windowSoftInputMode="adjustResize" />
<service android:name=".lanshare.LanShareService" android:exported="false"
    android:foregroundServiceType="dataSync" />
```

权限（`<manifest>` 下）：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- `INTERNET` 与 `ACCESS_NETWORK_STATE` **已经存在**（第 3、4 行），不必重复添加。
- `FOREGROUND_SERVICE` 已经存在（第 8 行），重复声明无害但可以不加。
- **`FOREGROUND_SERVICE_DATA_SYNC` 是新增项**，`targetSdk 34+` 下没有它 `startForeground` 会抛
  `SecurityException`。
- `POST_NOTIFICATIONS` **已经存在**（第 13 行），无需重复。
- `android:usesCleartextTraffic="true"` **已经存在**于 `<application>`；本功能是服务端而**不发起**
  明文请求，所以即使将来收紧这项也不影响本功能。

### 2.2 `app/src/main/assets/selected-tools.json`

加入一条（`id` 建议 `N01`，与 `supports()` 一致）：

```json
{ "id": "N01", "name": "局域网互传", "category": "网络工具", "module": "LanShareActivity", "enhancements": [] }
```

### 2.3 `ToolCatalog.java`

- `GROUP` 里 `"网络工具"` 那一行追加 ` N01`。
- `name()` 的 `switch` 加 `case "N01": return "局域网互传";`（不加也会用 JSON 里的 `name`，
  加只是为了统一命名口径）。

### 2.4 不需要改的东西

`ToolRegistry` 通过反射调用 `LanShareActivity.supports("N01")` 与 `limitation("N01")`，
签名与其它模块一致，**无需改动 `ToolRegistry`**。`MainActivity` 通过 `module` 字段启动 Activity，
也无需改动。

**若 `N01` 与 lead 的编号规划冲突**：只改 `LanShareActivity.supports/limitation` 里的两个字符串常量，
以及 `selected-tools.json` 的 `id`，别的都不用动。

---

## 3. 功能行为（用户能看到的）

### 3.1 页面结构

1. 说明区：明文风险、只共享选中文件、重名不覆盖，三条警示。
2. **接收目录**：`ACTION_OPEN_DOCUMENT_TREE` 选一个目录，持久授权。
3. **要发送的文件**：`ACTION_OPEN_DOCUMENT` 多选；列表里每个文件可「移除」。
4. **共享开关**：启动 / 停止，运行中显示地址 + 二维码（220dp）。
5. **传输状态**：进度条 + 文字（"正在接收 X：3.2 MB / 10 MB（32%）"）。
6. **文字互传**：输入框 + 发送，显示最近 6 条消息（区分「手机：」「电脑：」）。

### 3.2 生命周期

| 事件 | 行为 |
| --- | --- |
| 按「启动共享」 | 校验接收目录 → 选局域网地址 → 读网页 → 起服务 → 起前台服务 → 生成二维码 |
| 离开 Activity | **共享继续**（前台服务维持），回到页面时状态原样恢复 |
| 通知栏「停止共享」 | 立即停止并释放端口（与页面按钮同一条路径） |
| 闲置 10 分钟无请求 | 自动停止并释放端口，通知一起收掉 |
| 传输进行中 | **不**按闲置计（大文件传输期间 `transfers()` 非空） |
| 应用被杀 | `START_NOT_STICKY`，不会自己重启；重新开始必须用户再按一次 |
| 开机 | 没有任何广播接收器，不自启动 |

服务 `onDestroy` 里也会 `LanSession.stop()`：服务没了就不该继续占端口，宁可保守。

### 3.3 网络

- **只绑定一个具体的私有 IPv4**，不监听 `0.0.0.0`，不走蜂窝、不穿 VPN。
- 选择规则（`LanAddress.score`）：接口 down / VPN / 隧道名（`tun* tap* ppp* ipsec* wg* utun*
  vpn* clat* pptp* l2tp* sit* gre*`）/ 蜂窝名（`rmnet* ccmni* pdp* wwan* radio*`）/ 非 RFC1918
  一律排除；`192.168.*` 优先于 `10.*` 优先于 `172.16/12`；`169.254.*` 排最后。
- **同一 Wi-Fi 或热点都能连**：热点在系统里通常是 `wlan0` / `ap0` / `swlan0`，都命中加分规则。
- 端口由系统分配（`new ServerSocket(0, ...)`），避免固定端口被占用。

---

## 4. 安全设计（对照任务要求）

| 要求 | 实现 | 验证 |
| --- | --- | --- |
| 随机强会话 token | 32 位 base32，`SecureRandom`，字母表去掉易混字符 | 长度 < 16 时构造器直接抛 `IllegalArgumentException` |
| 鉴权 | 令牌在路径里 `/t/<token>/`，逐字符定长比较，且必须是完整一段（`/t/<token>extra` 不算） | 错误令牌 → 404 且不泄露页面；令牌前缀 → 404 |
| 拒绝跨站 | ① 所有写请求必须带 `X-LanShare: 1`（自定义头会触发预检）；② `Origin` 若存在必须等于 `http://<Host>`；③ **从不发任何 CORS 头**，预检自然失败 | 跨站 Origin → 403；缺标记头 → 403；预检无 `Access-Control-Allow-Origin` |
| 拒绝路径穿越 | 路由路径含 `../` `..\` `%2e%2e` `..%2f` 等 → 400；文件名一律经 `LanNames.sanitize` 削成单段；**落盘走 SAF，从不拼文件系统路径** | 5 种穿越形态全部被拒或落回页面；`../../../逃逸.txt` 只落在接收目录 |
| 禁 CORS | 所有响应都无 `Access-Control-*` | 断言 `access-control-allow-origin == null` |
| 不公开整盘 | 只登记用户挑中的**单个文件 URI**（不是目录）；接收目录里**原本就有**的文件一个都不列、一个都不删 | 用户移除后 `unshare` 真正撤下，电脑立刻看不到 |
| 重名新建不覆盖 | 选择名字与创建文档在同一把锁内完成，`reserved` 集合占位；已存在则 `(1)(2)…`；比较不区分大小写 | 顺序同名 → `photo (1).jpg`、`photo (2).jpg`，原文件字节不变；**并发**同名 → 两份独立内容 |
| 明文须明示 | Activity 顶部黄色警示块 + 网页顶部警示条，都写明"明文 HTTP / 同网段可能被看到" | 人工核对文案 |
| 大小/头部有界 | 请求行 4 KB、头部总 16 KB、头 48 行、上传 4 GB、文字 64 KB、消息 200 条、并发连接 12、Socket 超时 30 s | 超长请求行/头部、413、断连均被拒 |
| 拒绝冲突 Content-Length | 出现 ≥2 个 `Content-Length` → 400；负数/非数字 → 400；缺失 → 411 | 4 种形态逐一断言 |
| 拒绝未支持 TE | 任何 `Transfer-Encoding` → 501 | 断言 |
| 取消/失败删半成品 | 未 `commit()` 的 `Sink` 在 `finally` 里 `abort()`，删掉本次新建的文档。**名字是新建的唯一名，所以绝不会误删原件** | 传一半断开 → 无正式文件、无 `.part`、已有文件不变 |
| 不误删原件 | 删除接口只能删**本次会话上传**的文件；挑来的文件只能下载 | 重复删除 → 404；`keep.me` 内容不变 |

### 4.1 已知的、刻意接受的风险

- **明文 HTTP**：这是任务明确要求（"HTTP 未加密须 UI 写可信局域网"）。令牌会出现在 URL 里，
  同网段嗅探者可以看到令牌与内容。UI 已明示。
- **令牌在 URL 里**（而非 Cookie）：好处是电脑上不需要登录状态，坏处是浏览器历史里会留。
  这是一个"短时局域网工具"，接受。
- **通知里不写地址**：地址含令牌，锁屏预览会泄漏。通知只写"点开查看"。
- **不做断点续传、不做分块传输、不支持目录上传**：刻意收窄，`limitation()` 已如实写明。

---

## 5. 验证结果（实际运行输出）

```
=== LanServerTest (恶意/边界 HTTP) ===
100 checks, all passed
=== LanLifecycleTest (端口释放 + 闲置判定) ===
16 checks, all passed
=== LanEndToEndTest (标准客户端 + 真实页面) ===
21 checks, all passed
=== 页面静态检查 ===
OK 语法可解析 / 无外链 / 无外部样式
OK 所有 12 个元素引用都存在
OK 拖拽上传 / 文件选择 / 上传进度 / 取消上传 / 下载 / 文字发送 / 状态轮询 / 跨站标记头
OK 响应式（viewport + 小屏断点）
OK 中文界面
```

复跑：

```bash
bash .scratch/lan-share/run-tests.sh       # 三组 JVM 测试 + 页面检查
bash .scratch/lan-share/compile-check.sh   # android.jar 真实编译检查
```

两个脚本都只用 `F:/mlbuild` 既有工具链，不联网，不改全局环境。

### 5.1 测试覆盖的具体断言（节选）

| 类别 | 断言 |
| --- | --- |
| 认证 | 错误令牌 404 且不含页面内容；根路径无令牌 404；正确令牌 200；令牌前缀 404 |
| 页面 | `Content-Type: text/html; charset=utf-8`；有 CSP；**无** CORS 头；不带斜杠 → 302 |
| 上传 | 中文名 + 空格 200；二进制（0x00/0xFF）字节完全一致；状态里出现；长度一致 |
| 下载 | 200；字节与上传一致；`Content-Disposition: attachment; filename*=UTF-8''`；两个中文文件各自可下载（id 唯一） |
| 重名 | 顺序 → `(1)(2)`；大小写不同也算冲突；原文件字节不变；**并发**同名 → 两个文件、内容互不相同、长度未截断 |
| 穿越 | 5 种形态被拒或落回页面；`../../../逃逸.txt` 只在接收目录；`LanNames.sanitize` 单测 |
| 头部 | 超长请求行被拒；头部总量超限被拒；重复/负数/非数字 `Content-Length` 400；缺失 411；`Transfer-Encoding` 501 |
| 跨站 | 跨站 Origin 403；同源放行；缺标记头 403；预检无 CORS 头 |
| 取消 | 传一半断开 → 无正式文件、无半成品、已有文件不受影响、服务仍可用 |
| 超限 | 声明 > 上限 → 413 且不创建任何文件 |
| 文字 | 中文往返；超长 413；空 400 |
| 删除 | 200 且文件消失；重复删除 404 |
| 端口 | 启动可连；`stop()` 后连不上；可重新绑定同一端口；重复 `stop()` 不抛 |
| 闲置 | 超阈值→闲置；未到→不闲置；**传输中永不闲置**；无时间戳/阈值为 0→不自动停 |
| 地址 | VPN 隧道排除、热点可用、纯蜂窝拒绝、公网地址拒绝、down 接口拒绝、`172.16/12` 边界 |

---

## 6. 未验证 / 已知限制（如实列出）

1. **没有真机或可用模拟器验证**。以下全部未经运行验证：
   - 前台服务在 Android 8–16 上的实际存活与通知按钮响应；
   - `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 在 Android 14+ 的实际表现；
   - `POST_NOTIFICATIONS` 拒绝后的降级提示是否显示；
   - SAF 目录授权在不同提供方（本地/SD 卡/云盘）上的行为差异；
   - 二维码在真实屏幕上能否被扫出（ZXing 生成逻辑与项目现有 `QrTool` 相同）；
   - 不同厂商热点（`ap0` vs `swlan0` vs `wlan1`）下的地址选择是否正确。
2. **只在回环地址上做过真实传输测试**。`127.0.0.1` 与局域网地址在 TCP 层没有区别，
   但"两台设备真的能互相连上"这件事本身没有实测（受防火墙、AP 隔离影响）。
   部分路由器的 **AP 隔离（client isolation）** 会阻止同一 Wi-Fi 下的设备互访，
   这不是本功能的 bug，但用户会遇到。UI 没有专门提示这一点——如果需要，可以加一行。
3. **iOS Safari 的拖拽上传不可用**（桌面 Safari 15+ 支持，移动端不支持拖拽）。
   任务描述的是"电脑浏览器"，未针对 iOS 做额外降级。
4. **浏览器上传单次上限 4 GB**，且没有断点续传。超大文件建议换传输方式。
5. **不使用 chunked 编码**：`HttpURLConnection`/`fetch`/`XHR` 在长度已知时都会带
   `Content-Length`，所以正常浏览器不受影响；但任何刻意用 chunked 的客户端会被 501 拒绝。
6. **`LanServer` 手写而非用成熟库**：换取的是零依赖 + 可在 JVM 上被真实验证。
   代价是没有 HTTP/1.1 keep-alive（每请求一个连接）、没有 TLS、没有 HTTP/2。
   对"局域网短时传文件"这个场景，这些都不是需要的东西。
7. **闲置超时 10 分钟是硬编码常量**（`LanSession.IDLE_TIMEOUT_MS`）。没有做设置项——
   没人要求，而且多一个设置就多一个用户会配错的地方。

---

## 7. 设计取舍（为什么这样做）

- **为什么不复用 `FtpShareTool`**：FTP 需要客户端装软件，而任务要求"电脑浏览器免客户端"。
  两者也解决不同问题：FTP 面向文件管理器，本功能面向浏览器。
- **为什么服务端手写而不引入 NanoHTTPD**：NanoHTTPD 是成熟库，但引入它就等于把
  "认证/边界/清理" 藏进第三方代码里，而任务要求这些必须有实际测试。手写 ~700 行后，
  每一处拒绝都能被 JVM 测试直接断言。
- **为什么 `Store` 是接口**：Android 侧用 SAF 实现，测试侧用本地目录实现，**同一个契约**。
  测试因此是在验真实的 `LanServer`，而不是一个仿造的替身。
- **为什么文件名清洗与地址选择抽成纯逻辑类**：这两处最容易出错，也最容易测。
  它们不碰 Android，所以能在 JVM 上被穷举。
- **为什么「移除文件」要真的 `unshare`**：只改本地列表会让电脑那边还能下载，
  等于界面在骗人。宁可多一个方法，也不让 UI 显示与实际不符。

---

## 8. 给 lead 的一句话

`N01` 只在三个公共文件里各需要一行：Manifest 的 `activity`+`service`+一个权限、
`selected-tools.json` 一条、`ToolCatalog` 的 group 与 name。其余全部在新增文件里，可以整块拿走或整块丢弃。
