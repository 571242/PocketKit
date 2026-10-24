# 本地工具实现说明（002 003 035–066 · B02 B05）

本文件由「本地工具」子代理维护，对应 [module-contract.md](module-contract.md) 中划给本子代理的范围。
**不修改**父会话的公共文件（`app/build.gradle`、Manifest、主界面、`BaseToolActivity`、注册表）。

---

## 1. 交付物与入口合同

| 文件 | 作用 |
|---|---|
| `app/src/main/java/cn/xianhua/pocketkit/LocalToolsActivity.java` | 本地工具宿主。主界面唯一的对接点 |
| `app/src/main/java/cn/xianhua/pocketkit/local/LocalTool.java` | 每个工具组实现的接口（`ids` / `limitations` / `install` / `release`） |
| `…/local/ColorTools.java` | 002 003 |
| `…/local/TextTools.java` | 035 036 037 038 039 040 042 043 044 046 048 049 |
| `…/local/CalcTools.java` | 050 051 052 053 054 056 059 060 063 066 |
| `…/local/LifeTools.java` | 057 061 062 064 065 |
| `…/local/MoneyTools.java` | B02 B05 |
| `…/local/LocalStore.java` | SharedPreferences 存储（账本 / 周期付款 / 纪念日 / 记分牌 + CSV 导出） |
| `…/local/TtsHelper.java` | 系统 TextToSpeech 封装（048） |
| `…/local/TranslateEngine.java` | 翻译引擎封装（049）：ML Kit 端上 + 可选 MyMemory |
| `…/local/HttpGet.java` | 极小 HTTPS GET 客户端（049 在线引擎与 052 共用） |
| `…/local/TimerPane.java` | 计时器与番茄时钟的前台计时（064 065） |
| `…/local/AnniversaryPane.java` `ScorePane.java` | 纪念日（057）、记分牌（061） |
| `…/local/DanmakuScreen.java` | 全屏弹幕（054）与全屏时钟（062） |

### 主界面怎么接

```java
// 判断可用性（唯一依据）
boolean ok = LocalToolsActivity.supports("037");
String why = LocalToolsActivity.limitation("049");   // 已实现的边界说明 / 未实现的原因

// 打开
Intent i = new Intent(this, LocalToolsActivity.class);
i.putExtra("tool_id", "037");
i.putExtra("title", "数字转中文");
startActivity(i);
```

`supports(id)` 只对 `LocalToolsActivity` 里真正注册过的 id 返回 true，**本模块的 31 个入口现已全部为 true**。
`limitation(id)` 返回该功能的已知边界（例如 049 的隐私披露、052 的「非实时汇率」、060 的覆盖范围），
主界面可以在入口旁或详情里展示。任何将来未实现的入口会返回 false 并说明原因。

### 关于 `ui*` 桥接方法

`BaseToolActivity` 的组装方法（`field` / `select` / `button` / `note` / `show` / `background`）是 `protected`，
而工具类在 `local` 子包里，跨包访问 protected 成员不合法。`LocalToolsActivity` 与基类同包，
因此由它转发出一组 `uiField` / `uiSelect` / `uiButton` / `uiShow` / `uiBackground` / `uiDp` / `uiInk` 等方法。
**没有修改公共基类**。

---

## 2. 核心算法（纯 Java，可独立测试）

全部在 `core/`，不依赖 Android，因此能被 `tools/selftest/SelfTest.java` 直接覆盖。

| 类 | 覆盖入口 | 说明 |
|---|---|---|
| `MathEval` | 051 | 手写递归下降四则运算求值。**绝不用任何脚本引擎**（Android 没有 evalJavaScript，也不该为算 1+1 引入 JS 引擎）。全程 BigDecimal，`0.1+0.2` 得 `0.3` |
| `ChineseNumber` | 037 | 整数/小数中文读数 + 人民币金额大写。自己实现，因为 ICU 的 RBNF 简体中文规则给的是「一百〇一」（台湾写法），大陆通行是「一百零一」，金额大写 RBNF 也不提供 |
| `PinyinTool` | 038 | ICU4J `Han-Latin` 音译，离线。多音字按通用读音（重庆 → chóng qìng） |
| `MorseTool` | 043 | ITU-R M.1677-1 国际摩斯码。汉字明确拒绝并提示先转拼音 |
| `TextScript` | 044 | 上标/下标/小型大写/带圈/全角的码点映射。Unicode 没有字形的字符原样保留并列出，不悄悄跳过 |
| `MiniStyle` | 042 | 本地字形替换（h3110 这类玩法），明示不是翻译 |
| `ArticleSummary` | 035 | **抽取式**摘要：按词频+位置给句子打分，抽出原句拼接。不改写、不生成、不联网 |
| `RelativeTitle` | 060 | 98 条常用亲属关系，最多 4 步。未收录明确返回，不猜 |
| `PaletteTool` + `PaletteData` | 002 003 | 颜色码互转（HEX/RGB/HSL/HSV/CMYK）、传统色匹配、6 种配色方案推导 |
| `MoneyTool` | B02 | 单位价格比价、金额均摊。全部 BigDecimal，保留 2 位 |
| `LedgerBook` | B05 | 账目/周期付款模型 + 到期计算 + 往返序列化 |
| `CsvSafe` | B05 | CSV 公式注入防护 |
| `MiniJson` | 052 049 | 自己写的极小 JSON 解析器（core 需保持纯 Java） |
| `FxRates` | 052 | 汇率解析 / 换算 / 币种表（v2，165 币种） |
| `MyMemoryResponse` | 049 | 在线翻译响应解析（含实测算出的错误陷阱） |
| `TextTranslate` | 049 | 翻译语言表与结果模型 |
| `UnitTool`（扩展） | 066 | 14 类单位，见下 |

自检：`bash .scratch/core-test.sh`（需在 classpath 加 icu4j，见 §6），**536 项通过 0 失败**。

---

## 3. 066 全能单位换算

类别与单位数**对齐样本资源** `evidence/aapt2-resources.txt` 的 `array/measure_type`（14 类）
与 `array/units_*`（长度 24、重量 21、温度 5、角度 7、面积 18、体积 18、速度 7、时间 9、压力 14、功/能/热 10、功率 10、力/能源 8、密度 6、数据存储 8）。

| 类别 | 单位数 | 备注 |
|---|---|---|
| 长度 | 24 | 含市制丈/尺/寸/分/厘/毫/里 |
| 重量 | 19 | 含市制担/斤/两/钱/分 |
| 温度 | 5 | 摄氏 / 华氏 / 开尔文 / 列氏 / 兰氏（仿射换算，单独处理） |
| 角度 | 8 | 含直角、百分度、毫弧度 |
| 数据存储 | 15 | **1024 进制（KiB/MiB…）与 1000 进制（kB/MB…）分开列**，混用是这块最常见的坑 |
| 面积 | 18 | 含市制亩/分/顷、平方尺/平方寸 |
| 体积 | 20 | 含美制/英制加仑、液量盎司、亩英尺 |
| 速度 | 9 | 含节、马赫、光速 |
| 时间 | 9 | 纳秒到年 |
| 压力 | 14 | 含英寸汞柱、毫米水柱、公斤力 |
| 功/能/热 | 12 | 含度（kW·h）、卡/千卡、英热单位、马力·时 |
| 功率 | 12 | 含公制马力与英制马力（数值不同：735.49875 vs 745.6998715823） |
| 力/能源 | 8 | 达因到千磅力 |
| 密度 | 9 | 含磅/立方英尺、磅/加仑 |

结果用 `UnitTool.round` 收敛到 12 位有效数字再去掉尾零，避免 `1 米 = 1000.0000000000001 毫米`。
界面**动态**从 `UnitTool.everything()` 取类别与单位列表，不硬编码。

已加的物理校验：温度低于绝对零度被拒（含列氏、兰氏）、长度与重量不接受负数。
零值换算稳定（0 换算仍为 0）。

---

## 4. 049 翻译 / 052 汇率：实现方式与依据

两个入口**都已实现**（`supports` 返回 true）。共同原则：需要联网的动作一律只在用户主动点击时发生，
失败一律明确报错，**绝不返回缓存或猜测的数据**。

### 052 汇率换算 —— Frankfurter（已实测）

来源：[Frankfurter](https://frankfurter.dev)，MIT 开源，无需 API 密钥。

| 项 | 实测结果（2026-10-13） |
|---|---|
| 端点 | `https://api.frankfurter.dev/v2/rates?base=USD&quotes=CNY` |
| 响应 | `[{"date":"2026-10-12","base":"USD","quote":"CNY","rate":6.6883}]` |
| 币种覆盖 | **v2 覆盖 165 个币种**（含 CNY、TWD、RUB、VND、CNH）。**v1 只有 30 个**，实测 TWD/RUB 在 v1 里被静默丢弃，因此用 v2 |
| 历史汇率 | 支持：`/v2/rates?base=USD&quotes=CNY&date=2026-01-15` |
| 错误形态 | 未知币种返回 HTTP 404 + `{"message":"not found"}` |
| 数据性质 | 各国央行与官方机构公布的**参考汇率**（公开数据） |

界面**同时展示**汇率日期与来源 —— 结果里都带了。Frankfurter 的许可页写明：它不拥有这些汇率、
不保证实时（可能延迟 / 缺失 / 修订）、混合汇率是它自己的计算，并警告不要用于税务、会计或法律用途。
这些句子照写进了 `FxRates.SOURCE_NOTE`。

**常用币种名单是逐个对照 v2 的 165 个币种筛过的**：BGN（保加利亚）与 HRK（克罗地亚）因两国已改用
欧元而**不存在于 v2**，已剔除（有两条测试守着）。名单外的币种仍可手填 ISO 代码查询。

实现要点：
- `core/MiniJson`：自己写的极小 JSON 解析器。`core/` 必须是纯 Java（不依赖 Android 的 `org.json`），
  也不值得为几十行解析引入第三方库。严格解析，格式不符就抛错。
- `core/FxRates`：解析 + 换算 + 币种表。**任何字段缺失都视为失败**，并校验服务返回的 `base`
  与请求一致（否则会拿错误的基准算钱）。
- `local/HttpGet`：极小 HTTPS 客户端。固定主机、连接 10 s / 读取 15 s 超时、512 KB 上限、
  `Cache-Control: no-cache`。区分 DNS 失败、超时、TLS 失败、HTTP 非 2xx，各自给不同文案。
- **绝不回退到旧汇率**：拿旧数字冒充当前汇率比报错更糟。

### 049 翻译 —— 默认端上翻译，在线引擎必须显式勾选

**主路径：ML Kit 端上翻译**（`com.google.mlkit:translate:17.0.3`）。

选它的理由很直接：不索取密钥、**不把用户文本发给第三方翻译服务**、模型在设备上运行。
Java API 已核对（反编译 AAR 确认签名）：`TranslatorOptions.Builder` / `Translation.getClient` /
`downloadModelIfNeeded()` / `translate(String)` / `close()`；`TranslateLanguage` 有 59 个语言常量，
本工具的 30 种常用语言全部覆盖。AAR 27.7 MB 下载成功且编译通过。

**隐私必须如实说明（不能称「零通信」）**：据
[Google ML Kit 数据披露页](https://developers.google.com/ml-kit/android-data-disclosure)，
`com.google.mlkit:translate` 会收集**配置的源语言与目标语言**用于诊断与用量分析，
并使用 Firebase remote config / installations；它不收集文本内容，也不把数据转给第三方。
这一条写进了 `limitation("049")` 与界面文案。

**为什么没有默认用匿名在线翻译接口**：MyMemory 的官方限额页（`/doc/usagelimits.php`）明确写着
「匿名免费额度 5000 字符/天」，并且「只调用 get 端点、不通过 set 端点回馈的私人项目与开发中的项目，
**永远不会通过白名单审核**」——「只取不还」的客户端正是它明文不欢迎的用法。
因此做成**必须用户手动勾选**的次选路径，勾选框上直接写「会把文本发送给第三方」。

**实际验证过的候选（2026-10-13）**：

| 候选 | 实测结果 | 结论 |
|---|---|---|
| LibreTranslate 公共实例 | `libretranslate.com` 返回 Cloudflare 拦截页；`translate.terraprint.co` 502；`lt.vern.cc` 无响应 | 全部不可用 |
| Lingva（Google 前端代理） | `lingva.ml` 返回 Cloudflare challenge「Just a moment...」；`lingva.garudalinux.org` 无响应；`translate.plausibility.cloud` 返回 500 | 全部不可用 |
| Google 未公开端点 `translate_a/single` | 非公开接口 | 不用 |
| **ML Kit 端上翻译** | **AAR 下载成功、Java API 编译通过、59 种语言** | **采用（默认）** |
| MyMemory 匿名接口 | 可用（`hello` → `你好`），但额度页明确不欢迎本用法 | 采用，但必须用户勾选 |

**必须处理的坑（实测确认，已加测试）**：MyMemory 出错时 **HTTP 状态仍是 200**，
真正的状态放在响应体的 `responseStatus`，而错误说明会被塞进 `responseData.translatedText`
（也就是「译文」字段）：

```
{"responseData":{"translatedText":"QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS"},
 "responseStatus":"403", ...}
{"responseData":{"translatedText":"'XX-YY' IS AN INVALID TARGET LANGUAGE . EXAMPLE: LANGPAIR=EN|IT ..."},
 "responseStatus":"403", ...}
```

只看 HTTP 状态码、或只看 `responseStatus`、或不识别这些全大写英文说明，
界面上就会把一句英文报错当成翻译结果展示给用户。解析逻辑因此在
`core/MyMemoryResponse`（可被纯 Java 测试覆盖），并对上述真实响应逐一断言。

另外实测：单次请求超过 500 字符时服务返回 403 + `QUERY LENGTH LIMIT EXCEEDED`，
因此客户端在本地就先拦下超长请求。

---

## 4b. Manifest 注册：本模块**不需要新增任何 Activity**

精确回答：

- **本模块没有新增任何 Activity 类**。`local/` 与 `core/` 中没有任何 `extends Activity` /
  `extends BaseToolActivity` 的类（已用 grep 核对）。全部界面都是往宿主
  `cn.xianhua.pocketkit.LocalToolsActivity` 上装 View。
- **049 / 052 的联网不需要额外组件**，只是从宿主 Activity 发起一次请求。
- **054 手持弹幕、062 时间屏幕用的是 `Dialog`**（`local/DanmakuScreen.java`，
  `Theme_Black_NoTitleBar_Fullscreen`），**不是 Activity**。所以**不需要在 Manifest 里注册**，
  也没有隐式的 Activity 入口。这样选的另一个好处：不用改 Manifest、不占宿主界面状态，
  关掉就回到原来的输入框。
- **048 文字转语音会发两个外部 Intent**（都只在用户点按钮时才发，失败有降级）：
  1. `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` → 常量值
     `android.speech.tts.engine.INSTALL_TTS_DATA`（系统语音数据安装页）
  2. 回退：`com.android.settings.TTS_SETTINGS`（系统语音设置）

  这两个是**发出去的 Intent**，不是我们的组件，同样不需要注册。
  Android 11+ 的包可见性规则下，系统设置类 Intent 可直接解析，无需 `<queries>`。
- 唯一的外部入口是本模块**被主界面调用**：
  `new Intent(ctx, cn.xianhua.pocketkit.LocalToolsActivity.class).putExtra("tool_id", id)`
  —— 该 Activity 已在 Manifest 中（`android:name=".LocalToolsActivity" android:exported="false"`）。

所以本模块对 Manifest 的**唯一要求**是 `LocalToolsActivity` 已注册（现已注册），
外加 `INTERNET` 权限（已在，052/049 之前就存在）。

---

## 4c. 需要父会话加入的依赖（app/build.gradle 属父会话所有，我没有改）

```gradle
implementation 'com.google.mlkit:translate:17.0.3'
```

- 用途：049 翻译的默认端上引擎。
- 传递依赖（`play-services-tasks`、`androidx.lifecycle`、`androidx.annotation` 等）AGP 会自动解析；
  Manifest 合并带来的 `INTERNET` 与 `ACCESS_NETWORK_STATE` 应用都已声明，不会冲突。
- 不加入时：`local/TranslateEngine.java` 与 `core/TextTranslate.java` 会编译失败。
  若暂时不想加这个依赖，请告知，我会把 049 回退为 `supports=false` 并保留说明。

---

## 5. 传统色数据来源（002 / 003）

**绝不从商业 APK 提取素材。** 用的是两份公开、MIT 许可的数据集，许可证原文存放在
`.scratch/local/colors/*.LICENSE.txt`（建议纳入交付物）。

| 数据 | 条数 | 来源 | 许可 |
|---|---|---|---|
| 中国传统色 | 161 | [zerosoul/chinese-colors](https://github.com/zerosoul/chinese-colors) 的 `src/assets/colors.json` | MIT（Copyright © 2019 Tristan Yang） |
| 日本传统色 | 250 | [syaning/nippon-colors](https://github.com/syaning/nippon-colors) 的 `colors.json` | MIT（Copyright © 2016 Alex Sun） |

**独立溯源验证**：与中文维基百科《中国传统色彩》条目公开列出的色值交叉比对，
多条完全吻合 —— 品红 `#F00056`、桃红 `#DB5A6B`、朱砂 `#FF4C00`。说明该数据集与公开参考同源，不是自造值。

`core/PaletteData.java` 由 `.scratch/local/gen-palette.py` **自动生成**，没有手工誊写任何色值，
也没有从任何 APK 提取。重新生成：`python .scratch/local/gen-palette.py`。

配色方案（互补/邻近/三角/分裂互补/明度阶梯/同色系弱饱和）由输入色按 HSL 推导，纯本地、结果可复现。
「最接近的传统色名」是按加权 RGB 距离匹配的近似，界面已写明**不代表该色就是那个传统色**。

---

## 6. 构建与自检

### 纯 Java 核心自检（不跑 AGP、不并发 Gradle）

```bash
export PATH="/f/mlbuild/tools/jdk-17.0.20.1+1/bin:$PATH"
mkdir -p .scratch/core-test
javac -encoding UTF-8 -cp '.scratch/local/libs/icu4j-76.1.jar' -d .scratch/core-test \
      app/src/main/java/cn/xianhua/pocketkit/core/*.java tools/selftest/SelfTest.java
java -Dfile.encoding=UTF-8 -cp '.scratch/core-test;.scratch/local/libs/icu4j-76.1.jar' SelfTest
```

### 本模块的 Android 编译检查（含 ML Kit）

```bash
bash .scratch/local/compile-check.sh
```

它用 `android-36` 的 android.jar + `icu4j` + `mlkit-translate`（含其传递依赖）编译
`core/` + `local/` + `BaseToolActivity` + `LocalToolsActivity`。

> 注意：Windows 的 classpath 分隔符是 `;`，且 `javac` **不认 MSYS 风格的 `/f/...` 路径** ——
> 混用会让所有 jar 静默失效（症状是「程序包不存在」）。脚本里用 `cygpath -w` 转换。
> `-Dfile.encoding=UTF-8` 是必须的，否则 Windows 控制台会把中文显示成乱码（不是程序问题）。

## 7. 外部依赖与权限

| 依赖 | 用途 | 许可证 / 性质 |
|---|---|---|
| `com.ibm.icu:icu4j:76.1` | 038 汉字转拼音（离线规则打包进 APK） | Unicode 许可证，允许再分发 |
| `com.google.mlkit:translate:17.0.3` ← **需父会话加入** | 049 翻译的默认端上引擎 | 见下方说明 |
| 系统 `android.speech.tts.TextToSpeech` | 048 文字转语音 | 系统服务。**不内置、不下载语音包** |
| 无 | 其余全部为 JDK 标准库 | — |

### 关于 ML Kit 的措辞（不要写成「全开源 / 零通信」）

- ML Kit 是**闭源 SDK**，不能称其开源。AAR 里的 `third_party_licenses.json` 列出 124 项第三方组件
  （Abseil、AndroidX、Bazel Platforms 等，多为 Apache-2.0），那是**它内部用到的**开源组件的许可证，
  不等于 ML Kit 本身开源。
- 它**不是零通信组件**：据官方数据披露页，`translate` 会收集配置的源语言与目标语言，
  并使用 Firebase remote config / installations 做远程配置诊断；不收集文本内容，也不转交第三方。
- 任务书要求「使用打包中文模型保证识别模型可离线运行，另核实 SDK 数据披露」——
  **翻译功能的模型是按需下载**（约 30 MB/语言对），不是打包在 APK 里；
  本项目已有的 OCR（`text-recognition-chinese`）才是 bundled 模型。两者不要混为一谈。

### 权限与联网行为

本模块**没有新增任何权限**。049 / 052 用的 `INTERNET` 与 `ACCESS_NETWORK_STATE`
是应用原有声明（ML Kit 合并也会带来这两个，方向一致，不冲突）。

联网全部**由用户点击触发**：
- 052 汇率：点「获取汇率并换算」才请求。
- 049 翻译：默认端上翻译，只有首次使用某对语言下载模型时联网；
  在线引擎额外需要用户手动勾选。

**没有自动请求、没有后台刷新、没有启动时预热。**

存储：`SharedPreferences`（`LocalStore`）。个人记账的数据量与查询需求不需要 SQLite，也就不引入额外复杂度。
数据只在本机，不上传、不联网、不需要账号。

---

## 8. 关键实现取舍（都写在界面上）

- **统一在前台提醒**。计时器/番茄时钟**不申请通知权限、不做后台提醒**，只在应用处于前台时响铃+震动。
  这是刻意取舍：后台提醒要做到可靠，需要前台服务 + 通知权限 + 电池优化白名单，代价远超一个计时器的收益。
- **064/065 离开页面即重置计时**。不做状态持久化。理由是旋转或重建后从零开始，代价小于引入整套保存/恢复与后台服务。
- **周期付款（B05）只记录与计算到期日**：`LedgerBook.computeDue` 给出「还有几天到期」「此前有几期未记录」，
  但**不做后台报警、不发通知、不自动扣款、不发任何网络请求**。需要主动来点的动作是「记录已付」。
- **删除必须先确认**。`LocalStore.delete` / `deleteRecurring` / `deleteAnniversary` 的 `confirmed` 参数
  必须在界面二次确认后传 `true`，否则直接拒绝。
- **防覆盖丢失**：写入后立刻读回校验，不一致就回滚到旧值并报错，而不是留下半截数据。
  单条记录损坏不会拖垮整个账本（跳过并在界面提示损坏条数）。
- **CSV 公式注入防护**：以 `=` `+` `-` `@` 或制表符开头的内容会前置单引号，
  否则 Excel/WPS 会把用户填的备注当公式执行。
- **金额一律 BigDecimal 且保留两位**，不用 double 算钱。金额大写按「分」四舍五入，
  且金融写法不省开头的「壹」（10 元写「壹拾元」，写「拾元」会被银行退回）。
- **035 是抽取式摘要**，界面与 `limitation` 都强制展示声明：
  「本摘要由本地抽取算法从原文挑句组成，未改写、未生成，不是生成式 AI 的产物。」

---

## 9. 当前状态与未实测项（如实报告）

**已实测（本轮）**
- 纯 Java 核心自检 **536 项全部通过**（含边界与错误路径）。
- 全部源文件的 Android 编译检查通过（`android-36` + `icu4j` + `mlkit-translate`），45 个源文件。
- ICU4J 76.1 的 Han-Latin 音译实际运行验证（含多音字）。
- Frankfurter v2 与 MyMemory 以 curl 实际访问，抓取真实响应（含错误响应）作为测试夹具。
- ML Kit translate 的 AAR 实际下载并反编译核对 Java API 签名；`TranslateLanguage` 59 个语言常量已导出。
- 传统色数据与维基百科公开色值交叉比对。

**未实测（不得声称已验证）**
- **所有界面都没有运行过**：当前无真机、无可用的硬件加速模拟器。
  旋转恢复、TTS 实际朗读、全屏弹幕/时钟、计时器前台提醒、SharedPreferences 实际读写
  均只做了编译检查与算法自检，**没有在 Android 运行时验证过**。
- **049 的端上翻译没有真机验证过**：模型下载（约 30 MB）与真实翻译质量、
  首次下载耗时、无网络时的报错路径，都只在代码层面处理，**未在设备上跑过**。
- **052 的联网请求没有在 Android 上跑过**：`HttpGet` 的超时与错误分支只做了代码级处理，
  真实网络环境（弱网、代理、IPv6）未验证。curl 验证的是服务端行为，不是应用内的客户端行为。
- 未执行 AGP 构建（遵约不并发 Gradle）。合并后需由父会话统一跑 `assembleDebug`；
  加入 `com.google.mlkit:translate:17.0.3` 后需要重新确认依赖解析与 Manifest 合并结果。
- 大账本（数千条）下 SharedPreferences 的读写性能、`computeDue` 在很久以前的起始日期下的最坏步数，
  都只做了逐步实现层面的估算，没有压测。
- 048 的系统语音引擎可用性完全取决于设备：**没装中文语音时应用会明确提示去系统设置安装**，
  不会假装朗读成功；能否离线合成由系统引擎决定，部分引擎需要联网。
