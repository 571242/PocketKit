# 查询数据来源核验与当前实现

## 093 下一步候选（未启用）及105离线来源

360手机卫士公开查询网站：https://cx.shouji.360.cn/ 。其phonearea.php传7位号段实测返回省份、城市、运营商JSON（城市可能空，Unicode存在双重转义）。不是官方开放平台API文档，仍需核验服务许可/允许使用范围与应用同款Http客户端；本轮仅做候选验证，不以网页通道200当作功能接通，不采用来源不明phone.dat库。

GeoNames邮编下载说明：https://download.geonames.org/export/zip/readme.txt 。原文明确数据CC BY 4.0，但页面还链接旧版3.0URL，引用时保留原始说明与许可核对记录。中国数据仅保留以00结尾的粗粒度邮编，不可冒充街道级完整邮编。现已下载完整CN原文件，按明示CC BY4.0及About页确认许可，逐字节不改，接入105的粗粒度离线查询；细节见postal-data-provenance.md。不是全国街道级完整邮编。


## 098 上海垃圾分类离线参考

采用自编常见物品目录，不复制商业数据库。分类事实依据上海市绿化和市容管理局正式行政性文件《上海市生活垃圾分类投放指引（2024版）》，沪绿容〔2024〕278号，2024-08-02发布，2024-08-01施行。

官方原文：https://lhsr.sh.gov.cn/srgl/20240806/40b9feaa-53db-472f-8a6b-2c3cf0465def.html

易混淆物品补充说明由上海市绿化和市容管理局供稿、普陀区政府2023-08-17刊载：https://www.shpt.gov.cn/kpyd-xxcx/20210421/587289.html

本项目自行写名称别名与提示，按公开行政分类事实编排；未采用官方图标、图案、长篇科普叙述或商业数据。每条数据指向对应官方来源。行政性文件不等于所有政府网站作品皆无版权，此处不能作为其他内容的通用开放许可。

范围仅上海口径、有限常见物品、离线不会自动更新；相关名称不是精确结论；未知物品不默认干垃圾。官方2024指引允许区级针对餐盒、奶茶杯、纸塑包装等设专项回收，不能把这些直接归为全国通用固定类别。未核实到完整适用条件的条目暂不收录。

## 开放词典完整离线数据候选（099尚未接通，094/104另接在线查询）

2026-10-13已实读教育部原始授权网，而不是仅看GitHub许可：
https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/index.html

官方当前发布版本：重编2015_20260929，成语2020_20260929。

下载索引：
- https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/dict_reviseddict_download.html
- https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/dict_idiomsdict_download.html

**原始正文许可明确为CC BY-ND 3.0台湾，不是CC BY-SA，也不是Government Open Data License。** 官方允许重制、散布、传输（含商业），禁止改著作，且须遵照各词典使用说明。

机器格式参考：https://raw.githubusercontent.com/g0v/moedict-data/master/README.md
该README自述：教育部对禁止改作的解释针对文字本身，不限制格式转换与后续应用；其格式转换/编排的编辑版权由kcwu以CC0释出，但正文仍属教育部。

下一步须读取官方使用说明及CC条款，验证原始文字可逐条不改保留，再下载完整资料、留SHA256和署名。查询索引可采用原文之外的辅助索引；不能擅自翻译/简化显示正文，也不能把只看仓库CC0当成正文放弃版权。此处指完整离线数据路线：官方下载因Python证书链失败没有下载，未关闭TLS。0.1.2曾接萌典在线方案，0.1.3回归出现超时。当前0.1.4已使用固定commit原文镜像的离线只读SQLite（161194原记录，逐条内容一致），真实long-key近反义字段，无联网fallback；来源/版本与存储限制见offline-lexicon-provenance.md，在线方案仅留历史核验。099仍未启用。
