package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * EPUB in reading order, one section per spine item.
 *
 * <p>Only the text of the book is shown: styles, fonts and images stay out. A link to another
 * chapter becomes {@code reader-anchor:index} so the reader can jump there itself; a link to the web
 * keeps only its absolute address, which the screen hands to a browser.
 * DRM-protected books have no readable spine document and are refused with their own message.
 */
public final class EpubBook {
    private static final Pattern HREF = Pattern.compile("(?i)(\\bhref\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*:.*");

    private final Map<String, byte[]> entries;
    private final String opfPath;
    private final String declaredTitle;
    private final List<String> spine = new ArrayList<>();
    private final Map<String, String> manifest = new LinkedHashMap<>();

    private EpubBook(Map<String, byte[]> entries) throws IOException {
        this.entries = entries;
        byte[] container = entries.get("META-INF/container.xml");
        if (container == null) throw new IOException("EPUB 缺少 META-INF/container.xml，文件可能损坏。");
        List<Element> roots = SafeXml.descendants(SafeXml.parse(container), "rootfile");
        if (roots.isEmpty()) throw new IOException("EPUB 没有声明内容文件，文件可能损坏。");
        this.opfPath = ReaderIo.resolveZipPath("", SafeXml.attribute(roots.get(0), "full-path"));
        byte[] opf = entries.get(opfPath);
        if (opf == null) throw new IOException("EPUB 声明的内容文件不在压缩包内：" + opfPath);
        this.declaredTitle = readPackage(SafeXml.parse(opf));
    }

    public static ReaderDocument parse(String fallbackTitle, Map<String, byte[]> entries) throws IOException {
        EpubBook book = new EpubBook(entries);
        List<ReaderDocument.Section> sections = new ArrayList<>();
        int budget = ReaderIo.MAX_TEXT_BYTES;
        for (String href : book.spine) {
            byte[] raw = entries.get(href);
            if (raw == null) continue;
            String html = HtmlPolicy.sanitize(book.rewriteLinks(ReaderText.decode(raw).text(), href));
            if (html.isBlank()) continue;
            budget -= html.length();
            if (budget < 0) throw new IOException("这本书的正文超过阅读上限，请分册打开。");
            String chapter = chapterTitle(html, sections.size() + 1);
            sections.add(new ReaderDocument.Section(chapter, html, HtmlPolicy.plain(html)));
        }
        if (sections.isEmpty()) {
            throw new IOException("这本书按 spine 没有可显示的正文。它可能受 DRM 保护，或内容是纯图片；"
                    + "本阅读器不处理 DRM，也不提取图片。");
        }
        String title = book.declaredTitle.isBlank() ? fallbackTitle : book.declaredTitle;
        return new ReaderDocument(ReaderDocument.Format.EPUB, title, sections,
                sections.size() + " 章 · 按 spine 顺序 · 只显示文字，图片与样式已省略");
    }

    private String readPackage(Document opf) throws IOException {
        for (Element item : SafeXml.descendants(opf, "item")) {
            String id = SafeXml.attribute(item, "id");
            String href = SafeXml.attribute(item, "href");
            if (id.isEmpty() || href.isEmpty()) continue;
            manifest.putIfAbsent(id, resolve(href));
        }
        for (Element itemref : SafeXml.descendants(opf, "itemref")) {
            String href = manifest.get(SafeXml.attribute(itemref, "idref"));
            if (href != null && entries.containsKey(href)) spine.add(href);
        }
        List<Element> titles = SafeXml.descendants(opf, "title");
        return titles.isEmpty() ? "" : titles.get(0).getTextContent().trim();
    }

    private String resolve(String href) {
        try {
            return ReaderIo.resolveZipPath(opfPath, href);
        } catch (IOException outside) {
            return ""; // A manifest entry that escapes the archive is not part of the book.
        }
    }

    private String rewriteLinks(String html, String from) {
        Matcher link = HREF.matcher(html);
        StringBuffer out = new StringBuffer(html.length());
        while (link.find()) {
            String target = (link.group(3) != null ? link.group(3) : link.group(4)).trim();
            String replacement = "";
            if (!target.startsWith("#") && ABSOLUTE.matcher(target).matches()) {
                if (target.regionMatches(true, 0, "https://", 0, 8) || target.regionMatches(true, 0, "http://", 0, 7)
                        || target.regionMatches(true, 0, "mailto:", 0, 7)) {
                    replacement = target;
                }
            } else if (!target.startsWith("#")) {
                int index = chapterIndex(from, target);
                if (index >= 0) replacement = ReaderChapter.ANCHOR_PREFIX + index;
            }
            link.appendReplacement(out, Matcher.quoteReplacement(replacement.isEmpty() ? "" : "href=\"" + replacement + "\""));
        }
        link.appendTail(out);
        return out.toString();
    }

    /** Whole-book reference to a chapter or to an anchor inside it; -1 when it leaves the book. */
    private int chapterIndex(String from, String target) {
        String path = target;
        int hash = target.indexOf('#');
        if (hash >= 0) path = target.substring(0, hash);
        if (path.isEmpty()) return spine.indexOf(from);
        try {
            return spine.indexOf(ReaderIo.resolveZipPath(from, path));
        } catch (IOException outside) {
            return -1;
        }
    }

    private static String chapterTitle(String html, int position) {
        Matcher heading = Pattern.compile("(?is)<h[12][^>]*>(.*?)</h[12]>").matcher(html);
        if (heading.find()) {
            String text = HtmlPolicy.plain(heading.group(1));
            if (!text.isBlank() && text.length() <= 60) return text;
        }
        return "第 " + position + " 节";
    }
}
