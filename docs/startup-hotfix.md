# 0.1.6-startup：窗口初始化顺序修复

用户在 vivo V2507A / Android 16 / API 36 提供了明确堆栈：BaseToolActivity 第55行在 setContentView 前调用 Window.getInsetsController，PhoneWindow 内部 DecorView 为 null，调用本身已抛异常，后续 controller 判空无法保护。

修复：将系统栏控制器获取与外观设置移到 setContentView(root) 后；所有共用基类的首页、说明页与工具页一起修正。versionCode 7，版本 0.1.6-startup；包名与调试证书保持不变，覆盖安装，不卸载、不清数据。

验收：assembleDebug / lintDebug 成功，0 errors / 52 warnings；已编译 BaseToolActivity 的 javap 调用顺序断言通过；APK签名证书与0.1.5一致，APK结构及官方16KiB ZIP对齐校验通过。ADB没有已连接设备，未验证这台 vivo 的实际启动，不把静态检查说成真机通过。回归检查：tools/startup-selftest/insets_order.py。

以下保留上一版记录（当时尚未获得本次堆栈）。

# 0.1.5-startup 通用启动隔离与诊断

## 用户报告
用户机型iQOO Z10 Turbo+，0.1.4点击图标立即闪退。没有连接该真机、没有原始logcat。尚未定位原崩溃栈，不能断言已彻底修复其具体故障。

## 已核验与排除
0.1.4官方SDK36 zipalign -c -P16 -v4校验成功；全部20个原生库ZIP数据偏移为16KiB对齐。ARM64/x86_64原生库ELF加载段16KiB对齐，32位版本4KiB。初始中央目录extra误用造成的审计误报已撤回。未确认该手机实际ROM/API与内存页大小。
APK classes.dex及classes2至9.dex完整，启动类定义存在classes4，MlKitInitProvider存在classes.dex，不存在DEX编号断档。ZIP条目顺序不代表缺类。
本机模拟器缺虚拟化驱动；软件模式仍offline，未成功启动App，不计安卓运行验收。

## 本次改动
- 移除MLKit自动初始化Provider，不在打开首页前初始化模型SDK。模型功能入口和翻译客户端显式按需初始化；并非删除这些功能。
- ToolRegistry将类加载LinkageError限于具体模块，显示失效原因并写Android日志，不伪装可用。
- MainActivity捕获首页RuntimeException/LinkageError，显示复制启动诊断页，明确这是错误页而非正常首页。公共工具按钮/任务也显示LinkageError，不静默吞错。
- 诊断只包含版本、厂商/机型、Android/API、ABI与异常堆栈，不读取账本/照片/输入内容，无自动上传。当前不能捕获native SIGSEGV/OS强制杀进程或发生在首页之前的其他Provider异常。
- 同applicationId及调试证书，versionCode递增，支持覆盖安装；不要求卸载、不清本地账本/配置/字典。Android8+及四ABI支持保留，无品牌白名单、无root/关闭安全设置要求。

## 证据边界与下一步
构建、签名、Manifest与APK结构证明封装和代码检查，不证明这台iQOO已经启动成功。请先覆盖安装本版；若出现诊断页，复制其中异常定位真正失败点；若仍瞬退，则需要该App的AndroidRuntime/logcat片段，不能用猜测替代。不要为排障卸载或清空应用数据，账本卸载会丢失。
