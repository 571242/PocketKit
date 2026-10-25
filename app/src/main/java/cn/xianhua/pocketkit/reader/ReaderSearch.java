package cn.xianhua.pocketkit.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * Text search over the already-parsed model.
 *
 * <p>Finding in the parsed text (not in the HTML) is what makes the reported count trustworthy: the
 * number the reader sees cannot disagree with what the page contains, and a jump always knows which
 * section to open. Highlighting itself is left to the WebView's own find, which needs no script.
 */
public final class ReaderSearch {
    public record Hit(int section, int offset, String snippet) { }

    private ReaderSearch() { }

    public static List<Hit> find(ReaderDocument document, String query, int limit) {
        List<Hit> hits = new ArrayList<>();
        if (document == null || query == null || query.isBlank() || limit <= 0) return hits;
        String needle = query.trim();
        List<ReaderDocument.Section> sections = document.sections();
        for (int index = 0; index < sections.size() && hits.size() < limit; index++) {
            String text = sections.get(index).plainText();
            for (int at = text.indexOf(needle); at >= 0 && hits.size() < limit; at = text.indexOf(needle, at + needle.length())) {
                hits.add(new Hit(index, at, snippet(text, at, needle.length())));
            }
        }
        return hits;
    }

    /** How many hits a single section holds, for the per-section counter above the find bar. */
    public static int countInSection(ReaderDocument document, int section, String query) {
        if (document == null || query == null || query.isBlank()) return 0;
        List<ReaderDocument.Section> sections = document.sections();
        if (section < 0 || section >= sections.size()) return 0;
        String text = sections.get(section).plainText();
        String needle = query.trim();
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    /** Two words of context on each side; enough to recognise the line without re-reading the page. */
    private static String snippet(String text, int offset, int length) {
        int start = Math.max(0, offset - 24);
        int end = Math.min(text.length(), offset + length + 24);
        String context = text.substring(start, end).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + context + (end < text.length() ? "…" : "");
    }
}
