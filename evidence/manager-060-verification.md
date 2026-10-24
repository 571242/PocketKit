# PocketKit 0.6.0 管理器离线验收

## 结论与边界

已生成可安装开发测试包；离线门禁通过，不等于真机验收通过。没有物理安卓设备连接（adb devices -l 为空）；没有执行模拟器或云盘/Termux 实际账号操作，没有运行 UI 自动化。不会声明所有云服务、所有提供器、所有文件都完整支持。

## 已执行检查

| 检查 | 实际结果 |
| --- | --- |
| Gradle --offline :app:assembleDebug :app:lintDebug :app:testDebugUnitTest | 最终退出 0，BUILD SUCCESSFUL |
| Lint | 0 errors, 108 warnings；不是零警告。没有关闭全局 lint，只有对应用提取所需 QUERY_ALL_PACKAGES 的功能性理由做局部声明 |
| 独立 JVM ManagerAlgorithmsTest.main | 291 assertions PASS；流式复制、取消、MD5/SHA-1/SHA-256、UTF-8、Diff 还原、Hex、ZIP 读取及路径约束 |
| AutoclickerUiContractTest.main | PASS；保留原连点器导航与编辑/运行接线契约 |
| release-coverage.py | compiled-reflection：catalog=97、supported=97、errors=0；仅证明 supports/limitation 可加载，不证明各项真机可用 |
| release-verify.py | 0.6.0/code14；目录、APK 与源码资产一致、ABI/压缩包/16KiB 对齐检查 PASS |
| verify-manager-release.py | 管理器四个类在 DEX、六个快捷入口、VIEW/SEND/SEND_MULTIPLE、私有分享提供器及首页导航契约 PASS |
| apksigner verify --verbose --print-certs | Verifies；APK v2 签名通过；一个 Android Debug 签名者 |
| zipalign -c -P 16 4 | 退出 0 |
| 0.5.0 旧 APK 证书核对 | 与本版证书 SHA-256 相同 |
| 大文件归档 | ops_batch 预检后复制；done=1 failed=0 unknown=0 |

Gradle 的 testDebugUnitTest 执行成功不代表运行了上述 main 方法；291 项断言和首页契约已另外通过 javac/java 显式执行。

## 产物

- 安装包：[PocketKit-0.6.0.apk](../dist/PocketKit-0.6.0.apk)
- 安装包长度：200863875 bytes。
- APK SHA-256：1b547ba8d6047ae521a6ff69ff416b79d76eb2e8bac7d7af5bea9a38eb11cd6b。
- 证书 SHA-256：2fd062b98a5a22202e6957949eb7b5794fd32df82c6448b44f7131be949571a5。
- [反射覆盖证据](manager-060-coverage.json)、[使用说明](../docs/manager-release-060.md)。

## 范围决策

采用现有 Java 17 / 原生 Activity 架构和配色，无新增运行依赖；独立实现，不搬运 MT 的代码、SO 或素材。

云盘/Termux 走 Android 文档提供器，而非新增服务商密码管理或内置 WebDAV/SFTP/SMB 客户端。需要对应提供器已经安装并登录，最终权限受其接口与 Android 沙箱限制。

导入外部 URI 默认不修改原件；对外打开仅共享临时只读副本。复制默认不覆盖同名目标；跨来源移动在删除原件前做内容校验，但不宣称分布式原子事务或可以抵御另一应用同时修改源目录。ZIP 是只读浏览/提取，不提供压缩包内写回或 Level 2–4 功能。

## 真机待验

首页置顶与最近的视觉顺序；窄屏/大字体/横竖屏；SAF 授权的保存、撤销和只读来源；本地↔Termux、云盘↔本地目录复制及移动；断网/进程终止；系统打开/分享选择器；已安装应用 base/split 导出及图标；第三方应用打开只读副本。

请先用可删除测试目录验证，再使用重要文件。此版本只满足开发交付，不应描述为 MT 全功能复刻或已在真机全面验收。
