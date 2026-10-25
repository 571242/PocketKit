package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DOCX text and tables, one section per top-level heading.
 *
 * <p>Only content is reproduced: paragraph text, lists as bullet lines, tables as a grid. Fonts,
 * sizes, colours, columns, headers and images are not restored — this is a reader, not a renderer,
 * and the limitation says so on screen.
 */
public final class DocxBook {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final Map<String, byte[]> entries;
    private final List<ReaderDocument.Section> sections = new ArrayList<>();
    private final StringBuilder html = new StringBuilder();
    private String currentTitle = "正文";
    private boolean started;

    private DocxBook(Map<String, byte[]> entries) { this.entries = entries; }

    public static ReaderDocument parse(String fallbackTitle, Map<String, byte[]> entries) throws IOException {
        byte[] body = entries.get("word/document.xml");
        if (body == null) throw new IOException("DOCX 缺少 word/document.xml，文件可能损坏。");
        DocxBook book = new DocxBook(entries);
        book.collect(SafeXml.parse(body));
        book.flush();
        if (book.sections.isEmpty()) throw new IOException("这份 DOCX 没有可显示的正文（可能内容全在文本框或图片里）。");
        return new ReaderDocument(ReaderDocument.Format.DOCX, fallbackTitle, book.sections,
                "DOCX：显示文字与表格内容，不还原版式（字体、字号、分栏、页眉页脚、图片和批注都省略）。");
    }

    private void collect(Document xml) throws IOException {
        List<Element> bodies = SafeXml.descendants(xml, "body");
        if (bodies.isEmpty()) return;
        for (Node node = bodies.get(0).getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) continue;
            String name = SafeXml.local(element);
            if (name.equals("p")) paragraph(element);
            else if (name.equals("tbl")) table(element);
        }
    }

    private void paragraph(Element paragraph) throws IOException {
        String style = paragraphStyle(paragraph);
        int level = headingLevel(style);
        String text = textOf(paragraph);
        boolean listItem = paragraph.getElementsByTagNameNS(W, "numPr").getLength() > 0;
        if (level > 0) {
            if (!text.isBlank()) {
                if (started) flush();
                currentTitle = text;
                append("<h" + Math.min(level + 1, 6) + ">" + HtmlPolicy.escape(text) + "</h" + Math.min(level + 1, 6) + ">");
                started = true;
            }
            return;
        }
        if (text.isBlank()) {
            if (html.length() > 0 && !html.toString().endsWith("</p>")) append("<p></p>");
            return;
        }
        append(listItem ? "<p>· " + HtmlPolicy.escape(text) + "</p>" : "<p>" + HtmlPolicy.escape(text) + "</p>");
    }

    private void table(Element table) throws IOException {
        StringBuilder out = new StringBuilder("<table>");
        for (Element row : SafeXml.descendants(table, "tr")) {
            out.append("<tr>");
            for (Element cell : SafeXml.descendants(row, "tc")) {
                out.append("<td>").append(HtmlPolicy.escape(textOf(cell))).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
        append(out.toString());
    }

    private static String paragraphStyle(Element paragraph) throws IOException {
        List<Element> styles = SafeXml.descendants(paragraph, "pStyle");
        return styles.isEmpty() ? "" : SafeXml.attribute(styles.get(0), "val");
    }

    /** Word's built-in heading style names; the outline level is not stored in the style id. */
    private static int headingLevel(String style) {
        java.util.regex.Matcher number = java.util.regex.Pattern
                .compile("(?i)(?:heading|标题)\\s*([1-9])").matcher(style == null ? "" : style);
        if (number.find()) return Integer.parseInt(number.group(1));
        return style != null && style.equalsIgnoreCase("Title") ? 1 : 0;
    }

    /** Runs, hyperlink text and field results; nothing decorative. */
    private static String textOf(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getElementsByTagNameNS(W, "t");
        for (int i = 0; i < nodes.getLength(); i++) text.append(nodes.item(i).getTextContent());
        boolean hasTabs = element.getElementsByTagNameNS(W, "tab").getLength() > 0;
        if (text.length() == 0 && hasTabs) return "\t";
        return text.toString().replace('\u00A0', ' ').trim();
    }

    private void append(String fragment) throws IOException {
        html.append(fragment);
        if (html.length() > ReaderIo.MAX_TEXT_BYTES) throw new IOException("这份 DOCX 的正文超过阅读上限，请先拆分。");
        started = true;
    }

    private void flush() {
        if (html.length() == 0) return;
        String safe = HtmlPolicy.sanitize(html.toString());
        sections.add(new ReaderDocument.Section(currentTitle, safe, HtmlPolicy.plain(safe)));
        html.setLength(0);
    }
}
