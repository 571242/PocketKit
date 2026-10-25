package cn.xianhua.pocketkit.reader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Sniffs what a file really is before any parser runs.
 *
 * <p>The extension alone is not trusted: a ZIP with no known document parts is refused instead of
 * being rendered as text, and every format this reader cannot display gets its own sentence rather
 * than a page of mojibake.
 */
public final class DocumentFormat {
    /** Containers hold more bytes than plain text because they carry XML and media parts. */
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    public enum Kind { PDF, ZIP, MARKDOWN, TEXT, HTML }

    public record Archive(ReaderDocument.Format format, Map<String, byte[]> entries) { }

    private static final Set<String> CONTAINER_EXT = Set.of("pdf", "epub", "docx", "xlsx", "pptx",
            "doc", "xls", "ppt", "zip", "apk", "mobi", "azw", "azw3", "rar", "7z");

    private DocumentFormat() { }

    public static Kind sniff(String name, byte[] head) throws IOException {
        if (head.length == 0) throw new IOException("文件是空的");
        if (startsWith(head, "%PDF-")) return Kind.PDF;
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) return Kind.ZIP;
        if (isOle2(head)) {
            throw new IOException("这是旧版 Office 二进制文件（.doc / .xls / .ppt）。本阅读器只读新版 OOXML"
                    + "（.docx / .xlsx / .pptx），请先用 Word 或 WPS 另存为新格式再打开。");
        }
        if (isMobi(head)) {
            throw new IOException("这是 Kindle 电子书（.mobi / .azw）。本阅读器支持 EPUB，请先转换成 EPUB。");
        }
        if (hasNul(head) && ReaderText.bomLength(head) < 2) {
            String ext = ReaderIo.extension(name);
            throw new IOException(CONTAINER_EXT.contains(ext)
                    ? "文件扩展名是 ." + ext + "，但内容不是这种格式（可能已损坏或只是个占位文件）。"
                    : "这不是 UTF-8 文本文件，或它用了 UTF-16 等双字节编码。为避免显示乱码，未打开。");
        }
        String ext = ReaderIo.extension(name);
        if (ext.equals("md") || ext.equals("markdown") || ext.equals("mdown") || ext.equals("mkd")) return Kind.MARKDOWN;
        if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml")) return Kind.HTML;
        if (CONTAINER_EXT.contains(ext)) {
            throw new IOException("文件扩展名是 ." + ext + "，但内容不是这种格式（可能已损坏或只是个占位文件）。");
        }
        return Kind.TEXT;
    }

    /** Resolves which document an archive holds; an unknown ZIP is refused, never guessed. */
    public static Archive openZip(String name, byte[] bytes) throws IOException {
        Map<String, byte[]> entries = ReaderIo.unzip(new ByteArrayInputStream(bytes));
        if (entries.containsKey("META-INF/container.xml")) return new Archive(ReaderDocument.Format.EPUB, entries);
        if (entries.containsKey("word/document.xml")) return new Archive(ReaderDocument.Format.DOCX, entries);
        if (entries.containsKey("xl/workbook.xml")) return new Archive(ReaderDocument.Format.XLSX, entries);
        if (entries.containsKey("ppt/presentation.xml")) return new Archive(ReaderDocument.Format.PPTX, entries);
        throw new IOException("这个压缩包不是可读文档（支持 EPUB、DOCX、XLSX、PPTX），原文件没有被改动。");
    }

    /**
     * MIME types offered to the system picker. Providers mislabel text often, so the picker also
     * accepts {@code *}{@code /*} and the format is decided from content afterwards.
     */
    public static String[] pickerMimeTypes() {
        return new String[] {
                "text/markdown", "text/x-markdown", "text/plain", "text/html",
                "application/xhtml+xml", "application/epub+zip", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/octet-stream", "*/*"
        };
    }

    private static boolean startsWith(byte[] bytes, String text) {
        if (bytes.length < text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (bytes[i] != (byte) text.charAt(i)) return false;
        return true;
    }

    private static boolean isOle2(byte[] head) {
        return head.length >= 8 && head[0] == (byte) 0xD0 && head[1] == (byte) 0xCF
                && head[2] == 0x11 && head[3] == (byte) 0xE0;
    }

    private static boolean isMobi(byte[] head) {
        if (head.length < 68) return false;
        String marker = new String(head, 60, 8, java.nio.charset.StandardCharsets.ISO_8859_1);
        return marker.startsWith("BOOKMOBI");
    }

    private static boolean hasNul(byte[] head) {
        for (byte b : head) if (b == 0) return true;
        return false;
    }
}
