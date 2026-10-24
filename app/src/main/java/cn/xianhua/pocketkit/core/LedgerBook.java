package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简记账 / 收据报销 / 周期付款（B05）的数据模型与纯计算。
 *
 * 只负责算，不负责存：存储由 local.LedgerStore 用 SharedPreferences 完成。
 * 所有金额是 BigDecimal 且固定两位小数。
 *
 * 明确边界：
 * - 本工具只记录与统计，不做任何后台提醒。周期付款只计算「已到期/还有几天到期」，
 *   不会在后台弹通知、不会自动扣款、不发起任何网络请求。
 */
public final class LedgerBook {

    /** 一条流水。 */
    public static final class Entry {
        public final String id;
        /** 日期，ISO yyyy-MM-dd。 */
        public final String date;
        /** 金额，正数表示支出，负数表示收入。 */
        public final BigDecimal amount;
        public final String category;
        public final String note;
        /** 是否已报销/已核销。 */
        public final boolean reimbursed;
        /** 关联的收据文件名或标识（可为空）。 */
        public final String receipt;
        /** 是否属于周期付款生成的记录。 */
        public final boolean recurring;

        public Entry(String id, String date, BigDecimal amount, String category,
                     String note, boolean reimbursed, String receipt, boolean recurring) {
            this.id = id;
            this.date = date;
            this.amount = MoneyTool.round(amount);
            this.category = category == null ? "" : category;
            this.note = note == null ? "" : note;
            this.reimbursed = reimbursed;
            this.receipt = receipt == null ? "" : receipt;
            this.recurring = recurring;
        }

        public LocalDate localDate() {
            return LocalDate.parse(date);
        }
    }

    /** 周期付款计划。 */
    public static final class Recurring {
        public final String id;
        public final String name;
        public final BigDecimal amount;
        /** 首次到期日。 */
        public final String startDate;
        public final Cycle cycle;
        public final String category;
        public final boolean active;

        public Recurring(String id, String name, BigDecimal amount, String startDate,
                         Cycle cycle, String category, boolean active) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.amount = MoneyTool.round(amount);
            this.startDate = startDate;
            this.cycle = cycle;
            this.category = category == null ? "" : category;
            this.active = active;
        }
    }

    /** 周期。按月与按年需要日期算术，不能简单加天数。 */
    public enum Cycle {
        DAILY("每天", 0),
        WEEKLY("每周", 0),
        MONTHLY("每月", 0),
        QUARTERLY("每季", 0),
        YEARLY("每年", 0);

        public final String label;
        /** 保留字段，实际推进逻辑在 nextDue 里。 */
        public final int days;

        Cycle(String label, int days) {
            this.label = label;
            this.days = days;
        }

        @Override
        public String toString() {
            return label;
        }

        public LocalDate next(LocalDate from) {
            switch (this) {
                case DAILY:
                    return from.plusDays(1);
                case WEEKLY:
                    return from.plusWeeks(1);
                case MONTHLY:
                    return from.plusMonths(1);
                case QUARTERLY:
                    return from.plusMonths(3);
                case YEARLY:
                    return from.plusYears(1);
                default:
                    return from.plusMonths(1);
            }
        }
    }

    public static final class Summary {
        public final BigDecimal expense;
        public final BigDecimal income;
        public final BigDecimal balance;
        /** 未报销的支出合计。 */
        public final BigDecimal pendingReimbursement;
        public final int entryCount;
        public final List<String> categories;

        Summary(BigDecimal expense, BigDecimal income, BigDecimal balance,
                BigDecimal pendingReimbursement, int entryCount, List<String> categories) {
            this.expense = expense;
            this.income = income;
            this.balance = balance;
            this.pendingReimbursement = pendingReimbursement;
            this.entryCount = entryCount;
            this.categories = categories;
        }
    }

    private LedgerBook() {
    }

    /** 统计一批流水。支出取正数之和，收入取负数绝对值之和。 */
    public static Summary summarize(List<Entry> entries) {
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        List<String> categories = new ArrayList<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e.amount.signum() > 0) {
                    expense = expense.add(e.amount);
                    if (!e.reimbursed) {
                        pending = pending.add(e.amount);
                    }
                } else {
                    income = income.add(e.amount.abs());
                }
                if (!e.category.isEmpty() && !categories.contains(e.category)) {
                    categories.add(e.category);
                }
            }
        }
        return new Summary(MoneyTool.round(expense), MoneyTool.round(income),
                MoneyTool.round(income.subtract(expense)), MoneyTool.round(pending),
                entries == null ? 0 : entries.size(), categories);
    }

    // ---------- 周期付款：只算到期，不提醒 ----------

    /** 一个周期的到期情况。 */
    public static final class Due {
        public final Recurring plan;
        /** 下一个未到期的日期。 */
        public final LocalDate nextDue;
        /** 按「今天」结算，已经过了几次未记录。 */
        public final int missedCount;
        /** 距离下次到期还有几天（负数表示已过期）。 */
        public final long daysUntilNext;
        /** 下次到期的说明文本。 */
        public final String text;

        Due(Recurring plan, LocalDate nextDue, int missedCount, long daysUntilNext, String text) {
            this.plan = plan;
            this.nextDue = nextDue;
            this.missedCount = missedCount;
            this.daysUntilNext = daysUntilNext;
            this.text = text;
        }
    }

    /**
     * 只做计算：给出下一次到期日与已经错过几次。
     * 不写任何存储、不安排任何后台任务。
     */
    public static Due computeDue(Recurring plan, LocalDate today, int graceLimit) throws ToolException {
        if (plan == null) {
            throw new ToolException("没有可计算的周期计划。");
        }
        LocalDate start;
        try {
            start = LocalDate.parse(plan.startDate);
        } catch (RuntimeException e) {
            throw new ToolException("周期计划「" + plan.name + "」的起始日期「"
                    + plan.startDate + "」不是有效日期。请用 2026-10-13 格式。");
        }
        if (plan.cycle == null) {
            throw new ToolException("周期计划「" + plan.name + "」没有设置周期。");
        }
        LocalDate cursor = start;
        int guard = 0;
        // 从起始日一路推进到第一个 >= today 的日期；plan 很久以前的记录会走很多步，
        // 因此给一个上限，避免界面卡死。
        while (cursor.isBefore(today)) {
            cursor = plan.cycle.next(cursor);
            if (++guard > 200000) {
                throw new ToolException("周期计划「" + plan.name + "」的起始日期太早，"
                        + "无法在合理步数内推算。请把起始日期改近一些。");
            }
        }
        // 往回数已经错过的次数
        int missed = 0;
        LocalDate back = cursor;
        while (missed < graceLimit) {
            LocalDate prev = previous(back, plan.cycle);
            if (prev.isBefore(start) || prev.isBefore(today)) {
                break;
            }
            back = prev;
            missed++;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, cursor);
        String text = days == 0 ? "今天到期"
                : days > 0 ? "还有 " + days + " 天到期（" + cursor + "）"
                : "已过期 " + (-days) + " 天（" + cursor + "）";
        return new Due(plan, cursor, missed, days, text);
    }

    /** 上一个到期日（next 的逆运算，用于回溯已错过的期数）。 */
    private static LocalDate previous(LocalDate d, Cycle c) {
        switch (c) {
            case DAILY:
                return d.minusDays(1);
            case WEEKLY:
                return d.minusWeeks(1);
            case MONTHLY:
                return d.minusMonths(1);
            case QUARTERLY:
                return d.minusMonths(3);
            case YEARLY:
                return d.minusYears(1);
            default:
                return d.minusMonths(1);
        }
    }

    /** 按周期把金额摊到每个月，用于年度预算估算。 */
    public static BigDecimal monthlyEquivalent(Recurring plan) throws ToolException {
        if (plan == null || plan.cycle == null) {
            throw new ToolException("没有可换算的周期计划。");
        }
        BigDecimal perMonth;
        switch (plan.cycle) {
            case DAILY:
                perMonth = plan.amount.multiply(new BigDecimal("30.4375"));
                break;
            case WEEKLY:
                perMonth = plan.amount.multiply(new BigDecimal("52")).divide(new BigDecimal("12"), 8,
                        java.math.RoundingMode.HALF_UP);
                break;
            case MONTHLY:
                perMonth = plan.amount;
                break;
            case QUARTERLY:
                perMonth = plan.amount.divide(new BigDecimal("3"), 8, java.math.RoundingMode.HALF_UP);
                break;
            case YEARLY:
                perMonth = plan.amount.divide(new BigDecimal("12"), 8, java.math.RoundingMode.HALF_UP);
                break;
            default:
                perMonth = plan.amount;
        }
        return MoneyTool.round(perMonth);
    }

    // ---------- 序列化：给 local.LedgerStore 用，不依赖 Android ----------

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 转成一行文本，字段间用 \u0001 分隔以避免与内容里的逗号/竖线冲突。 */
    public static String encode(Entry e) {
        return String.join("\u0001", e.id, e.date, e.amount.toPlainString(),
                esc(e.category), esc(e.note), e.reimbursed ? "1" : "0",
                esc(e.receipt), e.recurring ? "1" : "0");
    }

    public static Entry decodeEntry(String line) throws ToolException {
        String[] f = line.split("\u0001", -1);
        if (f.length < 8) {
            throw new ToolException("账目记录格式不正确（字段数 " + f.length + "，应为 8）。"
                    + "这条记录可能来自损坏或旧版本的数据。");
        }
        try {
            return new Entry(f[0], f[1], new BigDecimal(f[2]), unesc(f[3]), unesc(f[4]),
                    "1".equals(f[5]), unesc(f[6]), "1".equals(f[7]));
        } catch (NumberFormatException ex) {
            throw new ToolException("账目记录里的金额「" + f[2] + "」不是数字，无法读取。");
        }
    }

    public static String encode(Recurring r) {
        return String.join("\u0001", r.id, esc(r.name), r.amount.toPlainString(), r.startDate,
                r.cycle.name(), esc(r.category), r.active ? "1" : "0");
    }

    public static Recurring decodeRecurring(String line) throws ToolException {
        String[] f = line.split("\u0001", -1);
        if (f.length < 7) {
            throw new ToolException("周期付款记录格式不正确（字段数 " + f.length + "，应为 7）。");
        }
        try {
            return new Recurring(f[0], unesc(f[1]), new BigDecimal(f[2]), f[3],
                    Cycle.valueOf(f[4]), unesc(f[5]), "1".equals(f[6]));
        } catch (IllegalArgumentException ex) {
            throw new ToolException("周期付款记录里的周期「" + f[4] + "」无法识别。");
        }
    }

    /** 字段内可能含分隔符，统一转义，保证读回来一字不差。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\u0001", "\\u0001")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else if (n == 'u' && s.startsWith("0001", i + 1)) {
                    // esc 写出的是「\u0001」这 6 个字符，此处 i 指向 'u'，后面还有 4 位数字。
                    sb.append('\u0001');
                    i += 4;
                } else {
                    sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 今天（ISO）。放在这里让上层不直接依赖 java.time 的静态调用，便于测试。 */
    public static String todayIso() {
        return LocalDate.now().format(ISO);
    }

    public static String formatDate(LocalDate d) {
        return d.format(ISO);
    }
}
