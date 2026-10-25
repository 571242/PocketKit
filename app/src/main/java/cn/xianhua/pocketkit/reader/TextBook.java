package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.List;

/**
 * Plain text and offline HTML files.
 *
 * <p>Text is escaped and keeps its own line breaks: a code sample must look like the code, not like
 * a paragraph. HTML is sanitized down to the reading allow-list, so an offline page loses scripts
 * and styling but keeps headings, lists, tables and links.
 */
public final class TextBook {
    private TextBook() { }

    public static ReaderDocument parseText(String fallbackTitle, byte[] bytes) throws IOException {
        ReaderText.Decoded decoded = ReaderText.decode(bytes);
        // The sanitizer keeps no attributes, so the viewer styles every <pre> the same pre-wrap way.
        String html = "<pre>" + HtmlPolicy.escape(normalize(decoded.text())) + "</pre>";
        String safe = HtmlPolicy.sanitize(html);
        ReaderDocument.Section section = new ReaderDocument.Section("正文", safe, HtmlPolicy.plain(safe));
        String note = "UTF-8".equals(decoded.charset()) ? "" : "按 " + decoded.charset() + " 解码。";
        return new ReaderDocument(ReaderDocument.Format.TEXT, fallbackTitle, List.of(section), note);
    }

    public static ReaderDocument parseHtml(String fallbackTitle, byte[] bytes) throws IOException {
        String source = ReaderText.decode(bytes).text();
        String body = bodyOf(source);
        String html = HtmlPolicy.sanitize(body);
        String title = fallbackTitle;
        java.util.regex.Matcher heading = java.util.regex.Pattern
                .compile("(?is)<h[12][^>]*>(.*?)</h[12]>").matcher(source);
        if (heading.find()) {
            String candidate = HtmlPolicy.plain(HtmlPolicy.sanitize(heading.group(1)));
            if (!candidate.isBlank()) title = candidate;
        } else {
            java.util.regex.Matcher documentTitle = java.util.regex.Pattern
                    .compile("(?is)<title[^>]*>(.*?)</title>").matcher(source);
            if (documentTitle.find()) {
                String candidate = HtmlPolicy.entities(documentTitle.group(1)).trim();
                if (!candidate.isEmpty()) title = candidate;
            }
        }
        ReaderDocument.Section section = new ReaderDocument.Section("正文", html, HtmlPolicy.plain(html));
        return new ReaderDocument(ReaderDocument.Format.HTML, title, List.of(section), "");
    }

    /** Drops the document shell and every element the sanitizer would remove anyway. */
    private static String bodyOf(String source) {
        java.util.regex.Matcher body = java.util.regex.Pattern
                .compile("(?is)<body\\b[^>]*>(.*)</body\\s*>").matcher(source);
        String content = body.find() ? body.group(1) : source;
        return content.replaceAll("(?is)<!DOCTYPE[^>]*>", "")
                .replaceAll("(?is)<\\?xml[^>]*\\?>", "");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    }
}
