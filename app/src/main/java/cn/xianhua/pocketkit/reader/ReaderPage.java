package cn.xianhua.pocketkit.reader;

import java.util.Map;

/**
 * Fills the offline viewer template with one section.
 *
 * <p>The template is a shipped asset, so the reading stylesheet is edited as CSS instead of being
 * concatenated in Java. Pure string work: no Android type, so the same code is covered by the JVM
 * test with the real asset file. The WebView around it has scripts, files and network disabled.
 */
public final class ReaderPage {
    public static final String TEMPLATE_ASSET = "reader/reader.html";
    public static final int MIN_FONT = 13;
    public static final int MAX_FONT = 30;
    public static final int DEFAULT_FONT = 17;

    private static final Map<String, String> LIGHT = Map.of(
            "{{SCHEME}}", "light", "{{BG}}", "#F3F6FB", "{{INK}}", "#172D4D", "{{MUTED}}", "#61718A",
            "{{ACCENT}}", "#2264DC", "{{RULE}}", "#D7E0ED", "{{CODE_BG}}", "#E9EFF8",
            "{{SELECTION}}", "#BBD3FA");
    private static final Map<String, String> DARK = Map.of(
            "{{SCHEME}}", "dark", "{{BG}}", "#101B2B", "{{INK}}", "#E7EEFC", "{{MUTED}}", "#A8B8D1",
            "{{ACCENT}}", "#9BBCFF", "{{RULE}}", "#40516C", "{{CODE_BG}}", "#1B293E",
            "{{SELECTION}}", "#2C4470");

    private ReaderPage() { }

    public static String render(String template, ReaderDocument.Section section, boolean dark, int fontSp) {
        String html = template;
        for (Map.Entry<String, String> entry : (dark ? DARK : LIGHT).entrySet()) {
            html = html.replace(entry.getKey(), entry.getValue());
        }
        int clamped = Math.max(MIN_FONT, Math.min(MAX_FONT, fontSp));
        return html.replace("{{TITLE}}", HtmlPolicy.escape(section.title()))
                .replace("{{FONT}}", Integer.toString(clamped))
                // Lines stay readable on a tablet instead of stretching to an awkward measure.
                .replace("{{WIDTH}}", "760")
                .replace("{{BODY}}", section.html());
    }
}
