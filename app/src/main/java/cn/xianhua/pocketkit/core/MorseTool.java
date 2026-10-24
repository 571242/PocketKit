package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摩斯电码编解码（国际摩尔斯电码 ITU-R M.1677-1）。
 *
 * 明确边界：国际码只覆盖拉丁字母、数字和少量标点。汉字没有国际摩斯码，
 * 中文需要先转拼音（另见 PinyinTool）；电报中文码（四位数字表）本工具不提供，
 * 因为那份表只在特定电报系统内有意义，贸然给出会误导。
 */
public final class MorseTool {

    /** 字母与数字，顺序固定便于测试。 */
    private static final Map<Character, String> TABLE = new LinkedHashMap<>();

    static {
        String[] letters = {
                "A.-", "B-...", "C-.-.", "D-..", "E.", "F..-.", "G--.", "H....",
                "I..", "J.---", "K-.-", "L.-..", "M--", "N-.", "O---", "P.--.",
                "Q--.-", "R.-.", "S...", "T-", "U..-", "V...-", "W.--", "X-..-",
                "Y-.--", "Z--.."
        };
        for (String e : letters) {
            TABLE.put(e.charAt(0), e.substring(1));
        }
        String[] digits = {
                "0-----", "1.----", "2..---", "3...--", "4....-",
                "5.....", "6-....", "7--...", "8---..", "9----."
        };
        for (String d : digits) {
            TABLE.put(d.charAt(0), d.substring(1));
        }
        String[] punct = {
                "..-.-.-", ",--..--", "?..--..", "' .----.".replace(" ", ""),
                "!-.-.--", "/-..-.", "(-.--.", ")-.--.-", "&-.-...", ":-...",
                ";-.-.-.", "=-...-", "+.-.-.", "\"-.-..-.", "$...-..-", "@.--.-."
        };
        for (String p : punct) {
            TABLE.put(p.charAt(0), p.substring(1));
        }
    }

    private static final Map<String, Character> REVERSE = new LinkedHashMap<>();

    static {
        for (Map.Entry<Character, String> e : TABLE.entrySet()) {
            REVERSE.put(e.getValue(), e.getKey());
        }
    }

    private MorseTool() {
    }

    public static class Result {
        public final String text;
        /** 无法编码的字符（原文里保留原样，并在这里列出）。 */
        public final String unsupported;
        /** 编码后有多少个点划符号，用于估算播放时长。 */
        public final int symbols;
        /** 按标准 20 WPM 估算的播放秒数（不含间隔的粗略值）。 */
        public final double wpmSeconds;

        Result(String text, String unsupported, int symbols) {
            this.text = text;
            this.unsupported = unsupported;
            this.symbols = symbols;
            // 标准 20 WPM：一点 = 60 ms，一划 = 3 点 = 180 ms，符号间隔 60 ms，字母间隔 180 ms
            this.wpmSeconds = symbols * 0.12;
        }
    }

    /** 文本转摩斯：字母之间空格，单词之间用 / 分开（ITU 惯例）。 */
    public static Result encode(String text) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有可编码的内容。请输入英文字母或数字，例如 SOS。");
        }
        StringBuilder out = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        int symbols = 0;
        boolean lastWasSpace = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000') {
                if (!lastWasSpace) {
                    out.append(" / ");
                    lastWasSpace = true;
                }
                continue;
            }
            String code = TABLE.get(Character.toUpperCase(c));
            if (code == null) {
                if (bad.indexOf(String.valueOf(c)) < 0) {
                    bad.append(c);
                }
                continue;
            }
            if (!lastWasSpace) {
                out.append(' ');
            }
            out.append(code);
            symbols += code.length();
            lastWasSpace = false;
        }
        String result = out.toString().trim();
        if (result.isEmpty()) {
            throw new ToolException("这段内容里没有国际摩斯码能表示的字符。"
                    + (bad.length() > 0 ? "不支持的字符：" + bad + "。" : "")
                    + "国际码只覆盖 A–Z、0–9 和常用标点；汉字请先转成拼音。");
        }
        return new Result(result, bad.toString(), symbols);
    }

    /** 摩斯转文本。点划之外的分隔符（空格、/、|、换行）都当作字母边界。 */
    public static Result decode(String morse) throws ToolException {
        if (morse == null || morse.trim().isEmpty()) {
            throw new ToolException("没有可解码的内容。请输入点划，例如 ... --- ...");
        }
        String normalized = morse.replace('—', '-').replace('–', '-')
                .replace('－', '-').replace('．', '.')
                .replace('·', '.').replace('•', '.');
        StringBuilder out = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        int symbols = 0;
        // 先按单词边界切，再按字母边界切，最后逐个符号查表。
        for (String word : normalized.split("\\s*/\\s*|\\s*\\|\\s*|\\n+|\\r+|\\s{3,}")) {
            String trimmedWord = word.trim();
            if (trimmedWord.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            String[] letters = trimmedWord.split("\\s+");
            for (String letter : letters) {
                String token = letter.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (!token.matches("[.\\-]+")) {
                    bad.append(token).append(' ');
                    continue;
                }
                symbols += token.length();
                Character c = REVERSE.get(token);
                if (c == null) {
                    bad.append(token).append(' ');
                    // 用「?」占位，明确这是没解出来的，而不是悄悄丢掉
                    out.append('?');
                } else {
                    out.append(c);
                }
            }
        }
        String text = out.toString();
        if (text.isEmpty()) {
            throw new ToolException("这段内容里没有可识别的点划序列。只允许 . 和 -，"
                    + "字母之间用空格，单词之间用 /。");
        }
        if (bad.length() > 0) {
            throw new ToolException("有 " + bad.toString().trim().split("\\s+").length
                    + " 个片段不是有效的摩斯码：" + bad.toString().trim()
                    + "。请检查点划拼写，或用 / 分隔单词。");
        }
        return new Result(text, "", symbols);
    }

    /** 用于测试与界面自检：全部可编码字符。 */
    public static List<Character> alphabet() {
        return new ArrayList<>(TABLE.keySet());
    }
}
