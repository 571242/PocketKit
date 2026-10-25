package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.Map;

/**
 * One entry point from bytes plus a file name to a reading model.
 *
 * <p>The order is deliberate: the same archive sniffing decides EPUB and the three OOXML formats, so
 * an extension that lies cannot pick a parser. Every failure carries a sentence the reader can act
 * on, and nothing is decoded "as best as possible".
 */
public final class ReaderLoader {
    private ReaderLoader() { }

    public static ReaderDocument load(String fileName, byte[] bytes) throws IOException {
        if (bytes.length > DocumentFormat.MAX_FILE_BYTES) {
            throw new IOException("文件超过 " + (DocumentFormat.MAX_FILE_BYTES / 1024 / 1024) + " MiB，本阅读器不打开。");
        }
        String title = titleOf(fileName);
        switch (DocumentFormat.sniff(fileName, bytes)) {
            case PDF:
                return PdfBook.parse(title, bytes, "");
            case ZIP: {
                DocumentFormat.Archive archive = DocumentFormat.openZip(fileName, bytes);
                return fromArchive(title, archive);
            }
            case MARKDOWN:
                return MarkdownBook.parse(title, ReaderText.decode(bytes).text());
            case HTML:
                return TextBook.parseHtml(title, bytes);
            default:
                return TextBook.parseText(title, bytes);
        }
    }

    private static ReaderDocument fromArchive(String title, DocumentFormat.Archive archive) throws IOException {
        Map<String, byte[]> entries = archive.entries();
        switch (archive.format()) {
            case EPUB: return EpubBook.parse(title, entries);
            case DOCX: return DocxBook.parse(title, entries);
            case XLSX: return XlsxBook.parse(title, entries);
            case PPTX: return PptxBook.parse(title, entries);
            default: throw new IOException("这个压缩包不是可读文档。");
        }
    }

    /** The file name without its path or extension; the document's own title wins later when it has one. */
    public static String titleOf(String displayName) {
        if (displayName == null) return "未命名文档";
        String name = displayName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isBlank() ? "未命名文档" : name.trim();
    }
}
