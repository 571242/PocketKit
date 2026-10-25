package cn.xianhua.pocketkit.reader;

/** Names and anchors shared by the document parsers and the screen. */
public final class ReaderChapter {
    /** Href prefix for a jump inside the open book; the screen intercepts it, the WebView never loads it. */
    public static final String ANCHOR_PREFIX = "reader-anchor:";

    private ReaderChapter() { }

    public static String anchor(int index) {
        return ANCHOR_PREFIX + index;
    }

    public static int anchorIndex(String href) {
        if (href == null || !href.startsWith(ANCHOR_PREFIX)) return -1;
        try {
            int index = Integer.parseInt(href.substring(ANCHOR_PREFIX.length()));
            return index < 0 ? -1 : index;
        } catch (NumberFormatException notAnAnchor) {
            return -1;
        }
    }
}
