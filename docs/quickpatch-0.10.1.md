# 0.10.1 更新说明

版本号 `versionCode 25` / `versionName 0.10.1`，上一版是 `24` / `0.10.0`。

## 一、远程文件连接（本次新增的主体）

管理器原来只是宣称支持云端，实际只有系统文档提供器，没有自己的远程能力。这版补上了四种协议的客户端，全部自己实现，不依赖第三方的文件管理器组件。

入口：管理器 → 「位置」 → 「远程连接」。

| 协议 | 库 | 认证 | 明确不支持 |
| --- | --- | --- | --- |
| SFTP | jsch 0.2.20 | 密码 | 密钥文件、主机指纹校验 |
| SMB2/3 | smbj 0.13.0 | 账号密码、来宾 | SMB1、域认证的特殊配置 |
| FTP / FTPS | 手写 | 账号密码、匿名 | 主动模式 |
| WebDAV | 手写（走 OkHttp） | Basic | Digest、OAuth |

四项都能浏览目录、上传、下载、重命名、新建文件夹、删除。FTP 优先用 `MLSD` 列目录，服务器不支持时退回解析 Unix 风格 `LIST`；数据连接优先 `EPSV`，退回 `PASV`，并且**不使用服务器在 `227` 应答里广播的 IP**——手机在 NAT 后面时那个地址通常不可路由，只用控制连接的对端地址。WebDAV 的 `PROPFIND` 解析关闭了 DOCTYPE 和实体展开，避免服务器构造外部实体。

### 密码怎么存

用 AndroidKeyStore 里生成的一把不可导出 AES 密钥，对密码做 AES/GCM 加密，IV 和密文一起写进应用私有 SharedPreferences。密钥本身永不离开密钥库。密钥库不可用（模拟器或系统异常）时，保存直接失败并告知原因，**不会退回明文保存**。

没有引入 `androidx.security:security-crypto`，因为那要几十万字节的包体，而这里需要的只是十几行平台 API。

### 下载落在哪里

统一落到应用缓存目录 `cache/remote/`，状态行会显示完整路径。服务器返回的文件名会按 `/` 和 `\` 切段，丢掉 `.`、`..` 和空段——否则一个恶意或异常的服务器只要返回名字 `../../x`，就能把文件写到缓存目录外面。

### 为什么是独立界面而不是文档提供器

四种协议对「路径」的定义不一样：SFTP 和 FTP 是 POSIX 路径，SMB 是不区分大小写的反斜杠路径，WebDAV 是 URL。硬塞进 SAF 的 document-id 模型就得给服务器上并不存在的「文档身份」编造 ID，之后所有基于 ID 的比较、持久化授权都会失真。所以远程浏览是独立面板，本地双窗保持原样，两者互不影响——远程列目录可能阻塞二十秒，混进本地窗格会把用户没发起的导航卡住。

## 二、更新入口收敛

原来首页有一个「检查更新」按钮走自己的 APK 通道。现在：

- 只剩「全部」页右上角一个 `⋯` 按钮，其他页和搜索状态下不显示。
- 点开是底部面板：免责声明、当前版本号、可编辑的仓库地址（默认 `571242/PocketKit`）、「检查更新」「打开仓库」。
- 检查更新读 GitHub Releases 的 latest，按数字段比较版本（`0.10.0 > 0.9.9`，不是字符串比较），有新版就给出「去仓库下载」和「打开发布页」两个出口，用系统浏览器打开。

免费 VPS 带宽差，所以客户端不自己下载 APK，把选择权交给用户和浏览器。

仓库地址可以填 `owner/name`、GitHub 网页地址或 git 地址，会统一归一成 `owner/name`。

## 三、管理器文件关联反转

`AndroidManifest.xml` 里删掉了 `ManagerActivity` 的 `ACTION_VIEW` + `content`/`file` + `*/*` 过滤。原来那条 catch-all 会让管理器出现在**所有**应用的「打开方式」列表最前面，霸占别人的入口，而它又不是一个通用文件查看器。

保留 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`，接收分享仍然可用。菜单里新增「打开所在位置」，用于从外部进来的单文件定位。

定位策略改了：原来写死 primary 存储根，现在按卷解析（`volumeRoot`/`volumeOf`），能处理 SD 卡和 `MediaStore` 的 `content://media/...`。如果系统只授予了单文件的读权限，父目录列表根本读不到，这时跳到最近的可读上级目录，并明确写「未精确定位到文件本身」，而不是复制一份文件冒充原位置。

## 四、顺带修掉的问题

这些是检查过程中发现的、会静默出错但不一定立刻崩的地方：

- **视频压缩**：`createInputSurface()` 原来在 `configure()` 之前调用；muxer 的 video track 在拿到输出格式之前就 `addTrack`。改成编码器 `INFO_OUTPUT_FORMAT_CHANGED` 之后再注册轨道并 `start()`，写入前跳过 `CODEC_CONFIG`。Surface 模式下解码输出的 `info.size` 恒为 0，原来靠它判断会漏帧，改成无条件 `releaseOutputBuffer(..., true)`。两处 `endMs * 1000` 加了溢出保护。
- **位图回收**：6 个工具在 JPEG 输出前需要展平 alpha，原来展平出的中间位图没有在 `finally` 里回收。新增 `ImageIo.encodeFlattened`，并在压缩工具里让质量搜索只展平一次。
- **二维码识别**：取消或 `Tasks.await` 抛异常时，`BarcodeScanner` 和 `Bitmap` 都不会释放。改成 `finally` 里统一关。
- **中文数字**：负数小数会输出「负负三点一四」，剥掉整数字符串自带的前导 `-`。
- **单位换算**：「顷」按 `100000/3` 算，实际 1 顷 = 100 市亩 = `200000/3` 平方米，差一倍。
- **计算器**：两千多个左括号会 `StackOverflowError`，而 `Error` 不会被 `ToolException` 的 catch 拦住，整个应用崩掉。加了 64 层嵌套上限。
- **文本拆分**：分隔符白名单漏了 `[`，未转义的 `[` 在字符类里会开嵌套类，抛 `PatternSyntaxException`。
- **纪念日**：`d.length() <= 5 || 第一个 '-' != 最后一个 '-'` 这个判断会把 `2020-05-20` 判成「每年重复」，一次性纪念日实际不可用。改成数连字符个数。
- **时钟计算**：改 `multiplyExact`/`addExact`，超范围给明确提示而不是静默溢出成负数。
- **代理规则产物**：`<sha>.json` 按内容哈希命名且从不清理，每份可达 12 MB。提交成功时删掉既非当前也非上一版的产物。
- **节点池**：`getNodes()` 每次都在主线程重新解析整个节点池，加了 `loaded` 标记。
- **VPN 服务**：停止过程中 `onStartCommand` 收到 `start` 会静默丢弃，导致按钮显示已连接但核心没起。改成停稳后补发一次；`stopSelf()` 改用当前 `startId`。
- **悬浮窗**：`OutOfMemoryError` 逃出 `catch(Exception)` 时 `busy`/`capturing` 永久为 true，悬浮窗按钮全失效。加了 `catch(Error)` 复位后重抛。
- **复制循环**：4 处 `while (read(buf) > 0)` 改成 `!= -1` 并跳过 `0` 字节读。

## 五、依赖

`app/libs/` 新增 5 个 jar：`jsch-0.2.20`、`smbj-0.13.0`、`mbassador-1.3.0`、`asn-one-0.6.0`、`slf4j-api-2.0.13`。许可证分别是 Revised BSD / Apache-2.0 / MIT / Apache-2.0 / MIT，都可随包分发。

**bcprov 没有单独打包**：`pdfbox-android` 已经传递引入 `org.bouncycastle:bcprov-jdk15to18:1.72`，再塞一份 `bcprov-jdk18on` 会触发 `checkDebugDuplicateClasses` 失败。核对过 smbj 引用的 BC 类（`GCMBlockCipher`、`CCMBlockCipher`、`KDFCounterBytesGenerator`、`MD4Digest`、`CMac`、`RC4Engine` 等）在 1.72 里都存在。

## 六、审查后改掉的问题

初版写完后做了一次独立代码审查，发现六处会让功能实际不可用或静默出错的问题，都改了：

1. **WebDAV 整个协议不可用**：原来用 `HttpURLConnection` 发 `PROPFIND`/`MKCOL`/`MOVE`，但 Android 的实现有一份方法白名单，只有 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE/PATCH，其他方法在 `setRequestMethod` 就抛 `ProtocolException`。也就是说列目录、建目录、重命名全都跑不起来。改用 OkHttp（本来就在 classpath 里，现在显式声明版本，避免跟着 pdfbox 浮动）。
2. **FTPS 只加密了命令通道**：发了 `PROT P` 却没给数据连接套 TLS，文件内容照旧明文传输；`PBSZ`/`PROT` 的返回码也没检查，服务器拒绝加密时会继续明文传。现在数据连接同样握手，`PROT P` 被拒就中止。
3. **TLS 不校验主机名**：裸 `SSLSocket` 只验证书链，签给别的域名的合法证书也能过。控制与数据连接都设了 `setEndpointIdentificationAlgorithm("HTTPS")`。
4. **`TYPE I` 失败被吞掉**：不检查返回码，服务器留在 ASCII 模式时二进制文件会被改行尾。现在失败即中止。
5. **SMB 句柄泄漏**：smbj 的流 `close()` 只清自己的引用，不关 `File`，每次上传下载都会在服务器上留一个句柄。改成包装流，`close()` 时同时关底层流和 `File`。
6. **UI 线程关闭连接**：`adopt()` 和 `release()` 原来在 UI 线程直接 `client.close()`，FTP 的 `QUIT` 一个来回就能卡到 ANR；而且正在连接、还没被采纳的 client 不在 `open` 列表里，销毁后会被漏掉。现在加了 `released` 标志和 `pending` 跟踪，所有关闭都放后台线程，回主线程的回调先检查是否已释放。

建议项也一并处理了：SMB 现在真的用你填的端口（原来固定 445）；WebDAV 没填端口时按是否加密选 443/80（原来固定 443，不加密时会拼出 `http://host:443`）；`parentOf` 对第一层目录返回根路径而不是拒绝；新建/重命名拒绝 `/`、`\`、`.`、`..`；下载先写 `.part` 再原子替换，失败不留半文件；删目录的文案改成「必须是空的」（SFTP/FTP/WebDAV 的删目录本来就不递归，SMB 也改成非递归了）。

## 七、验证到什么程度

已通过：

- `:app:assembleDebug`、`:app:lintDebug` 均 BUILD SUCCESSFUL。
- `RemotePanelContractTest` 63 条断言（协议分发、依赖打包、密码不明文、下载名清洗、流循环、生命周期释放、SMB 重命名用句柄、FTP EPSV/PASV/MLSD、WebDAV 关 DOCTYPE、busy 互斥）。
- `RemoteProtocolTransportTest` 18 条断言（本机起 TCP 服务器验多行应答拆包、PASV 端口 `hi*256+lo`、0 字节读不截断传输、文件名压平）。
- `ManagerOpenWithContractTest` 12 条、`ManagerAlgorithmsTest` 299 条。

**没有验证到的**：

- 没有任何一台 Android 真机或模拟器，所以「应用能装能跑」本身未经证实。
- 四种协议都没有连过真实服务器。SFTP/SMB 依赖库的真实握手、FTPS 的 TLS 升级、WebDAV 各家服务端的 `PROPFIND` 差异，全部只在代码层面检查过。
- 密码加解密依赖 AndroidKeyStore，只在真机上才真的有实现。

所以这一版的功能性声明只到「代码写完、能编译、逻辑测试过」，不到「确认可用」。
