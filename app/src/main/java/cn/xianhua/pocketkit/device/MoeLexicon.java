package cn.xianhua.pocketkit.device;

import cn.xianhua.pocketkit.core.MiniJson;
import cn.xianhua.pocketkit.core.ToolException;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 萌典公开 JSON 的最小适配器；正文字段保持原样，不混入外语扩展。 */
public final class MoeLexicon {
    public static final String API_ROOT = "https://www.moedict.tw/a/";
    public static final String SOURCE = "萌典／教育部重編國語辭典修訂本";
    public static final String SOURCE_URL = "https://www.moedict.tw/";
    public static final String LICENSE = "教育部原文 CC BY-ND 3.0 Taiwan；g0v 格式转换编排 CC0";

    private MoeLexicon() {}

    public static final class Result {
        public final String term;
        public final List<String> pronunciations;
        public final List<String> definitions;
        public final List<String> synonyms;
        public final List<String> antonyms;
        public Result(String term, List<String> pronunciations, List<String> definitions,
                      List<String> synonyms, List<String> antonyms) {
            this.term = term;
            this.pronunciations = pronunciations;
            this.definitions = definitions;
            this.synonyms = synonyms;
            this.antonyms = antonyms;
        }
    }

    public static Http.Response fetch(String query) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) return new Http.Response(0, "", null, "请输入要查询的词语。");
        if (term.codePointCount(0, term.length()) > 64 || term.codePoints().anyMatch(Character::isISOControl))
            return new Http.Response(0, "", null, "词语须在64个字符以内，且不能含控制字符。");
        try {
            return Http.get(API_ROOT + URLEncoder.encode(term, "UTF-8").replace("+", "%20") + ".json", "application/json");
        } catch (UnsupportedEncodingException e) {
            return new Http.Response(0, "", null, "系统不支持 UTF-8：" + e.getMessage());
        }
    }

    /** 解析萌典短键 t/h[].b,p,d[].f；其它字段（含英语、德语扩展）刻意忽略。 */
    public static List<Result> parse(String json) throws ToolException {
        Map<String, Object> root = MiniJson.asObject(MiniJson.parse(json));
        String term = string(root.get("t"));
        if (term.isEmpty()) throw new ToolException("返回 JSON 缺少词头 t。");
        List<Result> out = new ArrayList<>();
        Object heads = root.get("h");
        if (!(heads instanceof List)) throw new ToolException("返回 JSON 缺少正文 h 数组。");
        for (Object headValue : (List<?>) heads) {
            Map<String, Object> head = MiniJson.asObject(headValue);
            List<String> pronunciations = new ArrayList<>();
            addString(pronunciations, head.get("p"));
            List<String> definitions = new ArrayList<>();
            List<String> synonyms = new ArrayList<>();
            List<String> antonyms = new ArrayList<>();
            Object defs = head.get("d");
            if (defs instanceof List) for (Object defValue : (List<?>) defs) {
                Map<String, Object> def = MiniJson.asObject(defValue);
                String text = string(def.get("f"));
                if (!text.isEmpty()) {
                    definitions.add(text);
                    addRelations(def.get("s"), synonyms);
                    addRelations(def.get("a"), antonyms);
                }
            }
            out.add(new Result(term, pronunciations, definitions, synonyms, antonyms));
        }
        if (out.isEmpty() || out.stream().allMatch(r -> r.definitions.isEmpty()))
            throw new ToolException("服务返回缺少释义，不能当作有效词条。");
        return out;
    }

    private static void addRelations(Object field, List<String> out) {
        for (String word : LexiconTool.splitTerms(string(field))) if (!out.contains(word)) out.add(word);
    }

    /** Only remove generated hyperlink delimiters; do not convert or rewrite original wording. */
    public static String display(String text) {
        return text == null ? "" : text.replaceAll("`([^`~]+)~", "$1");
    }

    private static void addString(List<String> out, Object value) {
        String text = string(value);
        if (!text.isEmpty()) out.add(text);
    }

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
}
