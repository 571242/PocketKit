package cn.xianhua.pocketkit.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 日期天数差。
 * 已知边界（界面上必须写明）：只算自然日，不包含任何法定节假日/调休日历。
 */
public final class DateDiff {

    public static final class Result {
        public final LocalDate from;
        public final LocalDate to;
        /** 相差天数：不含起始日，即 to - from。 */
        public final long days;
        /** 含首含尾的天数，等同 days + 1。 */
        public final long inclusiveDays;
        public final long weeks;
        public final long remainderDays;
        /** 期间周一到周五的天数（不含起始日，按 to - from 的区间计）。 */
        public final long weekdays;
        public final boolean reversed;
        public final boolean sameDay;
        public final int startWeekdayIndex;
        public final int endWeekdayIndex;

        Result(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
            LocalDate a = from;
            LocalDate b = to;
            this.reversed = b.isBefore(a);
            if (this.reversed) {
                LocalDate tmp = a;
                a = b;
                b = tmp;
            }
            this.days = ChronoUnit.DAYS.between(a, b);
            this.inclusiveDays = days + 1;
            this.weeks = days / 7;
            this.remainderDays = days % 7;
            this.weekdays = countWeekdays(a, b);
            this.sameDay = days == 0;
            this.startWeekdayIndex = a.getDayOfWeek().getValue();
            this.endWeekdayIndex = b.getDayOfWeek().getValue();
        }

        private static long countWeekdays(LocalDate a, LocalDate b) {
            long count = 0;
            LocalDate d = a;
            while (d.isBefore(b)) {
                int dow = d.getDayOfWeek().getValue();
                if (dow <= 5) {
                    count++;
                }
                d = d.plusDays(1);
            }
            return count;
        }
    }

    private DateDiff() {
    }

    /** 接受 2026-10-13、2026/10/13、2026.10.13、2026年3月9日（月/日允许一位数）。 */
    public static LocalDate parse(String text, String fieldLabel) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException(fieldLabel + "没有填写。请用 2026-10-13 这样的格式。");
        }
        LocalDate parsed = tryParse(text);
        if (parsed == null) {
            throw new ToolException(fieldLabel + "「" + text.trim() + "」不是有效日期。"
                    + "请用 2026-10-13 或 2026年3月9日 格式，并确认这一天真实存在（例如 2 月没有 30 日）。");
        }
        return parsed;
    }

    /** 宽松解析：只补零，不猜。任何一段不是纯数字就返回 null，由调用方统一报错。 */
    private static LocalDate tryParse(String text) {
        String s = text.trim().replace('年', '-').replace('月', '-')
                .replace("日", "").replace("号", "")
                .replace('/', '-').replace('.', '-').trim();
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        String[] parts = s.split("-");
        if (parts.length != 3) {
            return null;
        }
        String y = parts[0].trim();
        String mo = parts[1].trim();
        String d = parts[2].trim();
        if (!isDigits(y) || !isDigits(mo) || !isDigits(d)) {
            return null;
        }
        try {
            // ISO 要求月、日两位；补零不改语义，年份原样交给 LocalDate 校验。
            return LocalDate.parse(y + "-" + pad2(mo) + "-" + pad2(d));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String pad2(String s) {
        return s.length() >= 2 ? s : "0" + s;
    }

    public static Result between(LocalDate from, LocalDate to) {
        return new Result(from, to);
    }

    public static Result between(String fromText, String toText) throws ToolException {
        return new Result(parse(fromText, "开始日期"), parse(toText, "结束日期"));
    }

    public static final String[] WEEKDAY_NAMES = { "", "周一", "周二", "周三", "周四", "周五", "周六", "周日" };

    public static String weekdayName(int isoDayOfWeek) {
        if (isoDayOfWeek < 1 || isoDayOfWeek > 7) {
            return "";
        }
        return WEEKDAY_NAMES[isoDayOfWeek];
    }
}
