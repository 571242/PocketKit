package cn.xianhua.pocketkit;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.junit.Test;

public final class LinkExtractTest {
    @Test public void shareTextAndOfficialPost() {
        assertEquals("https://v.douyin.com/demo/", LinkPage.fromShare("1.23 复制打开抖音 https://v.douyin.com/demo/。看视频").toString());
        assertNull(LinkPage.fromShare("javascript:alert(1) file:///sdcard/a.mp4 https://a:b@example.org/"));
        assertEquals("https://www.iesdouyin.com/share/video/7685345542323834441/", LinkPage.douyinShare(
                LinkPage.http("https://www.douyin.com/discover?modal_id=7685345542323834441")).toString());
        assertNull(LinkPage.douyinShare(LinkPage.http("https://notdouyin.com/video/7685345542323834441")));
    }

    @Test public void htmlMediaAndText() {
        HttpUrl url = LinkPage.http("https://example.org/page/");
        LinkPage page = LinkPage.html(Jsoup.parse("""
                <title>小样</title><meta property="og:audio" content="/voice.mp3">
                <article><p>第一段</p><p>第二段</p><script>不可见</script></article>
                <video src="video.mp4"></video><audio><source src="sound.wav" type="audio/wav"></audio>
                <a href="/voice.mp3">重复</a><a href="/live.m3u8">直播</a>
                <video src="blob:https://example.org/123"></video>
                """), url);
        assertEquals("小样", page.title);
        assertTrue(page.text.contains("第一段") && page.text.contains("第二段"));
        assertFalse(page.text.contains("不可见"));
        assertEquals(4, page.media.size());
        assertTrue(page.media.get("https://example.org/page/video.mp4").video());
        assertEquals("wav", page.media.get("https://example.org/page/sound.wav").extension());
        assertTrue(page.media.get("https://example.org/live.m3u8").stream());
    }

    @Test public void douyinOfficialShareDataAndEmptyTemplate() {
        HttpUrl url = LinkPage.http("https://www.iesdouyin.com/share/video/7685345542323834441/");
        LinkPage page = LinkPage.html(Jsoup.parse("""
                <script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[
                 {"desc":"视频文案","video":{"play_addr":{"url_list":["https://media.example.com/v?x=1"]}},
                  "music":{"play_url":{"url_list":["https://media.example.com/original.mp3"]}}}
                ]}}}};window.done=true;</script>
                """), url);
        assertEquals("视频文案", page.text);
        assertEquals("video/mp4", page.media.get("https://media.example.com/v?x=1").mime);
        assertEquals("audio/mpeg", page.media.get("https://media.example.com/original.mp3").mime);
        assertTrue(LinkPage.html(Jsoup.parse("""
                <script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"itemId":"1"}}};</script>
                """), url).media.isEmpty());
    }

    @Test public void httpRedirectDownloadAndText() throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        String host = "http://127.0.0.1:" + server.getLocalPort();
        byte[] video = {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] audio = {'I', 'D', '3', 3, 0, 0};
        Thread fixture = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String path = input.readLine().split(" ")[1];
                    String header;
                    while ((header = input.readLine()) != null && !header.isEmpty()) { }
                    switch (path) {
                        case "/share": respond(socket, "302 Found\r\nLocation: /post", "text/plain", new byte[0]); break;
                        case "/post": ok(socket, "text/html; charset=utf-8", "<article>网页正文</article><video src='/video'></video><audio src='/voice.mp3'></audio>".getBytes(StandardCharsets.UTF_8)); break;
                        case "/video": ok(socket, "video/mp4", video); break;
                        case "/voice.mp3": ok(socket, "audio/mpeg", audio); break;
                        case "/note": ok(socket, "text/plain; charset=utf-8", "文字\n第二行".getBytes(StandardCharsets.UTF_8)); break;
                        case "/bad.mp4": ok(socket, "application/octet-stream", "<html>denied</html>".getBytes(StandardCharsets.UTF_8)); break;
                        default: respond(socket, "404 Not Found", "text/plain", new byte[0]);
                    }
                } catch (IOException ignored) { return; }
            }
        });
        fixture.setDaemon(true);
        fixture.start();
        File out = File.createTempFile("pk-test", ".tmp");
        try {
            LinkTransfer transfer = new LinkTransfer();
            LinkPage page = transfer.read(LinkPage.http(host + "/share"));
            assertEquals(host + "/post", page.url.toString());
            assertEquals("网页正文", page.text);
            assertEquals("文字\n第二行", transfer.read(LinkPage.http(host + "/note")).text);
            assertEquals(1, transfer.read(LinkPage.http(host + "/video")).media.size());
            transfer.download(page.media.get(host + "/video"), page.url, LinkTransfer.MOBILE_UA, out);
            assertArrayEquals(video, Files.readAllBytes(out.toPath()));
            transfer.download(page.media.get(host + "/voice.mp3"), page.url, LinkTransfer.MOBILE_UA, out);
            assertArrayEquals(audio, Files.readAllBytes(out.toPath()));
            LinkPage.Item bad = new LinkPage.Item(LinkPage.http(host + "/bad.mp4"), "video/mp4");
            assertThrows(IOException.class, () -> transfer.download(bad, page.url, LinkTransfer.MOBILE_UA, out));
            assertArrayEquals(audio, Files.readAllBytes(out.toPath()));
        } finally {
            server.close();
            fixture.join(3000);
            Files.deleteIfExists(out.toPath());
        }
    }

    private static void ok(Socket socket, String mime, byte[] data) throws IOException {
        respond(socket, "200 OK", mime, data);
    }

    private static void respond(Socket socket, String status, String mime, byte[] data) throws IOException {
        var output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + data.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(data);
    }
}
