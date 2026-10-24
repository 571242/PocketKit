# 自动化工作台验收清单

本文件由调度者维护。桌面构建与离线自检已通过，设备运行验收未通过也未执行。以下保留原门槛用于真机复验，不能将源码/编译/JVM检查当成手机功能通过。

## 本次实际结果
- assembleDebug、assembleDebugAndroidTest、lintDebug：退出0。lint无error，但仍有警告，具体以最终报告为准。
- 独立执行核心、变量正则预算/取消、视觉正则、合成图片匹配、抢票配方转换、UI源码契约检查：退出0。UI检查只是静态契约，不是UI运行。
- 图匹配：1080×2400合成图最终基准507ms，7项断言通过；仅代表本机JVM，不代表手机速度。
- 最终编译反射目录：92/92 supports=true，errors=0。合并Manifest版本11/0.3.0、DEX连续、四ABI、ZIP16KiB对齐检查通过。
- APK v2签名验证通过，证书SHA256与0.2.2一致。debug签名不是生产发行签名。
- adb再次检查无连接设备；deviceRuntimeVerified=false，actualTicketPurchaseVerified=false。下方真机清单仍待执行。
- 安装包SHA256：230fae721deb7cdd18545d4165a81a9d653d71f08556faa77a4ee098ebea0150，263480562字节。

## 范围与交付
- 用户已确认：原生独立模块、可视化优先、离线OCR与识图、逻辑判断/循环、目标采集、脚本管理/导入导出、控制与日志；071复用同一引擎；不加入任意JS运行时/云识别。
- 保留0.2.2安装包和数据键；新安装包0.3.0/code11，包名/调试证书一致才可覆盖更新。
- 源码快照：dist/PocketKit-before-automation-20261014.zip（实现前，SHA256在同名sha256文件）。

## A 核心与持久化审查
- Step/Script schema与所有UI动作一致；未知schema/动作/嵌套深度/非法数字被明确拒绝。
- 手势完成回调才推进；超时/取消/代际切换后不再发旧动作；暂停阻止下一手势，恢复不意外重放。
- WAIT/WAIT_UNTIL/启动延迟/运行预算使用正确墙钟和单调时钟语义；时间变化与取消有测试。
- IF/LOOP嵌套、else分支、文字/图片等待、OCR变量与IF_VARIABLE实际执行；死循环每轮可取消且不忙等。
- 识别失败/超时清除lastMatch，useLastMatch不能静默沿用旧坐标；ROI/匹配中心不可双重缩放。
- 绑定App只是一项用户配置，无引擎用途黑名单或隐藏的支付/验证码词表。
- 导入不自动执行、不覆盖旧id；模板id无路径遍历；全部资产验证后再提交，错误应回滚新增资产；SAF访问授权和文件大小有边界。
- 悬浮控制/常驻通知就绪才开跑；销毁控制、锁屏、无障碍撤回与服务断开有明确停止。

## B 视觉与权限审查
- 中文/英文OCR真正走MLKit打包模型，与nodes查找可区分；不接云/不上传截图。
- API30+截图能力已注册；HardwareBuffer转软件Bitmap并正确关闭，限流/安全窗口/授权失败具体文案。
- API26-29 MP启动只来自用户授权；startForeground早于getProjection；callback早于VD；一个token一次createVirtualDisplay；新帧不是之前缓存；旋转/撤授权清理。
- 捕捉前自身浮窗隐藏，finally恢复；等待/取消不能留下隐藏的控制。
- 图匹配合成图正确定位、亮度改变、ROI偏移、阈值、无匹配/常量/边缘；必须记录实际基准规模/耗时，不承诺手机性能。
- 模板与导入图片解码检查像素/格式/大小，Id只到私有目录；屏幕截图和输入内容不写到外部或日志。

## C UI与操作路径审查
- C01首页可见，支持/limitation反射合同能加载；页内脚本库/编辑/日志/权限能找得到。
- 新建→添步骤→改参数→子分支→采集目标→保存→运行→暂停/恢复→急停→查看日志形成完整路径。
- 点位/滑动/ROI/模板采集实际回写Script；遮罩吞掉采集触摸，目标App不被采集触摸点击。
- 条件children与otherwise都可编辑；重复计数/识别源/超时/匹配结果点击/变量字段可达；模板生成真实Step不是隐藏专用逻辑。
- 保存/重命名/复制/删除、带素材导入导出正常；返回/导航/旋转保留草稿，避免服务回写被旧内存覆盖。
- 深浅色和系统字体缩放/48dp触控符合现有样式；悬浮栏不整屏吞触摸且满足Android12穿透alpha约束。
- 无设备时仅能验证源码/API/编译，不把人工想象的布局称作截图视觉验收。

## D 接入与旧版回归审查
- TicketTapService保留component/data API并真实调用Runtime；旧handler轮询执行器被移除。
- 071配方/time/network校准与独立倒计时保持；自动任务不是两套浮窗互相停止；支持送到通用工作台编辑。
- 旧SharedPreferences键/recipe字段/包名不变；没有系统授权component改名。
- Manifest新增Activity、控制/采集specialUse、投影mediaProjection和权限；accessibility加截图能力；没有多加无关权限。
- C01目录/说明/版本与compiled reflection、APK内目录一致；旧算法测试继续通过，不以删除测试掩盖回归。

## 整包证据
- 核心与图匹配自检：记录每条实际命令、退出码、真实断言，不能以Gradle tests=0冒充单测。
- assembleDebug / assembleDebugAndroidTest / lintDebug：真实完整产物，不只编译部分Java；旧warnings分开，不静默忽略new error。
- 编译反射目录覆盖（新结果文件）/Manifest合并版本与组件/DEX类/包内OCR模型/ABI/ZIP native对齐/无密钥混入。
- APK签名实际验证、与上一版本证书一致、SHA256与size记录；新文件独立命名。
- 设备状态再次核实：adb连接才可运行Instrumentation和截图；没有设备就保持deviceRuntimeVerified=false、actualTicketPurchaseVerified=false。

## 真机最小检查（当前待执行）
1. 覆盖安装后打开旧071，确认时间和已录点位仍在。
2. 开启系统无障碍（若受限设置先由用户允许）和悬浮权限。普通测试界面运行连点/长按/滑动，急停后不得继续出手势。
3. 制作IF/循环/else与OCR文字→匹配坐标点击；断网识别中英文，测试识别超时与未命中不点击。
4. 截图框选图片模板，保存/运行/导出/导入新脚本后验证素材仍可识别；测试屏幕旋转提示。
5. Android8-10系统投影授权/撤销、Android11+截图，敏感安全窗口失败文案；关闭浮窗/撤回无障碍后停止。
6. 071预约在无实际购买的测试页面执行，验证开始时刻/点位/可暂停停止；不以真实扣款验证算法。