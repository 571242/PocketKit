package cn.xianhua.pocketkit.timer;

/**
 * 计时器状态机：纯 Java，无 Android 依赖，可被 JVM 单元测试直接覆盖。
 *
 * 时钟约定：所有 now 都是单调时钟（Android 上是 SystemClock.elapsedRealtime）。
 * 运行中的剩余时间由 deadlineElapsed 结算，所以改系统时间不会让计时漂移；
 * 落盘时同时记下 savedElapsed，读回时若 now 比它还小，说明设备重启过
 * （elapsedRealtime 归零），这时不能假装计时还在跑。
 *
 * 到点只在 tick() 里结算一次。读盘（restore）永远不发通知：过期只把状态固化成
 * FINISHED 并把 notified 置位，避免重新打开页面时补发一堆历史提醒。
 *
 * 番茄阶段不自动切换：一次只排一个闹钟，到点只提醒一次，下一阶段由用户点按钮开始，
 * 所以不存在提醒连锁或失控循环。
 */
public final class TimerState {

    public enum Status {
        /** 尚未开始，或已停止。 */
        IDLE,
        RUNNING,
        PAUSED,
        FINISHED
    }

    public enum Mode {
        COUNTDOWN,
        POMODORO
    }

    public enum Phase {
        FOCUS,
        BREAK
    }

    private static final String VERSION = "v1";
    /** 重启判定余量：正常落盘与读回只差几毫秒。 */
    private static final long REBOOT_SLACK_MS = 2_000L;

    private final Mode mode;
    private Phase phase;
    private Status status = Status.IDLE;
    private long totalMs;
    private long remainingMs;
    /** 仅 RUNNING 有效：到点的单调时刻。 */
    private long deadlineElapsed;
    /** 上次落盘时的单调时刻，用来识别重启。 */
    private long savedElapsed;
    /** 上次落盘时的系统 BOOT_COUNT；-1 表示系统未提供，不使用墙钟猜测重启。 */
    private long savedBootStamp;
    /** 本轮是否已经提醒过，防止重复提醒与重放。 */
    private boolean notified;

    public TimerState(Mode mode, Phase phase, long totalMs) {
        if (totalMs <= 0) {
            throw new IllegalArgumentException("总时长必须为正数");
        }
        this.mode = mode == null ? Mode.COUNTDOWN : mode;
        this.phase = phase == null ? Phase.FOCUS : phase;
        this.totalMs = totalMs;
        this.remainingMs = totalMs;
    }

    private TimerState(Mode mode, Phase phase, long totalMs, Status status,
                       long remainingMs, long deadlineElapsed, long savedElapsed, boolean notified) {
        this.mode = mode;
        this.phase = phase;
        this.totalMs = totalMs;
        this.status = status;
        this.remainingMs = remainingMs;
        this.deadlineElapsed = deadlineElapsed;
        this.savedElapsed = savedElapsed;
        this.notified = notified;
    }

    public Mode mode() {
        return mode;
    }

    public Phase phase() {
        return phase;
    }

    public Status status() {
        return status;
    }

    public long totalMs() {
        return totalMs;
    }

    public long deadlineElapsed() {
        return deadlineElapsed;
    }

    public boolean notified() {
        return notified;
    }

    public void start(long now) {
        if (status == Status.RUNNING) {
            return;
        }
        if (status == Status.IDLE || status == Status.FINISHED) {
            remainingMs = totalMs;
        }
        deadlineElapsed = now + remainingMs;
        notified = false;
        status = Status.RUNNING;
    }

    public void pause(long now) {
        if (status != Status.RUNNING) {
            return;
        }
        long left = remainingMs(now);
        deadlineElapsed = 0;
        remainingMs = left;
        status = left > 0 ? Status.PAUSED : Status.FINISHED;
    }

    /** 暂停后继续：已跑过的时间保留。 */
    public void resume(long now) {
        if (status != Status.PAUSED) {
            return;
        }
        deadlineElapsed = now + remainingMs;
        notified = false;
        status = Status.RUNNING;
    }

    /** 停止并作废本轮：回到 IDLE，剩余时间回到总时长。 */
    public void stop() {
        status = Status.IDLE;
        remainingMs = totalMs;
        deadlineElapsed = 0;
        notified = false;
    }

    /** 加时：运行中直接推迟到点，暂停/结束时抬高剩余时间。 */
    public void addTime(long additionalMs) {
        if (additionalMs <= 0) {
            return;
        }
        totalMs += additionalMs;
        if (status == Status.RUNNING) {
            deadlineElapsed += additionalMs;
        } else {
            remainingMs += additionalMs;
            if (status == Status.FINISHED) {
                status = Status.PAUSED;
            }
        }
    }

    public long remainingMs(long now) {
        if (status != Status.RUNNING) {
            return remainingMs;
        }
        long left = deadlineElapsed - now;
        return left < 0 ? 0 : left;
    }

    /** 0–1000 的进度千分比。 */
    public int progressPermille(long now) {
        if (totalMs <= 0) {
            return 1000;
        }
        long left = remainingMs(now);
        if (left <= 0) {
            return 1000;
        }
        return (int) Math.max(0, Math.min(1000, (totalMs - left) * 1000L / totalMs));
    }

    /**
     * 结算到点。返回 true 表示这一次调用刚越过终点且此前没提醒过，
     * 调用方据此只提醒一次。
     */
    public boolean tick(long now) {
        if (status != Status.RUNNING || remainingMs(now) > 0) {
            return false;
        }
        status = Status.FINISHED;
        deadlineElapsed = 0;
        remainingMs = 0;
        if (notified) {
            return false;
        }
        notified = true;
        return true;
    }

    public void markSaved(long now, long bootStamp) {
        savedElapsed = now;
        savedBootStamp = bootStamp;
    }

    public String encode() {
        return VERSION + '|' + status.name() + '|' + mode.name() + '|' + phase.name()
                + '|' + totalMs + '|' + remainingMs + '|' + deadlineElapsed
                + '|' + savedElapsed + '|' + (notified ? 1 : 0) + '|' + savedBootStamp;
    }

    private static TimerState decode(String raw) {
        if (raw == null) {
            return null;
        }
        String[] f = raw.split("\\|", -1);
        if (f.length != 10 || !VERSION.equals(f[0])) {
            return null;
        }
        try {
            Status status = Status.valueOf(f[1]);
            Mode mode = Mode.valueOf(f[2]);
            Phase phase = Phase.valueOf(f[3]);
            long total = Long.parseLong(f[4]);
            long remaining = Long.parseLong(f[5]);
            long deadline = Long.parseLong(f[6]);
            long saved = Long.parseLong(f[7]);
            boolean notified = "1".equals(f[8]);
            long bootStamp = Long.parseLong(f[9]);
            if (total <= 0 || remaining < 0 || remaining > total || deadline < 0 || saved < 0) {
                return null;
            }
            if (status == Status.RUNNING && deadline <= 0) {
                return null;
            }
            TimerState state = new TimerState(mode, phase, total, status, remaining,
                    deadline, saved, notified);
            state.savedBootStamp = bootStamp;
            return state;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 读回状态。只读不写、不发通知：过期也仅固化为 FINISHED（notified 置位），
     * 这样反复打开页面不会重放提醒。
     *
     * @param nowElapsed 当前单调时钟
     * @param bootStamp 当前系统 BOOT_COUNT（保留参数名以兼容调用）；改系统时间不改变它
     */
    public static Restored restore(String raw, long nowElapsed, long bootStamp) {
        boolean corrupt = raw != null && !raw.isEmpty();
        TimerState state = decode(raw);
        if (state == null) {
            return new Restored(null, false, false, corrupt);
        }
        if (state.status == Status.RUNNING) {
            if (state.invalidatedByClock(nowElapsed, bootStamp)) {
                return new Restored(null, true, false, false);
            }
            if (state.remainingMs(nowElapsed) <= 0) {
                state.status = Status.FINISHED;
                state.deadlineElapsed = 0;
                state.remainingMs = 0;
                state.notified = true;
                return new Restored(state, false, true, false);
            }
        }
        return new Restored(state, false, false, false);
    }

    /**
     * 闹钟到点时用：只有「本来在跑、已经越过终点、还没提醒过、时钟可信」才返回状态，
     * 由调用方立即 tick() 拿 true 并提醒一次。其余情况返回 null，
     * 因此迟到的闹钟不会把历史提醒重放出来。
     */
    public static TimerState claimExpired(String raw, long nowElapsed, long bootStamp) {
        TimerState state = decode(raw);
        if (state == null || state.status != Status.RUNNING || state.notified) {
            return null;
        }
        if (state.invalidatedByClock(nowElapsed, bootStamp)) {
            return null;
        }
        return state.remainingMs(nowElapsed) <= 0 ? state : null;
    }

    /**
     * 单调时钟倒退或系统开机计数变化，才说明重启过。墙钟校时与时区切换不影响计时。
     */
    private boolean invalidatedByClock(long nowElapsed, long bootStamp) {
        if (nowElapsed + REBOOT_SLACK_MS < savedElapsed) {
            return true;
        }
        return savedBootStamp >= 0 && bootStamp >= 0 && savedBootStamp != bootStamp;
    }

    /** restore() 的结果。state 为 null 表示没有可用的计时状态。 */
    public static final class Restored {
        public final TimerState state;
        /** 设备重启导致状态作废。 */
        public final boolean rebooted;
        /** 上次在应用不在前台时已经到点：只显示结果，不补发提醒。 */
        public final boolean expiredWhileAway;
        /** 存的内容读不出来。 */
        public final boolean corrupt;

        private Restored(TimerState state, boolean rebooted, boolean expiredWhileAway, boolean corrupt) {
            this.state = state;
            this.rebooted = rebooted;
            this.expiredWhileAway = expiredWhileAway;
            this.corrupt = corrupt;
        }
    }
}
