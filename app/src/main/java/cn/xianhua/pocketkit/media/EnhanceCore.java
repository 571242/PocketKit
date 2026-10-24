package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;

/**
 * 非 AI 的图像增强数学：锐化、高质量放大、拉伸还原。
 *
 * <p>These are the three "enhancement" tools whose real function can be met by classical image
 * processing, so they are implemented as such and labelled 非 AI in the UI. They add <b>no</b>
 * invented detail:
 * <ul>
 *   <li>Sharpen is an unsharp mask — it raises local contrast, which makes an already-soft image
 *       read as crisper. It cannot recover information that is not in the file.</li>
 *   <li>Upscale is Lanczos-3 resampling — the best classical interpolation, and still an
 *       interpolation: it produces smooth edges, not new texture.</li>
 *   <li>Aspect repair undoes a 1-D stretch, which is exactly reversible geometry rather than a
 *       guess about content.</li>
 * </ul>
 *
 * <p>Keeping this separate from 026 (which does call an ML model) is deliberate: mixing a real
 * model with classical filters under one "AI" label is how a tool starts overstating itself.
 *
 * <p>Colour work stays in int arithmetic on ARGB; the filters are pure functions over pixel arrays
 * so the self-test can verify them without a device.
 */
public final class EnhanceCore {

    private EnhanceCore() {
    }

    /**
     * Unsharp mask.
     *
     * @param amount  0..300 (percent); 100 is a typical mild setting
     * @param radius  blur radius in pixels; larger radii create halo-like edges
     * @param threshold minimum local difference to act on, 0..255. Raising it keeps flat areas
     *                  (skin, sky) from turning noisy while still sharpening real edges.
     */
    public static int[] sharpen(int[] argb, int width, int height, int amount, int radius, int threshold) {
        if (amount <= 0) {
            return argb.clone();
        }
        int[] blurred = boxBlurArgb(argb, width, height, Math.max(1, radius));
        int[] out = new int[argb.length];
        double factor = amount / 100.0;
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int b = blurred[i];
            int r = sharpenChannel((c >> 16) & 0xFF, (b >> 16) & 0xFF, factor, threshold);
            int g = sharpenChannel((c >> 8) & 0xFF, (b >> 8) & 0xFF, factor, threshold);
            int bl = sharpenChannel(c & 0xFF, b & 0xFF, factor, threshold);
            out[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | bl;
        }
        return out;
    }

    private static int sharpenChannel(int original, int blurred, double factor, int threshold) {
        int diff = original - blurred;
        // Below the threshold the pixel is treated as flat, so texture noise is not amplified.
        if (Math.abs(diff) < threshold) {
            return original;
        }
        int value = (int) Math.round(original + diff * factor);
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Separable box blur over ARGB, alpha-preserving. Used as the unsharp mask's low-pass. */
    public static int[] boxBlurArgb(int[] argb, int width, int height, int radius) {
        int[] out = new int[argb.length];
        int[] channel = new int[argb.length];
        int window = radius * 2 + 1;
        for (int shift = 0; shift <= 24; shift += 8) {
            for (int i = 0; i < argb.length; i++) {
                channel[i] = (argb[i] >> shift) & 0xFF;
            }
            int[] horizontal = new int[argb.length];
            for (int y = 0; y < height; y++) {
                int row = y * width;
                long sum = 0;
                for (int x = -radius; x <= radius; x++) {
                    sum += channel[row + clamp(x, 0, width - 1)];
                }
                for (int x = 0; x < width; x++) {
                    horizontal[row + x] = (int) (sum / window);
                    sum -= channel[row + clamp(x - radius, 0, width - 1)];
                    sum += channel[row + clamp(x + radius + 1, 0, width - 1)];
                }
            }
            int[] vertical = new int[argb.length];
            for (int x = 0; x < width; x++) {
                long sum = 0;
                for (int y = -radius; y <= radius; y++) {
                    sum += horizontal[clamp(y, 0, height - 1) * width + x];
                }
                for (int y = 0; y < height; y++) {
                    vertical[y * width + x] = (int) (sum / window);
                    sum -= horizontal[clamp(y - radius, 0, height - 1) * width + x];
                    sum += horizontal[clamp(y + radius + 1, 0, height - 1) * width + x];
                }
            }
            for (int i = 0; i < argb.length; i++) {
                out[i] |= vertical[i] << shift;
            }
        }
        for (int i = 0; i < argb.length; i++) {
            out[i] |= argb[i] & 0xFF000000;
        }
        return out;
    }

    /**
     * Lanczos-3 resampling.
     *
     * <p>Chosen over the platform's bilinear scaler because bilinear softens edges noticeably on a
     * large upscale, and over bicubic because the window is only slightly wider while the ringing
     * is better controlled. The kernel is evaluated with a support of 3 source pixels per output
     * pixel, which is the standard Lanczos-3 window.
     *
     * <p>This cannot add detail: on a 2× upscale of a soft photo the result is a smooth 2× image.
     * The UI says so rather than implying an AI "enhancement".
     */
    public static int[] lanczos(int[] src, int srcW, int srcH, int dstW, int dstH) {
        if (srcW < 1 || srcH < 1 || dstW < 1 || dstH < 1) {
            throw new IllegalArgumentException("尺寸必须为正数");
        }
        if (srcW == dstW && srcH == dstH) {
            return src.clone();
        }
        double scaleX = dstW / (double) srcW;
        double scaleY = dstH / (double) srcH;
        // Precompute the horizontal pass into per-output-column weight sets; doing this per pixel
        // would recompute the same kernel thousands of times.
        double[][] weightsX = new double[dstW][];
        int[][] offsetsX = new int[dstW][];
        buildWeights(dstW, scaleX, weightsX, offsetsX);
        double[][] weightsY = new double[dstH][];
        int[][] offsetsY = new int[dstH][];
        buildWeights(dstH, scaleY, weightsY, offsetsY);

        int[] temp = new int[dstW * srcH];
        for (int y = 0; y < srcH; y++) {
            int row = y * srcW;
            for (int x = 0; x < dstW; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                double a = 0;
                int[] offsets = offsetsX[x];
                double[] weights = weightsX[x];
                for (int k = 0; k < offsets.length; k++) {
                    // Clamp to the source edges: a kernel near the border (and every kernel when
                    // shrinking) reaches outside the image, and indexing raw offsets would crash.
                    int c = src[row + clamp(offsets[k], 0, srcW - 1)];
                    double w = weights[k];
                    a += ((c >>> 24)) * w;
                    r += ((c >> 16) & 0xFF) * w;
                    g += ((c >> 8) & 0xFF) * w;
                    b += (c & 0xFF) * w;
                }
                temp[y * dstW + x] = (clampByte((int) Math.round(a)) << 24)
                        | (clampByte((int) Math.round(r)) << 16)
                        | (clampByte((int) Math.round(g)) << 8)
                        | clampByte((int) Math.round(b));
            }
        }

        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            int[] offsets = offsetsY[y];
            double[] weights = weightsY[y];
            for (int x = 0; x < dstW; x++) {
                double r = 0;
                double g = 0;
                double b = 0;
                double a = 0;
                for (int k = 0; k < offsets.length; k++) {
                    int c = temp[clamp(offsets[k], 0, srcH - 1) * dstW + x];
                    double w = weights[k];
                    a += ((c >>> 24)) * w;
                    r += ((c >> 16) & 0xFF) * w;
                    g += ((c >> 8) & 0xFF) * w;
                    b += (c & 0xFF) * w;
                }
                out[y * dstW + x] = (clampByte((int) Math.round(a)) << 24)
                        | (clampByte((int) Math.round(r)) << 16)
                        | (clampByte((int) Math.round(g)) << 8)
                        | clampByte((int) Math.round(b));
            }
        }
        return out;
    }

    /** For each output index, the source indices it samples and the normalised weights. */
    private static void buildWeights(int dstSize, double scale, double[][] weights, int[][] offsets) {
        double support = 3.0;
        // When shrinking, widen the kernel so the whole source contribution is covered; otherwise
        // downsampling aliases badly (a 4× shrink would ignore 3 of every 4 pixels).
        double filterScale = scale < 1.0 ? 1.0 / scale : 1.0;
        double radius = support * filterScale;
        for (int i = 0; i < dstSize; i++) {
            double center = (i + 0.5) / scale - 0.5;
            int left = (int) Math.ceil(center - radius);
            int right = (int) Math.floor(center + radius);
            int count = right - left + 1;
            double[] w = new double[count];
            int[] o = new int[count];
            double total = 0;
            for (int k = 0; k < count; k++) {
                int srcIndex = left + k;
                double distance = (center - srcIndex) / filterScale;
                double value = lanczosKernel(distance);
                w[k] = value;
                total += value;
                o[k] = srcIndex;
            }
            if (total == 0) {
                // Degenerate case (output single pixel): fall back to nearest so the result is the
                // pixel itself rather than black.
                w[0] = 1;
                total = 1;
            }
            for (int k = 0; k < count; k++) {
                w[k] /= total;
            }
            weights[i] = w;
            offsets[i] = o;
        }
    }

    /** Lanczos-3 kernel: sinc(x)·sinc(x/3) over |x| < 3, zero outside. */
    public static double lanczosKernel(double x) {
        double ax = Math.abs(x);
        if (ax < 1e-9) {
            return 1.0;
        }
        if (ax >= 3.0) {
            return 0.0;
        }
        double pix = Math.PI * x;
        return (Math.sin(pix) / pix) * (Math.sin(pix / 3.0) / (pix / 3.0));
    }

    /**
     * Restores a stretched image to its correct proportions.
     *
     * <p>If a picture was scaled by {@code factor} along one axis (the usual failure: a wide-screen
     * aspect forced onto a 4:3 photo), resampling by {@code 1/factor} on that axis returns it to the
     * original geometry. This is honest arithmetic, not reconstruction: it only works when the
     * distortion was a linear stretch, and the UI says so.
     */
    public static int[] repairStretch(int[] src, int srcW, int srcH, boolean horizontal, double factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("拉伸倍数必须大于 0");
        }
        if (Math.abs(factor - 1.0) < 1e-6) {
            return src.clone();
        }
        int dstW = horizontal ? Math.max(1, (int) Math.round(srcW / factor)) : srcW;
        int dstH = horizontal ? srcH : Math.max(1, (int) Math.round(srcH / factor));
        return lanczos(src, srcW, srcH, dstW, dstH);
    }

    /**
     * Estimates a horizontal stretch factor by comparing the image's current ratio with a ratio the
     * user believes is correct. Used to pre-fill the field, never to act silently.
     */
    public static double estimateFactor(int width, int height, double targetAspect) {
        if (width < 1 || height < 1 || targetAspect <= 0) {
            throw new IllegalArgumentException("参数无效");
        }
        double current = width / (double) height;
        return current / targetAspect;
    }

    /** Applies the same value to every channel of a pixel; used by the sharpen preview label. */
    static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Packs ARGB from a pixel array so callers do not each re-implement the shift maths. */
    public static Bitmap toBitmap(int[] argb, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
