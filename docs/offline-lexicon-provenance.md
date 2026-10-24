# 094/104 离线原文词典：来源、版本与存储

## 固定原始镜像

https://github.com/g0v/moedict-data
固定commit：a6dc997417507eb510fc29822bc514de2c92728c。
原文件：dict-revised.json.xz，Git blob 4f457b60ff75dc8813ce87d68ee41fbc49f21b4a，压缩SHA256 5cc4ec0efd7e549621edf9b46d261989230c1729d9dc2c8e2056f4b21c8a93da。
解压原JSON：80300977字节、161194记录，SHA256 ad43e8e2d30341f2c2458e04fb05a3fc1b1cd486fb52eb8708d89bcfbe46530f。
不采用dict-revised-translated.json.xz，不混入额外外语资料。
原压缩数据与README位于sources/moe，随源代码包分发。

## 原权利与版次

中華民國教育部（Ministry of Education, R.O.C.）。《重編國語辭典修訂本》。
原作者教育部國語推行委員會，企劃執行國家教育研究院，發行所中華民國教育部；維護國家教育研究院語文教育及編譯研究中心。原镜像README署名全文随app关于页显示。
镜像声明：中華民國110年11月臺灣學術網路第六版；此为镜像自身版次声明，不宣称最新官方下载版或现行大陆规范字典。

正文：CC BY-ND 3.0台湾，https://creativecommons.org/licenses/by-nd/3.0/tw/ 。官方授权 https://language.moe.gov.tw/001/Upload/Files/site_content/M0001/respub/index.html 。格式转换/编排kcwu以CC0释出；仓库g0v.json另有MIT元数据，但不能覆盖教育部正文。
CC第3条及官方说明允许不更改词条文字的必要格式转换。辅助查询可以对用户输入简转繁，结果正文不得转简体、翻译或改写。

## 全记录保真与索引

tools/build-offline-lexicon.py验证原Git blob及SHA，将所有原JSON对象完整存入SQLite payload，title建BINARY索引，无词条新增删减。全部161194记录逐条反解析与原对象相等。
SQLite：73863168字节（约70.45MiB）；SHA256 52bdde7e1015ccfada09822edb0fcfa90f65474a66019895c47e3159093cd5dc。gzip压缩资产moe-revised.pack约23.80MiB（避开AAPT对.gz的透明解压改名），元数据与字节哈希随evidence/offline-lexicon-source.json。
首次查询在应用私有目录解压并校验，需存储与临时空间；已安装同版本复用，库破损、缺元数据、空间不足不静默跳过；无联网回退，不自动更新。
参数化精确词目查询，不把未收录词猜成答案。一条词目保留多原读音/义组，同反义仅原synonyms/antonyms。原字形、部首、笔画、音读、词性、释义、书证、例句、参见、未知原字段均保留。

## 证据边界

Python验证gzip/SQLite哈希、integrity_check、全记录保真、实际春/美麗/快樂/行词目与未收录/SQL注入边界。Java显示层用原库样本校验所有原文字符串。安装发布逻辑另自检。
没有连接安卓设备，Android SQLite首次安装、低存储行为、界面性能仍未真机验收。完整原版功能等价也不由入口计数证明。
