# 已选功能的独立实现路径

范围与授权见 [feature-selection.json](<F:/SPACE/PocketKit/docs/feature-selection.json>)。这里记录已核验的技术来源，不表示功能已经全部实现或运行验证。

## 系统接口

原生Java Activity/View，界面跟随系统。文件读写使用Android Storage Access Framework；图像编辑基于Bitmap/Canvas/Matrix；基础PDF绘制与页面渲染可用PdfDocument/PdfRenderer；视频使用MediaExtractor/MediaCodec/MediaMuxer；传感器SensorManager、声音AudioRecord、语音输出TextToSpeech；本地记录用SQLite或应用私有存储。

系统API的权限、版本限制和真实功能以各模块交接和后续验证结果为准。没有连接真机，不能宣称已经完成硬件验收。

## 已确认发布坐标

- PdfBox Android `com.tom-roush:pdfbox-android:2.0.27.0`：PDF页面编辑，Apache-2.0；[官方发布](https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0)、[Maven POM](https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.pom)。
- ZXing `com.google.zxing:core:3.5.3`：二维码编码与条码算法；构建与声明将按真实解析构件记录，许可证文本需随包保留。
- ML Kit打包中文OCR `com.google.mlkit:text-recognition-chinese:16.0.1`：[Android官方使用文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。打包识别模型不代表整个SDK没有诊断通信。
- ML Kit条码、人脸检测、图片标签：分别为 `barcode-scanning:17.3.0`、`face-detection:16.1.7`、`image-labeling:17.0.9`；识别标签不是任意物体鉴定或生成式AI，模型作用范围应如实说明。
- ML Kit适用[条款](https://developers.google.com/ml-kit/terms)与[Android数据披露](https://developers.google.com/ml-kit/android-data-disclosure)：官方说明包含设备、应用、性能及诊断/使用数据收集。不得称整个应用全开源或零通信。
- AndroidX ExifInterface `1.3.7`：照片元数据读写。EXIF清除必须验证导出副本，不擅改原图。
- ICU4J `com.ibm.icu:icu4j:76.1`：拼音等Unicode转换，Unicode-3.0；[官方POM](https://repo.maven.apache.org/maven2/com/ibm/icu/icu4j/76.1/icu4j-76.1.pom)。
- 候选OpenCV `org.opencv:opencv:4.12.0`，Apache-2.0，[官方POM](https://repo.maven.apache.org/maven2/org/opencv/opencv/4.12.0/opencv-4.12.0.pom)。当前未加入构建；仅在扫描等确实需要时加入并验证。

## 构建证据与边界

已验证选中基础编号89项唯一且均存在于静态目录；新增7个功能组，另外5项是已有能力补强，因此主目录96个入口不等于96个独立操作，也不等于已经实现96项。

实现必须逐项声明真实支持；未接通入口不启用。流水线保留实际依赖构件、SHA-256和嵌入声明，后续记录到取证目录。公开联网服务需另核实许可、限流和现行可用性，不使用商业APK内嵌凭据；未知或收费依赖不得伪装完成。
