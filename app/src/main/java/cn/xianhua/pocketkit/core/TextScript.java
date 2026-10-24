package cn.xianhua.pocketkit.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数字与字母的 Unicode 变体转换：上标、下标、小型大写、带圈、全角。
 *
 * 只做码点映射，依据 Unicode 已编码的兼容字符，不自造字符。
 *
 * 明确边界（界面必须写明）：
 * 1. 这些字符在部分字体/应用里会显示成方块（缺字形），这是字体问题，不是转换失败。
 * 2. 上标、下标没有覆盖全部字母——Unicode 本身没为 q 这类字母编码上标字形。
 *    遇到没有对应字符的输入，本工具会把原字符原样保留，并在 unmapped 里列出，
 *    不悄悄跳过、也不悄悄替换。
 * 3. 复制出去的是普通 Unicode 文本，不是真正的富文本格式。
 */
public final class TextScript {

    private static final Map<Character, Character> SUPER = new LinkedHashMap<>();
    private static final Map<Character, Character> SUB = new LinkedHashMap<>();
    private static final Map<Character, Character> SMALL_CAPS = new LinkedHashMap<>();
    private static final Map<Character, Character> CIRCLED = new LinkedHashMap<>();

    static {
        buildSuperscript();
        buildSubscript();
        buildSmallCaps();
        buildCircled();
    }

    private static void buildSuperscript() {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        for (int i = 0; i < 10; i++) {
            SUPER.put((char) ('0' + i), digits.charAt(i));
        }
        // 拉丁小写：只放 Unicode 真有上标字形的那些
        String lower = "abcdefghijklmnoprstuvwxyz";
        String lowerSup = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ";
        for (int i = 0; i < lower.length(); i++) {
            SUPER.put(lower.charAt(i), lowerSup.charAt(i));
        }
        String upper = "ABDEGHIJKLMNOPRTUVW";
        String upperSup = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ";
        for (int i = 0; i < upper.length(); i++) {
            SUPER.put(upper.charAt(i), upperSup.charAt(i));
        }
        SUPER.put('+', '⁺');
        SUPER.put('-', '⁻');
        SUPER.put('=', '⁼');
        SUPER.put('(', '⁽');
        SUPER.put(')', '⁾');
    }

    private static void buildSubscript() {
        String digits = "₀₁₂₃₄₅₆₇₈₉";
        for (int i = 0; i < 10; i++) {
            SUB.put((char) ('0' + i), digits.charAt(i));
        }
        String lower = "aehijklmnoprstuvx";
        String lowerSub = "ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ";
        for (int i = 0; i < lower.length(); i++) {
            SUB.put(lower.charAt(i), lowerSub.charAt(i));
        }
        SUB.put('+', '₊');
        SUB.put('-', '₋');
        SUB.put('=', '₌');
        SUB.put('(', '₍');
        SUB.put(')', '₎');
    }

    private static void buildSmallCaps() {
        String lower = "abcdefghijklmnopqrstuvwxyz";
        // 逐对显式写入：Unicode 的小型大写码点分布零散，且有几个（如 x）实际借用了
        // 西里尔字母 х，不能靠 toLowerCase 之类的批量技巧得到。
        char[] small = {'ᴀ', 'ʙ', 'ᴄ', 'ᴅ', 'ᴇ', 'ꜰ', 'ɢ', 'ʜ', 'ɪ', 'ᴊ', 'ᴋ', 'ʟ',
                'ᴍ', 'ɴ', 'ᴏ', 'ᴘ', 'ǫ', 'ʀ', 'ꜱ', 'ᴛ', 'ᴜ', 'ᴠ', 'ᴡ', 'х', 'ʏ', 'ᴢ'};
        for (int i = 0; i < 26; i++) {
            SMALL_CAPS.put(lower.charAt(i), small[i]);
        }
    }

    private static void buildCircled() {
        // ⓪ U+24EA；①–⑨ U+2460–U+2468；⑩–⑳ U+2469–U+2473
        CIRCLED.put('0', '\u24EA');
        for (int i = 1; i <= 9; i++) {
            CIRCLED.put(Character.forDigit(i, 10), (char) (0x2460 + i - 1));
        }
        // ⑩–⑳ 需要看上下文（两个数字字符），由 convert 特判处理，不放在单字符表里。
        String circLower = "ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ";
        for (int i = 0; i < 26; i++) {
            CIRCLED.put((char) ('a' + i), circLower.charAt(i));
        }
        String circUpper = "ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏ";
        for (int i = 0; i < 26; i++) {
            CIRCLED.put((char) ('A' + i), circUpper.charAt(i));
        }
    }

    /** 带圈数字 10–20 的独立码点 U+2469–U+2473。 */
    private static final char[] CIRCLED_TEENS =
            {'⑩', '⑪', '⑫', '⑬', '⑭', '⑮', '⑯', '⑰', '⑱', '⑲', '⑳'};

    public static final class Result {
        public final String text;
        /** 没有对应变体字符、被原样保留的字符（去重）。 */
        public final String unmapped;

        Result(String text, String unmapped) {
            this.text = text;
            this.unmapped = unmapped;
        }
    }

    private TextScript() {
    }

    public enum Style {
        SUPERSCRIPT("上标"),
        SUBSCRIPT("下标"),
        SMALL_CAPS("小型大写"),
        CIRCLED("带圈"),
        FULL_WIDTH("全角");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static Result convert(String text, Style style) throws ToolException {
        if (text == null || text.isEmpty()) {
            throw new ToolException("没有可转换的内容。请输入要转换的文本。");
        }
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder miss = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (style == Style.FULL_WIDTH) {
                out.append(c > 0x20 && c < 0x7F ? (char) (c + 0xFEE0) : c);
                continue;
            }
            if (style == Style.CIRCLED && c >= '0' && c <= '9') {
                // 先量出整段连续数字，再决定：10–20 用单个带圈码点，其余逐位。
                // 必须整段判定，否则 "0123456789" 里的 "12" 会被误读成 ⑫。
                int j = i;
                while (j < text.length() && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                    j++;
                }
                String run = text.substring(i, j);
                if (run.length() == 2) {
                    int n = (run.charAt(0) - '0') * 10 + (run.charAt(1) - '0');
                    if (n >= 10 && n <= 20) {
                        out.append(CIRCLED_TEENS[n - 10]);
                        i = j - 1;
                        continue;
                    }
                }
                for (int k = i; k < j; k++) {
                    out.append(CIRCLED.get(text.charAt(k)));
                }
                i = j - 1;
                continue;
            }
            Character mapped = table(style).get(c);
            if (mapped != null) {
                out.append(mapped);
            } else {
                out.append(c);
                if (!Character.isWhitespace(c) && miss.indexOf(String.valueOf(c)) < 0) {
                    miss.append(c);
                }
            }
        }
        return new Result(out.toString(), miss.toString());
    }

    private static Map<Character, Character> table(Style style) {
        switch (style) {
            case SUBSCRIPT:
                return SUB;
            case SMALL_CAPS:
                return SMALL_CAPS;
            case CIRCLED:
                return CIRCLED;
            case FULL_WIDTH:
            case SUPERSCRIPT:
            default:
                return SUPER;
        }
    }

    /** 该风格能覆盖的普通字符，供界面提示与测试。 */
    public static String coverage(Style style) {
        StringBuilder sb = new StringBuilder();
        for (Character c : table(style).keySet()) {
            sb.append(c);
        }
        return sb.toString();
    }
}
