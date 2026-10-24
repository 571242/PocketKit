# 拾用 0.10.1：远程连接与更新入口收敛

版本 0.10.1 / code 25。直接覆盖安装 [APK](https://github.com/571242/PocketKit/releases)，不要先卸载；签名证书与 0.9.5 一致，沿用现有调试证书，不是生产发行证书。原有订阅、录音、工具数据不受影响。

## 主要改动

### 1. 远程文件连接（新增）

管理器原来只有系统文档提供器，自己并没有远程能力。这版补上四种协议，全部自己实现：

- **SFTP**：jsch 0.2.20，密码登录。不校验主机指纹（没有 known_hosts 存储），只接受密钥的服务器会明确报错而不是给一个协议错误。
- **SMB2/3**：smbj 0.13.0。要填主机、共享名和账号，路径用反斜杠，支持自定义端口。
- **FTP / FTPS**：手写。优先 `MLSD` 列目录、`EPSV` 建数据连接，两者都失败才退回 `LIST`/`PASV`。**不使用服务器在 `227` 应答里广播的 IP**——手机在 NAT 后面时那个地址通常不可路由。只支持被动模式，主动模式在手机上没有意义。
- **WebDAV**：手写，走 OkHttp。`PROPFIND` 列目录、`PUT` 上传、`MOVE` 改名、`MKCOL` 建目录。只支持 Basic 认证。

入口在「管理器 → 位置 → 远程连接」，是一个和本地双窗互相独立的界面。远程列目录可能阻塞二十秒，混进本地窗格会把用户没发起的导航一起卡住；而且四种协议对「路径」的定义本来就不同，硬塞进 SAF 的 document-id 模型得给服务器上不存在的「文档身份」编 ID。

能浏览、上传、下载、重命名、新建文件夹、删除。下载统一落到应用缓存目录 `cache/remote/`，完整路径显示在状态行。

密码用 AndroidKeyStore 里的不可导出密钥做 AES/GCM 封装后落盘，密钥永不离开密钥库。密钥库不可用时**拒绝保存**，不退回明文。

### 2. 更新入口收敛成一个按钮

原来首页有个「检查更新」按钮走自己的 APK 通道。现在只剩「全部」页右上角一个 `⋯`，其他页面和搜索状态下不显示。点开是免责声明、当前版本、可改的仓库地址（默认 `571242/PocketKit`）。

检查更新只读 GitHub Releases 的 latest，按数字段比较版本（`0.10.0 > 0.9.9`，不是字符串比较），有新版就给出「去仓库下载」和「打开发布页」两个出口。客户端不自己下载 APK——免费 VPS 带宽差，下载交给浏览器或别的下载器更合适。

仓库地址可以填 `owner/name`、GitHub 网页地址或 git 地址，会归一成 `owner/name`。

### 3. 管理器不再霸占「打开方式」

删掉了 `ManagerActivity` 的 `ACTION_VIEW` + `*/*` 过滤。那条 catch-all 会让管理器出现在**所有**应用的打开方式列表最前面，而它并不是一个通用文件查看器。

保留分享接收。菜单新增「打开所在位置」；定位按卷解析（不再是写死的 primary 根），也认 `content://media/...`。如果系统只授予了单文件读权限、父目录列表根本读不到，会跳到最近的可读上级目录并写明「未精确定位到文件本身」，而不是复制一份冒充原位置。

### 4. 顺带修掉的问题

- **视频压缩**：`createInputSurface()` 原来在 `configure()` 之前调用；muxer 的 video track 在拿到输出格式之前就 `addTrack`。改成 `INFO_OUTPUT_FORMAT_CHANGED` 之后再注册并 `start()`。Surface 模式下解码输出 `info.size` 恒为 0，原来靠它判断会漏帧，改成无条件 `releaseOutputBuffer(..., true)`。`endMs * 1000` 加了溢出保护。
- **位图回收**：6 个工具在 JPEG 输出前要展平 alpha，中间位图没在 `finally` 回收；压缩工具的质量搜索会重复展平。
- **二维码识别**：取消或 `Tasks.await` 抛异常时 `BarcodeScanner` 和 `Bitmap` 都不释放。
- **中文数字**：负数小数输出「负负三点一四」。
- **单位换算**：「顷」按 `100000/3` 算，实际 1 顷 = 100 市亩 = `200000/3` 平方米，差一倍。
- **计算器**：两千多个左括号触发 `StackOverflowError`，`Error` 不会被 `ToolException` 的 catch 拦住，整个应用崩掉。加了 64 层嵌套上限。
- **文本拆分**：白名单漏了 `[`，未转义的 `[` 在字符类里开嵌套类抛 `PatternSyntaxException`。
- **纪念日**：`2020-05-20` 被判成「每年重复」，一次性纪念日不可用。
- **代理规则产物**：`<sha>.json` 按内容哈希命名且从不清理，每份可达 12 MB。
- **VPN 服务**：停止过程中收到 `start` 会静默丢弃，按钮显示已连接但核心没起。
- **悬浮窗**：`OutOfMemoryError` 逃出 `catch(Exception)` 时 `busy`/`capturing` 永久为 true。
- **复制循环**：4 处 `while (read(buf) > 0)` 改成 `!= -1` 并跳过 0 字节读。

## 审查后改掉的问题

初版写完后做了一次独立代码审查，六处会让功能实际不可用或静默出错的问题已修：

1. **WebDAV 整套不可用**：原来用 `HttpURLConnection` 发 `PROPFIND`/`MKCOL`/`MOVE`，但 Android 的实现有方法白名单，其他方法在 `setRequestMethod` 直接抛 `ProtocolException`。改用 OkHttp。
2. **FTPS 只加密命令通道**：发了 `PROT P` 却没给数据连接套 TLS，文件内容照旧明文传；`PBSZ`/`PROT` 返回码也没检查。现在数据连接同样握手，被拒即中止。
3. **TLS 不校验主机名**：裸 `SSLSocket` 只验证书链，签给别的域名的合法证书也能过。控制与数据连接都设了 `setEndpointIdentificationAlgorithm("HTTPS")`。
4. **`TYPE I` 失败被吞**：服务器留在 ASCII 模式时二进制文件会被改行尾。
5. **SMB 句柄泄漏**：smbj 的流 `close()` 只清自己引用不关 `File`，每次传输在服务器上留一个句柄。
6. **UI 线程关连接**：FTP 的 `QUIT` 一个来回就能卡到 ANR；正在连接还没被采纳的 client 在销毁后会被漏掉。现在有 `released`/`pending` 跟踪，关闭全放后台。

## 安装包实测

- 新包：276390813 字节（263.6 MiB）。
- 上一版：275814369 字节（263.0 MiB）。
- 增加：576444 字节（0.6 MiB），来自新增的 jsch + smbj + mbassador + asn-one + slf4j。
- SHA-256：`a0c16d21eec5092d6a134c5ce4ed924681d231d7fcfd652739e76b2d3f343974`；另附 `PocketKit-0.10.1.apk.sha256`。

## 验证到什么程度

已通过：

- `:app:assembleDebug`、`:app:lintDebug` 均 BUILD SUCCESSFUL。
- `RemotePanelContractTest` 63 条断言。
- `RemoteProtocolTransportTest` 18 条（本机起 TCP 服务器验多行应答拆包、PASV 端口 `hi*256+lo`、0 字节读不截断传输、文件名压平）。
- `ManagerOpenWithContractTest` 12 条、`ManagerAlgorithmsTest` 299 条。

**没有验证到的**：

- 没有任何一台 Android 真机或模拟器，「应用能装能跑」本身未经证实。
- 四种协议都没连过真实服务器。SFTP/SMB 的库握手、FTPS 的 TLS 升级、各家 WebDAV 服务端的 `PROPFIND` 差异，全部只在代码层面检查过。
- 密码加解密依赖 AndroidKeyStore，只在真机上才真的有实现。

所以这一版的功能性声明只到「代码写完、能编译、逻辑测试过」，不到「确认可用」。如果你要连自己的服务器试，建议先用 SFTP 或 WebDAV 这类报错清楚的协议。

## 已知限制

- SFTP 不支持密钥文件，不校验主机指纹。
- WebDAV 只支持 Basic 认证，服务器只接受 Digest 会登录失败。
- FTP 只支持被动模式；不勾选加密时密码明文传输，仅建议局域网用。
- 删除目录要求目录为空（四个协议现在行为一致，都不递归）。
- 远程目录和本地目录之间没有直接互相复制，需要经过应用缓存中转。
