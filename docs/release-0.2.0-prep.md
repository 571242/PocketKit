# PocketKit 0.2.0 发布准备（阶段 A）

状态：已准备，未开始最终构建。

- 版本：`versionName 0.2.0`、`versionCode 8`，包名与签名配置未改。
- 启动校验：`tools/verify-startup-apk.py` 已更新为检查 0.2.0/code8，并要求 DEX 含 `MlKitBootstrap`；仍保留 Provider、连续 DEX、四 ABI 与 16KiB ZIP 对齐检查。
- 发布校验：`tools/release-verify.py` 只接受最终 APK、合并 Manifest、目录 JSON 与 compiled-reflection coverage；会动态核对目录 ID、版本、DEX、MLKit 类、ABI、16KiB 对齐和归档禁入项。
- 目录支持数必须来自最终编译 classes 的反射结果；未支持入口不得写入发布 catalog，coverage 缺失或不一致即失败。
- 最终证据 `evidence/release-info.json`、`evidence/compiled-coverage.json`、新 APK/source ZIP/SHA256SUMS 仅在父代理明确开始最终构建后生成。
- 当前未运行 Gradle、未复制旧 APK、未写最终证据；无真机验收结论。
- 用户授权全盘范围、MLKit 按需初始化、每日摘要 RSS 来源等说明由最终交付记录按实际实现写入，不在阶段 A 虚报。

## 阶段 B 入口

收到父代理明确“开始最终构建打包”信号后，执行 assembleDebug/lintDebug、启动顺序/目录合同/业务专项检查、签名与版本校验、16KiB 对齐、source archive 排除私钥/APK/build/.scratch，并动态生成 SHA-256 与 UTF-8 证据。
