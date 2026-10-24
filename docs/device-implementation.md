# 设备与网络工具实现说明

> 归属：设备子代理。拥有 `DeviceToolsActivity`、`device` 子包、本文件、设备测试。
> 范围：基础 067 068 069 070 072 073 074 075 076 077 078 079 081 083 084 086 087 088 089
> 093 094 095 096 097 098 099 104 105 106 110 111 112 113。
> 界面语言中文，跟随系统深浅色。使用系统公开 API 与已核实的公开接口，不复制任何商业 APK 代码或素材。

**验证状态声明**：当前**无真机、无可用硬件加速模拟器**。本文件所有"通过"均指本机
`javac` 编译检查、纯 Java 算法/协议自检，或对真实公开接口的实际 HTTP 请求，
**均不代表真机运行验证**。真机行为（传感器读数、振动、麦克风、SAF 授权、
联网接口在移动网络下的表现）尚未实测。

## 0. 生命周期：持续采集只在页面可见时进行

**按 Home 时 Activity 不会销毁**，因此只在 `onDestroy` 注销监听，等于让传感器和麦克风
在用户看不见的时候继续工作（费电，而且等于暗中采集）。本模块的处理：

- `DeviceToolsActivity.onStop()` 执行所有面板登记的停止动作：注销传感器、停止录音、停止 FTP 共享；
- `DeviceToolsActivity.onStart()` 只在"曾经离开过"时提示用户「采集已停止，如需继续请重新按开始」，
  **不自动恢复采集**；
- 067/068/069 与 070 都需要用户先按「开始测量」才会占用硬件，界面均明示这一点；
- 旋转时 Activity 重建（未声明 `configChanges`），采集停止，回来同样需要重新开始。

`SensorSession` 集中封装"注册/注销/是否活动"，避免每个工具各写一份而漏掉某一个。


---

## 1. 已交付批次

### 第 1 批：传感器与测量 + 设备信息（本地，无需联网）

| 编号 | 名称 | 实现方式 | supports |
|---|---|---|---|
| 067 | 指南针 | `TYPE_ROTATION_VECTOR`，退回磁力计+加速度计合成；圆周平滑用最短角差 | ✅ |
| 068 | 水平仪 | 重力传感器求双轴倾角，气泡显示，支持零位校准与三档量程 | ✅ |
| 069 | 金属探测仪 | 磁力计相对本底变化 | ✅（能力已限定） |
| 070 | 噪音测量 | `AudioRecord` 算 RMS → dBFS | ✅（默认不给 dB SPL） |
| 072 | 尺子（含量角器） | 屏幕刻度 + 卡片校准 px/mm；量角器三点式 | ✅ |
| 073 | 震动器 | `VibrationEffect`，支持时长与振幅 | ✅ |
| 077 | 设备详细信息 | `Build` / `StatFs` / `DisplayMetrics` / 特性查询 | ✅ |

### 第 2 批：文件与设备 + FTP

| 编号 | 名称 | 实现方式 | supports |
|---|---|---|---|
| 074 | 安装包清理 | SAF 扫描 `.apk`，默认仅预览 | ✅ |
| 075 | 应用管理 | 列表/详情/卸载用户应用/导出 APK+图标 | ✅（范围已限定） |
| 076 | 大文件清理 | SAF 扫描 ≥50 MB，默认仅预览 | ✅ |
| 078 | 空文件夹清理 | SAF 扫描子项数为 0 的目录，默认仅预览 | ✅ |
| 079 | 空文件清理 | SAF 扫描 0 字节文件，默认仅预览 | ✅ |
| 081 | FTP 局域网文件共享 | 自实现 FTP 服务端（RFC 959 子集），进程内运行，离开页面即停止 | ✅（能力已限定） |

### 第 3 批：联网查询 + 本地规则工具

| 编号 | 名称 | 实现 | supports |
|---|---|---|---|
| 083 | 域名信息查询 | RDAP `rdap.org/domain/{域名}` | ✅ 已实测 |
| 084 | 全球 IP 查询 | `ipwho.is/{ip}`（留空查本机） | ✅ 已实测 |
| 086 | 网速测试 | `speed.cloudflare.com/__down`（多档位下载） | ✅ 已实测 |
| 087 | 通知自己 | 本地 `NotificationManager`，不联网 | ✅ 本机逻辑自检 |
| 088 | 短链接生成与还原 | TinyURL / v.gd 生成；HEAD 跟随重定向还原 | ✅ 已实测 |
| 089 | 网页源码获取 | 直接 GET 目标 URL | ✅ 已实测 |
| 095 | 日报（读新闻） | 人民网/新华网/中新网**官方 HTTPS RSS**，只列标题+来源+时间 | ✅ 已实测（**替代实现**，见下） |
| 096 | 对联生成 | **本地规则引擎**：对偶词表最长匹配生成候选 + 平仄校验择优，非 AI | ✅ 生成路径已自检 |
| 097 | 快递查询 | 快递100 `autonumber/autoComNum` + `query` | ✅ 已实测 |
| 111 | QQ 会话 | 正规 intent（`mqqwpa://`）打开 QQ 聊天，非越权绕过 | ✅ 号码校验已自检 |
| 113 | 以图搜番 | trace.moe（POST 图片，**上传前逐次 AlertDialog 确认**） | ✅ 接口已实测 |

**supports=false（12 个）**，`limitation()` 逐个给出具体原因与候选来源，界面显示原因、不提供假按钮：

| 编号 | 不实现的原因 |
|---|---|
| 093 | 号段数据无可用匿名源；聚合接口需密钥；m.ip138 是脚本页（脆弱来源，不做逆向） |
| 094 | 拒绝抓自商业《新华字典》的数据；维基词典接口实测不可达。候选：CC BY-SA 的 CC-CEDICT，采用时须署名+同源共享 |
| 098 | 无许可清晰的开放分类数据集；旧公开接口全部下线 |
| 099 | 同 094；不接受抓自商业字典的成语数据 |
| 104 | 开源同义词库多为抓取来源、许可不清 |
| 105 | youbianku 是网页非接口；候选：国家统计局区划数据（adyliu/china_area 可获取）+ 需先确认许可的邮编表 |
| 106 | **代码已实现**，但 api.wikimedia.org 在开发环境实测 3 次超时，未验证 → 不标可用 |
| 110 | 京东联盟需 appkey+密钥、需资质审核。不索取密钥、不伪造券数据 |
| 112 | 淘宝开放平台（阿里妈妈）需 appid+密钥、需资质。同上 |

#### 数据许可红线（094 / 099 / 104）

`pwxcoo/chinese-xinhua` **不采用**：仓库虽标 MIT，但 README 自述为「中华新华字典数据库」，
正文抓取自**仍在版权期内**的商业出版物《新华字典》。仓库许可只覆盖作者自己写的代码，
**覆盖不到抓来的词典正文**——不能因为看到 MIT 就把数据打包进应用。

`mdbg.net`（CC-CEDICT 的一个下载点）**不采用**：页面实测含
「THIS WEBSITE IS FOR HUMAN USAGE ONLY, AUTOMATED / SCRIPTED ACCESS IS NOT ALLOWED」。

`LexiconTool.assessSource()` 把这条判断固化成代码，并由 `Batch3LogicTest` 断言盯住，
防止以后有人因为"看到 MIT"就把来源不清的数据打包进来。

#### 096 对联：为什么是"生成"而不是只有校验

用户选的功能是【对联生成】，因此只做平仄校验**不算实现**。当前实现两条腿都有：

1. **生成**（`DuilianGenerator`）：把上联按内置**对偶词表**做**最长匹配**切分
   （「春风明月」→ `[春风, 明月]`，不是逐字），逐词换成对偶词，组合出候选，
   再按平仄规则排序——关键位置（偶数字位与末字）平仄相对越多越靠前，并优先仄起平收。
2. **校验**（`Pingze`）：字数、关键位置平仄相对、仄起平收、重复用字，具体到第几个字。

不是 canned 输出的证据：`DuilianGenTest` 断言**不同上联产出不同下联**
（春风→秋雨，青山→绿水，春风明月→秋雨清风），且候选随词表替换规则可解释。

词表性质：由本项目**自行整理**，只收"天/地""山/水""春风/秋雨""青山/绿水"这类
汉语中广为人知的对偶常识对，**不来自任何词典或商业语料库**，因此无第三方数据授权问题。

诚实边界（界面标签为「规则建议」而非「创作」）：
- **覆盖有限**：上联里只要有词不在词表内，就明确报出是哪个词并停止，**不硬凑**；
- 只保证字面对偶与平仄形式，**不保证语义通顺与意境**——那需要语言模型，本地规则做不到；
- 多音字按 ICU 常用读音判平仄，可能取错声调。

#### 095 是替代实现（如实标注）
原功能为「每日 60 秒读懂世界」，其公开上游只返回**图片 URL**（api.2xb.cn/zaob 实测只给 imageUrl），
60s.viki.moe 返回 429 限流。因此改为**直接读官方 RSS**：这是**替代实现**，不是原上游的复刻。
取舍是：宁可给可核实的标题+来源+时间，也不给一张随时失效的图片或编造的"每日一段话"。

#### 113 的上传纪律
图片**只由用户主动选择**；选择后弹确认框写明「第三方 trace.moe」「上传目的」「文件大小」「占用 1 次配额」；
**只有点「同意并上传」才 POST**，取消则什么都不发。不做任何后台/自动上传，不缓存图片，不落盘。

---

## 2. 需要父会话注册的内容

### 2.1 Activity（必须）

`cn.xianhua.pocketkit` 包根目录，与其它工具入口同级，Intent 约定一致（`tool_id` + `title`）：

```xml
<activity
    android:name=".DeviceToolsActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden" />
```

### 2.2 Service（**不需要**）

**本模块不注册任何 Service，也不申请前台服务权限。**

081 FTP 最初设计为前台服务，现已改为**在页面进程内直接运行**（`FtpShareTool` 直接
`new FtpServer(...)`），理由：本批次不承诺后台常驻，服务只在页面处于前台时存在，
行为更容易验证，也少一套通知权限。

因此请父会话**不要**为 081 添加 `FtpShareService`、`FOREGROUND_SERVICE`、
`FOREGROUND_SERVICE_DATA_SYNC`。`FtpShareService.java` 已从代码库删除。

### 2.3 权限

| 权限 | 用于 | 类型 |
|---|---|---|
| `RECORD_AUDIO` | 070 噪音测量 | 运行时申请，代码内已走 `ensurePermission` |
| `VIBRATE` | 073 震动器 | 普通权限，安装即授予 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 083/084/086/088/089/097/106 联网 | 普通权限 |

**明确不申请、也不需要的权限**：`MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、
`READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`、`READ_PHONE_STATE`。

关于 `REQUEST_DELETE_PACKAGES`：075 的卸载走 `ACTION_DELETE` 系统界面，由系统确认并执行，
**技术上不需要**该权限。但父会话决定按 targetSdk 36 的规则保留它——这点以父会话核验的
官方说明为准，我这边不依赖它是否声明。

### 2.4 queries

075 只用 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`，**不需要**在 `<queries>` 里声明。

111（QQ 会话）需要声明目标包，否则 `getPackageInfo` / 显式 intent 在 Android 11+ 包可见性限制下会失效：

```xml
<queries>
    <package android:name="com.tencent.mobileqq" />
</queries>
```

如果父会话已有 MAIN/LAUNCHER 的 `<queries>` 声明，把上面这个 `<package>` 合并进去即可。
不声明也不会崩溃（代码会如实报"本机没有检测到 QQ"），但可能误判为未安装。

### 2.5 第三方依赖

**设备子包零新增依赖**，全部使用 framework API（含 framework 自带的 `org.json`）+ `java.*`。
不需要改 `app/build.gradle`。

> 备注：本模块曾短暂写过自实现的 JSON 解析器，现已删除并改用 framework 的
> `org.json.JSONObject/JSONArray`（父会话 ToolRegistry 已在用，Android 原生提供，不是新依赖）。

---

## 3. 能力边界（已写进界面文案，不是藏在注释里）

| 编号 | 明示的限制 |
|---|---|
| 067 | 依赖磁力计，未经专业校准；强磁、金属外壳、车内会造成明显偏差；不能用于导航或安全相关判断 |
| 068 | 精度取决于加速度计，需先用水平面校准零位；不能替代建筑用水平尺 |
| 069 | **只测磁场相对本底变化，不是金属探测仪**：铁磁性金属与通电导线会有响应，不锈钢/铝/铜几乎没有，无法定位墙内钢筋 |
| 070 | **默认只给 dBFS 相对电平，不冒充声压级**。手机麦克风无绝对灵敏度标定，刻意不设"默认 94 dB"这类假偏移；用户必须自行用外部声级计测定偏移量，且结果始终标注为估算。不能作法定声级计 |
| 072 | 长度依赖手动校准（信用卡长边 85.60 mm 对齐屏幕），校准后误差约 ±0.5 mm；不能替代游标卡尺/钢尺 |
| 073 | 是否振动取决于设备是否有马达；频率与强度由系统实现，机型差异大 |
| 074/076/078/079 | 只扫描用户主动授权的目录；**默认仅列出预览**，必须再按一次删除并在系统确认框确认才执行；不申请全盘访问权限，授权目录之外看不到也删不到 |
| 075 | 只列出带桌面入口的应用（不申请 `QUERY_ALL_PACKAGES`）；卸载走系统界面，用户应用可卸、系统应用不可卸；**冻结、静默卸载、转系统应用需要系统级或 root 权限，第三方应用无法实现，本工具不提供这些入口** |
| 077 | 序列号、IMEI 等标注"系统限制，普通应用无法读取"，不假装拿到了值 |
| 081 | **明文 FTP**：凭证与文件内容在局域网内可被同网段抓包。只绑定 Wi-Fi 私有 IPv4（不监听 0.0.0.0、不暴露蜂窝/全局 IPv6）；仅 PASV 被动模式；不支持 FTPS 与断点续传；分享目录固定在应用外部私有目录 `PocketKit/share`；服务端拒绝删除/改名/新建目录，并拒绝任何跳出分享目录的路径；**离开本页面即自动停止**（不承诺后台常驻）。因该私有目录在 Android 11+ 用户无法直接用文件管理器访问，页面提供**导入文件**（复制进分享目录，同名自动改名不覆盖、不移动原文件）、**列出分享目录内容**、**导出/另存到指定位置**（不删除原文件）三个通道，否则服务端开着却没有文件可分享 |

---

## 4. 安全设计要点（081 FTP）

1. **路径穿越防护**：所有文件操作都经过 `FtpServer.Session.resolve()`，做 canonical 化后校验
   仍在分享根目录内；比较时带分隔符，避免 `/share-evil` 被误判在 `/share` 内。
2. **凭证**：每次启动生成随机会话用户名与密码，只显示在界面；不写日志文件、不进通知
   （锁屏预览会泄漏）。校验用常量时间比较。
3. **破坏性命令全部拒绝**：`DELE` `RMD` `MKD` `RNFR` `RNTO` `SITE` `CHMOD` 一律 502。
4. **只支持被动模式**，`PORT` / `EPRT` 主动模式返回 502（服务端回连客户端在手机上既不可靠也不安全）。
5. **绑定地址**：只绑 Wi-Fi 的私有 IPv4（`WifiAddress.wifiIpv4`），公网地址一律拒绝绑定。

已用真实 FTP 协议测试（自写客户端）与**第三方客户端 curl** 双向验证，共 28 项断言，含
4 类路径穿越攻击、错误密码、未登录访问、上传越权写入、删除拒绝。

---

## 5. 自检与验证

```bash
# 全部 7 组门禁（不调用 Gradle；父会话统一负责 Gradle，避免并发）
bash tools/device-selftest/run.sh

# 也可单独跑某一组：
bash tools/device-selftest/network-test.sh   # 联网真实请求（real org.json 必须隔离）
bash tools/device-selftest/contract-test.sh  # 目录合同（反射 RootActivity）
bash tools/device-selftest/batch3-test.sh    # RSS/平仄/词条检索/许可判定
```

| 测试 | 覆盖 | 断言数 |
|---|---|---|
| 编译检查 | device 子包 + DeviceToolsActivity + 公共基类 + 依赖的 core | exit 0 |
| `DeviceSelfTest` | 向量模长、低通滤波、PCM RMS、dBFS、方位角圆周平滑、atan2 倾角、气泡截断、尺子校准 | 56 |
| `DeviceLogicTest` | 文件清理规则（含"未知大小不当空文件"）、私有地址判定（公网一律拒绝）、QQ 号码校验（拒绝注入 `&` `?`） | 51 |
| `FtpServerTest` | 真实 FTP 协议：登录、列目录、下载、上传、4 类路径穿越、破坏性命令拒绝、导入同名去重不覆盖、**中断上传不损坏原文件** | 38 |
| `NetworkQueryTest` | 输入清洗（域名/IP/URL 拒绝注入）、真实接口请求与解析、失败路径有明确原因 | 35（另有 106 明确记为"未验证"） |
| `RegistryContractTest` | **反射 RootActivity** 的 `supports`/`limitation`，逐项核对 33 个编号与 `dispatch` 分支一致；未实现项必须给出具体原因；未知编号/null 不崩 | 150 |
| `Batch3LogicTest` | RSS 解析（含 Atom 自闭合链接、实体、CDATA）、平仄与对联规则、词条检索、**数据许可判定** | 58 |
| `DuilianGenTest` | 对联**生成**路径：最长匹配切分、候选随输入变化（非 canned）、未覆盖词明确失败、按平仄择优排序 | 19 |
| `check-quotes.sh` | 扫描中文里夹的裸 ASCII 双引号（会导致编译失败，我踩过、父会话的 media 文件也踩过） | — |

**合计 8 组门禁，415 项断言。** 运行 `bash tools/device-selftest/run.sh`，退出码 0。

### 5.1 数据安全：FTP 上传的原子提交

`STOR` 曾有**数据丢失**风险，两轮修复：

1. 第一轮：改为先写同目录临时文件，传完再替换。但替换仍是
   「先 `target.delete()` → `renameTo` → 失败退回 copy」——**rename 与 copy 都失败时旧文件已被删掉**。
2. 第二轮（当前）：改用 `Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)`。
   不支持原子移动就直接失败并保留旧目标，**不做退而复制的降级**（降级正是丢数据的来源）。
   `java.nio.file` 在 minSdk 26 即可用，无需新依赖。

两层测试，**且都验证过能抓到旧实现**：
- 「中断上传不损坏原文件」：TCP RST 打断传输 → 退回旧代码时测试报 `✗ 实际内容：<文件不存在>`；
- 「提交阶段失败保原文件」（`commitFailureTests`）：直接调 `commitUpload` 构造临时文件缺失/是目录两种失败
  → 退回旧的 delete-rename-copy 后测试报 `✗ 提交失败后目标文件仍然存在 → 文件不见了`。
  （RST 测试覆盖不到提交阶段，所以这组是必要的补充。）

### 5.2 测试本身也被验证过（避免"永远绿的测试"）

- FTP 两组数据安全测试都做过"退回旧实现 → 确认报错 → 还原"的验证。
- 门禁脚本的退出码：曾因 `cmd | sed || fail=1` 取到的是 **sed** 的退出码，
  测试进程崩溃时仍报"全部通过"。现已全部改用 `PIPESTATUS[0]`，并注入必失败断言确认脚本真会返回 1。
- 目录合同测试：会比较源码与 class 的 mtime，源码更新就现场编译，
  避免拿**旧的 Gradle 产物**得出过时结论。
- 所有测试 JVM 都设 `-Djava.io.tmpdir=<项目>/.scratch/device/tmp`，
  临时试件不落到系统 Temp（实测系统 Temp 下 `pocketkit-*` 数量为 0）。

---

## 6. 待办与后续计划

### 6.1 第 3 批联网查询（未交付）

已实测的接口可用性（2026-10-13 探测，全部 HTTPS）：

**可用（无需密钥）**
- 083 域名信息：`https://rdap.org/domain/{域名}`（302 → 注册局 RDAP，需跟随重定向）
- 084 IP 归属：`https://ipwho.is/{ip}`（留空查本机）；`https://ipapi.co/{ip}/json/` 实测 403（Cloudflare 拦截）
- 086 网速测试：`https://speed.cloudflare.com/__down?bytes=N`（实测 1 MB 正常）
- 087 通知自己：本地 `NotificationManager`，无需网络
- 088 短链：`https://tinyurl.com/api-create.php?url=`、`https://v.gd/create.php?format=json&url=`
- 089 网页源码：直接 GET 目标 URL
- 095 日报：`https://www.people.com.cn/rss/politics.xml`、`/world.xml`、`https://www.xinhuanet.com/politics/news_politics.xml`、`https://www.chinanews.com.cn/rss/scroll-news.xml`
- 097 快递：`https://www.kuaidi100.com/query?type=&postid=`、`/autonumber/autoComNum?text=`
- 113 以图搜番：`https://api.trace.moe/search`（匿名配额 100/天，**POST 图片字节**已实测返回真实结果）

**不可用 / 需换源**
- `api.vvhan.com` **域名已无 A 记录（该站整体下线）**，原计划依赖它的 093/094/095/098/099/104/105 已全部换源或标为不可用。
- `api.uomg.com` 同样解析失败；`60s.viki.moe` 返回 429（Cloudflare 1027 限流）；`api.2xb.cn/zaob` 只返回图片 URL。
- `api.oioweb.cn` TLS 握手失败；`api.seeapi.cn` 无法解析。
- **注意明文限制**：应用设了 `usesCleartextTraffic=false`，**http:// 的源会被系统拦截**，
  因此第一轮探测到的 `http://www.people.com.cn/rss/...` 等必须换成 https，否则真机上必然失败。
- 106：`https://api.wikimedia.org` 在开发环境 3 次实测超时（同一接口经另一条 fetch 通道可通，
  说明是网络环境问题而非接口不存在），因此**未验证、不标可用**。

**需要密钥或无公开接口 → 只能 supports=false 并说明依赖**
- 110 京东优惠券、112 淘宝优惠券：需开放平台 appkey/密钥与资质审核。
  **不向用户索取密钥、不借用商业 APK 内嵌凭据、不伪造优惠券数据、不把搜索页当券查询。**
- 094/099/104：数据许可问题是主要障碍（见第 1 节"数据许可红线"），非技术问题。
- 093/098/105：无许可清晰且稳定的公开数据源。

### 6.2 已知技术欠账

- 081 只共享应用外部私有目录；共享**任意用户目录**需要再写一层 `DocumentFile` 虚拟文件系统适配。
- 081 离开页面即停止；真正的后台常驻需要前台服务 + 各 ROM 后台白名单，真机验证前不承诺。
- 074/076/078/079 已实现 SAF 扫描与删除，但真实授权流程（`takePersistableUriPermission`
  跨重启、不同提供方行为）需真机验证。
- 075 导出 APK 依赖系统给出的 `sourceDir` 可读；在 Android 11+ 对其它应用可能读不到，
  需真机确认并补充降级说明。
- 113 的上传确认框、`ACTION_OPEN_DOCUMENT` 选图、QUERY 相关的真机行为均未实测。
- 095 的 RSS 在真机移动网络下的可达性未测（开发机上是通的）。
