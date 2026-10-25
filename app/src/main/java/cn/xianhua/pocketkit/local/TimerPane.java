package cn.xianhua.pocketkit.local;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.EditText;
import android.widget.TextView;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.FocusTimer;
import cn.xianhua.pocketkit.core.ToolException;
import cn.xianhua.pocketkit.timer.TimerAlarm;
import cn.xianhua.pocketkit.timer.TimerAlarmReceiver;
import cn.xianhua.pocketkit.timer.TimerState;
import cn.xianhua.pocketkit.timer.TimerStore;

/**
 * 计时器（064）与番茄时钟（065）。
 *
 * 计时基准是单调时钟（SystemClock.elapsedRealtime）：锁屏、切应用、改系统时间都不会让
 * 剩余时间漂移。到点由 AlarmManager 的系统闹钟负责，所以进程不常驻也能提醒；
 * 本界面只在可见时每秒刷新，不用常驻线程冒充闹钟。
 *
 * 状态只在用户操作时落盘（开始/暂停/继续/停止/加时/换阶段）。旋转、离开页面、重开都从
 * 磁盘恢复；恢复本身不发通知，所以反复打开页面不会重放历史提醒。
 *
 * 番茄阶段不自动切换：每次只排一个到点闹钟，下一段由用户点按钮开始，不存在提醒连锁。
 */
final class TimerPane {

    private static final int REQUEST_NOTIFICATIONS = 0x6401;

    private final LocalToolsActivity host;
    private final boolean pomodoro;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TimerStore store;

    private TimerState state;
    private TextView readout;
    private TextView status;
    private TextView banner;
    private EditText minutes;
    private ToneGenerator tone;
    private TimerAlarm.Exactness exactness = TimerAlarm.Exactness.NONE;
    private boolean notifyPermitted;
    private boolean notifyEnabled;
    private long permissionsCheckedAt;
    private boolean notifiedAskSent;
    private final Runnable tick;

    TimerPane(LocalToolsActivity host, boolean pomodoro) {
        this.host = host;
        this.pomodoro = pomodoro;
        this.store = new TimerStore(host);
        this.tick = new Runnable() {
            @Override public void run() {
                if (!foreground()) {
                    // 没在前台就停掉链子：到点交给系统闹钟，不用后台心跳冒充。
                    return;
                }
                refresh();
                if (state != null && state.status() == TimerState.Status.RUNNING) {
                    handler.postDelayed(this, 200);
                }
            }
        };
    }

    void install() {
        long defaultMs = pomodoro ? 25 * 60_000L : 5 * 60_000L;

        permissionsCheckedAt = 0L;
        refreshPermissionsNow();

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

        banner = new TextView(host);
        banner.setTextSize(13);
        banner.setTextColor(host.uiMuted());
        host.uiAdd(banner);

        // 回到前台才重新开始刷新：离开时不必留任何后台心跳。
        readout.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus) {
                refresh();
                if (state != null && state.status() == TimerState.Status.RUNNING) {
                    startTicking();
                }
            }
        });

        restore();

        host.uiNote("到点由系统闹钟提醒：切应用、锁屏都有效，应用不在前台也能响。"
                + "计时用单调时钟，改系统时间不会让剩余时间漂移。");

        minutes = host.uiField(pomodoro ? "专注时长（分钟）" : "时长（分钟）", pomodoro ? "25" : "5");
        minutes.setText(String.valueOf(seedMinutes(defaultMs)));

        host.uiButton("开始 / 继续", this::begin);
        host.uiButton("暂停", this::pause);
        host.uiButton("停止并取消提醒", this::stop);
        host.uiButton("加 1 分钟", () -> addTime(60_000L));
        host.uiNote("暂停后「继续」接着剩余时间走；要改时长请先停止。");

        if (pomodoro) {
            host.uiButton("专注 25 分钟", () -> switchPhase(TimerState.Phase.FOCUS, 25 * 60_000L));
            host.uiButton("休息 5 分钟", () -> switchPhase(TimerState.Phase.BREAK, 5 * 60_000L));
            host.uiNote("专注与休息各提醒一次，不自动接续：下一段由你点按钮开始，不会有连环提醒。");
        }

        host.uiNote("「停止」会撤销这次到点提醒，并清掉已经弹出的通知。");
        if (!TimerAlarm.canScheduleExact(host)) {
            host.uiButton("系统设置：允许精确闹钟", this::openExactAlarmSettings);
        }

        if (state != null && state.status() == TimerState.Status.RUNNING) {
            arm();
            startTicking();
        } else {
            refresh();
        }
    }

    private TimerState.Mode modeOf() {
        return pomodoro ? TimerState.Mode.POMODORO : TimerState.Mode.COUNTDOWN;
    }

    private static long nowElapsed() {
        return SystemClock.elapsedRealtime();
    }

    /** 输入框初值：有可恢复的状态就用它的时长，否则用默认值。 */
    private long seedMinutes(long defaultMs) {
        if (state == null) {
            return defaultMs / 60_000L;
        }
        long total = state.totalMs();
        return total > 0 ? Math.max(1L, Math.round(total / 60_000.0)) : defaultMs / 60_000L;
    }

    /** 读盘恢复：只赋状态，不补发通知，也不改磁盘。 */
    private void restore() {
        TimerState.Restored restored = store.load(modeOf());
        if (restored.corrupt) {
            host.uiNote("上次的计时记录读不出来（或来自旧版本），已忽略，从新的一次开始。");
            return;
        }
        if (restored.rebooted) {
            host.uiNote("设备重启过，中断了多久无从得知，上次的计时已作废。");
            return;
        }
        state = restored.state;
        if (state == null) {
            return;
        }
        if (restored.expiredWhileAway) {
            host.uiNote("上次离开期间已经到点。这里只显示结果，不会补发提醒。");
        }
        if (state.status() == TimerState.Status.FINISHED) {
            TimerAlarm.cancel(host, modeOf());
        }
    }

    /** 开始或继续：暂停中接着走；其余情况按输入时长重开一段。 */
    private void begin() {
        long now = nowElapsed();
        if (state != null && state.status() == TimerState.Status.PAUSED) {
            state.resume(now);
        } else if (state != null && state.status() == TimerState.Status.RUNNING) {
            startTicking();
            return;
        } else {
            long requested;
            try {
                requested = parseMinutes(minutes.getText().toString());
            } catch (ToolException e) {
                host.uiFail(e);
                return;
            }
            state = new TimerState(modeOf(), currentPhase(), requested);
            state.start(now);
        }
        ensureNotifyPermission();
        afterChange();
        startTicking();
    }

    private TimerState.Phase currentPhase() {
        if (!pomodoro || state == null) {
            return TimerState.Phase.FOCUS;
        }
        return state.phase();
    }

    /** 番茄换阶段：新开一段并开始。只排这一次闹钟，不会自动接续。 */
    private void switchPhase(TimerState.Phase phase, long totalMs) {
        state = new TimerState(modeOf(), phase, totalMs);
        minutes.setText(String.valueOf(totalMs / 60_000L));
        TimerAlarmReceiver.cancelNotification(host, modeOf());
        state.start(nowElapsed());
        ensureNotifyPermission();
        afterChange();
        startTicking();
    }

    private void pause() {
        if (state == null || state.status() != TimerState.Status.RUNNING) {
            refresh();
            return;
        }
        state.pause(nowElapsed());
        handler.removeCallbacks(tick);
        afterChange();
    }

    private void stop() {
        if (state == null) {
            refresh();
            return;
        }
        state.stop();
        handler.removeCallbacks(tick);
        TimerAlarm.cancel(host, modeOf());
        TimerAlarmReceiver.cancelNotification(host, modeOf());
        afterChange();
    }

    private void addTime(long deltaMs) {
        if (state == null || state.status() == TimerState.Status.IDLE) {
            host.uiNote("先开始一段计时，再往上加时间。");
            return;
        }
        state.addTime(deltaMs);
        afterChange();
        if (state.status() == TimerState.Status.RUNNING) {
            startTicking();
        }
    }

    /** 所有状态变化都只经过这里：落盘 → 重排闹钟 → 刷新。 */
    private void afterChange() {
        if (state == null) {
            return;
        }
        if (!store.save(state)) {
            // 没落盘的话，到点闹钟读不到状态就发不出通知——不能默默当成成功。
            host.uiShow("计时状态没能写入本机存储：这次到点可能不会有后台提醒。");
        }
        arm();
        refresh();
    }

    /** 按当前状态重排闹钟；精确度交给界面如实显示。 */
    private void arm() {
        if (state == null) {
            return;
        }
        exactness = TimerAlarm.arm(host, state);
    }

    // ---------- 权限（按需申请，拒绝也不静默失效） ----------

    /** 刷新权限快照。每 200ms 刷界面时不该去问系统，所以只每秒查一次。 */
    private void refreshPermissions() {
        long now = nowElapsed();
        if (now - permissionsCheckedAt < 1_000L) {
            return;
        }
        permissionsCheckedAt = now;
        refreshPermissionsNow();
    }

    private void refreshPermissionsNow() {
        notifyPermitted = Build.VERSION.SDK_INT < 33
                || host.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager =
                (NotificationManager) host.getSystemService(Context.NOTIFICATION_SERVICE);
        notifyEnabled = manager != null && manager.areNotificationsEnabled();
    }

    private boolean canNotify() {
        return notifyPermitted;
    }

    private boolean notificationsEnabled() {
        return notifyEnabled;
    }

    /** 首次排后台提醒时按需问一次通知权限；只在本次页面里问一次。 */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33 || canNotify() || notifiedAskSent) {
            return;
        }
        notifiedAskSent = true;
        try {
            host.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_NOTIFICATIONS);
        } catch (RuntimeException ignored) {
            // 申请失败不影响计时本身；界面会显示「通知未授予」。
        }
        refreshPermissionsNow();
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) {
            host.uiNote("这台系统没有精确闹钟开关，提醒本来就是准时的。");
            return;
        }
        try {
            host.startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + host.getPackageName())));
        } catch (RuntimeException e) {
            host.uiFail(new IllegalStateException("这台设备打不开精确闹钟设置页，"
                    + "提醒继续用近似方式，可能晚几分钟。"));
        }
    }

    private long parseMinutes(String s) throws ToolException {
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
            throw new ToolException("时长超过 24 小时，不受支持。");
        }
        return Math.round(v * 60_000L);
    }

    // ---------- 刷新 ----------

    /** 本窗口是否真的在前台且获得焦点。没焦点时不结算，交给系统闹钟。 */
    private boolean foreground() {
        return !host.isFinishing() && host.hasWindowFocus();
    }

    private void startTicking() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void refresh() {
        refreshPermissions();
        if (state == null) {
            readout.setText(FocusTimer.formatClock(pomodoro ? 25 * 60_000L : 5 * 60_000L));
            status.setText("未开始");
            renderBanner();
            return;
        }
        long now = nowElapsed();
        boolean justFinished = state.tick(now);
        if (justFinished) {
            // 后台闹钟可能已经先结算过（还弹了通知）：那就只显示结果，不再重复提醒。
            TimerState.Restored disk = store.load(modeOf());
            if (disk.state != null && !disk.expiredWhileAway
                    && disk.state.status() != TimerState.Status.RUNNING) {
                state = disk.state;
                justFinished = false;
            }
        }
        readout.setText(FocusTimer.formatClock(state.remainingMs(now)));
        String label;
        switch (state.status()) {
            case RUNNING:
                label = "进行中 " + (state.progressPermille(now) / 10) + "%";
                break;
            case PAUSED:
                label = "已暂停";
                break;
            case FINISHED:
                label = "已结束";
                break;
            default:
                label = "未开始";
        }
        if (pomodoro) {
            label = (state.phase() == TimerState.Phase.FOCUS ? "专注" : "休息") + " · " + label;
        }
        status.setText(label + "    总计 " + FocusTimer.describeDuration(state.totalMs()));
        if (justFinished) {
            // 前台已经提醒过：撤销闹钟与通知，同一次到点不重复打扰。
            store.save(state);
            handler.removeCallbacks(tick);
            TimerAlarm.cancel(host, modeOf());
            TimerAlarmReceiver.cancelNotification(host, modeOf());
            exactness = TimerAlarm.Exactness.NONE;
            alert();
        } else if (state.status() == TimerState.Status.RUNNING
                && exactness == TimerAlarm.Exactness.APPROXIMATE && TimerAlarm.canScheduleExact(host)) {
            // 用户刚在系统设置里放开了精确闹钟：下一次刷新就升级成精确。
            arm();
        }
        renderBanner();
    }

    /** 把「提醒到底靠不靠谱」如实写在界面上，不静默降级。 */
    private void renderBanner() {
        StringBuilder sb = new StringBuilder();
        if (state == null || state.status() != TimerState.Status.RUNNING) {
            sb.append(exactness == TimerAlarm.Exactness.NONE
                    ? "当前没有待触发的提醒（未开始、已暂停或已结束）。"
                    : "提醒已排上。");
        } else if (exactness == TimerAlarm.Exactness.EXACT) {
            sb.append("精确提醒已排上：到点由系统闹钟唤醒，切应用/锁屏都有效。");
        } else if (exactness == TimerAlarm.Exactness.APPROXIMATE) {
            sb.append("近似提醒：没有精确闹钟权限，系统可能晚几分钟才响。");
        } else {
            sb.append("提醒没能排上（系统拒绝或接收者未注册），后台到点不会有提示。");
        }
        if (!canNotify()) {
            sb.append(" 通知权限未授予：应用不在前台时到点不会有提示。");
        } else if (!notificationsEnabled()) {
            sb.append(" 系统里本应用的通知是关闭的：应用不在前台时到点不会有提示。");
        }
        banner.setText(sb.toString());
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
            Vibrator v = host.getSystemService(Vibrator.class);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {
            // 没有震动马达：只响提示音
        }
    }

    /** 离开页面：只停回调与音轨。闹钟与落盘状态都保留，后台照常提醒。 */
    void release() {
        handler.removeCallbacks(tick);
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }
}
