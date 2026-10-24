# 105 GeoNames中国粗粒度邮编离线参考

完整保留GeoNames CN.zip内CN.txt原字节，文件对应app/src/main/assets/geonames-cn.tsv。不是中国邮政官方街道级完整库。

## 来源与原始数据

下载：https://download.geonames.org/export/zip/CN.zip
原始说明：https://download.geonames.org/export/zip/readme.txt
提供方：https://www.geonames.org/
下载时间：2026-10-13T11:03:32+08:00；HTTP Last-Modified：2026-10-13T02:03:10Z。
目录每日生成日期不是每个地区行政名称或邮编的校订日期。

压缩包42326字节，SHA256：1942c2d1d9f85ae616f8a4a5c50435d6acca701fb5c8644cba7256b455282a3f。
原数据210156字节/2352行，SHA256：ad935b4c99b73a4969313e9c10b28c76735b042414bd6b9ab4e4effe67495127。
ZIP CRC验证通过；保留来源下载记录于evidence/geonames-postal-source.json。

## 许可与范围

GeoNames原始readme明确“Creative Commons Attribution 4.0 License”。其后仍留旧3.0URL；GeoNames官方About页另明确CC BY4.0，故以其明示最新许可为准，并随包保存原始readme，不静默修改该链接。署名GeoNames，链接https://www.geonames.org/，条款https://creativecommons.org/licenses/by/4.0/。

原始readme明确中国数据只保留以00结尾的邮编。数据按原状提供，无准确性、及时性、完整性保证。应用不把粗粒度编码用作具体收件地址结论、不补零猜测、未收录明确提示。不使用估算经纬度定位用户。

## 查询与可验证边界

离线按六位编码精确反查，或按原地名/地区标签文字筛选；一编码允许多行，不能宣称2352个唯一邮编。
地名主要拼音/拉丁写法。中文输入仅ICU Han-Latin作辅助索引；多音字、音译、别名、行政后缀可能不匹配。结果原地名不自动翻译，不生成虚构中文地名。
原数据不改；显示最多30行且报告总匹配数。不自动联网/更新，卸载不影响源说明，具体投递地址以中国邮政现行服务为准。

测试：原字节SHA、全部实际编码、北京/上海/合肥正向与反向、多音/带调拼音归一化、未知与精细邮编、空输入、显示限额、错误资料/BOM/重复编码行/不可变目录。
