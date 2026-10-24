package cn.xianhua.pocketkit.core.automation;

/** A recognition result in current-screen physical pixels. */
public final class Match {
    public final boolean found;
    public final int left, top, right, bottom;
    public final String text;
    public final double score;

    public Match(boolean found, int left, int top, int right, int bottom, String text, double score) {
        this.found = found; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.text = text == null ? "" : text; this.score = score;
    }
    public int centerX() { return left + (right - left) / 2; }
    public int centerY() { return top + (bottom - top) / 2; }
    public static Match notFound() { return new Match(false, 0, 0, 0, 0, "", 0); }
}
