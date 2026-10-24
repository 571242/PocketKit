package cn.xianhua.pocketkit.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Document-scan imaging: page-edge detection, perspective correction and shadow removal.
 *
 * <p>Written directly over {@code int[] ARGB} pixels rather than pulled from a CV library. The
 * three operations a document scanner actually needs are small and can be verified off-device on
 * synthetic images, which is what makes this approach defensible for a tool that ships offline;
 * the module does not link a native OpenCV it cannot test here.
 *
 * <p>Cost is bounded by the caller: detection is meant to run on a downscaled copy (long edge
 * ~1000 px), and the returned quad is scaled back to full resolution for the warp.
 */
public final class ScanCore {

    /** Corner order is top-left, top-right, bottom-right, bottom-left. */
    public static final class Quad {
        public final double[] xs = new double[4];
        public final double[] ys = new double[4];

        public Quad(double x0, double y0, double x1, double y1,
                    double x2, double y2, double x3, double y3) {
            xs[0] = x0; ys[0] = y0;
            xs[1] = x1; ys[1] = y1;
            xs[2] = x2; ys[2] = y2;
            xs[3] = x3; ys[3] = y3;
        }

        /** A quad covering the whole image; used when detection is not confident. */
        public static Quad whole(int w, int h) {
            return new Quad(0, 0, w - 1, 0, w - 1, h - 1, 0, h - 1);
        }

        /** Scales every corner; used to map a detection back to full resolution. */
        public Quad scaled(double factor) {
            return new Quad(xs[0] * factor, ys[0] * factor, xs[1] * factor, ys[1] * factor,
                    xs[2] * factor, ys[2] * factor, xs[3] * factor, ys[3] * factor);
        }

        public Quad clampedTo(int w, int h) {
            return new Quad(clampD(xs[0], 0, w - 1), clampD(ys[0], 0, h - 1),
                    clampD(xs[1], 0, w - 1), clampD(ys[1], 0, h - 1),
                    clampD(xs[2], 0, w - 1), clampD(ys[2], 0, h - 1),
                    clampD(xs[3], 0, w - 1), clampD(ys[3], 0, h - 1));
        }

        private static double clampD(double v, double lo, double hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        public double area() {
            double sum = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                sum += xs[i] * ys[j] - xs[j] * ys[i];
            }
            return Math.abs(sum) / 2.0;
        }

        /** Average of the two horizontal sides; the output width hint. */
        public double width() {
            return (dist(0, 1) + dist(3, 2)) / 2.0;
        }

        /** Average of the two vertical sides; the output height hint. */
        public double height() {
            return (dist(0, 3) + dist(1, 2)) / 2.0;
        }

        public double dist(int a, int b) {
            double dx = xs[a] - xs[b];
            double dy = ys[a] - ys[b];
            return Math.sqrt(dx * dx + dy * dy);
        }

        /** Convex, non-degenerate and roughly on-screen; a self-crossing quad means detection broke. */
        public boolean isSane(int w, int h, double minAreaRatio) {
            if (area() < w * (double) h * minAreaRatio) {
                return false;
            }
            double sign = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                int k = (i + 2) % 4;
                double cross = (xs[j] - xs[i]) * (ys[k] - ys[j]) - (ys[j] - ys[i]) * (xs[k] - xs[j]);
                if (cross == 0) {
                    return false;
                }
                if (sign == 0) {
                    sign = cross;
                } else if (sign * cross < 0) {
                    return false;
                }
            }
            for (int i = 0; i < 4; i++) {
                if (xs[i] < -w * 0.05 || xs[i] > w * 1.05 || ys[i] < -h * 0.05 || ys[i] > h * 1.05) {
                    return false;
                }
            }
            return true;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(java.util.Locale.US, "(%.0f,%.0f)", xs[i], ys[i]));
                if (i < 3) {
                    sb.append(' ');
                }
            }
            return sb.toString();
        }
    }

    private ScanCore() {
    }

    // ---------------------------------------------------------------- primitives

    /** Rec. 601 luma, 0..255. */
    public static int[] luma(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            out[i] = (r * 77 + g * 150 + b * 29) >> 8;
        }
        return out;
    }

    /** Separable box blur; O(n) in the radius, so it is cheap enough to run twice per scan. */
    public static int[] boxBlur(int[] src, int w, int h, int radius) {
        if (radius < 1) {
            return src.clone();
        }
        int[] tmp = new int[src.length];
        int[] out = new int[src.length];
        int window = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += src[row + clamp(x, 0, w - 1)];
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = (int) (sum / window);
                sum -= src[row + clamp(x - radius, 0, w - 1)];
                sum += src[row + clamp(x + radius + 1, 0, w - 1)];
            }
        }
        for (int x = 0; x < w; x++) {
            long sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += tmp[clamp(y, 0, h - 1) * w + x];
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (int) (sum / window);
                sum -= tmp[clamp(y - radius, 0, h - 1) * w + x];
                sum += tmp[clamp(y + radius + 1, 0, h - 1) * w + x];
            }
        }
        return out;
    }

    /** Otsu's threshold over a histogram of 0..max. Returns the level that best splits the two modes. */
    public static int otsu(int[] values, int max) {
        int[] hist = new int[max + 1];
        for (int v : values) {
            hist[v < 0 ? 0 : (v > max ? max : v)]++;
        }
        long total = values.length;
        double sum = 0;
        for (int i = 0; i <= max; i++) {
            sum += (double) i * hist[i];
        }
        double sumB = 0;
        long wB = 0;
        double best = -1;
        int bestThreshold = 0;
        for (int i = 0; i <= max; i++) {
            wB += hist[i];
            if (wB == 0) {
                continue;
            }
            long wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += (double) i * hist[i];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            // Ties go to the highest level: a gap in the histogram makes every level between the two
            // modes score equally, and taking the lowest would put the split inside the dark mode and
            // classify it as bright. The highest level of the plateau separates the two modes cleanly.
            if (between >= best) {
                best = between;
                bestThreshold = i;
            }
        }
        return bestThreshold;
    }

    /** Otsu over an 8-bit plane. */
    public static int otsu(int[] values) {
        return otsu(values, 255);
    }

    // ---------------------------------------------------------------- edge detection

    public static final class Detected {
        public final Quad quad;
        /** False means "no confident page boundary"; the quad is then the whole image. */
        public final boolean found;
        /** 0..1: how well the quad hugs an actual uniform-brightness page-shaped region. */
        public final double confidence;

        Detected(Quad quad, boolean found, double confidence) {
            this.quad = quad;
            this.found = found;
            this.confidence = confidence;
        }
    }

    /**
     * Finds the page quad.
     *
     * <p>Method: Otsu-split the blurred luma into a bright and a dark class, take the largest
     * connected region of each, take the convex hull of that region, and reduce the hull to four
     * corners as the extreme points along the two diagonals. The two polarities are scored against
     * each other and the better one wins, which covers both "white page on a dark desk" and
     * "dark page on a light desk".
     *
     * <p>The score is the fraction of the quad that the region actually fills, so a hull that
     * wandered off the page scores low. Below {@link #MIN_CONFIDENCE} the method reports
     * {@code found = false} and hands back the whole image: a scanner that silently crops to a
     * guess is worse than one that says it could not find the paper.
     */
    public static final double MIN_CONFIDENCE = 0.62;

    public static Detected detectPage(int[] argb, int w, int h) {
        Quad fallback = Quad.whole(w, h);
        if (w < 32 || h < 32 || argb.length < w * h) {
            return new Detected(fallback, false, 0);
        }
        int[] luma = boxBlur(luma(argb), w, h, Math.max(1, Math.min(w, h) / 120));
        int threshold = otsu(luma);
        // Two polarities, because a page may be the bright region or, on a white desk, the dark one.
        Detected bright = fromClass(luma, w, h, threshold, true);
        Detected dark = fromClass(luma, w, h, threshold, false);
        Detected best = bright.confidence >= dark.confidence ? bright : dark;
        if (best.quad == null || best.confidence < MIN_CONFIDENCE) {
            return new Detected(fallback, false, best.quad == null ? 0 : best.confidence);
        }
        return best;
    }

    /**
     * Detection over the largest connected region on one side of {@code threshold}.
     *
     * @param bright true selects pixels above the threshold (a white page on a dark desk),
     *               false selects the rest (a dark page on a white desk)
     */
    private static Detected fromClass(int[] luma, int w, int h, int threshold, boolean bright) {
        boolean[] member = new boolean[luma.length];
        for (int i = 0; i < luma.length; i++) {
            if (bright ? luma[i] > threshold : luma[i] <= threshold) {
                member[i] = true;
            }
        }
        int[] region = largestComponent(member, w, h);
        if (region == null || region.length < w * (double) h * 0.08) {
            return new Detected(null, false, 0);
        }
        double[] hull = convexHullXy(region, w);
        Quad quad = quadFromHull(hull, w, h);
        if (quad == null || !quad.isSane(w, h, 0.10)) {
            return new Detected(null, false, 0);
        }
        // A quad covering essentially the whole frame carries no boundary information: a page
        // photographed edge-to-edge has nothing to find, and a flat image would otherwise score a
        // perfect confidence against its own full-frame component. Rejected per class, so one
        // polarity covering the frame does not veto a genuine page found by the other — which is
        // exactly what happens when the background is the larger bright region.
        if (quad.area() >= w * (double) h * 0.95) {
            return new Detected(null, false, 0);
        }
        return new Detected(quad, true, fillRatio(quad, member, w, h, region.length));
    }

    /** How much of the quad the region actually occupies, plus a penalty for loose hulls. */
    private static double fillRatio(Quad quad, boolean[] member, int w, int h, int regionSize) {
        // Sample on a grid rather than every pixel: this runs per detection, not per output pixel.
        int step = Math.max(2, Math.min(w, h) / 96);
        int inside = 0;
        int hits = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (!contains(quad, x + 0.5, y + 0.5)) {
                    continue;
                }
                inside++;
                if (member[y * w + x]) {
                    hits++;
                }
            }
        }
        if (inside == 0) {
            return 0;
        }
        double coverage = hits / (double) inside;
        // Also require the region to be a real portion of the frame, not a speck that happens to
        // sit inside a big quad.
        double presence = regionSize / (double) (w * h) / 0.35;
        if (presence > 1) {
            presence = 1;
        }
        return coverage * presence;
    }

    static boolean contains(Quad q, double x, double y) {
        boolean in = false;
        for (int i = 0, j = 3; i < 4; j = i++) {
            double yi = q.ys[i];
            double yj = q.ys[j];
            if ((yi > y) != (yj > y)
                    && x < (q.xs[j] - q.xs[i]) * (y - yi) / (yj - yi) + q.xs[i]) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * Largest 4-connected component of {@code member}, returned as packed pixel indexes.
     * Iterative flood fill with an int stack: a recursive fill would blow the stack on a
     * full-page region.
     */
    static int[] largestComponent(boolean[] member, int w, int h) {
        boolean[] seen = new boolean[member.length];
        int[] stack = new int[member.length];
        int[] best = null;
        int[] current = new int[1024];
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
                int x = p % w;
                int y = p / w;
                if (x > 0 && member[p - 1] && !seen[p - 1]) {
                    seen[p - 1] = true;
                    stack[top++] = p - 1;
                }
                if (x < w - 1 && member[p + 1] && !seen[p + 1]) {
                    seen[p + 1] = true;
                    stack[top++] = p + 1;
                }
                if (y > 0 && member[p - w] && !seen[p - w]) {
                    seen[p - w] = true;
                    stack[top++] = p - w;
                }
                if (y < h - 1 && member[p + w] && !seen[p + w]) {
                    seen[p + w] = true;
                    stack[top++] = p + w;
                }
            }
            if (best == null || count > best.length) {
                best = Arrays.copyOf(current, count);
            }
            if (best.length > member.length / 2) {
                break;
            }
        }
        return best;
    }

    /**
     * 4 corners from a hull: the extreme point along each diagonal direction. Works for any
     * rotation as long as the page is not turned past 45 degrees, which is the normal case for a
     * photographed document.
     */
    static Quad quadFromHull(double[] hullXy, int w, int h) {
        if (hullXy == null || hullXy.length < 8) {
            return null;
        }
        int n = hullXy.length / 2;
        int tl = 0;
        int tr = 0;
        int br = 0;
        int bl = 0;
        double bestTl = Double.MAX_VALUE;
        double bestTr = -Double.MAX_VALUE;
        double bestBr = -Double.MAX_VALUE;
        double bestBl = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = hullXy[i * 2];
            double y = hullXy[i * 2 + 1];
            double sum = x + y;
            double diff = x - y;
            if (sum < bestTl) {
                bestTl = sum;
                tl = i;
            }
            if (sum > bestBr) {
                bestBr = sum;
                br = i;
            }
            if (diff > bestTr) {
                bestTr = diff;
                tr = i;
            }
            if (diff < bestBl) {
                bestBl = diff;
                bl = i;
            }
        }
        if (tl == tr || tl == br || tl == bl || tr == br || tr == bl || br == bl) {
            return null;
        }
        Quad q = new Quad(hullXy[tl * 2], hullXy[tl * 2 + 1], hullXy[tr * 2], hullXy[tr * 2 + 1],
                hullXy[br * 2], hullXy[br * 2 + 1], hullXy[bl * 2], hullXy[bl * 2 + 1]);
        return q.isSane(w, h, 0.10) ? q : null;
    }

    // ---------------------------------------------------------------- warp

    /**
     * Rectifies {@code quad} into a straight rectangle of {@code outW × outH} by inverse mapping
     * with bilinear sampling. Sampling per destination pixel (rather than scattering source pixels
     * forward) leaves no holes when the page is foreshortened.
     */
    public static int[] warp(int[] argb, int w, int h, Quad quad, int outW, int outH) {
        double[] m = homography(
                0, 0, outW - 1, 0, outW - 1, outH - 1, 0, outH - 1,
                quad.xs[0], quad.ys[0], quad.xs[1], quad.ys[1],
                quad.xs[2], quad.ys[2], quad.xs[3], quad.ys[3]);
        int[] out = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double den = m[6] * x + m[7] * y + m[8];
                if (den == 0) {
                    out[y * outW + x] = 0xFFFFFFFF;
                    continue;
                }
                double u = (m[0] * x + m[1] * y + m[2]) / den;
                double v = (m[3] * x + m[4] * y + m[5]) / den;
                out[y * outW + x] = sample(argb, w, h, u, v);
            }
        }
        return out;
    }

    /**
     * 3x3 homography from four destination corners to four source corners, solved as the standard
     * 8-equation system by Gauss-Jordan elimination with partial pivoting.
     */
    static double[] homography(double... p) {
        double[] dx = {p[0], p[2], p[4], p[6]};
        double[] dy = {p[1], p[3], p[5], p[7]};
        double[] sx = {p[8], p[10], p[12], p[14]};
        double[] sy = {p[9], p[11], p[13], p[15]};
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            a[i * 2][0] = dx[i];
            a[i * 2][1] = dy[i];
            a[i * 2][2] = 1;
            a[i * 2][6] = -dx[i] * sx[i];
            a[i * 2][7] = -dy[i] * sx[i];
            a[i * 2][8] = sx[i];
            a[i * 2 + 1][3] = dx[i];
            a[i * 2 + 1][4] = dy[i];
            a[i * 2 + 1][5] = 1;
            a[i * 2 + 1][6] = -dx[i] * sy[i];
            a[i * 2 + 1][7] = -dy[i] * sy[i];
            a[i * 2 + 1][8] = sy[i];
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            if (Math.abs(a[col][col]) < 1e-12) {
                continue;
            }
            for (int row = 0; row < 8; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col] / a[col][col];
                if (factor == 0) {
                    continue;
                }
                for (int k = col; k <= 8; k++) {
                    a[row][k] -= factor * a[col][k];
                }
            }
        }
        double[] m = new double[9];
        for (int i = 0; i < 8; i++) {
            m[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][8] / a[i][i];
        }
        m[8] = 1;
        return m;
    }

    private static int sample(int[] src, int w, int h, double u, double v) {
        if (u < 0 || v < 0 || u > w - 1 || v > h - 1) {
            return 0xFFFFFFFF;
        }
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        double fx = u - x0;
        double fy = v - y0;
        int c00 = src[y0 * w + x0];
        int c10 = src[y0 * w + x1];
        int c01 = src[y1 * w + x0];
        int c11 = src[y1 * w + x1];
        int r = bilerp((c00 >> 16) & 0xFF, (c10 >> 16) & 0xFF, (c01 >> 16) & 0xFF, (c11 >> 16) & 0xFF, fx, fy);
        int g = bilerp((c00 >> 8) & 0xFF, (c10 >> 8) & 0xFF, (c01 >> 8) & 0xFF, (c11 >> 8) & 0xFF, fx, fy);
        int b = bilerp(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy);
        int alpha = bilerp((c00 >>> 24), (c10 >>> 24), (c01 >>> 24), (c11 >>> 24), fx, fy);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int bilerp(int a, int b, int c, int d, double fx, double fy) {
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return (int) Math.round(top + (bottom - top) * fy);
    }

    // ---------------------------------------------------------------- shadow removal

    /**
     * Flattens uneven lighting by dividing the image by a heavily blurred copy of itself, which is
     * the illumination estimate. A shadowed page and its lit side then come out at the same
     * background level while ink stays dark, because the blur radius is far wider than a stroke.
     *
     * @param strength 0..100; 0 returns the input untouched.
     */
    public static int[] removeShadow(int[] argb, int w, int h, int strength) {
        if (strength <= 0) {
            return argb.clone();
        }
        int radius = Math.max(4, Math.min(w, h) / 12);
        int[] luma = luma(argb);
        int[] background = boxBlur(luma, w, h, radius);
        int[] out = new int[argb.length];
        double blend = Math.min(100, strength) / 100.0;
        for (int i = 0; i < argb.length; i++) {
            int bg = background[i];
            if (bg < 16) {
                // Near-black neighbourhood: dividing would amplify noise into confetti.
                out[i] = argb[i];
                continue;
            }
            int c = argb[i];
            int r = normalize((c >> 16) & 0xFF, bg, blend);
            int g = normalize((c >> 8) & 0xFF, bg, blend);
            int b = normalize(c & 0xFF, bg, blend);
            out[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    private static int normalize(int value, int background, double blend) {
        int corrected = (int) Math.round(value * 255.0 / background);
        if (corrected > 255) {
            corrected = 255;
        }
        int mixed = (int) Math.round(value + (corrected - value) * blend);
        return mixed < 0 ? 0 : Math.min(mixed, 255);
    }

    /**
     * Contrast stretch over the 2nd..98th percentile, for the black-and-white scan look.
     * Percentiles rather than min/max so one specular highlight cannot flatten the whole page.
     */
    public static int[] stretchContrast(int[] argb, int w, int h) {
        int[] luma = luma(argb);
        int[] hist = new int[256];
        for (int v : luma) {
            hist[v]++;
        }
        int total = luma.length;
        int lowCut = (int) (total * 0.02);
        int highCut = (int) (total * 0.98);
        int low = 0;
        int high = 255;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= lowCut) {
                low = i;
                break;
            }
        }
        acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= highCut) {
                high = i;
                break;
            }
        }
        if (high - low < 8) {
            return argb.clone();
        }
        int[] map = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = (int) Math.round((i - low) * 255.0 / (high - low));
            map[i] = v < 0 ? 0 : Math.min(v, 255);
        }
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            out[i] = (c & 0xFF000000) | (map[(c >> 16) & 0xFF] << 16)
                    | (map[(c >> 8) & 0xFF] << 8) | map[c & 0xFF];
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** Nearest-neighbour downscale; used to run detection cheaply on a large photo. */
    public static int[] downscale(int[] argb, int w, int h, int maxEdge) {
        int longer = Math.max(w, h);
        if (longer <= maxEdge) {
            return argb;
        }
        double k = maxEdge / (double) longer;
        int nw = Math.max(1, (int) (w * k));
        int nh = Math.max(1, (int) (h * k));
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, (int) (y / k));
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(w - 1, (int) (x / k));
                out[y * nw + x] = argb[sy * w + sx];
            }
        }
        return out;
    }

    /** Number of pixels per row produced by {@link #downscale}; keeps callers from re-deriving it. */
    public static int[] downscaledSize(int w, int h, int maxEdge) {
        int longer = Math.max(w, h);
        if (longer <= maxEdge) {
            return new int[]{w, h};
        }
        double k = maxEdge / (double) longer;
        return new int[]{Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k))};
    }

    /** Andrew's monotone chain over the region's pixels. Returns packed {x,y,x,y,...}. */
    static double[] convexHullXy(int[] region, int w) {
        int n = region.length;
        double[][] pts = new double[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = region[i] % w;
            pts[i][1] = region[i] / w;
        }
        Arrays.sort(pts, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<double[]> hull = new ArrayList<>();
        for (double[] p : pts) {
            while (hull.size() >= 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        int lower = hull.size() + 1;
        for (int i = n - 2; i >= 0; i--) {
            double[] p = pts[i];
            while (hull.size() >= lower && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        if (hull.isEmpty()) {
            return null;
        }
        hull.remove(hull.size() - 1);
        double[] out = new double[hull.size() * 2];
        for (int i = 0; i < hull.size(); i++) {
            out[i * 2] = hull.get(i)[0];
            out[i * 2 + 1] = hull.get(i)[1];
        }
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
