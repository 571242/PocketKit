package cn.xianhua.pocketkit.core.automation;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Brightness-tolerant normalized cross-correlation matcher; no native/OpenCV dependency. */
public final class TemplateMatcher {
    private static final int MAX_CANDIDATES = 8;

    private TemplateMatcher() { }

    public static Match find(Bitmap screen, Bitmap template, Rect region,
                             double threshold, long timeoutMs) {
        if (screen == null || template == null || screen.isRecycled() || template.isRecycled()) {
            throw new IllegalArgumentException("截图和模板必须是有效 Bitmap。");
        }
        if (Double.isNaN(threshold) || threshold < 0d || threshold > 1d) {
            throw new IllegalArgumentException("匹配阈值必须在 0 到 1 之间。");
        }
        if (timeoutMs <= 0) throw new IllegalArgumentException("匹配超时必须大于 0 毫秒。");
        Rect area = region == null ? new Rect(0, 0, screen.getWidth(), screen.getHeight()) : new Rect(region);
        if (!area.intersect(0, 0, screen.getWidth(), screen.getHeight()) || area.isEmpty()) {
            throw new IllegalArgumentException("ROI 不在截图范围内。");
        }
        int tw = template.getWidth(), th = template.getHeight();
        if (tw <= 0 || th <= 0 || tw > area.width() || th > area.height()) return Match.notFound();

        int[] screenPixels = new int[screen.getWidth() * screen.getHeight()];
        int[] templatePixels = new int[tw * th];
        screen.getPixels(screenPixels, 0, screen.getWidth(), 0, 0, screen.getWidth(), screen.getHeight());
        template.getPixels(templatePixels, 0, tw, 0, 0, tw, th);
        int[] screenGray = gray(screenPixels);
        int[] templateGray = gray(templatePixels);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int scanStride = Math.max(1, Math.min(4, Math.max(2, Math.max(tw, th) / 64)));
        int sampleStride = Math.max(tw, th) < 24 ? 1 : 4;
        List<Candidate> candidates = new ArrayList<>(MAX_CANDIDATES);
        int checks = 0;
        for (int y = area.top; y <= area.bottom - th; y += scanStride) {
            for (int x = area.left; x <= area.right - tw; x += scanStride) {
                if ((checks++ & 1023) == 0) checkBudget(deadline);
                addCandidate(candidates, new Candidate(x, y,
                        score(screenGray, screen.getWidth(), templateGray, tw, th, x, y, sampleStride)));
            }
        }
        int edgeX = area.right - tw, edgeY = area.bottom - th;
        checkBudget(deadline);
        addCandidate(candidates, new Candidate(edgeX, edgeY,
                score(screenGray, screen.getWidth(), templateGray, tw, th, edgeX, edgeY, sampleStride)));

        Candidate best = null;
        for (Candidate coarse : candidates) {
            int fromX = Math.max(area.left, coarse.x - scanStride + 1);
            int toX = Math.min(area.right - tw, coarse.x + scanStride - 1);
            int fromY = Math.max(area.top, coarse.y - scanStride + 1);
            int toY = Math.min(area.bottom - th, coarse.y + scanStride - 1);
            for (int y = fromY; y <= toY; y++) {
                for (int x = fromX; x <= toX; x++) {
                    if ((checks++ & 1023) == 0) checkBudget(deadline);
                    double score = score(screenGray, screen.getWidth(), templateGray, tw, th, x, y, 1);
                    if (best == null || score > best.score) best = new Candidate(x, y, score);
                }
            }
        }
        if (best == null || best.score < threshold) return new Match(false, 0, 0, 0, 0, "", best == null ? 0d : best.score);
        return new Match(true, best.x, best.y, best.x + tw, best.y + th, "", best.score);
    }

    public static Match find(Bitmap screen, Bitmap template, double threshold, long timeoutMs) {
        return find(screen, template, null, threshold, timeoutMs);
    }

    public static Match match(Bitmap screen, Bitmap template, Rect region,
                              double threshold, long timeoutMs) {
        return find(screen, template, region, threshold, timeoutMs);
    }

    private static int[] gray(int[] pixels) {
        int[] result = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            result[i] = (ColorRed(color) * 299 + ColorGreen(color) * 587 + ColorBlue(color) * 114) / 1000;
        }
        return result;
    }

    private static double score(int[] source, int sourceWidth, int[] template, int tw, int th,
                               int left, int top, int step) {
        double sourceMean = 0d, templateMean = 0d;
        long count = 0;
        for (int y = 0; y < th; y += step) {
            int sourceRow = (top + y) * sourceWidth + left;
            int templateRow = y * tw;
            for (int x = 0; x < tw; x += step) {
                sourceMean += source[sourceRow + x];
                templateMean += template[templateRow + x];
                count++;
            }
        }
        sourceMean /= count;
        templateMean /= count;
        double covariance = 0d, sourceVar = 0d, templateVar = 0d, absolute = 0d;
        for (int y = 0; y < th; y += step) {
            int sourceRow = (top + y) * sourceWidth + left;
            int templateRow = y * tw;
            for (int x = 0; x < tw; x += step) {
                double a = source[sourceRow + x], b = template[templateRow + x];
                covariance += (a - sourceMean) * (b - templateMean);
                sourceVar += (a - sourceMean) * (a - sourceMean);
                templateVar += (b - templateMean) * (b - templateMean);
                absolute += Math.abs(a - b);
            }
        }
        if (sourceVar < 1e-9 || templateVar < 1e-9) return clamp01(1d - absolute / (count * 255d));
        return clamp01(covariance / Math.sqrt(sourceVar * templateVar));
    }

    private static void checkBudget(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("图片匹配已取消。");
        if (System.nanoTime() > deadline) throw new IllegalStateException("图片匹配超时。");
    }

    private static void addCandidate(List<Candidate> list, Candidate candidate) {
        int at = 0;
        while (at < list.size() && list.get(at).score >= candidate.score) at++;
        if (at < MAX_CANDIDATES) list.add(at, candidate);
        if (list.size() > MAX_CANDIDATES) list.remove(list.size() - 1);
    }

    private static double clamp01(double value) { return Math.max(0d, Math.min(1d, value)); }
    private static int ColorRed(int color) { return (color >>> 16) & 255; }
    private static int ColorGreen(int color) { return (color >>> 8) & 255; }
    private static int ColorBlue(int color) { return color & 255; }

    private static final class Candidate {
        final int x, y; final double score;
        Candidate(int x, int y, double score) { this.x = x; this.y = y; this.score = score; }
    }
}
