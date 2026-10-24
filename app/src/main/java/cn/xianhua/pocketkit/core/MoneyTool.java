package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 金额与比价的纯计算。
 * 所有金额用 BigDecimal 且保留两位小数，绝不用 double 累加钱。
 *
 * 明确边界：不涉及任何货币汇率换算，只做同一货币下的单位价格比较与金额运算。
 */
public final class MoneyTool {

    /** 金额统一保留 2 位，四舍五入。 */
    public static final int SCALE = 2;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private MoneyTool() {
    }

    /** 解析金额文本，给出可操作错误。接受千分位与全角逗号。 */
    public static BigDecimal parse(String text, String fieldLabel) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException(fieldLabel + "没有填写。请输入金额，例如 19.90。");
        }
        String s = text.trim().replace(",", "").replace("，", "")
                .replace(" ", "").replace("　", "");
        if (s.startsWith("¥") || s.startsWith("￥") || s.startsWith("$")) {
            s = s.substring(1);
        }
        BigDecimal v;
        try {
            v = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ToolException(fieldLabel + "「" + text.trim() + "」不是数字。"
                    + "请输入金额，例如 19.90。");
        }
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 展示用：固定两位小数，带货币符号。 */
    public static String display(BigDecimal v) {
        return "¥" + round(v).toPlainString();
    }

    /** 展示用：不带符号。 */
    public static String plain(BigDecimal v) {
        return round(v).toPlainString();
    }

    // ---------- 单位价格比价（B02） ----------

    public static final class Offer {
        /** 商品描述，例如「A 超市 500g 装」。 */
        public final String label;
        public final BigDecimal price;
        /** 数量，必须 &gt; 0。 */
        public final BigDecimal amount;
        /** 单位名，例如 g / ml / 片。 */
        public final String unit;

        public Offer(String label, BigDecimal price, BigDecimal amount, String unit) {
            this.label = label;
            this.price = price;
            this.amount = amount;
            this.unit = unit;
        }
    }

    public static final class Comparison {
        public final Offer best;
        public final Offer worst;
        /** 每个报价的单价（每 1 个 unit）。 */
        public final BigDecimal[] unitPrices;
        /** 相对最优方案，最差方案贵出的百分比；只有一个报价时为 0。 */
        public final BigDecimal worstExtraPercent;
        /** 按最优单价购买基准数量需要多少钱。 */
        public final BigDecimal baselineTotal;

        Comparison(Offer best, Offer worst, BigDecimal[] unitPrices,
                   BigDecimal worstExtraPercent, BigDecimal baselineTotal) {
            this.best = best;
            this.worst = worst;
            this.unitPrices = unitPrices;
            this.worstExtraPercent = worstExtraPercent;
            this.baselineTotal = baselineTotal;
        }
    }

    /**
     * 比价：算出每个报价的单位价格并排序。
     * 单位不同会直接报错，因为把「每 100g」和「每片」放在一起比是没有意义的。
     */
    public static Comparison compare(java.util.List<Offer> offers) throws ToolException {
        if (offers == null || offers.isEmpty()) {
            throw new ToolException("没有可比较的报价。至少填写一条商品的价格和数量。");
        }
        String unit = offers.get(0).unit;
        for (Offer o : offers) {
            if (o.price == null || o.price.signum() < 0) {
                throw new ToolException("「" + o.label + "」的价格不能是负数。");
            }
            if (o.amount == null || o.amount.signum() <= 0) {
                throw new ToolException("「" + o.label + "」的数量必须大于 0。"
                        + "数量为 0 时算不出单价。");
            }
            if (!o.unit.equals(unit)) {
                throw new ToolException("「" + o.label + "」的单位是「" + o.unit + "」，"
                        + "与「" + unit + "」不同，不能直接比价。"
                        + "请先换算成同一个单位再比较。");
            }
        }
        BigDecimal[] unitPrices = new BigDecimal[offers.size()];
        int bestIdx = 0;
        int worstIdx = 0;
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            unitPrices[i] = o.price.divide(o.amount, 8, RoundingMode.HALF_UP);
            if (unitPrices[i].compareTo(unitPrices[bestIdx]) < 0) {
                bestIdx = i;
            }
            if (unitPrices[i].compareTo(unitPrices[worstIdx]) > 0) {
                worstIdx = i;
            }
        }
        BigDecimal extra = BigDecimal.ZERO;
        if (offers.size() > 1 && unitPrices[bestIdx].signum() > 0) {
            extra = unitPrices[worstIdx].subtract(unitPrices[bestIdx])
                    .divide(unitPrices[bestIdx], 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
        }
        // 以最优单价买「最便宜方案的那个数量」需要多少钱 —— 让比较有直观金额。
        Offer b = offers.get(bestIdx);
        BigDecimal baseline = unitPrices[bestIdx].multiply(b.amount).setScale(SCALE, RoundingMode.HALF_UP);
        return new Comparison(offers.get(bestIdx), offers.get(worstIdx), unitPrices, extra, baseline);
    }

    /** 单价展示：每 100 g/ml 这类常用口径比每 1 单位更好读。 */
    public static BigDecimal perHundred(BigDecimal unitPrice) {
        return unitPrice.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    // ---------- 拆分与均摊 ----------

    /** 把总额按权重均摊到 n 份，余数分给前几份，保证分毫不丢。 */
    public static BigDecimal[] split(BigDecimal total, int parts) throws ToolException {
        if (parts <= 0) {
            throw new ToolException("要分成的份数必须大于 0。");
        }
        if (total == null) {
            throw new ToolException("没有可拆分的金额。");
        }
        BigDecimal cents = total.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        java.math.BigInteger totalCents = cents.toBigIntegerExact();
        java.math.BigInteger[] out = totalCents.divideAndRemainder(java.math.BigInteger.valueOf(parts));
        java.math.BigInteger base = out[0];
        int remainder = out[1].abs().intValue();
        boolean negative = totalCents.signum() < 0;
        BigDecimal[] result = new BigDecimal[parts];
        for (int i = 0; i < parts; i++) {
            java.math.BigInteger centsPart = base;
            if (i < remainder) {
                centsPart = negative ? base.subtract(java.math.BigInteger.ONE)
                        : base.add(java.math.BigInteger.ONE);
            }
            result[i] = new BigDecimal(centsPart).movePointLeft(2).setScale(SCALE);
        }
        return result;
    }
}
