package cn.xianhua.pocketkit.local;

import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.ChineseNumber;
import cn.xianhua.pocketkit.core.LedgerBook;
import cn.xianhua.pocketkit.core.MoneyTool;
import cn.xianhua.pocketkit.core.ToolException;

/** B02 单位价格比价、B05 极简记账 / 收据报销 / 周期付款。 */
public final class MoneyTools implements LocalTool {

    @Override public String[] ids() {
        return new String[]{"B02", "B05"};
    }

    @Override public Map<String, String> limitations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("B02", "只做同一货币下的单位价格比较。单位不同会直接拒绝比较，"
                + "因为「每 100g」和「每片」放在一起没有意义。"
                + "金额全部用 BigDecimal 且保留两位小数，不用 double 算钱。"
                + "不做汇率换算（那是 052 的范围）。");
        m.put("B05", "数据只存在本机（SharedPreferences），不上传、不联网、不需要账号。"
                + "支持记账增删改查、报销状态与收据备注、按周期记录付款。"
                + "周期付款只做「还有几天到期」的记录与计算：不会在后台提醒、"
                + "不会自动扣款、不发起任何网络请求。"
                + "CSV 导出做了公式注入防护（以 = + - @ 开头的内容会被加上单引号）。"
                + "删除必须先确认，且会先提示影响。");
        return m;
    }

    @Override public void install(LocalToolsActivity host) {
        if ("B02".equals(host.toolIdOf())) {
            installCompare(host);
        } else {
            installLedger(host);
        }
    }

    // ---------- B02 比价 ----------

    private void installCompare(LocalToolsActivity host) {
        host.uiNote("同一种商品，不同规格/不同店家，谁更划算？"
                + "所有报价必须用同一个单位（例如都用 g），否则无法比较。");

        EditText unit = host.uiField("单位（所有报价必须一致）", "g");
        unit.setText("g");

        List<EditText[]> rows = new ArrayList<>();
        LinearLayout rowBox = new LinearLayout(host);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        host.uiAdd(rowBox);

        for (int i = 0; i < 3; i++) {
            addOfferRow(host, rowBox, rows, i);
        }

        host.uiButton("再加一行报价", () -> addOfferRow(host, rowBox, rows, rows.size()));

        host.uiButton("开始比价", () -> {
            try {
                String u = unit.getText().toString().trim();
                if (u.isEmpty()) {
                    throw new ToolException("请填写单位，例如 g、ml、片。");
                }
                List<MoneyTool.Offer> offers = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    EditText[] r = rows.get(i);
                    String label = r[0].getText().toString().trim();
                    String priceText = r[1].getText().toString().trim();
                    String amountText = r[2].getText().toString().trim();
                    if (priceText.isEmpty() && amountText.isEmpty()) {
                        continue;
                    }
                    if (label.isEmpty()) {
                        label = "第 " + (i + 1) + " 个报价";
                    }
                    BigDecimal price = MoneyTool.parse(priceText, label + "的价格");
                    BigDecimal amount = MoneyTool.parse(amountText, label + "的数量");
                    if (amount.signum() <= 0) {
                        throw new ToolException(label + "的数量必须大于 0。");
                    }
                    offers.add(new MoneyTool.Offer(label, price, amount, u));
                }
                if (offers.size() < 2) {
                    throw new ToolException("至少填写两条报价才能比价。"
                            + "现在只读到 " + offers.size() + " 条。");
                }
                MoneyTool.Comparison c = MoneyTool.compare(offers);
                StringBuilder sb = new StringBuilder();
                sb.append("单价（每 1 ").append(u).append("）：\n");
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < offers.size(); i++) {
                    order.add(i);
                }
                final BigDecimal[] prices = c.unitPrices;
                order.sort((x, y) -> Double.compare(prices[x].doubleValue(), prices[y].doubleValue()));
                for (int rank = 0; rank < order.size(); rank++) {
                    int i = order.get(rank);
                    MoneyTool.Offer o = offers.get(i);
                    BigDecimal per = prices[i];
                    sb.append(rank == 0 ? "★ " : "  ");
                    sb.append(o.label).append("  ").append(MoneyTool.display(o.price))
                            .append(" / ").append(MoneyTool.plain(o.amount)).append(u)
                            .append("   →  每 100").append(u).append(" 约 ")
                            .append(MoneyTool.plain(MoneyTool.perHundred(per))).append(" 元\n");
                }
                sb.append('\n');
                sb.append("最划算：").append(c.best.label)
                        .append("（").append(MoneyTool.display(c.best.price)).append("）\n");
                if (offers.size() > 1) {
                    sb.append("最不划算：").append(c.worst.label)
                            .append("，单价比最划算的贵 ").append(c.worstExtraPercent.toPlainString())
                            .append("%\n");
                }
                sb.append("\n按最划算的单价买 ").append(MoneyTool.plain(c.best.amount)).append(u)
                        .append(" 需要 ").append(MoneyTool.display(c.baselineTotal)).append('\n');
                sb.append("\n金额用 BigDecimal 计算并保留两位小数，不用浮点。");
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    private void addOfferRow(LocalToolsActivity host, LinearLayout box,
                             List<EditText[]> rows, int index) {
        EditText label = new EditText(host);
        label.setHint("报价 " + (index + 1) + "：名称或店家");
        EditText price = new EditText(host);
        price.setHint("价格，例如 19.90");
        price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText amount = new EditText(host);
        amount.setHint("数量，例如 500");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(label);
        box.addView(price);
        box.addView(amount);
        rows.add(new EditText[]{label, price, amount});
    }

    // ---------- B05 记账 ----------

    private void installLedger(LocalToolsActivity host) {
        LocalStore store = new LocalStore(host);
        host.uiNote("数据只存在本机，不上传、不联网、不需要账号。"
                + "周期付款只记录并计算到期日：不会后台提醒、不会自动扣款。");

        // 记一笔
        EditText date = host.uiField("日期（留空用今天）", LedgerBook.todayIso());
        EditText amount = host.uiField("金额（正数=支出，负数=收入）", "19.90");
        EditText category = host.uiField("分类", "餐饮");
        EditText note = host.uiField("备注", "午饭");
        EditText receipt = host.uiField("收据编号/文件名（可留空）", "");

        host.uiButton("记一笔", () -> {
            try {
                String d = date.getText().toString().trim();
                if (d.isEmpty()) {
                    d = LedgerBook.todayIso();
                }
                LocalDate parsed = cn.xianhua.pocketkit.core.DateDiff.parse(d, "日期");
                BigDecimal amt = MoneyTool.parse(amount.getText().toString(), "金额");
                if (amt.signum() == 0) {
                    throw new ToolException("金额为 0 的记录没有意义。请填写实际金额。");
                }
                LedgerBook.Entry e = new LedgerBook.Entry(
                        LocalStore.newId(),
                        parsed.toString(), amt,
                        category.getText().toString().trim(),
                        note.getText().toString().trim(), false,
                        receipt.getText().toString().trim(), false);
                store.add(e);
                amount.setText("");
                note.setText("");
                receipt.setText("");
                host.uiShow("已记录。" + summaryText(store));
            } catch (ToolException ex) {
                host.uiFail(ex);
            }
        });

        host.uiButton("查看全部与统计", () -> host.uiShow(summaryText(store)));

        // 报销
        EditText target = host.uiField("要操作的记录编号（见统计列表）", "");
        host.uiButton("标记为已报销", () -> {
            try {
                String id = target.getText().toString().trim();
                LedgerBook.Entry e = find(store, id);
                if (e.reimbursed) {
                    host.uiShow("这条记录已经是已报销状态。");
                    return;
                }
                store.update(new LedgerBook.Entry(e.id, e.date, e.amount, e.category,
                        e.note, true, e.receipt, e.recurring));
                host.uiShow("已标记为已报销。\n" + summaryText(store));
            } catch (ToolException ex) {
                host.uiFail(ex);
            }
        });

        host.uiButton("导出 CSV（公式注入已防护）", () -> {
            try {
                String csv = store.exportCsv();
                host.uiShow(csv.length() > 4000
                        ? csv.substring(0, 4000) + "\n…（共 " + csv.length() + " 字符，完整内容请用「保存备份」）"
                        : csv);
            } catch (ToolException ex) {
                host.uiFail(ex);
            }
        });

        host.uiButton("删除一条记录", () -> {
            String id = target.getText().toString().trim();
            try {
                LedgerBook.Entry e = find(store, id);
                new AlertDialog.Builder(host)
                        .setTitle("确认删除")
                        .setMessage("将删除：\n" + e.date + "  " + MoneyTool.display(e.amount)
                                + "  " + e.category + "  " + e.note
                                + "\n\n删除后无法恢复。要继续吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            try {
                                store.delete(id, true);
                                host.uiShow("已删除。\n" + summaryText(store));
                            } catch (ToolException ex) {
                                host.uiFail(ex);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } catch (ToolException ex) {
                host.uiFail(ex);
            }
        });

        // 周期付款
        host.uiNote("周期付款：只记录与计算到期日，不做后台提醒、不自动扣款。");
        EditText rName = host.uiField("周期项目名", "房租");
        EditText rAmount = host.uiField("每次金额", "2000");
        EditText rStart = host.uiField("首次到期日", LedgerBook.todayIso());
        android.widget.Spinner rCycle = host.uiSelect("周期",
                "每月", "每季", "每年", "每周", "每天");
        android.widget.Spinner rCat = host.uiSelect("分类", "住房", "订阅", "保险", "教育", "其他");

        host.uiButton("添加周期付款", () -> {
            try {
                String n = rName.getText().toString().trim();
                if (n.isEmpty()) {
                    throw new ToolException("请填写周期项目名。");
                }
                BigDecimal amt = MoneyTool.parse(rAmount.getText().toString(), "金额");
                if (amt.signum() <= 0) {
                    throw new ToolException("周期金额必须大于 0。");
                }
                LocalDate start = cn.xianhua.pocketkit.core.DateDiff.parse(
                        rStart.getText().toString(), "首次到期日");
                LedgerBook.Cycle cycle = cycleOf(rCycle.getSelectedItemPosition());
                store.addRecurring(new LedgerBook.Recurring(
                        LocalStore.newId(), n, amt,
                        start.toString(), cycle, String.valueOf(rCat.getSelectedItem()),
                        true));
                host.uiShow("已添加。\n" + recurringText(store));
            } catch (ToolException ex) {
                host.uiFail(ex);
            }
        });

        host.uiButton("查看到期情况", () -> host.uiShow(recurringText(store)));
    }

    private static LedgerBook.Cycle cycleOf(int index) {
        switch (index) {
            case 0: return LedgerBook.Cycle.MONTHLY;
            case 1: return LedgerBook.Cycle.QUARTERLY;
            case 2: return LedgerBook.Cycle.YEARLY;
            case 3: return LedgerBook.Cycle.WEEKLY;
            default: return LedgerBook.Cycle.DAILY;
        }
    }

    private static LedgerBook.Entry find(LocalStore store, String id) throws ToolException {
        if (id.isEmpty()) {
            throw new ToolException("请先在上面填写要操作的记录编号。"
                    + "编号可以在「查看全部与统计」里看到。");
        }
        for (LedgerBook.Entry e : store.entries()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        throw new ToolException("没有找到编号为 " + id + " 的记录。");
    }

    private static String summaryText(LocalStore store) {
        List<LedgerBook.Entry> entries = store.entries();
        LedgerBook.Summary s = LedgerBook.summarize(entries);
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(s.entryCount).append(" 条记录\n");
        sb.append("支出 ").append(MoneyTool.display(s.expense))
                .append("    收入 ").append(MoneyTool.display(s.income)).append('\n');
        sb.append("结余 ").append(MoneyTool.display(s.balance)).append('\n');
        sb.append("待报销 ").append(MoneyTool.display(s.pendingReimbursement)).append('\n');
        int bad = store.refreshCorruptedCount();
        if (bad > 0) {
            sb.append("注意：有 ").append(bad).append(" 条记录读不出来（数据损坏或来自旧版本），"
                    + "已跳过不参与统计。可以先导出备份再排查。\n");
        }
        if (entries.isEmpty()) {
            sb.append("\n还没有记录。\n");
            return sb.toString();
        }
        sb.append("\n最近记录（编号 / 日期 / 金额 / 分类 / 备注 / 报销）：\n");
        int n = 0;
        for (LedgerBook.Entry e : entries) {
            sb.append(e.id).append("  ").append(e.date).append("  ")
                    .append(MoneyTool.plain(e.amount)).append("  ")
                    .append(e.category.isEmpty() ? "（无分类）" : e.category).append("  ")
                    .append(e.note.isEmpty() ? "" : e.note).append("  ")
                    .append(e.reimbursed ? "已报销" : "未报销");
            if (!e.receipt.isEmpty()) {
                sb.append("  收据:").append(e.receipt);
            }
            sb.append('\n');
            if (++n >= 30) {
                sb.append("…（只显示最近 30 条）\n");
                break;
            }
        }
        sb.append("\n金额大写示例：" + moneyUpperHint(s.expense));
        return sb.toString();
    }

    private static String moneyUpperHint(BigDecimal v) {
        try {
            return MoneyTool.display(v) + " —— " + ChineseNumber.moneyUpper(v);
        } catch (ToolException e) {
            return MoneyTool.display(v);
        }
    }

    private static String recurringText(LocalStore store) {
        List<LedgerBook.Recurring> plans = store.recurring();
        if (plans.isEmpty()) {
            return "还没有周期付款计划。\n"
                    + "添加后可以在这里看到「还有几天到期」，但本工具不会在后台提醒你。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("周期付款（只记录与计算，不做后台提醒、不自动扣款）：\n\n");
        LocalDate today = LocalDate.now();
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        for (LedgerBook.Recurring p : plans) {
            sb.append(p.name).append("  ").append(MoneyTool.display(p.amount))
                    .append("  ").append(p.cycle.label)
                    .append("  从 ").append(p.startDate).append('\n');
            try {
                LedgerBook.Due due = LedgerBook.computeDue(p, today, 500);
                sb.append("   下一次：").append(due.text);
                if (due.missedCount > 0) {
                    sb.append("；此前已有 ").append(due.missedCount).append(" 期到期未记录");
                }
                sb.append('\n');
                sb.append("   折合每月约 ").append(MoneyTool.display(
                        LedgerBook.monthlyEquivalent(p))).append('\n');
                monthlyTotal = monthlyTotal.add(LedgerBook.monthlyEquivalent(p));
            } catch (ToolException e) {
                sb.append("   无法计算：").append(e.getMessage()).append('\n');
            }
        }
        sb.append("\n全部周期项目折合每月约 ").append(MoneyTool.display(monthlyTotal)).append('\n');
        sb.append("需要主动来做的是「记录已付」——这个工具不会提醒你。");
        return sb.toString();
    }
}
