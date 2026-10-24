# 在线萌典适配的历史核验记录（0.1.2/0.1.3）

当前0.1.4的094/104已改为离线保原文数据库，无联网fallback。当前来源及版次以offline-lexicon-provenance.md为准；以下是在线方案历史核验，不代表当前联网行为。

数据接口：https://www.moedict.tw/a/{UTF-8词语}.json
已用应用相同Http实测春、美麗、快樂，均真实HTTPS 200；JVM测试验证词头、正文以及实际s/a字段。不是只以网页搜索片段宣告可用。

## 原文与署名

中華民國教育部（Ministry of Education, R.O.C.）。《重編國語辭典修訂本》。
https://dict.revised.moe.edu.tw/

官方原始公眾授权页：https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/index.html

原文采用CC BY-ND 3.0台湾：https://creativecommons.org/licenses/by-nd/3.0/tw/
法律原文：https://creativecommons.org/licenses/by-nd/3.0/tw/legalcode.zh-hant
第3条允许行使授权所必要的技术性格式修改，不许可改写原文。

官方额外使用说明：https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/reviseddict_10312.pdf
检索获得官方条款：词目、部首、笔画、字形、音读与释义不得修改或转简化字；不涉及更改个别条目内容的格式转换另行允许。官网另有使用说明页：https://dict.revised.moe.edu.tw/page.jsp?ID=33 。官方下载ODT因本机Python证书链验证失败未能保存全文，未关闭TLS或循环换命令。

格式转换/编排：https://github.com/g0v/moedict-data 。kcwu相关编辑版权CC0不代替教育部正文许可。

**版本限制**：官方授权网最新下载版本为2015_20260929，但在线萌典响应未标明具体快照版本。不得把官方版本号当作镜像版本；应用及关于页明确镜像版本未声明。属于历史语言参考，不当成现行大陆规范字典。

## 保原文显示与查询

t词头、r部首、c总笔画、h[].p拼音、h[].b注音、d[].type词性、f释义、q书证、e例句、s近义、a反义按原文显示。萌典生成的反引号/波浪线链接分隔符仅作技术性显示转换，文字不改写或翻译；外语扩展English/Deutsch/francais/translation不采用。

简体输入可选择ICU简转繁辅助查询，也可原样查询；原文不转简体。存在多义简繁情况时应自行核对词头。104只读真实s/a字段，不在释义文字中猜关系、无数据不编造。

查询词主动提交给moedict.tw。仅在线、未内置完整离线库；服务故障、限流、缺词、格式变化都可能导致失败。并非与原版完整等价。099成语专门目录没有验证，不以四字长度判定成语，因此继续禁用。
