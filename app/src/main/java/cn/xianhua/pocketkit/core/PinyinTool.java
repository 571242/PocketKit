package cn.xianhua.pocketkit.core;

import com.ibm.icu.text.Transliterator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 汉字转拼音。基于 ICU4J 的 Han–Latin 音译规则（打包在 APK 内，完全离线）。
 *
 * 关于准确性，界面必须如实说明：
 * - 多音字按 ICU 的通用读音处理，会给出「重庆 -> chóng qìng」这类常见词组的正确读音，
 *   但脱离语境的孤立多音字（如「行」「重」）无法保证是你要的那个读音。
 * - 声调以带调符号标出（ā á ǎ à）。
 *
 * 已知限制：ICU 的规则只覆盖基本汉字区；生僻字、异体字可能返回原字，此时列出而不假装成功。
 */
public final class PinyinTool {

    private static final Transliterator HAN_LATIN = Transliterator.getInstance("Han-Latin");
    private static final Transliterator HAN_LATIN_PLAIN = Transliterator.getInstance("Han-Latin; Latin-ASCII");

    public static final class Result {
        /** 带声调拼音，音节用空格分开。 */
        public final String toned;
        /** 不带声调、已转 ASCII 的拼音。 */
        public final String plain;
        /** 首字母缩写，例如「你好世界」-> nhsj。 */
        public final String initials;
        /** 没有转出拼音、被原样保留的汉字。 */
        public final String unconverted;

        Result(String toned, String plain, String initials, String unconverted) {
            this.toned = toned;
            this.plain = plain;
            this.initials = initials;
            this.unconverted = unconverted;
        }
    }

    private PinyinTool() {
    }

    public static Result convert(String text) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有可转换的内容。请输入汉字，例如「你好世界」。");
        }
        String toned = normalize(HAN_LATIN.transliterate(text));
        String plain = normalize(HAN_LATIN_PLAIN.transliterate(text));

        StringBuilder unconverted = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c) && unconverted.indexOf(String.valueOf(c)) < 0) {
                unconverted.append(c);
            }
        }
        return new Result(toned, plain, initialsOf(plain), unconverted.toString());
    }

    /** 取每个音节的首字母；非拉丁字符跳过。 */
    static String initialsOf(String plain) {
        StringBuilder sb = new StringBuilder();
        boolean atStart = true;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetter(c) && c < 128) {
                if (atStart) {
                    sb.append(c);
                    atStart = false;
                }
            } else {
                atStart = true;
            }
        }
        return sb.toString();
    }

    /** 折叠 ICU 输出里的多余空格，去掉音调符号与 IPA 括号等残渣。 */
    private static String normalize(String s) {
        String out = s.replace('\u0304', ' ').trim();
        out = out.replaceAll("\\s+", " ");
        return out;
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 逐字给出拼音，用于界面逐字展示。返回与输入等长的列表（非汉字位置为原字符）。 */
    public static List<String> perCharacter(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            String single = String.valueOf(text.charAt(i));
            if (isHan(text.charAt(i))) {
                out.add(normalize(HAN_LATIN.transliterate(single)));
            } else {
                out.add(single);
            }
        }
        return out;
    }

    /** 输入里出现过的非汉字类别的字符种类（用于界面提示「只转换了汉字」）。 */
    public static Set<Character> nonHanKinds(String text) {
        Set<Character> kinds = new LinkedHashSet<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isHan(c)) {
                kinds.add(c);
            }
        }
        return kinds;
    }
}
