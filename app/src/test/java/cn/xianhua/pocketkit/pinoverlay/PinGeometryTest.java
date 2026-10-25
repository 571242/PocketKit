package cn.xianhua.pocketkit.pinoverlay;

/**
 * Host-side checks for the pin geometry: no Android runtime, no device claims. Runs under a plain
 * JDK 17 with {@code java -ea} or without it, because every failure throws a real AssertionError.
 */
public final class PinGeometryTest {
    private static int checks;

    public static void main(String[] args) {
        sampling();
        fitting();
        scaling();
        placement();
        visibility();
        collapsing();
        alphaCycle();
        exif();
        decodeSide();
        rejections();
        System.out.println("PinGeometryTest OK: " + checks + " checks");
    }

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static void same(int[] actual, int... expected) {
        check(actual.length == expected.length, "size mismatch");
        for (int i = 0; i < expected.length; i++) {
            check(actual[i] == expected[i], "expected " + expected[i] + " but was " + actual[i] + " at " + i);
        }
    }

    private static void sampling() {
        check(PinGeometry.sampleSize(100, 80, 2048) == 1, "small image is not subsampled");
        check(PinGeometry.sampleSize(4096, 4096, 2048) == 2, "4096 needs /2");
        check(PinGeometry.sampleSize(4097, 100, 2048) == 2, "integer division at 2048 is still inside the bound");
        check(PinGeometry.sampleSize(4098, 100, 2048) == 4, "just past twice the bound drops to /4");
        check(PinGeometry.sampleSize(2048, 2048, 2048) == 1, "exact bound stays at 1");
        // The long side is what matters: a panorama must not keep a 12000 px width.
        check(PinGeometry.sampleSize(12000, 600, 2048) == 8, "panorama is subsampled by its long side");
        // The sample must always be a power of two, because BitmapFactory rounds it down otherwise.
        for (int side : new int[]{1, 2, 3, 1023, 1024, 1025, 4096, 100000}) {
            for (int limit : new int[]{720, 1024, 2048}) {
                int sample = PinGeometry.sampleSize(side, side / 2 + 1, limit);
                check(Integer.bitCount(sample) == 1 && sample >= 1, "sample is a power of two");
                check(side / sample <= limit || sample == 1, "sample respects the limit or cannot go higher");
            }
        }
    }

    private static void fitting() {
        same(PinGeometry.fit(1080, 2400, 1080, 2000), 900, 2000);
        same(PinGeometry.fit(2400, 1080, 1080, 2000), 1080, 486);
        same(PinGeometry.fit(200, 100, 1080, 2000), 200, 100);
        same(PinGeometry.fit(1080, 1080, 1080, 2000), 1080, 1080);
        // A square box still yields a square, and a tall box never exceeds either side.
        int[] tall = PinGeometry.fit(1000, 5000, 1080, 2000);
        check(tall[0] <= 1080 && tall[1] <= 2000, "fit stays inside the box");
        check(Math.abs(tall[0] / (double) tall[1] - 0.2) < 0.01, "fit keeps the aspect ratio");
        check(PinGeometry.fit(1, 100000, 1080, 2000)[0] == 1, "a 1 px wide image stays at least 1 px wide");
    }

    private static void scaling() {
        same(PinGeometry.scaled(400, 200, 2f, 96, 1080, 2000), 800, 400);
        same(PinGeometry.scaled(400, 200, 0.01f, 96, 1080, 2000), 96, 48);
        same(PinGeometry.scaled(400, 200, 100f, 96, 1080, 2000), 1080, 540);
        // Tall image: the height bound, not the width bound, decides the ceiling.
        int[] tall = PinGeometry.scaled(200, 4000, 100f, 96, 1080, 2000);
        check(tall[1] <= 2000, "tall pin is capped by the screen height");
        check(Math.abs(tall[0] / (double) tall[1] - 200 / 4000.0) < 0.01, "tall pin keeps its ratio");
        // Ceiling below the floor must not produce a negative width.
        int[] squeezed = PinGeometry.scaled(200, 4000, 1f, 96, 1080, 100);
        check(squeezed[0] >= 1 && squeezed[1] >= 1, "ceiling below floor still yields a usable size");
        // Repeated small steps never exceed the bounds, and a shrinking then growing pair returns.
        int width = 600, height = 300;
        for (int i = 0; i < 40; i++) {
            int[] next = PinGeometry.scaled(width, height, 1.22f, 96, 1080, 2000);
            width = next[0]; height = next[1];
            check(width <= 1080 && height <= 2000, "growth stays on screen");
        }
        // Integer rounding lets the ratio drift by a pixel per step, so saturation is a range.
        check(width == 1080 && height >= 530 && height <= 550, "growth saturates at the width bound");
        int[] back = PinGeometry.scaled(width, height, 96f / 1080f, 96, 1080, 2000);
        same(back, 96, 48);
    }

    private static void placement() {
        same(PinGeometry.place(400, 200, 1080, 2400), 340, 1100);
        // Larger than the screen: a negative offset would hide the top-left corner, so it is 0.
        same(PinGeometry.place(1200, 2600, 1080, 2400), 0, 0);
        same(PinGeometry.place(1080, 2400, 1080, 2400), 0, 0);
    }

    private static void visibility() {
        same(PinGeometry.onScreen(-500, -500, 400, 200, 1080, 2400, 64), 0, 0);
        same(PinGeometry.onScreen(9999, 9999, 400, 200, 1080, 2400, 64), 680, 2200);
        same(PinGeometry.onScreen(123, 456, 400, 200, 1080, 2400, 64), 123, 456);
        // Oversized window keeps the required px of itself reachable on every side.
        same(PinGeometry.onScreen(-9999, -9999, 2000, 3000, 1080, 2400, 64), -1936, -2936);
        same(PinGeometry.onScreen(9999, 9999, 2000, 3000, 1080, 2400, 64), 1016, 2336);
        // Exactly screen sized: the only legal offset is 0.
        same(PinGeometry.onScreen(50, 50, 1080, 2400, 1080, 2400, 64), 0, 0);
        // A visible-sliver request larger than the window is capped at the window size.
        same(PinGeometry.onScreen(-9999, 0, 400, 200, 1080, 2400, 5000), 0, 0);
    }

    private static void collapsing() {
        same(PinGeometry.collapse(100, 200, 400, 200, 64), 268, 268);
        // Block stays on screen when the pin was partly off it; the caller re-clamps, which is idempotent.
        int[] block = PinGeometry.collapse(-300, -100, 400, 200, 64);
        int[] fixed = PinGeometry.onScreen(block[0], block[1], 64, 64, 1080, 2400, 64);
        same(fixed, 0, 0);
        int[] again = PinGeometry.onScreen(fixed[0], fixed[1], 64, 64, 1080, 2400, 64);
        same(again, 0, 0);
    }

    private static void alphaCycle() {
        float alpha = 1f;
        float[] expected = {.75f, .5f, .3f, 1f};
        for (float value : expected) {
            alpha = PinGeometry.nextAlpha(alpha);
            check(Math.abs(alpha - value) < .001f, "alpha step order");
        }
        check(Math.abs(PinGeometry.nextAlpha(.1f) - 1f) < .001f, "unknown alpha falls back to 100%");
        check(PinGeometry.percent(1f) == 100 && PinGeometry.percent(.75f) == 75 && PinGeometry.percent(.3f) == 30, "percent labels");
        check(PinGeometry.percent(-1f) == 0 && PinGeometry.percent(2f) == 100, "percent clamps");
    }

    private static void exif() {
        check(PinGeometry.exifDegrees(1) == 0, "orientation 1");
        check(PinGeometry.exifDegrees(3) == 180, "orientation 3");
        check(PinGeometry.exifDegrees(6) == 90, "orientation 6 is the common sideways photo");
        check(PinGeometry.exifDegrees(8) == 270, "orientation 8");
        check(PinGeometry.exifDegrees(5) == 90 && PinGeometry.exifDegrees(7) == 270, "mirrored orientations still rotate");
        check(PinGeometry.exifDegrees(0) == 0 && PinGeometry.exifDegrees(99) == 0, "unknown orientation is left alone");
        check(PinGeometry.exifMirrors(2) && PinGeometry.exifMirrors(4) && PinGeometry.exifMirrors(5) && PinGeometry.exifMirrors(7), "mirrored set");
        check(!PinGeometry.exifMirrors(1) && !PinGeometry.exifMirrors(3) && !PinGeometry.exifMirrors(6) && !PinGeometry.exifMirrors(8), "plain set");
    }

    private static void decodeSide() {
        check(PinGeometry.decodeSide(1080, 2400) == 2048, "phone long side is capped at 2048");
        check(PinGeometry.decodeSide(1080, 1920) == 1920, "under the cap keeps the screen size");
        check(PinGeometry.decodeSide(320, 480) == 720, "a tiny screen still decodes at a readable size");
        check(PinGeometry.decodeSide(0, 0) == 1024, "unknown screen falls back");
        check(PinGeometry.decodeSide(4000, 4000) == 2048, "tablet cannot push memory past 2048");
    }

    private static void rejections() {
        rejects(() -> PinGeometry.sampleSize(0, 10, 100), "zero width");
        rejects(() -> PinGeometry.fit(0, 10, 100, 100), "zero source");
        rejects(() -> PinGeometry.fit(10, 10, 0, 100), "zero box");
        rejects(() -> PinGeometry.scaled(10, 10, 0f, 1, 100, 100), "zero factor");
        rejects(() -> PinGeometry.scaled(10, 10, Float.NaN, 1, 100, 100), "NaN factor");
        rejects(() -> PinGeometry.scaled(10, 10, Float.POSITIVE_INFINITY, 1, 100, 100), "infinite factor");
        rejects(() -> PinGeometry.scaled(10, 10, 1f, 1, 0, 100), "zero screen width");
        rejects(() -> PinGeometry.place(10, 10, 0, 100), "zero screen");
        rejects(() -> PinGeometry.onScreen(0, 0, 10, 10, 0, 100, 1), "zero screen in clamp");
        rejects(() -> PinGeometry.collapse(0, 0, 10, 10, 0), "zero block");
    }

    private interface Work { void run(); }

    private static void rejects(Work work, String label) {
        try { work.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("expected rejection: " + label);
    }
}
