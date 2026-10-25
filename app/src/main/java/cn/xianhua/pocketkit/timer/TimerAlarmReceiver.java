package cn.xianhua.pocketkit.timer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 计时器到点闹钟的接收者。
 *
 * 契约（交接给 lead，Manifest 由 lead 统一接）：
 *   action   cn.xianhua.pocketkit.timer.FIRE
 *   receiver .timer.TimerAlarmReceiver   exported=false（只由本应用的 PendingIntent 触发）
 *
 * onReceive 里的工作有界：读一次 SharedPreferences → 确认这次到点仍然成立
 * （状态仍是 RUNNING、还没提醒过、时钟没有倒退）→ 发一条通知 → 落盘 notified。
 * 不发网络请求、不启动服务、不开线程、不排队补发。
 *
 * 过期闹钟（用户在别处暂停/停止过，或系统把闹钟延迟到很久以后）在这里被丢弃：
 * claimExpired() 会返回 null，历史提醒不会被重放。
 */
public final class TimerAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "cn.xianhua.pocketkit.timer.FIRE";
    /** 取值是 TimerState.Mode 的名字（COUNTDOWN / POMODORO）。 */
    public static final String EXTRA_MODE = "mode";

    private static final String CHANNEL_ID = "pocketkit_timer";
    private static final int NOTIFICATION_ID_COUNTDOWN = 6401;
    private static final int NOTIFICATION_ID_POMODORO = 6501;

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_FIRE.equals(intent.getAction())) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        TimerStore store = new TimerStore(context);
        TimerState.Mode mode = parseMode(intent.getStringExtra(EXTRA_MODE));
        // 闹钟可能与用户刚做的暂停/继续擦肩而过：以落盘状态为准，不以 intent 为准。
        TimerState fresh = TimerState.claimExpired(store.raw(mode), now, store.bootId());
        if (fresh == null || !fresh.tick(now)) {
            return;
        }
        store.save(fresh);
        notifyFinished(context, fresh);
    }

    private static TimerState.Mode parseMode(String raw) {
        try {
            return TimerState.Mode.valueOf(raw);
        } catch (RuntimeException e) {
            return TimerState.Mode.COUNTDOWN;
        }
    }

    private static void notifyFinished(Context context, TimerState state) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        // 建通道是幂等的（minSdk 26）：同 id 再建一次不会手改用户已调的设置。
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "计时器提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("倒计时与番茄钟到点提醒");
        manager.createNotificationChannel(channel);
        // 用户关掉了本应用的通知：发出去也不会显示，这里不做无谓的重试。
        if (!manager.areNotificationsEnabled()) {
            return;
        }
        Intent open = new Intent(context, cn.xianhua.pocketkit.LocalToolsActivity.class)
                .putExtra("tool_id", state.mode() == TimerState.Mode.POMODORO ? "065" : "064")
                .putExtra("title", state.mode() == TimerState.Mode.POMODORO ? "番茄时钟" : "计时器")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, notificationId(state.mode()), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID);
        builder.setContentTitle(title(state))
                .setContentText("点开查看，或直接开始下一段。")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .setContentIntent(content);
        manager.notify(notificationId(state.mode()), builder.build());
    }

    static String title(TimerState state) {
        if (state.mode() == TimerState.Mode.POMODORO) {
            return state.phase() == TimerState.Phase.FOCUS ? "专注时间到" : "休息结束";
        }
        return "计时结束";
    }

    static int notificationId(TimerState.Mode mode) {
        return mode == TimerState.Mode.POMODORO ? NOTIFICATION_ID_POMODORO : NOTIFICATION_ID_COUNTDOWN;
    }

    /** 用户主动取消提醒（停止计时）时顺手清掉可能还挂着的通知。 */
    public static void cancelNotification(Context context, TimerState.Mode mode) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId(mode));
        }
    }
}
