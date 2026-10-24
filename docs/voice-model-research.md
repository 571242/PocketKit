# PocketKit 原生 Android 离线 AI 语音转换调研

> 范围：**speech-to-speech / voice conversion（VC）**，即输入一段已有语音，保留其语言内容和大体节奏，输出另一种音色。
> 本文不把 ASR→TTS、普通 TTS、音高/均衡器特效冒充 VC。
>
> 调研结论基于上游仓库、上游模型仓库、官方产品页和官方运行时文档；文件大小为来源接口返回的实际字节数。尚未在 PocketKit 真机执行模型，因此性能结论均标为“需实测”。

---

## 1. 结论先行

### 1.1 可做与不可做

**当前最小可实现方案：OpenVoice V2 的 `ToneColorConverter`，限定为 PocketKit App 内“录音/导入音频 → 离线转换 → 试听/导出”。**

- OpenVoice 的上游 `ToneColorConverter.convert()` 确实直接接收源语音频谱、源说话人 embedding 和目标说话人 embedding，输出新波形；这部分是 S2S，不需要运行 TTS。
- 三种内置音色不需要三套 131 MB 模型。可共用一套 converter，把“清脆少女 / 清冷御姐 / 成熟男”做成三份经过授权的参考录音，并在发布前预计算为三个 `[1,256,1]` 目标 embedding。
- MyShell 官方只发布 PyTorch `.pth`，**没有官方 ONNX 文件或官方 Android 样例**。PocketKit 若要上线，必须固定上游 commit/checkpoint，自行导出 ONNX、保存导出脚本并做 PyTorch/ONNX 一致性与真机性能验收。
- 社区已有完整的 OpenVoice V2 ONNX 导出，证明接口可导、可由 ONNX Runtime 执行，可用于技术验证；但它不是 MyShell 官方资产，而且未附导出脚本和精确上游 checkpoint revision，不能未经复现就作为正式供应链资产。

**当前不能承诺普通 Android App 向 Discord、游戏、电话等其他 App 提供系统级实时“虚拟麦克风”。** Android 公共 API 允许本 App 采集麦克风、播放处理后的音频，也允许在特定授权条件下捕获其他 App 的播放音频，但没有普通第三方 App 可注册的全局虚拟麦克风输出。竞品自己的官方说明也印证了这个边界。

### 1.2 路线判定

| 路线 | 真 S2S | 官方可下载权重 | 官方 ONNX / 张量签名 | 原生 Java Android 判定 | 结论 |
| --- | --- | --- | --- | --- | --- |
| sherpa-onnx | **没有 VC 实现** | 大量 ASR/TTS/增强等模型，但无 VC 模型 | 无 VC API/模型 | Android 框架成熟，但不能凭空运行不支持的 VC | **排除为 VC 引擎** |
| OpenVoice V2 converter-only | 是 | `converter/checkpoint.pth`，131,320,490 B | 官方无 ONNX；上游 PyTorch 接口明确，社区导出签名完整 | ONNX Runtime Java 可接；离线文件转换可做，性能需真机测 | **首选 POC；自行重导后才可上线** |
| Seed-VC v1 realtime | 是 | 主权重 142,112,203 B，另需 XLSR/HIFT/说话人组件 | 官方无 ONNX | 依赖约 1.6 GB 级资产和扩散迭代；官方强烈建议实时使用 GPU | **不适合当前 Android 包内置** |
| FreeVC | 是 | generator 472,644,351 B；还需 WavLM-Large 和 speaker encoder | 官方无 ONNX | 仅已确认依赖就约 1.75 GB；上游推理代码直接 `.cuda()` | **不适合当前 Android 包内置** |

---

## 2. sherpa-onnx：Android 很成熟，但没有 voice conversion

[sherpa-onnx 官方仓库](https://github.com/k2-fsa/sherpa-onnx)列出的能力是 speech-to-text、text-to-speech、speaker diarization、speech enhancement、source separation、VAD 等。其[官方 Android 预构建 APK 清单](https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html)逐项列出：流式/非流式 ASR、TTS、VAD、音频标签、说话人识别、语言识别和关键词检测；没有 voice conversion。

因此：

1. sherpa-onnx 不能直接加载 FreeVC、Seed-VC 或 OpenVoice 模型；它不是任意 ONNX 图的通用 Java 包装器，而是为已实现的任务和模型族提供专用 API。
2. sherpa-onnx 的 TTS 不能替代 S2S。把输入语音先识别成文字再合成会丢失原节奏、语气、停顿和非语言声学细节，不满足本功能定义。
3. 可以借鉴其 Android 构建、音频 I/O、模型解包方式，但 VC 推理应直接用 ONNX Runtime Android，或在 sherpa-onnx 上游真正新增一类 VC API；后者工作量明显更大。
4. sherpa-onnx 自身为 [Apache-2.0](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE)，许可友好，但这不替它尚未实现的 VC 模型背书。

**明确阻碍：不存在可从 sherpa-onnx 官方下载并直接用于上述三种音色的 VC ONNX 权重，也不存在官方 VC 输入/输出签名。**

---

## 3. OpenVoice V2：唯一值得先做 Android POC 的路线

### 3.1 官方权重、许可和实际用途

- 上游：[myshell-ai/OpenVoice](https://github.com/myshell-ai/OpenVoice)
- 官方模型仓库：[myshell-ai/OpenVoiceV2](https://huggingface.co/myshell-ai/OpenVoiceV2)
- 官方使用文档：[docs/USAGE.md](https://raw.githubusercontent.com/myshell-ai/OpenVoice/main/docs/USAGE.md)
- 许可：[MIT](https://raw.githubusercontent.com/myshell-ai/OpenVoice/main/LICENSE)；README 明确 V1/V2 均可商业和研究使用。

官方 Hugging Face 文件树显示：

| 文件 | 实际大小 | 说明 |
| --- | ---: | --- |
| `converter/checkpoint.pth` | 131,320,490 B（约 125.24 MiB） | tone-color converter 主权重 |
| `converter/config.json` | 838 B | 采样率、FFT 和网络配置 |
| `base_speakers/ses/*.pth` | 每个约 1.7 KB | 官方基座 TTS 说话人 embedding；不是“少女/御姐/成熟男”成品角色 |
| 官方模型仓库总显示 | 131 MB | 不含 MeloTTS；converter-only 不需要 MeloTTS |

官方演示常采用“MeloTTS 先合成基座语音，再由 OpenVoice 换音色”，但 [OpenVoice `api.py`](https://raw.githubusercontent.com/myshell-ai/OpenVoice/main/openvoice/api.py) 中的 converter 本身是独立的：

- `extract_se(ref_wav)`：参考音频 → speaker embedding；
- `convert(audio_src_path, src_se, tgt_se, tau=0.3)`：已有源语音 + 源/目标 embedding → 转换后的波形。

因此 PocketKit 只用 converter 时仍是**真实 audio-to-audio / speech-to-speech**；运行链无需文本，也无需 TTS。

### 3.2 官方 PyTorch 计算接口

上游 `SynthesizerTrn.voice_conversion()` 的实际计算接口为：

| 方向 | 张量 | 类型/形状 | 含义 |
| --- | --- | --- | --- |
| 输入 | `y` | float32 `[B, 513, frames]` | 22,050 Hz 源语音的线性幅度频谱 |
| 输入 | `y_lengths` | int64 `[B]` | 频谱帧数 |
| 输入 | `sid_src` | float32 `[B, 256, 1]` | 源说话人 embedding |
| 输入 | `sid_tgt` | float32 `[B, 256, 1]` | 目标说话人 embedding |
| 输入 | `tau` | float scalar | 潜变量扰动强度；公开 API 默认 0.3 |
| 输出 | `o_hat` | float32 `[B, 1, samples]` | 22,050 Hz 单声道波形 |

参考 embedding 的上游接口实际接收 `[B, frames, 513]` 频谱并产出 256 维向量。预处理来自上游 `spectrogram_torch`：`n_fft=1024`、`hop=256`、`win=1024`、periodic Hann、`center=false`、左右 reflect pad 各 384 samples、513 个单边频点、线性幅度谱而非 mel/log 谱。

注意：这张表是**上游 PyTorch 计算接口**，不是“官方 ONNX 节点名”。官方没有发布 ONNX，所以不存在可宣称的官方 ONNX 节点名、opset 或动态轴契约。

### 3.3 社区 ONNX：可用于 POC，但不能冒充官方发布

[Hinotsuba/OpenVoice-ONNX-v2](https://huggingface.co/Hinotsuba/OpenVoice-ONNX-v2) 是第三方转换产物，其文件和接口足够具体：

| 文件 | 大小 | 输入 | 输出 |
| --- | ---: | --- | --- |
| `tone_extract.onnx` | 3,364,792 B（约 3.21 MiB） | `input` float32 `[1,frames,513]` | `tone_embedding` float32 `[1,256]` |
| `tone_color.onnx` | 157,196,170 B（约 149.91 MiB） | `audio` `[1,513,frames]`; `audio_length` int64 `[1]`; `src_tone`/`dest_tone` `[1,256,1]`; `tau` `[1]` | `converted_audio` float32 `[1,1,length]`, 22,050 Hz |

模型卡还声明：converter 内固定噪声张量为 `[1,192,32768]`，单次最长约 380 秒；`tone_extract` opset 11，`tone_color` opset 17，并在 ONNX Runtime CPU 上验证。它也坦白说明**没有附导出脚本和精确上游 checkpoint revision**。这正是正式供应链不能直接采用它的原因。

正确用法：

- 把该资产仅用于验证 Java 音频预处理、ORT 调用、内存和延迟；
- 正式发布前从官方 `checkpoint.pth` 自行导出相同接口；
- 固定上游 commit、checkpoint SHA-256、导出环境/opset、模型 SHA-256；
- 保存自动对比：相同频谱/embedding 下 PyTorch 与 ONNX 波形误差，以及固定样本的可懂度和音色 AB 测试。

### 3.4 三种内置音色的正确建模方式

“清脆少女 / 清冷御姐 / 成熟男”不是 OpenVoice 官方权重自带的离散类别。它们应是 PocketKit 自有的三个目标声音预设：

1. 委托或自行录制三位已明确授权用于模型推理、App 分发和商业使用的干净参考声音；建议每个预设准备多句、共 10–30 秒。
2. 发布前离线跑 `tone_extract`，求多句 embedding 均值并归一化，输出三个约 1 KB 的 float32 embedding；运行时不必捆绑参考原音。
3. 每个 preset 记录来源、授权范围、同意书版本和 embedding SHA-256。MIT 模型许可**不授予参考说话人的人格权、声音权或录音版权**。
4. 产品文案使用虚构风格名，不模仿或暗示具体真人、演员或角色。

这样是“一套通用 VC 模型 + 三个合法目标音色 embedding”，而不是把同一套 150 MB 权重复制三次。

---

## 4. Seed-VC：质量/实时研究价值高，但当前 Android 不现实

### 4.1 官方事实

- 上游：[Plachtaa/seed-vc](https://github.com/Plachtaa/seed-vc)
- 官方模型仓库：[Plachta/Seed-VC](https://huggingface.co/Plachta/Seed-VC)
- 许可：代码和模型卡均标注 [GPL-3.0](https://raw.githubusercontent.com/Plachtaa/seed-vc/main/LICENSE)。在闭源/专有 App 中分发其程序或衍生实现前必须做专项许可证评估。

官方 README 明确支持 zero-shot VC 和实时 VC，参考音频 1–30 秒；实时模式算法延迟约 300 ms、设备侧约 100 ms。这个数字来自 RTX 3060 Laptop GPU 的例子，配置为 10 diffusion steps、0.18 s block、每块推理约 150 ms；官方同时明确 **strongly recommended to use a GPU for real-time voice conversion**。

### 4.2 实际权重和依赖体积

官方模型接口返回的主要文件：

| 文件 | 实际大小 | 用途 |
| --- | ---: | --- |
| `DiT_uvit_tat_xlsr_ema.pth` | 142,112,203 B（约 135.53 MiB） | v1 realtime VC 主模型，25M 参数 |
| `hift.pt` | 81,896,716 B（约 78.10 MiB） | HIFT vocoder |
| `se_db.pt` | 102,405,286 B（约 97.66 MiB） | timbre/speaker 相关数据 |
| `facebook/wav2vec2-xls-r-300m/pytorch_model.bin` | 1,269,737,156 B（约 1.18 GiB） | realtime 配置指定的 XLSR content encoder |

配置还引用 `campplus_cn_common.bin`。仅上表四项就约 **1.60 GB（十进制）**，尚未计 ONNX 转换后的额外文件、运行时和量化前中间内存。官方模型仓库本身显示 3.94 GB（包含多个用途的全部 checkpoint），不能整仓随 APK 打包。

### 4.3 ONNX 与 Android 阻碍

- 官方发布物是 PyTorch `.pth`，没有官方 VC ONNX，也没有官方 Android/Java 推理实现。
- 图不是一个简单 waveform-in/waveform-out 模型，而是 XLSR content encoder、style/speaker encoder、扩散/flow 主干、vocoder 和实时分块/交叉淡化的流水线。
- realtime 配置为 22,050 Hz、80 mel bins、`n_fft=1024`、`hop=256`，主干需要多次 diffusion step；导出必须分别处理动态长度、随机/时间步循环、weight norm 和多子模型。
- NNAPI 对算子和布局有约束，且这类 Transformer/1D 音频图很可能大量回落到 CPU。官方没有移动端内存、RTF、温升或耗电数据。

**判定：不要作为 PocketKit 第一版。** 只有在未来存在上游维护的移动量化模型，或项目愿意投入独立的模型压缩/蒸馏工作并接受 GPL 影响时再评估。

---

## 5. FreeVC：真实 one-shot VC，但体积更不适合移动端

### 5.1 官方事实、许可与实际权重

- 上游：[OlaWod/FreeVC](https://github.com/OlaWod/FreeVC)
- 许可：[MIT](https://raw.githubusercontent.com/OlaWod/FreeVC/main/LICENSE)
- 上游 README 只提供 Python/CUDA 推理；没有官方 ONNX 或 Android 实现。

官方 Hugging Face Space 文件树给出：

| 文件 | 实际大小 | 用途 |
| --- | ---: | --- |
| `checkpoints/freevc.pth` | 472,644,351 B（约 450.75 MiB） | 16 kHz FreeVC generator |
| `checkpoints/freevc-24.pth` | 472,644,351 B | 24 kHz 版本 |
| `checkpoints/freevc-s.pth` | 490,230,998 B（约 467.52 MiB） | spectrogram-target 变体 |
| `speaker_encoder/ckpt/pretrained_bak_5805000.pt` | 17,090,379 B（约 16.30 MiB） | 256 维目标说话人 embedding |
| `microsoft/wavlm-large/pytorch_model.bin` | 1,261,990,257 B（约 1.18 GiB） | 1024 维 content feature extractor |

实际部署 `freevc.pth + speaker encoder + WavLM-Large` 约 **1.75 GB（十进制）**，还未计运行时和内存。WavLM 上游代码为 MIT，但模型和所有转移资产仍应逐项保留其许可证/模型卡。

### 5.2 数据接口和阻碍

上游 `convert.py` 和配置显示实际流水线：

1. 源音频重采样为 16 kHz waveform；
2. WavLM-Large 输出约 `[B,1024,T]` content feature；
3. 目标参考音频经 speaker encoder 得 `[B,256]` embedding（`freevc.pth`），或经 80-bin mel 频谱进入 `freevc-s`；
4. `SynthesizerTrn.infer(content, g=target_embedding)` 输出单声道波形。

这些是 PyTorch 层级接口；**官方没有 ONNX 节点名、动态轴或 opset**。上游 `utils.get_cmodel()` 和 generator 初始化直接调用 `.cuda()`。Intel OpenVINO 官方 notebook证明它可以被拆分和转换以在 CPU 上运行，但这不是 Android ONNX 发布物，也没有移动端性能背书。

**判定：技术上能做桌面 CPU 转换，工程上不适合作为 PocketKit 内置模型。** 它比 OpenVoice converter 大一个数量级，还需要导出和优化三个子图。

---

## 6. 原生 Java Android 具体实现方案

### 6.1 第一阶段：可交付的离线文件转换

建议只做 OpenVoice V2 converter-only，流程如下：

1. `AudioRecord` 录制或 SAF 导入音频；解码为 mono float PCM。
2. 重采样到 22,050 Hz，幅度约束在 `[-1,1]`。
3. 纯 Java 实现与上游一致的 STFT：reflect pad 384，periodic Hann，FFT 1024，hop 256，取 513-bin 线性幅度谱。
4. 从当前源音频中取一段稳定有声区跑 speaker encoder，得到 `src_tone [1,256,1]`；三个内置 `dest_tone` 随包预置。
5. 通过官方 Maven 包 `com.microsoft.onnxruntime:onnxruntime-android` 创建 Java `OrtSession`。ONNX Runtime [Android 安装页](https://onnxruntime.ai/docs/install/#install-on-android)和 [Java API](https://onnxruntime.ai/docs/get-started/with-java.html)均为官方支持。
6. 运行 converter，取 `[1,1,N]` 波形；必要时分成 5–15 秒语音块，块间 50–150 ms equal-power crossfade，并保留上下文，降低接缝。
7. 用 `AudioTrack` 试听，并编码 WAV/可选 AAC 后通过 SAF 导出。

这条链**没有文本和 TTS**。

### 6.2 为什么第一版不是实时跨 App

- 普通 App 可以把处理结果播放到扬声器/耳机，但不能把自定义 PCM 注册成其他 App 可选择的系统麦克风设备。
- Android 的[音频输入共享规则](https://developer.android.com/media/platform/sharing-audio-input)解决的是多个 App 谁能捕获输入、谁收到静音，不是允许一个 App 向另一个 App 注入“虚拟麦”。
- [AudioPlaybackCapture / MediaProjection](https://developer.android.com/media/platform/av-capture)是经用户授权捕获其他 App **播放**的音频，也不是麦克风注入 API。
- 可支持的“实时”只限 PocketKit 自己的监听模式（麦克风 → 模型 → 耳机），并需要耳机防啸叫；对通话/游戏全局生效需要目标 App 集成 SDK、系统/厂商特权、root，或外接 USB 音频硬件。

### 6.3 真机验收门槛

在承诺上线前，至少对一台中端 arm64 和一台高端 arm64 设备记录：

| 项目 | 验收内容 |
| --- | --- |
| 正确性 | PyTorch 与自导 ONNX 对固定输入的 embedding/波形误差；中文、英文、男女交叉转换的可懂度 |
| 性能 | 5/15/30 秒输入的 wall time、RTF、峰值 RSS、模型首次加载时间 |
| 稳定性 | 前后台切换、取消、低内存回收、长音频分块、静音/噪声输入 |
| 音质 | 接缝、爆音、齿音、源音色泄漏、三种 preset 的主观区分度 |
| 热与电 | 连续转换 10 分钟后的温升、降频、耗电 |
| 兼容性 | Android 8.1+ CPU ORT；NNAPI 仅作可选实验，不把“能创建 EP”当成真的硬件加速 |

ONNX Runtime 的 [NNAPI 文档](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)要求 Android 8.1+，建议 Android 9+，并明确不支持的算子会回落。OpenVoice 含 GRU、1D convolution/transposed convolution 等结构，不能在未 profile 前宣称 NNAPI 全图加速。

### 6.4 上线前必须补齐的资产

1. 从官方 OpenVoice checkpoint 自行生成 `tone_extract.onnx` 与 `tone_color.onnx`；不要直接把第三方模型当官方。
2. 保存 checkpoint、导出脚本、opset、上游 commit、SHA-256 和许可证全文。
3. 对模型做一次安全扫描；正式包优先使用 ONNX 而非可执行 pickle/PyTorch checkpoint。
4. 签订并归档三位目标声音的录制/模型化/商业分发授权；只发布 embedding 也不免除声音权审查。
5. UI 明示“仅处理本 App 内录音或导入文件；不能作为系统虚拟麦用于其他 App”。

---

## 7. MagicMic / Voicemod Android 核查

### 7.1 MagicMic

[iMyFone MagicMic 官方移动页](https://filme.imyfone.com/voice-changer-app/)的营销标题会写“instantly”，但同页更明确地写着：

- “Real-time voice changing is only supported in desktop version”；
- Android/iOS 支持 live recording（边录边处理）和上传 WAV/MP3 后变声；
- [官方技术规格页](https://filme.imyfone.com/voice-changer/reference/)再次写明：**Android and iOS system only support to recording and audio voice changing**。

所以 MagicMic Android 不是 Discord/游戏可选的通用实时虚拟麦，实际范围与本文建议的“App 内录音/文件变声”相近。

官方 Android 入口从 iMyFone 页面跳转 Google Play，包名为 `com.imyfone.magicmic`。官网提供的直链安装包是 Windows `.exe`，未发现 iMyFone 官方域名提供 Android `.apk`。第三方 APK 站的 MagicMic 文件不是官方来源，不应纳入下载或更新链。

### 7.2 Voicemod

[Voicemod 官方移动页](https://www.voicemod.net/en/mobile-app/)区分得很清楚：

- 普通移动 App：录制并分享变声音频、soundboard、遥控桌面 Voicemod；
- 手机/主机实时变声：官方页面要求 **VMKey Pocket** 外接硬件；
- [官方移动下载页](https://www.voicemod.net/en/download-mobile/)同样强调控制桌面端和 VM Key。

Voicemod 曾在 2023 年发布 [VMgram](https://www.voicemod.com/latest-press-releases/voicemod-vmgram)：它是基于 Telegram 开源代码制作的专用 Android 客户端，可在**这个 Telegram 分支内部**的通话/消息中实时变声，包名 `net.voicemod.telegram`。这证明“目标通信 App 内集成 SDK”可行，但不代表可向任意 App 提供系统虚拟麦。

普通 Voicemod Android 商店包名为 `net.voicemod.soundboard`。官方网页的移动下载入口导向应用商店/智能链接；未发现 Voicemod 官方域名提供裸 APK。APKPure、APKMirror 等第三方镜像均不能称为官方 APK 来源。

### 7.3 官方来源结论

| 产品 | Android 官方来源 | 实际移动功能 | 能否作为任意 App 实时虚拟麦 |
| --- | --- | --- | --- |
| MagicMic | iMyFone 官网 → Google Play，`com.imyfone.magicmic` | 录音/导入文件变声；实时仅桌面 | 否 |
| Voicemod Mobile | Voicemod 官网 → 商店链接，`net.voicemod.soundboard` | 录制分享、soundboard、桌面遥控 | 否；跨 App 实时需 VMKey Pocket 硬件 |
| VMgram | Voicemod 官方公告 → Google Play，`net.voicemod.telegram` | 仅其 Telegram 分支内实时 | 否，不是系统级 |

---

## 8. 最终决策

### 建议立项

**OpenVoice V2 converter-only 的离线 POC**，目标是证明自导 ONNX 在两档 Android 真机上可接受，而不是先承诺“实时游戏变声”。三种音色用三份自有授权的目标 embedding 实现。

### 明确不立项

- 不用 sherpa-onnx TTS/ASR 串联伪装 S2S；
- 不把 sherpa-onnx 描述为已支持 VC；
- 不在当前包中内置 Seed-VC/FreeVC 的 GB 级 PyTorch 链；
- 不从第三方 APK 站下载 MagicMic/Voicemod；
- 不承诺普通 Android App 能成为其他 App 的系统虚拟麦。

### Go / No-Go 条件

只有同时满足以下条件才进入产品实现：

1. 项目能从 MyShell 官方 MIT checkpoint 可复现地导出 ONNX，且模型/脚本/许可证完整归档；
2. 三个参考声音取得明确、可审计的 App 分发和模型化授权；
3. 代表性中端真机上 15 秒音频不会 OOM，处理速度和音质达到产品可接受线；
4. 产品接受第一版仅为 App 内录音/导入文件的离线转换。

若任一项不满足，应明确标为阻塞，而不是退回 TTS 或简单 pitch-shift 冒充 AI speech-to-speech。
