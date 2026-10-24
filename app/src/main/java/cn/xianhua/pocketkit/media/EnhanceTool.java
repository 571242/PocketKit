package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 029 图片清晰度增强、030 拉伸图片修复、032 图片分辨率提升。
 *
 * <p>All three are classical image processing, and the screen says <b>非 AI</b> in the first line so
 * nobody mistakes them for a neural model. That distinction is the point: they genuinely do their
 * job (edge contrast, geometry, resampling), and they genuinely cannot invent detail.
 *
 * <p>See {@link EnhanceCore} for the maths and why each method was chosen.
 */
public final class EnhanceTool implements Tool {

    private final Host host;
    private final String toolId;

    public EnhanceTool(Host host, String toolId) {
        this.host = host;
        this.toolId = toolId;
    }

    @Override public void build() {
        switch (toolId) {
            case "030": buildStretchRepair(); break;
            case "032": buildUpscale(); break;
            default: buildSharpen(); break;
        }
    }

    // ------------------------------------------------------------ shared

    /** Source picker plus the run button every mode shares. */
    private void withSource(String hint, java.util.function.Consumer<Uri> run) {
        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n" + hint);
        }));
        host.button("开始处理", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            run.accept(uri);
        });
    }

    /** Runs a pixel operation on the worker, then offers the result through the SAF. */
    private void process(String what, Uri uri, PixelJob job) {
        final Cancel cancel = new Cancel();
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            File prepared = null;
            try {
                Bitmap decoded = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS,
                        true, false, cancel);
                int srcW = decoded.getWidth();
                int srcH = decoded.getHeight();
                int[] pixels = new int[srcW * srcH];
                decoded.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH);
                decoded.recycle();
                cancel.check();

                int[] outSize = job.size(srcW, srcH);
                int outW = outSize[0];
                int outH = outSize[1];
                int[] outPixels = job.apply(pixels, srcW, srcH, cancel);
                cancel.check();
                if (outPixels.length != outW * outH) {
                    throw new Exception("内部错误：输出像素数与尺寸不一致（" + outPixels.length
                            + " vs " + (outW * outH) + "）");
                }
                Bitmap result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                result.setPixels(outPixels, 0, outW, 0, 0, outW, outH);

                ImgSizing.Format format = job.getFormat();
                byte[] data = ImageIo.encodeFlattened(result, format, 95);
                result.recycle();

                prepared = MediaIo.scratch(host.activity(), "." + format.ext);
                try (FileOutputStream os = new FileOutputStream(prepared)) {
                    os.write(data);
                }
                String name = ImageCompressTool.suggestName(
                        MediaIo.displayName(host.activity(), uri), job.getSuffix() + "." + format.ext);
                String summary = what + "完成：" + srcW + "×" + srcH + " → " + outW + "×" + outH
                        + "，" + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length)
                        + "\n" + job.getDetail();
                final File ready = prepared;
                final String finalName = name;
                final String finalSummary = summary;
                final String mime = ImageCompressTool.mimeOf(format);
                prepared = null;
                host.post(() -> {
                    host.setRunning(false);
                    host.show(finalSummary + "\n\n请选择保存位置（原图不会被修改）。");
                    host.createDocument(finalName, mime, target -> host.background(() -> {
                        try {
                            MediaIo.publish(host.activity(), target, ready);
                            host.post(() -> host.show(finalSummary + "\n\n已保存：" + finalName));
                        } finally {
                            if (ready.exists()) {
                                ready.delete();
                            }
                        }
                    }));
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
                if (prepared != null && prepared.exists()) {
                    prepared.delete();
                }
            }
        });
    }

    /** A job is a pixel operation plus the size it produces and how it is described. */
    private interface PixelJob {
        int[] apply(int[] pixels, int width, int height, Cancel cancel) throws Exception;

        /** Output size for a given input size; may throw when the result would be too large. */
        int[] size(int width, int height);

        String getSuffix();

        String getDetail();

        ImgSizing.Format getFormat();
    }

    private interface Op {
        int[] run(int[] pixels, int width, int height, Cancel cancel) throws Exception;
    }

    /** Size function, passed in explicitly so each mode can supply its own. */
    private interface Sizer {
        int[] size(int width, int height);
    }

    /**
     * Concrete job. The size rule is a field rather than an overridable method, because a subclass
     * of this class cannot override a method declared on the interface behind it.
     */
    private static final class Job implements PixelJob {
        private final Op op;
        private final Sizer sizer;
        private final String suffix;
        private final String detail;
        private final ImgSizing.Format format;

        Job(Op op, Sizer sizer, String suffix, String detail, ImgSizing.Format format) {
            this.op = op;
            this.sizer = sizer;
            this.suffix = suffix;
            this.detail = detail;
            this.format = format;
        }

        @Override public int[] apply(int[] pixels, int width, int height, Cancel cancel) throws Exception {
            return op.run(pixels, width, height, cancel);
        }

        @Override public int[] size(int width, int height) {
            return sizer.size(width, height);
        }

        @Override public String getSuffix() {
            return suffix;
        }

        @Override public String getDetail() {
            return detail;
        }

        @Override public ImgSizing.Format getFormat() {
            return format;
        }
    }

    // ------------------------------------------------------------ 029

    private void buildSharpen() {
        host.note("锐化：提升局部对比度，让发虚的图看起来更清晰。");
        host.note("它能做的是让原本就存在的边缘更明显；不能恢复已经丢失的细节，也不能把模糊的人脸变清楚——"
                + "那需要生成式模型，本工具不做假效果。");

        final Spinner strength = host.select("锐化强度", "轻微", "适中（推荐）", "较强", "很强");
        final Spinner radius = host.select("锐化半径", "细（1 像素，适合小图）", "中（2 像素）", "粗（4 像素，适合大图）");
        final Spinner threshold = host.select("保护平坦区域",
                "低（0，全部锐化）", "中（8，推荐）", "高（20，只锐化明显边缘）");
        final Spinner format = host.select("输出格式", "PNG（无损）", "JPEG（体积小）");

        withSource("点「开始处理」进行锐化。", uri -> {
            final int amount = new int[]{60, 120, 200, 300}[strength.getSelectedItemPosition()];
            final int rad = new int[]{1, 2, 4}[radius.getSelectedItemPosition()];
            final int thr = new int[]{0, 8, 20}[threshold.getSelectedItemPosition()];
            final ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());

            process("锐化", uri, new Job(
                    (pixels, width, height, cancel) -> {
                        cancel.check();
                        return EnhanceCore.sharpen(pixels, width, height, amount, rad, thr);
                    },
                    (width, height) -> new int[]{width, height},
                    "sharp",
                    "强度 " + amount + "%，半径 " + rad + " 像素，平坦区域保护阈值 " + thr
                            + "。\n不会增加真实细节。",
                    outFormat));
        });
        host.note("建议：JPEG 照片用「适中 + 细半径」；扫描件或截图用「较强 + 中半径」。"
                + "阈值调高可以避免把皮肤和天空的噪点一起放大。");
    }

    // ------------------------------------------------------------ 030

    private void buildStretchRepair() {
        host.note("比例还原：如果图片被沿一个方向压扁或拉长过，可以按倍数还原回正确比例。");
        host.note("前提是失真确实是「线性拉伸」。这属于几何还原，能精确算出来；"
                + "如果图像是被裁切、透视变形或内容本身被扭曲，本工具无法修复，也不会假装能修。");

        final Spinner direction = host.select("被拉伸的方向",
                "横向被拉长（要压窄）", "纵向被拉长（要压扁）");
        final EditText factor = host.field("拉伸倍数（例如 4:3 被拉成 16:9 就填 1.333）", "例如 1.333");
        final Spinner preset = host.select("常用比例（选了则覆盖上面的倍数）",
                "不用常用比例", "4:3 被拉成 16:9 → 1.333", "16:9 被压成 4:3 → 0.75",
                "1:1 被拉成 16:9 → 1.778", "3:2 被拉成 16:9 → 1.185");
        final Spinner format = host.select("输出格式", "PNG（无损）", "JPEG（体积小）");

        withSource("点「开始处理」还原比例。", uri -> {
            final boolean horizontal = direction.getSelectedItemPosition() == 0;
            final double[] presets = {1.0, 1.3333, 0.75, 1.7778, 1.1852};
            final int presetIndex = preset.getSelectedItemPosition();
            double chosen;
            if (presetIndex > 0) {
                chosen = presets[presetIndex];
            } else {
                String text = factor.getText().toString().trim();
                if (text.isEmpty()) {
                    host.show("请填写拉伸倍数，或从「常用比例」里选一个。"
                            + "例如屏幕把 4:3 的内容拉成 16:9，倍数就是 1.333。");
                    return;
                }
                try {
                    chosen = Double.parseDouble(text);
                } catch (NumberFormatException ex) {
                    host.show("倍数「" + text + "」不是数字。请填例如 1.333。");
                    return;
                }
            }
            if (chosen <= 0.2 || chosen > 5.0) {
                host.show("倍数必须在 0.2 到 5 之间。当前是 " + chosen
                        + "。常见情况是 0.75 到 1.8 之间。");
                return;
            }
            final double f = chosen;
            final ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());
            final int limit = 40_000_000;

            process("比例还原", uri, new Job(
                    (pixels, width, height, cancel) -> {
                        cancel.check();
                        return EnhanceCore.repairStretch(pixels, width, height, horizontal, f);
                    },
                    (width, height) -> {
                        int w = horizontal ? Math.max(1, (int) Math.round(width / f)) : width;
                        int h = horizontal ? height : Math.max(1, (int) Math.round(height / f));
                        if ((long) w * h > limit) {
                            throw new IllegalArgumentException("还原后的图像会变成 " + w + "×" + h
                                    + "，超过 4000 万像素上限。请先缩小图片，或确认倍数是否正确。");
                        }
                        return new int[]{w, h};
                    },
                    "repaired",
                    "按 " + f + " 倍沿" + (horizontal ? "横向" : "纵向") + "还原。"
                            + "\n这是几何还原，不是内容重建；只对线性拉伸有效。",
                    outFormat));
        });
    }

    // ------------------------------------------------------------ 032

    private void buildUpscale() {
        host.note("图片放大：用高质量插值把图片放大到指定倍数或指定长边。");
        host.note("插值只能让像素变多、边缘变顺滑，不会凭空造出细节。放得越大，画面越平滑，但并不会真的更清晰。"
                + "真正把模糊照片变清晰需要超分模型，本工具不提供，也不做假效果。");

        final Spinner scale = host.select("放大倍数", "2 倍", "3 倍", "4 倍", "6 倍");
        final EditText targetEdge = host.field("或指定长边像素（填了则优先用它）", "例如 3000");
        final Spinner format = host.select("输出格式", "PNG（无损，推荐）", "JPEG（体积小）");

        withSource("点「开始处理」放大。", uri -> {
            final int factor = new int[]{2, 3, 4, 6}[scale.getSelectedItemPosition()];
            final int requestedEdge = ImageCompressTool.parsePositive(targetEdge.getText().toString(), 0);
            final ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());
            final int limit = 64_000_000;

            process("放大", uri, new Job(
                    (pixels, width, height, cancel) -> {
                        cancel.check();
                        int[] size = upscaleSize(width, height, factor, requestedEdge);
                        return EnhanceCore.lanczos(pixels, width, height, size[0], size[1]);
                    },
                    (width, height) -> {
                        int[] size = upscaleSize(width, height, factor, requestedEdge);
                        if ((long) size[0] * size[1] > limit) {
                            throw new IllegalArgumentException("放大后会是 " + size[0] + "×" + size[1]
                                    + "（" + ((long) size[0] * size[1] / 1_000_000) + " 百万像素），"
                                    + "超过 6400 万上限。请把倍数调小，或先把原图压缩一下。");
                        }
                        return size;
                    },
                    "upscaled",
                    (requestedEdge > 0 ? "放大到长边 " + requestedEdge + " 像素" : "按 " + factor + " 倍放大")
                            + "。\n这是插值放大，不增加真实细节。",
                    outFormat));
        });
        host.note("用途说明：把 1080p 素材放到 4K 时间线、给小图加内边距、避免放大时的锯齿。"
                + "如果你期待的是「把模糊的老照片变清楚」，本工具做不到，那是超分模型的工作。");
    }

    static int[] upscaleSize(int width, int height, int factor, int requestedEdge) {
        if (requestedEdge > 0) {
            return ImgSizing.fitLongEdge(width, height, requestedEdge);
        }
        return new int[]{width * factor, height * factor};
    }
}
