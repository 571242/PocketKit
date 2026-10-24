# PocketKit 自动化工作台实施方案（2026-10-14）

## 访谈结论（用户已明确确认理解一致）
- 参考 AutoX.js 的能力和操作，不复制代码，不追求全部 AutoX API 兼容。独立原生模块，保持现有视觉体系。
- 首版先做可视化步骤，不提供任意 JavaScript 执行器或编辑器。
- 多点点击、长按、滑动、等待、循环、条件分支、本地中英文 OCR、图片模板匹配、目标采集、脚本保存/导入导出、日志、悬浮启动/暂停/急停、脚本内预约等待。
- 通用引擎不按用途设限；由用户控制操作。没有隐藏的支付/验证码词表停机或目标应用黑名单。不是权限/验证码/安全窗口绕过工具。
- 现有 071 抢票入口保留，改为生成同一引擎的脚本模板；保存的时间、点位与配置不能丢。
- 全本地识别，不接云、不上传截图。复用已打包 ML Kit 模型；不把闭源 SDK 描述为经网络监听证明的零通信组件。
- 交付源码与 APK；设备验证取决于实际设备，不能把 JVM/编译通过写成真机通过。

## 已核实事实与架构决定
- Java17 / 原生 View / 单 :app / minSdk26 / targetSdk36 / AGP8.13.0。无 Git，变更前源码已归档 dist/PocketKit-before-automation-20261014.zip。旧 0.2.2 成品保留。
- 保留 device.TicketTapService 的 component 身份，作为唯一已授权的无障碍入口；连接/断开转发通用 AutomationRuntime。旧 arm/stop/status API 保留为适配器，旧循环执行器移除。多个无障碍服务在 Android 上并非天然不允许；选单入口是为了少一次授权和避免重复手势。
- Android11/API30+ 使用无障碍按需截图；Android8–10 用用户明确授权的 MediaProjection 前台截图会话，minSdk 不提高。安全窗口失败明确提示，不尝试绕过。
- 沿用 ML Kit 包内中文/拉丁模型；模板匹配独立实现，不引入 OpenCV/JS/Kotlin。不要误删 ML Kit 必需的 play-services 传递依赖。
- 工作台新编号 C01、分类 自动化、module AutomationToolsActivity；接入者先检查编号是否冲突。版本目标 0.3.0 / code11（若接入时已被其他改动占用，先报告）。
- UI 延续 BaseToolActivity 实际令牌（ink、muted、surface、accent、base）；列表式工作台，步骤的缩进表达真实条件层级，参数按动作显示，48dp 触控、深浅色、系统字体缩放。

## 冻结数据与跨模块接口 v1
实现者可自主组织内部代码；以下跨模块名字/签名冻结，若必须改动须先告诉父代理，不自行让其他代理猜。

### 核心模型 cn.xianhua.pocketkit.core.automation（纯 Java）
- Step.Type：TAP、LONG_PRESS、SWIPE、WAIT、WAIT_UNTIL、INPUT_TEXT、BACK、HOME、RECENTS、WAIT_TEXT、WAIT_IMAGE、OCR、IF_TEXT、IF_IMAGE、LOOP、SET_VARIABLE、IF_VARIABLE、STOP。
- Step 可变公开字段：String id（稳定UUID）、label、text、templateId、variable、value、operator、textSource（默认ocr，可选nodes）、matchMode（contains/exact/regex）；Type type；int x,y,x2,y2,left,top,right,bottom（ROI均为0表示全屏）；long durationMs,timeoutMs,intervalMs,atEpochMs；int count（LOOP 0为直到停止）；double threshold（0..1）；boolean useLastMatch；List<Step> children,otherwise。提供无参构造与 Step(Type)。
- Script 可变字段：String id,name,targetPackage（空=不绑定）；int width,height（录制参考分辨率，0表示当前）；long updatedAt,startDelayMs,timeoutMs（0表示不设置运行总时长）；List<Step> steps。提供无参构造和 validate()。
- Match：public final boolean found；public final int left,top,right,bottom；public final String text；public final double score；public Match(boolean,int,int,int,int,String,double)；centerX()/centerY()。
- 坐标为录制屏幕的物理像素；执行器可按同方向尺寸缩放，方向改变先明确停止，识别Match是当前截图坐标，不再二次缩放。
- Runner 在单后台线程执行，Backend 契约由核心内部决定；每次操作前检查取消/暂停/绑定App，手势完成后才推进；WAIT_UNTIL 到点后/延迟与超时用单调时钟；循环/等待/识别均可及时急停；无忙等。变量替换支持 ${name}，识别命中可供下一动作 useLastMatch；失败不默默点击旧匹配坐标。

### 核心 Android API cn.xianhua.pocketkit.automation
- ScriptCodec.encode(Script)->String；decode(String)->Script（严格可运行校验，schemaVersion=1）。第二轮增加 encodeDraft(Script)->String、decodeDraft(String)->Script：保留未完成text/template/variable等操作数，但仍严格校验schema、动作类型、数字、深度、总量、id/路径，不能运行draft来绕过 Runtime 的完整validate()。
- new ScriptStore(Context)：list()->List<Script>、load(String)->Script（可加载draft以供编辑）、save(Script)（完整校验）、saveDraft(Script)（严格结构+未完成操作数可存）、delete(String)、exportScript(Script)->String、importScript(String)->Script。第二轮统一 public static final int MAX_TRANSFER_BYTES=16*1024*1024；最终导出payload不得超此上限，UI脚本导入沿用同一上限，图片单件仍按TemplateStore上限。脚本私有存储；导出包含实际引用的图片资产，导入创建新id而不是覆盖同名；限制大小/深度/图片像素/路径，先完整验证再提交，不能执行导入的脚本。
- AutomationRuntime：attach(AccessibilityService)、detach(AccessibilityService)、isEnabled()->boolean、start(Context,Script)、pause()、resume()、stop(String)、isActive()->boolean、isPaused()->boolean、service()->AccessibilityService（未连接时null）、status()->String、logs()->List<String>、addListener(Runnable)、removeListener(Runnable)。监听回调在主线程，日志有合理容量，不记录密码。
- AutomationRuntime.start 先建立用户可见的前台悬浮控制/通知，再执行。UI服务在控制创建后直接调用 public static void AutomationRuntime.controlsReady()，关闭/销毁时直接调用 public static void AutomationRuntime.controlsClosed()（两个方法为强制公开合同，不准反射缺失时静默跳过）。取消、代际隔离、后台超时不能留下旧任务手势。

### 视觉 API cn.xianhua.pocketkit.automation
- ScreenCapture.capture(AccessibilityService,long timeoutMs)->Bitmap，后台线程调用，返回由调用者回收的软件Bitmap；隐藏自身浮窗后取新帧，限流/授权失效/FLAG_SECURE明确异常，finally恢复浮窗。
- AutomationVision.findText(AccessibilityService,String query,String source,String matchMode,android.graphics.Rect region,long timeoutMs)->Match。source=ocr 或 nodes；matchMode=contains/exact/regex。
- AutomationVision.readText(AccessibilityService,android.graphics.Rect region,long timeoutMs)->String（中英文离线OCR，坐标含ROI偏移）。
- AutomationVision.findImage(AccessibilityService,Bitmap template,android.graphics.Rect region,double threshold,long timeoutMs)->Match。
- new TemplateStore(Context)：save(Bitmap)->String、saveBytes(byte[])->String、load(String)->Bitmap、read(String)->byte[]、delete(String)。模板私有保存，id由UUID生成，不允许导入任意文件路径。
- ProjectionCaptureService：start(Context,int resultCode,Intent resultData)、isReady()->boolean、stop(Context)；屏幕投影同意Intent由UI通过系统MediaProjectionManager创建。只在确有用户授权后启动，按API正确声明FGS；token只使用一次、registerCallback早于VirtualDisplay；屏幕旋转/进程重建/授权撤回须清理且明确重授权。

### UI/控制 API
- AutomationToolsActivity extends BaseToolActivity，在根包，public static supports(String) 和 limitation(String)，支持C01。不改公共Base UI，仅在新Activity或自有桥组装控件。
- AutomationOverlayService.show(Context)、setCaptureHidden(boolean)。foreground specialUse，常驻通知有停止按钮；运行/暂停/恢复/停止、可拖动，Android12非触摸浮窗合成alpha不超过0.8；controlsReady/Closed按上方核心合同调用。
- AutomationPickerService.start(Context,String scriptId,String stepId,String mode)、setCaptureHidden(boolean)、stop(Context)。mode=point/swipe/template/region；按id加载/更新脚本，递归寻找Step；点位、滑动端点、ROI、截取图片模板能回写；取点遮罩吞掉此次触摸不穿透目标App；保存/取消清楚可达。
- 编辑器必须可到达 LOOP/IF 的 children 与 otherwise 编辑（不是只显示字符串）；选择图片/文字、匹配结果点击、超时/重复/间隔、变量判断均可用。保存草稿/旋转/导航不要丢用户填写。

## 并行任务板与文件所有权
A 执行核心：core/automation 下除 TemplateMatcher.java 外全部；automation/ScriptCodec.java、ScriptStore.java、AutomationRuntime.java、AccessibilityBackend.java 与自身辅助类；tools/automation-selftest 下引擎/存储测试。不得改公共注册、Ticket*、视觉/UI所有文件。
B 离线视觉：core/automation/TemplateMatcher.java；automation/ScreenCapture.java、AutomationVision.java、ProjectionCaptureService.java、TemplateStore.java 及视觉辅助类；tools/automation-vision-selftest。不得改Manifest、Ticket*、OCR现有工具、A/C文件。
C 界面与悬浮控制：AutomationToolsActivity.java；automation/AutomationOverlayService.java、AutomationPickerService.java、AutomationEditor.java 与UI辅助类；res/values/automation_ui_strings.xml；自有UI测试。不得改BaseToolActivity/ToolUi/Manifest/注册/A/B/Ticket*。
D 抢票迁移与公共集成：TicketTapService.java、TicketAutoPanel.java、TicketOverlayService.java、TicketRecorderService.java（仅必要适配）、TicketRecipe.java（仅必要兼容）、TicketAssistantTool.java（仅必要文案）；新automation/TicketScriptFactory.java；AndroidManifest.xml、res/xml/ticket_accessibility.xml、res/values/ticket_strings.xml、app/build.gradle（仅版本）、selected-tools.json、ToolDescriptions.java、所需回归/注册测试和制品校验工具的最小目录数量适配。不得改A/B/C文件或静默删除旧测试。
父代理：计划、决策、文档、任务派发、关键代码复审、独立验收、交付；不替子代理写模块。

## 验收层次
1. 子代理：读现有约定并自行规划/实现/自检，报告文件与行号、实际命令/退出码/断言、未知项；过程探针仅项目 .scratch/ 各自子目录，结束清理自己的临时文件。
2. 父代理：复读关键状态机、识图、截图生命周期、导入路径与前台服务；对照上述验收，不把自我陈述当通过。发现缺口发回责任代理，最多两次返工。
3. 整包：核心/匹配/旧回归自检、assembleDebug、lintDebug（核对真实warnings）、APK签名/manifest版本/模型/目录/DEX/密钥泄漏校验；新APK独立命名，保留旧包。
4. 当前无adb设备；真机无障碍、悬浮窗、OCR推理、截图与实际点击尚不能验收。最终明确这一缺口并附手机检查清单，不能宣称完美成品或实际抢票成功。

## 构建命令环境
JAVA_HOME=F:/mlbuild/tools/jdk-17.0.20.1+1；ANDROID_HOME=F:/mlbuild/sdk；Gradle=/f/mlbuild/tools/gradle-8.14.3/bin/gradle.bat；优先--offline。不得改全局环境、不安装替代UI服务器、不读取或泄漏密钥。