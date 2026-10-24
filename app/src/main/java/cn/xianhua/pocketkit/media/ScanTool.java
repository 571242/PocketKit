package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A03 文档扫描：自动找边、透视校正、去阴影。
 *
 * <p>All the imaging lives in {@link ScanCore}, which is plain {@code int[]} arithmetic and is
 * covered by the self-test. This class only handles the flow: decode, detect on a downscaled copy,
 * warp, clean, save. Detection runs at a reduced size because the Hough/connected-component work
 * scales with pixel count, while the warp stays at full resolution so no detail is lost.
 */
public final class ScanTool implements Tool {

    /** Detection is done at this long edge; the quad is scaled back up for the warp. */
    private static final int DETECT_EDGE = 1000;

    private final Host host;

    public ScanTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("把拍歪、有阴影的纸面照片拉正。全过程在本机完成，图片不上传。");

        final Uri[] source = {null};
        host.button("选择照片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「开始处理」。");
        }));

        final Spinner edgeMode = host.select("边缘处理",
                "自动找边 + 拉正", "自动找边 + 拉正 + 去阴影", "不找边，只去阴影",
                "手动指定四角 + 拉正", "不找边，原样输出");
        final Spinner finish = host.select("画面风格", "保持彩色", "增强对比（浅底深字）", "灰度", "黑白（适合文字）");
        final Spinner format = host.select("输出格式", "JPEG", "PNG");

        host.note("手动四角：按「左上,右上,右下,左下」顺序填写百分比坐标，每角 x,y 用空格分隔。"
                + "例如 10,8 90,12 88,92 12,90 表示四角分别落在宽 10% 高 8% 等位置。"
                + "自动找边失败时用它兜底，比反复拍更省事。");
        final EditText manualCorners = host.field("手动四角（百分比，模式选「手动」时使用）",
                "例如 10,8 90,12 88,92 12,90");

        host.button("开始处理", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张照片。");
                return;
            }
            final int mode = edgeMode.getSelectedItemPosition();
            final int style = finish.getSelectedItemPosition();
            final ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());
            final double[][] manual = mode == 3
                    ? parseManualCorners(manualCorners.getText().toString()) : null;
            if (mode == 3 && manual == null) {
                host.show("未完成：手动模式的四角格式不对。请按「x,y x,y x,y x,y」顺序填写四个角的百分比"
                        + "（左上、右上、右下、左下），例如 10,8 90,12 88,92 12,90。数值范围 0~100。");
                return;
            }
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                Bitmap work = null;
                Bitmap scaled = null;
                try {
                    Bitmap decoded = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    work = decoded;
                    int w = decoded.getWidth();
                    int h = decoded.getHeight();
                    int[] pixels = new int[w * h];
                    decoded.getPixels(pixels, 0, w, 0, 0, w, h);
                    cancel.check();

                    ScanCore.Quad quad = ScanCore.Quad.whole(w, h);
                    boolean detected = false;
                    String edgeReport;
                    if (mode == 0 || mode == 1) {
                        int[] smallSize = ScanCore.downscaledSize(w, h, DETECT_EDGE);
                        int[] small = ScanCore.downscale(pixels, w, h, DETECT_EDGE);
                        ScanCore.Detected found = ScanCore.detectPage(small, smallSize[0], smallSize[1]);
                        if (found.found) {
                            // The detection ran on a smaller image; the quad must be scaled back before
                            // it is used to sample the full-resolution pixels.
                            double k = w / (double) smallSize[0];
                            quad = found.quad.scaled(k).clampedTo(w, h);
                            detected = true;
                            edgeReport = "已找到纸张边缘（置信度 " + Math.round(found.confidence * 100) + "%）。";
                        } else {
                            edgeReport = "没有找到清晰的纸张边缘，已按整幅照片处理。"
                                    + "把纸张放在与背景对比明显的桌面上、四角都入画并留一点背景，通常能识别到。";
                        }
                        cancel.check();
                    } else if (mode == 3) {
                        quad = quadFromPercent(manual, w, h);
                        detected = true;
                        edgeReport = "按手动指定的四角拉正：" + quad;
                        cancel.check();
                    } else {
                        edgeReport = "按设置跳过了找边。";
                    }

                    int outW = w;
                    int outH = h;
                    int[] result;
                    if (detected) {
                        outW = Math.max(16, (int) Math.round(quad.width()));
                        outH = Math.max(16, (int) Math.round(quad.height()));
                        // Cap the output: a page photographed at an angle can ask for a very large
                        // rectified image, and the cap keeps the allocation bounded.
                        long outPixels = (long) outW * outH;
                        if (outPixels > ImageIo.DEFAULT_MAX_PIXELS) {
                            double k = Math.sqrt(ImageIo.DEFAULT_MAX_PIXELS / (double) outPixels);
                            outW = Math.max(16, (int) (outW * k));
                            outH = Math.max(16, (int) (outH * k));
                        }
                        result = ScanCore.warp(pixels, w, h, quad, outW, outH);
                    } else {
                        result = pixels;
                    }
                    cancel.check();

                    if (mode == 1 || mode == 2) {
                        result = ScanCore.removeShadow(result, outW, outH, 70);
                        cancel.check();
                    }
                    if (style == 1) {
                        result = ScanCore.stretchContrast(result, outW, outH);
                    } else if (style == 2 || style == 3) {
                        result = ScanCore.stretchContrast(result, outW, outH);
                        result = toGrayOrBilevel(result, style == 3);
                    }
                    cancel.check();

                    Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    out.setPixels(result, 0, outW, 0, 0, outW, outH);
                    scaled = out;
                    byte[] data = ImageIo.encodeFlattened(out, outFormat, 92);
                    cancel.check();

                    prepared = MediaIo.scratch(host.activity(), "." + outFormat.ext);
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(data);
                    }

                    String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "scan." + outFormat.ext);
                    String summary = "原图 " + w + "×" + h + " → 输出 " + outW + "×" + outH
                            + "，" + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length) + "\n"
                            + edgeReport + "\n"
                            + "画面风格：" + finish.getSelectedItem() + "；格式：" + ImageIo.formatName(outFormat);

                    final File ready = prepared;
                    final String finalName = name;
                    final String finalSummary = summary;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置（原照片不会被修改）。");
                        host.createDocument(finalName, ImageCompressTool.mimeOf(outFormat), target -> {
                            host.background(() -> {
                                try {
                                    MediaIo.publish(host.activity(), target, ready);
                                    host.post(() -> host.show(finalSummary + "\n\n已保存：" + finalName));
                                } finally {
                                    if (ready.exists()) {
                                        ready.delete();
                                    }
                                }
                            });
                        });
                    });
                } catch (Cancel.Stop stopped) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.show("已取消，未写出文件。");
                    });
                } catch (Exception ex) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.fail(ex);
                    });
                } finally {
                    if (work != null && !work.isRecycled()) {
                        work.recycle();
                    }
                    if (scaled != null && !scaled.isRecycled() && scaled != work) {
                        scaled.recycle();
                    }
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });

        host.note("找边基于亮度分割与凸包，适合深色背景上的纸张或浅色背景上的深色纸张；"
                + "纸张与背景亮度接近时可能找不到边，界面会如实说明并按整幅输出。");
    }

    /**
     * Parses "x,y x,y x,y x,y" as percentages, in the order top-left, top-right, bottom-right,
     * bottom-left. Returns null when the text is not exactly four valid pairs, so the caller can
     * explain the format rather than silently using a wrong crop.
     */
    public static double[][] parseManualCorners(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] pairs = trimmed.split("[;\\s]+");
        if (pairs.length != 4) {
            return null;
        }
        double[][] out = new double[4][2];
        for (int i = 0; i < 4; i++) {
            String[] xy = pairs[i].split("[,，]");
            if (xy.length != 2) {
                return null;
            }
            try {
                double x = Double.parseDouble(xy[0].trim());
                double y = Double.parseDouble(xy[1].trim());
                if (x < 0 || x > 100 || y < 0 || y > 100) {
                    return null;
                }
                out[i][0] = x;
                out[i][1] = y;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** Turns percentage corners into image pixels, then rejects a degenerate quad. */
    public static ScanCore.Quad quadFromPercent(double[][] corners, int width, int height) {
        double[] xs = new double[4];
        double[] ys = new double[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = corners[i][0] / 100.0 * width;
            ys[i] = corners[i][1] / 100.0 * height;
        }
        ScanCore.Quad quad = new ScanCore.Quad(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3]);
        if (!quad.isSane(width, height, 0.01)) {
            throw new IllegalArgumentException("这四个角构成的区域是交叉或太小的四边形，无法拉正。"
                    + "请按顺时针（左上、右上、右下、左下）顺序填写，并让它们围出纸张的范围。");
        }
        return quad;
    }

    /**
     * Converts to gray, or to pure black-and-white by thresholding at Otsu's level.
     * A fixed threshold would fail on a dim photo, so the level comes from the image itself.
     */
    public static int[] toGrayOrBilevel(int[] argb, boolean bilevel) {
        int[] luma = ScanCore.luma(argb);
        int threshold = bilevel ? ScanCore.otsu(luma) : 0;
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int level = luma[i];
            if (bilevel) {
                level = level > threshold ? 255 : 0;
            }
            out[i] = 0xFF000000 | (level << 16) | (level << 8) | level;
        }
        return out;
    }
}
