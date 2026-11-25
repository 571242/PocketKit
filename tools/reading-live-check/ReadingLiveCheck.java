import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Detail;
import cn.xianhua.pocketkit.reading.ReadingModels.Item;
import cn.xianhua.pocketkit.reading.ReadingModels.Playback;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real-network smoke test for the Android client's own engine, no UI and no test doubles.
 *
 * Usage: java ReadingLiveCheck <bundle.json> [query] [name-filter]
 *   bundle    app/src/main/assets/reading/builtin-sources.json (or the live package)
 *   query     search keyword, default 斗破苍穹
 *   filter    only sources whose name contains this string
 *
 * Novel  : search -> detail -> first chapters -> non-empty text
 * Manga  : search -> detail -> first chapter -> at least one image URL
 * Video  : search -> detail -> first episode -> playable URL (+ .m3u8 mime)
 */
public final class ReadingLiveCheck {
    public static void main(String[] args) throws Exception {
        String raw = new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8);
        String query = args.length > 1 ? args[1] : "斗破苍穹";
        String filter = args.length > 2 ? args[2] : "";
        JSONObject bundle = new JSONObject(raw);
        JSONArray sources = bundle.getJSONArray("sources");
        ReadingEngine engine = new ReadingEngine();
        int passed = 0, failed = 0;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject object = sources.getJSONObject(i);
            Source source = Source.fromJson(object);
            if (!filter.isEmpty() && !source.name.contains(filter)) continue;
            long started = System.currentTimeMillis();
            try {
                java.util.List<Item> hits = engine.search(source, query);
                if (hits.isEmpty()) throw new IllegalStateException("search returned 0");
                Detail detail = engine.detail(source, hits.get(0));
                if (detail.chapters.isEmpty()) throw new IllegalStateException("toc returned 0");
                String verdict;
                if ("manga".equals(source.kind)) {
                    Chapter chapter = detail.chapters.get(0);
                    java.util.List<String> images = engine.chapterImages(source, chapter);
                    if (images.isEmpty()) throw new IllegalStateException("chapter returned 0 images");
                    verdict = "toc=" + detail.chapters.size() + "\timages=" + images.size()
                            + "\tfirst=" + images.get(0);
                } else if ("video".equals(source.kind)) {
                    Playback playback = engine.playback(source, detail.chapters.get(0));
                    if (playback.url == null || playback.url.isBlank()) throw new IllegalStateException("no play url");
                    verdict = "episodes=" + detail.chapters.size() + "\tmime=" + playback.mimeType
                            + "\turl=" + playback.url;
                } else {
                    int checked = 0, chars = 0;
                    for (int c = 0; c < Math.min(3, detail.chapters.size()); c++) {
                        String text = engine.chapter(source, detail.chapters.get(c));
                        if (text == null || text.isBlank()) throw new IllegalStateException("empty chapter");
                        chars += text.length();
                        checked++;
                    }
                    verdict = "toc=" + detail.chapters.size() + "\tchapters=" + checked + "\tchars=" + chars;
                }
                System.out.println("PASS\t" + source.kind + "\t" + source.name + "\tsearch=" + hits.size()
                        + "\t" + verdict + "\t" + (System.currentTimeMillis() - started) + "ms");
                passed++;
            } catch (Throwable error) {
                System.out.println("FAIL\t" + source.kind + "\t" + source.name + "\t"
                        + error.getClass().getSimpleName() + ": " + error.getMessage()
                        + "\t" + (System.currentTimeMillis() - started) + "ms");
                failed++;
            }
        }
        System.out.println("SUMMARY\tpassed=" + passed + "\tfailed=" + failed);
        if (passed == 0) System.exit(2);
    }
}
