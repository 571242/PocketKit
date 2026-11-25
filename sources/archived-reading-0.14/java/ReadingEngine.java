package cn.xianhua.pocketkit.reading;

import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Detail;
import cn.xianhua.pocketkit.reading.ReadingModels.Item;
import cn.xianhua.pocketkit.reading.ReadingModels.Playback;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Legado-compatible novel subset plus CMS/direct video sources.
 *
 * <p>Rule/pagination/CMS semantics were independently reimplemented after checking the GPL-3.0
 * Legado snapshot huajideshutiao/legado@d0c42f32426d01741dba0122982554f2934209e7 (AnalyzeRule,
 * AnalyzeByJSoup/JSONPath/XPath, AnalyzeUrl, and the video CMS getters). No Kotlin/Android/Room
 * code is copied; the provider layer was restructured into Java classes rather than translated
 * line for line. That reference is GPL-3.0; this file remains under the project's terms and the
 * GPL snapshot is acknowledged in the distribution notices.
 */
public final class ReadingEngine {
    private static final int MAX_PAGES = 20;
    private static final int MAX_ITEMS = 5000;
    private final Transport transport;

    public ReadingEngine() {
        this(SourceHttp::execute);
    }

    ReadingEngine(Transport transport) {
        this.transport = transport;
    }

    public List<Item> search(Source source, String query) throws Exception {
        requireSupported(source);
        if ("video".equals(kind(source))) {
            String type = source.rule.optString("type", "");
            if ("cms".equalsIgnoreCase(type)) return searchCms(source, query);
            return searchDirect(source, query);
        }
        return searchNovel(source, query == null ? "" : query);
    }

    public Detail detail(Source source, Item item) throws Exception {
        requireSupported(source);
        if (item == null) throw new IllegalArgumentException("详情条目不能为空");
        if ("video".equals(kind(source))) {
            String type = source.rule.optString("type", "");
            if ("cms".equalsIgnoreCase(type)) return detailCms(source, item);
            return detailDirect(source, item);
        }
        return detailNovel(source, item);
    }

    /** Manga chapter: resolves the rule to an ordered list of image URLs. */
    public List<String> chapterImages(Source source, Chapter chapter) throws Exception {
        requireSupported(source);
        if (!"manga".equals(kind(source))) throw new IllegalArgumentException("这不是漫画源");
        if (chapter == null || blank(chapter.url)) throw new IllegalArgumentException("章节 URL 为空");
        JSONObject contentRule = object(source.rule, "ruleContent");
        String imageRule = first(contentRule, "content", "body", "images");
        if (blank(imageRule)) throw new IllegalArgumentException("漫画源缺少 ruleContent.content");
        String nextRule = first(contentRule, "nextContentUrl", "nextUrl");

        String current = absolute(chapter.url, sourceBase(source));
        Set<String> seenPages = new LinkedHashSet<>();
        Set<String> images = new LinkedHashSet<>();
        int page = 0;
        while (!blank(current)) {
            if (page >= MAX_PAGES) throw new IOException("漫画章节超过 " + MAX_PAGES + " 页上限：" + chapter.name);
            if (!seenPages.add(current)) break;
            page++;
            Fetched fetched = fetch(source, current, vars("page", page), current);
            RuleEvaluator evaluator = evaluator(source, fetched.body, fetched.url);
            for (String url : evaluator.images(imageRule)) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                images.add(url);
                if (images.size() >= MAX_ITEMS) break;
            }
            if (blank(nextRule)) break;
            String next = evaluator.string(nextRule);
            if (blank(next)) break;
            current = evaluator.absolute(next);
        }
        if (images.isEmpty()) throw new IOException("漫画章节没有解析出图片：" + chapter.name);
        return new ArrayList<>(images);
    }

    public String chapter(Source source, Chapter chapter) throws Exception {
        requireSupported(source);
        if (!"novel".equals(kind(source))) throw new IllegalArgumentException("只有小说源有文字正文");
        if (chapter == null || blank(chapter.url)) throw new IllegalArgumentException("章节 URL 为空");
        JSONObject contentRule = object(source.rule, "ruleContent");
        String bodyRule = first(contentRule, "content", "body");
        if (blank(bodyRule)) throw new IllegalArgumentException("书源缺少 ruleContent.content");
        String nextRule = first(contentRule, "nextContentUrl", "nextUrl");

        String current = absolute(chapter.url, sourceBase(source));
        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> seenText = new LinkedHashSet<>();
        ArrayList<String> pages = new ArrayList<>();
        int page = 0;
        while (!blank(current)) {
            if (page >= MAX_PAGES) {
                // Hitting the page cap with a further page still available is a real truncation, not "the end".
                throw new IOException("正文超过 " + MAX_PAGES + " 页上限，已停止以免漏章：" + chapter.name);
            }
            if (!seenUrls.add(current)) break;
            page++;
            Fetched fetched = fetch(source, current, vars("page", page), current);
            RuleEvaluator evaluator = evaluator(source, fetched.body, fetched.url);
            String text = evaluator.plainText(bodyRule);
            if (!blank(text) && seenText.add(text)) pages.add(text);
            if (blank(nextRule)) break;
            String next = evaluator.string(nextRule);
            if (blank(next)) break;
            current = evaluator.absolute(next);
        }
        if (pages.isEmpty()) throw new IOException("章节规则没有解析出正文：" + chapter.name);
        return String.join("\n\n", pages).trim();
    }

    public Playback playback(Source source, Chapter chapter) throws Exception {
        requireSupported(source);
        if (!"video".equals(kind(source))) throw new IllegalArgumentException("非视频源没有播放地址");
        if (chapter == null || blank(chapter.url)) throw new IllegalArgumentException("播放地址为空");
        String url = absolute(chapter.url, sourceBase(source));
        SourceHttp.validatePublicUrl(url);
        Playback playback = new Playback();
        playback.url = url;
        playback.mimeType = mimeType(url);
        playback.headers.putAll(sourceHeaders(source.rule));
        return playback;
    }

    public static String supportIssue(Source source) {
        if (source == null) return "来源为空";
        String kind = kind(source);
        if ("web".equals(kind)) return "网页入口只能交给浏览器，不能作为小说、漫画或视频规则源";
        if (!"novel".equals(kind) && !"manga".equals(kind) && !"video".equals(kind)) return "不支持的来源类型：" + source.kind;
        if (source.rule == null) return "来源缺少规则对象";
        String raw = source.rule.toString();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains(".jar") || lower.contains("jar:")) return "不接受 JAR 插件或 JAR 规则";
        if (lower.contains("\"webview\":true") || lower.contains("\"webview\": true")) {
            return "不支持需要 WebView 执行或验证码绕过的规则";
        }
        if ("video".equals(kind)) {
            String type = source.rule.optString("type", "");
            if ("cms".equalsIgnoreCase(type)) {
                return blank(source.rule.optString("api")) ? "CMS 视频源缺少 api" : "";
            }
            if ("direct".equalsIgnoreCase(type)) {
                return directItems(source).isEmpty() ? "direct 视频源缺少 items 数组或直链 url" : "";
            }
            return "视频源仅支持 type=cms 或 type=direct";
        }
        if (blank(first(source.rule, "searchUrl"))) return "源缺少 searchUrl";
        JSONObject search = object(source.rule, "ruleSearch");
        if (blank(first(search, "bookList", "list")) || blank(first(search, "name", "bookName"))
                || blank(first(search, "bookUrl", "url"))) {
            return "搜索规则至少需要 bookList、name、bookUrl";
        }
        JSONObject toc = object(source.rule, "ruleToc");
        if (blank(first(toc, "chapterList", "list")) || blank(first(toc, "chapterName", "name"))
                || blank(first(toc, "chapterUrl", "url"))) {
            return "目录规则至少需要 chapterList、chapterName、chapterUrl";
        }
        JSONObject content = object(source.rule, "ruleContent");
        if (blank(first(content, "content", "body"))) return "来源缺少 ruleContent.content";
        return "";
    }

    public static String fetchSubscription(String url) throws Exception {
        SourceHttp.Response response = SourceHttp.execute(SourceHttp.Request.get(url), SourceHttp.MAX_BYTES);
        subscriptionAccepted(response.body);
        return response.body;
    }

    /** Throws unless the body is a recognizeable source object/array, curated bundle or video config. */
    static void subscriptionAccepted(String body) throws IOException {
        if (blank(body)) throw new IOException("订阅返回空内容");
        Object json;
        try {
            json = body.trim().startsWith("[") ? new JSONArray(body) : new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("订阅内容不是合法 JSON（可能是网页或错误页）", e);
        } catch (RuntimeException e) {
            throw new IOException("订阅内容不是合法 JSON（可能是网页或错误页）", e);
        }
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            boolean known = object.has("sources") || object.has("bookSourceUrl") || object.has("sourceUrl")
                    || object.has("sourceUrls") || object.has("api")
                    || "cms".equalsIgnoreCase(object.optString("type"))
                    || "direct".equalsIgnoreCase(object.optString("type"));
            if (!known) throw new IOException("订阅 JSON 不是来源对象、来源数组、精选包或视频配置");
        }
    }

    /** Normalizes CMS response text to a JSONObject; a non-JSON body is a parse failure, not "no results". */
    private static JSONObject cmsJson(Fetched fetched) throws IOException {
        String body = fetched.body == null ? "" : fetched.body.trim();
        if (blank(body)) throw new IOException("CMS 接口返回空内容（可能已失效），而非无结果");
        try {
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("CMS 接口返回了非 JSON 内容（可能已失效或被拦截），而非无结果", e);
        } catch (RuntimeException e) {
            throw new IOException("CMS 接口返回了非 JSON 内容（可能已失效或被拦截），而非无结果", e);
        }
    }

    private List<Item> searchNovel(Source source, String query) throws Exception {
        JSONObject searchRule = object(source.rule, "ruleSearch");
        String template = encodeBareKeywords(first(source.rule, "searchUrl"), query == null ? "" : query);
        Map<String, Object> variables = vars("key", query == null ? "" : query,
                "query", query == null ? "" : query, "page", 1);
        String startUrl = renderedUrlOrEmpty(template, variables, sourceBase(source), source);
        Fetched fetched = fetch(source, template, variables, sourceBase(source));
        RuleEvaluator root = evaluator(source, fetched.body, fetched.url);
        List<Object> nodes = root.nodes(first(searchRule, "bookList", "list"));
        if (nodes.isEmpty() && !blank(startUrl) && !startUrl.equalsIgnoreCase(fetched.url)) {
            // Many sites answer an exact-title search with a 302 straight to the book page.
            // Legado treats that as a single search result; do the same instead of "0 results".
            Item direct = new Item();
            direct.sourceId = source.id;
            direct.kind = "novel";
            direct.url = fetched.url;
            direct.title = prefer(root.string(first(object(source.rule, "ruleBookInfo"), "name", "bookName")), query);
            if (blank(direct.title)) direct.title = query;
            return List.of(direct);
        }
        LinkedHashMap<String, Item> unique = new LinkedHashMap<>();
        for (Object node : nodes) {
            RuleEvaluator evaluator = evaluator(source, node, fetched.url);
            Item item = new Item();
            item.sourceId = source.id;
            item.kind = "novel";
            item.title = evaluator.string(first(searchRule, "name", "bookName"));
            item.author = evaluator.string(first(searchRule, "author"));
            item.description = evaluator.string(first(searchRule, "intro", "description"));
            item.cover = absoluteOptional(evaluator.string(first(searchRule, "coverUrl", "cover")), fetched.url);
            item.url = absoluteOptional(evaluator.string(first(searchRule, "bookUrl", "url")), fetched.url);
            if (!blank(item.title) && !blank(item.url)) unique.putIfAbsent(item.url, item);
        }
        return new ArrayList<>(unique.values());
    }

    private Detail detailNovel(Source source, Item original) throws Exception {
        String detailUrl = absolute(original.url, sourceBase(source));
        Fetched detailPage = fetch(source, detailUrl, Map.of(), detailUrl);
        JSONObject infoRule = object(source.rule, "ruleBookInfo");
        RuleEvaluator info = evaluator(source, detailPage.body, detailPage.url);
        Item item = copyItem(original);
        item.sourceId = source.id;
        item.kind = "novel";
        item.title = prefer(info.string(first(infoRule, "name", "bookName")), item.title);
        item.author = prefer(info.string(first(infoRule, "author")), item.author);
        item.description = prefer(info.string(first(infoRule, "intro", "description")), item.description);
        item.cover = prefer(absoluteOptional(info.string(first(infoRule, "coverUrl", "cover")), detailPage.url), item.cover);
        String toc = info.string(first(infoRule, "tocUrl", "chapterUrl"));
        String current = blank(toc) ? detailPage.url : info.absolute(toc);

        JSONObject tocRule = object(source.rule, "ruleToc");
        String listRule = first(tocRule, "chapterList", "list");
        String nameRule = first(tocRule, "chapterName", "name");
        String urlRule = first(tocRule, "chapterUrl", "url");
        String nextRule = first(tocRule, "nextTocUrl", "nextUrl");
        LinkedHashMap<String, Chapter> chapters = new LinkedHashMap<>();
        Set<String> seenPages = new LinkedHashSet<>();
        int page = 0;
        String lastNext = "";
        while (!blank(current) && chapters.size() < MAX_ITEMS) {
            if (page >= MAX_PAGES) {
                // A very long serial can page past the cap; keep what was read instead of failing the whole book.
                break;
            }
            if (!seenPages.add(current)) break;
            page++;
            Fetched fetched = current.equals(detailPage.url) ? detailPage
                    : fetch(source, current, vars("page", page), current);
            RuleEvaluator root = evaluator(source, fetched.body, fetched.url);
            for (Object node : root.nodes(listRule)) {
                RuleEvaluator evaluator = evaluator(source, node, fetched.url);
                String name = evaluator.string(nameRule);
                String url = absoluteOptional(evaluator.string(urlRule), fetched.url);
                if (blank(name) || blank(url)) continue;
                Chapter chapter = new Chapter();
                chapter.name = name;
                chapter.url = url;
                chapters.putIfAbsent(url, chapter);
                if (chapters.size() >= MAX_ITEMS) break;
            }
            if (blank(nextRule)) break;
            String next = root.string(nextRule);
            if (blank(next) || next.equals(lastNext)) break;
            lastNext = next;
            current = root.absolute(next);
        }
        if (chapters.size() >= MAX_ITEMS) {
            throw new IOException("目录超过 " + MAX_ITEMS + " 章上限，已停止以免漏章：" + item.title);
        }
        if (chapters.isEmpty()) throw new IOException("目录规则没有解析出章节：" + item.title);
        Detail detail = new Detail();
        detail.item = item;
        detail.chapters.addAll(chapters.values());
        return detail;
    }

    private List<Item> searchCms(Source source, String query) throws Exception {
        String url = appendQuery(source.rule.optString("api"), "ac", "detail", "wd", query == null ? "" : query);
        JSONObject response = cmsJson(fetch(source, url, Map.of(), sourceBase(source)));
        JSONArray list = cmsList(response);
        ArrayList<Item> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject value = list.optJSONObject(i);
            if (value == null) continue;
            Item item = cmsItem(source, value);
            if (!blank(item.url) && ids.add(item.url)) result.add(item);
        }
        return result;
    }

    private Detail detailCms(Source source, Item original) throws Exception {
        String id = original.url;
        String url = appendQuery(source.rule.optString("api"), "ac", "detail", "ids", id);
        JSONObject response = cmsJson(fetch(source, url, Map.of(), sourceBase(source)));
        JSONArray list = cmsList(response);
        if (list.length() == 0 || list.optJSONObject(0) == null) throw new IOException("CMS 未返回视频详情：" + id);
        JSONObject value = list.getJSONObject(0);
        Detail detail = new Detail();
        detail.item = cmsItem(source, value);
        if (blank(detail.item.title)) detail.item.title = original.title;
        String[] groups = value.optString("vod_play_from", "默认").split("\\$\\$\\$", -1);
        String[] routes = value.optString("vod_play_url", "").split("\\$\\$\\$", -1);
        // Apple CMS publishes several routes per title: usually an HTML share page first and a
        // direct .m3u8 stream after it. ExoPlayer can only open the stream, so the m3u8 routes
        // are listed first and the share pages remain available as a fallback group.
        Integer[] order = new Integer[routes.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (left, right) -> Boolean.compare(
                !routes[left].contains(".m3u8"), !routes[right].contains(".m3u8")));
        Set<String> seen = new LinkedHashSet<>();
        for (int position = 0; position < order.length; position++) {
            int route = order[position];
            String group = route < groups.length && !blank(groups[route]) ? groups[route] : "线路 " + (position + 1);
            for (String entry : routes[route].split("#")) {
                if (blank(entry)) continue;
                int split = entry.indexOf('$');
                String name = split < 0 ? "第 " + (detail.chapters.size() + 1) + " 集" : entry.substring(0, split).trim();
                String playUrl = split < 0 ? entry.trim() : entry.substring(split + 1).trim();
                if (blank(playUrl) || !seen.add(group + "\n" + playUrl)) continue;
                Chapter chapter = new Chapter();
                chapter.name = blank(name) ? "第 " + (detail.chapters.size() + 1) + " 集" : name;
                chapter.url = playUrl;
                chapter.group = group;
                detail.chapters.add(chapter);
            }
        }
        if (detail.chapters.isEmpty()) throw new IOException("CMS 详情没有可播放选集");
        return detail;
    }

    private List<Item> searchDirect(Source source, String query) {
        List<JSONObject> items = directItems(source);
        ArrayList<Item> result = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < items.size(); i++) {
            Item item = directItem(source, items.get(i), i);
            String haystack = (item.title + " " + item.author + " " + item.description).toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || haystack.contains(needle)) result.add(item);
        }
        return result;
    }

    /**
     * Accepts {@code items:[...]}, a single {@code items:{...}} object, or one direct object on the
     * rule itself ({@code {"type":"direct","url":"..."}}), so a curated single-link entry still works.
     */
    private static List<JSONObject> directItems(Source source) {
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONObject rule = source.rule;
        JSONArray array = rule.optJSONArray("items");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) out.add(value);
            }
            return out;
        }
        JSONObject single = rule.optJSONObject("items");
        if (single != null) { out.add(single); return out; }
        if (!blank(rule.optString("url"))) out.add(rule);
        return out;
    }

    private Detail detailDirect(Source source, Item original) throws Exception {
        List<JSONObject> items = directItems(source);
        JSONObject found = null;
        for (JSONObject value : items) {
            String id = value.optString("id");
            String url = value.optString("url");
            if (original.url.equals(id) || original.url.equals(url)) {
                found = value;
                break;
            }
        }
        if (found == null) throw new IOException("direct 视频源中找不到条目：" + original.url);
        Detail detail = new Detail();
        detail.item = directItem(source, found, 0);
        JSONArray chapters = found.optJSONArray("chapters");
        if (chapters == null && !blank(found.optString("url"))) {
            chapters = new JSONArray().put(new JSONObject().put("name", found.optString("title", "播放"))
                    .put("url", found.optString("url")));
        }
        for (int i = 0; chapters != null && i < chapters.length(); i++) {
            JSONObject value = chapters.optJSONObject(i);
            if (value == null || blank(value.optString("url"))) continue;
            Chapter chapter = new Chapter();
            chapter.name = value.optString("name", "第 " + (i + 1) + " 集");
            chapter.url = absolute(value.optString("url"), sourceBase(source));
            chapter.group = value.optString("group", "直链");
            detail.chapters.add(chapter);
        }
        if (detail.chapters.isEmpty()) throw new IOException("direct 条目没有合法播放地址");
        return detail;
    }

    private Fetched fetch(Source source, String template, Map<String, Object> variables, String baseUrl)
            throws Exception {
        RequestSpec spec = requestSpec(source, template, variables, baseUrl);
        SourceHttp.Response response = transport.execute(
                new SourceHttp.Request(spec.url, spec.method, spec.body, spec.headers), SourceHttp.DEFAULT_MAX_BYTES);
        return new Fetched(response.url, response.body);
    }

    private RequestSpec requestSpec(Source source, String template, Map<String, Object> variables,
                                    String baseUrl) throws Exception {
        if (blank(template)) throw new IOException("请求 URL 规则为空");
        String rendered = RuleEvaluator.renderRequest(template, variables, baseUrl, jsLib(source), sourceKey(source));
        JSONObject option = null;
        int optionAt = rendered.lastIndexOf(",{");
        if (optionAt >= 0) {
            String candidate = rendered.substring(optionAt + 1).trim();
            try {
                option = new JSONObject(candidate);
                rendered = rendered.substring(0, optionAt).trim();
            } catch (RuntimeException ignored) {
                throw new IOException("URL 请求参数 JSON 无效");
            }
        }
        String url = absolute(toAsciiUrl(rendered), baseUrl);
        String method = option == null ? "GET" : option.optString("method", "GET").toUpperCase(Locale.ROOT);
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IOException("仅支持 GET/POST URL 模板，不支持 " + method);
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(sourceHeaders(source.rule));
        if (option != null) headers.putAll(jsonHeaders(option.opt("headers")));
        byte[] body = null;
        if ("POST".equals(method)) {
            Object rawBody = option == null ? null : option.opt("body");
            String bodyText = rawBody == null || rawBody == JSONObject.NULL ? "" : String.valueOf(rawBody);
            bodyText = RuleEvaluator.renderRequest(bodyText, variables, baseUrl, jsLib(source), sourceKey(source));
            body = bodyText.getBytes(StandardCharsets.UTF_8);
            if (!hasHeader(headers, "Content-Type")) {
                headers.put("Content-Type", bodyText.trim().startsWith("{")
                        ? "application/json; charset=utf-8" : "application/x-www-form-urlencoded; charset=utf-8");
            }
        }
        return new RequestSpec(url, method, body, headers);
    }

    private static Map<String, String> sourceHeaders(JSONObject rule) {
        Object raw = rule == null ? null : (rule.has("header") ? rule.opt("header") : rule.opt("headers"));
        return jsonHeaders(raw);
    }

    /** Android's JSONObject has keys(), not the desktop convenience keySet(). */
    private static List<String> keys(JSONObject object) {
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    private static Map<String, String> jsonHeaders(Object raw) {
        JSONObject object = null;
        if (raw instanceof JSONObject) object = (JSONObject) raw;
        else if (raw instanceof String && !blank((String) raw)) {
            try { object = new JSONObject((String) raw); } catch (org.json.JSONException e) { return Map.of(); } catch (RuntimeException e) { return Map.of(); }
        }
        if (object == null) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : keys(object)) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) result.put(key, String.valueOf(value));
        }
        return result;
    }

    private static boolean hasHeader(Map<String, String> headers, String wanted) {
        for (String name : headers.keySet()) if (name.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    private static Item cmsItem(Source source, JSONObject value) {
        Item item = new Item();
        item.sourceId = source.id;
        item.kind = "video";
        item.title = value.optString("vod_name", value.optString("name", ""));
        item.author = value.optString("vod_actor", value.optString("vod_director", ""));
        item.url = String.valueOf(value.opt("vod_id"));
        if ("null".equals(item.url)) item.url = value.optString("id", "");
        item.cover = value.optString("vod_pic", "");
        item.description = htmlText(value.optString("vod_content", value.optString("vod_blurb", "")));
        return item;
    }

    private static JSONArray cmsList(JSONObject response) {
        JSONArray list = response.optJSONArray("list");
        if (list == null) list = response.optJSONArray("data");
        JSONObject data = response.optJSONObject("data");
        if (list == null && data != null) list = data.optJSONArray("list");
        return list == null ? new JSONArray() : list;
    }

    private static Item directItem(Source source, JSONObject value, int index) {
        Item item = new Item();
        item.sourceId = source.id;
        item.kind = "video";
        item.title = value.optString("title", value.optString("name", "未命名视频"));
        item.author = value.optString("author", "");
        item.description = value.optString("description", "");
        item.cover = value.optString("cover", "");
        item.url = value.optString("id", value.optString("url", "direct-" + index));
        return item;
    }

    private static Item copyItem(Item input) {
        Item item = new Item();
        item.sourceId = input.sourceId;
        item.kind = input.kind;
        item.title = input.title;
        item.author = input.author;
        item.url = input.url;
        item.cover = input.cover;
        item.description = input.description;
        return item;
    }

    private static String sourceBase(Source source) {
        String value = first(source.rule, "bookSourceUrl", "api");
        return blank(value) ? source.url : value;
    }

    private static JSONObject object(JSONObject parent, String key) {
        JSONObject value = parent == null ? null : parent.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }

    private static String first(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!blank(value)) return value;
        }
        return "";
    }

    private static String prefer(String preferred, String fallback) { return blank(preferred) ? fallback : preferred; }

    private static String absoluteOptional(String value, String base) throws IOException {
        if (blank(value)) return "";
        // A CSS rule can legitimately match several covers; the first usable URL wins.
        for (String line : value.split("[\\r\\n\\s]+")) {
            if (!line.isBlank()) return absolute(line, base);
        }
        return "";
    }

    private static String absolute(String value, String base) throws IOException {
        if (blank(value)) throw new IOException("URL 为空");
        value = RuleEvaluator.stripUrlOptions(value.trim());
        try {
            URI uri = URI.create(value.trim());
            if (uri.isAbsolute()) return uri.toASCIIString();
            if (blank(base)) throw new IOException("相对 URL 缺少基准地址：" + value);
            return URI.create(base.trim()).resolve(uri).toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new IOException("URL 无效：" + value, e);
        }
    }

    private static String toAsciiUrl(String value) throws IOException {
        try { return URI.create(value.trim().replace(" ", "%20")).toASCIIString(); }
        catch (RuntimeException e) { throw new IOException("URL 模板生成了无效地址", e); }
    }

    private static String appendQuery(String base, String... pairs) throws IOException {
        if (blank(base)) throw new IOException("API 地址为空");
        StringBuilder result = new StringBuilder(base);
        result.append(base.contains("?") ? '&' : '?');
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) result.append('&');
            result.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pairs[i + 1] == null ? "" : pairs[i + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static Map<String, Object> vars(Object... pairs) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }

    /**
     * Legado frequently writes {@code /search?keyword={keyword}} (or {key}) directly in searchUrl
     * instead of inside {{ }}. Fill those placeholders and URL-encode the raw value so CJK and
     * reserved characters do not break the query, while an already {{expr}} template is untouched.
     */
    private static String encodeBareKeywords(String template, String keyword) {
        if (blank(template) || template.contains("{{")) return template;
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return template.replace("{keyword}", encoded).replace("{key}", encoded)
                .replace("{page}", "1");
    }

    private static String kind(Source source) {
        return source == null || source.kind == null ? "" : source.kind.trim().toLowerCase(Locale.ROOT);
    }

    /** Every rule evaluation carries the source's jsLib and a stable key for @put/@get variables. */
    private static RuleEvaluator evaluator(Source source, Object content, String baseUrl) {
        return new RuleEvaluator(content, baseUrl, jsLib(source), sourceKey(source));
    }

    private static String jsLib(Source source) {
        if (source == null || source.rule == null) return "";
        String lib = source.rule.optString("jsLib", "");
        return blank(lib) ? "" : lib;
    }

    private static String sourceKey(Source source) {
        if (source == null) return "";
        String key = source.id;
        if (blank(key) && source.rule != null) key = source.rule.optString("bookSourceUrl", "");
        return key == null ? "" : key;
    }

    /** Best-effort URL the request template would call, used only to detect a search redirect. */
    private static String renderedUrlOrEmpty(String template, Map<String, Object> variables, String baseUrl, Source source) {
        try {
            String value = RuleEvaluator.renderRequest(template, variables, baseUrl, jsLib(source), sourceKey(source)).trim();
            int comma = value.lastIndexOf(",{");
            if (comma >= 0) value = value.substring(0, comma).trim();
            return absolute(value, baseUrl);
        } catch (Exception e) {
            return "";
        }
    }

    private static void requireSupported(Source source) {        String issue = supportIssue(source);
        if (!issue.isEmpty()) throw new IllegalArgumentException(issue);
    }

    private static String mimeType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return "application/x-mpegURL";
        if (lower.contains(".mpd")) return "application/dash+xml";
        if (lower.contains(".webm")) return "video/webm";
        if (lower.contains(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private static String htmlText(String value) {
        return value == null ? "" : org.jsoup.Jsoup.parse(value).text();
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    interface Transport {
        SourceHttp.Response execute(SourceHttp.Request request, int maxBytes) throws Exception;
    }

    private static final class Fetched {
        final String url;
        final String body;
        Fetched(String url, String body) { this.url = url; this.body = body; }
    }

    private static final class RequestSpec {
        final String url;
        final String method;
        final byte[] body;
        final Map<String, String> headers;
        RequestSpec(String url, String method, byte[] body, Map<String, String> headers) {
            this.url = url;
            this.method = method;
            this.body = body;
            this.headers = headers;
        }
    }
}
