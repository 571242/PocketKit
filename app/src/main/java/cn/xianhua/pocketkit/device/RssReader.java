package cn.xianhua.pocketkit.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简 RSS 2.0 / Atom 条目提取器。
 *
 * 为什么不用 Android 的 XmlPullParser：它在纯 JVM 自检里是 stub（抛 RuntimeException），
 * 那样这段逻辑就没法脱机测试。RSS 结构很浅，用字符串扫描足够，而且可以完整自检。
 *
 * 明确不做的事：不解析 XHTML 正文、不执行任何脚本、不跟随 feed 里的链接。
 */
public final class RssReader {

    private RssReader() {
    }

    /** 一条新闻。 */
    public static final class Item {
        public final String title;
        public final String link;
        public final String pubDate;
        public final String summary;

        Item(String title, String link, String pubDate, String summary) {
            this.title = title;
            this.link = link;
            this.pubDate = pubDate;
            this.summary = summary;
        }
    }

    /** feed 级别的信息。 */
    public static final class Feed {
        public final String title;
        public final List<Item> items;

        Feed(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    /** 解析失败抛 IllegalArgumentException，绝不返回空列表冒充"没有新闻"。 */
    public static Feed parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("收到的内容为空，不是有效的 RSS。");
        }
        String text = xml.trim();
        if (!text.startsWith("<?xml") && !text.startsWith("<rss") && !text.startsWith("<feed")) {
            throw new IllegalArgumentException(
                    "返回的内容不是 RSS/Atom 文档（可能是登录页或错误页）。开头：" + snippet(text));
        }

        String channelTitle = firstTagText(text, "title");
        List<Item> items = new ArrayList<>();

        // RSS 2.0 用 <item>，Atom 用 <entry>。
        for (String block : blocks(text, "item")) {
            items.add(readItem(block, false));
        }
        if (items.isEmpty()) {
            for (String block : blocks(text, "entry")) {
                items.add(readItem(block, true));
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("这份 RSS 里没有可读的条目（可能只有频道信息）。");
        }
        return new Feed(channelTitle == null ? "（未署名来源）" : channelTitle, items);
    }

    private static Item readItem(String block, boolean atom) {
        String title = firstTagText(block, "title");
        // Atom 的链接在属性里：<link href="..."/>
        String link = atom ? attrValue(block, "link", "href") : firstTagText(block, "link");
        if (link == null) {
            link = attrValue(block, "link", "href");
        }
        String date = firstTagText(block, "pubDate");
        if (date == null) {
            date = firstTagText(block, "published");
        }
        if (date == null) {
            date = firstTagText(block, "updated");
        }
        if (date == null) {
            // 部分中文 feed 用 dc:date 命名空间。
            date = firstTagText(block, "dc:date");
        }
        String summary = firstTagText(block, "description");
        if (summary == null) {
            summary = firstTagText(block, "summary");
        }
        return new Item(clean(title), clean(link), clean(date), clean(summary));
    }

    /** 取出所有 &lt;tag&gt;...&lt;/tag&gt; 块的内容（含标签本身）。 */
    public static List<String> blocks(String xml, String tag) {
        List<String> found = new ArrayList<>();
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int from = 0;
        while (true) {
            int start = xml.indexOf(open, from);
            if (start < 0) {
                return found;
            }
            // 确认紧接着是 '>' 或空白，避免把 <items> 当成 <item>。
            int nameEnd = start + open.length();
            if (nameEnd < xml.length()) {
                char next = xml.charAt(nameEnd);
                if (next != '>' && !Character.isWhitespace(next)) {
                    from = nameEnd;
                    continue;
                }
            }
            int end = xml.indexOf(close, start);
            if (end < 0) {
                return found;
            }
            found.add(xml.substring(start, end + close.length()));
            from = end + close.length();
        }
    }

    /** 取第一个该标签的文本内容，做实体与 CDATA 处理；找不到返回 null。 */
    public static String firstTagText(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        String open = "<" + tag;
        int start = 0;
        while (true) {
            start = xml.indexOf(open, start);
            if (start < 0) {
                return null;
            }
            int nameEnd = start + open.length();
            if (nameEnd < xml.length()) {
                char next = xml.charAt(nameEnd);
                if (next != '>' && !Character.isWhitespace(next) && next != '/') {
                    start = nameEnd;
                    continue;
                }
            }
            int gt = xml.indexOf('>', start);
            if (gt < 0) {
                return null;
            }
            // 自闭合标签（如 <link href="..."/>）没有文本内容。
            if (gt > start && xml.charAt(gt - 1) == '/') {
                return null;
            }
            String close = "</" + tag + ">";
            int end = xml.indexOf(close, gt);
            if (end < 0) {
                return null;
            }
            String raw = xml.substring(gt + 1, end);
            if (raw.startsWith("<![CDATA[")) {
                // CDATA 里不该再有嵌套 CDATA；取到第一个 ]]> 为止。
                int cdataEnd = raw.indexOf("]]>");
                raw = cdataEnd >= 0 ? raw.substring(9, cdataEnd) : raw.substring(9);
            } else {
                raw = decodeEntities(raw);
            }
            return raw.trim();
        }
    }

    /** 取属性值，例如 &lt;link href="x"/&gt; 里的 href。 */
    public static String attrValue(String xml, String tag, String attribute) {
        if (xml == null) {
            return null;
        }
        // 注意：不能用 blocks()，它要求存在闭合标签，而 Atom 的
        // <link href="..."/> 是自闭合的，那样会漏掉链接（已由自检抓到）。
        // 属性只出现在开始标签里，取到第一个 '>' 为止即可。
        String open = "<" + tag;
        int start = 0;
        while (true) {
            start = xml.indexOf(open, start);
            if (start < 0) {
                return null;
            }
            int nameEnd = start + open.length();
            if (nameEnd < xml.length()) {
                char next = xml.charAt(nameEnd);
                if (next != '>' && !Character.isWhitespace(next) && next != '/') {
                    start = nameEnd;
                    continue;
                }
            }
            int tagEnd = xml.indexOf('>', start);
            if (tagEnd < 0) {
                return null;
            }
            String header = xml.substring(start, tagEnd);
            String needle = attribute + "=";
            int at = header.indexOf(needle);
            if (at < 0) {
                start = tagEnd + 1;
                continue;
            }
            int quote = at + needle.length();
            if (quote >= header.length()) {
                start = tagEnd + 1;
                continue;
            }
            char q = header.charAt(quote);
            if (q != '"' && q != '\'') {
                start = tagEnd + 1;
                continue;
            }
            int close = header.indexOf(q, quote + 1);
            if (close < 0) {
                start = tagEnd + 1;
                continue;
            }
            return decodeEntities(header.substring(quote + 1, close)).trim();
        }
    }

    /** XML 预定义实体 + 数字实体。 */
    public static String decodeEntities(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = raw.indexOf(';', i);
            // 实体名最长 8 个字符左右；找不到分号就按字面量处理。
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String name = raw.substring(i + 1, semi);
            String replacement = null;
            switch (name) {
                case "amp": replacement = "&"; break;
                case "lt": replacement = "<"; break;
                case "gt": replacement = ">"; break;
                case "quot": replacement = "\""; break;
                case "apos": replacement = "'"; break;
                case "nbsp": replacement = " "; break;
                default:
                    if (name.startsWith("#")) {
                        try {
                            int code = name.startsWith("#x") || name.startsWith("#X")
                                    ? Integer.parseInt(name.substring(2), 16)
                                    : Integer.parseInt(name.substring(1));
                            replacement = new String(Character.toChars(code));
                        } catch (Exception ignored) {
                            replacement = null;
                        }
                    }
                    break;
            }
            if (replacement == null) {
                sb.append(c);
                i++;
            } else {
                sb.append(replacement);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    /** 去掉 HTML 标签（摘要里常带 <p> 之类）并压缩空白。 */
    public static String stripTags(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean inTag = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return decodeEntities(sb.toString()).replaceAll("\\s+", " ").trim();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = stripTags(value);
        return text == null || text.isEmpty() ? null : text;
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 80 ? flat.substring(0, 80) : flat;
    }

    /** 把 RSS 的日期规整成简短可读形式；无法识别时原样返回。 */
    public static String shortDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "时间未标注";
        }
        // 常见格式：Tue, 13 Oct 2026 08:00:00 +0800
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        String trimmed = raw.trim();
        for (int m = 0; m < months.length; m++) {
            int at = trimmed.indexOf(months[m]);
            if (at < 0) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 4) {
                break;
            }
            // 形如 [Tue,] 13 Oct 2026 ...
            for (int i = 0; i < parts.length - 2; i++) {
                if (parts[i + 1].equals(months[m]) && parts[i].length() <= 2 && parts[i + 2].length() == 4) {
                    String time = i + 3 < parts.length ? parts[i + 3] : "";
                    return String.format(Locale.US, "%s-%02d-%02d%s",
                            parts[i + 2], m + 1, safeInt(parts[i]),
                            time.matches("\\d{2}:\\d{2}.*") ? " " + time.substring(0, 5) : "");
                }
            }
        }
        // ISO 8601：2026-10-13T08:00:00Z
        if (trimmed.length() >= 16 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            return trimmed.substring(0, 10) + " " + trimmed.substring(11, 16);
        }
        return trimmed;
    }

    private static int safeInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
