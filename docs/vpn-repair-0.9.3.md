# PocketKit 0.9.3 修复与验证记录

## 交付

- 安装包：[PocketKit-0.9.3.apk](../dist/PocketKit-0.9.3.apk)，versionCode 21，ARM64/ARM32，467684470 字节（约 446 MiB）。加入真实原生代理内核后包体比旧版大。
- SHA-256：`52a3d634c44041f761ad74a0f3bad15121efb26d1f803109c26b71e7f0dbd3fc`。
- apksigner 验签通过，与 0.9.2 的签名一致，可覆盖升级。无需卸载或清空本地数据。
- 01 更新服务已发布同一字节内容，远端全量 SHA-256 一致；公网元数据 0.9.3/code21、下载 HTTP 200、Content-Length 与本地文件一致。公网下载仅验证响应头和 ZIP 前缀，未再次全量下载。
- 云端配置版本 `2026.10.24.6`，手机打开新版时检查更新并同步东京固定 HTTPS 双节点订阅；失败不清空已有有效节点、不把失败同步记为成功。

## 断网根因与修复

旧实现创建 TUN 和默认路由，却没有实际转发 TUN 数据的代理内核，因此系统流量被送进黑洞；流量统计与部分失败测速还存在模拟值。旧解析器只留下节点摘要，丢失 UUID/密码/TLS/传输参数，单纯恢复订阅 URL 不能修复客户端。

现接入 libbox 1.14.3，采用 CommandServer、PlatformInterface、VpnService.protect(fd)；真正转发 TUN IPv4/IPv6 数据，核心套接字绕开自己的 VPN。流量来自内核状态订阅，TCP 连接失败如实显示失败。出口检测在连接时通过本地 mixed 入站，断开时使用直连；过期异步结果不会覆盖新连接。

生成当前内核版本的 typed DNS、DNS 劫持规则、真实出站；代理服务器域名通过直连 DNS 解析，避免解析回环。修掉会在实际启动时致命失败的 `direct-dns detour=direct`：空 direct 出站不能作 DNS detour；该项静态 `check` 能通过，启动才失败，已用真实运行测试捕获。

规则模式范围为私网与 `.cn` 直连，其他代理；`.cn` DNS 也直连。并不执行订阅内全部自定义 Clash rules，界面已明确说明。支持 VLESS、VMess、Trojan、Shadowsocks、Hysteria2 及已实现的 TLS/REALITY/WS/gRPC 等参数；不支持的扩展明确拒绝，不静默丢字段。旧版本缺凭证节点须重新同步。

重载前先构建、静态校验；校验不通过保留旧连接并还原选择。连接过程中修改配置会排队收敛到最新请求；进程内核心资源串行启停，旧服务清理不会覆盖新服务状态。失败/停止会清理 Android 与原生 TUN 句柄。Android 8/9 使用 procfs 进程查询路径。

多实例订阅写入使用同一事务锁，每次读取新快照；只替换当前订阅的节点，不覆盖其他订阅。网络响应限长、支持压缩/受限重定向；不接受嵌入账户口令的订阅 URL，拒绝 HTTPS 降级跳转；错误日志不记录订阅 URL 或认证字段。

## 固定订阅与持久运行

东京固定的基础是保留公网 IP、VLESS+REALITY 服务和 systemd 自启；管理 HTTPS 使用可自动续期的 IP 证书，不依赖随机 quick-tunnel 域名。

01 已发布固定 InstaBox HTTPS 端口 12443，平台记录 `expiresAt: null`，经 `/ray` WebSocket 到 Xray。quick tunnel 仅作备用。东京聚合每 10 分钟从固定入口刷新；独立东京种子避免读取自身产物；旧 2088 原订阅与新 443 HTTPS 入口共用同一原子输出文件。两入口均两节点，原东京节点所有字段未变；上游失败时保留 last-known-good。

- Tokyo Reality：真实出口 `168.110.52.174`。
- Instabox-US-01：真实出口 `44.244.119.140`。
- 两节点均经真实代理完成外部 HTTPS 204 与 DNS 查询。

运维与回滚见 [东京订阅记录](tokyo-subscription-merge.md) 和 [01 恢复记录](server-repair.md)。私密 URL、UUID、密钥不写本文。固定不等于永不失效：仍依赖机器/平台可用、证书续期和服务正确运行。

## 其他确定缺陷

- Base64：任何复制/解码前检查长度，8 Mi 字符上限，约 6 MiB 解码数据；仍需足够图像解码内存，不宣称任何图片都不会 OOM。
- LocalStore：新记录对称转义，旧记录按旧格式读取，避免改写原有反斜杠、换行与分隔符。
- FTP：瞬时 accept 错误退避重试；监听关闭时清理真实状态；单连接五次错误登录后断开。FTP 仍是明文局域网协议，不是 FTPS。
- ONNX Runtime Android 升为 1.22.0；最终 APK 所有 7 个 ARM64 ELF 库的 LOAD 对齐不小于 16 KiB，未压缩原生库 APK 偏移也对齐。
- 更新服务器：管理页面/写接口强制 Basic 鉴权，localhost 不豁免，口令缺失失败关闭；文件名白名单、上传限长、原子文件替换、缺失字段保留、跨站 Origin 检查。公共检查/下载继续可用。详见 [更新服务安全记录](update-server-security.md)。

## 实际执行的验证

1. Gradle 离线 assembleDebug、assembleDebugAndroidTest、testDebugUnitTest 成功。现有测试主要是 main 入口，未把 Gradle 空测试任务当作运行证据。
2. 7 个现有 JVM main 自测逐个实际执行，全通过。
3. 订阅解析/配置 113 项、持久化/合并/URL 29 项，共 142 项通过。
4. Base64/LocalStore 24 项通过；FTP 65 项真实套接字测试通过。子任务还做了旧实现负向回归，确认断言确实能抓住旧缺陷。
5. 官方同版本 sing-box CLI 对应用解析器输出的 DIRECT、双节点 GLOBAL/RULE 共 5 份真实订阅配置检查通过；DIRECT 真 DNS+HTTPS 通过；双节点 GLOBAL 与东京 RULE 的握手、出口、HTTPS 204、DNS 劫持/查询通过。CLI 测试仅移除 Android TUN、改用本地测试入站，出站与 DNS 由应用解析器生成。
6. 新旧 APK 签名身份一致；最终包内容、ARM ABI、原生 ELF/APK 16 KiB 对齐及许可资源通过检查。
7. 更新服务子任务初版 45 项远端验证、10 项本地测试通过；主会话部署最终 512 MiB/Origin/订阅目标支持后再次执行 10 项本地测试，并远端验证匿名写 401、跨站写 403、正常上传/保存、全量哈希、公网版本/订阅/下载响应。
8. 东京旧/新订阅外部请求均 200、两节点、内容一致；证书校验通过；合并 timer active/enabled，手动再执行仍两节点，其他主要服务保持运行。

## 未验证及保留边界

没有连接真实 Android 手机。本机缺硬件加速的软件模拟器始终 adb offline，已停止，因此**未实际执行 Android TUN、VPN 授权界面、Wi-Fi/蜂窝切换、待机/系统回收、真实手机升级安装**。设备测试 APK 已编译，但未把它记为运行通过。新 ONNX 原生库未在真机执行语音推理。

未整机重启，也未强制续期证书，自启依据 systemd enabled 与运行/故障恢复证据。检测到另有与本任务无关的东京 session-sync 服务失败，未擅自修改。未承诺发现或修复整个工具箱所有潜在问题。源码初始备份位于项目私有 scratch，未包含在发布安装包。
