package cn.xianhua.pocketkit.core;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One explicit Beijing-time target; display precision is not clock accuracy. */
public final class TicketTiming {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?");

    private TicketTiming() {}

    public static long parseTarget(String date, String time, long now) {
        Matcher match = TIME.matcher(time.trim());
        if (!match.matches()) {
            throw new IllegalArgumentException("时间格式应为 时:分 或 时:分:秒，例如 09:37:30。");
        }
        final long target;
        try {
            LocalTime value = LocalTime.of(Integer.parseInt(match.group(1)),
                    Integer.parseInt(match.group(2)), match.group(3) == null ? 0 : Integer.parseInt(match.group(3)));
            target = LocalDate.parse(date.trim()).atTime(value).atZone(ZONE).toInstant().toEpochMilli();
        } catch (java.time.DateTimeException ex) {
            throw new IllegalArgumentException("请填写有效日期，以及时:分:秒，例如 09:37:30。", ex);
        }
        if (target <= now) throw new IllegalArgumentException("这个时间已经过去，请修改日期或时间。");
        return target;
    }

    public static String remaining(long millis) {
        if (millis <= 0) return "开抢时间已到";
        long seconds = millis / 1000;
        long days = seconds / 86400;
        String clock = String.format(Locale.US, "%02d:%02d:%02d.%03d",
                seconds / 3600 % 24, seconds / 60 % 60, seconds % 60, millis % 1000);
        return days == 0 ? clock : days + "天 " + clock;
    }

    public static boolean keepScreenOn(long remaining) {
        return remaining <= 60000 && remaining >= -10000;
    }

    /** Emit only the latest crossed threshold, never burst old reminders after a delayed frame. */
    public static final class Alerts {
        private int lastStage = -1;

        public int poll(long remaining) {
            if (remaining < -5000) {
                lastStage = 3;
                return -1;
            }
            int stage = remaining <= 0 ? 3 : remaining <= 1000 ? 2 : remaining <= 3000 ? 1 : remaining <= 5000 ? 0 : -1;
            if (stage <= lastStage) return -1;
            lastStage = stage;
            return stage;
        }
    }
}
