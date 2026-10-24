# PocketKit 0.9.4：云端规则自动同步

## 本次边界

PocketKit 使用 **sing-box/libbox 1.14.3**，不是 FlClash 的 Mihomo。原规则模式只有私网和 `.cn` 直连；订阅中的完整 Clash rules 并没有被执行。本次增加独立的、只含域名和 IP 数据的规则热更新，不把 FlClash 配置或原生库直接覆盖进应用。

- **可自动更新**：国内直连域名、明确代理域名、中国 IP 网段。
- **自动跟踪、不能热替换**：FlClash、Mihomo、sing-box 的稳定版本及发布说明。新增协议/原生内核仍需重新构建并安装兼容 APK。
- 不添加远程脚本执行、未经验证的核心下载、广告拦截规则或新的节点。

## 云端已部署

主机：现有 InstaBox-01。原更新中心入口保持不变，仍使用 HTTPS 和原管理鉴权。

- `pocketkit-rules-sync.timer`：每小时检查，附带最多五分钟随机延时，开机后补上错过的执行。
- `pocketkit-rules-sync.service`：固定可信源、条件请求、完整校验后原子发布；由 systemd 独立执行，不依赖本地终端或本次会话。
- 源码：[同步器](../tools/update-server/rules-sync.py)、[服务单元](../tools/update-server/pocketkit-rules-sync.service)、[定时单元](../tools/update-server/pocketkit-rules-sync.timer)、[更新中心](../tools/update-server/server.py)。
- 仅重启过更新中心；nginx、Xray、gost、tunnel-sub、keepalive 的主进程启动时间与部署前相同。原应用版本元数据和订阅地址在规则部署时逐字节未变。

### 当前规则快照

| 数据 | 条目 |
| --- | ---: |
| 国内直连域名 | 110235 |
| 明确代理域名 | 27313 |
| 中国 CIDR | 10011 |
| 总计 | 147559 |

JSON 大小 2258010 字节；schemaVersion=1。首版 SHA-256：

`95ee8bb95bcf90b1e4feb2a89ce2c162b92f0b17da79ba3bbcf6946d1e0536ee`

可信数据源：[Loyalsoldier/clash-rules](https://github.com/Loyalsoldier/clash-rules)，只使用 direct、proxy、cncidr；先取得 release 分支的同一个提交，再从固定提交读取三份文件，避免混合不同快照。GPL-3.0 许可、原始文件及各自 SHA-256 一并保留、公开提供，转换结果不是无来源的规则库。

稳定发布监测：[FlClash](https://github.com/chen08209/FlClash/releases)、[Mihomo](https://github.com/MetaCubeX/mihomo/releases)、[sing-box](https://github.com/SagerNet/sing-box/releases)。初次发现分别为 v0.9.2、v1.19.33、v1.14.4。监测结果只是升级审阅线索，不表示 PocketKit 已拥有这些版本的全部协议。

### 公开只读接口

- `GET /api/check-update`：旧字段保留，新增独立 rulesVersion/rulesSha256/rulesUrl/rulesBytes/rulesSchemaVersion 和 upstreamReleases，不用节点 configVersion 冒充规则版本。
- `GET /rules/<sha256>.json`：按内容哈希命名的规则文件。
- `GET /rule-sources/<sha256>.txt` 与 `/rule-sources/LICENSE.txt`：原始来源快照及许可。
- `GET /api/rule-status`：最后尝试/成功时间、错误、当前快照及上游版本观察。没有管理口令或节点认证信息。

所有写接口及管理页面继续强制鉴权。规则目录与源码缓存不可列目录，也不可通过任意文件名访问。

## 手机端机制

先覆盖安装支持新规则机制的版本；旧 APK 不能因为云端多出一个接口就自动获得执行能力。无需卸载，不清空已有订阅和节点。

- 应用启动、手动检查更新时同步；VPN 连接期间每十五分钟静默检查，停止服务后取消定时，不额外添加永久后台服务。
- 清单和文件必须同一 HTTPS origin，拒绝跨源/降级重定向；有字节和条目上限，SHA-256、JSON schema、域名/CIDR 及原生核心校验通过后才采用。
- 规则模式使用国内 `.com` 等域名和 CN 网段，不再仅靠 `.cn`。明确代理域名例外与精确域名例外均有对应优先级；DNS 同步分流。
- 全局/直连模式不受云规则覆盖。规则文件不包含订阅或节点参数，不修改节点选择。
- 失败保留上一份有效数据；没有有效云规则时使用随包的少量基础规则。代理页显示规则短版本、数量与更新状态。

这里的“自动热更新”是定时检查后重新加载配置，**不是 FCM 实时推送，也不承诺不断开任何长连接**。原生内核/新增协议不在这个数据通道内自动替换。

## 运维与回滚

服务器本机：

```bash
systemctl status pocketkit-rules-sync.timer
systemctl list-timers pocketkit-rules-sync.timer
sudo systemctl start pocketkit-rules-sync.service
journalctl -u pocketkit-rules-sync.service -n 30 --no-pager
```

规则数据目录为 `/data/pocketkit-hub`，失败详情见 `rules-state.json`，当前指针见 `rules-manifest.json`。不要手改正在分发的哈希命名文件；修改数据须生成新哈希并切换清单。

部署前备份位于服务器 `/root/pocketkit-rules-094-backup-20261024-100917/`。紧急停用自动同步：

```bash
sudo systemctl disable --now pocketkit-rules-sync.timer
```

需要撤回规则时，先停 timer，再将备份的有效清单原子恢复；首次部署没有旧云规则清单，可移除清单停止继续分发，但已下载的手机会继续使用 last-known-good，而不是被远程清空。要回退手机已采用的数据，应把上一份有效规则重新发布为清单目标。

不要回退到早期匿名写入版更新服务，也不要删除管理凭据。服务器历史规则/来源快照当前保留；长期运行仍需关注磁盘、主机与平台入口的可用性。

## 验证边界

云端转换、失败回退、条件请求和损坏缓存自测已执行；Hub 鉴权及新只读端点回归已执行。规则和原始来源文件经公网读取后 SHA-256 一致。InstaBox 公网将源站的 immutable Cache-Control 改为 no-store；手机按版本比较决定是否下载，不依赖该缓存头。一次公网匿名管理接口复测发生客户端 TLS EOF，未取得 HTTP 结果；服务器本机匿名请求仍返回 401，不把该次网络中断记为公网鉴权通过。

### 已实际通过

- JVM：ProxyConfig 113 checks、节点持久化 29 checks、云规则 161 checks；覆盖未知字段、字节/条目上限、损坏文件、同源/路径、失败写入和精确域名优先级。
- 云端：Hub 11 个用例；同步器 8 个用例，含 ETag/304、损坏缓存、幂等发布、原生拒绝和保留旧清单。
- 真实 sing-box 1.14.3：六份完整客户端配置通过原生 check，包含双节点的规则/全局模式、直连和首次规则预检模板；另做固定版本 schema 的关键规则字段兼容性检查。
- 双节点实流量：出口 IP 与预期一致、代理 HTTPS 204、国内 `.com` HTTPS 200、国内/国外分流 DNS 均通过。`adcdownload.apple.com` 精确直连例外优先于 `apple.com` 代理后缀，`scholar.google.cn` 仍明确走代理；DNS 同步遵守该顺序。
- Gradle 离线构建：assembleDebug、assembleDebugAndroidTest、lintDebug 均成功。AndroidTest 仅构建，未运行在设备上。
- APK：0.9.4 / code 22，467984475 字节；签名证书与 0.9.3 一致，可覆盖升级；ARM32/ARM64、ARM64 ELF 和 APK 16 KiB 对齐、规则代码/默认数据/许可打包均通过。沿用现有调试证书，不冒充生产签名。
- 云端发布后重算整包 SHA-256，与本地一致；更新元数据已切到 0.9.4，原 configVersion/订阅地址逐项比较未变；Hub 与 timer 均 active，timer enabled，已自动完成 2026-10-24T11:00:43Z 的下一轮同步。

本地 [APK](../dist/PocketKit-0.9.4.apk) 与 [SHA-256](../dist/PocketKit-0.9.4.apk.sha256) 已保存。云端下载入口：[PocketKit-0.9.4.apk](https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/releases/PocketKit-0.9.4.apk)。完整脱敏记录见 [验收 JSON](../evidence/proxy-rules-094-verification.json)。

整包 SHA-256：`b7f63733b09dd458f00338ba73145d4ac353a3a2b35d33a2ed96d3951738ce69`。

### 尚未验证与环境限制

当前没有连接真实 Android 设备，不声称已经验证真机 TUN、首次 Android 原生初始化、连接中的规则热重载、Wi-Fi/蜂窝切换、待机保活或覆盖安装。桌面原生核心联调不能替代这些项目。

最终从本机访问公网更新元数据时再次出现 TLS 握手 EOF，未取得 HTTP 响应，因此**新版 APK 的公网终验在当前网络中未完成**；没有关闭证书校验、改用 HTTP 或反复换下载命令。源站完整包摘要、元数据、定时器与订阅保护已通过 SSH/本机接口验证；此前规则文件及来源快照经公网 HTTPS 获取并核对过摘要。不能把源站成功写成当前公网下载已经验通。
