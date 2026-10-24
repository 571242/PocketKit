# PocketKit 开发接口与文件边界

用户已授权开发；范围以 [feature-selection.json](<F:/SPACE/PocketKit/docs/feature-selection.json>) 为准。基础89候选，新增7组，补强5项不能简单相加为独立工具数。用户界面跟随系统，中文，不使用商业APK源码或素材。当前无真机、没有可用硬件加速模拟器，不得宣称已运行验证。

## 技术路线
- 原生Java Activity + View，AGP8.13.0、compileSdk/targetSdk36、minSdk26、Java17。
- JDK F:/mlbuild/tools/jdk-17.0.20.1+1，SDK F:/mlbuild/sdk，Gradle F:/mlbuild/tools/gradle-8.14.3/bin/gradle.bat。仅单次进程设置环境，不改全局。
- 父会话拥有 app/build.gradle、Manifest、主界面、公共基类与资产注册表；子代理只提交自己的模块文件，不修改这些公共文件。第三方依赖需要写各自交接文档并通知父会话集成。
- 新依赖需可信来源与许可证。Google ML Kit是闭源SDK，不能称全开源或零通信；使用打包中文模型保证识别模型可离线运行，另核实SDK数据披露。
- 文件选择/保存用 ACTION_OPEN_DOCUMENT、ACTION_CREATE_DOCUMENT、SAF。不能偷偷读取全盘或上传图片。

## 入口合同
所有Activity在 cn.xianhua.pocketkit 包根目录，内部辅助类可用自己的子包。
BaseToolActivity提供 protected String toolId；从Intent的tool_id读取，例如004、A01。title字段读取中文页面标题。公共方法：field(label,hint)返回EditText；select(label,String...)返回Spinner；button(label,Runnable)返回Button；note(text)返回TextView；show(text)显示可选中结果；fail(Throwable)显示原因；background(Work)运行工作线程，Work.run() throws Exception；protected LinearLayout body；protected ExecutorService workers。
每个模块 public static boolean supports(String id) 只对真正有实现的入口返回true；public static String limitation(String id) 返回未实现原因或已实现限制（可为空），主界面用它明确显示状态。不得给未实现入口返回true。各工具至少有可重复的核心算法测试或Android编译检查。

## 文件所有权与编号
- 父会话：MainActivity、BaseToolActivity、ToolRegistry、Manifest、Gradle与注册资产。
- 文字生活子代理：LocalToolsActivity、local子包与相关core测试；基础002 003 035 036 037 038 039 040 042 043 044 046 048 049 050 051 052 053 054 056 057 059 060 061 062 063 064 065 066，以及B02 B05。
- 媒体子代理：MediaToolsActivity及media子包、docs/media-implementation.md和媒体测试。基础004 005 007 008 009 010 011 012 013 014 015 018 019 020 022 023 024 025 026 027 029 030 031 032 033 034 080；新增A01 A02 A03 A04 A08；补强D01-D05落到对应基础工具，不重复建入口。
- 设备子代理：DeviceToolsActivity及device子包、docs/device-implementation.md和设备测试。基础067 068 069 070 072 073 074 075 076 077 078 079 081 083 084 086 087 088 089 093 094 095 096 097 098 099 104 105 106 110 111 112 113。

## 实现与报告纪律
先读相关文件和技能（ponytail、frontend-design如适用），复用现有代码，优先系统接口。公网接口核对现行官方/公开说明与用户动作授权；失败明确说明，不编造数据，不以泛搜索页代替功能后声称已实现。文件清理默认仅预览，真正删除要用户明确动作；FTP默认停止、用户显式启动、限定分享目录、随机会话凭证、防止路径穿越，明示明文风险。
OCR、传感器等当前仅可编译与算法自检，未实机验证必须报告。复杂AI效果若无法找到可信依赖，不要假效果；给出具体待解决路径并保持supports=false。
临时脚本/探针仅项目.scratch，各自目录独立；主会话统一收尾清理。不要删除别人的文件，不改DSH配置，不启动替代Web服务器。
