package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迷你英文生成（042）。
 *
 * 这是纯本地的字符替换：把常见英文单词里的字母换成同形或形近的符号/数字，
 * 生成「h3ll0 w0rld」这类小字号风格文字。不调用任何在线服务，不模仿具体品牌。
 *
 * 明确边界：本工具不是翻译，也不改变原意；它只做字形替换。
 * 替换后的文本在很多字体下会显得难读，这是这类玩法的固有代价。
 */
public final class MiniStyle {

    /** 每个字母可用的候选替换。顺序即风格优先级。 */
    private static final String[][] MAP = {
            {"a", "@", "4"},
            {"b", "8"},
            {"c", "("},
            {"e", "3"},
            {"g", "9"},
            {"i", "1", "!"},
            {"l", "1", "|"},
            {"o", "0"},
            {"s", "5", "$"},
            {"t", "7"},
            {"z", "2"},
    };

    public enum Strength {
        LIGHT("轻度", 3),
        MEDIUM("中度", 2),
        HEAVY("重度", 1);

        public final String label;
        /** 每隔几个可替换字母替换一次：数字越小替换越密。 */
        public final int everyNth;

        Strength(String label, int everyNth) {
            this.label = label;
            this.everyNth = everyNth;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Result {
        public final String text;
        /** 实际替换了多少个字母。 */
        public final int replaced;
        /** 可被替换的字母总数（用于说明覆盖率）。 */
        public final int replaceable;

        Result(String text, int replaced, int replaceable) {
            this.text = text;
            this.replaced = replaced;
            this.replaceable = replaceable;
        }
    }

    private MiniStyle() {
    }

    /**
     * 生成迷你风格文本。
     *
     * @param keepFirstLetter 是否保留每个单词的首字母原样（更可读，推荐）
     */
    public static Result stylize(String text, Strength strength, boolean keepFirstLetter)
            throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有可转换的内容。请输入英文文本，例如 hello world。");
        }
        StringBuilder out = new StringBuilder(text.length());
        int replaced = 0;
        int replaceable = 0;
        int sinceLastReplace = 0;
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char raw = text.charAt(i);
            char lower = Character.toLowerCase(raw);
            boolean isAsciiLetter = lower >= 'a' && lower <= 'z';
            String replacement = firstReplacement(lower);
            if (replacement != null) {
                replaceable++;
            }
            boolean isWordStart = atWordStart && isAsciiLetter;
            boolean eligible = replacement != null && !(keepFirstLetter && isWordStart);
            if (eligible) {
                sinceLastReplace++;
                if (sinceLastReplace >= strength.everyNth) {
                    boolean upper = Character.isUpperCase(raw);
                    out.append(upper ? replacement.toUpperCase(Locale.ROOT) : replacement);
                    replaced++;
                    sinceLastReplace = 0;
                    atWordStart = false;
                    continue;
                }
            }
            out.append(raw);
            atWordStart = !isAsciiLetter && !Character.isDigit(raw);
        }
        return new Result(out.toString(), replaced, replaceable);
    }

    /** 全部替换（最激进），用于「能看到哪些字母有替换」的预览。 */
    public static Result all(String text) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有可转换的内容。请输入英文文本。");
        }
        StringBuilder out = new StringBuilder(text.length());
        int replaced = 0;
        int replaceable = 0;
        for (int i = 0; i < text.length(); i++) {
            char raw = text.charAt(i);
            char lower = Character.toLowerCase(raw);
            String replacement = firstReplacement(lower);
            if (replacement == null) {
                out.append(raw);
                continue;
            }
            replaceable++;
            out.append(Character.isUpperCase(raw)
                    ? replacement.toUpperCase(Locale.ROOT) : replacement);
            replaced++;
        }
        return new Result(out.toString(), replaced, replaceable);
    }

    private static String firstReplacement(char lower) {
        for (String[] entry : MAP) {
            if (entry[0].charAt(0) == lower) {
                return entry[1];
            }
        }
        return null;
    }

    /** 可用于替换的全部字母，供界面说明。 */
    public static String coveredLetters() {
        StringBuilder sb = new StringBuilder();
        for (String[] e : MAP) {
            sb.append(e[0]);
        }
        return sb.toString();
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    /** 词数统计，用于报告处理规模。 */
    public static int wordCount(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = WORD.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 供测试：所有可替换字母集合。 */
    static Set<String> replaceableLetters() {
        Set<String> s = new LinkedHashSet<>();
        for (String[] e : MAP) {
            s.add(e[0]);
        }
        return s;
    }

    /** 供测试与界面预览：所有候选替换符号。 */
    static List<String> allReplacements() {
        List<String> s = new ArrayList<>();
        for (String[] e : MAP) {
            s.addAll(Arrays.asList(e).subList(1, e.length));
        }
        return s;
    }
}
