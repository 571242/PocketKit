package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章摘要（035）—— 本地抽取式摘要。
 *
 * 重要声明（界面必须原样展示）：
 * 本工具做的是「抽取式摘要」：从原文里挑出权重最高的几个句子按原顺序拼起来，
 * 一个字都不改写、不生成、不缩写。它不是生成式 AI，不做改写、不做翻译、
 * 不联网、不上传任何文本。所谓「摘要」= 原文中最有代表性的那几句话。
 *
 * 算法：中文按标点切句，按词频（二元组也参与）给句子打分，
 * 位置靠前、长度适中、含标题词的句子加权。纯本地，结果可复现。
 */
public final class ArticleSummary {

    /** 中文与英文的句子终止符。 */
    private static final String SENTENCE_END = "。！？!?；;";
    /** 不算作关键词的常见虚词与标点。 */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "的", "了", "和", "是", "在", "就", "都", "而", "及", "与", "着", "或", "一个", "没有",
            "我们", "你们", "他们", "它们", "这个", "那个", "这些", "那些", "可以", "因为", "所以",
            "但是", "如果", "虽然", "然而", "并且", "以及", "对于", "关于", "通过", "进行", "具有",
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "for",
            "on", "at", "by", "with", "as", "that", "this", "it", "be", "from", "will", "can"
    ));

    public static final class Result {
        /** 抽取出来的摘要（原句拼接）。 */
        public final String summary;
        /** 摘要包含的句子。 */
        public final List<String> pickedSentences;
        /** 原文总句数。 */
        public final int totalSentences;
        /** 摘要占原文的字数比例，0–100。 */
        public final int compressionPercent;
        /** 参与打分的关键词（按权重降序）。 */
        public final List<String> keywords;
        public final int originalChars;
        public final int summaryChars;
        /** 是否抽取失败退化为「取开头几句」。 */
        public final boolean fallback;
        /** 必须与摘要一起展示的声明。 */
        public static final String DISCLOSURE =
                "本摘要由本地抽取算法从原文挑句组成，未改写、未生成，不是生成式 AI 的产物。";

        Result(String summary, List<String> picked, int totalSentences, int compressionPercent,
               List<String> keywords, int originalChars, int summaryChars, boolean fallback) {
            this.summary = summary;
            this.pickedSentences = picked;
            this.totalSentences = totalSentences;
            this.compressionPercent = compressionPercent;
            this.keywords = keywords;
            this.originalChars = originalChars;
            this.summaryChars = summaryChars;
            this.fallback = fallback;
        }
    }

    private ArticleSummary() {
    }

    /**
     * 抽取式摘要。
     *
     * @param maxSentences 最多取几句（1–10）
     */
    public static Result summarize(String text, int maxSentences) throws ToolException {
        if (text == null || text.trim().isEmpty()) {
            throw new ToolException("没有可摘要的内容。请粘贴一段中文或英文文章。");
        }
        int limit = Math.max(1, Math.min(10, maxSentences));
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            throw new ToolException("这段内容里没有找到完整的句子。"
                    + "摘要需要至少一个以句号、问号或感叹号结尾的句子。");
        }
        if (sentences.size() <= limit) {
            String all = join(sentences);
            return new Result(all, sentences, sentences.size(), 100,
                    topKeywords(text, 6), text.length(), all.length(), false);
        }

        Map<String, Integer> freq = wordFrequencies(text);
        double maxFreq = 1;
        for (int v : freq.values()) {
            maxFreq = Math.max(maxFreq, v);
        }

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            scores.add(score(sentences.get(i), freq, maxFreq, i, sentences.size()));
        }

        // 取分数最高的 limit 句，再按原文顺序输出 —— 顺序被打乱就不是摘要了。
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        List<Integer> chosen = new ArrayList<>(order.subList(0, limit));
        chosen.sort(Integer::compareTo);

        List<String> picked = new ArrayList<>();
        for (int i : chosen) {
            picked.add(sentences.get(i));
        }
        String summary = join(picked);
        int percent = text.length() == 0 ? 0
                : (int) Math.round(summary.length() * 100.0 / text.length());
        return new Result(summary, picked, sentences.size(), percent,
                topKeywords(text, 6), text.length(), summary.length(), false);
    }

    /** 按中英文标点切句，保留终止符。 */
    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
            if (SENTENCE_END.indexOf(c) >= 0) {
                // 终止符后面若还有引号/括号，跟着收进来
                while (i + 1 < text.length() && "”\")）』」".indexOf(text.charAt(i + 1)) >= 0) {
                    cur.append(text.charAt(++i));
                }
                String s = cur.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            String tail = cur.toString().trim();
            if (!tail.isEmpty()) {
                out.add(tail);
            }
        }
        return out;
    }

    private static double score(String sentence, Map<String, Integer> freq, double maxFreq,
                                int index, int total) {
        List<String> words = words(sentence);
        if (words.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (String w : words) {
            Integer f = freq.get(w);
            if (f != null) {
                sum += 1.0 + f / maxFreq;
            }
        }
        double base = sum / Math.sqrt(words.size());
        // 位置加权：开头与结尾的句子更可能是主题句
        double position = 0;
        if (index == 0) {
            position = 1.2;
        } else if (index == 1) {
            position = 0.6;
        } else if (index == total - 1) {
            position = 0.4;
        }
        // 过短的句子信息量低，过长的句子占位置
        double lengthFactor = 1.0;
        if (words.size() < 6) {
            lengthFactor = 0.6;
        } else if (words.size() > 60) {
            lengthFactor = 0.85;
        }
        return (base + position) * lengthFactor;
    }

    /** 中文按 2–4 字 n-gram，英文按单词。 */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        List<Character> han = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                flushLatin(latin, out);
                han.add(c);
            } else if (Character.isLetterOrDigit(c) && c < 128) {
                flushHan(han, out);
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, out);
                flushHan(han, out);
            }
        }
        flushLatin(latin, out);
        flushHan(han, out);
        return out;
    }

    private static void flushLatin(StringBuilder latin, List<String> out) {
        if (latin.length() > 0) {
            String w = latin.toString();
            if (w.length() > 1 && !STOPWORDS.contains(w)) {
                out.add(w);
            }
            latin.setLength(0);
        }
    }

    /** 汉字按连续片段切成 2 字与 3 字组合，避免只统计单字导致噪声。 */
    private static void flushHan(List<Character> han, List<String> out) {
        if (han.size() == 0) {
            return;
        }
        StringBuilder run = new StringBuilder();
        for (char c : han) {
            run.append(c);
        }
        han.clear();
        String s = run.toString();
        if (s.length() == 1) {
            if (!STOPWORDS.contains(s)) {
                out.add(s);
            }
            return;
        }
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= s.length(); i++) {
                String w = s.substring(i, i + n);
                if (!STOPWORDS.contains(w)) {
                    out.add(w);
                }
            }
        }
    }

    static Map<String, Integer> wordFrequencies(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String w : words(text)) {
            freq.merge(w, 1, Integer::sum);
        }
        return freq;
    }

    private static List<String> topKeywords(String text, int n) {
        Map<String, Integer> freq = wordFrequencies(text);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, entries.size()); i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }

    private static String join(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (String s : sentences) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 供界面展示与测试：字数统计。 */
    public static int countChars(String text) {
        return text == null ? 0 : text.length();
    }
}
