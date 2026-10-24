package cn.xianhua.pocketkit.device;

import java.util.Locale;

/**
 * 平仄与对仗分析。纯逻辑，可脱机自检。
 *
 * 平仄判定规则（传统诗韵的常用简化）：
 *  - 一声、二声为「平」（阴平、阳平）
 *  - 三声、四声为「仄」（上声、去声）
 *
 * 已知限制（必须在界面上如实说明）：多音字按 ICU 的常用读音取调，
 * 脱离语境的孤立多音字无法保证取到你要的那个读音，因此平仄判断可能出错。
 */
public final class Pingze {

    private Pingze() {
    }

    public static final String LEVEL = "平";
    public static final String OBLIQUE = "仄";
    /** 无法判定（非汉字、或该字没转出带调拼音）。 */
    public static final String UNKNOWN = "？";

    /**
     * 从带调拼音里取声调：1..4，取不到返回 0。
     * 依据声调符号所在的韵母元音判断，不依赖任何外部数据。
     */
    public static int toneOf(String toned) {
        if (toned == null || toned.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < toned.length(); i++) {
            char c = toned.charAt(i);
            int tone = toneFromChar(c);
            if (tone > 0) {
                return tone;
            }
        }
        return 0;
    }

    /** 单个带调元音字符 -> 声调。 */
    public static int toneFromChar(char c) {
        // 一声：长音符
        if ("āēīōūǖĀĒĪŌŪǕ".indexOf(c) >= 0) {
            return 1;
        }
        // 二声：锐音符
        if ("áéíóúǘÁÉÍÓÚǗ".indexOf(c) >= 0) {
            return 2;
        }
        // 三声：抑扬符
        if ("ǎěǐǒǔǚǍĚǏǑǓǙ".indexOf(c) >= 0) {
            return 3;
        }
        // 四声：抑音符
        if ("àèìòùǜÀÈÌÒÙǛ".indexOf(c) >= 0) {
            return 4;
        }
        return 0;
    }

    /** 声调 -> 平仄。0（未知）返回 {@link #UNKNOWN}。 */
    public static String pingzeOfTone(int tone) {
        switch (tone) {
            case 1:
            case 2:
                return LEVEL;
            case 3:
            case 4:
                return OBLIQUE;
            default:
                return UNKNOWN;
        }
    }

    /** 声调的中文名，用于界面显示。 */
    public static String toneName(int tone) {
        switch (tone) {
            case 1: return "一声（阴平）";
            case 2: return "二声（阳平）";
            case 3: return "三声（上声）";
            case 4: return "四声（去声）";
            default: return "无法判定（多音字或非汉字）";
        }
    }

    /** 一行字的平仄串，例如「平平仄仄平」。 */
    public static String pattern(String tonedPinyin, String original) {
        StringBuilder sb = new StringBuilder();
        String[] syllables = tonedPinyin == null ? new String[0] : tonedPinyin.trim().split("\\s+");
        int syllableIndex = 0;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN) {
                continue;
            }
            String syllable = syllableIndex < syllables.length ? syllables[syllableIndex] : "";
            syllableIndex++;
            sb.append(pingzeOfTone(toneOf(syllable)));
        }
        return sb.toString();
    }

    /**
     * 对仗检查结果。
     * 逐条给出结论与具体位置，而不是笼统的「不合格」。
     */
    public static final class Report {
        public final int upperLength;
        public final int lowerLength;
        public final String upperPattern;
        public final String lowerPattern;
        public final boolean sameLength;
        /** 关键位置（偶数位与末字）平仄是否相对。 */
        public final boolean oppositionOk;
        /** 上联末字应为仄；null 表示无法判定。 */
        public final Boolean upperEndsOblique;
        /** 下联末字应为平；null 表示无法判定。 */
        public final Boolean lowerEndsLevel;
        public final String repeatedChars;
        public final String detail;

        Report(int upperLength, int lowerLength, String upperPattern, String lowerPattern,
               boolean sameLength, boolean oppositionOk, Boolean upperEndsOblique,
               Boolean lowerEndsLevel, String repeatedChars, String detail) {
            this.upperLength = upperLength;
            this.lowerLength = lowerLength;
            this.upperPattern = upperPattern;
            this.lowerPattern = lowerPattern;
            this.sameLength = sameLength;
            this.oppositionOk = oppositionOk;
            this.upperEndsOblique = upperEndsOblique;
            this.lowerEndsLevel = lowerEndsLevel;
            this.repeatedChars = repeatedChars;
            this.detail = detail;
        }

        public boolean allPassed() {
            return sameLength && oppositionOk
                    && Boolean.TRUE.equals(upperEndsOblique)
                    && Boolean.TRUE.equals(lowerEndsLevel);
        }
    }

    /**
     * 分析上下联。
     *
     * @param upperText 上联原文
     * @param upperToned 上联带调拼音（空格分隔音节）
     * @param lowerText 下联原文
     * @param lowerToned 下联带调拼音
     */
    public static Report analyse(String upperText, String upperToned, String lowerText, String lowerToned) {
        String upperPattern = pattern(upperToned, upperText);
        String lowerPattern = pattern(lowerToned, lowerText);
        boolean sameLength = upperPattern.length() == lowerPattern.length();

        StringBuilder detail = new StringBuilder();
        boolean oppositionOk = true;
        if (!sameLength) {
            detail.append("字数不等：上联 ").append(upperPattern.length())
                    .append(" 字，下联 ").append(lowerPattern.length()).append(" 字。\n");
            oppositionOk = false;
        } else {
            // 传统上只严格要求「偶数字位」与「末字」平仄相对，这里按同一口径检查并说明。
            StringBuilder mismatch = new StringBuilder();
            for (int i = 0; i < upperPattern.length(); i++) {
                boolean mustOppose = (i + 1) % 2 == 0 || i == upperPattern.length() - 1;
                if (!mustOppose) {
                    continue;
                }
                String u = String.valueOf(upperPattern.charAt(i));
                String l = String.valueOf(lowerPattern.charAt(i));
                if (UNKNOWN.equals(u) || UNKNOWN.equals(l)) {
                    continue;
                }
                if (u.equals(l)) {
                    oppositionOk = false;
                    mismatch.append("第 ").append(i + 1).append(" 字（")
                            .append(upperText.charAt(hanIndexToRaw(upperText, i))).append("/")
                            .append(lowerText.charAt(hanIndexToRaw(lowerText, i)))
                            .append("）都是「").append(u).append("」");
                    mismatch.append("；");
                }
            }
            if (oppositionOk) {
                detail.append("偶数字位与末字的平仄均相对。\n");
            } else {
                detail.append("以下关键位置没有做到平仄相对：").append(mismatch).append('\n');
            }
        }

        Boolean upperEndsOblique = null;
        if (upperPattern.length() > 0) {
            char last = upperPattern.charAt(upperPattern.length() - 1);
            if (last == OBLIQUE.charAt(0)) {
                upperEndsOblique = Boolean.TRUE;
            } else if (last == LEVEL.charAt(0)) {
                upperEndsOblique = Boolean.FALSE;
                detail.append("上联末字是「平」，传统上上联应以「仄」收尾。\n");
            }
        }
        Boolean lowerEndsLevel = null;
        if (lowerPattern.length() > 0) {
            char last = lowerPattern.charAt(lowerPattern.length() - 1);
            if (last == LEVEL.charAt(0)) {
                lowerEndsLevel = Boolean.TRUE;
            } else if (last == OBLIQUE.charAt(0)) {
                lowerEndsLevel = Boolean.FALSE;
                detail.append("下联末字是「仄」，传统上下联应以「平」收尾（仄起平收）。\n");
            }
        }

        String repeated = repeatedChars(upperText);
        if (!repeated.isEmpty()) {
            detail.append("上联中重复出现的字：").append(repeated)
                    .append("（对联一般避免同字相对，可留意）。\n");
        }

        return new Report(upperPattern.length(), lowerPattern.length(), upperPattern, lowerPattern,
                sameLength, oppositionOk, upperEndsOblique, lowerEndsLevel, repeated, detail.toString());
    }

    /** 找出重复出现的汉字（去重、按出现顺序）。 */
    public static String repeatedChars(String text) {
        StringBuilder seen = new StringBuilder();
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN) {
                continue;
            }
            if (seen.indexOf(String.valueOf(c)) >= 0) {
                if (repeated.indexOf(String.valueOf(c)) < 0) {
                    repeated.append(c);
                }
            } else {
                seen.append(c);
            }
        }
        return repeated.toString();
    }

    /** 把「第 n 个汉字」的下标换算回原字符串下标（跳过标点与空格）。 */
    public static int hanIndexToRaw(String text, int hanIndex) {
        int count = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                count++;
                if (count == hanIndex) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(text.length() - 1, hanIndex));
    }

    /** 供界面展示的一行摘要。 */
    public static String summaryLine(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "（无）";
        }
        return pattern;
    }

    static String formatPercent(double ratio) {
        return String.format(Locale.US, "%.0f%%", ratio * 100);
    }
}
