package cn.xianhua.pocketkit.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对联生成器：本地词表最长匹配 + 平仄择优。
 *
 * ## 定位（必须在界面上如实说明）
 * 这是**规则引擎，不是 AI**，也不是预先写好几副对联拿来回放。它的做法是：
 *   1. 把用户给的上联按「对偶词表」做**最长匹配**切分；
 *   2. 每个词在词表里查到它的**对偶词**；
 *   3. 组合出若干下联候选，再用平仄规则**排序择优**（优先满足关键位置相对、仄起平收）；
 *   4. 有任何一个词查不到对偶词，就**明确报出是哪个词**，不硬凑、不跳过。
 *
 * ## 词表来源与性质
 * 词表由本项目**自行整理**，只收录汉语中广为人知的**对偶常识对**（如 天/地、山/水、春/秋），
 * 以及对偶常用词组（如 春风/秋雨、青山/绿水）。不来自任何词典或商业语料库，
 * 因此不存在第三方数据授权问题。
 *
 * ## 已知限制（诚实交代）
 *  - 覆盖面有限：只收录常见字词，生僻词与专有名词必然查不到，此时会明确失败；
 *  - 只保证**字面对偶与平仄形式**，不保证语义通顺、不保证意境——它是"给建议"，不是"写诗"；
 *  - 多音字用 ICU 常用读音判平仄，可能取错声调。
 */
public final class DuilianGenerator {

    private DuilianGenerator() {
    }

    /** 拼音提供者。抽出来是为了让自检能在没有 ICU 的情况下也测生成逻辑。 */
    public interface PinyinProvider {
        /** 返回空格分隔的带调拼音；无法转换返回 null。 */
        String pinyinOf(String text);
    }

    /**
     * 对偶词表：每行是一组互为对偶的词。
     * 全部为常见对偶关系，由本项目自行整理（非取自任何词典或语料库）。
     */
    private static final String[][] PAIRS = {
            // 天象时序
            {"天", "地"}, {"日", "月"}, {"朝", "暮"}, {"晨", "昏"}, {"昼", "夜"},
            {"春", "秋"}, {"夏", "冬"}, {"年", "月"}, {"时", "刻"}, {"今", "古"},
            {"风", "雨"}, {"云", "雾"}, {"雪", "霜"}, {"雷", "电"}, {"露", "霜"},
            {"星", "月"}, {"晴", "阴"}, {"寒", "暑"}, {"暖", "凉"},
            // 山川地理
            {"山", "水"}, {"江", "河"}, {"湖", "海"}, {"峰", "岭"}, {"谷", "崖"},
            {"泉", "井"}, {"溪", "涧"}, {"田", "园"}, {"城", "乡"}, {"东", "西"},
            {"南", "北"}, {"上", "下"}, {"前", "后"}, {"左", "右"}, {"内", "外"},
            {"高", "低"}, {"深", "浅"}, {"远", "近"},
            // 草木花果
            {"花", "草"}, {"树", "林"}, {"松", "竹"}, {"梅", "兰"}, {"桃", "李"},
            {"柳", "槐"}, {"红", "绿"}, {"青", "紫"}, {"朱", "碧"}, {"苍", "翠"},
            {"果", "花"}, {"根", "叶"},
            // 鸟兽虫鱼
            {"鸟", "鱼"}, {"龙", "虎"}, {"凤", "鸾"}, {"马", "牛"}, {"鸡", "犬"},
            {"蜂", "蝶"}, {"鹤", "鸥"}, {"猿", "鹿"},
            // 器物文房
            {"金", "银"}, {"玉", "石"}, {"珠", "玉"}, {"笔", "墨"}, {"纸", "砚"},
            {"书", "画"}, {"琴", "棋"}, {"诗", "词"}, {"歌", "舞"}, {"鼓", "钟"},
            {"笛", "箫"}, {"灯", "烛"}, {"刀", "剑"}, {"弓", "箭"}, {"窗", "门"},
            {"楼", "阁"}, {"亭", "台"}, {"桥", "路"}, {"舟", "车"}, {"衣", "帽"},
            {"茶", "酒"}, {"饭", "菜"}, {"桑", "麻"}, {"稻", "麦"},
            // 人事义理
            {"人", "物"}, {"心", "意"}, {"声", "色"}, {"名", "利"}, {"家", "国"},
            {"父", "母"}, {"兄", "弟"}, {"夫", "妇"}, {"师", "友"}, {"君", "臣"},
            {"文", "武"}, {"文", "质"}, {"德", "才"}, {"恩", "怨"}, {"情", "义"},
            {"是", "非"}, {"有", "无"}, {"真", "假"}, {"善", "恶"}, {"美", "丑"},
            {"生", "死"}, {"兴", "亡"}, {"安", "危"}, {"得", "失"}, {"福", "祸"},
            {"动", "静"}, {"开", "关"}, {"进", "退"}, {"升", "降"}, {"增", "减"},
            {"满", "空"}, {"轻", "重"}, {"快", "慢"}, {"冷", "热"}, {"干", "湿"},
            {"新", "旧"}, {"明", "暗"}, {"黑", "白"}, {"大", "小"}, {"多", "少"},
            {"长", "短"}, {"来", "去"}, {"行", "止"}, {"眠", "起"}, {"藏", "露"},
            // 常用对偶词组（最长匹配用）
            {"春风", "秋雨"}, {"青山", "绿水"}, {"明月", "清风"}, {"白日", "青天"},
            {"高山", "流水"}, {"飞鸟", "游鱼"}, {"红花", "绿叶"}, {"东去", "西来"},
            {"江南", "塞北"}, {"天涯", "海角"}, {"千山", "万水"}, {"龙飞", "凤舞"},
            {"云起", "霞飞"}, {"风来", "雨过"}, {"松间", "竹里"}, {"石上", "溪边"},
            {"青山", "碧水"}, {"明月", "繁星"}, {"春风", "夏雨"}, {"秋月", "春花"},
            {"晨钟", "暮鼓"}, {"金戈", "铁马"}, {"琴棋", "书画"}, {"梅兰", "竹菊"},
            {"笔走", "墨飞"}, {"书香", "墨韵"}, {"地灵", "人杰"}, {"山明", "水秀"},
            {"鸟语", "花香"}, {"风和", "日丽"}, {"冰清", "玉洁"}, {"龙腾", "虎跃"},
            {"天长", "地久"}, {"海阔", "天空"}, {"云淡", "风轻"}, {"花好", "月圆"},
            {"柳暗", "花明"}, {"山高", "水长"}, {"月白", "风清"}, {"星移", "斗转"},
    };

    /** 双向索引：词 -> 对偶候选（去重、稳定顺序）。 */
    private static final Map<String, List<String>> OPPOSITE = buildIndex();

    /** 词表里最长的词有几个字，决定最长匹配的窗口上限。 */
    private static final int MAX_WORD_LEN = maxWordLength();

    /** 最多产出多少个候选，避免组合爆炸把界面塞满。 */
    private static final int MAX_CANDIDATES = 6;

    private static Map<String, List<String>> buildIndex() {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (String[] pair : PAIRS) {
            if (pair.length != 2 || pair[0].isEmpty() || pair[1].isEmpty() || pair[0].equals(pair[1])) {
                continue;
            }
            add(index, pair[0], pair[1]);
            add(index, pair[1], pair[0]);
        }
        return Collections.unmodifiableMap(index);
    }

    private static void add(Map<String, List<String>> index, String key, String value) {
        List<String> list = index.get(key);
        if (list == null) {
            list = new ArrayList<>();
            index.put(key, list);
        }
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static int maxWordLength() {
        int max = 1;
        for (String key : OPPOSITE.keySet()) {
            max = Math.max(max, key.length());
        }
        return max;
    }

    /** 词表规模，供界面与自检报告。 */
    public static int vocabularySize() {
        return OPPOSITE.size();
    }

    /** 一个下联候选。 */
    public static final class Candidate {
        public final String text;
        public final String pattern;
        /** 与上联关键位置平仄相对的个数。 */
        public final int opposed;
        /** 关键位置总数（偶数字位 + 末字，且双方都有确定平仄的）。 */
        public final int checked;
        public final boolean endsLevel;

        Candidate(String text, String pattern, int opposed, int checked, boolean endsLevel) {
            this.text = text;
            this.pattern = pattern;
            this.opposed = opposed;
            this.checked = checked;
            this.endsLevel = endsLevel;
        }

        /** 综合得分：相对比例优先，其次仄起平收。 */
        public double score() {
            double ratio = checked == 0 ? 0 : opposed / (double) checked;
            return ratio + (endsLevel ? 0.25 : 0);
        }
    }

    /** 生成结果。uncovered 非空表示有词查不到对偶词，属明确失败。 */
    public static final class Result {
        public final List<Candidate> candidates;
        /** 无法替换的词（按出现顺序去重）。 */
        public final List<String> uncovered;
        /** 上联的对偶切分，便于界面展示"怎么切的"。 */
        public final List<String> segmentation;

        Result(List<Candidate> candidates, List<String> uncovered, List<String> segmentation) {
            this.candidates = candidates;
            this.uncovered = uncovered;
            this.segmentation = segmentation;
        }

        public boolean complete() {
            return uncovered.isEmpty() && !candidates.isEmpty();
        }
    }

    /**
     * 生成下联候选。
     *
     * @param upperText   上联原文（只取汉字）
     * @param provider    拼音提供者
     */
    public static Result generate(String upperText, PinyinProvider provider) {
        List<String> words = segment(upperText);
        List<String> uncovered = new ArrayList<>();
        // 每个位置的候选对偶词
        List<List<String>> options = new ArrayList<>();

        for (String word : words) {
            List<String> opposites = OPPOSITE.get(word);
            if (opposites == null || opposites.isEmpty()) {
                if (!uncovered.contains(word)) {
                    uncovered.add(word);
                }
                options.add(Collections.singletonList(word));
            } else {
                options.add(opposites);
            }
        }

        if (!uncovered.isEmpty()) {
            // 明确失败：说清是哪个词覆盖不到，不硬凑一个下联出来。
            return new Result(new ArrayList<>(), uncovered, words);
        }

        // 组合（上限内做笛卡尔积），再按平仄择优。
        List<StringBuilder> combos = new ArrayList<>();
        combos.add(new StringBuilder());
        for (List<String> level : options) {
            List<StringBuilder> next = new ArrayList<>();
            for (StringBuilder prefix : combos) {
                for (String choice : level) {
                    next.add(new StringBuilder(prefix).append(choice));
                    if (next.size() >= MAX_CANDIDATES * 8) {
                        break;
                    }
                }
                if (next.size() >= MAX_CANDIDATES * 8) {
                    break;
                }
            }
            combos = next;
            if (combos.isEmpty()) {
                break;
            }
        }

        String upperToned = provider == null ? null : provider.pinyinOf(upperText);
        String upperPattern = Pingze.pattern(upperToned, upperText);

        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StringBuilder combo : combos) {
            String text = combo.toString();
            if (text.equals(upperText) || !seen.add(text)) {
                continue;
            }
            String toned = provider == null ? null : provider.pinyinOf(text);
            String pattern = Pingze.pattern(toned, text);
            int opposed = 0;
            int checked = 0;
            int length = Math.min(upperPattern.length(), pattern.length());
            for (int i = 0; i < length; i++) {
                boolean mustOppose = (i + 1) % 2 == 0 || i == length - 1;
                if (!mustOppose) {
                    continue;
                }
                char u = upperPattern.charAt(i);
                char l = pattern.charAt(i);
                if (u == '？' || l == '？') {
                    continue;
                }
                checked++;
                if (u != l) {
                    opposed++;
                }
            }
            boolean endsLevel = !pattern.isEmpty() && pattern.charAt(pattern.length() - 1) == '平';
            candidates.add(new Candidate(text, pattern, opposed, checked, endsLevel));
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }
        return new Result(candidates, new ArrayList<String>(), words);
    }

    /**
     * 最长匹配切分：每一步优先匹配词表里最长的词。
     * 例如「春风/明月」会切成 [春风, 明月] 而不是 [春, 风, 明, 月]。
     */
    public static List<String> segment(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        String han = hanOnly(text);
        int i = 0;
        while (i < han.length()) {
            int match = 0;
            int limit = Math.min(MAX_WORD_LEN, han.length() - i);
            for (int len = limit; len >= 2; len--) {
                String candidate = han.substring(i, i + len);
                if (OPPOSITE.containsKey(candidate)) {
                    match = len;
                    break;
                }
            }
            if (match == 0) {
                match = 1;
            }
            words.add(han.substring(i, i + match));
            i += match;
        }
        return words;
    }

    /** 只保留汉字，丢掉标点与空格。 */
    public static String hanOnly(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 查询某个词有没有对偶词；没有返回 null。 */
    public static String describeOpposites(String word) {
        List<String> list = OPPOSITE.get(word);
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? "、" : "").append(list.get(i));
        }
        return sb.toString();
    }

    /** 界面用的说明文字。 */
    public static String vocabularySummary() {
        return String.format(Locale.US,
                "本地对偶词表：%d 个词，最长 %d 字；词表由本项目自行整理（只用常见对偶常识对，非词典或语料库数据）。",
                vocabularySize(), MAX_WORD_LEN);
    }

    /** 便于自检：列出所有词（有序）。 */
    public static List<String> allWords() {
        return new ArrayList<>(OPPOSITE.keySet());
    }

    static {
        // 词表自检：确保没有把同一个词同时映射到自己。
        for (Map.Entry<String, List<String>> entry : OPPOSITE.entrySet()) {
            if (entry.getValue().contains(entry.getKey())) {
                throw new IllegalStateException("对偶词表自相矛盾：" + entry.getKey());
            }
        }
        if (PAIRS.length < 50) {
            throw new IllegalStateException("对偶词表过小，无法给出有意义的建议");
        }
    }

    /** 供外部只读访问（自检用）。 */
    static String[] pairSnapshot() {
        List<String> flat = new ArrayList<>();
        for (String[] pair : PAIRS) {
            flat.addAll(Arrays.asList(pair));
        }
        return flat.toArray(new String[0]);
    }
}
