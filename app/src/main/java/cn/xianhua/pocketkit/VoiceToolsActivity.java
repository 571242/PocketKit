package cn.xianhua.pocketkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import cn.xianhua.pocketkit.local.TtsHelper;
import cn.xianhua.pocketkit.voice.AndroidAudioIo;
import cn.xianhua.pocketkit.voice.DspVoiceProcessor;
import cn.xianhua.pocketkit.voice.VoiceProcessor;
import cn.xianhua.pocketkit.voice.VoiceStorage;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native local audio tools: V01 microphone voice changer and V02 file conversion. */
public class VoiceToolsActivity extends BaseToolActivity {
    private static final int REQUEST_RECORD_AUDIO = 7101;
    private static final int REQUEST_OPEN_AUDIO = 7102;
    private static final int REQUEST_CREATE_WAV = 7103;
    private static final int MAX_RECORD_SECONDS = 60;

    private final AtomicBoolean operationRunning = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean recordingRequested = new AtomicBoolean();
    private final AtomicBoolean playbackRequested = new AtomicBoolean();
    private final VoiceProcessor processor = new DspVoiceProcessor();

    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private AndroidAudioIo.AudioData inputAudio;
    private AndroidAudioIo.AudioData outputAudio;
    private Uri selectedAudio;
    private boolean startAfterPermission;
    private Spinner preset;
    private SeekBar pitch;
    private SeekBar speed;
    private TextView pitchValue;
    private TextView speedValue;
    private Button stopRecording;
    private Button stopPlayback;
    private EditText textInput;
    private TtsHelper ttsHelper;
    private File synthesizedInput;
    private String inputMode;
    private AndroidAudioIo.AudioData recordedAudio;
    private final Map<String, LinearLayout> inputPanels = new LinkedHashMap<>();
    private final Map<String, TextView> inputTabs = new LinkedHashMap<>();
    private Button convertButton;

    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "声音工作台");
        super.onCreate(state);
        if (!supports(toolId)) {
            note("这个工具暂时不可用。");
            show(limitation(toolId));
            return;
        }
        note("文字配音、录音和音频文件，在这里选音色、试听与导出。");
        inputMode = state == null ? toolId : state.getString("input_mode", toolId);
        if (!supports(inputMode)) inputMode = "V01";
        buildInputSwitcher();

        preset = select("音色", "原声", "清亮", "低沉", "机器人");
        buildControls();
        convertButton = button("开始变声", this::process);
        LinearLayout actions = new LinearLayout(this);
        Button preview = button("试听结果", this::playOutput);
        Button export = button("导出 WAV", this::chooseExport);
        body.removeView(preview); body.removeView(export);
        for (Button action : new Button[]{preview, export}) {
            action.setTextColor(accent); action.setBackground(ToolUi.ripple(this, surface, dp(15), Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(0, dp(8), action == preview ? dp(8) : 0, 0); actions.addView(action, lp);
        }
        body.addView(actions);
        stopPlayback = button("停止试听", this::stopPlayback);
        stopPlayback.setEnabled(false);
        Button cancel = button("取消当前任务", this::cancelCurrent);
        cancel.setTextColor(muted); cancel.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(15), Color.TRANSPARENT, 0));
        Button help = button("使用说明", () -> new android.app.AlertDialog.Builder(this).setTitle("声音工作台")
                .setMessage("文字先由设备的系统 TTS 合成，需已安装中文语音，再用下方本地 DSP 引擎变声。\n\n录音最长 60 秒，导入音频最长 120 秒。离开页面会停止任务，不提供实时耳返或游戏麦克风注入。\n\n变声全部在本机完成，音调与速度滑块只对本地 DSP 生效。结果另存 WAV，不修改原文件。")
                .setPositiveButton("知道了", null).show());
        help.setTextColor(muted); help.setBackgroundColor(Color.TRANSPARENT);
        refreshInputMode();
        // Upgraded installs may still hold the 160 MB OpenVoice snapshot in app storage.
        File privateDir = getFilesDir();
        workers.execute(() -> VoiceStorage.purgeLegacyModels(privateDir));
    }

    public static boolean supports(String id) {
        return "048".equals(id) || "V01".equals(id) || "V02".equals(id);
    }

    public static String limitation(String id) {
        if ("048".equals(id)) {
            return "先由系统文字转语音引擎合成，再用本地 DSP 变声；与录音、导入入口共用同一套效果。"
                    + "系统 TTS 是否离线及中文语音质量取决于设备。";
        }
        if ("V01".equals(id)) {
            return "页面前台麦克风录音，最长 60 秒；切到后台立即停止。支持本地 DSP 变声、试听与 WAV 另存。"
                    + "不提供实时耳返：未实现可靠的耳机路由门槛，避免扬声器回授啸叫。";
        }
        if ("V02".equals(id)) {
            return "选择系统可解码的音频，转为 8–48 kHz 单声道 PCM 后使用本地 DSP 变声；输入最长 120 秒。"
                    + "超长会明确拒绝；结果另存 WAV，不修改原文件。可用格式取决于设备 MediaCodec。";
        }
        return id == null || id.isEmpty() ? "缺少 tool_id。" : "暂时没有这个音频工具。";
    }

    private void buildInputSwitcher() {
        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(0, dp(8), 0, dp(12)); body.addView(tabs);
        String[] ids = {"048", "V01", "V02"};
        String[] labels = {"文字配音", "录音变声", "导入音频"};
        for (int i = 0; i < ids.length; i++) {
            String mode = ids[i]; TextView tab = new TextView(this); tab.setText(labels[i]); ToolUi.text(tab, muted, 14);
            tab.setGravity(Gravity.CENTER); tab.setMinHeight(dp(52)); tab.setPadding(dp(4), dp(10), dp(4), dp(10)); tab.setFocusable(true);
            tab.setOnClickListener(v -> {
                if (operationRunning.get()) { show("请先停止或取消当前任务，再切换输入方式。"); return; }
                if (!mode.equals(inputMode)) { inputMode = mode; outputAudio = null; refreshInputMode(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(0, 0, i < 2 ? dp(6) : 0, 0); tabs.addView(tab, lp); inputTabs.put(mode, tab);
        }
        LinearLayout page = body;
        for (String mode : ids) {
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(16), dp(10), dp(16), dp(16)); panel.setBackground(ToolUi.fill(surface, dp(20), Color.TRANSPARENT, 0));
            body = panel;
            if ("048".equals(mode)) buildTextInput(); else if ("V01".equals(mode)) buildRecorderInput(); else buildFileInput();
            body = page; page.addView(panel); inputPanels.put(mode, panel);
        }
    }

    private void refreshInputMode() {
        for (Map.Entry<String, LinearLayout> panel : inputPanels.entrySet()) panel.getValue().setVisibility(panel.getKey().equals(inputMode) ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, TextView> entry : inputTabs.entrySet()) {
            boolean selected = entry.getKey().equals(inputMode); TextView tab = entry.getValue(); tab.setSelected(selected);
            tab.setTextColor(selected ? surface : muted); tab.setBackground(ToolUi.ripple(this, selected ? accent : surface, dp(14), Color.TRANSPARENT, 0));
        }
        if (convertButton != null) convertButton.setText("048".equals(inputMode) ? "生成配音" : "开始变声");
    }

    private void buildTextInput() {
        textInput = field("配音文字", "输入想说的话，再选择下方音色");
        textInput.setMinLines(3); textInput.setGravity(Gravity.TOP);
        ttsHelper = new TtsHelper(this);
        Button settings = button("系统语音设置", () -> {
            if (operationRunning.get()) { show("请先停止当前任务。"); return; }
            ttsHelper.openSettings();
        });
        settings.setTextColor(accent); settings.setBackgroundColor(Color.TRANSPARENT);
    }

    private void buildRecorderInput() {
        button("开始录音", this::requestRecording);
        stopRecording = button("停止录音", () -> stopRecording(false));
        stopRecording.setEnabled(false);
    }

    private void buildFileInput() {
        button("选择音频文件", () -> {
            if (operationRunning.get()) { show("请先停止当前任务。"); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_OPEN_AUDIO);
        });
    }

    private void buildControls() {
        pitchValue = note("音调：0 半音");
        pitch = new SeekBar(this);
        pitch.setMax(16);
        pitch.setProgress(8);
        pitch.setContentDescription("音调，负八到正八半音");
        pitch.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int semitones = value - 8;
                pitchValue.setText("音调：" + (semitones > 0 ? "+" : "") + semitones + " 半音");
            }
        });
        body.addView(pitch);

        speedValue = note("速度：1.00×");
        speed = new SeekBar(this);
        speed.setMax(50);
        speed.setProgress(25);
        speed.setContentDescription("速度，零点七五到一点二五倍");
        speed.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                speedValue.setText(String.format(java.util.Locale.ROOT, "速度：%.2f×", 0.75f + value / 100f));
            }
        });
        body.addView(speed);
    }

    private void requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        if (!beginOperation("录音")) return;
        try {
            int sampleRate = supportedRecordRate();
            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("设备不支持单声道 PCM 录音");
            int bufferBytes = Math.max(min, sampleRate / 5);
            AudioRecord local = createStartedRecorder(sampleRate, bufferBytes);
            recorder = local;
            recordingRequested.set(true);
            if (stopRecording != null) stopRecording.setEnabled(true);
            show("正在录音…最多 60 秒。离开页面会停止并释放麦克风。");
            workers.execute(() -> recordLoop(local, sampleRate, Math.max(1024, bufferBytes / 2)));
        } catch (SecurityException denied) {
            recordingRequested.set(false);
            releaseRecorder();
            finishOperation();
            fail(new IllegalStateException("没有麦克风权限", denied));
        } catch (RuntimeException failure) {
            recordingRequested.set(false);
            releaseRecorder();
            finishOperation();
            fail(failure);
        }
    }

    private AudioRecord createStartedRecorder(int sampleRate, int bufferBytes) {
        RuntimeException last = null;
        for (int source : new int[]{MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.DEFAULT}) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("麦克风初始化失败");
                }
                candidate.startRecording();
                if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("麦克风未进入录音状态");
                }
                return candidate;
            } catch (SecurityException denied) {
                if (candidate != null) candidate.release();
                throw denied;
            } catch (RuntimeException failure) {
                last = failure;
                if (candidate != null) {
                    try { candidate.release(); } catch (RuntimeException ignored) { }
                }
            }
        }
        throw new IllegalStateException("麦克风无法开始录音，可能正被其他应用占用", last);
    }

    private void recordLoop(AudioRecord local, int sampleRate, int chunkSamples) {
        float[] captured = new float[sampleRate * MAX_RECORD_SECONDS];
        short[] chunk = new short[chunkSamples];
        int size = 0;
        boolean limitReached = false;
        try {
            while (recordingRequested.get() && !cancelled.get() && size < captured.length) {
                int count = local.read(chunk, 0, Math.min(chunk.length, captured.length - size), AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (!recordingRequested.get()) break;
                    throw new IllegalStateException("录音读取失败：" + count);
                }
                for (int i = 0; i < count; i++) captured[size++] = chunk[i] / 32768f;
            }
            limitReached = size == captured.length;
            if (!cancelled.get() && size > 0) {
                recordedAudio = new AndroidAudioIo.AudioData(Arrays.copyOf(captured, size), sampleRate);
                inputAudio = recordedAudio;
                outputAudio = null;
                final int finalSize = size;
                final boolean reached = limitReached;
                runOnUiThread(() -> show((reached ? "已达到 60 秒上限并自动停止。" : "录音已停止。")
                        + String.format(java.util.Locale.ROOT, " 已录制 %.1f 秒，可开始转换。", finalSize / (double)sampleRate)));
            } else if (!cancelled.get()) {
                runOnUiThread(() -> show("没有录到可用音频。"));
            }
        } catch (RuntimeException failure) {
            if (!cancelled.get() && recordingRequested.get()) runOnUiThread(() -> fail(failure));
        } finally {
            recordingRequested.set(false);
            releaseRecorder();
            finishOperation();
            runOnUiThread(() -> { if (stopRecording != null) stopRecording.setEnabled(false); });
        }
    }

    private void stopRecording(boolean discard) {
        if (discard) cancelled.set(true);
        recordingRequested.set(false);
        AudioRecord local = recorder;
        if (local != null) {
            try { local.stop(); } catch (RuntimeException ignored) {}
        }
    }

    private void process() {
        if (operationRunning.get()) { show("已有任务正在进行，请先停止或取消。"); return; }
        if ("048".equals(inputMode) && (textInput == null || textInput.getText().toString().trim().isEmpty())) {
            show("请输入要合成并变声的文字。"); return;
        }
        if ("V01".equals(inputMode) && recordedAudio == null) { show("请先完成一段录音。"); return; }
        if ("V02".equals(inputMode) && selectedAudio == null) { show("请先选择音频文件。"); return; }
        VoiceProcessor.Params params = selectedParams();
        if (!beginOperation("转换")) return;
        outputAudio = null;
        if ("048".equals(inputMode)) {
            synthesizeText(processor, params);
        } else {
            if ("V01".equals(inputMode)) inputAudio = recordedAudio;
            Uri source = "V02".equals(inputMode) ? selectedAudio : null;
            submit(() -> convertInput(processor, params, source, false));
        }
    }

    private void synthesizeText(VoiceProcessor processor, VoiceProcessor.Params params) {
        synthesizedInput = new File(getCacheDir(), "voice-tts-" + System.nanoTime() + ".wav");
        show("正在用系统语音引擎合成文字…");
        try {
            ttsHelper.synthesizeToFile(textInput.getText().toString(), synthesizedInput,
                    new TtsHelper.SynthesisCallback() {
                        @Override public void onDone(File audioFile) {
                            if (cancelled.get()) {
                                audioFile.delete();
                                finishOperation();
                                return;
                            }
                            submit(() -> convertInput(processor, params, Uri.fromFile(audioFile), true));
                        }

                        @Override public void onError(Exception failure) {
                            if (synthesizedInput != null) synthesizedInput.delete();
                            finishOperation();
                            runOnUiThread(() -> {
                                if (cancelled.get()) show("任务已取消。"); else fail(failure);
                            });
                        }
                    });
        } catch (Exception failure) {
            synthesizedInput.delete();
            finishOperation();
            fail(failure);
        }
    }

    private void convertInput(VoiceProcessor processor, VoiceProcessor.Params params, Uri source,
                              boolean deleteSource) throws Exception {
        try {
            AndroidAudioIo.AudioData input = inputAudio;
            if (source != null) {
                show(deleteSource ? "正在解码合成语音…" : "正在解码音频…");
                input = AndroidAudioIo.decode(this, source, cancellation());
                inputAudio = input;
            }
            cancellation().throwIfCancelled();
            show("正在转换…");
            float[] result = processor.process(input.samples, input.sampleRate, params, cancellation());
            cancellation().throwIfCancelled();
            outputAudio = new AndroidAudioIo.AudioData(result, processor.outputSampleRate(input.sampleRate));
            AndroidAudioIo.AudioData finalOutput = outputAudio;
            runOnUiThread(() -> show(String.format(java.util.Locale.ROOT,
                    "转换完成：%.1f 秒，%d Hz 单声道。可试听或导出 WAV。",
                    finalOutput.seconds(), finalOutput.sampleRate)));
        } finally {
            if (deleteSource && synthesizedInput != null) synthesizedInput.delete();
            finishOperation();
        }
    }

    private void playOutput() {
        AndroidAudioIo.AudioData audio = outputAudio;
        if (audio == null) { show("请先完成转换。"); return; }
        if (!beginOperation("试听")) return;
        int min = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { finishOperation(); fail(new IllegalStateException("设备不支持该采样率播放")); return; }
        try {
            AudioTrack local = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, audio.sampleRate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            player = local;
            playbackRequested.set(true);
            stopPlayback.setEnabled(true);
            local.play();
            show("正在试听转换结果…");
            submit(() -> playLoop(local, audio));
        } catch (RuntimeException failure) {
            releasePlayer();
            finishOperation();
            fail(failure);
        }
    }

    private void playLoop(AudioTrack local, AndroidAudioIo.AudioData audio) {
        short[] chunk = new short[4096];
        int position = 0;
        try {
            while (position < audio.samples.length && playbackRequested.get() && !cancelled.get()) {
                int count = Math.min(chunk.length, audio.samples.length - position);
                for (int i = 0; i < count; i++) {
                    float value = Math.max(-1f, Math.min(1f, audio.samples[position + i]));
                    chunk[i] = (short)Math.round(value * (value < 0 ? 32768f : 32767f));
                }
                int written = local.write(chunk, 0, count, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("播放失败：" + written);
                position += written;
            }
            while (position >= audio.samples.length && playbackRequested.get() && !cancelled.get()
                    && local.getPlaybackHeadPosition() < audio.samples.length) {
                Thread.sleep(20);
            }
            if (!cancelled.get() && position >= audio.samples.length) runOnUiThread(() -> show("试听完成。"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            if (!cancelled.get()) runOnUiThread(() -> fail(failure));
        } finally {
            playbackRequested.set(false);
            releasePlayer();
            finishOperation();
            runOnUiThread(() -> { if (stopPlayback != null) stopPlayback.setEnabled(false); });
        }
    }

    private void stopPlayback() {
        playbackRequested.set(false);
        AudioTrack local = player;
        if (local != null) {
            try { local.pause(); local.flush(); } catch (RuntimeException ignored) {}
        }
    }

    private void chooseExport() {
        if (outputAudio == null) { show("请先完成转换。"); return; }
        if (operationRunning.get()) { show("请先停止当前任务。"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/wav");
        intent.putExtra(Intent.EXTRA_TITLE, "PocketKit-voice.wav");
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE_WAV);
    }

    private void export(Uri destination) {
        AndroidAudioIo.AudioData audio = outputAudio;
        if (audio == null || !beginOperation("导出")) return;
        submit(() -> {
            boolean complete = false;
            try {
                AndroidAudioIo.writeWav(getContentResolver(), destination, audio, cancellation());
                cancellation().throwIfCancelled();
                complete = true;
                runOnUiThread(() -> show("WAV 已另存；原文件未修改。"));
            } finally {
                if (!complete) {
                    try { getContentResolver().delete(destination, null, null); } catch (RuntimeException ignored) {}
                }
                finishOperation();
            }
        });
    }

    private boolean beginOperation(String name) {
        if (!operationRunning.compareAndSet(false, true)) {
            show("已有任务正在进行，请先停止或取消。");
            return false;
        }
        cancelled.set(false);
        return true;
    }

    private void finishOperation() {
        operationRunning.set(false);
    }

    private void cancelCurrent() {
        if (!operationRunning.get()) { show("当前没有正在进行的任务。"); return; }
        cancelled.set(true);
        stopRecording(true);
        stopPlayback();
        if (ttsHelper != null) ttsHelper.stop();
        show("已请求取消，正在释放资源…");
    }

    private void submit(Work work) {
        try {
            workers.execute(() -> {
                try { work.run(); }
                catch (VoiceProcessor.CancelledException cancelled) { runOnUiThread(() -> show("任务已取消。")); }
                catch (Exception | LinkageError failure) { runOnUiThread(() -> fail(failure)); }
            });
        } catch (RejectedExecutionException rejected) {
            finishOperation();
            fail(rejected);
        }
    }

    private VoiceProcessor.Params selectedParams() {
        VoiceProcessor.Preset effect;
        switch (preset.getSelectedItemPosition()) {
            case 1: effect = VoiceProcessor.Preset.CLEAR; break;
            case 2: effect = VoiceProcessor.Preset.DEEP; break;
            case 3: effect = VoiceProcessor.Preset.ROBOT; break;
            default: effect = VoiceProcessor.Preset.ORIGINAL;
        }
        return new VoiceProcessor.Params(effect, pitch.getProgress() - 8, 0.75f + speed.getProgress() / 100f);
    }

    private VoiceProcessor.Cancellation cancellation() {
        return cancelled::get;
    }

    private int supportedRecordRate() {
        for (int rate : new int[]{48000, 44100, 32000, 16000}) {
            if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) return rate;
        }
        throw new IllegalStateException("设备没有可用的 PCM 麦克风采样率");
    }

    private synchronized void releaseRecorder() {
        AudioRecord local = recorder;
        recorder = null;
        if (local != null) {
            try { if (local.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) local.stop(); } catch (RuntimeException ignored) {}
            local.release();
        }
    }

    private synchronized void releasePlayer() {
        AudioTrack local = player;
        player = null;
        if (local != null) {
            try { local.stop(); } catch (RuntimeException ignored) {}
            local.release();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && startAfterPermission) startRecording();
        else show("麦克风权限被拒绝，无法录音；你仍可返回，不会创建空结果。");
        startAfterPermission = false;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == REQUEST_OPEN_AUDIO) {
            if (resultCode == RESULT_OK && uri != null) {
                selectedAudio = uri;
                inputAudio = null;
                outputAudio = null;
                show("已选择：" + AndroidAudioIo.displayName(getContentResolver(), uri) + "。原文件只读，不会被修改。");
            } else show("已取消选择，未读取任何文件。");
        } else if (requestCode == REQUEST_CREATE_WAV) {
            if (resultCode == RESULT_OK && uri != null) export(uri);
            else show("已取消保存位置选择，结果仍可试听，未写出文件。");
        }
    }

    @Override protected void onStop() {
        if (operationRunning.get()) {
            cancelled.set(true);
            stopRecording(true);
            stopPlayback();
            if (ttsHelper != null) ttsHelper.stop();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelled.set(true);
        stopRecording(true);
        stopPlayback();
        releaseRecorder();
        releasePlayer();
        if (ttsHelper != null) ttsHelper.shutdown();
        if (synthesizedInput != null) synthesizedInput.delete();
        try { processor.close(); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("input_mode", inputMode);
        super.onSaveInstanceState(state);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
