package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 文本清理与统计。纯计算，无 Android 依赖，可被独立单元测试。
 */
public final class TextCleaner {

    public static final class Result {
        public final String text;
        public final int inputLines;
        public final int outputLines;
        public final int removedBlank;
        public final int removedDuplicate;
        public final int trimmedLines;
        public final int inputChars;
        public final int outputChars;

        Result(String text, int inputLines, int outputLines, int removedBlank,
               int removedDuplicate, int trimmedLines, int inputChars, int outputChars) {
            this.text = text;
            this.inputLines = inputLines;
            this.outputLines = outputLines;
            this.removedBlank = removedBlank;
            this.removedDuplicate = removedDuplicate;
            this.trimmedLines = trimmedLines;
            this.inputChars = inputChars;
            this.outputChars = outputChars;
        }

        public int removedTotal() {
            return removedBlank + removedDuplicate;
        }
    }

    private TextCleaner() {
    }

    /** 按 \n、\r\n、\r 切行；结尾的换行不额外产生一个空行。 */
    public static String[] splitLines(String s) {
        if (s == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        int n = s.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(s.substring(start, i));
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            out.add(s.substring(start));
        }
        return out.toArray(new String[0]);
    }

    /** 去首尾空白；额外把全角空格、不换行空格、BOM 也算作空白，比 String.trim 更符合中文文本预期。 */
    public static String trimEdges(String s) {
        if (s == null) {
            return "";
        }
        int a = 0;
        int b = s.length();
        while (a < b && isEdgeBlank(s.charAt(a))) {
            a++;
        }
        while (b > a && isEdgeBlank(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isEdgeBlank(char c) {
        return Character.isWhitespace(c) || c == '\u3000' || c == '\uFEFF' || c == '\u00A0';
    }

    /**
     * @param dropBlank     删除只含空白（清理后为空）的行
     * @param dropDuplicate 删除重复行，保留第一次出现的位置
     * @param trimEdges     每一行去掉首尾空白
     */
    public static Result clean(String src, boolean dropBlank, boolean dropDuplicate, boolean trimEdges) {
        if (src == null) {
            src = "";
        }
        String[] lines = splitLines(src);
        List<String> kept = new ArrayList<>(lines.length);
        HashSet<String> seen = new HashSet<>();
        int removedBlank = 0;
        int removedDuplicate = 0;
        int trimmedLines = 0;

        for (String raw : lines) {
            String line = raw;
            if (trimEdges) {
                String t = trimEdges(line);
                if (!t.equals(line)) {
                    trimmedLines++;
                }
                line = t;
            }
            if (dropBlank && line.isEmpty()) {
                removedBlank++;
                continue;
            }
            if (dropDuplicate && !seen.add(line)) {
                removedDuplicate++;
                continue;
            }
            kept.add(line);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(kept.get(i));
        }
        String text = sb.toString();
        return new Result(text, lines.length, kept.size(), removedBlank, removedDuplicate,
                trimmedLines, src.length(), text.length());
    }
}
