package cn.xianhua.pocketkit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Extracts media actually advertised by a page; stream manifests are not media files. */
final class LinkPage {
    static final int MAX_TEXT = 100_000;
    static final int MAX_MEDIA = 20;
    private static final Pattern LINK = Pattern.compile("(?i)https?://[^\\s\\p{Cntrl}<>\"“”‘’。！，、；）】》]+" );
    private static final Pattern POST = Pattern.compile("/(?:share/)?(video|note)/(\\d+)/?$");
    private static final Pattern ROUTER = Pattern.compile("^\\s*(?:window\\.)?_ROUTER_DATA\\s*=\\s*");
    private static String extensionMime(String extension) {
        return switch (extension) {
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg", "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            default -> "";
        };
    }

    static final class Item {
        final HttpUrl url;
        final String mime;
        Item(HttpUrl url, String mime) { this.url = url; this.mime = mime; }
        boolean video() { return mime.startsWith("video/"); }
        boolean stream() { return !video() && !mime.startsWith("audio/"); }
        String label() { return stream() ? "流媒体地址" : video() ? "视频" : "音频"; }
        String extension() {
            String ext = pathExtension(url);
            if (mime.equals(extensionMime(ext)) || ext.equals("webm") && mime.equals("audio/webm")) return ext;
            switch (mime) {
                case "video/webm": case "audio/webm": return "webm";
                case "video/quicktime": return "mov";
                case "video/x-matroska": return "mkv";
                case "video/x-msvideo": return "avi";
                case "audio/mpeg": return "mp3";
                case "audio/aac": return "aac";
                case "audio/wav": return "wav";
                case "audio/flac": return "flac";
                case "audio/ogg": return "ogg";
                default: return video() ? "mp4" : "m4a";
            }
        }
    }

    final HttpUrl url;
    final LinkedHashMap<String, Item> media = new LinkedHashMap<>();
    String title = "", text = "";

    LinkPage(HttpUrl url) { this.url = url; }

    static HttpUrl fromShare(String value) {
        Matcher found = LINK.matcher(value);
        while (found.find()) {
            HttpUrl url = http(found.group().replaceFirst("[),.\\]!?;，]+$", ""));
            if (url != null) return url;
        }
        return null;
    }

    static HttpUrl http(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192) return null;
        HttpUrl url = HttpUrl.parse(value);
        return clean(url);
    }

    private static HttpUrl clean(HttpUrl url) {
        return url == null || !url.username().isEmpty() || !url.password().isEmpty() ? null
                : url.fragment() == null ? url : url.newBuilder().fragment(null).build();
    }

    static HttpUrl douyinShare(HttpUrl url) {
        String host = url.host();
        if (!host.equals("douyin.com") && !host.endsWith(".douyin.com")
                && !host.equals("iesdouyin.com") && !host.endsWith(".iesdouyin.com")) return null;
        Matcher post = POST.matcher(url.encodedPath());
        String type = "video", id = url.queryParameter("modal_id");
        if (post.find()) { type = post.group(1); id = post.group(2); }
        if (id == null || !id.matches("[0-9]{10,24}")) return null;
        return http("https://www.iesdouyin.com/share/" + type + "/" + id + "/");
    }

    static HttpUrl douyinWatch(HttpUrl url) {
        HttpUrl share = douyinShare(url);
        return share != null && share.encodedPath().startsWith("/share/video/")
                ? http("https://www.douyin.com" + share.encodedPath().substring(6)) : url;
    }

    private static String pathExtension(HttpUrl url) {
        String last = url.pathSegments().get(url.pathSegments().size() - 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 ? "" : last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseMime(String value) {
        return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static String mediaMime(HttpUrl url, String hint) {
        String mime = baseMime(hint);
        String ext = pathExtension(url);
        if (ext.equals("m3u8") || mime.endsWith("mpegurl")) return "application/vnd.apple.mpegurl";
        if (ext.equals("mpd") || mime.equals("application/dash+xml")) return "application/dash+xml";
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return mime;
        String type = extensionMime(ext);
        if (!type.isEmpty()) return type;
        String queryMime = url.queryParameter("mime_type");
        if ("video_mp4".equals(queryMime) || "video/mp4".equals(queryMime)) return "video/mp4";
        if ("audio_mp4".equals(queryMime) || "audio/mp4".equals(queryMime)) return "audio/mp4";
        if ("audio_mpeg".equals(queryMime) || "audio/mpeg".equals(queryMime)) return "audio/mpeg";
        return "";
    }

    void add(String value, String hint) {
        if (value == null || value.isEmpty() || value.length() > 8192 || media.size() >= MAX_MEDIA) return;
        HttpUrl target = clean(url.resolve(value));
        if (target == null) return;
        String mime = mediaMime(target, hint);
        if (!mime.isEmpty()) media.putIfAbsent(target.toString(), new Item(target, mime));
    }

    static String limitText(String value) {
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    static LinkPage html(Document doc, HttpUrl url) {
        LinkPage page = new LinkPage(url);
        page.title = doc.title();
        if (douyinShare(url) != null) {
            for (Element script : doc.select("script:not([src])")) {
                String data = script.data();
                Matcher marker = ROUTER.matcher(data);
                if (marker.find()) { page.router(data.substring(marker.end())); break; }
            }
        }
        for (Element tag : doc.select("video[src], audio[src], video source[src], audio source[src]")) {
            Element parent = tag.normalName().equals("source") ? tag.closest("video,audio") : tag;
            if (parent == null) continue;
            page.add(tag.attr("src"), tag.hasAttr("type") ? tag.attr("type") : parent.normalName() + "/mp4");
        }
        for (Element tag : doc.select("meta[property][content], meta[name][content]")) {
            String name = tag.hasAttr("property") ? tag.attr("property") : tag.attr("name");
            if (name.matches("og:video(?::url|:secure_url)?|twitter:player:stream")) page.add(tag.attr("content"), "video/mp4");
            if (name.matches("og:audio(?::url|:secure_url)?")) page.add(tag.attr("content"), "audio/mp4");
        }
        for (Element tag : doc.select("a[href]")) page.add(tag.attr("href"), "");
        Element main = doc.selectFirst("article, main, [role=main]");
        if (main == null) main = doc.body();
        if (page.text.isEmpty() && main != null) {
            main.select("script,style,noscript,nav,footer,[hidden]").remove();
            for (Element block : main.select("p,li,blockquote,h1,h2,h3,h4,pre")) block.after(new org.jsoup.nodes.TextNode("\n"));
            page.text = limitText(main.wholeText().trim());
        }
        if (page.text.isEmpty()) {
            Element desc = doc.selectFirst("meta[name=description],meta[property=og:description]");
            if (desc != null) page.text = limitText(desc.attr("content").trim());
        }
        return page;
    }

    void router(String data) {
        if (data.length() > 2_000_000) return;
        try {
            Object value = new JSONTokener(data).nextValue();
            if (!(value instanceof JSONObject)) return;
            JSONObject loader = ((JSONObject) value).optJSONObject("loaderData");
            if (loader == null) return;
            JSONObject route = loader.optJSONObject("video_(id)/page");
            if (route == null) route = loader.optJSONObject("note_(id)/page");
            JSONObject info = route == null ? null : route.optJSONObject("videoInfoRes");
            JSONArray items = info == null ? null : info.optJSONArray("item_list");
            JSONObject item = items == null ? null : items.optJSONObject(0);
            if (item == null) return;
            String desc = item.optString("desc", "").trim();
            if (!desc.isEmpty()) text = limitText(desc);
            addAddress(item.optJSONObject("video"), "play_addr", "video/mp4");
            addAddress(item.optJSONObject("video"), "download_addr", "video/mp4");
            addAddress(item.optJSONObject("music"), "play_url", "audio/mpeg");
        } catch (Exception | StackOverflowError ignored) { /* Invalid or excessively nested page data. */ }
    }

    private void addAddress(JSONObject owner, String key, String mime) {
        JSONObject address = owner == null ? null : owner.optJSONObject(key);
        JSONArray urls = address == null ? null : address.optJSONArray("url_list");
        for (int i = 0; urls != null && i < urls.length() && media.size() < MAX_MEDIA; i++) {
            add(urls.optString(i), mime);
        }
    }
}
