package cn.xianhua.pocketkit.timer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 计时器到点的系统闹钟。
 *
 * 用 AlarmManager.ELAPSED_REALTIME_WAKEUP（与计时用的 elapsedRealtime 同一个时基）：
 * 不依赖进程常驻，切应用、锁屏、进程被杀都照常触发；也不受改系统时间影响。
 *
 * 精确度：Android 12 起精确闹钟属于「特殊应用权限」（SCHEDULE_EXACT_ALARM）。
 * 有授权就用 setExactAndAllowWhileIdle；没有就退到 setAndAllowWhileIdle（可能延迟几分钟），
 * 并把这件事如实交给界面显示。不索权、不静默降级。
 *
 * 每个模式（倒计时 / 番茄）各占一个 requestCode，两个工具互不覆盖对方的闹钟。
 */
public final class TimerAlarm {

    /** 精确度：界面据此如实标注提醒行为。 */
    public enum Exactness {
        /** 精确闹钟，系统会在到点时刻唤醒。 */
        EXACT,
        /** 近似闹钟：没有精确闹钟权限，可能晚几分钟。 */
        APPROXIMATE,
        /** 没有排上（状态不在运行中或系统拒绝）。 */
        NONE
    }

    private TimerAlarm() {
    }

    /** 每个模式独立一个 requestCode 与闹钟槽位。 */
    static int requestCode(TimerState.Mode mode) {
        return mode == TimerState.Mode.POMODORO ? 6501 : 6401;
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /**
     * 按当前状态重排闹钟。只有运行中才排；暂停/停止/结束则撤销。
     * 重复调用是安全的：同一个 PendingIntent 会被更新而不是叠加。
     */
    public static Exactness arm(Context context, TimerState state) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return Exactness.NONE;
        }
        long deadline = state.deadlineElapsed();
        if (state.status() != TimerState.Status.RUNNING || deadline <= 0) {
            cancel(context, state.mode());
            return Exactness.NONE;
        }
        if (!receiverDeclared(context)) {
            return Exactness.NONE;
        }
        PendingIntent pending = pendingIntent(context, state.mode());
        if (canScheduleExact(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending);
                return Exactness.EXACT;
            } catch (SecurityException ignored) {
                // 声明缺失或系统拒绝：继续走近似分支，不假装精确。
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending);
        return Exactness.APPROXIMATE;
    }

    /** 撤销本模式的提醒（停止、暂停、结束）。 */
    public static void cancel(Context context, TimerState.Mode mode) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent existing = PendingIntent.getBroadcast(context, requestCode(mode),
                intent(context, mode),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(existing);
        existing.cancel();
    }

    private static PendingIntent pendingIntent(Context context, TimerState.Mode mode) {
        return PendingIntent.getBroadcast(context, requestCode(mode), intent(context, mode),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Intent intent(Context context, TimerState.Mode mode) {
        return new Intent(context, TimerAlarmReceiver.class)
                .setAction(TimerAlarmReceiver.ACTION_FIRE)
                .putExtra(TimerAlarmReceiver.EXTRA_MODE, mode.name());
    }

    /**
     * 接收者必须已在 Manifest 里声明，否则闹钟发出去了也没人收——那就是静默失效。
     * 查本包组件信息，不受 Android 11 包可见性限制；查询本身出错时不要谎报失败。
     */
    private static boolean receiverDeclared(Context context) {
        try {
            context.getPackageManager().getReceiverInfo(
                    new ComponentName(context, TimerAlarmReceiver.class), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
