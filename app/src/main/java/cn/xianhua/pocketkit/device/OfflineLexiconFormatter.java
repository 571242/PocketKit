package cn.xianhua.pocketkit.device;

import cn.xianhua.pocketkit.core.MiniJson;
import cn.xianhua.pocketkit.core.ToolException;
import java.util.*;

/** Renders every JSON field recursively; unknown source fields are never discarded. */
public final class OfflineLexiconFormatter {
    private OfflineLexiconFormatter() {}
    public static String format(String title, String payload) throws ToolException {
        Map<String,Object> root = MiniJson.asObject(MiniJson.parse(payload));
        if (!title.equals(root.get("title")) || !(root.get("heteronyms") instanceof List)) throw new ToolException("词典记录与索引不匹配或缺少原读音字段。");
        StringBuilder out = new StringBuilder("词目：").append(title).append('\n');
        render(out, root, 0, "");
        return out.toString();
    }
    private static void render(StringBuilder out, Object v, int depth, String key) {
        StringBuilder pad = new StringBuilder(); for (int i=0; i<Math.min(depth, 12); i++) pad.append("  ");
        String indent = pad.toString();
        if (v instanceof Map) {
            for (Map.Entry<?,?> e : ((Map<?,?>)v).entrySet()) {
                String k=String.valueOf(e.getKey()); Object x=e.getValue();
                if (x instanceof Map || x instanceof List) { out.append(indent).append(label(k)).append("：\n"); render(out,x,depth+1,k); }
                else out.append(indent).append(label(k)).append("：").append(display(x)).append('\n');
            }
        } else if (v instanceof List) {
            for (Object x:v instanceof List ? (List<?>)v : Collections.emptyList()) render(out,x,depth+1,key);
        } else out.append(indent).append(display(v)).append('\n');
    }
    private static String label(String key) {
        switch(key) {
            case "title": return "词目";
            case "heteronyms": return "原读音组";
            case "radical": return "部首";
            case "stroke_count": return "总笔画";
            case "non_radical_stroke_count": return "部外笔画";
            case "bopomofo": return "注音";
            case "pinyin": return "汉语拼音";
            case "definitions": return "原释义组";
            case "def": return "释义";
            case "type": return "词性";
            case "quote": return "书证";
            case "example": return "例句";
            case "link": return "参见原文";
            case "synonyms": return "近义（原义组）";
            case "antonyms": return "反义（原义组）";
            default: return key;
        }
    }
    private static String display(Object v) { return v == null ? "null" : String.valueOf(v); }
}
