package cn.xianhua.pocketkit.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded reads for untrusted documents. A limit is an error, never a silent truncation. */
public final class ReaderIo {
    public static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ARCHIVE_ENTRY_BYTES = 12 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ARCHIVE_ENTRIES = 2048;

    private ReaderIo() { }

    public static byte[] readAll(InputStream input, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("negative limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 32 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int n; (n = input.read(buffer)) != -1; ) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("读取已取消");
            if (n == 0) continue;
            if (total > limit - n) throw new IOException("文件超过读取上限 " + limit + " 字节");
            out.write(buffer, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    public static String utf8(byte[] bytes) throws IOException {
        try {
            int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("文本不是有效 UTF-8", ex);
        }
    }

    public static Map<String, byte[]> unzip(InputStream input) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("读取已取消");
                if (++count > MAX_ARCHIVE_ENTRIES) throw new IOException("压缩包条目过多");
                String raw = entry.getName();
                if (entry.isDirectory() && raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
                if (entry.isDirectory() && zip.read() != -1) throw new IOException("目录条目包含文件数据");
                String name = normalizedEntry(raw);
                if (name == null) throw new IOException("压缩包包含不安全路径");
                if (!entry.isDirectory()) {
                    byte[] data = readAll(zip, MAX_ARCHIVE_ENTRY_BYTES);
                    total += data.length;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("压缩包解压内容超过 64 MiB");
                    if (entries.putIfAbsent(name, data) != null) throw new IOException("压缩包包含重复条目：" + name);
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("压缩包文件名编码无效", ex);
        }
        return entries;
    }

    public static String normalizedEntry(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0 || raw.startsWith("/")) return null;
        String[] parts = raw.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0) return null;
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    public static String resolveZipPath(String baseFile, String relative) throws IOException {
        if (relative == null) throw new IOException("文档引用缺少路径");
        int hash = relative.indexOf('#');
        if (hash >= 0) relative = relative.substring(0, hash);
        if (relative.contains(":")) throw new IOException("文档包含外部资源引用");
        String base = baseFile.contains("/") ? baseFile.substring(0, baseFile.lastIndexOf('/') + 1) : "";
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : (base + relative).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IOException("文档引用越出压缩包根目录");
                parts.removeLast();
            } else parts.addLast(part);
        }
        if (parts.isEmpty()) throw new IOException("文档引用路径为空");
        return String.join("/", parts);
    }

    public static String extension(String name) {
        if (name == null) return "";
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
