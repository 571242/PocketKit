package cn.xianhua.pocketkit;

import cn.xianhua.pocketkit.media.Cancel;
import cn.xianhua.pocketkit.media.MediaIo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;

final class LinkTransfer {
    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final long MAX_PAGE = 4L * 1024 * 1024;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).build();

    void cancel() { client.dispatcher().cancelAll(); }

    LinkPage read(HttpUrl url) throws IOException {
        HttpUrl share = LinkPage.douyinShare(url);
        HttpUrl target = share == null ? url : share;
        LinkPage page;
        try { page = readOne(target); }
        catch (IOException e) {
            if (target.equals(url)) throw e;
            return readOne(url);
        }
        share = LinkPage.douyinShare(page.url);
        if (share != null && page.media.isEmpty() && !share.encodedPath().equals(page.url.encodedPath())) {
            try {
                LinkPage mobile = readOne(share);
                if (!mobile.media.isEmpty() || page.text.isEmpty()) return mobile;
            } catch (IOException ignored) { /* Keep the page the short link resolved to. */ }
        }
        return page;
    }

    private LinkPage readOne(HttpUrl url) throws IOException {
        var call = client.newCall(new Request.Builder().url(url).header("User-Agent", MOBILE_UA).build());
        call.timeout().timeout(18, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("网页返回 HTTP " + response.code());
            HttpUrl finalUrl = response.request().url();
            String mime = LinkPage.baseMime(response.header("Content-Type"));
            if (genericMediaMime(mime) || mime.startsWith("audio/") || mime.startsWith("video/")
                    || mime.endsWith("mpegurl") || mime.equals("application/dash+xml")) {
                LinkPage page = new LinkPage(finalUrl);
                page.add(finalUrl.toString(), mime);
                if (!page.media.isEmpty()) return page;
            }
            if (!mime.isEmpty() && !mime.startsWith("text/") && !mime.equals("application/xhtml+xml")
                    && !mime.equals("application/json")) throw new IOException("该链接不是可读取的网页、文本或影音文件");
            try (ResponseBody body = response.peekBody(MAX_PAGE)) {
                if (mime.startsWith("text/") && !mime.equals("text/html") || mime.equals("application/json")) {
                    LinkPage page = new LinkPage(finalUrl);
                    page.text = LinkPage.limitText(body.string().trim());
                    return page;
                }
                var type = body.contentType();
                var charset = type == null ? null : type.charset();
                return LinkPage.html(Jsoup.parse(body.byteStream(), charset == null ? null : charset.name(), finalUrl.toString()), finalUrl);
            }
        }
    }

    private static boolean genericMediaMime(String mime) {
        return mime.isEmpty() || mime.equals("application/octet-stream") || mime.equals("application/mp4");
    }

    LinkPage.Item download(LinkPage.Item item, HttpUrl page, String userAgent, File target) throws IOException {
        Request.Builder request = new Request.Builder().url(item.url).header("User-Agent", userAgent);
        if (item.url.isHttps() || !page.isHttps()) request.header("Referer", page.resolve("/").toString());
        var call = client.newCall(request.build());
        call.timeout().timeout(3, TimeUnit.MINUTES);
        try (Response response = call.execute()) {
            if (response.code() != 200) throw new IOException("媒体返回 HTTP " + response.code() + "，可重扫页面后再试");
            String mime = LinkPage.baseMime(response.header("Content-Type"));
            if (genericMediaMime(mime)) mime = item.mime;
            LinkPage.Item downloaded = new LinkPage.Item(response.request().url(), mime);
            if (downloaded.stream() || downloaded.video() != item.video()
                    || !LinkPage.mediaMime(downloaded.url, mime).equals(mime)) {
                throw new IOException("网站没有返回完整的" + item.label() + "文件");
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MediaIo.MAX_BYTES) throw new IOException("媒体为空或超过 512 MB");
            try (ResponseBody head = response.peekBody(256)) {
                String prefix = new String(head.bytes(), StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
                if (prefix.startsWith("<") || prefix.startsWith("#extm3u") || prefix.startsWith("{") || prefix.startsWith("[")) {
                    throw new IOException("网站返回的是网页、错误信息或播放列表，不是影音文件");
                }
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                long size = MediaIo.copy(body.byteStream(), out, new Cancel(), MediaIo.MAX_BYTES, null);
                if (size == 0 || body.contentLength() >= 0 && size != body.contentLength()) {
                    throw new IOException("媒体下载不完整，请重试");
                }
            }
            return downloaded;
        }
    }
}
