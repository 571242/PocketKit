import cn.xianhua.pocketkit.core.TicketTiming;
import java.time.Instant;

public final class TicketTimingSelfTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(String date, String time, long now) {
        try {
            TicketTiming.parseTarget(date, time, now);
            throw new AssertionError("accepted invalid target: " + date + " " + time);
        } catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        long now = Instant.parse("2026-10-13T15:59:50Z").toEpochMilli();
        check(TicketTiming.parseTarget("2026-10-14", "00:00:05", now) == now + 15000, "Beijing midnight rollover");
        check(TicketTiming.parseTarget("2026-10-14", "9:37:30", now) == Instant.parse("2026-10-14T01:37:30Z").toEpochMilli(), "non-hour with seconds");
        check(TicketTiming.parseTarget("2026-10-14", "09:37", now) == Instant.parse("2026-10-14T01:37:00Z").toEpochMilli(), "optional seconds");
        rejects("2026-10-13", "23:59:49", now);
        rejects("2026-10-13", "23:59:50", now);
        rejects("2026-10-14", "24:00:00", now);
        rejects("2026-10-14", "12:60:00", now);
        rejects("2026-10-14", "12:00:60", now);
        rejects("2026-02-30", "12:00:00", now);
        rejects("", "09:00:00", now);
        rejects("2026-10-14", "09:00:00 trailing", now);
        check(TicketTiming.remaining(61005).equals("00:01:01.005"), "milliseconds retained");
        check(TicketTiming.remaining(86400001).equals("1天 00:00:00.001"), "days not lost");
        check(TicketTiming.remaining(-1).equals("开抢时间已到"), "no unverified claim tickets are on sale");
        TicketTiming.Alerts alerts = new TicketTiming.Alerts();
        check(alerts.poll(6000) == -1, "too early");
        check(alerts.poll(4600) == 0, "5s threshold, delayed frame");
        check(alerts.poll(4000) == -1, "5s threshold once");
        check(alerts.poll(2500) == 1, "3s threshold, delayed frame");
        check(alerts.poll(850) == 2, "1s threshold");
        check(alerts.poll(-100) == 3, "arrival once");
        check(alerts.poll(-1000) == -1, "no duplicate arrival");
        TicketTiming.Alerts stalled = new TicketTiming.Alerts();
        check(stalled.poll(750) == 2, "skip missed stages rather than burst vibrations");
        check(stalled.poll(-6000) == -1, "do not replay overdue alarm after suspension");
        check(stalled.poll(-20) == -1, "clock jump cannot replay an already overdue alarm");
        check(!TicketTiming.keepScreenOn(86400000), "do not keep the screen on all night");
        check(TicketTiming.keepScreenOn(60000), "keep awake only near target");
        check(TicketTiming.keepScreenOn(-10000), "brief grace period after target");
        check(!TicketTiming.keepScreenOn(-10001), "release keep-awake flag after grace period");
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            check(TicketTiming.parseTarget("2026-10-14", "09:37:30", now)
                    == Instant.parse("2026-10-14T01:37:30Z").toEpochMilli(), "device time zone cannot change Beijing target");
        } finally { java.util.TimeZone.setDefault(previous); }
        System.out.println("TicketTimingSelfTest PASS: " + checks + " checks");
    }
}
