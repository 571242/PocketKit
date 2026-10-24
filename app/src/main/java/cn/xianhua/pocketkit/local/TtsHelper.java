package cn.xianhua.pocketkit.local;

import android.app.Activity;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.xianhua.pocketkit.core.ToolException;

/**
 * 文字转语音（048）的系统引擎封装。
 *
 * 只用系统 TextToSpeech，不内置语音包、不下载模型。因此：
 * - 没有装任何 TTS 引擎时明确报错，并给出打开系统设置的入口；
 * - 装了引擎但没有中文语音时同样明确说明，而不是假装朗读成功；
 * - 是否能离线合成取决于用户的系统引擎（部分引擎需要联网），应用无法保证。
 *
 * 生命周期：Activity 销毁时必须 shutdown，否则会泄漏引擎连接。
 */
public final class TtsHelper {

    private final Activity host;
    private TextToSpeech tts;
    private boolean ready;
    private String initError = "";
    /** 中文语音是否可用：null 表示还没探测。 */
    private Boolean chineseOk;
    private final List<String> lastEvents = new ArrayList<>();
    private String lastSpoken = "";
    private Pending pending;
    private long nextId;

    public interface SynthesisCallback {
        void onDone(File audioFile);
        void onError(Exception failure);
    }

    private static final class Pending {
        final String text, id;
        final File file;
        final SynthesisCallback callback;
        Pending(String text, File file, String id, SynthesisCallback callback) {
            this.text = text; this.file = file; this.id = id; this.callback = callback;
        }
    }

    public TtsHelper(Activity host) {
        this.host = host;
    }

    /** 懒初始化：第一次真正要朗读时才连引擎。 */
    private void ensure() {
        if (tts != null) {
            return;
        }
        tts = new TextToSpeech(host, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ready = true;
                initError = "";
                try {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {
                            record("开始朗读");
                        }

                        @Override public void onDone(String utteranceId) {
                            record("朗读结束");
                            finishSynthesis(utteranceId, null);
                        }

                        @Override public void onError(String utteranceId) {
                            record("引擎报错");
                            finishSynthesis(utteranceId, new ToolException("系统语音引擎合成失败。"));
                        }
                    });
                } catch (RuntimeException e) {
                    record("无法监听朗读进度：" + e.getClass().getSimpleName());
                }
                startPending();
            } else {
                ready = false;
                initError = "系统文字转语音引擎初始化失败。"
                        + "可能是没有安装任何语音引擎，或引擎被停用。";
                finishSynthesis(null, new ToolException(initError));
            }
        });
    }

    /** Synthesizes speech to a private file; the caller may then decode and voice-convert it. */
    public synchronized void synthesizeToFile(String text, File file, SynthesisCallback callback) throws ToolException {
        if (text == null || text.trim().isEmpty()) throw new ToolException("没有要合成的文字。");
        if (file == null || callback == null) throw new IllegalArgumentException("缺少合成目标");
        if (pending != null) throw new ToolException("已有文字正在合成，请稍后再试。");
        pending = new Pending(text, file, "pocketkit-file-" + (++nextId), callback);
        ensure();
        if (ready) startPending();
        else if (!initError.isEmpty()) finishSynthesis(null, new ToolException(initError));
    }

    private synchronized void startPending() {
        Pending job = pending;
        if (job == null || !ready || tts == null) return;
        int language = tts.setLanguage(Locale.CHINA);
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            chineseOk = false;
            finishSynthesis(job.id, new ToolException("当前语音引擎没有中文（zh-CN）语音。"));
            return;
        }
        chineseOk = true;
        File parent = job.file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            finishSynthesis(job.id, new ToolException("无法创建语音缓存目录。"));
            return;
        }
        if (job.file.exists() && !job.file.delete()) {
            finishSynthesis(job.id, new ToolException("无法替换语音缓存文件。"));
            return;
        }
        lastSpoken = job.text;
        int result = tts.synthesizeToFile(job.text, null, job.file, job.id);
        if (result == TextToSpeech.ERROR) {
            finishSynthesis(job.id, new ToolException("系统语音引擎拒绝合成这段文字。"));
        }
    }

    private void finishSynthesis(String utteranceId, Exception failure) {
        Pending job;
        synchronized (this) {
            if (pending == null || (utteranceId != null && !pending.id.equals(utteranceId))) return;
            job = pending;
            pending = null;
        }
        if (failure == null) job.callback.onDone(job.file); else job.callback.onError(failure);
    }

    public void speak(String text) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有要朗读的文字。请输入内容再点朗读。");
        }
        ensure();
        if (tts == null || !ready) {
            throw new ToolException(initError.isEmpty()
                    ? "语音引擎还没准备好。请稍后重试，或用「检查语音可用性」。"
                    : initError + " 可以点「打开系统语音设置」安装或启用一个引擎。");
        }
        int set = tts.setLanguage(Locale.CHINA);
        if (set == TextToSpeech.LANG_MISSING_DATA || set == TextToSpeech.LANG_NOT_SUPPORTED) {
            chineseOk = false;
            throw new ToolException("当前语音引擎没有中文（zh-CN）语音，无法正确朗读中文。"
                    + "点「打开系统语音设置」安装中文语音数据后再试。");
        }
        chineseOk = true;
        lastSpoken = text;
        // 明确的错误码检查：speak 返回 ERROR 时不能当作已朗读
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocketkit-utt");
        if (r == TextToSpeech.ERROR) {
            throw new ToolException("引擎拒绝朗读这段文字（speak 返回 ERROR）。"
                    + "文本可能过长，或引擎当前不可用。");
        }
    }

    public void stop() {
        finishSynthesis(null, new ToolException("语音合成已取消。"));
        if (tts != null) {
            tts.stop();
            record("已停止");
        }
    }

    /** 探测引擎与中文语音的可用性，结果通过 describe() 展示。 */
    void probe() {
        ensure();
        if (tts == null || !ready) {
            chineseOk = null;
            return;
        }
        int set = tts.setLanguage(Locale.CHINA);
        chineseOk = set != TextToSpeech.LANG_MISSING_DATA
                && set != TextToSpeech.LANG_NOT_SUPPORTED;
    }

    /** 打开系统的语音设置，让用户自己安装引擎或语音数据。 */
    public void openSettings() {
        try {
            Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(i);
            record("已打开系统语音数据安装页");
        } catch (RuntimeException e) {
            try {
                host.startActivity(new Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                record("已打开系统语音设置");
            } catch (RuntimeException e2) {
                record("这台设备没有提供语音设置入口，请在系统设置里搜索「文字转语音」");
            }
        }
    }

    private void record(String e) {
        lastEvents.add(e);
        while (lastEvents.size() > 6) {
            lastEvents.remove(0);
        }
    }

    /** 给用户看的真实状态，不含任何猜测。 */
    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("引擎状态：");
        if (tts == null) {
            sb.append("尚未初始化（还没点过朗读）");
        } else if (ready) {
            sb.append("已就绪");
        } else {
            sb.append("初始化失败");
            if (!initError.isEmpty()) {
                sb.append("（").append(initError).append("）");
            }
        }
        sb.append('\n');
        sb.append("中文语音：");
        if (chineseOk == null) {
            sb.append("未探测，点「检查语音可用性」");
        } else {
            sb.append(chineseOk ? "可用" : "不可用，需要安装中文语音数据");
        }
        sb.append('\n');
        sb.append("离线合成：取决于你的系统引擎，部分引擎需要联网，本应用无法保证。\n");
        if (!lastSpoken.isEmpty()) {
            sb.append("上次朗读：").append(lastSpoken.length()).append(" 字\n");
        }
        if (!lastEvents.isEmpty()) {
            sb.append("最近事件：").append(String.join(" → ", lastEvents));
        }
        return sb.toString();
    }

    /** Activity 销毁时必须调用。 */
    public void shutdown() {
        synchronized (this) { pending = null; }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
