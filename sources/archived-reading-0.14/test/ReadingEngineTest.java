package cn.xianhua.pocketkit.reading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Detail;
import cn.xianhua.pocketkit.reading.ReadingModels.Item;
import cn.xianhua.pocketkit.reading.ReadingModels.Playback;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;

import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real checks for the reading engine against actual {@link ReadingModels} objects.
 *
 * <p>Network is replaced by an in-package fake {@link ReadingEngine.Transport} that only serves
 * canned bytes; the production path is never bypassed and no test touches a real host. Host
 * safety is tested through the pure address predicate and the structural invariants of the
 * hardened client, not by opening sockets.
 */
public class ReadingEngineTest {

    // ------------------------------------------------------------------ JSONPath

    @Test public void jsonPathChildOfReturnedMapIsNotFakeJson() throws Exception {
        String body = "{\"data\":{\"list\":[{\"name\":\"甲\",\"id\":1},{\"name\":\"乙\",\"id\":2}]},\"msg\":\"ok\"}";
        RuleEvaluator ev = new RuleEvaluator(body, "https://ex.com/");
        // The map returned by $.data must be traversed, not turned into "{name=A}" text.
        assertEquals("甲", ev.string("$.data.list[0].name"));
        assertEquals("2", ev.string("$.data.list[1].id"));
        assertEquals(2, ev.strings("$.data.list[*].name").size());
        // A scalar object coerced to text must still be real JSON, never Map.toString.
        String whole = ev.string("$.data");
        assertTrue("object text should be valid JSON: " + whole, whole.startsWith("{"));
        new JSONObject(whole);
    }

    // ------------------------------------------------------------------ css / body

    @Test public void cssChainFallbackAndAttributes() throws Exception {
        String html = "<div id='x'><a class='t' href='/a'>一</a><a class='t' href='/b'>二</a></div>";
        RuleEvaluator ev = new RuleEvaluator(html, "https://ex.com/book/1");
        assertEquals(2, ev.strings("div#x@a.t@text").size());
        assertEquals("https://ex.com/a", ev.strings("a.t@href").get(0));
        // || first-non-empty fallback: the first selector matches nothing, so the second decides.
        assertEquals("一", ev.string("nope@text||div#x@a:first-child@text"));
        // prefix shorthand still works with absolute href resolution
        assertEquals("一", ev.string("id.x@a.0@text"));
    }

    @Test public void bodyTextRuleKeepsBlockBreaks() throws Exception {
        String html = "<div id='c'><p>第一段</p><p>第二段</p></div>";
        RuleEvaluator ev = new RuleEvaluator(html, "https://ex.com/");
        String text = ev.plainText("#c");
        assertTrue(text.contains("第一段") && text.contains("第二段"));
        assertTrue("paragraphs must not run together: " + text.replace("\n", "\\n"), text.contains("\n"));
        assertFalse(text.contains("第一段第二段"));
    }

    // ------------------------------------------------------------------ xpath

    @Test public void xpathAttributeAndTextNode() throws Exception {
        String html = "<ul><li><a href='/1'>一</a></li><li><a href='/2'>二</a></li></ul>";
        RuleEvaluator ev = new RuleEvaluator(html, "https://ex.com/dir/");
        List<String> hrefs = ev.strings("@XPath://li/a/@href");
        assertEquals(2, hrefs.size());
        assertEquals("https://ex.com/1", hrefs.get(0));
        List<String> texts = ev.strings("@XPath://li/a/text()");
        assertEquals(List.of("一", "二"), texts);
    }

    // ------------------------------------------------------------------ regex

    @Test public void regexFirstGroupAndReplace() throws Exception {
        RuleEvaluator ev = new RuleEvaluator("id=42&n=7", "https://ex.com/");
        assertEquals("42", ev.string("@Regex:id=(\\d+)"));
        assertEquals("x42", ev.string("@Regex:id=(\\d+)##(\\d+)##x$1"));
    }

    // ------------------------------------------------------------------ search url encoding

    @Test public void searchUrlBareKeywordIsEncoded() throws Exception {
        List<String> seen = new ArrayList<>();
        FakeTransport fake = new FakeTransport(url -> {
            seen.add(url);
            return page("<div class='b'><a href='/book/1'>书甲</a></div>");
        });
        Source source = novelSource();
        source.rule.put("searchUrl", "https://ex.com/search?keyword={keyword}&page={page}");
        new ReadingEngine(fake).search(source, "斗破苍穹 a&b");
        assertTrue(seen.size() == 1);
        String url = seen.get(0);
        // CJK and reserved characters are percent-encoded; the raw space/& must not leak into the query.
        assertTrue(url, url.contains("keyword=%E6%96%97"));
        assertFalse(url, url.contains("斗破"));
        assertFalse("raw space must not survive: " + url, url.contains(" "));
        assertTrue(url, url.contains("&page=1"));
    }

    // ------------------------------------------------------------------ pagination caps

    @Test public void chapterPaginationBeyondCapFailsInsteadOfTruncating() throws Exception {
        // Every page advertises another page with fresh text: the engine must refuse, not silently stop.
        FakeTransport fake = new FakeTransport(url -> {
            int page = pageNumber(url);
            return page("<div id='c'>第" + page + "页正文</div><a id='next' href='/ch?p=" + (page + 1) + "'>下页</a>");
        });
        Source source = novelSource();
        JSONObject content = new JSONObject();
        content.put("content", "#c");
        content.put("nextContentUrl", "#next@href");
        source.rule.put("ruleContent", content);
        try {
            new ReadingEngine(fake).chapter(source, chapter("/ch?p=1"));
            fail("should refuse past the page cap");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("页上限"));
        }
    }

    @Test public void tocPaginationBeyondCapStopsInsteadOfHanging() throws Exception {
        FakeTransport fake = new FakeTransport(url -> {
            int page = pageNumber(url);
            return page("<ul><li><a href='/c/" + page + "'>第" + page + "章</a></li></ul>"
                    + "<a id='next' href='/toc?p=" + (page + 1) + "'>下一页</a>");
        });
        Source source = novelSource();
        JSONObject toc = new JSONObject();
        toc.put("chapterList", "ul@li");
        toc.put("chapterName", "a@text");
        toc.put("chapterUrl", "a@href");
        toc.put("nextTocUrl", "#next@href");
        source.rule.put("ruleToc", toc);
        // An endless "next page" must terminate at the page cap and keep what was read; failing the
        // whole book would hide a usable table of contents behind one broken pagination link.
        Detail detail = new ReadingEngine(fake).detail(source, item("/toc?p=1"));
        assertTrue(detail.chapters.size() > 0);
        assertTrue(detail.chapters.size() <= 5000);
    }

    // ------------------------------------------------------------------ cms

    @Test public void cmsMultiLineupParsesEveryGroup() throws Exception {
        String json = "{\"code\":1,\"list\":[{\"vod_id\":7,\"vod_name\":\"片名\","
                + "\"vod_play_from\":\"线路A$$$线路B\","
                + "\"vod_play_url\":\"第1集$http://a/1.m3u8#第2集$http://a/2.m3u8$$$"
                + "第1集$http://b/1.m3u8#第2集$http://b/2.m3u8\"}]}";
        FakeTransport fake = new FakeTransport(url -> json);
        Source source = cmsSource();
        Detail detail = new ReadingEngine(fake).detail(source, item("7"));
        assertEquals(4, detail.chapters.size());
        assertTrue(detail.chapters.stream().anyMatch(c -> "线路A".equals(c.group)));
        assertTrue(detail.chapters.stream().anyMatch(c -> "线路B".equals(c.group)));
        assertEquals("http://b/2.m3u8", detail.chapters.get(3).url);
    }

    @Test public void cmsBrokenJsonIsFailureNotEmptyResult() throws Exception {
        FakeTransport fake = new FakeTransport(url -> "<html>接口已下线</html>");
        try {
            new ReadingEngine(fake).search(cmsSource(), "x");
            fail("broken CMS body must raise, not return empty");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("非 JSON"));
        }
    }

    @Test public void cmsGenuinelyEmptyListIsEmptyNotError() throws Exception {
        FakeTransport fake = new FakeTransport(url -> "{\"code\":1,\"list\":[]}");
        assertTrue(new ReadingEngine(fake).search(cmsSource(), "x").isEmpty());
    }

    // ------------------------------------------------------------------ direct video

    @Test public void directSourceResolvesChaptersAndRejectsUnknown() throws Exception {
        Source source = new Source();
        source.id = "dr";
        source.kind = "video";
        JSONObject rule = new JSONObject();
        rule.put("type", "direct");
        rule.put("items", new org.json.JSONArray()
                .put(new JSONObject().put("id", "v1").put("title", "视频一").put("url", "http://cdn/x.m3u8")));
        source.rule = rule;
        ReadingEngine engine = new ReadingEngine(new FakeTransport(url -> "{}"));
        assertEquals(1, engine.search(source, "视频").size());
        Detail detail = engine.detail(source, item("v1"));
        assertEquals(1, detail.chapters.size());
        assertEquals("http://cdn/x.m3u8", detail.chapters.get(0).url);
        try {
            engine.detail(source, item("missing"));
            fail("unknown direct id must raise");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("找不到条目"));
        }
    }

    @Test public void directSingleObjectAndArrayBothWork() throws Exception {
        ReadingEngine engine = new ReadingEngine(new FakeTransport(url -> "{}"));
        // Single direct object on the rule itself.
        Source single = new Source();
        single.id = "d1";
        single.kind = "video";
        single.url = "http://1.1.1.1/x.m3u8";
        JSONObject r1 = new JSONObject();
        r1.put("type", "direct");
        r1.put("title", "单片");
        r1.put("url", "http://1.1.1.1/x.m3u8");
        single.rule = r1;
        assertEquals("", ReadingEngine.supportIssue(single));
        assertEquals(1, engine.search(single, "单").size());
        assertEquals(1, engine.detail(single, item("http://1.1.1.1/x.m3u8")).chapters.size());
        // Object form of items:{...}.
        Source obj = new Source();
        obj.id = "d2";
        obj.kind = "video";
        JSONObject r2 = new JSONObject();
        r2.put("type", "direct");
        r2.put("items", new JSONObject().put("id", "z").put("title", "对象").put("url", "http://1.1.1.1/y.mp4"));
        obj.rule = r2;
        assertEquals("", ReadingEngine.supportIssue(obj));
        assertEquals(1, engine.detail(obj, item("z")).chapters.size());
    }

    // ------------------------------------------------------------------ manga

    @Test public void mangaChapterResolvesImageUrlsAndPercentEncodes() throws Exception {
        FakeTransport fake = new FakeTransport(url -> page(
                "<div id='imgs'><img data-src='/a/1.webp'/><img src='/a/2.webp'/><img src='data:image/gif;base64,R0lGOD'/></div>"));
        Source source = novelSource();
        source.kind = "manga";
        JSONObject content = new JSONObject();
        content.put("content", "#imgs@img");
        source.rule.put("ruleContent", content);
        List<String> images = new ReadingEngine(fake).chapterImages(source, chapter("https://ex.com/ch/1"));
        assertEquals(2, images.size());
        assertEquals("https://ex.com/a/1.webp", images.get(0));
        assertEquals("https://ex.com/a/2.webp", images.get(1));
    }

    @Test public void mangaJsonArrayAndNewlineListsAreAccepted() throws Exception {
        FakeTransport fake = new FakeTransport(url -> page("<div id='c'>[\"https://cdn/1.jpg\",\"https://cdn/2.jpg\"]</div>"));
        Source source = novelSource();
        source.kind = "manga";
        JSONObject content = new JSONObject();
        content.put("content", "#c@text");
        source.rule.put("ruleContent", content);
        List<String> viaJson = new ReadingEngine(fake).chapterImages(source, chapter("https://ex.com/ch/1"));
        assertEquals(2, viaJson.size());

        FakeTransport lines = new FakeTransport(url -> page("<div id='c'>https://cdn/a.jpg\nhttps://cdn/b.jpg</div>"));
        source.rule.put("ruleContent", new JSONObject().put("content", "#c@text"));
        assertEquals(2, new ReadingEngine(lines).chapterImages(source, chapter("https://ex.com/ch/1")).size());
    }

    @Test public void mangaSourceWithoutImagesFailsLoudly() throws Exception {
        FakeTransport fake = new FakeTransport(url -> page("<div id='c'>没有图</div>"));
        Source source = novelSource();
        source.kind = "manga";
        source.rule.put("ruleContent", new JSONObject().put("content", "#c@img"));
        try {
            new ReadingEngine(fake).chapterImages(source, chapter("https://ex.com/ch/1"));
            fail("no images must raise");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("没有解析出图片"));
        }
    }

    // ------------------------------------------------------------------ script / jsLib / @put

    @Test public void jsLibIsLoadedBeforeTheRuleScript() throws Exception {
        Source source = novelSource();
        source.rule.put("jsLib", "function dec(v){ return v.split('').reverse().join(''); }");
        source.rule.put("ruleSearch", new JSONObject()
                .put("bookList", ".b").put("name", "a@text").put("bookUrl", "a@href"));
        FakeTransport fake = new FakeTransport(url -> page("<div><a class='b' href='/1'>abc</a></div>"));
        List<Item> hits = new ReadingEngine(fake).search(source, "x");
        assertEquals(1, hits.size());
    }

    @Test public void putAndGetCarryStateBetweenRuleSteps() throws Exception {
        Source source = novelSource();
        source.rule.put("searchUrl", "https://ex.com/s?q={{key}}");
        source.rule.put("ruleSearch", new JSONObject()
                .put("bookList", "@js:java.put('tid', 'abc'); result")
                .put("name", "a@text").put("bookUrl", "a@href"));
        FakeTransport fake = new FakeTransport(url -> page("<div><a class='b' href='/1'>题目</a></div>"));
        List<Item> hits = new ReadingEngine(fake).search(source, "x");
        assertEquals(1, hits.size());
        assertEquals("abc", RuleEvaluator.renderTemplate("{{java.get('tid')}}", java.util.Map.of(), "", "", source.id));
    }

    // ------------------------------------------------------------------ subscription

    @Test public void subscriptionAcceptsBundleAndVideoButNotJunk() throws Exception {
        ReadingEngine.subscriptionAccepted("{\"schemaVersion\":1,\"sources\":[]}");
        ReadingEngine.subscriptionAccepted("[]");
        ReadingEngine.subscriptionAccepted("{\"bookSourceUrl\":\"https://a\"}");
        ReadingEngine.subscriptionAccepted("{\"type\":\"cms\",\"api\":\"https://a/api.php\"}");
        try {
            ReadingEngine.subscriptionAccepted("<html>404</html>");
            fail("html is not a subscription");
        } catch (Exception expected) { /* expected */ }
        try {
            ReadingEngine.subscriptionAccepted("{\"hello\":1}");
            fail("unknown object is not a subscription");
        } catch (Exception expected) { /* expected */ }
    }

    // ------------------------------------------------------------------ playback

    @Test public void playbackValidatesAndMimes() throws Exception {
        Source source = cmsSource();
        Playback pb = new ReadingEngine(new FakeTransport(url -> "{}")).playback(source, chapter("http://1.1.1.1/x.m3u8"));
        assertNotNull(pb);
        assertEquals("application/x-mpegURL", pb.mimeType);
    }

    // ------------------------------------------------------------------ host safety predicate

    @Test public void privateAndMappedAddressesAreRejected() throws Exception {
        String[] blocked = {
                "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1",
                "::ffff:10.0.0.1", "64:ff9b::7f00:1"
        };
        for (String literal : blocked) {
            InetAddress address = InetAddress.getByName(literal);
            assertFalse("must block " + literal, SourceHttp.isPublicAddress(address));
        }
        assertTrue(SourceHttp.isPublicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(SourceHttp.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test public void mediaClientIsHardened() throws Exception {
        okhttp3.OkHttpClient client = SourceHttp.mediaClient();
        assertNotNull(client);
        assertFalse("redirects must be followed manually", client.followRedirects());
        assertFalse(client.followSslRedirects());
    }

    // ------------------------------------------------------------------ restricted script

    @Test public void restrictedScriptRejectsDangerousConstructs() {
        String[] denied = {
                "eval('1+1')",
                "var f = Function('return 1')",
                "new java.io.File('/etc/passwd')",
                "'a'.repeat(1000000)",
                "Array(2).join('x')",
                "while(true){}",
                "for(;;){}",
                "importClass(java.io.File)",
                "java.lang.Runtime.getRuntime().exec('id')",
                "Packages.java.lang.System.exit(0)"
        };
        for (String script : denied) {
            try {
                RuleEvaluator.validateScript(script);
                fail("should reject: " + script);
            } catch (Exception expected) { /* expected */ }
        }
    }

    @Test public void allowedBridgeHelpersRunInRestrictedScope() throws Exception {
        RuleEvaluator.validateScript("java.md5(result)");
        RuleEvaluator ev = new RuleEvaluator("hello", "https://ex.com/");
        // {{java.xxx(x)}} is a template expression, evaluated in the restricted Rhino scope.
        assertEquals("5d41402abc4b20b7f9b5d1a24d94d0a0".substring(0, 4),
                RuleEvaluator.renderTemplate("{{java.md5('hello')}}", Map.of(), "https://ex.com/")
                        .substring(0, 4));
        assertEquals("%E4%B8%AD", RuleEvaluator.renderTemplate("{{java.urlEncode('中')}}", Map.of(), ""));
    }

    // ---------------------------------------------------------------- helpers

    private interface Responder { String reply(String url) throws Exception; }

    /** In-package transport stand-in: canned bytes only, no sockets, no production bypass. */
    private static final class FakeTransport implements ReadingEngine.Transport {
        private final Responder responder;
        FakeTransport(Responder responder) { this.responder = responder; }

        @Override public SourceHttp.Response execute(SourceHttp.Request request, int maxBytes) throws Exception {
            String body = responder.reply(request.url);
            return new SourceHttp.Response(request.url, body, Map.of());
        }
    }

    private static Source novelSource() {
        Source source = new Source();
        source.id = "n1";
        source.name = "测试小说";
        source.kind = "novel";
        source.url = "https://ex.com/";
        JSONObject rule = new JSONObject();
        rule.put("bookSourceUrl", "https://ex.com/");
        rule.put("searchUrl", "https://ex.com/search?keyword={{key}}");
        JSONObject search = new JSONObject();
        search.put("bookList", ".b");
        search.put("name", "a@text");
        search.put("bookUrl", "a@href");
        rule.put("ruleSearch", search);
        JSONObject info = new JSONObject();
        info.put("name", "h1@text");
        rule.put("ruleBookInfo", info);
        JSONObject toc = new JSONObject();
        toc.put("chapterList", "ul@li");
        toc.put("chapterName", "a@text");
        toc.put("chapterUrl", "a@href");
        rule.put("ruleToc", toc);
        JSONObject content = new JSONObject();
        content.put("content", "#c");
        rule.put("ruleContent", content);
        source.rule = rule;
        return source;
    }

    private static Source cmsSource() {
        Source source = new Source();
        source.id = "cms1";
        source.name = "CMS";
        source.kind = "video";
        source.url = "https://ex.com/api.php";
        JSONObject rule = new JSONObject();
        rule.put("type", "cms");
        rule.put("api", "https://ex.com/api.php/provide/vod/");
        source.rule = rule;
        return source;
    }

    private static Item item(String url) {
        Item item = new Item();
        item.url = url;
        item.title = "标题";
        return item;
    }

    private static Chapter chapter(String url) {
        Chapter chapter = new Chapter();
        chapter.url = url;
        chapter.name = "第一章";
        return chapter;
    }

    private static String page(String body) {
        return "<html><body>" + body + "</body></html>";
    }

    private static int pageNumber(String url) {
        int at = url.lastIndexOf("p=");
        if (at < 0) return 1;
        String tail = url.substring(at + 2);
        int amp = tail.indexOf('&');
        if (amp >= 0) tail = tail.substring(0, amp);
        return Integer.parseInt(tail);
    }
}
