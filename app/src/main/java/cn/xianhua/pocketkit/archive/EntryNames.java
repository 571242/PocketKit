package cn.xianhua.pocketkit.archive;

import java.util.Locale;
import java.util.Set;

/**
 * 压缩包里写出来的条目名一律不可信：可能是绝对路径、带 ../ 的穿越、Windows 盘符、
 * 控制字符，或者几 KB 的垃圾。这里只把它变成「若干段安全的相对路径」，
 * 拼盘时的落点由 SAF 决定；任何解析不出来的名字返回 null，由调用方跳过并记录。
 *
 * 纯逻辑，可脱机测试。
 */
public final class EntryNames {

    /** 单段上限。ext4/fuse 与大多数 SAF 提供方都按 255 字节算，这里按字符保守处理。 */
    static final int MAX_SEGMENT = 120;
    /** 相对路径总长度上限，挡住「上千层目录」把 SAF 调用拖死。 */
    static final int MAX_PATH = 900;

    private static final String[] RESERVED = {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private EntryNames() {
    }

    /**
     * 规范成以 "/" 分隔的相对路径；末尾的 "/" 表示目录。
     *
     * @return 安全相对路径，解析不出安全结果时返回 null
     */
    public static String sanitize(String raw) {
        return sanitize(raw, false);
    }

    /** isDirectory 为真时返回以 "/" 结尾，调用方据此建目录。 */
    public static String sanitize(String raw, boolean isDirectory) {
        if (raw == null) {
            return null;
        }
        String name = raw.replace('\\', '/');
        boolean directory = isDirectory || name.endsWith("/");
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue; // 控制字符直接丢，别透传到 SAF
            }
            cleaned.append(c);
        }
        name = cleaned.toString();
        if (name.startsWith("/")) {
            return null; // 绝对路径：不能决定它落到哪
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            return null; // Windows 盘符或备用数据流
        }
        String[] parts = name.split("/");
        StringBuilder out = new StringBuilder(name.length());
        int kept = 0;
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null; // 路径穿越：整条丢弃，不做「就地修正」
            }
            String safe = cleanSegment(part);
            if (safe.isEmpty()) {
                continue;
            }
            if (kept > 0) {
                out.append('/');
            }
            out.append(safe);
            kept++;
            if (out.length() > MAX_PATH) {
                return null;
            }
        }
        if (kept == 0) {
            return null;
        }
        return directory ? out.append('/').toString() : out.toString();
    }

    /** 单段清洗：去前导点、去尾随点与空格、避开 Windows 保留名、超长截断。 */
    static String cleanSegment(String raw) {
        String part = raw.trim();
        while (part.startsWith(".")) {
            part = part.substring(1);
        }
        while (part.endsWith(".") || part.endsWith(" ")) {
            part = part.substring(0, part.length() - 1);
        }
        if (part.isEmpty()) {
            return "";
        }
        if (part.length() > MAX_SEGMENT) {
            part = truncate(part, MAX_SEGMENT);
        }
        if (isReserved(part)) {
            part = "_" + part;
        }
        return part;
    }

    private static boolean isReserved(String name) {
        int dot = name.indexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name).toUpperCase(Locale.ROOT);
        for (String reserved : RESERVED) {
            if (reserved.equals(stem)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String name, int max) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
        int keep = Math.max(1, max - extension.length());
        return name.substring(0, keep) + extension;
    }

    /** 同名条目（有些包故意放重名）追加 (1)(2)，绝不覆盖已经写出去的本次文件。 */
    public static String unique(String path, Set<String> taken) {
        if (!taken.contains(path)) {
            return path;
        }
        boolean directory = path.endsWith("/");
        String bare = directory ? path.substring(0, path.length() - 1) : path;
        int slash = bare.lastIndexOf('/');
        String dir = slash < 0 ? "" : bare.substring(0, slash + 1);
        String name = slash < 0 ? bare : bare.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = dir + base + " (" + i + ")" + extension + (directory ? "/" : "");
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return dir + base + " (" + System.currentTimeMillis() + ")" + extension + (directory ? "/" : "");
    }

    /**
     * 本次新建的落点目录名：压缩包基础名，撞名就加 (1)(2)。
     *
     * 解压永远新建一个独立子目录，而不是往用户选的目录里平铺 —— 这样即使包里有和目录里
     * 同名的文件，也不会覆盖任何原有内容。
     */
    public static String rootName(String archiveDisplayName, Set<String> existingNames) {
        String base = archiveDisplayName == null ? "" : archiveDisplayName.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String cleaned = cleanSegment(base);
        if (cleaned.isEmpty()) {
            cleaned = "解压结果";
        }
        String candidate = cleaned;
        for (int i = 1; existingNames != null && existingNames.contains(candidate); i++) {
            candidate = cleaned + " (" + i + ")";
        }
        return candidate;
    }

    /** 压缩时给单个文件一个包内名字：只取最后一段，再按单段规则清洗。 */
    public static String forZipEntry(String displayName, String fallback) {
        if (displayName == null) {
            return fallback;
        }
        String name = displayName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            cleaned.append(c);
        }
        String safe = cleanSegment(cleaned.toString());
        return safe.isEmpty() ? fallback : safe;
    }
}
