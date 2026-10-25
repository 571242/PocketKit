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
 * XLSX cell values, one section per visible sheet.
 *
 * <p>Cell text only: formulas show their stored result, and number formats, colours, merged cells,
 * charts and images are not restored. Shared strings and inline strings are both read, because a
 * workbook written by a generator often uses only the latter.
 */
public final class XlsxBook {
    private static final int MAX_CELLS_PER_SHEET = 200_000;
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final Map<String, byte[]> entries;

    private XlsxBook(Map<String, byte[]> entries) { this.entries = entries; }

    public static ReaderDocument parse(String fallbackTitle, Map<String, byte[]> entries) throws IOException {
        XlsxBook book = new XlsxBook(entries);
        Document workbook = SafeXml.parse(book.required("xl/workbook.xml"));
        List<String> shared = book.sharedStrings();
        List<Element> sheets = SafeXml.descendants(workbook, "sheet");
        if (sheets.isEmpty()) throw new IOException("这份 XLSX 没有工作表。");
        List<ReaderDocument.Section> sections = new ArrayList<>();
        int budget = ReaderIo.MAX_TEXT_BYTES;
        for (Element sheet : sheets) {
            String name = SafeXml.attribute(sheet, "name");
            String path = book.sheetPath(SafeXml.attributeNS(sheet, R, "id"));
            byte[] xml = path.isEmpty() ? null : entries.get(path);
            if (xml == null) continue; // A chart sheet or a deleted part holds no cells to read.
            String html = book.sheetHtml(SafeXml.parse(xml), shared, name);
            budget -= html.length();
            if (budget < 0) throw new IOException("这份表格的正文超过阅读上限，请先拆分。");
            String label = name == null || name.isBlank() ? "工作表" : name;
            sections.add(new ReaderDocument.Section(label, html, HtmlPolicy.plain(html)));
        }
        if (sections.isEmpty()) throw new IOException("这份 XLSX 没有可显示的工作表内容。");
        return new ReaderDocument(ReaderDocument.Format.XLSX, fallbackTitle, sections,
                "XLSX：" + sections.size() + " 个工作表，显示单元格文字与公式结果；不还原列宽、颜色、合并单元格和图表。");
    }

    private String sheetHtml(Document sheet, List<String> shared, String name) throws IOException {
        List<Element> rows = SafeXml.descendants(sheet, "row");
        if (rows.isEmpty()) return "<p>这个工作表是空的。</p>";
        StringBuilder html = new StringBuilder("<table>");
        int cells = 0;
        for (Element row : rows) {
            html.append("<tr>");
            for (Element cell : SafeXml.descendants(row, "c")) {
                if (++cells > MAX_CELLS_PER_SHEET) {
                    throw new IOException("工作表「" + name + "」的单元格超过上限 " + MAX_CELLS_PER_SHEET + "，请先拆分。");
                }
                html.append("<td>").append(HtmlPolicy.escape(cellText(cell, shared))).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</table>").toString();
    }

    /** Shared, inline and boolean cells; a formula contributes its cached value, never the formula. */
    private static String cellText(Element cell, List<String> shared) throws IOException {
        String type = SafeXml.attribute(cell, "t");
        if ("inlineStr".equals(type)) {
            List<Element> inline = SafeXml.descendants(cell, "is");
            if (inline.isEmpty()) return "";
            StringBuilder text = new StringBuilder();
            for (Element run : SafeXml.descendants(inline.get(0), "t")) text.append(run.getTextContent());
            return text.toString();
        }
        List<Element> values = SafeXml.descendants(cell, "v");
        if (values.isEmpty()) return "";
        String raw = values.get(0).getTextContent().trim();
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw);
                return index >= 0 && index < shared.size() ? shared.get(index) : "";
            } catch (NumberFormatException brokenReference) {
                return "";
            }
        }
        if ("b".equals(type)) return "1".equals(raw) ? "真" : "0".equals(raw) ? "假" : raw;
        return raw;
    }

    private List<String> sharedStrings() throws IOException {
        List<String> strings = new ArrayList<>();
        byte[] xml = entries.get("xl/sharedStrings.xml");
        if (xml == null) return strings;
        for (Element item : SafeXml.descendants(SafeXml.parse(xml), "si")) {
            NodeList runs = item.getElementsByTagNameNS(S, "t");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < runs.getLength(); i++) text.append(runs.item(i).getTextContent());
            strings.add(text.toString());
        }
        return strings;
    }

    /** Resolves a sheet's r:id through the workbook relationships part. */
    private String sheetPath(String relationshipId) throws IOException {
        byte[] rels = entries.get("xl/_rels/workbook.xml.rels");
        if (rels == null) return "";
        for (Element relationship : SafeXml.descendants(SafeXml.parse(rels), "Relationship")) {
            if (!relationshipId.equals(SafeXml.attribute(relationship, "Id"))) continue;
            String target = SafeXml.attribute(relationship, "Target");
            try {
                return ReaderIo.resolveZipPath("xl/workbook.xml", target);
            } catch (IOException outside) {
                return "";
            }
        }
        return "";
    }

    private byte[] required(String path) throws IOException {
        byte[] bytes = entries.get(path);
        if (bytes == null) throw new IOException("XLSX 缺少 " + path + "，文件可能损坏。");
        return bytes;
    }
}
