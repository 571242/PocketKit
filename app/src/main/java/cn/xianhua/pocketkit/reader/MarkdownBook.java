package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Markdown through commonmark, split into chapters at first-level headings.
 *
 * <p>Raw HTML in the source is escaped rather than executed ({@code escapeHtml}), and the result is
 * still passed through {@link HtmlPolicy}, so a document can never reach the WebView as markup.
 */
public final class MarkdownBook {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private MarkdownBook() { }

    public static ReaderDocument parse(String fallbackTitle, String text) throws IOException {
        Parser parser = Parser.builder().extensions(EXTENSIONS).build();
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();
        Node root = parser.parse(text == null ? "" : text);
        List<List<Node>> groups = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Node> current = new ArrayList<>();
        String firstH1 = null;
        String firstHeading = null;
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            String heading = node instanceof Heading h ? literal(h) : null;
            boolean firstLevel = node instanceof Heading h && h.getLevel() == 1;
            if (heading != null && !heading.isBlank()) {
                if (firstHeading == null) firstHeading = heading;
                if (firstLevel && firstH1 == null) firstH1 = heading;
            }
            if (firstLevel && !current.isEmpty()) {
                groups.add(current);
                titles.add(firstHeading(current, fallbackTitle));
                current = new ArrayList<>();
            }
            current.add(node);
        }
        if (!current.isEmpty()) {
            groups.add(current);
            titles.add(firstHeading(current, fallbackTitle));
        }
        if (groups.isEmpty()) {
            return new ReaderDocument(ReaderDocument.Format.MARKDOWN, fallbackTitle,
                    List.of(new ReaderDocument.Section("正文", "", "")), "");
        }
        List<ReaderDocument.Section> sections = new ArrayList<>();
        int budget = ReaderIo.MAX_TEXT_BYTES;
        for (int i = 0; i < groups.size(); i++) {
            String html = HtmlPolicy.sanitize(render(renderer, groups.get(i)));
            budget -= html.length();
            if (budget < 0) throw new IOException("这份 Markdown 渲染后超过阅读上限，请先拆分文件再打开。");
            sections.add(new ReaderDocument.Section(titles.get(i), html, HtmlPolicy.plain(html)));
        }
        String title = firstH1 != null ? firstH1 : firstHeading != null ? firstHeading : fallbackTitle;
        return new ReaderDocument(ReaderDocument.Format.MARKDOWN, title, sections, "");
    }

    private static String render(HtmlRenderer renderer, List<Node> nodes) {
        StringBuilder html = new StringBuilder();
        for (Node node : nodes) html.append(renderer.render(node));
        return html.toString();
    }

    /** A chapter is named after its own first heading, which is what a reader sees at the top. */
    private static String firstHeading(List<Node> nodes, String fallback) {
        for (Node node : nodes) {
            if (node instanceof Heading heading) {
                String text = literal(heading);
                if (!text.isBlank()) return text;
            }
        }
        return fallback;
    }

    static String literal(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text t) text.append(t.getLiteral());
            else if (child instanceof org.commonmark.node.Code c) text.append(c.getLiteral());
            else text.append(literal(child));
        }
        return text.toString().trim();
    }
}
