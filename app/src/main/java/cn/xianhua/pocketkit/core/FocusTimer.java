package cn.xianhua.pocketkit.core;

import java.util.Locale;

/**
 * 前台专注倒计时的状态机。
 *
 * 计时基准是单调时钟（Android 上是 SystemClock.elapsedRealtime），因此锁屏、切后台、
 * 系统时间被改动都不会让剩余时间漂移。界面只负责把 now 传进来，逻辑本身与 Android 无关，
 * 可以被纯 Java 单元测试完整覆盖。
 *
 * 明确的边界：本计时器只在应用处于前台时提醒（响铃/震动）。它不申请通知权限，
 * 不做后台提醒，也不承诺在后台继续有任何提示。这是刻意的取舍，界面上如实写明。
 */
public final class FocusTimer {

    public enum State {
        /** 尚未开始，或已被重置。 */
        IDLE,
        RUNNING,
        PAUSED,
        FINISHED
    }

    /** 基准总时长，加时只增加 extraMs。 */
    private final long baseMs;
    /** 用户加出来的时间。 */
    private long extraMs;
    private State state = State.IDLE;
    /** 已累计的专注时长（毫秒），不含当前正在跑的这一段。 */
    private long accumulatedMs;
    /** 当前这段开始的单调时刻，仅 RUNNING 时有效。 */
    private long runningSince;

    public FocusTimer(long totalMs) {
        if (totalMs <= 0) {
            throw new IllegalArgumentException("总时长必须为正数");
        }
        this.baseMs = totalMs;
    }

    public long totalMs() {
        return baseMs + extraMs;
    }

    public State state() {
        return state;
    }

    public void start(long now) {
        if (state == State.RUNNING) {
            return;
        }
        if (state == State.FINISHED || state == State.IDLE) {
            accumulatedMs = 0;
        }
        runningSince = now;
        state = State.RUNNING;
    }

    public void pause(long now) {
        if (state != State.RUNNING) {
            return;
        }
        accumulatedMs = rawElapsed(now);
        state = State.PAUSED;
    }

    /** 暂停后继续；已跑过的时间保留，不倒扣也不重置。 */
    public void resume(long now) {
        if (state != State.PAUSED) {
            return;
        }
        runningSince = now;
        state = State.RUNNING;
    }

    /** 清零回到 IDLE；加时也一并清掉。 */
    public void reset() {
        state = State.IDLE;
        accumulatedMs = 0;
        runningSince = 0;
        extraMs = 0;
    }

    /** 加时：直接抬高总时长，已跑过的进度自然保留。 */
    public void addTime(long additionalMs) {
        if (additionalMs <= 0) {
            return;
        }
        extraMs += additionalMs;
        if (state == State.FINISHED && remainingMs(runningSince) > 0) {
            // 结束后又加时：回到暂停态，等用户点继续。
            state = State.PAUSED;
        }
    }

    private long rawElapsed(long now) {
        long base = accumulatedMs;
        if (state == State.RUNNING) {
            base += Math.max(0, now - runningSince);
        }
        return base;
    }

    public long elapsedMs(long now) {
        return Math.min(rawElapsed(now), totalMs());
    }

    public long remainingMs(long now) {
        long r = totalMs() - rawElapsed(now);
        return r < 0 ? 0 : r;
    }

    /** 0–1000 的进度千分比，便于画进度弧。 */
    public int progressPermille(long now) {
        long t = totalMs();
        if (t <= 0) {
            return 1000;
        }
        long e = rawElapsed(now);
        if (e >= t) {
            return 1000;
        }
        return (int) (e * 1000L / t);
    }

    public boolean isFinished(long now) {
        return totalMs() - rawElapsed(now) <= 0;
    }

    /**
     * 结算：把「已到点」从运行状态固化为 FINISHED。
     * 返回 true 表示本次调用刚刚越过终点，用于只触发一次的提醒。
     */
    public boolean settle(long now) {
        if (state != State.RUNNING) {
            return false;
        }
        if (isFinished(now)) {
            accumulatedMs = totalMs();
            state = State.FINISHED;
            return true;
        }
        return false;
    }

    /** 把毫秒格式化成 mm:ss，超过一小时则 h:mm:ss。 */
    public static String formatClock(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** 毫秒转成「25 分钟」这类可读文本。 */
    public static String describeDuration(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(" 小时");
        }
        if (m > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(m).append(" 分钟");
        }
        if (s > 0 || sb.length() == 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s).append(" 秒");
        }
        return sb.toString();
    }
}
