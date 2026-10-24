# 0.7.0-preview 验证记录

## 实际运行

- Gradle 离线 `:app:assembleDebug :app:lintDebug`：最终 BUILD SUCCESSFUL，48 tasks，25 秒。不是零警告；最终报告没有 error，原有及新增 warning 未全部消除。
- `VoiceSelfTest`：PASS，20 项。覆盖左右贴边、位置 clamp、屏幕变化、DSP 时长与频率、数值范围、取消。
- `AdSkipRulesSelfTest`：PASS，50 项。覆盖严格文本标签、系统/支付包名保护、区域大小、短窗口、去重和暂停。
- adb devices：没有连接设备。因此未做真机 UI/录音/广告/游戏验收。
- 最终 lint 文本报告：0 errors，116 warnings。
- apksigner verify：PASS，APK v2 签名，Android Debug，证书 SHA-256 为 2fd062b98a5a22202e6957949eb7b5794fd32df82c6448b44f7131be949571a5。
- zipalign -c 4：PASS。
- aapt2 badging：cn.xianhua.pocketkit，versionCode 15，versionName 0.7.0-preview，minSdk 26 / targetSdk 36。

## 构建过程中修复

- 修复中断助手留下的非法 RuntimeException 多重捕获。
- 补全开屏模块 supports/limitation 契约、全部目录和 Manifest 注册。
- 补全悬浮服务麦克风/播放前台类型及录音前权限复检，消除 MissingPermission 错误。
- 将悬浮窗收缩改为真正调整窗口宽高；抽出 EdgeDock 做纯 JVM 验证。
- 接入应用排除设置，忽略不属于当前窗口的后台事件，限制可点击父节点范围并释放根节点。

## 不在验证范围

AI 模型与三种目标音色未集成；游戏麦克风注入未实现；实时耳返未实现；自定义 AI 音色导入未实现。算法自测只证明合成信号的基础性质，不证明语音听感。未对其他全部既有功能做完整回归。
