# 阅读中心：源码与分发说明

## 本版本的选择

项目所有者已明确接受 GPLv3 路线。根目录 LICENSE 采用 GNU GPL v3；原有 Apache 2.0 声明（含原版权行）保存为 LICENSES/Apache-2.0.txt。此前文件中的版权/许可声明和独立第三方材料的条款不变，不能因根目录换了许可证就认定全部材料被重新授权。

规则兼容层参考以下固定源码快照的公开规则语义：

- 仓库：https://github.com/huajideshutiao/legado
- Commit：d0c42f32426d01741dba0122982554f2934209e7
- 重点：AnalyzeRule、AnalyzeByJSoup、AnalyzeByJSonPath、AnalyzeByXPath、AnalyzeUrl、ImportBookSourceViewModel。
- 上游许可文件 Git blob：ef08d94b4bf3cd935a545be060e001f872a3090c；下载的原文已按 Git blob 算法核验并保存在 sources/legado/LICENSE。

这是 Java 兼容子集，不是将整个 Legado APK、Kotlin/KMP 界面或数据库搬进 PocketKit。没有复制、运行网页返回的混淆脚本或商业 APK 代码。不以翻译/改写语言作为规避上游许可的理由。源规则里的站点正文、封面与媒体并不因此获得 GPL 授权。

## 新增运行依赖

| 组件 | 版本 | 项目/许可 |
| --- | --- | --- |
| jsoup | 1.18.3 | https://jsoup.org/ ，MIT |
| Rhino | 1.7.15 | https://github.com/mozilla/rhino ，MPL-2.0 |
| JsonPath | 2.9.0 | https://github.com/json-path/JsonPath ，Apache-2.0 |
| AndroidX Media3 | 1.8.0 | https://github.com/androidx/media ，Apache-2.0 |
| OkHttp | 4.12.0 | https://github.com/square/okhttp ，Apache-2.0 |

Media3 使用系统解码器，不承诺任意编码或 DRM 播放。OkHttp 是原有依赖的版本统一升级，源获取与媒体请求使用独立的受限客户端，不共享私人浏览器的 Cookie。JsonPath 的传递 SLF4J 被排除，继续使用项目已随 SMB 模块提供的 slf4j-api 2.0.13，避免重复类。

GPL/原 Apache 许可文本也随 APK 保存在 assets/reading/licenses/；阅读组件及依赖附加声明见 assets/reading/component-licenses.txt 和 assets/reading/notices.txt。

## 公开分发边界

源码快照、构建脚本、修改记录、上游标识和许可证是对应源码交付的一部分；不能只给一个当前 GitHub 主分支链接就声称完整履行义务。若公开分发整包，需要核实所有链接组件的兼容条款，并提供与该二进制完全对应的源代码、必要构建输入和声明。

现有应用仍集成 ML Kit 等适用独立条款的 SDK，代理核心 libbox 是另附的大型原生库；本次不进行它们的完整来源/公开组合分发法律审查，也不把不可获得的闭源 SDK 源码伪称为已提供。已有功能不为阅读模块被静默删除。当前构建用于项目所有者的个人使用与测试，不自动上传公共 Release；公开再分发前必须解决这些独立条件。

## 数据与隐私边界

服务器只发布书源/视频接口定义及检测元数据，不托管小说正文、不代理媒体流、不绕付费、登录或验证码。用户自加订阅的可用性、授权和规则执行能力需分别判断。HTTP 成功、脚本被静态接受或一次章节校验，不等于永远可用或已获内容转载许可。
