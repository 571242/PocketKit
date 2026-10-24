package cn.xianhua.pocketkit.local;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.FocusTimer;
import cn.xianhua.pocketkit.core.ToolException;

/**
 * 计时器（064）与番茄时钟（065）。
 *
 * 计时基准是 SystemClock.elapsedRealtime（单调时钟）：锁屏、切后台、改系统时间
 * 都不会让剩余时间漂移。所有剩余时间都由 FocusTimer 按「当前单调时钟」结算，
 * 界面只负责每秒把 now 传进去刷新。
 *
 * 明确的边界（界面上也写明）：只在应用处于前台时提醒。不申请通知权限、
 * 不做后台提醒、不保证后台还有提示。这是刻意取舍。
 *
 * 旋转：Activity 重建时计时状态会丢——不做持久化，因为一个前台计时器在重建后
 * 从零开始的代价，比引入一整套保存/恢复与后台服务要小得多。界面会写明这一点。
 */
final class TimerPane {

    private final LocalToolsActivity host;
    private final boolean pomodoro;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FocusTimer timer;
    private TextView readout;
    private TextView status;
    private ToneGenerator tone;
    private final Runnable tick;

    TimerPane(LocalToolsActivity host, boolean pomodoro) {
        this.host = host;
        this.pomodoro = pomodoro;
        this.tick = new Runnable() {
            @Override public void run() {
                refresh();
                if (timer != null && timer.state() == FocusTimer.State.RUNNING) {
                    handler.postDelayed(this, 200);
                }
            }
        };
    }

    void install() {
        long defaultMs = pomodoro ? 25 * 60_000L : 5 * 60_000L;

        readout = new TextView(host);
        readout.setTextSize(64f);
        readout.setGravity(android.view.Gravity.CENTER);
        readout.setTextColor(host.uiInk());
        readout.setText(FocusTimer.formatClock(defaultMs));
        host.uiAdd(readout);

        status = new TextView(host);
        status.setTextSize(14);
        status.setGravity(android.view.Gravity.CENTER);
        status.setTextColor(host.uiMuted());
        host.uiAdd(status);

        host.uiNote("只在应用处于前台时提醒：不申请通知权限、不做后台提醒。"
                + "计时用单调时钟，锁屏与改系统时间都不会让剩余时间漂移。"
                + "离开这个页面会重置计时。");

        EditText minutes = host.uiField(pomodoro ? "专注时长（分钟）" : "时长（分钟）",
                pomodoro ? "25" : "5");
        minutes.setText(String.valueOf(defaultMs / 60_000L));

        host.uiButton("开始 / 继续", () -> {
            try {
                if (timer == null || timer.state() == FocusTimer.State.FINISHED) {
                    timer = new FocusTimer(parseMinutes(minutes.getText().toString()));
                }
                timer.start(SystemClock.elapsedRealtime());
                startTicking();
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("暂停", () -> {
            if (timer != null) {
                timer.pause(SystemClock.elapsedRealtime());
                refresh();
                handler.removeCallbacks(tick);
            }
        });
        host.uiButton("重置", () -> {
            handler.removeCallbacks(tick);
            try {
                timer = new FocusTimer(parseMinutes(minutes.getText().toString()));
                refresh();
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("加 1 分钟", () -> {
            if (timer != null) {
                timer.addTime(60_000L);
                refresh();
                startTicking();
            }
        });
        if (pomodoro) {
            host.uiButton("专注 25 分钟", () -> {
                minutes.setText("25");
                handler.removeCallbacks(tick);
                timer = new FocusTimer(25 * 60_000L);
                timer.start(SystemClock.elapsedRealtime());
                startTicking();
            });
            host.uiButton("休息 5 分钟", () -> {
                minutes.setText("5");
                handler.removeCallbacks(tick);
                timer = new FocusTimer(5 * 60_000L);
                timer.start(SystemClock.elapsedRealtime());
                startTicking();
            });
        }
        refresh();
    }

    private static long parseMinutes(String s) throws ToolException {
        String t = s.trim();
        if (t.isEmpty()) {
            throw new ToolException("时长没有填写。请输入分钟数，例如 25。");
        }
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new ToolException("时长「" + t + "」不是数字。请输入分钟数，例如 25。");
        }
        if (v <= 0) {
            throw new ToolException("时长必须大于 0 分钟。");
        }
        if (v > 24 * 60) {
            throw new ToolException("时长超过 24 小时。前台计时器不适合这么长的时段。");
        }
        return Math.round(v * 60_000L);
    }

    private void startTicking() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void refresh() {
        if (timer == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean justFinished = timer.settle(now);
        if (justFinished) {
            alert();
        }
        readout.setText(FocusTimer.formatClock(timer.remainingMs(now)));
        String state;
        switch (timer.state()) {
            case RUNNING:
                state = "进行中 " + (timer.progressPermille(now) / 10) + "%";
                break;
            case PAUSED:
                state = "已暂停";
                break;
            case FINISHED:
                state = "已结束";
                break;
            default:
                state = "未开始";
        }
        status.setText(state + "    总计 " + FocusTimer.describeDuration(timer.totalMs()));
    }

    /** 前台提醒：响一声 + 震动一下。两者都不是权限敏感的能力。 */
    private void alert() {
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
            }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 800);
        } catch (RuntimeException ignored) {
            // 某些设备没有可用音轨；静默降级到只震动
        }
        try {
            Vibrator v = (Vibrator) host.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(600);
            }
        } catch (RuntimeException ignored) {
            // 没有震动马达：只响提示音
        }
    }

    /** 离开页面时停掉回调与音轨，避免泄漏。 */
    void release() {
        handler.removeCallbacks(tick);
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }
}
