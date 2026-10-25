package cn.xianhua.pocketkit.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the real documents the reader tests open; no fixture files to drift from the code. */
final class Fixtures {
    private Fixtures() { }

    static byte[] markdown() {
        return utf8("""
                # 第一章 开始

                正文里有一个&amp;实体，还有 a < b 与 4 > 3。

                ## 小节

                - 第一项
                - 第二项 **加粗**

                > 引用一行

                ```java
                int x = 1 < 2 && 3 > 2;
                ```

                | 名称 | 数量 |
                |:--|--:|
                | 苹果 | 3 |
                | 梨 | 12 |

                [官网](https://example.com/a?b=1&c=2) 和 [危险](javascript:alert(1)) 和 [本地](file:///etc/passwd)

                ![图片](/data/local/tmp/x.png)

                <div onclick="alert(1)">原始 HTML</div>

                # 第二章 继续

                第二章的正文。
                """);
    }

    static byte[] plainText() {
        return utf8("第一行\t制表\n第二行 & <tag>\n");
    }

    static byte[] gb18030Text() {
        return "中文 GB18030 文件\n第二行\n".getBytes(Charset.forName("GB18030"));
    }

    static byte[] offlineHtml() {
        return utf8("""
                <!DOCTYPE html>
                <html><head><title>离线页 &amp; 标题</title>
                <style>body{color:red}</style>
                <script>alert('x')</script></head>
                <body onload="alert(1)">
                <h1>离线文档标题</h1>
                <p>段落 <b>加粗</b> 与 <a href="https://example.com/x">链接</a></p>
                <p><a href="javascript:alert(1)">坏链接</a><img src="file:///etc/passwd"></p>
                <table><tr><th>甲</th><th>乙</th></tr><tr><td>1</td><td>2</td></tr></table>
                </body></html>
                """);
    }

    static byte[] epub() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("mimetype", "application/epub+zip");
        files.put("META-INF/container.xml", """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
        files.put("OEBPS/content.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试电子书</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c3"/>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """);
        files.put("OEBPS/ch3.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第三章</h1><p>第三章正文。</p>
                <p><a href="ch1.xhtml">去第一章</a> <a href="text/ch2.xhtml#x">去第二章</a>
                <a href="https://example.com/book">外部</a> <a href="../../etc/passwd">越界</a></p></body></html>
                """);
        files.put("OEBPS/ch1.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1>
                <script>alert(1)</script><p>第一章正文 &amp; 一点内容。</p></body></html>
                """);
        files.put("OEBPS/text/ch2.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第二章</h1><p>第二章正文。</p></body></html>
                """);
        return zip(files);
    }

    static byte[] emptySpineEpub() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
        files.put("content.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>加密书</dc:title></metadata>
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="missing"/></spine>
                </package>
                """);
        files.put("ch1.xhtml", "<html><body><p>never referenced</p></body></html>");
        return zip(files);
    }

    static byte[] docx() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        files.put("word/document.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>报告标题</w:t></w:r></w:p>
                    <w:p><w:r><w:t>第一段 &amp; 内容。</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>列表项一</w:t></w:r></w:p>
                    <w:tbl>
                      <w:tr><w:tc><w:p><w:r><w:t>列甲</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>列乙</w:t></w:r></w:p></w:tc></w:tr>
                      <w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc></w:tr>
                    </w:tbl>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第二节</w:t></w:r></w:p>
                    <w:p><w:r><w:t>第二节正文。</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """);
        return zip(files);
    }

    static byte[] xlsx() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        files.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="数据" sheetId="1" r:id="rId1"/>
                    <sheet name="第二表" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>
                """);
        files.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/>
                  <Relationship Id="rId2" Target="worksheets/sheet2.xml" Type="worksheet"/>
                </Relationships>
                """);
        files.put("xl/sharedStrings.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>名称</t></si><si><t>苹果 &amp; 梨</t></si>
                </sst>
                """);
        files.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>内联</t></is></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>42</v></c><c r="C2" t="b"><v>1</v></c></row>
                </sheetData></worksheet>
                """);
        files.put("xl/worksheets/sheet2.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>第二表内容</t></is></c></row>
                </sheetData></worksheet>
                """);
        return zip(files);
    }

    static byte[] pptx() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ppt/presentation.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId1"/></p:sldIdLst>
                </p:presentation>
                """);
        files.put("ppt/_rels/presentation.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="slides/slide1.xml" Type="slide"/>
                  <Relationship Id="rId2" Target="slides/slide2.xml" Type="slide"/>
                </Relationships>
                """);
        files.put("ppt/slides/slide1.xml", """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree>
                  <p:sp><p:txBody>
                    <a:p><a:r><a:t>幻灯片一标题</a:t></a:r></a:p>
                    <a:p><a:r><a:t>要点甲</a:t></a:r></a:p>
                    <a:p><a:r><a:t>要点乙</a:t></a:r></a:p>
                  </p:txBody></p:sp>
                </p:spTree></p:cSld></p:sld>
                """);
        files.put("ppt/slides/slide2.xml", """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree>
                  <p:sp><p:txBody><a:p><a:r><a:t>真正的第一页</a:t></a:r></a:p></p:txBody></p:sp>
                  <p:graphicFrame><a:graphic><a:graphicData>
                    <a:tbl>
                      <a:tr><a:tc><a:txBody><a:p><a:r><a:t>格1</a:t></a:r></a:p></a:txBody></a:tc>
                            <a:tc><a:txBody><a:p><a:r><a:t>格2</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
                    </a:tbl>
                  </a:graphicData></a:graphic></p:graphicFrame>
                </p:spTree></p:cSld></p:sld>
                """);
        return zip(files);
    }

    static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(utf8(entry.getValue()));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static List<String> paths(String... names) { return List.of(names); }
}
