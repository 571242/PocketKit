package cn.xianhua.pocketkit.media;

/**
 * Geometry for the basic edits: rotation and cropping.
 *
 * <p>Pure integer maths, so the clamping and the "crop box is inside the image" checks are exercised
 * by the self-test rather than discovered on a device. Every method returns a box in image
 * coordinates, which the caller turns into a Bitmap.
 */
public final class EditCore {

    /** A crop rectangle in image pixels, already clamped and validated. */
    public static final class Box {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        Box(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public boolean isUsable() {
            return width >= 1 && height >= 1;
        }

        @Override public String toString() {
            return x + "," + y + " " + width + "×" + height;
        }
    }

    private EditCore() {
    }

    /** Size after rotating by a multiple of 90 degrees: the axes swap at 90 and 270. */
    public static int[] rotatedSize(int width, int height, int degrees) {
        int normalized = normalize(degrees);
        if (normalized == 90 || normalized == 270) {
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }

    /** Folds any degree value, including negatives, into 0/90/180/270. */
    public static int normalize(int degrees) {
        int d = degrees % 360;
        if (d < 0) {
            d += 360;
        }
        // Snap to the nearest right angle; other angles would need resampling and are not offered.
        return (int) (Math.round(d / 90.0) * 90) % 360;
    }

    /**
     * A centred crop with the given aspect ratio, as large as fits.
     *
     * @param ratioWidth  horizontal part of the ratio, e.g. 16
     * @param ratioHeight vertical part, e.g. 9
     */
    public static Box centeredAspect(int width, int height, int ratioWidth, int ratioHeight) {
        if (ratioWidth <= 0 || ratioHeight <= 0) {
            throw new IllegalArgumentException("比例必须为正数");
        }
        double target = ratioWidth / (double) ratioHeight;
        double current = width / (double) height;
        int w;
        int h;
        if (current > target) {
            h = height;
            w = (int) Math.round(height * target);
        } else {
            w = width;
            h = (int) Math.round(width / target);
        }
        return box(width, height, (width - w) / 2, (height - h) / 2, w, h);
    }

    /** A crop inset by a percentage of each edge; used by the quick "trim the frame" presets. */
    public static Box inset(int width, int height, double percent) {
        double p = Math.max(0, Math.min(45, percent)) / 100.0;
        int dx = (int) Math.round(width * p);
        int dy = (int) Math.round(height * p);
        return box(width, height, dx, dy, width - dx * 2, height - dy * 2);
    }

    /**
     * Clamps a requested rectangle into the image and rejects degenerate results.
     *
     * <p>A crop that is partly outside is trimmed to the overlap rather than refused: dragging a
     * selection past the edge is normal, and silently returning a black band would be worse than
     * cropping to what exists.
     */
    public static Box box(int imageWidth, int imageHeight, int x, int y, int width, int height) {
        if (imageWidth < 1 || imageHeight < 1) {
            throw new IllegalArgumentException("图片尺寸无效");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("裁剪区域至少要 1×1 像素");
        }
        // The overlap of the requested region with the image: an origin of -10 with width 50 keeps
        // the 40 pixels that are actually inside, rather than sliding the region to x=0 and keeping
        // a shifted 50-pixel band the user did not ask for.
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(imageWidth, x + width);
        int bottom = Math.min(imageHeight, y + height);
        int w = right - left;
        int h = bottom - top;
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("裁剪区域超出了图片范围。这张图是 "
                    + imageWidth + "×" + imageHeight + "，请把区域放在图片内。");
        }
        return new Box(left, top, w, h);
    }

    /** Hit test used by the crop UI to decide whether a drag is inside the selection. */
    public static boolean contains(Box box, int x, int y) {
        return box != null && x >= box.x && y >= box.y
                && x < box.x + box.width && y < box.y + box.height;
    }

    /** A human-readable summary for the result pane. */
    public static String describe(int outWidth, int outHeight) {
        return outWidth + " × " + outHeight + " 像素";
    }
}
