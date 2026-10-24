package cn.xianhua.pocketkit.media;

/**
 * 027 人脸融合的合成核心：对齐 + 软掩膜混合两张人脸。
 *
 * <p>What this actually is: two faces are aligned by their detected landmarks, then cross-faded
 * through a feathered elliptical mask so the result reads as one face carrying features of both.
 * That is a real, reproducible image operation — the same idea as the classic "average face"
 * composite.
 *
 * <p>What it deliberately is <b>not</b>: a generative face swap. It does not synthesise new facial
 * structure, so it cannot make one person look like another, and it cannot relight or change pose.
 * The UI says 非 AI 合成 and describes the result as a cross-fade, because calling a linear blend an
 * "AI face swap" would be the kind of overstatement this module is avoiding elsewhere.
 *
 * <p>Geometry and blending are pure arithmetic over pixel arrays, so the self-test covers the
 * alignment and the mask falloff without a device.
 */
public final class FaceBlendCore {

    /** A face as detected: bounding box plus the two eye centres, in image pixels. */
    public static final class FaceBox {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final int leftEyeX;
        public final int leftEyeY;
        public final int rightEyeX;
        public final int rightEyeY;
        public final boolean hasEyes;

        public FaceBox(int left, int top, int right, int bottom,
                       int leftEyeX, int leftEyeY, int rightEyeX, int rightEyeY, boolean hasEyes) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.leftEyeX = leftEyeX;
            this.leftEyeY = leftEyeY;
            this.rightEyeX = rightEyeX;
            this.rightEyeY = rightEyeY;
            this.hasEyes = hasEyes;
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public int centerX() {
            return (left + right) / 2;
        }

        public int centerY() {
            return (top + bottom) / 2;
        }
    }

    /**
     * Alignment of face B onto face A: a uniform scale about a pivot plus a translation, so B's eye
     * midpoint and eye separation land on A's.
     *
     * <p>The pivot is stored <em>with</em> the offset rather than passed in later. That is
     * deliberate: the offset is derived relative to this pivot, so mapping a point through any other
     * pivot would silently misplace the result — which is exactly the bug this shape prevents.
     *
     * <p>Scale comes from the eye separation when both faces report eyes, which is far more stable
     * than the bounding box: a detector's box grows with hair and chin, so box-based scaling
     * misjudges face size by 10–20%. The box is the fallback.
     */
    public static final class Align {
        public final double scale;
        private final double pivotX;
        private final double pivotY;
        private final double offsetX;
        private final double offsetY;

        Align(double scale, double pivotX, double pivotY, double offsetX, double offsetY) {
            this.scale = scale;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        /** Maps a point from B's frame into A's frame. */
        public double[] map(double x, double y) {
            return new double[]{
                    pivotX + (x - pivotX) * scale + offsetX,
                    pivotY + (y - pivotY) * scale + offsetY
            };
        }
    }

    private FaceBlendCore() {
    }

    public static Align align(FaceBox base, FaceBox overlay) {
        if (base.hasEyes && overlay.hasEyes) {
            double baseSeparation = distance(base.leftEyeX, base.leftEyeY, base.rightEyeX, base.rightEyeY);
            double overlaySeparation = distance(overlay.leftEyeX, overlay.leftEyeY,
                    overlay.rightEyeX, overlay.rightEyeY);
            // A degenerate separation means the detector returned the same point twice; fall back
            // rather than dividing by ~0 and blowing the image up.
            if (overlaySeparation > 2 && baseSeparation > 2) {
                double scale = clampScale(baseSeparation / overlaySeparation);
                double pivotX = (overlay.leftEyeX + overlay.rightEyeX) / 2.0;
                double pivotY = (overlay.leftEyeY + overlay.rightEyeY) / 2.0;
                double targetX = (base.leftEyeX + base.rightEyeX) / 2.0;
                double targetY = (base.leftEyeY + base.rightEyeY) / 2.0;
                // Offset is relative to the same pivot the mapping scales about.
                return new Align(scale, pivotX, pivotY, targetX - pivotX, targetY - pivotY);
            }
        }
        double baseSize = Math.max(1, Math.min(base.width(), base.height()));
        double overlaySize = Math.max(1, Math.min(overlay.width(), overlay.height()));
        double pivotX = overlay.centerX();
        double pivotY = overlay.centerY();
        return new Align(clampScale(baseSize / overlaySize), pivotX, pivotY,
                base.centerX() - pivotX, base.centerY() - pivotY);
    }

    /** Keeps the scale in a range where the result still resembles a face rather than confetti. */
    static double clampScale(double scale) {
        if (Double.isNaN(scale) || scale <= 0) {
            return 1.0;
        }
        return Math.max(0.2, Math.min(5.0, scale));
    }

    static double distance(int x1, int y1, int x2, int y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    /**
     * Blends {@code overlay} into {@code base} through a feathered elliptical mask centred on the
     * base face.
     *
     * <p>The mask falls off smoothly from its centre so there is no visible seam. Fully hard-edged
     * compositing is what makes a naive blend look like a pasted sticker, and the whole point of
     * feathering is that the transition is gradual.
     *
     * @param blend      0..100, how strongly the overlay shows at the mask centre
     * @param featherRun 0..100, how much of the mask radius is the soft transition band
     */
    public static int[] blend(int[] base, int baseW, int baseH, int[] overlay, int overlayW, int overlayH,
                              FaceBox baseFace, FaceBox overlayFace, int blend, double featherRun,
                              Cancel cancel) throws Cancel.Stop {
        int[] out = base.clone();
        Align mapping = align(baseFace, overlayFace);

        // The mask covers the face plus a margin, so hair and jaw get some of the overlay too.
        double radiusX = baseFace.width() * 0.62;
        double radiusY = baseFace.height() * 0.72;
        double centerX = baseFace.centerX();
        double centerY = baseFace.centerY();
        double strength = Math.min(100, Math.max(0, blend)) / 100.0;
        double featherInner = Math.max(0.05, 1.0 - Math.min(0.95, featherRun / 100.0));

        int left = (int) Math.floor(centerX - radiusX * 1.1);
        int right = (int) Math.ceil(centerX + radiusX * 1.1);
        int top = (int) Math.floor(centerY - radiusY * 1.1);
        int bottom = (int) Math.ceil(centerY + radiusY * 1.1);
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(baseW - 1, right);
        bottom = Math.min(baseH - 1, bottom);

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                double nx = (x - centerX) / radiusX;
                double ny = (y - centerY) / radiusY;
                double distance = Math.sqrt(nx * nx + ny * ny);
                if (distance >= 1.0) {
                    continue;
                }
                double mask;
                if (distance <= featherInner) {
                    mask = 1.0;
                } else {
                    double t = (distance - featherInner) / (1.0 - featherInner);
                    // Smoothstep: a cosine ramp avoids the visible ring a linear ramp produces.
                    mask = 0.5 * (1.0 + Math.cos(Math.PI * t));
                }
                mask *= strength;
                if (mask <= 0) {
                    continue;
                }
                double[] source = mapping.map(x, y);
                int sx = (int) Math.round(source[0]);
                int sy = (int) Math.round(source[1]);
                if (sx < 0 || sy < 0 || sx >= overlayW || sy >= overlayH) {
                    continue;
                }
                int target = y * baseW + x;
                int a = base[target];
                int b = overlay[sy * overlayW + sx];
                out[target] = mix(a, b, mask);
            }
        }
        cancel.check();
        return out;
    }

    /** Linear mix; {@code t=0} keeps the base pixel, {@code t=1} takes the overlay pixel. */
    public static int mix(int base, int overlay, double t) {
        int ar = (base >> 16) & 0xFF;
        int ag = (base >> 8) & 0xFF;
        int ab = base & 0xFF;
        int br = (overlay >> 16) & 0xFF;
        int bg = (overlay >> 8) & 0xFF;
        int bb = overlay & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int b = (int) Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * Picks the largest face when a photo contains several, because that is the subject the user
     * means, and says which one was used rather than guessing silently.
     */
    public static FaceBox largest(java.util.List<FaceBox> faces) {
        FaceBox best = null;
        long bestArea = -1;
        for (FaceBox face : faces) {
            long area = (long) face.width() * face.height();
            if (area > bestArea) {
                bestArea = area;
                best = face;
            }
        }
        return best;
    }

    /** How strongly to blend by default: an even cross-fade reads as neither person's photo. */
    public static int defaultBlend() {
        return 50;
    }
}
