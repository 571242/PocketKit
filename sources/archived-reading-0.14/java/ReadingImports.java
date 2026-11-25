package cn.xianhua.pocketkit.reading;

import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded data import. Parsing a rule never executes it or follows its URLs. */
public final class ReadingImports {
    public static final int MAX_BUNDLE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SOURCES = 1000;
    private ReadingImports() { }

    public static List<Source> parse(String text, String subscriptionId) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("订阅内容为空");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BUNDLE_BYTES) throw new IllegalArgumentException("订阅超过 8 MiB，请分批导入");
        JSONTokener tokener = new JSONTokener(text.replaceFirst("^\\uFEFF", "").trim());
        Object parsed = tokener.nextValue();
        if (tokener.nextClean() != 0) throw new IllegalArgumentException("JSON 末尾含有多余内容");
        JSONArray array;
        if (parsed instanceof JSONArray) array = (JSONArray) parsed;
        else if (parsed instanceof JSONObject) {
            JSONObject root = (JSONObject) parsed;
            if (root.has("sources")) {
                if (root.optInt("schemaVersion", 1) != 1) throw new IllegalArgumentException("这个精选订阅需要更新版应用");
                array = root.optJSONArray("sources");
                if (array == null) throw new IllegalArgumentException("sources 必须是源列表");
            } else if (root.has("sourceUrls")) throw new IllegalArgumentException("这是远程地址集合，请选择其中一个订阅地址导入");
            else array = new JSONArray().put(root);
        } else throw new IllegalArgumentException("需要书源 JSON 对象或数组，不是网页/RSS XML");
        if (array.length() > MAX_SOURCES) throw new IllegalArgumentException("每次最多导入 1000 个源");
        if (array.length() == 0) throw new IllegalArgumentException("订阅未提供任何源，已有书源不会被清空");
        Map<String, Source> unique = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject raw = array.optJSONObject(i);
            if (raw == null) throw new IllegalArgumentException("第 " + (i + 1) + " 项不是源定义");
            if (raw.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) throw new IllegalArgumentException("第 " + (i + 1) + " 个源超过 2 MiB");
            Source s = normalize(raw);
            s.subscriptionId = subscriptionId == null ? "" : subscriptionId;
            s.localModified = false;
            s.upstreamHash = sourceHash(s);
            unique.putIfAbsent(s.id, s);
        }
        return new ArrayList<>(unique.values());
    }

    public static Source normalize(JSONObject raw) throws Exception {
        Source s;
        if (raw.optJSONObject("rule") != null) {
            s = Source.fromJson(new JSONObject(raw.toString()));
        } else if (raw.has("bookSourceUrl")) {
            s = new Source(); s.rule = new JSONObject(raw.toString());
            s.url = raw.optString("bookSourceUrl", "").trim(); s.name = raw.optString("bookSourceName", "").trim();
            Object type = raw.opt("bookSourceType");
            String group = (raw.optString("bookSourceGroup", "") + " " + s.name).toLowerCase(java.util.Locale.ROOT);
            String typeStr = type == null || JSONObject.NULL.equals(type) ? "0" : String.valueOf(type).trim();
            if ("2".equals(typeStr) || "image".equalsIgnoreCase(typeStr) || "manga".equalsIgnoreCase(typeStr)
                    || ("0".equals(typeStr) && (group.contains("漫画") || group.contains("漫蛙") || group.contains("包子") || group.contains("哔咔")))) {
                s.kind = "manga";
            } else if ("0".equals(typeStr) || "text".equalsIgnoreCase(typeStr) || "novel".equalsIgnoreCase(typeStr)) {
                s.kind = "novel";
            } else {
                s.kind = "unsupported";
            }
            s.enabled = raw.optBoolean("enabled", true);
        } else if (raw.has("sourceUrl")) {
            s = new Source(); s.rule = new JSONObject(raw.toString());
            s.url = raw.optString("sourceUrl", "").trim(); s.name = raw.optString("sourceName", "").trim();
            boolean hasArticles = !raw.optString("ruleArticles", "").trim().isEmpty();
            s.kind = (raw.optBoolean("singleUrl", false) || !hasArticles) ? "web" : "novel";
            s.enabled = raw.optBoolean("enabled", true);
        } else if ("cms".equals(raw.optString("type")) || "direct".equals(raw.optString("type"))) {
            s = new Source(); s.rule = new JSONObject(raw.toString());
            String declaredKind = raw.optString("kind", "video").trim();
            s.kind = ("manga".equals(declaredKind) || "novel".equals(declaredKind)) ? declaredKind : "video";
            s.url = raw.optString("api", raw.optString("url", "")).trim(); s.name = raw.optString("name", "视频源");
        } else throw new IllegalArgumentException("无法识别源格式：需要 Legado 书源、网页型订阅或 CMS 视频配置");
        if (s.name.isBlank()) s.name = s.url.isBlank() ? "未命名源" : s.url;
        if (s.name.length() > 200) s.name = s.name.substring(0, 200);
        if (s.url.isBlank()) s.url = s.rule.optString("bookSourceUrl", s.rule.optString("api", s.rule.optString("sourceUrl", ""))).trim();
        if (s.url.isBlank()) throw new IllegalArgumentException(s.name + " 缺少源标识/地址");
        if (s.url.length() > 8192) throw new IllegalArgumentException("源地址过长");
        for (String key : new String[]{"ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleExplore"}) {
            Object value = s.rule.opt(key);
            if (value instanceof String && !((String) value).isBlank()) {
                String str = ((String) value).trim();
                if (str.startsWith("{")) s.rule.put(key, new JSONObject(str));
            }
        }
        s.originKey = digest(s.kind + "\n" + s.rule.optString("bookSourceUrl", s.rule.optString("api", s.url)).trim());
        if (!s.id.matches("[A-Za-z0-9_.:-]{1,120}")) s.id = "source-" + s.originKey;
        return s;
    }

    public static String sourceHash(Source s) throws Exception {
        JSONObject rule = new JSONObject(s.rule.toString());
        // Provider timestamps and local ordering are not changes to the actual source definition.
        rule.remove("lastUpdateTime"); rule.remove("customOrder"); rule.remove("lastCheckTime");
        JSONObject comparable = ReadingModels.object("name", s.name, "kind", s.kind, "url", s.url, "rule", rule);
        return digest(canonical(comparable));
    }

    static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key))); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value; StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) { if (i > 0) out.append(','); out.append(canonical(a.opt(i))); } return out.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    public static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
