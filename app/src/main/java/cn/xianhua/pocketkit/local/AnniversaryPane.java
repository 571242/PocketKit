package cn.xianhua.pocketkit.local;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.DateDiff;
import cn.xianhua.pocketkit.core.ToolException;

/**
 * 纪念日（057）。
 *
 * 只做本地记录与天数计算：不申请通知权限、不做后台提醒、不联网。
 * 打开这个工具时才能看到还有多少天，界面上如实写明。
 *
 * 每年重复的纪念日按「今年/明年」取最近的一次，2 月 29 日在非闰年落到 2 月 28 日
 * （这是常见处理方式，界面会标注）。
 */
final class AnniversaryPane {

    private final LocalToolsActivity host;
    private final LocalStore store;
    private LinearLayout list;

    AnniversaryPane(LocalToolsActivity host) {
        this.host = host;
        this.store = new LocalStore(host);
    }

    void install() {
        host.uiNote("只在本机记录与计算天数：不申请通知权限、不做后台提醒、不联网。"
                + "只有打开这个工具时才看得到剩余天数。");

        EditText name = host.uiField("名称", "结婚纪念日");
        EditText date = host.uiField("日期", "2020-05-20 或 05-20（每年重复）");
        host.uiButton("添加", () -> {
            try {
                String n = name.getText().toString().trim();
                String d = date.getText().toString().trim();
                if (n.isEmpty()) {
                    throw new ToolException("请填写纪念日名称。");
                }
                // A month-day input ("05-20") repeats every year; a full date ("2020-05-20")
                // is a one-off. Counting hyphens inverted this: two hyphens meant the full date
                // was treated as yearly, so one-off anniversaries could not be recorded at all.
                boolean yearly = countHyphens(d) == 1;
                String normalized = normalize(d, yearly);
                store.addAnniversary(LocalStore.newId(), n,
                        normalized, yearly);
                name.setText("");
                date.setText("");
                render();
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });

        host.uiButton("刷新天数", this::render);
        // 用独立容器，不能清空 body —— 那会把上面的输入框和按钮一起删掉
        list = host.uiContainer();
        render();
    }

    /** 把用户输入规范成 ISO 日期。只写月日时按「每年重复」处理，年份取今年。 */
    static String normalize(String d, boolean yearly) throws ToolException {
        String s = d.trim();
        if (s.isEmpty()) {
            throw new ToolException("日期没有填写。请用 2020-05-20 或 05-20。");
        }
        if (yearly) {
            String[] p = s.split("-");
            if (p.length == 2) {
                int m = parseInt(p[0], "月");
                int day = parseInt(p[1], "日");
                return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                        LocalDate.now().getYear(), m, day);
            }
            if (p.length == 3) {
                // 完整年份但被判定为每年重复（例如两个连字符不该出现的情况）
                return DateDiff.parse(s, "日期").toString();
            }
            throw new ToolException("日期「" + s + "」格式不对。请用 2020-05-20 或 05-20。");
        }
        return DateDiff.parse(s, "日期").toString();
    }

    private static int parseInt(String s, String label) throws ToolException {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new ToolException(label + "「" + s.trim() + "」不是数字。");
        }
    }

    /** Separator count decides the format: one hyphen is 月-日, two is 年-月-日. */
    private static int countHyphens(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '-') count++;
        }
        return count;
    }

    private void render() {
        // 只重建自己的动态容器，保留表单
        list.removeAllViews();
        List<String[]> items = store.anniversaries();
        if (items.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText("还没有纪念日。在上面的输入框里添加一条。");
            empty.setTextSize(14);
            empty.setTextColor(host.uiMuted());
            list.addView(empty);
            return;
        }
        LocalDate today = LocalDate.now();
        List<String[]> rows = new ArrayList<>(items);
        rows.sort((a, b) -> Long.compare(daysTo(a, today), daysTo(b, today)));
        for (String[] a : rows) {
            boolean yearly = "1".equals(a[3]);
            long days = daysTo(a, today);
            LocalDate next = nextOccurrence(a, today, yearly);
            TextView t = new TextView(host);
            t.setText(a[1] + "\n  " + a[2] + (yearly ? "（每年）" : "（仅一次）")
                    + "\n  " + (days == 0 ? "就是今天" : days > 0
                    ? "还有 " + days + " 天（" + next + "）"
                    : "已经过去 " + (-days) + " 天"));
            t.setTextSize(15);
            t.setTextColor(host.uiInk());
            t.setPadding(0, host.uiDp(6), 0, host.uiDp(6));
            list.addView(t);
        }
    }

    private static LocalDate nextOccurrence(String[] a, LocalDate today, boolean yearly) {
        LocalDate date = LocalDate.parse(a[2]);
        if (!yearly) {
            return date;
        }
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate.isBefore(today)) {
            candidate = safeDate(today.getYear() + 1, month, day);
        }
        return candidate;
    }

    /** 2 月 29 日在非闰年落到 2 月 28 日，避免抛异常。 */
    private static LocalDate safeDate(int year, int month, int day) {
        int d = day;
        if (month == 2 && day == 29 && !java.time.Year.isLeap(year)) {
            d = 28;
        }
        return LocalDate.of(year, month, d);
    }

    private static long daysTo(String[] a, LocalDate today) {
        LocalDate date = LocalDate.parse(a[2]);
        boolean yearly = "1".equals(a[3]);
        if (!yearly) {
            return ChronoUnit.DAYS.between(today, date);
        }
        return ChronoUnit.DAYS.between(today, nextOccurrence(a, today, true));
    }
}
