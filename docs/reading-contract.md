# 阅读中心集成契约

本轮目标：底部「阅读」进入小说/漫画/视频三合一中心；内置一个「拾用精选」订阅（13 个实测源），01 自动维护候选，手机只手动获取和确认导入。个人编辑不被静默覆盖。保留原本地 ReaderActivity 安全边界。

## 并行写入边界

- Lead：ReadingModels.java、ReadingStore.java、ReadingImports.java、MainActivity、AndroidManifest、Gradle、许可与发布、总体测试。
- 引擎：reading/ReadingEngine.java、reading/SourceHttp.java、reading/RuleEvaluator.java 及该子任务自己的辅助文件（不得占用 ReadingModels/Store/Imports）、tools/reading-selftest/ 引擎测试。
- UI：reading/ui/ 下全部 Activity 与辅助类；不改 Manifest/Gradle/MainActivity/存储与引擎。
- 服务：tools/content-subscription/、app/src/main/assets/reading/builtin-sources.json、docs/reading-source-provenance.md；仅可对 tools/update-server/server.py 新增只读 /content/ 路由。可部署仅该独立服务，禁止修改代理/东京/其他 VPS。

## Java API（cn.xianhua.pocketkit.reading）

所有网络操作是同步阻塞方法，必须由 UI 的后台线程调用；Activity 销毁应取消/忽略旧任务。错误抛出 Exception，不用空列表掩盖网络或规则失败。

ReadingModels 为 public final class，内部所有类型 public static final，有 public 可读写字段、无参构造、toJson()、static fromJson(JSONObject)。

- Source: String id,name,kind,url,subscriptionId,upstreamHash; JSONObject rule; boolean enabled=true,localModified=false。kind=novel/manga/video/web。toJson 是标准化封装，rule 保存原规则。id 为稳定标识；导入匹配不得只靠显示名称。
- Item: String sourceId,kind,title,author,url,cover,description。url 是书籍详情 URL；视频 CMS 为 vod_id 或详情 URL，交由引擎解释。
- Chapter: String name,url,group（视频线路名，可空）。
- 漫画：`kind=manga`，`chapterImages(Source,Chapter)` 返回有序图片 URL 列表；正文规则按图片解析（`<img>` / JSON 数组 / 换行列表）。
- Detail: Item item; List<Chapter> chapters=new ArrayList<>()。
- Playback: String url,mimeType; Map<String,String> headers=new LinkedHashMap<>()。
- Subscription: String id,name,url,lastMessage,updatedAt; boolean builtin。
- ImportCandidate: Source source; String state,message。state=new/update/unchanged/conflict/unsupported。冲突默认不选，不支持禁止选。
- Progress: String id,sourceId,kind; Detail detail; int chapterIndex,scrollY; long positionMs,updatedAt; boolean favorite。

### ReadingEngine（引擎子任务实现）

- public ReadingEngine()
- public List<Item> search(Source source,String query) throws Exception
- public Detail detail(Source source,Item item) throws Exception
- public String chapter(Source source,Chapter chapter) throws Exception — 返回纯文本正文，不能把未知 HTML/JS 交给 UI 执行
- public List<String> chapterImages(Source source,Chapter chapter) throws Exception — 漫画章节图片 URL 列表（manga 源）
- public Playback playback(Source source,Chapter chapter) throws Exception
- public static String supportIssue(Source source) — 空字符串=可尝试；明确不支持则返回人类可读原因（不是成功率承诺）
- public static String fetchSubscription(String url) throws Exception — 有界下载、禁止危险地址/重定向

支持 Legado 小说与漫画的明确子集（`bookSourceType` 0/2），核心选择器/分页/URL/正则行为参考已核验 GPL 快照，脚本只在受限解释器运行；不得开放 JVM 反射、文件、进程、私网请求或自动验证码绕过。未知调用必须报清楚，不静默漏章。视频首版 CMS JSON api，Source.rule={"type":"cms","api":"https://.../api.php/provide/vod/"}；另可支持 {"type":"direct",...} 合法直链目录。web 为浏览器入口，不当成书源。

脚本边界（`RuleEvaluator`）：Rhino 解释执行，类白名单只放 `java.lang` 基础类型、`Math`、`java.util.Base64/Arrays/ArrayList/HashMap/LinkedHashMap`、`javax.crypto.Cipher/SecretKeySpec/IvParameterSpec`；`PackageClassShutter` 让 `File/ProcessBuilder/Runtime/java.net.URL/System.getenv/Class.forName` 不可见，另有 13 条正则拒绝 eval/Function/反射/无限循环等构造。指令数上限是兜底，真正的防线是 8 秒墙钟 deadline。

`ruleBookInfo.tocUrl` 里 `java.put('mid',...)` 写入的变量在同一源的后续 `ruleToc` 求值中仍可读到（`PUT_VARS` 按 sourceKey 隔离，跨 evaluator 实例共享）。

网络边界（`SourceHttp`）：应用自己的 `PublicDns` 在解析时校验每个地址并把这批地址交给 OkHttp（无 TOCTOU 窗口）；重定向由 `SafeRedirect` 逐跳处理，拒绝非 HTTP(S)、拒绝 HTTPS→HTTP 降级，跨源时丢弃敏感头。连接 8s / 读 20s / 整次调用 35s，响应体 4 MiB（上限 8 MiB）。请求先走 HTTP/2，若在读阶段被中间设备挂住则在 18s 内自动以 HTTP/1.1 重试一次——这是实测到的真实差异：有的站点 h2 秒开、h1 超时，有的正好相反。

### ReadingStore（Lead 实现，Context 构造）

- public ReadingStore(Context)
- public List<Source> sources(); public Source source(String id)
- public void saveSource(Source source,boolean markModified); public void deleteSource(String id)
- public List<Subscription> subscriptions(); public void saveSubscription(Subscription); public void deleteSubscription(String id)
- public List<ImportCandidate> preview(String json,String subscriptionId) throws Exception
- public int importSelected(List<ImportCandidate> selected,boolean replaceModified) throws Exception
- public List<Progress> history(String kind); public void saveProgress(Progress); public void deleteProgress(String id)
- public String cachedChapter(String sourceId,String chapterUrl); public void cacheChapter(String sourceId,String chapterUrl,String text) throws Exception

默认订阅 id=builtin-curated、name=拾用精选、url=https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/content/book-sources.json。初次进入只建立订阅和可预览的 bundled 快照，不把未确认的网络规则静默装入。断网首次获取可明确使用内置快照；已有源离线保留。

## UI（入口类）

- reading.ui.ReadingHubActivity：小说/漫画/视频切换、搜索、继续阅读/观看、收藏、源管理入口。只搜启用且匹配类型的源；并发有上限，结果渐进更新/可取消，单源失败不拖垮其他源。
- reading.ui.ReadingSourcesActivity：订阅与源管理；内置获取、URL/JSON 导入，差异预览，编辑 JSON、启停/删除；web 类入口明确只打开网页。
- reading.ui.OnlineReaderActivity：Intent extra progress_json 是 Progress.toJson().toString()。使用现有 ReaderPage + reader.html 字体/深色样式，WebView 禁 JS/网络/文件；章节由引擎取纯文本再转义。目录、上下章、字体、夜间、滚动进度、缓存、失败保留已读正文。
- reading.ui.MangaReaderActivity：同一 progress_json extra；按 `chapterImages` 顺序加载图片，支持翻页/缩放/上下章与进度保存。
- reading.ui.ReadingPlayerActivity：同一 progress_json extra；Media3 PlayerView、选集/线路、续播/倍速/横屏/下一集，生命周期释放，不添加后台常驻服务。

Activity extra 不接收未知自动执行代码；除 UI 内操作均 exported=false。主题跟随现有 ToolUi 约定。

## 服务器精选包协议

GET /content/book-sources.json，UTF-8 JSON：

{"schemaVersion":1,"name":"拾用精选","updatedAt":"ISO8601","sources":[{"id":"stable-id","name":"...","kind":"novel|manga|video","url":"https://...","rule":{"bookSourceUrl":"...","ruleSearch":{},"ruleBookInfo":{},"ruleToc":{},"ruleContent":{}}}],"note":"检测边界说明"}

可直接接受 Legado 对象或数组作为手动导入；旧 sourceUrl/singleUrl 的 RSS 网页入口识别为 web，不能假装小说源。服务不执行任意上游 JS；先收录能完成有界搜索→目录→正文校验的明确规则/授权公共内容，其他候选隔离并记录原因。视频仅验证明确许可/开放媒体候选。禁止因为所有候选失败就用空包替换旧可用包。抓取 worker 不可读取 Hub admin.token。

## 依赖与许可

Lead 添加 org.jsoup:jsoup:1.18.3、org.mozilla:rhino:1.7.15、com.jayway.jsonpath:json-path:2.9.0、Media3（版本由缓存与 SDK 兼容性核验后固定）。引擎可用这些依赖，Android 原生 org.json/XPath 标准库。尽量不引入 Kotlin/KMP/Room 全家桶。

GPL 参考固定快照：huajideshutiao/legado commit d0c42f32426d01741dba0122982554f2934209e7。任何直接移植/翻译的代码须有文件头归属、路径、commit、修改说明；保留 GPL 文本并在总体分发声明中说明。不得以“改成 Java”为由去掉许可。现有 Apache 来源声明保留。
