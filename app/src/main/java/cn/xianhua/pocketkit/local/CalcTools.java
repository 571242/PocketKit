package cn.xianhua.pocketkit.local;

import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.ChineseNumber;
import cn.xianhua.pocketkit.core.DateDiff;
import cn.xianhua.pocketkit.core.FxRates;
import cn.xianhua.pocketkit.core.MathEval;
import cn.xianhua.pocketkit.core.MoneyTool;
import cn.xianhua.pocketkit.core.RandomTool;
import cn.xianhua.pocketkit.core.ToolException;
import cn.xianhua.pocketkit.core.UnitTool;

/** 计算与生活：050 051 052 053 054 056 059 060 063 066。 */
public final class CalcTools implements LocalTool {

    /** 各入口的简短说明；汇率只在用户点按钮时请求。 */
    @Override public String[] ids() {
        return new String[]{"050", "051", "052", "053", "054", "056", "059", "060", "063", "066"};
    }

    @Override public Map<String, String> limitations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("050", "BMI 用国际成人标准（WHO）分级，仅作参考："
                + "不分年龄性别、不适用于孕妇、运动员、儿童与青少年。"
                + "结果不能作为医学诊断。");
        m.put("051", "支持常用数学表达式，"
                + "也不该为算 1+1 引入 JS 引擎）。支持 + - * / % ^、括号、一元正负号、"
                + "sqrt/abs/round/floor/ceil 与常量 pi e。全程 BigDecimal。");
        m.put("052", "在线获取汇率。"
                + "的真实 HTTPS 请求。只有你点「获取汇率并换算」时才会联网，"
                + "应用不做自动请求、不做后台刷新。"
                + "汇率是各国央行公布的参考汇率，不是实时价：周末与节假日不更新，"
                + "可能延迟、缺失或被修订，不能用于税务、会计或法律用途 —— "
                + "结果里会同时显示汇率日期与来源。"
                + "失败时明确报错，绝不显示缓存或猜测的数字。"
                + "界面列出的常用币种是逐个对照服务实际支持的 165 个币种筛过的；"
                + "其余币种可直接填 ISO 代码查询（该接口实测可查 CNH、TWD、RUB 等）。");
        m.put("053", "日期计算只算自然日，不含法定节假日与调休日历；"
                + "不联网校时，用的是设备本地日期。支持 2026-10-13、2026/10/13、"
                + "2026年3月9日 等写法。");
        m.put("054", "手持弹幕是全屏大字显示，用常亮窗口实现，点一下屏幕退出。"
                + "不请求任何权限，不修改系统亮度设置（只提高这个窗口的亮度）。");
        m.put("056", "从候选项中随机抽取。"
                + "随机结果没有倾向性，也不能替代需要真实信息的决策。");
        m.put("059", "生成范围内的随机数。"
                + "这是随机数，不是密码生成器。");
        m.put("060", "亲戚称呼表收录 98 条常用关系，最多 4 步（四代以内）。"
                + "超出范围的组合明确返回「未收录」，不会猜一个称呼给你。"
                + "各地方言称谓差别很大（姥姥/外婆、姥爷/外公等），本表给普通话通用写法并并列列出。");
        m.put("063", "时间计算做时长加减与换算。跨时区、跨夏令时的日期运算不在此工具范围内。");
        m.put("066", "支持常用单位和市制单位换算。"
                + "温度含摄氏/华氏/开尔文/列氏/兰氏。所有的换算都是比例或仿射换算，"
                + "用 BigDecimal 收敛到 12 位有效数字。不做货币换算（那是 052 的范围）。");
        return m;
    }

    @Override public void install(LocalToolsActivity host) {
        switch (host.toolIdOf()) {
            case "050": bmi(host); break;
            case "051": calculator(host); break;
            case "052": exchange(host); break;
            case "053": dateCalc(host); break;
            case "054": danmaku(host); break;
            case "056": decide(host); break;
            case "059": randomNumber(host); break;
            case "060": relatives(host); break;
            case "063": timeCalc(host); break;
            case "066": units(host); break;
            default: break;
        }
    }

    // ---------- 050 BMI ----------

    private void bmi(LocalToolsActivity host) {
        EditText cm = host.uiField("身高（厘米）", "170");
        EditText kg = host.uiField("体重（公斤）", "65");
        host.uiButton("计算", () -> {
            try {
                double h = UnitTool.parseNumber(cm.getText().toString(), "身高");
                double w = UnitTool.parseNumber(kg.getText().toString(), "体重");
                if (h <= 0 || h > 300) {
                    throw new ToolException("身高 " + UnitTool.format(UnitTool.round(h))
                            + " 厘米不合理。请填 30–300 之间的数。");
                }
                if (w <= 0 || w > 700) {
                    throw new ToolException("体重 " + UnitTool.format(UnitTool.round(w))
                            + " 公斤不合理。请填 1–700 之间的数。");
                }
                double m = h / 100.0;
                double bmi = w / (m * m);
                String level;
                if (bmi < 18.5) {
                    level = "偏瘦（WHO 标准：低于 18.5）";
                } else if (bmi < 24) {
                    level = "正常（WHO 标准：18.5–23.9）";
                } else if (bmi < 28) {
                    level = "超重（WHO 标准：24–27.9）";
                } else {
                    level = "肥胖（WHO 标准：28 及以上）";
                }
                // 健康体重区间：BMI 18.5–23.9 对应的体重范围
                double lo = 18.5 * m * m;
                double hi = 23.9 * m * m;
                host.uiShow("BMI  " + UnitTool.format(UnitTool.round(bmi)) + "\n"
                        + level + "\n\n"
                        + "该身高对应的合理体重区间  "
                        + UnitTool.format(UnitTool.round(lo)) + " – "
                        + UnitTool.format(UnitTool.round(hi)) + " 公斤\n\n"
                        + "本结果仅供参考，不区分年龄性别，不适用于孕妇、运动员、"
                        + "儿童与青少年。不能作为医学诊断。");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 051 计算器 ----------

    private void calculator(LocalToolsActivity host) {
        EditText expr = host.uiField("表达式", "(1+2)*3 或 sqrt(2) 或 2^10");
        host.uiNote("支持 + - * / % ^、括号、一元正负号，"
                + "函数 sqrt abs round floor ceil，常量 pi e。"
                + "全程用 BigDecimal，0.1+0.2 得到 0.3。");
        host.uiButton("计算", () -> {
            try {
                MathEval.Result r = MathEval.eval(expr.getText().toString());
                StringBuilder sb = new StringBuilder("= ").append(r.text);
                if (r.approximate) {
                    sb.append("\n（结果为近似值：除法除不尽或用了无理运算，"
                            + "已按 12 位有效数字显示）");
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiNote("常用：");
        String[] quick = {"1+1", "(1+2)*3", "2^10", "sqrt(2)", "1/3", "0.1+0.2"};
        for (String q : quick) {
            host.uiButton(q, () -> {
                expr.setText(q);
                try {
                    MathEval.Result r = MathEval.eval(q);
                    host.uiShow(q + " = " + r.text + (r.approximate ? "（近似值）" : ""));
                } catch (ToolException e) {
                    host.uiFail(e);
                }
            });
        }
    }

    // ---------- 052 汇率（真实联网，仅用户主动触发） ----------

    private void exchange(LocalToolsActivity host) {
        host.uiNote("数据来源：Frankfurter（https://frankfurter.dev ，MIT 开源，无需 API 密钥）。"
                + "汇率是各国央行与官方机构公布的参考汇率，属于公开数据。");
        host.uiNote("重要：这不是实时汇率。央行在周末与节假日不发布，因此不会更新；"
                + "它也可能延迟、缺失或被修订。不能用于税务、会计或法律用途。");

        EditText amount = host.uiField("金额", "100");
        Spinner from = host.uiSelect("从", FxRates.labels());
        Spinner to = host.uiSelect("到", FxRates.labels());
        from.setSelection(Math.max(0, FxRates.codes().indexOf("USD")));
        to.setSelection(Math.max(0, FxRates.codes().indexOf("CNY")));

        EditText custom = host.uiField("也可直接填币种代码（留空则用上面的选择）", "如 USD CNY");
        host.uiNote("上面只列了常用币种。服务实际覆盖 165 个币种，"
                + "这里可以填任意 ISO 代码，例如 CNH、KPW、XAU。");

        TextView readout = host.uiOutputBlock();
        readout.setText("还没有获取汇率。只有点下面的按钮才会联网。");

        host.uiButton("获取汇率并换算（会联网）", () -> host.uiBackground(() -> {
            String baseCode = null;
            try {
                String c = custom.getText().toString().trim();
                String targetCode;
                if (!c.isEmpty()) {
                    String[] parts = c.toUpperCase(java.util.Locale.US).split("[,\\s/]+");
                    if (parts.length < 2) {
                        throw new ToolException("手动指定时请填「基准币 目标币」，"
                                + "例如「USD CNY」。只填一个代码无法确定换算方向。");
                    }
                    baseCode = parts[0];
                    targetCode = parts[1];
                } else {
                    baseCode = FxRates.codeOf((String) from.getSelectedItem());
                    targetCode = FxRates.codeOf((String) to.getSelectedItem());
                }
                if (baseCode.equals(targetCode)) {
                    throw new ToolException("基准币种和目标币种都是 " + baseCode
                            + "，不需要换算。请换一个目标币种。");
                }
                BigDecimal amt = MoneyTool.parse(amount.getText().toString(), "金额");

                String url = FxRates.url(baseCode, java.util.Arrays.asList(targetCode));
                String body = HttpGet.fetch(url);
                FxRates.Snapshot snap = FxRates.parseV2(body, baseCode);

                if (!snap.has(targetCode)) {
                    throw new ToolException("服务没有提供 " + baseCode + " → " + targetCode
                            + " 的汇率（可能该币种不被支持）。本次没有取得汇率，"
                            + "不会用其它数字代替。");
                }
                BigDecimal rate = snap.rate(targetCode);
                BigDecimal result = FxRates.convert(amt, rate);

                StringBuilder sb = new StringBuilder();
                sb.append(MoneyTool.plain(amt)).append(' ').append(baseCode)
                        .append("  =  ").append(MoneyTool.plain(result)).append(' ')
                        .append(targetCode).append("\n\n");
                sb.append("汇率  1 ").append(baseCode).append(" = ")
                        .append(rate.stripTrailingZeros().toPlainString()).append(' ')
                        .append(targetCode).append('\n');
                sb.append("汇率日期  ").append(snap.rateDate).append('\n');
                sb.append("数据来源  ").append(FxRates.SOURCE_NAME)
                        .append("（").append(FxRates.SOURCE_URL).append("）\n\n");
                sb.append(FxRates.SOURCE_NOTE);
                final String out = sb.toString();
                host.uiRunOnUiThread(() -> {
                    readout.setText(out);
                    host.uiShow("换算完成（结果见上方）。");
                });
            } catch (ToolException e) {
                final String base = baseCode;
                host.uiRunOnUiThread(() -> {
                    readout.setText("本次没有取得汇率，因此没有可换算的结果。\n\n"
                            + "原因：" + e.getMessage()
                            + (base == null ? "" : "\n请求的基准币种：" + base));
                    host.uiFail(e);
                });
            }
        }));

        host.uiButton("清空结果", () -> readout.setText("已清空。需要时再点「获取汇率并换算」。"));
    }

    // ---------- 053 日期计算 ----------

    private void dateCalc(LocalToolsActivity host) {
        EditText from = host.uiField("开始日期", "2026-01-01 或 2026年1月1日");
        EditText to = host.uiField("结束日期", "2026-12-31（留空则用今天）");
        host.uiButton("计算天数差", () -> {
            try {
                String b = to.getText().toString().trim();
                LocalDate end = b.isEmpty() ? LocalDate.now() : DateDiff.parse(b, "结束日期");
                DateDiff.Result r = DateDiff.between(
                        DateDiff.parse(from.getText().toString(), "开始日期"), end);
                StringBuilder sb = new StringBuilder();
                sb.append(r.days).append(" 天\n")
                        .append("含首含尾 ").append(r.inclusiveDays).append(" 天\n")
                        .append("折合 ").append(r.weeks).append(" 周");
                if (r.remainderDays > 0) {
                    sb.append(" ").append(r.remainderDays).append(" 天");
                }
                sb.append("\n期间工作日 ").append(r.weekdays).append(" 天"
                        + "（只算周一到周五，不含法定节假日与调休）\n");
                if (r.reversed) {
                    sb.append("\n注意：开始日期晚于结束日期，已按绝对值计算。\n");
                }
                sb.append("\n").append(r.from).append("（").append(DateDiff.weekdayName(r.startWeekdayIndex))
                        .append("） → ").append(r.to).append("（")
                        .append(DateDiff.weekdayName(r.endWeekdayIndex)).append("）");
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("加 / 减天数", () -> {
            try {
                LocalDate start = DateDiff.parse(from.getText().toString(), "日期");
                String b = to.getText().toString().trim();
                int d;
                try {
                    d = Integer.parseInt(b);
                } catch (NumberFormatException e) {
                    throw new ToolException("要把「结束日期」这一栏填成天数，例如 30 或 -15。");
                }
                LocalDate result = start.plusDays(d);
                host.uiShow(start + " " + (d >= 0 ? "加 " : "减 ") + Math.abs(d) + " 天\n= " + result
                        + "（" + DateDiff.weekdayName(result.getDayOfWeek().getValue()) + "）");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 054 手持弹幕 ----------

    private void danmaku(LocalToolsActivity host) {
        EditText text = host.uiField("弹幕文字", "加油！");
        Spinner size = host.uiSelect("字号", "大", "很大", "超大");
        host.uiNote("点击后进入全屏大字显示。按返回键退出，屏幕亮度与常亮设置会自动恢复。");
        host.uiButton("开始全屏显示", () -> DanmakuScreen.show(host, text.getText().toString(),
                size.getSelectedItemPosition()));
    }

    // ---------- 056 做个决定 ----------

    private void decide(LocalToolsActivity host) {
        EditText options = host.uiField("候选（每行一个）", "火锅\n烧烤\n日料");
        options.setMinLines(4);
        host.uiButton("帮我决定", () -> {
            try {
                List<String> items = RandomTool.parseItems(options.getText().toString());
                List<String> one = RandomTool.draw(items, 1);
                host.uiShow("就它了：\n\n" + one.get(0) + "\n\n（从 " + items.size()
                        + " 个候选中随机抽取，本地随机，没有倾向性）");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("排出完整顺序", () -> {
            try {
                List<String> items = RandomTool.parseItems(options.getText().toString());
                List<String> all = RandomTool.draw(items, items.size());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < all.size(); i++) {
                    sb.append(i + 1).append(". ").append(all.get(i)).append('\n');
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 059 随机数 ----------

    private void randomNumber(LocalToolsActivity host) {
        EditText min = host.uiField("最小值", "1");
        EditText max = host.uiField("最大值", "100");
        EditText count = host.uiField("生成个数", "1");
        host.uiButton("生成", () -> {
            try {
                long lo = parseLong(min.getText().toString(), "最小值");
                long hi = parseLong(max.getText().toString(), "最大值");
                int n = itemCount(count.getText().toString());
                List<Long> values = RandomTool.integers(lo, hi, n, true);
                StringBuilder sb = new StringBuilder();
                for (long v : values) {
                    sb.append(v).append('\n');
                }
                sb.append("\n区间 [").append(lo).append(", ").append(hi).append("]，带重复，")
                        .append(n).append(" 个。\n这是随机数，不是密码生成器。");
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("不重复抽取", () -> {
            try {
                long lo = parseLong(min.getText().toString(), "最小值");
                long hi = parseLong(max.getText().toString(), "最大值");
                int n = itemCount(count.getText().toString());
                List<Long> values = RandomTool.integers(lo, hi, n, false);
                StringBuilder sb = new StringBuilder();
                for (long v : values) {
                    sb.append(v).append('\n');
                }
                sb.append("\n从 [").append(lo).append(", ").append(hi).append("] 不重复抽取 ")
                        .append(n).append(" 个。");
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    private static long parseLong(String s, String label) throws ToolException {
        String t = s.trim();
        if (t.isEmpty()) {
            throw new ToolException(label + "没有填写。请填一个整数。");
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new ToolException(label + "「" + t + "」不是整数。");
        }
    }

    /**
     * Counts are parsed as long then narrowed; a plain (int) cast would turn 4294967297 into 1.
     * Anything outside the accepted range is reported instead of silently truncated.
     */
    private static int itemCount(String text) throws ToolException {
        long value = parseLong(text, "个数");
        if (value < 1) {
            throw new ToolException("生成个数至少要 1。");
        }
        if (value > 10000) {
            throw new ToolException("一次最多生成 10000 个，请缩小个数。");
        }
        return (int) value;
    }

    // ---------- 060 亲戚称呼 ----------

    private void relatives(LocalToolsActivity host) {
        host.uiNote("共收录 " + cn.xianhua.pocketkit.core.RelativeTitle.coveredCount()
                + " 条关系，最多 4 步（四代以内）。超出范围的组合会明确说「未收录」。");
        Spinner first = host.uiSelect("第一层", "父", "母", "夫", "妻", "子", "女",
                "兄", "弟", "姐", "妹");
        Spinner second = host.uiSelect("第二层（可留空）", "（不选）", "父", "母", "夫", "妻",
                "子", "女", "兄", "弟", "姐", "妹");
        Spinner third = host.uiSelect("第三层（可留空）", "（不选）", "父", "母", "夫", "妻",
                "子", "女", "兄", "弟", "姐", "妹");
        host.uiButton("查询称呼", () -> {
            StringBuilder path = new StringBuilder(String.valueOf(first.getSelectedItem()));
            if (second.getSelectedItemPosition() > 0) {
                path.append(second.getSelectedItem());
            }
            if (third.getSelectedItemPosition() > 0) {
                path.append(third.getSelectedItem());
            }
            try {
                cn.xianhua.pocketkit.core.RelativeTitle.Result r =
                        cn.xianhua.pocketkit.core.RelativeTitle.lookup(path.toString());
                StringBuilder sb = new StringBuilder();
                sb.append("关系：").append(cn.xianhua.pocketkit.core.RelativeTitle
                        .describePath(path.toString())).append('\n');
                sb.append("称呼：").append(r.titleText()).append('\n');
                if (!r.reverse.isEmpty() && !r.unknown) {
                    sb.append("对方称呼我：").append(r.reverseText()).append('\n');
                }
                if (r.unknown) {
                    sb.append("\n这张表没有收录这条关系，为避免误导不给出猜测的称呼。\n");
                    sb.append("已收录的多为常见直系与三代旁系，各地方言写法（姥姥/外婆 等）"
                            + "已并列给出。");
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiNote("也可以直接输入路径，例如「父父」= 爷爷，「母兄子」= 舅表哥。");
        EditText manual = host.uiField("路径", "父父");
        host.uiButton("按路径查询", () -> {
            try {
                String p = manual.getText().toString().trim();
                cn.xianhua.pocketkit.core.RelativeTitle.Result r =
                        cn.xianhua.pocketkit.core.RelativeTitle.lookup(p);
                StringBuilder sb = new StringBuilder();
                sb.append("关系：").append(cn.xianhua.pocketkit.core.RelativeTitle.describePath(p))
                        .append('\n');
                sb.append("称呼：").append(r.titleText()).append('\n');
                if (!r.reverse.isEmpty() && !r.unknown) {
                    sb.append("对方称呼我：").append(r.reverseText()).append('\n');
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 063 时间计算 ----------

    private void timeCalc(LocalToolsActivity host) {
        host.uiNote("输入两个时间点，得到时长；也可以做时长的加减。格式 09:30 或 1:05:30。");
        EditText a = host.uiField("时间 / 时长 A", "09:30");
        EditText b = host.uiField("时间 / 时长 B", "18:05");
        host.uiButton("算时长", () -> {
            try {
                long msA = parseClock(a.getText().toString(), "A");
                long msB = parseClock(b.getText().toString(), "B");
                long diff = msB - msA;
                host.uiShow("A = " + cn.xianhua.pocketkit.core.FocusTimer.formatClock(msA)
                        + "\nB = " + cn.xianhua.pocketkit.core.FocusTimer.formatClock(msB)
                        + "\n\nB - A = " + cn.xianhua.pocketkit.core.FocusTimer.formatClock(Math.abs(diff))
                        + (diff < 0 ? "（B 早于 A）" : "")
                        + "\n可读：" + cn.xianhua.pocketkit.core.FocusTimer.describeDuration(Math.abs(diff))
                        + "\n合计秒数 " + Math.abs(diff) / 1000);
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("时长相加", () -> {
            try {
                long msA = parseClock(a.getText().toString(), "A");
                long msB = parseClock(b.getText().toString(), "B");
                host.uiShow("A + B = " + cn.xianhua.pocketkit.core.FocusTimer.formatClock(msA + msB)
                        + "\n可读：" + cn.xianhua.pocketkit.core.FocusTimer
                        .describeDuration(msA + msB));
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    /** 解析 09:30 / 1:05:30 / 90（分钟）。 */
    static long parseClock(String text, String label) throws ToolException {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            throw new ToolException(label + "没有填写。请用 09:30 或 1:05:30 这样的格式。");
        }
        String[] parts = s.split(":");
        try {
            if (parts.length == 1) {
                return scaled(Long.parseLong(s), 60_000L, label);
            }
            if (parts.length == 2) {
                long h = Long.parseLong(parts[0].trim());
                long m = Long.parseLong(parts[1].trim());
                if (m >= 60) {
                    throw new ToolException(label + " 的分钟数 " + m + " 超过 59。");
                }
                return scaled(Math.addExact(Math.multiplyExact(h, 60L), m), 60_000L, label);
            }
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0].trim());
                long m = Long.parseLong(parts[1].trim());
                long sec = Long.parseLong(parts[2].trim());
                if (m >= 60 || sec >= 60) {
                    throw new ToolException(label + " 的分钟或秒数超过 59。");
                }
                long minutes = Math.addExact(Math.multiplyExact(h, 60L), m);
                return scaled(Math.addExact(Math.multiplyExact(minutes, 60L), sec), 1000L, label);
            }
        } catch (NumberFormatException | ArithmeticException e) {
            // Hours have no upper bound in the input, so the product would wrap long into a
            // negative duration that later renders as 00:00. Exact arithmetic reports it instead.
            throw new ToolException(label + " 的数值太大，无法计算。请把时间控制在合理范围内。");
        }
        throw new ToolException(label + "「" + s + "」格式不对。请用 09:30 或 1:05:30。");
    }

    private static long scaled(long value, long factor, String label) throws ToolException {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException overflow) {
            throw new ToolException(label + " 的数值太大，无法计算。请把时间控制在合理范围内。");
        }
    }

    // ---------- 066 全能单位换算 ----------

    private void units(LocalToolsActivity host) {
        List<UnitTool.Category> cats = UnitTool.everything();
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            names[i] = cats.get(i).name;
        }
        Spinner cat = host.uiSelect("类别（共 " + cats.size() + " 类）", names);
        host.uiNote("选择类别后，「从 / 到」的单位表会跟着变。");
        Spinner from = host.uiSelect("从", "（先选类别）");
        Spinner to = host.uiSelect("到", "（先选类别）");
        EditText value = host.uiField("数值", "1");

        Runnable refreshUnits = () -> {
            UnitTool.Category c = cats.get(cat.getSelectedItemPosition());
            String[] unitNames = new String[c.units.size()];
            for (int i = 0; i < c.units.size(); i++) {
                UnitTool.Unit u = c.units.get(i);
                unitNames[i] = u.name + "（" + u.symbol + "）";
            }
            from.setAdapter(new android.widget.ArrayAdapter<>(host,
                    android.R.layout.simple_spinner_item, unitNames));
            from.setSelection(Math.min(1, unitNames.length - 1));
            to.setAdapter(new android.widget.ArrayAdapter<>(host,
                    android.R.layout.simple_spinner_item, unitNames));
            to.setSelection(0);
        };
        cat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                  android.view.View view, int position, long id) {
                refreshUnits.run();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        refreshUnits.run();

        host.uiButton("换算", () -> {
            try {
                UnitTool.Category c = cats.get(cat.getSelectedItemPosition());
                UnitTool.Unit uFrom = c.units.get(from.getSelectedItemPosition());
                UnitTool.Unit uTo = c.units.get(to.getSelectedItemPosition());
                double v = UnitTool.parseNumber(value.getText().toString(), "数值");
                UnitTool.checkPhysical(c, uFrom, v);
                BigDecimal result = UnitTool.convert(c, uFrom, uTo, v);
                host.uiShow(UnitTool.format(BigDecimal.valueOf(v)) + " " + uFrom.name
                        + " = " + UnitTool.format(result) + " " + uTo.name + "\n\n"
                        + UnitTool.format(BigDecimal.valueOf(v)) + " " + uFrom.symbol
                        + " = " + UnitTool.format(result) + " " + uTo.symbol
                        + "\n\n类别 " + c.name + "，共 " + c.units.size() + " 个单位。");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }
}
