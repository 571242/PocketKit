package cn.xianhua.pocketkit.pinoverlay;

/**
 * Screen-side math for a pinned reference image. No Android types, so the same code runs in a
 * plain JDK test; every caller passes integers it already has (bitmap size, screen, window rect).
 */
public final class PinGeometry {
    private PinGeometry() {}

    /** 100% → 75% → 50% → 30% → 100%; the overlay shows the image only this translucent. */
    private static final float[] ALPHAS = {1f, .75f, .5f, .3f};

    /** Largest power of two that leaves BOTH sides at or below {@code maxSide} after integer division. */
    public static int sampleSize(int width, int height, int maxSide) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("图片尺寸无效");
        int sample = 1;
        if (maxSide <= 0) return sample;
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2;
        return sample;
    }

    /**
     * Aspect-preserving size inside {@code maxWidth} × {@code maxHeight}. Images smaller than the
     * box keep their pixels: a 200 px screenshot must not be blown up into a blur.
     */
    public static int[] fit(int srcWidth, int srcHeight, int maxWidth, int maxHeight) {
        if (srcWidth <= 0 || srcHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) throw new IllegalArgumentException("尺寸无效");
        double scale = Math.min(maxWidth / (double) srcWidth, maxHeight / (double) srcHeight);
        if (scale > 1d) scale = 1d;
        return new int[]{Math.max(1, (int) Math.round(srcWidth * scale)), Math.max(1, (int) Math.round(srcHeight * scale))};
    }

    /**
     * Pinch result: keep the window aspect, clamp the width so the height also stays on screen.
     * For a very tall image {@code maxHeight / ratio} can fall below {@code minWidth}, so the floor
     * is lowered to the ceiling instead of producing an inverted range.
     */
    public static int[] scaled(int width, int height, float factor, int minWidth, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("窗口尺寸无效");
        if (!(factor > 0f) || Float.isInfinite(factor)) throw new IllegalArgumentException("缩放比例无效");
        if (maxWidth <= 0 || maxHeight <= 0) throw new IllegalArgumentException("屏幕尺寸无效");
        double ratio = height / (double) width;
        int ceiling = Math.max(1, Math.min(maxWidth, (int) Math.floor(maxHeight / ratio)));
        int floor = Math.min(Math.max(1, minWidth), ceiling);
        long wanted = Math.round(width * (double) factor);
        int nextWidth = (int) Math.max(floor, Math.min(ceiling, wanted));
        return new int[]{nextWidth, Math.max(1, (int) Math.round(nextWidth * ratio))};
    }

    /** Centred first position; the drag handler moves it from there. */
    public static int[] place(int windowWidth, int windowHeight, int screenWidth, int screenHeight) {
        if (windowWidth <= 0 || windowHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) throw new IllegalArgumentException("尺寸无效");
        return new int[]{Math.max(0, (screenWidth - windowWidth) / 2), Math.max(0, (screenHeight - windowHeight) / 2)};
    }

    /**
     * Position after a drag or a rotation. A window that fits is pulled fully on screen; an
     * oversized one keeps {@code minVisible} px reachable instead of leaving the user with a
     * window they can no longer touch.
     */
    public static int[] onScreen(int x, int y, int width, int height, int screenWidth, int screenHeight, int minVisible) {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) throw new IllegalArgumentException("尺寸无效");
        int keep = Math.max(1, Math.min(minVisible, Math.min(width, height)));
        return new int[]{axis(x, width, screenWidth, keep), axis(y, height, screenHeight, keep)};
    }

    private static int axis(int value, int size, int screen, int keep) {
        if (size <= screen) return Math.max(0, Math.min(screen - size, value));
        return Math.max(-(size - keep), Math.min(screen - keep, value));
    }

    /** Where the small block sits when the pin is tucked away: over the image centre. */
    public static int[] collapse(int x, int y, int width, int height, int blockSize) {
        if (width <= 0 || height <= 0 || blockSize <= 0) throw new IllegalArgumentException("尺寸无效");
        return new int[]{x + (width - blockSize) / 2, y + (height - blockSize) / 2};
    }

    public static float nextAlpha(float current) {
        for (int i = 0; i < ALPHAS.length; i++) {
            if (Math.abs(ALPHAS[i] - current) < .01f) return ALPHAS[(i + 1) % ALPHAS.length];
        }
        return ALPHAS[0];
    }

    public static int percent(float alpha) {
        return Math.round(Math.max(0f, Math.min(1f, alpha)) * 100f);
    }

    /** EXIF orientation 1..8 → clockwise rotation that stands the pixels upright. */
    public static int exifDegrees(int orientation) {
        switch (orientation) {
            case 3:
            case 4: return 180;
            case 5:
            case 6: return 90;
            case 7:
            case 8: return 270;
            default: return 0;
        }
    }

    public static boolean exifMirrors(int orientation) {
        return orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7;
    }

    /**
     * Longest side to decode at: enough to fill the screen's longest side, never past 2048 px
     * (2048² ARGB_8888 is ~16 MB, and up to three pins may be alive at once).
     */
    public static int decodeSide(int screenWidth, int screenHeight) {
        int longest = Math.max(screenWidth, screenHeight);
        if (longest <= 0) return 1024;
        return Math.max(720, Math.min(2048, longest));
    }
}
