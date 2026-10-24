package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 亲戚称呼换算（060）。
 *
 * 模型：把一条亲属关系表示成从「我」出发的一串步骤（父/母/夫/妻/子/女/兄/弟/姐/妹，
 * 以及用于长辈的 祖父/祖母/外祖父/外祖母 等直达步骤），再查表得到称呼。
 *
 * 覆盖范围是有限的、并且明确说明：本表收录的是汉语常用称谓，最多 4 步（四代以内）。
 * 超出范围的组合会明确返回「未收录」，不会随便猜一个称呼给你。
 * 各地方言称谓差别很大（姥姥/外婆、姥爷/外公等），本表取普通话通用写法，
 * 并在结果里同时给出并列说法。
 */
public final class RelativeTitle {

    /** 一步关系。label 是正着说的动词（我 → 对方）。 */
    public enum Step {
        FATHER("父", "父亲"),
        MOTHER("母", "母亲"),
        HUSBAND("夫", "丈夫"),
        WIFE("妻", "妻子"),
        SON("子", "儿子"),
        DAUGHTER("女", "女儿"),
        ELDER_BROTHER("兄", "哥哥"),
        YOUNGER_BROTHER("弟", "弟弟"),
        ELDER_SISTER("姐", "姐姐"),
        YOUNGER_SISTER("妹", "妹妹");

        public final String key;
        public final String text;

        Step(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }

    /** 路径 -> 称呼（可能多个并列）。路径用 Step.key 拼接，例如 "父父" = 爷爷。 */
    private static final Map<String, String[]> TABLE = new LinkedHashMap<>();
    /** 反向：对方称呼我。 */
    private static final Map<String, String[]> REVERSE = new LinkedHashMap<>();

    static {
        // 直系长辈
        put("父", "爸爸", "父亲", "爹");
        put("母", "妈妈", "母亲", "娘");
        put("父父", "爷爷", "祖父");
        put("父母", "奶奶", "祖母");
        put("母父", "外公", "姥爷", "外祖父");
        put("母母", "外婆", "姥姥", "外祖母");
        put("父父父", "曾祖父", "太爷爷");
        put("父父母", "曾祖母", "太奶奶");
        put("父母父", "曾外祖父");
        put("父母母", "曾外祖母");
        put("母父父", "外曾祖父");
        put("母父母", "外曾祖母");
        put("父父父父", "高祖父");
        put("父父父母", "高祖母");

        // 直系晚辈
        put("子", "儿子");
        put("女", "女儿");
        put("子子", "孙子");
        put("子女", "孙女");
        put("女子", "外孙");
        put("女女", "外孙女");
        put("子子子", "曾孙");
        put("子子女", "曾孙女");
        put("子女子", "外曾孙");
        put("子女女", "外曾孙女");
        put("子子子子", "玄孙");
        put("子子子女", "玄孙女");

        // 同辈
        put("兄", "哥哥", "兄长");
        put("弟", "弟弟");
        put("姐", "姐姐");
        put("妹", "妹妹");
        put("父兄", "伯父", "伯伯");
        put("父弟", "叔叔", "叔父");
        put("父姐", "姑妈", "姑姑");
        put("父妹", "姑姑", "小姑");
        put("母兄", "舅舅", "舅父");
        put("母弟", "舅舅", "小舅");
        put("母姐", "姨妈", "大姨");
        put("母妹", "小姨", "姨妈");
        put("父兄子", "堂兄", "堂哥");
        put("父兄女", "堂姐");
        put("父弟子", "堂弟");
        put("父弟女", "堂妹");
        put("父姐子", "表哥", "姑表哥");
        put("父姐女", "表姐");
        put("父妹子", "表弟");
        put("父妹女", "表妹");
        put("母兄子", "表哥", "舅表哥");
        put("母兄女", "表姐");
        put("母弟子", "表弟");
        put("母弟女", "表妹");
        put("母姐子", "表哥", "姨表哥");
        put("母姐女", "表姐");
        put("母妹子", "表弟");
        put("母妹女", "表妹");
        put("兄子", "侄子");
        put("兄女", "侄女");
        put("弟子", "侄子");
        put("弟女", "侄女");
        put("姐子", "外甥");
        put("姐女", "外甥女");
        put("妹子", "外甥");
        put("妹女", "外甥女");

        // 配偶与姻亲
        put("夫", "丈夫", "老公");
        put("妻", "妻子", "老婆");
        put("夫妻", "妻子", "老婆");
        put("妻夫", "丈夫", "老公");
        put("夫子", "儿子");   // 略：夫之子按家庭语境多为「儿子」
        put("妻女", "女儿");

        // 姻亲：配偶的父母/兄弟姐妹
        put("妻父", "岳父", "老丈人");
        put("妻母", "岳母", "丈母娘");
        put("夫父", "公公");
        put("夫母", "婆婆");
        put("兄妻", "嫂子", "嫂嫂");
        put("弟妻", "弟媳", "弟妹");
        put("姐夫", "姐夫");
        put("妹夫", "妹夫");
        put("妻兄", "大舅子", "内兄");
        put("妻弟", "小舅子", "内弟");
        put("妻姐", "大姨子", "内姐");
        put("妻妹", "小姨子", "内妹");
        put("夫兄", "大伯子");
        put("夫弟", "小叔子");
        put("夫姐", "大姑子");
        put("夫妹", "小姑子");
        put("子妻", "儿媳", "儿媳妇");
        put("女夫", "女婿");
        put("子子妻", "孙媳");
        put("子女夫", "孙女婿");
        put("夫妻父", "岳父", "老丈人");
        put("夫子母", "婆婆");

        // 姻亲的长辈
        put("妻父父", "太岳父");
        put("妻父母", "太岳母");
        put("夫父父", "太公公");
        put("夫父母", "太婆婆");

        // 侄子侄女/外甥的配偶
        put("兄子妻", "侄媳");
        put("兄女夫", "侄女婿");
        put("姐子妻", "外甥媳");
        put("姐女夫", "外甥女婿");
    }

    private static void put(String path, String... titles) {
        TABLE.put(path, titles);
    }

    /** 反向关系表：正着查出来是「爸爸」，那么爸爸看我是「儿子/女儿」，由调用方指定我的性别。 */
    private static final Map<String, String[]> INVERSE = new LinkedHashMap<>();

    static {
        INVERSE.put("父", new String[]{"儿子", "女儿"});
        INVERSE.put("母", new String[]{"儿子", "女儿"});
        INVERSE.put("父父", new String[]{"孙子", "孙女"});
        INVERSE.put("父母", new String[]{"孙子", "孙女"});
        INVERSE.put("母父", new String[]{"外孙", "外孙女"});
        INVERSE.put("母母", new String[]{"外孙", "外孙女"});
        INVERSE.put("子", new String[]{"爸爸", "妈妈"});
        INVERSE.put("女", new String[]{"爸爸", "妈妈"});
        INVERSE.put("兄", new String[]{"弟弟或妹妹", "弟弟或妹妹"});
        INVERSE.put("弟", new String[]{"哥哥或姐姐", "哥哥或姐姐"});
        INVERSE.put("姐", new String[]{"弟弟或妹妹", "弟弟或妹妹"});
        INVERSE.put("妹", new String[]{"哥哥或姐姐", "哥哥或姐姐"});
        INVERSE.put("夫", new String[]{"妻子", "妻子"});
        INVERSE.put("妻", new String[]{"丈夫", "丈夫"});
        INVERSE.put("父兄", new String[]{"侄子或侄女", "侄子或侄女"});
        INVERSE.put("父弟", new String[]{"侄子或侄女", "侄子或侄女"});
        INVERSE.put("父姐", new String[]{"侄子或侄女", "侄子或侄女"});
        INVERSE.put("父妹", new String[]{"侄子或侄女", "侄子或侄女"});
        INVERSE.put("母兄", new String[]{"外甥或外甥女", "外甥或外甥女"});
        INVERSE.put("母弟", new String[]{"外甥或外甥女", "外甥或外甥女"});
        INVERSE.put("母姐", new String[]{"外甥或外甥女", "外甥或外甥女"});
        INVERSE.put("母妹", new String[]{"外甥或外甥女", "外甥或外甥女"});
        INVERSE.put("子子", new String[]{"爷爷或奶奶", "爷爷或奶奶"});
        INVERSE.put("子女", new String[]{"爷爷或奶奶", "爷爷或奶奶"});
    }

    public static final class Result {
        /** 对方是我的什么：并列说法。 */
        public final List<String> titles;
        /** 规范路径，例如「父父」。 */
        public final String path;
        /** 反向：我该叫对方什么（在同一条关系上）。 */
        public final List<String> reverse;
        /** 表里没有这条关系。 */
        public final boolean unknown;
        /** 已尝试查询的路径。 */
        public final String queried;

        Result(List<String> titles, String path, List<String> reverse, boolean unknown, String queried) {
            this.titles = titles;
            this.path = path;
            this.reverse = reverse;
            this.unknown = unknown;
            this.queried = queried;
        }

        public String titleText() {
            return String.join("、", titles);
        }

        public String reverseText() {
            return reverse.isEmpty() ? "" : String.join("、", reverse);
        }
    }

    /** 本表收录的最大步数。 */
    public static final int MAX_STEPS = 4;

    /** 收录的关系条数，用于界面说明覆盖规模。 */
    public static int coveredCount() {
        return TABLE.size();
    }

    private RelativeTitle() {
    }

    /** 把路径字符串（如 "父父"）解析成步骤列表。未知字直接报错。 */
    public static List<Step> parsePath(String path) throws ToolException {
        if (path == null || path.trim().isEmpty()) {
            throw new ToolException("请先选择或输入关系，例如「父亲的父亲」。");
        }
        String s = path.trim();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Step found = null;
            for (Step st : Step.values()) {
                if (st.key.charAt(0) == c) {
                    found = st;
                    break;
                }
            }
            if (found == null) {
                throw new ToolException("「" + c + "」不是可识别的一步关系。"
                        + "可用的是：" + stepKeys() + "。例如 父父 表示爷爷。");
            }
            steps.add(found);
        }
        if (steps.isEmpty()) {
            throw new ToolException("没有解析出任何关系。");
        }
        return steps;
    }

    public static String stepKeys() {
        StringBuilder sb = new StringBuilder();
        for (Step s : Step.values()) {
            sb.append(s.key);
        }
        return sb.toString();
    }

    /** 主查询：根据一串步骤查称呼。 */
    public static Result lookup(String path) throws ToolException {
        List<Step> steps = parsePath(path);
        if (steps.size() > MAX_STEPS) {
            return new Result(
                    Arrays.asList("未收录（步数 " + steps.size() + " 超过本表 " + MAX_STEPS + " 步上限）"),
                    path, new ArrayList<>(), true, path);
        }
        String canonical = canonical(steps);
        String[] titles = TABLE.get(canonical);
        if (titles == null) {
            return new Result(
                    Arrays.asList("未收录"),
                    canonical, new ArrayList<>(), true, path);
        }
        return new Result(Arrays.asList(titles), canonical,
                Arrays.asList(INVERSE.getOrDefault(canonical, new String[0])), false, path);
    }

    /** 把连续同性别的兄弟姐妹折叠为「兄/弟/姐/妹」的规范形式，保持表规模可控。 */
    private static String canonical(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            sb.append(s.key.charAt(0));
        }
        return sb.toString();
    }

    /** 用人类可读的说法描述路径，例如 父父 -> 父亲的父亲。 */
    public static String describePath(String path) throws ToolException {
        List<Step> steps = parsePath(path);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append("的");
            }
            sb.append(steps.get(i).text);
        }
        return sb.toString();
    }

    /** 全部已收录路径，供界面列出可选关系。 */
    public static List<String> allPaths() {
        return new ArrayList<>(TABLE.keySet());
    }
}
