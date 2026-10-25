package cn.xianhua.pocketkit.reader;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small allow-list sanitizer; presentation attributes and executable/resource tags are dropped.
 *
 * <p>Every reader format ends up here, and the result is the only thing the WebView ever sees. Text
 * is decoded and re-escaped so sanitizing is idempotent: a source entity and a renderer escape both
 * come out as one correct escape instead of showing {@code &amp;} to the reader.
 */
public final class HtmlPolicy {
    private static final Set<String> TAGS = Set.of("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "blockquote", "pre", "code", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "strong", "b", "em", "i", "del", "hr", "a");
    private static final Set<String> VOID = Set.of("br", "hr");
    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9]*)([^>]*)>");
    private static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern RAW_BLOCK = Pattern.compile("(?is)<(script|style|title|noscript|template)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9A-Fa-f]{1,6});");

    private HtmlPolicy() { }

    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) return "";
        // Comments first so a split tag like "<scr<!-- -->ipt>" cannot survive as a tag.
        String text = RAW_BLOCK.matcher(COMMENT.matcher(input).replaceAll("")).replaceAll("");
        StringBuilder out = new StringBuilder(Math.min(text.length() + 64, ReaderIo.MAX_TEXT_BYTES));
        Matcher matcher = TAG.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            appendText(out, text.substring(cursor, matcher.start()));
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            if (TAGS.contains(name)) {
                boolean closing = !matcher.group(1).isEmpty();
                if (closing) {
                    if (!VOID.contains(name)) out.append("</").append(name).append('>');
                } else if (name.equals("a")) {
                    String href = href(matcher.group(3));
                    out.append("<a");
                    if (href != null) out.append(" href=\"").append(escapeAttribute(href)).append("\"");
                    out.append('>');
                } else out.append('<').append(name).append('>');
            }
            cursor = matcher.end();
        }
        appendText(out, text.substring(cursor));
        // An image is dropped by the allow-list, which would otherwise leave a blank paragraph behind.
        return out.toString().replace("<p></p>", "");
    }

    public static String plain(String safeHtml) {
        String withBreaks = safeHtml.replaceAll("(?i)</?(?:p|h[1-6]|li|blockquote|pre|tr|table|ul|ol|div)[^>]*>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</t[dh]\\s*>", "\t").replaceAll("<[^>]*>", "");
        return entities(withBreaks).replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void appendText(StringBuilder out, String raw) {
        if (!raw.isEmpty()) out.append(escape(entities(raw)));
    }

    private static String escapeAttribute(String text) {
        return escape(text).replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Only real web targets survive, plus the reader's own anchor scheme. The WebView intercepts the
     * anchor scheme and never resolves it as a URL; anything else is dropped, so a link cannot turn
     * into a local file read.
     */
    private static String href(String attributes) {
        Matcher matcher = HREF.matcher(attributes == null ? "" : attributes);
        if (!matcher.find()) return null;
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        String trimmed = entities(value).trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("https://") || trimmed.startsWith("http://") || trimmed.startsWith("mailto:")
                || trimmed.startsWith(ReaderChapter.ANCHOR_PREFIX)
                ? entities(value).trim() : null;
    }

    /** Decodes the entities a document can carry into raw characters; escaping happens afterwards. */
    static String entities(String value) {
        Matcher numeric = NUMERIC.matcher(value);
        StringBuffer decoded = new StringBuffer(value.length());
        while (numeric.find()) {
            int code = -1;
            try {
                code = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException overflow) {
                // Out-of-range reference: leave the source text for the escaper instead of inventing a character.
            }
            String replacement = code > 0 && code <= 0x10FFFF && (code < 0xD800 || code > 0xDFFF)
                    ? new String(Character.toChars(code)) : numeric.group();
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(decoded);
        return decoded.toString()
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
