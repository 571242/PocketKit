package cn.xianhua.pocketkit.reader;

import java.util.List;

/** Immutable, already-sanitized reading model. */
public record ReaderDocument(Format format, String title, List<Section> sections, String limitation) {
    public ReaderDocument {
        title = title == null || title.isBlank() ? "未命名文档" : title;
        sections = List.copyOf(sections);
        limitation = limitation == null ? "" : limitation;
    }

    public record Section(String title, String html, String plainText) {
        public Section {
            title = title == null || title.isBlank() ? "正文" : title;
            html = html == null ? "" : html;
            plainText = plainText == null ? "" : plainText;
        }
    }

    public enum Format { MARKDOWN, TEXT, HTML, EPUB, PDF, DOCX, XLSX, PPTX }
}
