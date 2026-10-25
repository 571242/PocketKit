package cn.xianhua.pocketkit.timer;

/**
 * TimerState 的纯 JVM 断言检查；无 Android 运行时、无设备依赖。
 *
 * 覆盖：到期只提醒一次、暂停/继续保留剩余、加时、停止作废、系统开机计数与重启、
 * 往返编码、损坏输入、以及「恢复不重放历史提醒」。
 *
 * bootStamp 参数传系统 BOOT_COUNT，改墙钟不会改变它；测试里用一个固定值，
 * 需要模拟重启时换一个值。
 */
public final class TimerStateTest {

    private static final long BOOT = 7L;
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    public static void main(String[] args) {
        startPauseResume();
        expiryRingsOnce();
        addTimeAndStop();
        pomodoroPhasesAreIndependent();
        roundTrip();
        restoreDoesNotReplay();
        restoreRejectsReboot();
        restoreRejectsGarbage();
        claimExpiredGuards();
        System.out.println("TimerStateTest OK: " + checks + " checks");
    }

    private static TimerState countdown(long totalMs) {
        return new TimerState(TimerState.Mode.COUNTDOWN, TimerState.Phase.FOCUS, totalMs);
    }

    private static void startPauseResume() {
        TimerState s = countdown(60_000L);
        check(s.status() == TimerState.Status.IDLE, "new state is IDLE");
        check(s.remainingMs(0) == 60_000L, "idle keeps full duration");

        s.start(1_000L);
        check(s.status() == TimerState.Status.RUNNING, "start -> RUNNING");
        check(s.remainingMs(11_000L) == 50_000L, "running counts down");

        s.pause(11_000L);
        check(s.status() == TimerState.Status.PAUSED, "pause -> PAUSED");
        check(s.remainingMs(99_000L) == 50_000L, "paused clock is frozen");

        s.resume(99_000L);
        check(s.status() == TimerState.Status.RUNNING, "resume -> RUNNING");
        check(s.remainingMs(109_000L) == 40_000L, "resume keeps remaining");
        check(s.progressPermille(109_000L) == 333, "progress reflects elapsed");
    }

    private static void expiryRingsOnce() {
        TimerState s = countdown(1_000L);
        s.start(0L);
        check(!s.tick(999L), "not yet expired");
        check(s.tick(1_000L), "expiry rings once");
        check(s.status() == TimerState.Status.FINISHED, "expiry -> FINISHED");
        check(s.remainingMs(5_000L) == 0L, "finished stays at zero");
        check(!s.tick(6_000L), "ringing never repeats");
    }

    private static void addTimeAndStop() {
        TimerState s = countdown(60_000L);
        s.start(0L);
        s.addTime(30_000L);
        check(s.totalMs() == 90_000L, "addTime raises total");
        check(s.remainingMs(60_000L) == 30_000L, "addTime defers deadline, progress kept");

        s.stop();
        check(s.status() == TimerState.Status.IDLE, "stop -> IDLE");
        check(s.remainingMs(60_000L) == 90_000L, "stop restores the whole duration");
        check(s.deadlineElapsed() == 0L, "stop clears the alarm deadline");

        TimerState f = countdown(1_000L);
        f.start(0L);
        f.tick(1_000L);
        f.addTime(60_000L);
        check(f.status() == TimerState.Status.PAUSED, "add after finish waits for resume");
        check(f.remainingMs(2_000L) == 60_000L, "add after finish gives back the extra");
    }

    /** 阶段互不牵连：换阶段不会让上一段的到点再响一次。 */
    private static void pomodoroPhasesAreIndependent() {
        TimerState s = new TimerState(TimerState.Mode.POMODORO, TimerState.Phase.FOCUS, 1_000L);
        s.start(0L);
        check(s.tick(1_000L), "focus rings once");
        TimerState br = new TimerState(TimerState.Mode.POMODORO, TimerState.Phase.BREAK, 1_000L);
        check(br.status() == TimerState.Status.IDLE, "break starts idle");
        br.start(2_000L);
        check(br.tick(3_000L), "break rings once");
        check(!s.tick(4_000L), "old focus state stays quiet");
        check(br.phase() == TimerState.Phase.BREAK, "phase survives the run");
    }

    private static void roundTrip() {
        TimerState s = new TimerState(TimerState.Mode.POMODORO, TimerState.Phase.BREAK, 300_000L);
        s.start(10_000L);
        s.markSaved(10_000L, BOOT);
        TimerState.Restored back = TimerState.restore(s.encode(), 10_000L, BOOT);
        check(back.state != null && !back.rebooted && !back.corrupt, "clean round trip");
        check(back.state.mode() == TimerState.Mode.POMODORO, "mode survives");
        check(back.state.phase() == TimerState.Phase.BREAK, "phase survives");
        check(back.state.status() == TimerState.Status.RUNNING, "status survives");
        check(back.state.totalMs() == 300_000L, "total survives");
        check(back.state.remainingMs(60_000L) == 250_000L, "deadline survives");
        check(back.state.encode().equals(s.encode()), "re-encoding is stable");
    }

    /** 恢复只能给结果，不能补提醒：这是「不重放历史通知」的核心保证。 */
    private static void restoreDoesNotReplay() {
        TimerState s = countdown(10_000L);
        s.start(0L);
        s.markSaved(0L, BOOT);
        TimerState.Restored late = TimerState.restore(s.encode(), 500_000L, BOOT);
        check(late.expiredWhileAway, "expired while away is reported");
        check(late.state != null && late.state.status() == TimerState.Status.FINISHED, "settled to FINISHED");
        check(late.state.notified(), "restore marks it already notified");
        check(!late.state.tick(500_000L), "restore never rings");
        // 再次恢复同一个快照，仍然是「已提醒」，不会因为反复打开页面而重放。
        TimerState.Restored again = TimerState.restore(late.state.encode(), 600_000L, BOOT);
        check(again.state != null && again.state.notified(), "repeat restore stays quiet");
        check(!again.state.tick(600_000L), "repeat restore never rings");
        TimerState delivered = countdown(1_000L);
        delivered.start(0L); delivered.tick(1_000L); delivered.markSaved(1_000L, BOOT);
        TimerState.Restored actual = TimerState.restore(delivered.encode(), 2_000L, BOOT);
        check(!actual.expiredWhileAway && actual.state.notified(), "actual delivery differs from read-time expiry");
    }

    /** 单调时钟倒退或开机时刻变了 = 设备重启：不能假装计时还在跑。 */
    private static void restoreRejectsReboot() {
        TimerState s = countdown(600_000L);
        s.start(900_000L);
        s.markSaved(900_000L, BOOT);
        TimerState.Restored after = TimerState.restore(s.encode(), 1_000L, BOOT);
        check(after.state == null, "clock going backwards drops the running state");
        check(after.rebooted, "reboot is reported");
        check(!after.expiredWhileAway, "reboot is not a silent expiry");

        // 单调时钟已经超过旧快照，但开机时刻变了：仍然判定重启。
        TimerState.Restored fresh = TimerState.restore(s.encode(), 5_000_000L, BOOT + 1);
        check(fresh.state == null && fresh.rebooted, "boot stamp change drops the running state");

        // 墙钟和时区任意调整都不改变系统 BOOT_COUNT，不让它们作废单调计时。
        TimerState.Restored nudged = TimerState.restore(s.encode(), 1_000_000L, BOOT);
        check(nudged.state != null && !nudged.rebooted, "unchanged boot identity preserves timer");
        check(nudged.state.remainingMs(1_000_000L) == s.remainingMs(1_000_000L), "elapsed time remains authoritative");

        // 暂停的计时不依赖单调时钟：剩余时间是存下来的，重启后照常可读。
        TimerState p = countdown(600_000L);
        p.start(0L);
        p.pause(100_000L);
        p.markSaved(100_000L, BOOT);
        TimerState.Restored kept = TimerState.restore(p.encode(), 5L, BOOT + 1);
        check(!kept.rebooted, "paused snapshot does not need the clock");
        check(kept.state != null && kept.state.status() == TimerState.Status.PAUSED,
                "paused survives reboot");
        check(kept.state.remainingMs(5L) == 500_000L, "paused remaining survives reboot");

        TimerState idle = countdown(600_000L);
        idle.markSaved(0L, BOOT);
        TimerState.Restored plain = TimerState.restore(idle.encode(), 5L, BOOT);
        check(!plain.rebooted, "idle snapshot needs no clock check");
        check(plain.state != null && plain.state.status() == TimerState.Status.IDLE, "idle survives");
    }

    private static void restoreRejectsGarbage() {
        check(TimerState.restore("", 0L, BOOT).state == null, "empty value has no state");
        check(!TimerState.restore("", 0L, BOOT).corrupt, "empty value is not corruption");
        check(TimerState.restore("garbage", 0L, BOOT).corrupt, "garbage is reported");
        check(TimerState.restore("v1|RUNNING|COUNTDOWN|FOCUS|-1|0|0|0|0|0", 0L, BOOT).corrupt,
                "negative total");
        check(TimerState.restore("v1|RUNNING|COUNTDOWN|FOCUS|1000|0|0|0|0|0", 0L, BOOT).corrupt,
                "running without a deadline");
        check(TimerState.restore("v9|RUNNING|COUNTDOWN|FOCUS|1000|0|0|0|0|0", 0L, BOOT).corrupt,
                "unknown version");
        check(TimerState.restore("v1|NOPE|COUNTDOWN|FOCUS|1000|0|0|0|0|0", 0L, BOOT).corrupt,
                "unknown status");
        check(TimerState.restore("v1|IDLE|COUNTDOWN|FOCUS|1000|0|0|0|0", 0L, BOOT).corrupt,
                "short record (old format) is rejected");
    }

    private static void claimExpiredGuards() {
        TimerState running = countdown(1_000L);
        running.start(0L);
        running.markSaved(0L, BOOT);
        check(TimerState.claimExpired(running.encode(), 500L, BOOT) == null, "not expired yet");
        TimerState claimed = TimerState.claimExpired(running.encode(), 1_000L, BOOT);
        check(claimed != null && claimed.tick(1_000L), "expired claim rings once");
        check(TimerState.claimExpired(running.encode(), 2_000L, BOOT).tick(2_000L),
                "claim is fresh each time");
        check(TimerState.claimExpired(running.encode(), 2_000L, BOOT + 1) == null,
                "a stale alarm after reboot does not ring");

        TimerState paused = countdown(1_000L);
        paused.start(0L);
        paused.pause(500L);
        paused.markSaved(500L, BOOT);
        check(TimerState.claimExpired(paused.encode(), 9_000L, BOOT) == null,
                "paused never rings from an alarm");

        TimerState done = countdown(1_000L);
        done.start(0L);
        done.tick(1_000L);
        done.markSaved(1_000L, BOOT);
        check(TimerState.claimExpired(done.encode(), 3_000L, BOOT) == null,
                "already finished never rings again");
    }
}
