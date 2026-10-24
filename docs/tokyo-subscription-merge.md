# 东京固定双节点订阅运维记录

更新时间：2026-10-24（UTC）

## 结果

东京原有 VLESS+REALITY 节点与 InstaBox 01 的 VLESS+WebSocket+TLS 节点已合并到东京固定订阅。443 私密 HTTPS 入口与原 2088 旧 URL 现在都返回同一份双节点聚合结果；2088 的端口、旧 URL 和 TCP 多路复用行为不变。完整私密订阅 URL 仅保存在 `.scratch/server-merge/result.json`，本文不记录 token、UUID 或 REALITY 密钥。

本次未修改 PocketKit app、PocketKit Hub、机器 metadata、DSH、OpenList 或 Cockpit。

## 实际链路

1. `arm-subscription.service` 继续在东京 `2088` 提供原 URL 和 TCP 多路复用；服务进程记住旧 YAML 路径、每次请求重新读文件。该路径现为指向聚合结果的符号链接，因此无需重载 2088 服务。
2. `tokyo-sub-merge.timer` 每 10 分钟运行 `tokyo-sub-merge.service`。
3. `/usr/local/bin/tokyo-sub-merge.py` 固定从 `/opt/subscription-merge/tokyo-seed.yaml` 读取原东京节点；该 0600 种子由切换前的原 YAML 复制，聚合器不会读取自身输出。
4. 聚合器从 01 固定入口获取当前 Xray 参数，只白名单保留 UUID 与 WS path，并把 server/SNI/Host 固定为 InstaBox 12443 发布域名。
5. 合并结果先写临时文件、`fsync`，再用 `os.replace` 原子替换 `/opt/subscription-merge/subscription.json`。443 与旧 2088 路径读取同一文件；上游失败时仅更新状态文件，两个入口都保留 last-known-good。
6. 东京 Nginx 443 用独立精确私密 location alias 到该合并文件；其他 location 顺序与内容未改变。
7. 01 上的 Nginx 监听 12443，仅提供 `/ray` 与 `/sub`；`/ray` 反代本机 Xray WS 入站。InstaBox 平台把 12443 发布为固定 HTTPS 域名，`expiresAt` 为 `null`。Quick Tunnel 仍保留为备用，不写入聚合订阅。

## 部署文件

| 位置 | 文件 | 状态 |
| --- | --- | --- |
| 本机 | `tools/server-tokyo/tokyo-sub-merge.py` | 与东京远端 SHA-256 一致 |
| 本机 | `tools/server-tokyo/tokyo-sub-merge.service` | 与东京远端 SHA-256 一致 |
| 本机 | `tools/server-tokyo/tokyo-sub-merge.timer` | 与东京远端 SHA-256 一致 |
| 东京 | `/opt/subscription-merge/tokyo-seed.yaml` | 原东京节点固定种子，0600，不读取聚合输出 |
| 东京 | `/opt/subscription-merge/subscription.json` | 2 个节点，原子更新，443 与旧 2088 共享 |
| 东京 | `/opt/subscription-merge/state.json` | 最近一次刷新状态 |
| 东京 | `/opt/subscription/<旧文件名>.yaml` | 符号链接到同一聚合结果；旧 URL 不变 |
| 东京 | `tokyo-sub-merge.timer` | active / enabled，启动后 60 秒首次运行，之后每 10 分钟 |
| 01 | Nginx `xray-fixed` 站点 | 监听 12443，`/ray` 与 `/sub` 均存在 |
| 01 | `xray.service` | active / enabled，VLESS WS `/ray` |

本机三个运维文件的 SHA-256：

- `tokyo-sub-merge.py`: `8f6531d66d5629a5f670dff5c002b7b305bcaf233335907d27be9124c760d98a`
- `tokyo-sub-merge.service`: `36213411916581439250866c3e15ca2eccf634eef7fa8d3260e168d014541ec9`
- `tokyo-sub-merge.timer`: `e19228408458cb95ca241bb2cd1561baa46c758890dcf711c55b461f45c0f2c2`

## 备份

- 东京初次聚合：`/root/pocketkit-sub-merge-20261023T181838Z`（10 个文件，含 `SHA256SUMS`，目录权限仅 root）
- 东京旧入口兼容：`/root/pocketkit-sub-compat-20261023T235957Z`（切换前的 2088 server、原 YAML、merge.py 与 `SHA256SUMS`，目录权限仅 root）
- 01：`/root/pocketkit-xray-fixed-20261023T181926Z`（7 个文件，含 `SHA256SUMS`，目录权限仅 root）

### 东京兼容回滚

仅让旧 2088 URL 恢复为原东京单节点、保留 443 聚合入口时：

```bash
backup=/root/pocketkit-sub-compat-20261023T235957Z
legacy=$(find /opt/subscription -maxdepth 1 -name '*.yaml' -print -quit)
sudo install -o root -g root -m 0755 "$backup/tokyo-sub-merge.py" /usr/local/bin/tokyo-sub-merge.py
sudo install -o root -g root -m 0644 "$backup/legacy-subscription.yaml" "${legacy}.restore"
sudo mv -fT "${legacy}.restore" "$legacy"
sudo systemctl start tokyo-sub-merge.service
```

2088 服务按请求重新读取同一路径，无需重载；恢复后旧入口为原东京单节点，443 仍由聚合 timer 更新。

### 东京完整回滚

完全撤销聚合入口时，先按上面的兼容回滚恢复旧 YAML，再执行：

```bash
sudo systemctl disable --now tokyo-sub-merge.timer
sudo cp -a /root/pocketkit-sub-merge-20261023T181838Z/usr/local/bin/tokyo-sub-merge.py /usr/local/bin/
sudo cp -a /root/pocketkit-sub-merge-20261023T181838Z/etc/systemd/system/tokyo-sub-merge.service /etc/systemd/system/
sudo cp -a /root/pocketkit-sub-merge-20261023T181838Z/etc/systemd/system/tokyo-sub-merge.timer /etc/systemd/system/
sudo cp -a /root/pocketkit-sub-merge-20261023T181838Z/etc/nginx/sites-available/oracle-ai /etc/nginx/sites-available/oracle-ai
sudo cp -a /root/pocketkit-sub-merge-20261023T181838Z/opt/subscription-merge/. /opt/subscription-merge/
sudo systemctl daemon-reload
sudo nginx -t && sudo systemctl reload nginx
```

恢复备份的 Nginx 文件后保持 timer 禁用即可；旧 2088 URL 已由兼容备份恢复为独立原东京 YAML。

### 01 回滚

本次核实时未继续修改 01；如以后需要恢复固定入口，先校验备份清单，再从 `/root/pocketkit-xray-fixed-20261023T181926Z` 恢复对应的 Nginx/Xray/systemd 文件，执行 `sudo nginx -t`、`sudo systemctl daemon-reload`，最后只重载 Nginx并重启 Xray。

## 实测证据

- 东京 SSH ED25519 指纹与档案值一致。
- 东京 Nginx、`arm-subscription`、`sing-box`、DSH、OpenList、Cockpit 均为 active；聚合 timer 为 active/enabled。
- 合并器日志曾记录上游拒连并明确 `kept last-known-good=True`；下一次 timer 运行恢复成功，证明失败不会覆盖好配置且定时刷新会继续。
- 旧 2088 订阅：HTTP 200、2 个节点；与 443 私密聚合响应体逐字节一致。手动运行一次 merge service 后再次验证仍为 2 个节点。
- 东京节点切换前后全字段规范化 SHA-256 一致，凭证及其余字段未变化。
- 01 固定 `/sub`：HTTPS 200，证书链和域名校验通过；证书 SAN 为 `*.instabox.tech`，当次证书有效期至 2027-05-06。
- 东京私密聚合订阅：HTTPS 200、`application/json`、2 个节点；IP 证书链和主机名校验通过，SAN 为 `168.110.52.174`，当次证书有效期至 2026-10-29。
- 真实代理验证从东京启动临时独立 sing-box 客户端完成：
  - `Tokyo Reality`：VLESS+REALITY 握手成功，经 SOCKS 访问 `api.ipify.org` 的出口为 `168.110.52.174`，另一个 HTTPS 目标返回 204。
  - `Instabox-US-01`：VLESS+WebSocket+TLS 握手成功，经固定 12443 域名访问 `api.ipify.org` 的出口为 `44.244.119.140`，另一个 HTTPS 目标返回 204。
- 验证用远端临时目录和进程由 trap 清理；未留下临时代理服务或 systemd 单元。

## 验证边界

- 未重启东京或 01 整机，也未重载 2088、Nginx、sing-box 或其他管理服务；自启结论来自 systemd `enabled` 与现有运行状态。
- `arm-subscription`、Nginx、sing-box、聚合 timer、DSH、OpenList、Cockpit 均为 active/enabled；另有与本次订阅改动无关的 `oracle-ai-dsh-session-sync.service` 处于 failed，本次未越界处理。
- 未强制执行东京短期 IP 证书续期；仅验证当前证书和既有续期服务未受影响。
- 未在 PocketKit app 内执行订阅同步，也未构建或发布 APK；私密同步目标交由主任务从结果文件读取。
- 未实施人为断网故障注入；last-known-good 结论来自代码路径、原子替换实现和已有真实失败后自动恢复日志。
