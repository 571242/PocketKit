package cn.xianhua.pocketkit.voiceoverlay;

/** Screen-space docking math kept independent of Android for regression tests. */
public final class EdgeDock {
    private EdgeDock() {}
    public static int clamp(int value, int max) { return Math.max(0, Math.min(Math.max(0, max), value)); }
    public static int nearEdge(int x, int width, int screenWidth, int threshold) {
        if (x <= threshold) return -1;
        if (x + width >= screenWidth - threshold) return 1;
        return 0;
    }
    public static int nearest(int x, int width, int screenWidth) { return x + width / 2 < screenWidth / 2 ? -1 : 1; }
    public static int x(int side, int width, int screenWidth) { return side < 0 ? 0 : Math.max(0, screenWidth - width); }
}
