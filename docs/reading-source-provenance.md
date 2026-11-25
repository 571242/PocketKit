# 拾用精选 · 来源记录与维护状态

> 机器 01 独立服务：`pocketkit-content-sync.{service,timer}`（低权限用户 `pk-content`）
> 下发地址：`https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/content/book-sources.json`
> 状态地址：`.../content/book-sources-status.json`；内置镜像：`.../content/builtin-sources.json`
> 代码：`tools/content-subscription/`（`curator.py` / `legado.py` / `boundedhttp.py` / `deploy.py`）

## 一、它做什么，不做什么

- **做**：每天（04:17 ± 30min）从跟踪的书源合集里**发现候选**，识别「普通网页/RSS 入口」不是书源，去重、过滤不支持或危险规则，再对候选做**真实有界**链路测试：小说 `search → toc → content`、漫画 `search → detail → toc → image bytes`、视频 `CMS list → playable`。
- **只下发规则**：`sources[].rule` 是标准化后的 Legado 声明式规则 + `provenance` 来源 + `detection` 检测元数据；视频是 `{"type":"cms","api":...}`。
- **不做**（硬边界）：服务端**不执行**任何上游 `@js` / `<js>` / `java.*` 脚本（只检测并在 `provenance.hasScript` 标注，客户端在受限解释器里执行）；不托管小说正文；不转发视频；不绕过付费/登录/验证码；不访问私网或云元数据地址。

## 二、候选来源（可扩展跟踪，非固定一个旧 id）

`curator.DEFAULT_COLLECTIONS` 里跟踪多个 yckceo 合集 id，每次运行**重新拉取**，因此作者新增的源会被发现，而不是永远钉死一份历史快照：

| 合集 id | 说明 |
| --- | --- |
| 1298 | 阿豪书源小说合集（15 条：起点/得奇/篱笆/速读谷/小原/错层…）|
| 1285 | yckceo 书源合集 1285（10 条）|
| 1325 | yckceo 书源合集 1325（约 1465 条大合集）|

> 1285/1325 仅是**候选**，不是精品认证；是否收录完全取决于当次真实链路测试。

## 三、筛选规则（本机实测）

1. **脚本即拒（服务端）**：`bookSourceComment` 里写「无脚本」不代表子规则无脚本——`<js>` 内联脚本、`@js:`、`java.*`、`org.jsoup` 在服务端一律判为 `unsupported`、**不执行**。客户端引擎则用受限 Rhino 解释器执行（见 `docs/reading-contract.md`）。
2. **网页/RSS 入口 → web，不当小说源**：`sourceUrl`/`singleUrl` 形态识别为 web，不下载、不假装成书源。
3. **政策排除**：含 `po18`、按版主、海棠等成人/付费盗版特征直接 `excluded`。
4. **诚实检测深度**：每源记录 `depth`：
   - `search` = 搜索有结果；`toc` = 目录有章节；`content` = 正文可取且通过质量闸门；`image` = 漫画章节解析出真实图片字节；`playable` = CMS 有可播放地址。
   - **只有完整 depth 才进入下发包**；半程只作为失败样本记录。
   - `tocVia`/`contentVia` 标注 `declared`（声明规则命中）或 `heuristic`（声明规则失效、由有界启发式抽取）。
5. **质量闸门**：正文必须 ≥200 字、CJK 密度达标、且**不含**反爬提示（"本章没完请点击下一页""退出转码/转马"）、付费/APP 壳（"上QQ阅读APP看后续""登录订阅本章"）等垃圾文本。

## 四、稳定性策略

| 场景 | 行为 |
| --- | --- |
| 单源某次测试失败 | **保留上一次已验证的条目**（`keptLastGood`），只累加 `consecutiveFailures`、写 `lastError`；已保存的 `depth` 不被失败结果覆盖（否则连续两次失败就会丢源） |
| 本轮未覆盖到的旧源 | **原样带过**（`carriedOver`），不会因为候选集漂移或测试预算上限而缩短下发包 |
| 全部失败 | **不发布空包**，保留线上 last-good，`published=false` 并写明原因 |
| 单次有网络超时/瞬时错误 | 失败源保留旧条目，不影响其它已验证源发布 |
| 视频 CMS 源 | 4 个固定 CMS 端点写在 `curator.CMS_SEED`，不依赖 yckceo 合集，每晚随包保留 |

> 这两条（carry-over 与 CMS seed）是 0.14.0 修掉的真实缺陷：早期版本只发布本轮测过的源，一次夜间运行就会把 13 源包缩成 1 源。

## 五、内置快照与线上包（当前状态）

`app/src/main/assets/reading/builtin-sources.json` 与线上 `/content/book-sources.json` **同结构**，当前都是 **13 个源**：

| 类型 | 数量 | 源 |
| --- | --- | --- |
| 小说 | 7 | 番茄小说、搬山人小说网、无忧书城、笔趣阁、精华书阁、全本小说网、得奇小说网 |
| 漫画 | 2 | G社漫画、漫画团 |
| 视频 | 4 | 非凡资源、极速资源、光速资源、红牛资源 |

每个源都在**客户端同一 Java 引擎**里跑过完整链路（搜索→目录→正文/图片/播放地址），不是纸面声明。线上包已实测 `HTTP 200`；一夜增量抓取后为 14 源（13 个随包基线全部保留 + 1 个新验证的错层小说），`carriedOver=8` 证明本轮没测到的源不会被夜间任务抹掉。

### 5.1 被剔除的源与实测原因

| 源 | 原因 |
| --- | --- |
| 筆趣閣（biquge.tw） | 两个域名从本机 TCP 直接超时，IPv6 无路由；站点侧封锁，不是规则问题 |
| 拷贝漫画（mangacopy.com） | 4 个 Cloudflare IP 全部在 TLS 握手阶段被 reset；IPv6 无路由 |
| 最大资源（zuidazy.me） | 无路由（Network unreachable） |
| 无尽资源（api.wujinapi.me） | Cloudflare JS challenge，HTTP 403 |

> 这 4 个不是「规则写错」，是当前出口环境被目标站点拦截。换网络环境可能可用；它们留在候选池里，服务端若能验证通过会自动回归，不需要改代码。

## 六、本次排查修掉的引擎缺陷（都影响全部源，不只某个源）

1. **JSON 节点在 JS 里读不到字段**：JSONPath 返回的是 `java.util.Map`，Rhino 把它包成 NativeJavaObject，`result.id` 恒为 `undefined`。改为递归转成真正的 JS 对象。G社漫画的 `m=76&c=undefined`、番茄的章节 URL 全空都是这一个根因。
2. **脚本指令计数跨脚本累计**：指令观察器用**类**做 thread-local key，Rhino 的 Context 按线程复用，计数永不归零——跑几个源之后任何脚本都会莫名"超限"。改成用 factory 实例做 key。
3. **预算太紧**：纯 JS 的 AES-128-CBC 解密要走上亿条字节码，1M 指令 / 3 秒必然失败。改为 400M 指令 + 8 秒墙钟，真正的防线是墙钟。
4. **视频只取网页线路**：Apple CMS 每个标题有两条线路，第一条是 HTML `/share/` 页，第二条才是 `.m3u8`。ExoPlayer 打不开网页，改为 m3u8 线路优先。
5. **URL 选项后缀**：`https://x.com/a,{"webView":true}` 被当成 URL 解析失败，新增 `stripUrlOptions`。
6. **多 URL 拼接**：搜索结果多个链接用空格拼在一起时整条失效，改为截断到首个空白。
7. **番茄小说的搜索结果是混合的**：音频专辑（`item_schema_url` 含 `detail/audio`）没有阅读页，`/api/book/info` 对搜索来的 id 一律"查无此书"。改为音频行直接丢弃、`bookUrl` 直接用目录 API。
8. **目录分页到上限**：达到 5000 章上限时原来抛错丢掉整本，改为保留已读章节并停止翻页。

## 七、抓取安全（`boundedhttp.py`）

- 仅 `http/https`，URL 内禁带凭据；**每次解析域名并拒绝**私网/回环/链路本地/保留/组播段及 `169.254.169.254`。
- 每次读取硬上限 4 MiB、超时 20s；重定向 ≤4 次且**逐跳复检目标主机**。
- 非 ASCII 的路径/查询统一百分号编码，避免 http.client 抛错。

## 八、状态字段（`book-sources-status.json`）

`lastAttemptAt` / `lastSuccessAt` / `candidatesSeen` / `acceptedNovel` / `acceptedManga` / `acceptedVideo` / `tested` / `verifiedNow` / `keptLastGood` / `carriedOver` / `publishedSources` / `published` / `reason` / `rejected[]`（隔离原因）/ `results[]`（每源 depth、searchHits、tocChapters、contentChars、error）。

## 九、部署与权限（见 `deploy.py`）

- 新建系统用户 `pk-content`（无登录 shell），代码只读 `/opt/pocketkit-content`，数据可写 `/data/pocketkit-content`，**不能读** `/data/pocketkit-hub/server.py` 或 `admin.token`。
- hub 进程以 `user` 身份运行，只读暴露 `/content/{book-sources,book-sources-status,builtin-sources}.json`（固定文件名白名单，禁子路径/符号链接）。
- **发布包必须是 `0644` 且目录可进入（755）**：`pk-content` 写的 JSON 若是 `0600`，hub 读不到就 `502`。这条踩了两次坑——先是 `curator.atomic_write`，后是 `finalize_sources.atomic_json` 用 `mkstemp` 重写同一文件时默认 `0600` 把它盖回去。两处现在都显式 `chmod 0644`，`deploy.py verify` 会把非 200、空包、缺少任一类型或少于 13 源判为失败。
- 定时器 `Persistent=true`，开机自启。

## 十、验证状态（本地已验证 / 生产已部署）

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| worker 声明式解析/选择器/正则/回退 | 本地通过 | `selftest.py` 31 项全过（含 carry-over / CMS seed / 连续两次失败仍保留） |
| 私网/云元数据地址拦截 | 本地通过 | `selftest.py` + `boundedhttp._validate` |
| last-good 全失败保留旧包 | 本地通过 | `selftest.py` `test_lastgood_keeps_package` |
| `/content/` 只读白名单路由 | 本地通过 | `venue_selftest.py` 4/4 |
| 引擎单元测试 | 本地通过 | `ReadingEngineTest` + `ReadingImportsTest` 34/34 |
| 13 源客户端实链 | 本机实测 13/13 | 小说 7 源均取到正文；漫画 2 源各 36 张图；视频 4 源均解析出 `.m3u8` |
| **01 生产部署** | **已完成** | timer enabled，下次触发 `Sat 2026-10-31 04:40:30 UTC`；service `Result=success`；`/content/book-sources.json` HTTP 200 / 37,892 bytes / 14 源（8 novel / 2 manga / 4 video）；`/content/book-sources-status.json` HTTP 200；admin 仍 401 |
| 更新推送 | 已完成 | `api/check-update` 返回 0.14.0 / code 37；`releases/PocketKit-0.14.0.apk` HTTP 200、286,498,053 bytes；`latest.apk` 已指向新版 |

## 十一、维护入口

```bash
python tools/content-subscription/deploy.py preflight   # 只读探测 + server.py 差异
python tools/content-subscription/deploy.py deploy      # 备份→建用户/目录→装码→装单元→首个 run
python tools/content-subscription/deploy.py verify      # timer、service、HTTP 200 非空、admin 401
```

`deploy.py` 通过 InstaBox REST 执行（旧 SSH key `ibssh_lcg7...` 已过期，不要再使用）；只动机器 01，不碰代理/东京/其他 VPS。
