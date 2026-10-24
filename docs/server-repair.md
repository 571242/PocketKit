# 01 服务器代理隧道修复与持久化运维

> 修复时间：2026-10-24
> 目标主机：InstaBox 机器 01（美西·俄勒冈 / AWS，公网出口 `44.244.119.140`）
> 范围：**仅** 01 上的订阅隧道链路。未改动 `app/`、`build.gradle`，未触碰 02–05 与东京主机。

## 一、故障现象

订阅地址 `https://<旧域名>.trycloudflare.com/sub` 完全不可用：客户端无法解析域名，订阅拉取失败。

## 二、根因（已取证）

| 层级 | 事实 |
| --- | --- |
| 直接原因 | `cloudflared` 进程仍在运行（存活 3 天），但服务端持续返回 `ERR Register tunnel error error="Unauthorized: Tunnel not found"`。Cloudflare 已作废该 quick tunnel 记录，进程陷入无限重连。 |
| 表现 | 旧域名 DNS 返回 NXDOMAIN；`127.0.0.1:20241/ready` 返回 `503`。 |
| 放大因素 | 原 `/usr/local/bin/tunnel-manager.py` 只在 cloudflared 启动时用正则抓取一次域名，之后没有任何健康检查或重启逻辑。隧道一旦失效，它既不重连新隧道，也不重写 `sub.yaml`，订阅被永久固定在死域名上。 |

## 三、修复内容

| 文件（服务器） | 变更 |
| --- | --- |
| `/usr/local/bin/tunnel-manager.py` | 重写为监督循环：捕获域名 → 渲染订阅；每 60s 自检（Cloudflare 报 not found / 域名不解析 / `/sub` 非 200）→ 主动终止并按退避（5s→60s）重启 cloudflared，重启后自动用新域名重写 `sub.yaml` 与 `domain.txt`。 |
| `/usr/local/bin/tunnel-watchdog.sh` | 系统级健康检查：单元不活跃、`domain.txt` 为空、`sub.yaml` 与当前域名不一致、或 `/sub` 非 200 → 重启 `tunnel-sub.service`。 |
| `/etc/systemd/system/instabox-tunnel-watchdog.{service,timer}` | 每 5 分钟执行一次上述检查，`OnBootSec=3min`，已 `enable --now`。 |
| `/etc/systemd/system/tunnel-sub.service` | 保持不变（已 `enabled`，`Restart=always`）。 |

本地源码副本见 `tools/server/`，与线上文件哈希一致。

### 未改动（刻意保留）

`xray.service`、`nginx.service`、`gost.service`、peerflix/Xvnc 等其他服务全部原样运行，未重启、未改配置。`/sub`、`/ray` 的 nginx 反代规则沿用原有配置。

## 四、备份与回滚

修复前远端备份：`/root/backup-server-repair/20261023-175018/`

```
tunnel-manager.py     原管理器
tunnel-sub.service    原单元
sub.yaml / domain.txt 原订阅与域名
xray-config.json      xray 配置快照
```

回滚：

```bash
sudo cp -a /root/backup-server-repair/20261023-175018/tunnel-manager.py /usr/local/bin/tunnel-manager.py
sudo systemctl disable --now instabox-tunnel-watchdog.timer
sudo systemctl restart tunnel-sub.service
```

## 五、验证证据

| 检查项 | 结果 |
| --- | --- |
| 隧道注册 | `INF Registered tunnel connection ... protocol=quic`（此前为 not found 循环） |
| 订阅拉取 | `https://<当前域名>/sub` 连续 3 次 `200`，1022–1067 字节，`1.66s / 1.28s / 0.83s` |
| 订阅内容 | `server:` 字段与 `domain.txt` 一致 |
| 站点首页 | `https://<当前域名>/` → `200` |
| **真实代理穿透** | 用服务器本地 xray 客户端经该域名 SOCKS 出网，`https://api.ipify.org` → `200`，出口 IP `44.244.119.140`（即 01 本机）；`gstatic generate_204` → `204` |
| 自愈演练 | 手动写入伪造死域名 → watchdog 判定 `subscription stale` 并重启 → 45s 后自动获得新域名，`/sub` 恢复 `200` |
| 误重启防护 | 健康状态下执行 watchdog 输出 `healthy: ...`，不重启 |
| 服务与自启 | `tunnel-sub` / `instabox-tunnel-watchdog.timer` / `xray` / `nginx` / `gost` 均 `active` 且 `enabled`；无 failed 单元 |

测试用的临时 xray 客户端与 `/tmp/e2e*` 目录已全部清理，未遗留进程。

## 六、稳定 URL

当前有效订阅：

```
https://settings-development-cleanup-chapter.trycloudflare.com/sub
```

> ⚠️ **该地址不保证永久固定。**它由 Cloudflare quick tunnel 随机分配，无 SLA，官方不支持固定，且可能被回收——这正是本次故障的成因。域名变更时 01 会自动自愈，但**客户端需重新拉取订阅**才能拿到新域名。

查询当前域名（无需登录）：

```bash
curl -s https://<当前域名>/sub | head -12
# 或登录 01 后：cat /var/www/html/domain.txt
```

## 七、要真正固定 URL 还需要什么

已核查 01 现状：**无** `~/.cloudflared/` 凭证、**无** `cert.pem`、**无** Let's Encrypt 证书、**443 未监听**。因此当前环境下无法配置命名隧道。

要做成长期稳定地址，需满足其一（需要额外凭据，未在本轮实施）：

1. **Cloudflare 命名隧道（推荐）**：需要一个托管在 Cloudflare 的域名 + `cloudflared tunnel login` 产生的凭证。之后用固定子域（如 `sub.example.com`）作为稳定入口，`sub.yaml` 中的 `server`/`servername`/`Host` 全部改为该固定域名，域名不再变化。
2. **自有域名 + 443 直连**：给 01 签发证书并在 443 上终止 TLS，nftables/安全组放行 443，绕过 Cloudflare 隧道。

具备上述任一条件后，`tunnel-manager.py` 的 quick-tunnel 监督逻辑即可停用，改为固定域名渲染。

## 八、已知边界

- quick tunnel 域名随时可能再次被回收；自愈能让服务恢复，但**无法让 URL 保持不变**。
- watchdog 以 5 分钟为周期，最坏情况下有约 5 分钟不可用窗口。
- 未验证 01 之外的节点、未发布 APK、未改动应用代码。
- `/etc/xray_uuid.txt` 与订阅中的 UUID 属敏感凭证，本文档不予记录。
