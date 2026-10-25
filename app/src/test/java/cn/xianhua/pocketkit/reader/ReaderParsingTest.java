package cn.xianhua.pocketkit.reader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-host checks for the reader: every format that needs no Android framework is opened from
 * actual bytes here, including the shipped viewer template.
 *
 * <p>PDF text extraction is deliberately absent: pdfbox's text engine calls {@code android.graphics}
 * and cannot run on a plain JVM, so it is covered by the compile check and the device run only.
 */
public final class ReaderParsingTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        markdown();
        plainText();
        offlineHtml();
        epub();
        docx();
        xlsx();
        pptx();
        refusals();
        search();
        viewerTemplate(root);
        System.out.println("ReaderParsingTest PASS: " + checks + " assertions over markdown/text/html/epub/docx/xlsx/pptx/refusals/search/viewer");
    }

    // ------------------------------------------------------------------ markdown

    private static void markdown() throws IOException {
        ReaderDocument doc = ReaderLoader.load("指南.md", Fixtures.markdown());
        check(doc.format() == ReaderDocument.Format.MARKDOWN, "format is markdown");
        check(doc.sections().size() == 2, "splits at first-level headings, got " + doc.sections().size());
        check(doc.sections().get(0).title().equals("第一章 开始"), "chapter one title");
        check(doc.sections().get(1).title().equals("第二章 继续"), "chapter two title");
        check(doc.title().equals("第一章 开始"), "document title from the first h1");

        String one = doc.sections().get(0).html();
        check(one.contains("<h2>小节</h2>"), "second-level heading kept");
        check(one.contains("<ul>") && one.contains("<li>第一项</li>"), "bullet list kept");
        check(one.contains("<strong>加粗</strong>"), "bold kept");
        check(one.contains("<blockquote>"), "quote kept");
        // Table alignment is presentation, and the sanitizer keeps no attributes: the grid is what matters.
        check(one.contains("<table>") && one.contains("<th>名称</th>"), "gfm table rendered, alignment dropped");
        check(one.contains("<td>12</td>"), "table body cell");
        // The sanitizer keeps no attributes, so a language hint is not needed (there is no highlighting).
        check(one.contains("<pre><code>"), "fenced code kept as a block");
        check(one.contains("int x = 1 &lt; 2 &amp;&amp; 3 &gt; 2;"), "code sample is escaped, not swallowed");
        check(!one.contains("&amp;amp;"), "entities are not double escaped");
        check(one.contains("a &lt; b 与 4 &gt; 3"), "literal angle brackets stay text");

        check(one.contains("href=\"https://example.com/a?b=1&amp;c=2\""), "web link kept with escaped query");
        check(!one.contains("javascript:"), "javascript: link dropped");
        check(!one.contains("file://"), "file: link dropped");
        check(!one.contains("<div"), "a raw html block never becomes an element");
        check(one.contains("原始 HTML"), "raw html block is still readable as text");
        check(!one.contains("<img"), "images are not embedded");
        check(doc.limitation().isEmpty(), "markdown needs no limitation note");
    }

    // ------------------------------------------------------------------ text

    private static void plainText() throws IOException {
        ReaderDocument doc = ReaderLoader.load("笔记.txt", Fixtures.plainText());
        check(doc.format() == ReaderDocument.Format.TEXT, "format is text");
        String html = doc.sections().get(0).html();
        check(html.startsWith("<pre>") && html.endsWith("</pre>"), "text keeps its own line breaks");
        check(html.contains("第一行    制表"), "tab expanded to four spaces");
        check(html.contains("第二行 &amp; &lt;tag&gt;"), "markup characters escaped");

        ReaderDocument legacy = ReaderLoader.load("旧文件.txt", Fixtures.gb18030Text());
        check(legacy.sections().get(0).plainText().contains("中文 GB18030 文件"), "GB18030 file decoded, not mangled");
        check(legacy.limitation().contains("GB18030"), "the reader is told which encoding was guessed");

        check(ReaderLoader.titleOf("/storage/emulated/0/Download/读书笔记.md").equals("读书笔记"), "title from path");
        check(ReaderLoader.titleOf(null).equals("未命名文档"), "missing name has a default title");
    }

    // ------------------------------------------------------------------ html

    private static void offlineHtml() throws IOException {
        ReaderDocument doc = ReaderLoader.load("页面.html", Fixtures.offlineHtml());
        check(doc.format() == ReaderDocument.Format.HTML, "format is html");
        check(doc.title().equals("离线文档标题"), "title from the page heading, got " + doc.title());
        String html = doc.sections().get(0).html();
        check(!html.contains("<script") && !html.contains("alert"), "scripts removed with their content");
        check(!html.contains("<style"), "styles removed");
        check(!html.contains("onload"), "inline handlers removed");
        check(!html.contains("<img"), "images removed");
        check(html.contains("<h1>离线文档标题</h1>") && html.contains("<b>加粗</b>"), "reading markup kept");
        check(html.contains("href=\"https://example.com/x\""), "external link kept");
        check(!html.contains("javascript:"), "bad link dropped");
        check(html.contains("<table><tr><th>甲</th>"), "table kept");
    }

    // ------------------------------------------------------------------ epub

    private static void epub() throws IOException {
        ReaderDocument doc = ReaderLoader.load("书.epub", Fixtures.epub());
        check(doc.format() == ReaderDocument.Format.EPUB, "format is epub");
        check(doc.title().equals("测试电子书"), "title from opf metadata");
        check(doc.sections().size() == 3, "one section per spine item, got " + doc.sections().size());
        check(doc.sections().get(0).title().equals("第三章"), "spine order, not part-name order");
        check(doc.sections().get(1).title().equals("第一章"), "second spine item second");
        check(doc.limitation().contains("spine"), "limitation names the reading basis");

        String third = doc.sections().get(0).html();
        check(third.contains("href=\"" + ReaderChapter.ANCHOR_PREFIX + "1\""), "cross-chapter link becomes an in-reader anchor");
        check(third.contains("href=\"" + ReaderChapter.ANCHOR_PREFIX + "2\""), "nested relative path resolves to its chapter");
        check(third.contains("href=\"https://example.com/book\""), "web link kept for the browser");
        check(!third.contains("etc/passwd"), "reference outside the archive dropped");
        check(!doc.sections().get(1).html().contains("alert"), "script in a chapter removed");
        check(doc.sections().get(1).html().contains("第一章正文 &amp; 一点内容"), "chapter text with entity kept once");
        check(ReaderChapter.anchorIndex(ReaderChapter.ANCHOR_PREFIX + "2") == 2, "anchor parses back to an index");
        check(ReaderChapter.anchorIndex("https://example.com") == -1, "a web link is not an anchor");
        check(ReaderChapter.anchorIndex(ReaderChapter.ANCHOR_PREFIX + "x") == -1, "a malformed anchor is rejected");
    }

    // ------------------------------------------------------------------ docx / xlsx / pptx

    private static void docx() throws IOException {
        ReaderDocument doc = ReaderLoader.load("报告.docx", Fixtures.docx());
        check(doc.format() == ReaderDocument.Format.DOCX, "format is docx");
        check(doc.sections().size() == 2, "splits at Heading1, got " + doc.sections().size());
        check(doc.sections().get(0).title().equals("报告标题"), "first chapter title");
        String first = doc.sections().get(0).html();
        check(first.contains("第一段 &amp; 内容"), "paragraph text with entity");
        check(first.contains("· 列表项一"), "numbered paragraph shown as a list line");
        check(first.contains("<table><tr><td>列甲</td><td>列乙</td></tr>"), "table grid kept");
        check(doc.sections().get(1).html().contains("第二节正文"), "later chapter body");
        check(doc.limitation().contains("不还原版式"), "layout is explicitly not restored");
    }

    private static void xlsx() throws IOException {
        ReaderDocument doc = ReaderLoader.load("表格.xlsx", Fixtures.xlsx());
        check(doc.format() == ReaderDocument.Format.XLSX, "format is xlsx");
        check(doc.sections().size() == 2, "one section per sheet, got " + doc.sections().size());
        check(doc.sections().get(0).title().equals("数据"), "sheet name kept");
        String first = doc.sections().get(0).html();
        check(first.contains("<td>名称</td>"), "shared string resolved");
        check(first.contains("<td>苹果 &amp; 梨</td>"), "shared string entity escaped once");
        check(first.contains("<td>内联</td>"), "inline string read");
        check(first.contains("<td>42</td>"), "number cell");
        check(first.contains("<td>真</td>"), "boolean cell spelled out");
        check(doc.sections().get(1).html().contains("第二表内容"), "second sheet content");
        check(doc.limitation().contains("不还原列宽"), "sheet formatting limitation stated");
    }

    private static void pptx() throws IOException {
        ReaderDocument doc = ReaderLoader.load("演示.pptx", Fixtures.pptx());
        check(doc.format() == ReaderDocument.Format.PPTX, "format is pptx");
        check(doc.sections().size() == 2, "one section per slide");
        check(doc.sections().get(0).title().equals("第 1 页"), "slides numbered in order");
        check(doc.sections().get(0).html().contains("<h3>真正的第一页</h3>"), "order follows the presentation, not part numbers");
        check(doc.sections().get(0).html().contains("<td>格1</td><td>格2</td>"), "slide table grid");
        check(doc.sections().get(1).html().contains("<h3>幻灯片一标题</h3>"), "second slide in the deck");
        check(doc.sections().get(1).html().contains("<li>要点甲</li>"), "bullets after the title line");
        check(doc.limitation().contains("不还原版式"), "deck layout limitation stated");
    }

    // ------------------------------------------------------------------ refusals

    private static void refusals() throws IOException {
        check(throwsWith("旧版 Office", Fixtures.concat(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0}, Fixtures.utf8("x")),
                "word.doc"), "旧版 Office 二进制");
        check(throwsWith("Kindle", mobi(), "book.mobi"), "Kindle 电子书");
        check(throwsWith("UTF-16", Fixtures.concat(new byte[0], utf16()), "weird.txt"), "双字节编码");
        check(throwsWith("压缩包", Fixtures.zip(Map.of("readme.txt", "hi")), "unknown.zip"), "压缩包不是可读文档");
        check(throwsWith("DRM", Fixtures.emptySpineEpub(), "locked.epub"), "spine");

        Map<String, String> traversal = new LinkedHashMap<>();
        traversal.put("../escape.txt", "x");
        check(throwsWith("不安全路径", Fixtures.zip(traversal), "bad.epub"), "压缩包包含不安全路径");

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE c [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<container><rootfiles><rootfile full-path=\"&e;\"/></rootfiles></container>";
        Map<String, String> xxeEpub = new LinkedHashMap<>();
        xxeEpub.put("META-INF/container.xml", xxe);
        check(throwsWith("XML", Fixtures.zip(xxeEpub), "xxe.epub"), "XML");

        // PDF is absent here on purpose: pdfbox's text engine calls android.graphics.
        check(DocumentFormat.sniff("a.pdf", Fixtures.utf8("%PDF-1.7\n")) == DocumentFormat.Kind.PDF, "pdf signature wins over the extension");
        try {
            ReaderLoader.load("空.txt", new byte[0]);
            throw new AssertionError("an empty file must be refused");
        } catch (IOException expected) {
            check(expected.getMessage().contains("空"), "empty file has a plain message");
        }
        check(DocumentFormat.pickerMimeTypes().length >= 10, "picker offers the document types");
        check(List.of(DocumentFormat.pickerMimeTypes()).contains("application/epub+zip"), "picker includes epub");
        check(List.of(DocumentFormat.pickerMimeTypes()).contains("text/markdown"), "picker includes markdown");
    }

    private static byte[] utf16() { return "hello".getBytes(StandardCharsets.UTF_16LE); }

    private static byte[] mobi() {
        byte[] head = new byte[80];
        byte[] marker = "BOOKMOBI".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(marker, 0, head, 60, marker.length);
        return head;
    }

    private static boolean throwsWith(String expectedFragment, byte[] bytes, String name) {
        try {
            ReaderLoader.load(name, bytes);
            return false;
        } catch (IOException expected) {
            return expected.getMessage() != null && expected.getMessage().contains(expectedFragment);
        }
    }

    // ------------------------------------------------------------------ search

    private static void search() throws IOException {
        ReaderDocument doc = ReaderLoader.load("指南.md", Fixtures.markdown());
        List<ReaderSearch.Hit> hits = ReaderSearch.find(doc, "正文", 20);
        check(hits.size() == 2, "one hit per chapter for 正文, got " + hits.size());
        check(hits.get(0).section() == 0 && hits.get(1).section() == 1, "hits carry their chapter index");
        check(hits.get(0).snippet().contains("正文"), "snippet shows the match");
        check(ReaderSearch.find(doc, "   ", 10).isEmpty(), "blank query finds nothing");
        check(ReaderSearch.find(doc, "不存在的词", 10).isEmpty(), "absent word finds nothing");
        check(ReaderSearch.countInSection(doc, 0, "正文") == 1, "per-chapter counter");
        check(ReaderSearch.countInSection(doc, 9, "正文") == 0, "out-of-range chapter counts zero");
        check(ReaderSearch.find(doc, "正文", 1).size() == 1, "limit is honoured");
    }

    // ------------------------------------------------------------------ viewer template

    private static void viewerTemplate(Path root) throws Exception {
        String template = Files.readString(root.resolve("app/src/main/assets/reader/reader.html"), StandardCharsets.UTF_8);
        check(template.contains("Content-Security-Policy"), "template declares a CSP");
        check(template.contains("script-src 'none'"), "template forbids scripts");
        check(template.contains("connect-src 'none'"), "template forbids network access");
        check(!template.contains("<script"), "template has no script element");
        check(!template.contains("http://") && !template.contains("https://"), "template loads nothing from the network");

        ReaderDocument.Section section = new ReaderDocument.Section("第一章", "<p>正文</p>", "正文");
        String page = ReaderPage.render(template, section, true, 21);
        check(page.contains("<div class=\"doc\"><p>正文</p></div>"), "section body goes into the document");
        check(page.contains("font-size: 21px"), "font size applied");
        check(page.contains("color-scheme: dark") && page.contains("#101B2B"), "dark theme applied");
        check(!page.contains("{{"), "no placeholder left unfilled, found " + leftover(page));
        String light = ReaderPage.render(template, section, false, 5);
        check(light.contains("font-size: 13px"), "small font clamps to the minimum");
        check(light.contains("color-scheme: light"), "light theme applied");
        String huge = ReaderPage.render(template, section, false, 100);
        check(huge.contains("font-size: 30px"), "large font clamps to the maximum");
        String unsafe = ReaderPage.render(template, new ReaderDocument.Section("<标题>", "<p>x</p>", "x"), false, 17);
        check(unsafe.contains("<title>&lt;标题&gt;</title>"), "title is escaped for the document head");
    }

    private static String leftover(String page) {
        int at = page.indexOf("{{");
        return at < 0 ? "" : page.substring(at, Math.min(page.length(), at + 12));
    }

    private ReaderParsingTest() { }
}
