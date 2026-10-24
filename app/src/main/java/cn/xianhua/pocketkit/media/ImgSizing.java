package cn.xianhua.pocketkit.media;

/**
 * Image sizing arithmetic and the target-file-size search used by 图片压缩.
 *
 * <p>Pure arithmetic on purpose: the caller supplies the actual encoder, so the search policy can
 * be exercised without a device or a Bitmap.
 */
public final class ImgSizing {

    /** Output container. Quality only applies to the lossy one. */
    public enum Format {
        JPEG("jpg", true), PNG("png", false), WEBP("webp", true);

        public final String ext;
        public final boolean lossy;

        Format(String ext, boolean lossy) {
            this.ext = ext;
            this.lossy = lossy;
        }

        public static Format of(String name) {
            if (name == null) {
                return JPEG;
            }
            String n = name.toUpperCase(java.util.Locale.US);
            if (n.contains("PNG")) {
                return PNG;
            }
            if (n.contains("WEBP")) {
                return WEBP;
            }
            return JPEG;
        }
    }

    public static final int[] PRESET_LONG_EDGES = {640, 1080, 1600, 2048, 4096};

    /** Smallest quality worth trying; below this the artefacts are worse than the saving. */
    public static final int MIN_QUALITY = 20;
    public static final int MAX_QUALITY = 100;

    private ImgSizing() {
    }

    /** Scales so that the longer edge is at most {@code longEdge}; never enlarges. */
    public static int[] fitLongEdge(int width, int height, int longEdge) {
        if (width <= 0 || height <= 0 || longEdge <= 0) {
            return new int[]{Math.max(1, width), Math.max(1, height)};
        }
        long longer = Math.max(width, height);
        if (longer <= longEdge) {
            return new int[]{width, height};
        }
        double k = longEdge / (double) longer;
        return new int[]{Math.max(1, (int) Math.round(width * k)), Math.max(1, (int) Math.round(height * k))};
    }

    /** Scales so that total pixels is at most {@code maxPixels}; never enlarges. */
    public static int[] fitPixels(int width, int height, long maxPixels) {
        if (width <= 0 || height <= 0 || maxPixels <= 0) {
            return new int[]{Math.max(1, width), Math.max(1, height)};
        }
        long pixels = (long) width * height;
        if (pixels <= maxPixels) {
            return new int[]{width, height};
        }
        double k = Math.sqrt(maxPixels / (double) pixels);
        int w = Math.max(1, (int) Math.round(width * k));
        int h = Math.max(1, (int) Math.round(height * k));
        // Rounding each edge independently can land just above the cap; step down until it fits so
        // the result never exceeds what the user asked for.
        while ((long) w * h > maxPixels && (w > 1 || h > 1)) {
            if (w >= h && w > 1) {
                w--;
            } else if (h > 1) {
                h--;
            } else {
                break;
            }
        }
        return new int[]{w, h};
    }

    /** An encoder that produces bytes for a given quality; implemented over Bitmap on device. */
    public interface Encoder {
        byte[] encode(int quality) throws Exception;
    }

    public static final class Attempt {
        public final int quality;
        public final int bytes;

        Attempt(int quality, int bytes) {
            this.quality = quality;
            this.bytes = bytes;
        }
    }

    public static final class Result {
        public final byte[] data;
        public final int quality;
        /** True when even the lowest quality still exceeds the target. */
        public final boolean targetMissed;

        Result(byte[] data, int quality, boolean targetMissed) {
            this.data = data;
            this.quality = quality;
            this.targetMissed = targetMissed;
        }
    }

    /**
     * Binary-searches the highest quality whose output fits {@code targetBytes}.
     *
     * <p>Each probe is a real encode, so the size is measured, never modelled — a modelled guess
     * would claim a file size the encoder does not actually produce. When even {@link #MIN_QUALITY}
     * overshoots, the best (smallest) attempt is returned with {@code targetMissed}, so the caller
     * can say so instead of silently shipping a file over the limit.
     */
    public static Result searchQuality(Encoder encoder, long targetBytes) throws Exception {
        if (targetBytes <= 0) {
            throw new IllegalArgumentException("目标大小必须大于 0");
        }
        byte[] best = encoder.encode(MIN_QUALITY);
        if (best.length > targetBytes) {
            return new Result(best, MIN_QUALITY, true);
        }
        int low = MIN_QUALITY;
        int high = MAX_QUALITY;
        byte[] bestFit = best;
        int bestQuality = MIN_QUALITY;
        // log2(100-20) ~ 7 probes; enough for real files and cheap compared to the encode itself.
        while (low <= high) {
            int mid = (low + high) >>> 1;
            byte[] probe = encoder.encode(mid);
            if (probe.length <= targetBytes) {
                bestFit = probe;
                bestQuality = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return new Result(bestFit, bestQuality, false);
    }

    /** Formats a small help line about what a preset will roughly produce. */
    public static String describe(int width, int height) {
        return width + " × " + height + " 像素";
    }

    /** A short label for the size chip list; values are bytes. */
    public static long kilobytes(int kb) {
        return kb * 1024L;
    }
}
