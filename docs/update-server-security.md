# PocketKit 更新服务（01）鉴权加固

> 修复时间：2026-10-24
> 主机：InstaBox 机器 01（`instabox-01`，公网出口 `44.244.119.140`）
> 范围：**仅** `/data/pocketkit-hub/` 下的 hub 服务与其 systemd 单元。未改动东京主机、01 的 nginx / xray / cloudflared / gost，未改动 `app/` 与 `build.gradle`。

## 一、漏洞：管理接口完全匿名

原 `server.py` 对写接口没有任何身份校验：

| 端点 | 修复前 | 影响 |
| --- | --- | --- |
| `POST /api/admin/save-all` | 匿名可写 | 任意人能改版本号、更新日志、下载直链、代理规则 —— 可把全体用户的更新指向自己控制的 APK |
| `POST /api/upload-release` | 匿名可写 | 任意人能往 `releases/` 写任意文件（`X-Filename` 未校验，可 `../` 穿越），可覆盖已有 APK |
| `GET /` 管理页 | 匿名可读 | 暴露运营配置全貌 |

公网固定入口 `https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech` 直接映射到该 9876 端口，无需任何凭据即可利用。

## 二、修复内容

| 位置 | 变更 |
| --- | --- |
| `_require_auth()` | 所有 `POST`（含上传、保存、以及将来新增的写接口）先过鉴权；`/` 管理页同样需要 |
| 凭据来源 | 每次请求读 `/data/pocketkit-hub/admin.token`（`0600`，内容 `user:password`），轮换口令**无需重启** |
| 比较方式 | `hmac.compare_digest` 常量时间比较用户名与口令；Base64 解析失败、缺冒号、无头一律拒绝 |
| 失败关闭 | 口令文件缺失/格式损坏时**全部拒绝**，不会退化成匿名放行 |
| 来源判断 | 完全不看客户端 IP / `TCP peer`。平台入口可能从本地转发，localhost 来源同样挑战凭据 |
| 响应 | `401` + `WWW-Authenticate: Basic realm="PocketKit Hub Admin", charset="UTF-8"`；浏览器弹出原生登录框，登录后表单 POST 自动携带同一凭据，管理操作无需改工具链 |
| 文件名校验 | 只接受 `^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$`，显式拒绝 `..`；路径穿越与非常规扩展名返回 `400` |
| 上传大小 | 上限 512 MiB（容纳实核修复版），空请求体返回 `413`；超限/异常不落盘 |
| 原子替换 | 上传与 metadata 都先写同目录临时文件 → `fsync` → `os.replace`。中断只丢临时文件，已发布 APK 与旧 metadata 不受影响 |
| metadata 保留 | 表单里**未出现**的字段保持原值（原实现会把缺失字段清空）；`versionCode` 仅在表单提交时更新 |
| 保存边界 | 两文件先分别落临时文件，再依次替换；暂存失败保留旧文件。两个 `os.replace` 不是跨文件事务，不承诺替换阶段断电时两份同时提交 |
| 浏览器跨站写入 | 已认证请求仍检查 `Origin`；非本服务来源返回 `403` |

保留不变的公共只读能力：`GET /api/check-update`、`GET /api/proxy-config`、`GET /releases/*` 依然匿名 `200`，App 更新检查与 APK 下载链路完全未受影响。

### 服务与自启

`/etc/systemd/system/pocketkit-hub.service` 原本已是 `Restart=always` / `RestartSec=3` 且 `enabled`，本轮**未改动**该单元，仅确认保持：

- `systemctl is-enabled pocketkit-hub` → `enabled`
- 实测 `kill -9` 主进程后 8 秒内自动以新 PID 恢复，`check-update` 回到 `200`，匿名上传仍 `401`

## 三、凭据

- 位置：**仅服务器** `/data/pocketkit-hub/admin.token`，权限 `0600 user:user`
- 内容：`admin:<32 字节 base64 随机串>`（`openssl rand -base64 32`）
- 轮换：`bash tools/update-server/manage.sh rotate-token`，旧口令立即失效，无需重启
- **该口令不在 APK、源码、聊天记录或本文档中**；仓库里只有读取路径，没有任何明文

## 四、备份与回滚

修复前完整备份（服务器上）：

```
/root/backup-update-security/20261023-180925/
  server.py                原 9657 字节源码（sha256 f8aca60c…7611）
  config.json              原 metadata（sha256 9f253e2b…157d）
  pocketkit-hub.service    原单元
  releases-listing.txt     发布目录快照
  sha256-before.txt        上述哈希
```

紧急回退服务代码时，使用后续发布前的**已鉴权**备份，保留口令文件：

```bash
sudo cp -a /root/pocketkit-release-093-backup/server.py /data/pocketkit-hub/server.py
sudo systemctl restart pocketkit-hub
```

早期备份中的实现允许匿名写入，只作取证，不应重新暴露公网。不要删除 `admin.token`。回退代码不会回退当前 metadata；0.9.3 已发布，旧 APK 仍保留，若需调整下载目标应经鉴权管理接口操作，不要为了回退 APK 撤销鉴权。

## 五、验证证据

`bash tools/update-server/verify.sh`，**45 项全部 PASS**：

| 检查 | 结果 |
| --- | --- |
| 匿名 `GET /api/check-update`、`/api/proxy-config` | `200`（只读继续开放） |
| 匿名下载 `PocketKit-0.9.2.apk` | `200`，`Content-Length=313990408` 与磁盘一致 |
| 匿名 `POST /api/admin/save-all` | `401`，`config.json` 哈希未变 |
| 匿名 `POST /api/upload-release` | `401`，磁盘未产生文件 |
| 错误口令 / 畸形 Basic（无冒号）/ 非 Base64 / `Bearer` | 全部 `401` |
| 匿名 `GET /` 管理页 | `401` 且带 `WWW-Authenticate: Basic` |
| 服务器本机 `127.0.0.1` 匿名 upload / save-all / 管理页 | 全部 `401`（不因回环放行） |
| 经公网入口带凭据 `GET /` | `200`（入口不剥离 `Authorization`） |
| 授权原值回写 metadata | `303`，`config.json` **逐字节未变** |
| 授权只提交 `title` | 其余字段（`versionName`/`versionCode`/`configVersion`）保持原值 |
| 授权上传探针 → 下载 → 内容比对 | `200` / 逐字节一致 / 已清理 |
| 文件名 `../evil.apk`、`../../etc/cron.d/evil.apk`、`sub/dir.apk`、`evil.txt`、`.hidden.apk`、`a b.apk`、空名 | 全部 `400` |
| 空上传 | `413`；非表单编码 → `415` |
| 收尾：`config.json` 哈希、APK 哈希、`releases/` 清单 | 与基线完全一致 |
| 其他服务 | `xray`/`nginx`/`gost`/`vod`/`keepalive`/`tunnel-sub` 启动时间戳未变（未重启） |

本地另有 10 项单元自检（`python3 tools/update-server/selftest.py`）与发布脚本自检，覆盖鉴权分支、文件名校验、原子覆盖、口令轮换即时生效。

## 六、日常用法（不含口令）

```bash
# 服务状态 / 发布目录
bash tools/update-server/manage.sh status

# 验证（自动从服务器读凭据，不打印）
bash tools/update-server/verify.sh

# 轮换管理口令
bash tools/update-server/manage.sh rotate-token

# 发布新 APK（口令不经过客户端）
bash tools/update-server/publish.sh dist/PocketKit-0.9.3.apk --point-download \
    --set versionName=0.9.3 --set versionCode=21 --set title='PocketKit 0.9.3'
```

`publish.sh` 的行为：把 APK `scp` 到服务器 `/tmp` → 服务器本地 `publish-remote.py` 读取 `admin.token` 并带凭据调用 `127.0.0.1:9876` → 校验落盘字节数 → 按需只更新给定 metadata 字段 → 清理临时文件。因此口令不会出现在客户端命令行、`ps`、shell 历史或本仓库任何文件中。

浏览器管理：直接打开 `https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/`，输入 `admin` 与服务器上的口令即可，表单提交沿用同一 Basic 凭据。

## 七、本次刻意未做

- 鉴权子任务阶段未发布 APK。主会话随后已发布 0.9.3/code21，配置版本 `2026.10.24.6`，下载文件全量远端 SHA-256 与本地一致。`proxyConfigUrl` 已设为东京私密 HTTPS 聚合订阅；兼容的 `/api/proxy-config` 也已保存双节点配置。完整私密 URL 不写入本文。
- 未给 `releases/` 增加签名或校验和强制校验（超出"强制鉴权"的最小范围）。
- 未引入反向代理层或 WAF，认证直接在 hub 进程内完成，避免依赖平台入口行为。
- 未改动 01 上其他任何服务、东京主机、以及 App 源码与 `build.gradle`。

## 八、已知边界

- Basic 认证在 HTTPS 下传输，凭据本身不加密存储；平台入口若被替换为非 TLS，凭据将明文暴露。
- 单一口令共享给所有管理员，无审计日志与按人吊销；需要时再加。
- 上传只限制大小与文件名，未限制内容类型/签名；误传的合法名文件仍会发布，需人工核对 `sha256` 与版本号。
