package cn.xianhua.pocketkit.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds text that should be masked: phone numbers, e-mail addresses and card-like digit runs.
 *
 * <p>Deliberately plain regex over recognised text instead of a name/address NER model: the
 * patterns here are verifiable, and a detector that guesses at names would either miss most of a
 * Chinese document or paint over ordinary prose. What it can find, it finds exactly.
 */
public final class RedactCore {

    public enum Kind {
        PHONE("电话"), EMAIL("邮箱"), CARD("卡号");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** A half-open span of the input that should be covered. */
    public static final class Span {
        public final int start;
        public final int end;
        public final Kind kind;
        public final String text;

        Span(int start, int end, Kind kind, String text) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.text = text;
        }

        public int length() {
            return end - start;
        }

        @Override public String toString() {
            return kind.label + " " + text + " [" + start + "," + end + ")";
        }
    }

    // CN mobile: 1[3-9] + 9 digits, tolerating spaces/dashes; also +86 prefix.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])(?:\\+?86[\\s-]?)?1[3-9](?:[\\s-]?[0-9]){9}(?![0-9])");
    // Landline with area code, e.g. 010-12345678 / 0571-1234567
    private static final Pattern LANDLINE = Pattern.compile(
            "(?<![0-9])0[0-9]{2,3}[\\s-]?[0-9]{7,8}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // 13-19 digits optionally grouped, which covers cards and CN ID numbers.
    private static final Pattern CARD_GROUPED = Pattern.compile(
            "(?<![0-9])(?:[0-9]{4}[\\s-]){3}[0-9]{4}(?![0-9])");
    private static final Pattern CARD_RUN = Pattern.compile(
            "(?<![0-9])[0-9]{15,19}(?![0-9])");

    private RedactCore() {
    }

    /**
     * All spans to cover, sorted, with overlaps merged (longest wins) so a card number inside a
     * phone-like run is not masked twice.
     */
    public static List<Span> find(String text, boolean phone, boolean email, boolean card) {
        List<Span> found = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        if (phone) {
            collect(PHONE, text, Kind.PHONE, found);
            collect(LANDLINE, text, Kind.PHONE, found);
        }
        if (email) {
            collect(EMAIL, text, Kind.EMAIL, found);
        }
        if (card) {
            collect(CARD_GROUPED, text, Kind.CARD, found);
            collect(CARD_RUN, text, Kind.CARD, found);
        }
        return merge(found);
    }

    private static void collect(Pattern p, String text, Kind kind, List<Span> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String hit = m.group();
            // A grouped "phone" that is really 16 digits is a card; keep the longer classification.
            out.add(new Span(m.start(), m.end(), kind, hit));
        }
    }

    /** Sorts by start then length, and drops any span that overlaps one already kept. */
    static List<Span> merge(List<Span> raw) {
        Collections.sort(raw, (a, b) -> a.start != b.start ? a.start - b.start : b.length() - a.length());
        List<Span> kept = new ArrayList<>();
        int cursor = -1;
        for (Span s : raw) {
            if (s.start >= cursor) {
                kept.add(s);
                cursor = s.end;
            }
        }
        return kept;
    }

    /** Replaces every span with {@code mask} repeated over its length; keeps indexes aligned. */
    public static String apply(String text, List<Span> spans, String mask) {
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (Span s : spans) {
            String rep = repeat(mask, s.length());
            sb.replace(s.start, s.end, rep);
        }
        return sb.toString();
    }

    static String repeat(String unit, int count) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < count) {
            sb.append(unit);
        }
        return sb.substring(0, count);
    }
}
