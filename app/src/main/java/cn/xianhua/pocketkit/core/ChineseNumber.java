package cn.xianhua.pocketkit.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 数字转中文读数，以及人民币金额大写。
 *
 * 用自己实现的算法而不是 ICU 的 RBNF：RBNF 的简体中文规则在中间位零上给的是
 * 「一百〇一」（台湾写法），大陆通行写法是「一百零一」；金额大写 RBNF 也不提供。
 *
 * 支持的整数范围：到「亿亿」段（20 位）。再大直接报错，不输出错误读法。
 */
public final class ChineseNumber {

    private static final char[] DIGITS = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};
    private static final char[] UPPER = {'零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖'};
    private static final String[] LOW_UNITS = {"千", "百", "十", ""};
    private static final String[] UP_UNITS = {"仟", "佰", "拾", ""};
    /** 每 4 位一段，段名。索引即段号。 */
    private static final String[] SECTIONS = {"", "万", "亿", "万亿", "亿亿", "万亿亿", "亿亿亿"};
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10000);

    private ChineseNumber() {
    }

    // ---------- 读数（普通中文） ----------

    /** 整数转中文读数：1234 -> 一千二百三十四；-5 -> 负五。 */
    public static String read(BigInteger value) throws ToolException {
        if (value == null) {
            throw new ToolException("没有可转换的数字。");
        }
        int sections = sectionCount(value.abs());
        if (sections > SECTIONS.length) {
            throw new ToolException("数字太大，超出「亿亿」段（最多 20 位整数）。"
                    + "请去掉多余的位数再试。");
        }
        if (value.signum() == 0) {
            return "零";
        }
        StringBuilder sb = new StringBuilder();
        if (value.signum() < 0) {
            sb.append('负');
        }
        sb.append(intText(value.abs(), DIGITS, LOW_UNITS, true));
        return sb.toString();
    }

    /** 小数转中文读数：3.14 -> 三点一四。小数部分逐位读，不做四舍五入。 */
    public static String read(BigDecimal value) throws ToolException {
        if (value == null) {
            throw new ToolException("没有可转换的数字。");
        }
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0) {
            return read(v.toBigIntegerExact());
        }
        String plain = v.toPlainString();
        int dot = plain.indexOf('.');
        String intPart = plain.substring(0, dot);
        String fracPart = plain.substring(dot + 1);
        if (intPart.equals("-0")) {
            intPart = "0";
        }
        StringBuilder sb = new StringBuilder();
        // The sign is emitted here, so the integer part must be passed unsigned: read(BigInteger)
        // adds its own 负 and would otherwise produce 负负三点一四.
        if (v.signum() < 0) {
            sb.append('负');
            intPart = intPart.startsWith("-") ? intPart.substring(1) : intPart;
        }
        sb.append(read(new BigInteger(intPart)));
        sb.append('点');
        for (int i = 0; i < fracPart.length(); i++) {
            sb.append(DIGITS[fracPart.charAt(i) - '0']);
        }
        return sb.toString();
    }

    /** 解析用户输入的数字，给出可操作的错误。 */
    public static BigDecimal parse(String text, String fieldLabel) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException(fieldLabel + "没有填写。请输入一个数字，例如 1234.56。");
        }
        String s = text.trim().replace(",", "").replace("，", "").replace(" ", "")
                .replace("　", "");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ToolException(fieldLabel + "「" + text.trim() + "」不是数字。"
                    + "可以用整数、小数和负号，例如 2024、3.14、-50。");
        }
    }

    // ---------- 金额大写 ----------

    /**
     * 人民币金额大写：1234.56 -> 壹仟贰佰叁拾肆元伍角陆分。
     * 金额先按「分」四舍五入，避免 0.005 元这类输入给出无法支付的金额。
     */
    public static String moneyUpper(BigDecimal amount) throws ToolException {
        return money(amount, true);
    }

    /** 金额中文小写：1234.56 -> 一千二百三十四元五角六分。 */
    public static String moneyLower(BigDecimal amount) throws ToolException {
        return money(amount, false);
    }

    private static String money(BigDecimal amount, boolean upper) throws ToolException {
        if (amount == null) {
            throw new ToolException("没有可转换的金额。");
        }
        BigDecimal v = amount.setScale(2, RoundingMode.HALF_UP);
        if (v.abs().compareTo(new BigDecimal("9999999999999999.99")) > 0) {
            throw new ToolException("金额超出可表示范围。金额大写最多支持 16 位整数（约 9999 万亿）。");
        }
        boolean negative = v.signum() < 0;
        v = v.abs();
        BigInteger yuan = v.toBigInteger();
        int cents = v.subtract(new BigDecimal(yuan)).movePointRight(2).intValueExact();
        int jiao = cents / 10;
        int fen = cents % 10;

        char[] digits = upper ? UPPER : DIGITS;
        String[] units = upper ? UP_UNITS : LOW_UNITS;
        // 金融大写不省开头的「壹」：10 元必须写「壹拾元」，写成「拾元」会被银行退回。
        boolean leadingShorthand = !upper;

        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('负');
        }
        if (yuan.signum() > 0) {
            sb.append(intText(yuan, digits, units, leadingShorthand)).append("元");
        }
        if (jiao == 0 && fen == 0) {
            if (sb.length() == 0 || (negative && sb.length() == 1)) {
                sb.append("零元");
            }
            sb.append("整");
            return sb.toString();
        }
        if (jiao > 0) {
            sb.append(digits[jiao]).append('角');
        } else if (yuan.signum() > 0) {
            // 1.05 -> 壹元零伍分，中间这个零不能省
            sb.append(digits[0]);
        }
        if (fen > 0) {
            sb.append(digits[fen]).append('分');
        } else {
            // 1.50 -> 壹元伍角整
            sb.append("整");
        }
        return sb.toString();
    }

    // ---------- 内部实现 ----------

    private static int sectionCount(BigInteger abs) {
        int n = 1;
        BigInteger t = abs;
        while (t.compareTo(TEN_THOUSAND) >= 0) {
            t = t.divide(TEN_THOUSAND);
            n++;
        }
        return n;
    }

    /** 整数读数通用实现：digits 决定普通/大写字形，units 决定 十/拾 这套单位。 */
    private static String intText(BigInteger value, char[] digits, String[] units,
                                  boolean leadingShorthand) throws ToolException {
        if (value.signum() == 0) {
            return String.valueOf(digits[0]);
        }
        List<Integer> sections = new ArrayList<>();
        BigInteger t = value;
        while (t.signum() > 0) {
            sections.add(t.mod(TEN_THOUSAND).intValue());
            t = t.divide(TEN_THOUSAND);
        }
        if (sections.size() > SECTIONS.length) {
            throw new ToolException("数字太大，超出「亿亿」段（最多 20 位整数）。");
        }
        StringBuilder out = new StringBuilder();
        boolean zeroGap = false;
        for (int i = sections.size() - 1; i >= 0; i--) {
            int s = sections.get(i);
            if (s == 0) {
                if (out.length() > 0) {
                    zeroGap = true;
                }
                continue;
            }
            // 段内高位是 0（如 一亿零一万）或中间有空段时，补一个「零」。
            if (out.length() > 0 && (zeroGap || s < 1000)) {
                out.append(digits[0]);
            }
            zeroGap = false;
            out.append(sectionText(s, out.length() == 0 && leadingShorthand, digits, units));
            if (i > 0) {
                out.append(SECTIONS[i]);
            }
        }
        return out.toString();
    }

    /** 读一个 1–9999 的段。leading 为真且段值 < 20 时，开头的「一」省掉（十、十二）。 */
    private static String sectionText(int s, boolean leading, char[] digits, String[] units) {
        int[] weights = {1000, 100, 10, 1};
        StringBuilder sb = new StringBuilder();
        boolean zeroPending = false;
        int rest = s;
        for (int k = 0; k < 4; k++) {
            int d = rest / weights[k];
            rest %= weights[k];
            if (d == 0) {
                if (sb.length() > 0 && rest > 0) {
                    zeroPending = true;
                }
                continue;
            }
            if (zeroPending) {
                sb.append(digits[0]);
                zeroPending = false;
            }
            // 一十 -> 十（仅在整串最前面时）
            if (leading && sb.length() == 0 && k == 2 && d == 1 && s < 20) {
                sb.append(units[k]);
            } else {
                sb.append(digits[d]).append(units[k]);
            }
        }
        return sb.toString();
    }
}
