# PocketKit 0.3.0 交付记录

新增C01原生自动化工作台，独立管理脚本；071抢票助手转为通用脚本工厂与同一执行引擎的调用者。保留旧组件和配方数据键，不加入JavaScript兼容运行时。

## 成品

- [安装包](<F:/SPACE/PocketKit/dist/PocketKit-0.3.0.apk>)，versionName 0.3.0 / versionCode 11，Android 8及以上，调试签名。
- [源码](<F:/SPACE/PocketKit/dist/PocketKit-0.3.0-source.zip>)，不含构建缓存、安装包和签名密钥。
- [操作指南](<F:/SPACE/PocketKit/docs/automation-quickstart.md>)；[验收与真机清单](<F:/SPACE/PocketKit/docs/automation-acceptance.md>)。

APK SHA256：230fae721deb7cdd18545d4165a81a9d653d71f08556faa77a4ee098ebea0150；263480562字节。签名证书SHA256：2fd062b98a5a22202e6957949eb7b5794fd32df82c6448b44f7131be949571a5，与0.2.2一致。未实际覆盖安装测试。

## 能力与行为

可视化动作、嵌套条件/循环、等待、变量、匹配后点击、节点文字、打包中英文OCR、独立图像模板匹配、点/滑动/区域/模板采集、草稿及带素材JSON交换。

普通脚本先准备悬浮栏，切到目标应用后点击开始；控制丢失、取消、锁屏、权限撤回等停止后续动作。不按用途维护购买或验证码黑名单，但不绕过系统权限或安全窗口。

## 证据与限制

[最终整包构建](<F:/SPACE/PocketKit/evidence/automation-final-build-030.txt>)、[核心自检](<F:/SPACE/PocketKit/evidence/final-core-check-030.txt>)、[视觉合成测试](<F:/SPACE/PocketKit/evidence/automation-vision-030.txt>)、[抢票转换](<F:/SPACE/PocketKit/evidence/final-factory-check-030.txt>)、[UI静态契约](<F:/SPACE/PocketKit/evidence/final-ui-check-030.txt>)、[签名](<F:/SPACE/PocketKit/evidence/automation-signature-030.txt>)。

应用与测试包构建、lint、JVM检查通过。测试包只构建，Instrumentation没有执行；UI没有真机截图验收；OCR SDK推理与手势未在手机运行。本次无ADB连接设备，不承诺抢票或实际支付成功。

图匹配全屏1080×2400合成测试最终507ms，非手机基准。ZIP16KiB对齐通过不等于逐台设备原生库兼容确认；第三方ML Kit沿用项目已有闭源打包模型，未做网络监听审计。

## 审查纪律

接口与访谈结论保留。四模块并行委派、子代理自检后父复审；最多两轮返修。A/B/C各有第二轮后仍未满足的局部问题，按任务级兜底补齐，不改变后续任务的默认委派方式。[详细复审记录](<F:/SPACE/PocketKit/docs/automation-review.md>)。
