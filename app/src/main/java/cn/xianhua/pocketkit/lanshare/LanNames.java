package cn.xianhua.pocketkit.lanshare;

import java.util.Locale;
import java.util.Set;

/**
 * 文件名清洗与重名处理。纯逻辑，可脱机测试。
 *
 * 浏览器传来的名字完全不可信：可能带路径分隔符、控制字符、前导点，也可能是
 * 几 KB 的垃圾。这里只做「变成一个安全的单段显示名」，不负责落盘 ——
 * 落盘一律走 SAF，绝不拼接本地路径。
 */
public final class LanNames {

    private LanNames() {
    }

    /** 单段文件名的最大字符数；超出时保留扩展名截断主体。 */
    static final int MAX_NAME = 120;
    static final String FALLBACK = "未命名文件";

    /** 清洗为单段文件名；拿不到有效内容时返回空串，由调用方兜底。 */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw;
        // 只保留最后一段：../../etc/passwd → passwd
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // 控制字符与 NUL 直接丢弃，避免透传到 SAF 或响应头。
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            cleaned.append(c);
        }
        name = cleaned.toString().trim();
        // 前导点：既挡住 "." / ".."，也别让对方丢一个隐藏文件进来。
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "";
        }
        return truncate(name, MAX_NAME);
    }

    /** 清洗后仍为空则用带时间戳的兜底名，保证上传永远有名字。 */
    public static String sanitizeOrFallback(String raw, long stamp) {
        String cleaned = sanitize(raw);
        return cleaned.isEmpty() ? "上传-" + stamp : cleaned;
    }

    /**
     * 同名时插入 (1)(2)…，绝不覆盖已有文件。
     *
     * taken 是接收目录里已有的名字（大小写不敏感比较，和 SAF 提供方的实际行为一致）。
     */
    public static String unique(String name, Set<String> taken) {
        if (name == null || name.isEmpty()) {
            name = FALLBACK;
        }
        if (taken == null || taken.isEmpty() || !containsIgnoreCase(taken, name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (!containsIgnoreCase(taken, candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")" + extension;
    }

    private static boolean containsIgnoreCase(Set<String> taken, String name) {
        if (taken.contains(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String existing : taken) {
            if (existing != null && existing.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String name, int max) {
        if (name.length() <= max) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
        int keep = Math.max(1, max - extension.length());
        return name.substring(0, keep) + extension;
    }
}
