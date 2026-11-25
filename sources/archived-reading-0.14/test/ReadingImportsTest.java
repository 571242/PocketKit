package cn.xianhua.pocketkit.reading;

import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReadingImportsTest {
    private JSONObject novel() throws Exception {
        return new JSONObject().put("bookSourceUrl", "https://example.org")
                .put("bookSourceName", "测试书源").put("bookSourceType", 0)
                .put("searchUrl", "/search?q={{key}}")
                .put("ruleSearch", new JSONObject().put("bookList", ".book"))
                .put("ruleToc", new JSONObject().put("chapterList", "a.chapter"))
                .put("ruleContent", new JSONObject().put("content", "#content@text"));
    }

    @Test public void legadoImportPreservesRuleAndAssignsStableId() throws Exception {
        JSONObject rule = novel();
        Source first = ReadingImports.parse(rule.toString(), "subscription-one").get(0);
        rule.put("bookSourceName", "改名仍是同一个源");
        Source renamed = ReadingImports.parse(rule.toString(), "subscription-two").get(0);
        assertEquals("novel", first.kind);
        assertEquals(first.id, renamed.id);
        assertEquals("subscription-one", first.subscriptionId);
        assertEquals("#content@text", first.rule.getJSONObject("ruleContent").getString("content"));
        assertNotEquals(first.upstreamHash, renamed.upstreamHash);
    }

    @Test public void contentHashIgnoresObjectKeyOrderAndProviderTimestamp() throws Exception {
        JSONObject rule = novel();
        Source first = ReadingImports.parse(rule.toString(), "").get(0);
        rule.put("lastUpdateTime", 9999999).put("customOrder", 20);
        Source later = ReadingImports.parse(rule.toString(), "").get(0);
        assertEquals(first.upstreamHash, later.upstreamHash);
        assertEquals(ReadingImports.canonical(new JSONObject("{\"a\":1,\"b\":2}")),
                ReadingImports.canonical(new JSONObject("{\"b\":2,\"a\":1}")));
    }

    @Test public void webShortcutIsNotClassifiedAsNovel() throws Exception {
        Source source = ReadingImports.parse("{\"sourceUrl\":\"https://example.org\",\"sourceName\":\"源仓库\",\"singleUrl\":true}", "").get(0);
        assertEquals("web", source.kind);
        // A feed definition is still rule-driven: imported as a novel source, not silently dropped.
        Source rss = ReadingImports.parse("{\"sourceUrl\":\"https://example.org/feed\",\"singleUrl\":false,\"ruleArticles\":\"item\"}", "").get(0);
        assertEquals("novel", rss.kind);
    }

    @Test public void mangaTypesAreAccepted() throws Exception {
        JSONObject manga = novel().put("bookSourceType", 2).put("bookSourceName", "漫画源");
        assertEquals("manga", ReadingImports.parse(manga.toString(), "").get(0).kind);
        JSONObject named = novel().put("bookSourceGroup", "漫画").put("bookSourceName", "某漫画");
        assertEquals("manga", ReadingImports.parse(named.toString(), "").get(0).kind);
        // Explicit unknown types stay refused instead of being guessed as text.
        assertEquals("unsupported", ReadingImports.parse(novel().put("bookSourceType", 3).toString(), "").get(0).kind);
    }

    @Test public void remoteMetadataCannotPretendItIsALocalEdit() throws Exception {
        Source source = ReadingImports.normalize(novel());
        JSONObject wrapper = source.toJson().put("localModified", true).put("subscriptionId", "forged").put("upstreamHash", "forged").put("originKey", "forged");
        Source imported = ReadingImports.parse(wrapper.toString(), "trusted-context").get(0);
        assertFalse(imported.localModified);
        assertEquals("trusted-context", imported.subscriptionId);
        assertNotEquals("forged", imported.upstreamHash);
        assertNotEquals("forged", imported.originKey);
    }

    @Test public void duplicateSourcesDoNotCreateDuplicateEntries() throws Exception {
        String input = new JSONArray().put(novel()).put(novel()).toString();
        assertEquals(1, ReadingImports.parse(input, "").size());
    }

    @Test public void curatedEnvelopeAndBomAreAccepted() throws Exception {
        JSONObject envelope = new JSONObject().put("schemaVersion", 1).put("sources", new JSONArray().put(novel()));
        List<Source> result = ReadingImports.parse("\uFEFF" + envelope, "builtin-curated");
        assertEquals(1, result.size());
        assertEquals("builtin-curated", result.get(0).subscriptionId);
    }

    @Test public void serializedRuleObjectsAreNormalized() throws Exception {
        JSONObject rule = novel().put("ruleToc", "{\"chapterList\":\"a\"}");
        Source s = ReadingImports.parse(rule.toString(), "").get(0);
        assertEquals("a", s.rule.getJSONObject("ruleToc").getString("chapterList"));
    }

    @Test public void videoCmsAndDirectDefinitionsRemainVideo() throws Exception {
        Source cms = ReadingImports.parse("{\"type\":\"cms\",\"api\":\"https://example.org/api.php\",\"name\":\"公开视频\"}", "").get(0);
        assertEquals("video", cms.kind);
        assertEquals("cms", cms.rule.getString("type"));
        assertEquals("https://example.org/api.php", cms.url);
    }

    @Test public void malformedAndEmptyCatalogsAreRejectedRatherThanClearingAnything() throws Exception {
        for (String input : new String[]{"[]", "{}", "<html>not json</html>", "[] trailing", "{\"schemaVersion\":2,\"sources\":[]}", "[1]", "{\"sourceUrls\":[\"https://example.org\"]}"}) {
            try { ReadingImports.parse(input, ""); fail("should reject: " + input); }
            catch (Exception expected) { assertNotNull(expected.getMessage()); }
        }
    }
}
