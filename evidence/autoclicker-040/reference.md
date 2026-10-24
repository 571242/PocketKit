# 参考 APK 的静态证据

来源是用户指定的 [小触控 APK](<F:/Download/xiaochukong_860979.apk>)。SHA-256：`2867c3f344ceeab5573026f9d87ba405d72b10fa2de6f85fb253d4fd77344db5`。

本次分析仅限文件与资源，不运行目标 APK，不拷贝其代码、视频、模型或图片素材到产品。

## 可复核材料

- [REA 文件审计](reference-artifact.json)：`inspect-artifact` 成功；根证据 ID `ev_4be59c6450e0e5a9bb9f7577463d6c1d9d0841814b60cd5e0bc6dc78267686de`。
- 包内资源目录 inventory 证据 ID：`ev_116c9ced6ab51ef3bcbbe5b31ddada9230bbab9c9a34decbe337ede184db3400`。
- [APK 基本信息](reference-badging.txt)、[编译清单转储](reference-manifest.txt)、[无障碍配置转储](reference-accessibility.txt)：来自本机既有 aapt2；后两份是资源树文本，不是还原出的应用业务代码。
- [Android 分析能力限制](reference-android-unavailable.json)：缺少 `REA_JADX_MCP_JAR` 与对应 JADX MCP，未擅自安装。没有业务代码反编译结果。

## 观察与判断的区别

观察：包名 `com.chenai.littletouch`，版本 `26.09.11`；minSdk 24、targetSdk 33。清单包含无障碍、悬浮窗、MediaProjection 和后台任务服务；无障碍配置声明手势执行、窗口内容与按键过滤能力。资源存在权限引导、悬浮操作与录制教程相关名称和文案。

判断：这些信息支持“显式权限引导 + 随时可见的悬浮控制 + 录制/编辑/回放闭环”作为可借鉴的交互组织方式，不能证明该 APK 实际运行时的流畅度、异常处理或业务实现方式。壳类和加固元数据说明静态代码可见性有限；没有把资源字符串当成已验证的运行行为。

PocketKit 的对应实现为独立原生代码。没有复制云脚本、会员、广告、支付 SDK 或加固载荷，也不宣称本次已达到参考应用的完整功能或商业可靠性。
