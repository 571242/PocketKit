package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * PPTX slide text in presentation order, one section per slide.
 *
 * <p>The slide order comes from the presentation part, not from the part names, so a deck whose
 * slides were reordered still reads in the order the author sees. Tables are shown as a grid; layout,
 * theme, colours, animations, notes and images are not restored.
 */
public final class PptxBook {
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern SLIDE_PART = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");

    private PptxBook() { }

    public static ReaderDocument parse(String fallbackTitle, Map<String, byte[]> entries) throws IOException {
        byte[] presentation = entries.get("ppt/presentation.xml");
        if (presentation == null) throw new IOException("PPTX 缺少 ppt/presentation.xml，文件可能损坏。");
        List<String> ordered = slideOrder(SafeXml.parse(presentation), entries);
        List<ReaderDocument.Section> sections = new ArrayList<>();
        int budget = ReaderIo.MAX_TEXT_BYTES;
        for (int i = 0; i < ordered.size(); i++) {
            byte[] xml = entries.get(ordered.get(i));
            if (xml == null) continue;
            String body = slideBody(SafeXml.parse(xml));
            String html = body.isBlank() ? "<p>这一页没有文字（可能只有图片或图形）。</p>" : body;
            budget -= html.length();
            if (budget < 0) throw new IOException("这份 PPTX 的正文超过阅读上限，请先拆分。");
            sections.add(new ReaderDocument.Section("第 " + (i + 1) + " 页", html, HtmlPolicy.plain(html)));
        }
        if (sections.isEmpty()) throw new IOException("这份 PPTX 没有可显示的幻灯片文字。");
        return new ReaderDocument(ReaderDocument.Format.PPTX, fallbackTitle, sections,
                "PPTX：" + sections.size() + " 页，按放映顺序显示文字与表格；不还原版式、主题、动画、备注和图片。");
    }

    /** Slide relationship order from presentation.xml; part-number order is the fallback. */
    private static List<String> slideOrder(Document presentation, Map<String, byte[]> entries) throws IOException {
        List<String> result = new ArrayList<>();
        List<Element> ids = SafeXml.descendants(presentation, "sldId");
        byte[] rels = entries.get("ppt/_rels/presentation.xml.rels");
        if (rels != null && !ids.isEmpty()) {
            Document relationships = SafeXml.parse(rels);
            List<Element> targets = SafeXml.descendants(relationships, "Relationship");
            for (Element id : ids) {
                // sldId carries both a numeric id and a namespaced r:id; only r:id points at a part.
                String reference = SafeXml.attributeNS(id, R, "id");
                for (Element target : targets) {
                    if (!reference.equals(SafeXml.attribute(target, "Id"))) continue;
                    String path = resolve(SafeXml.attribute(target, "Target"));
                    if (path != null && entries.containsKey(path)) result.add(path);
                }
            }
        }
        if (!result.isEmpty()) return result;
        entries.keySet().stream().filter(name -> SLIDE_PART.matcher(name).matches())
                .sorted(Comparator.comparingInt(PptxBook::slideNumber)).forEach(result::add);
        return result;
    }

    private static int slideNumber(String name) {
        Matcher matcher = SLIDE_PART.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static String resolve(String target) {
        try {
            return ReaderIo.resolveZipPath("ppt/presentation.xml", target);
        } catch (IOException outside) {
            return null;
        }
    }

    private static String slideBody(Document slide) throws IOException {
        StringBuilder html = new StringBuilder();
        for (Element shape : SafeXml.descendants(slide, "sp")) {
            String text = shapeText(shape);
            if (text.isBlank()) continue;
            String[] lines = text.split("\n");
            if (lines.length == 1) html.append("<h3>").append(HtmlPolicy.escape(lines[0])).append("</h3>");
            else {
                html.append("<h3>").append(HtmlPolicy.escape(lines[0])).append("</h3><ul>");
                for (int i = 1; i < lines.length; i++) {
                    if (!lines[i].isBlank()) html.append("<li>").append(HtmlPolicy.escape(lines[i])).append("</li>");
                }
                html.append("</ul>");
            }
        }
        for (Element table : SafeXml.descendants(slide, "tbl")) {
            html.append("<table>");
            for (Element row : SafeXml.descendants(table, "tr")) {
                html.append("<tr>");
                for (Element cell : SafeXml.descendants(row, "tc")) {
                    html.append("<td>").append(HtmlPolicy.escape(cellText(cell))).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
        }
        return HtmlPolicy.sanitize(html.toString());
    }

    /** Paragraphs inside one shape, each on its own line; runs of a paragraph are joined. */
    private static String shapeText(Element shape) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Element paragraph : SafeXml.descendants(shape, "p")) {
            StringBuilder line = new StringBuilder();
            NodeList runs = paragraph.getElementsByTagNameNS(A, "t");
            for (int i = 0; i < runs.getLength(); i++) line.append(runs.item(i).getTextContent());
            if (text.length() > 0) text.append('\n');
            text.append(line.toString().trim());
        }
        return text.toString().trim();
    }

    private static String cellText(Element cell) {
        StringBuilder text = new StringBuilder();
        NodeList runs = cell.getElementsByTagNameNS(A, "t");
        for (int i = 0; i < runs.getLength(); i++) text.append(runs.item(i).getTextContent());
        return text.toString().trim();
    }
}
