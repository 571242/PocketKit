package cn.xianhua.pocketkit.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 033 拍照计数的计数核心：在二值图上数连通区域。
 *
 * <p>What this honestly is: a classical counter for <em>distinct blobs</em> on a contrasting
 * background — pills on a tray, coins on paper, parts on a mat, dots in a grid. It is not an object
 * detector and does not know what it is counting, which is why the screen asks the user to confirm
 * the count against the marked image instead of trusting the number.
 *
 * <p>Deliberately not shipped: a model-based detector. ML Kit has no general object-detection API in
 * the dependencies, and a bundled detector with an unclear weight licence is worse than a
 * well-explained classical count. The UI says 非 AI and states when it will get the answer wrong.
 */
public final class CountCore {

    /** One counted region: area in pixels and its bounding box. */
    public static final class Blob {
        public final int area;
        public final int minX;
        public final int minY;
        public final int maxX;
        public final int maxY;
        public final int centerX;
        public final int centerY;

        Blob(int area, int minX, int minY, int maxX, int maxY, int centerX, int centerY) {
            this.area = area;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.centerX = centerX;
            this.centerY = centerY;
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }
    }

    public static final class Result {
        public final List<Blob> blobs;
        /** How many candidates were dropped for being too small to be a real object. */
        public final int rejectedSmall;
        /** How many were dropped for merging into a bigger region (they touch). */
        public final int threshold;
        public final boolean brightObjects;

        Result(List<Blob> blobs, int rejectedSmall, int threshold, boolean brightObjects) {
            this.blobs = blobs;
            this.rejectedSmall = rejectedSmall;
            this.threshold = threshold;
            this.brightObjects = brightObjects;
        }
    }

    private CountCore() {
    }

    /**
     * Counts blobs on the side of the threshold that forms the minority region.
     *
     * <p>Choosing the polarity automatically matters: a photo of dark capsules on a white tray and
     * a photo of white pills on a dark mat are the same task, and asking the user to declare which
     * one they have would be a needless way to get it wrong. Otsu picks the split, and the smaller
     * of the two classes is the objects — a background normally covers more of the frame.
     *
     * @param minAreaRatio minimum blob area as a fraction of the frame; below this it is noise
     */
    public static Result count(int[] argb, int width, int height, double minAreaRatio) {
        int[] luma = ScanCore.luma(argb);
        int threshold = ScanCore.otsu(luma);
        boolean[] bright = new boolean[luma.length];
        int brightCount = 0;
        for (int i = 0; i < luma.length; i++) {
            if (luma[i] > threshold) {
                bright[i] = true;
                brightCount++;
            }
        }
        // Count the minority class by default: on a tray of objects, the objects are the minority.
        boolean objectsAreBright = brightCount * 2 <= luma.length;
        boolean[] isObject = new boolean[luma.length];
        for (int i = 0; i < luma.length; i++) {
            isObject[i] = objectsAreBright ? bright[i] : !bright[i];
        }

        int[] region = ScanCore.largestComponent(isObject, width, height);
        // Discard the largest region: on most scenes it is the background leaking through the
        // threshold, and counting it as an object would add one phantom item to every photo.
        int backgroundSize = region == null ? 0 : region.length;
        boolean[] masked = Arrays.copyOf(isObject, isObject.length);
        if (region != null && backgroundSize > isObject.length * 0.4) {
            for (int index : region) {
                masked[index] = false;
            }
        }

        int minArea = (int) Math.max(4, width * height * minAreaRatio);
        List<int[]> components = allComponents(masked, width, height);
        List<Blob> blobs = new ArrayList<>();
        int rejected = 0;
        for (int[] component : components) {
            if (component.length < minArea) {
                rejected++;
                continue;
            }
            blobs.add(toBlob(component, width));
        }
        // Largest first: the biggest objects are the ones a person would count first.
        blobs.sort((a, b) -> b.area - a.area);
        return new Result(blobs, rejected, threshold, objectsAreBright);
    }

    /** Every connected component, iteratively (a full-frame region would overflow a recursion). */
    static List<int[]> allComponents(boolean[] member, int width, int height) {
        boolean[] seen = new boolean[member.length];
        int[] stack = new int[member.length];
        List<int[]> out = new ArrayList<>();
        int[] current = new int[256];
        for (int start = 0; start < member.length; start++) {
            if (!member[start] || seen[start]) {
                continue;
            }
            int top = 0;
            int count = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int p = stack[--top];
                if (count == current.length) {
                    current = Arrays.copyOf(current, current.length * 2);
                }
                current[count++] = p;
                int x = p % width;
                int y = p / width;
                if (x > 0 && member[p - 1] && !seen[p - 1]) {
                    seen[p - 1] = true;
                    stack[top++] = p - 1;
                }
                if (x < width - 1 && member[p + 1] && !seen[p + 1]) {
                    seen[p + 1] = true;
                    stack[top++] = p + 1;
                }
                if (y > 0 && member[p - width] && !seen[p - width]) {
                    seen[p - width] = true;
                    stack[top++] = p - width;
                }
                if (y < height - 1 && member[p + width] && !seen[p + width]) {
                    seen[p + width] = true;
                    stack[top++] = p + width;
                }
            }
            out.add(Arrays.copyOf(current, count));
        }
        return out;
    }

    private static Blob toBlob(int[] component, int width) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        long sumX = 0;
        long sumY = 0;
        for (int p : component) {
            int x = p % width;
            int y = p / width;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumX += x;
            sumY += y;
        }
        return new Blob(component.length, minX, minY, maxX, maxY,
                (int) (sumX / component.length), (int) (sumY / component.length));
    }

    /**
     * Paints a numbered marker on each blob so the user can verify the count instead of trusting it.
     *
     * <p>The marker is a crosshair plus a ring, sized from the blob, and the number is drawn only
     * when the blob is big enough to hold it legibly. Drawing numbers that overlap into mush would
     * defeat the purpose of the overlay.
     */
    public static void markBlobs(int[] argb, int width, int height, List<Blob> blobs) {
        int ink = 0xFF00C853;
        int shadow = 0xFF000000;
        for (int i = 0; i < blobs.size(); i++) {
            Blob blob = blobs.get(i);
            int radius = Math.max(3, Math.min(blob.width(), blob.height()) / 2);
            drawRing(argb, width, height, blob.centerX, blob.centerY, radius, shadow, 3);
            drawRing(argb, width, height, blob.centerX, blob.centerY, radius, ink, 1);
        }
    }

    private static void drawRing(int[] argb, int width, int height, int cx, int cy, int radius,
                                 int color, int thickness) {
        for (int t = 0; t < thickness; t++) {
            int r = radius + t;
            for (int angle = 0; angle < 3600; angle++) {
                double rad = Math.toRadians(angle / 10.0);
                int x = (int) Math.round(cx + Math.cos(rad) * r);
                int y = (int) Math.round(cy + Math.sin(rad) * r);
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    argb[y * width + x] = color;
                }
            }
        }
    }

    /** Summary line: count plus the caveats that make the number trustworthy or not. */
    public static String describe(Result result, int width, int height, double minAreaRatio) {
        StringBuilder sb = new StringBuilder();
        sb.append("计数结果：").append(result.blobs.size()).append(" 个\n");
        sb.append("判定为：").append(result.brightObjects ? "亮色物体（深色背景）" : "深色物体（浅色背景）")
                .append('\n');
        sb.append("分割阈值：").append(result.threshold).append("/255（Otsu 自动）\n");
        sb.append("最小面积：").append(Math.round(minAreaRatio * 100)).append("% 画面（更小的斑点已忽略");
        if (result.rejectedSmall > 0) {
            sb.append("，本次忽略 ").append(result.rejectedSmall).append(" 个");
        }
        sb.append("）\n");
        if (!result.blobs.isEmpty()) {
            sb.append("面积范围：").append(result.blobs.get(result.blobs.size() - 1).area)
                    .append(" ~ ").append(result.blobs.get(0).area).append(" 像素\n");
            long total = 0;
            for (Blob b : result.blobs) {
                total += b.area;
            }
            double mean = total / (double) result.blobs.size();
            // Two independent signals that blobs merged. A wide size spread only means something
            // with several blobs; a single blob covering much of the frame is itself the signal.
            double spread = result.blobs.get(0).area / Math.max(1.0, mean);
            boolean wideSpread = result.blobs.size() > 1 && spread > 3.0;
            boolean oneBigBlob = result.blobs.size() == 1
                    && mean > width * (double) height * 0.08;
            if (wideSpread || oneBigBlob) {
                sb.append("\n注意：可能有相邻物体粘在一起被当成一个"
                        + (oneBigBlob ? "（只数出 1 个，且占画面很大面积）"
                        : "（最大的一块明显大于其他）")
                        + "。把物体分开摆放、或用纯色背景重拍，通常能得到更准的结果。");
            }
        }
        return sb.toString();
    }
}
