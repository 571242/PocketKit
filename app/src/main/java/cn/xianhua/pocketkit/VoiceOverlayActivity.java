package cn.xianhua.pocketkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Spinner;
import cn.xianhua.pocketkit.voiceoverlay.VoiceOverlayService;

/** Permission entry for a visible local recorder, not a system virtual microphone. */
public final class VoiceOverlayActivity extends BaseToolActivity {
    private Spinner effect;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        note("悬浮录音 → 本地 DSP 变声 → 试听或分享 WAV。当前不是 AI 音色，也不能替换游戏、通话或聊天应用的麦克风输入。分享文件不等于发送游戏语音。悬浮录音会保持前台通知；分享缓存由系统管理，重要录音请及时分享保存。");
        note("拖动悬浮窗标题到左右侧边，松手后自动收成小球；点击小球展开。录音上限 60 秒，其他应用占用麦克风时可能只录到静音。试听前录音会停止。");
        effect = select("悬浮窗效果", "原声", "清亮（DSP）", "低沉（DSP）", "机器人（DSP）");
        button("授权麦克风", () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7301));
        button("授权悬浮窗", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        if (android.os.Build.VERSION.SDK_INT >= 33) button("授权通知（推荐）", () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7302));
        button("开启悬浮录音", () -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("请先授权麦克风");
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("请先授权悬浮窗");
            startForegroundService(new Intent(this, VoiceOverlayService.class).putExtra("preset", effect.getSelectedItemPosition()));
            show("悬浮录音已请求启动，可切到其他应用。它不会自动点击或替换其他应用麦克风。");
        });
        button("关闭悬浮录音", () -> { stopService(new Intent(this, VoiceOverlayService.class)); show("已关闭悬浮录音"); });
    }
    public static boolean supports(String id) { return "V03".equals(id); }
    public static String limitation(String id) { return "悬浮窗录音、本地 DSP 变声、试听与文件分享；不支持向游戏麦克风直接注入音频。"; }
}
