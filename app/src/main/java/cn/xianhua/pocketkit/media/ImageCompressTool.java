package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.util.Locale;

/**
 * 011 图片压缩（含 D01 指定像素 / 指定目标文件大小）与 023 简易图片编辑的格式转换（D02）。
 *
 * <p>Both entry points share one pipeline: pick → decode within budget → resize → encode →
 * publish. The difference is only how the output parameters are chosen, so they are one screen with
 * two modes rather than two near-identical screens.
 */
public final class ImageCompressTool implements Tool {

    private final Host host;

    public ImageCompressTool(Host host) {
        this.host = host;
    }

    /** Which entry point launched the screen; it decides the default mode. */
    public static ImageCompressTool compress(Host host) {
        return new ImageCompressTool(host);
    }

    @Override public void build() {
        host.note("选择一张图片，按像素或按目标文件大小压缩。全过程在本机完成，不上传。");

        final Uri[] source = {null};
        final android.widget.TextView picked = host.note("尚未选择图片");

        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            String name = MediaIo.displayName(host.activity(), uri);
            long size = MediaIo.size(host.activity(), uri);
            picked.setText("已选择：" + name + (size > 0 ? "（" + cn.xianhua.pocketkit.core.SizeFmt.bytes(size) + "）" : ""));
        }));

        final android.widget.Spinner mode = host.select("压缩方式",
                "按最长边像素", "按总像素（百万像素）", "按目标文件大小");
        final android.widget.EditText longEdge = host.field("最长边像素（按像素方式使用）", "例如 1080");
        final android.widget.EditText megaPixels = host.field("总像素（百万像素，按总像素方式使用）", "例如 2");
        final android.widget.EditText targetKb = host.field("目标文件大小 KB（按大小方式使用）", "例如 300");
        final android.widget.Spinner format = host.select("输出格式", "JPEG", "PNG", "WebP");
        host.note("说明：按目标文件大小时会真实编码多次试探质量，最后一步的文件大小是实测值；"
                + "若最小质量仍超出目标，会明确告知，不会假装达成。PNG 为无损，目标大小对它无效。");

        host.note("—— 基础编辑（可选，在压缩前一并应用）——");
        final android.widget.Spinner rotate = host.select("旋转",
                "不旋转", "顺时针 90°", "旋转 180°", "逆时针 90°");
        final android.widget.Spinner cropMode = host.select("裁剪",
                "不裁剪", "正方形 1:1", "16:9", "4:3", "3:4 竖版", "四边各内缩 10%");
        host.note("裁剪为居中选区。旋转与裁剪先应用，再按上面的压缩方式缩放，因此结果尺寸以旋转后的画面为准。");

        host.button("开始压缩", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片");
                return;
            }
            int modeIndex = mode.getSelectedItemPosition();
            int requestedEdge = parsePositive(longEdge.getText().toString(), 0);
            int requestedMp = parsePositive(megaPixels.getText().toString(), 0);
            long requestedBytes = parsePositive(targetKb.getText().toString(), 0) * 1024L;
            ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());
            final int rotateDegrees = new int[]{0, 90, 180, 270}[rotate.getSelectedItemPosition()];
            final int cropIndex = cropMode.getSelectedItemPosition();

            if (modeIndex == 0 && requestedEdge <= 0) {
                host.show("未完成：请填写最长边像素，例如 1080");
                return;
            }
            if (modeIndex == 1 && requestedMp <= 0) {
                host.show("未完成：请填写总像素（百万像素），例如 2");
                return;
            }
            if (modeIndex == 2 && requestedBytes <= 0) {
                host.show("未完成：请填写目标文件大小（KB），例如 300");
                return;
            }
            if (modeIndex == 2 && outFormat == ImgSizing.Format.PNG) {
                host.show("未完成：PNG 是无损格式，无法通过质量逼近目标大小。请改选 JPEG 或 WebP，"
                        + "或用「按最长边像素」压缩后再转 PNG。");
                return;
            }

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                Bitmap work = null;
                try {
                    String sourceName = MediaIo.displayName(host.activity(), uri);
                    long sourceSize = MediaIo.size(host.activity(), uri);
                    ImageIo.Meta meta = ImageIo.readMeta(host.activity(), uri, cancel);

                    Bitmap decoded = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    work = decoded;
                    String editNote = "";

                    // Rotation and crop are applied before the resize, so the requested pixel count
                    // refers to the picture the user will actually see.
                    if (rotateDegrees != 0) {
                        Bitmap rotated = rotateQuarter(decoded, rotateDegrees);
                        if (rotated != decoded) {
                            work = rotated;
                            decoded.recycle();
                        }
                        editNote += "已旋转 " + rotateDegrees + "°。";
                    }
                    if (cropIndex > 0) {
                        EditCore.Box box = cropBox(work.getWidth(), work.getHeight(), cropIndex);
                        Bitmap cropped = Bitmap.createBitmap(work, box.x, box.y, box.width, box.height);
                        if (cropped != work) {
                            work.recycle();
                            work = cropped;
                        }
                        editNote += "已裁剪到 " + box + "。";
                    }
                    cancel.check();
                    decoded = work;

                    int srcW = decoded.getWidth();
                    int srcH = decoded.getHeight();

                    int[] target;
                    if (modeIndex == 0) {
                        target = ImgSizing.fitLongEdge(srcW, srcH, requestedEdge);
                    } else if (modeIndex == 1) {
                        target = ImgSizing.fitPixels(srcW, srcH, requestedMp * 1_000_000L);
                    } else {
                        // Size mode: keep the full pixel count and let the quality search shrink it.
                        // Halving pixels first would change the picture more than quality would.
                        target = new int[]{srcW, srcH};
                    }

                    Bitmap sized = ImageIo.scale(decoded, target[0], target[1]);
                    if (sized != decoded) {
                        work = sized;
                        decoded.recycle();
                    }

                    byte[] data;
                    String detail;
                    if (modeIndex == 2) {
                        // Flatten once: the quality search encodes many times and re-creating a
                        // full-size ARGB copy per attempt would be pure waste.
                        final Bitmap flat = outFormat == ImgSizing.Format.JPEG && work.hasAlpha()
                                ? ImageIo.flattenAlpha(work, 0xFFFFFFFF) : work;
                        ImgSizing.Result result;
                        try {
                            result = ImgSizing.searchQuality(
                                    q -> ImageIo.encode(flat, outFormat, q), requestedBytes);
                        } finally {
                            if (flat != work && !flat.isRecycled()) {
                                flat.recycle();
                            }
                        }
                        data = result.data;
                        detail = result.targetMissed
                                ? "注意：已降到最低质量 " + result.quality + "，仍大于目标；文件大小以实测为准。"
                                : "质量参数 " + result.quality + "（在目标大小内取到的最高质量）。";
                    } else {
                        // Fixed-size modes keep quality high and report the real result, rather than
                        // running the size search that the user did not ask for.
                        data = ImageIo.encodeFlattened(work, outFormat, 92);
                        detail = "JPEG/WebP 质量 92。";
                    }

                    cancel.check();
                    prepared = MediaIo.scratch(host.activity(), "." + outFormat.ext);
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(prepared)) {
                        os.write(data);
                    }

                    final String outName = suggestName(sourceName, outFormat.ext);
                    int outW = work.getWidth();
                    int outH = work.getHeight();
                    long originalBytes = sourceSize;
                    String summary = "原图：" + meta.orientedWidth() + "×" + meta.orientedHeight()
                            + "，解码后 " + srcW + "×" + srcH
                            + (originalBytes > 0 ? "，" + cn.xianhua.pocketkit.core.SizeFmt.bytes(originalBytes) : "")
                            + "\n输出：" + outW + "×" + outH + " "
                            + ImageIo.formatName(outFormat) + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length)
                            + "\n" + detail
                            + (editNote.isEmpty() ? "" : "\n" + editNote);
                    if (originalBytes > 0) {
                        long saved = originalBytes - data.length;
                        summary += (saved > 0
                                ? String.format(Locale.US, "\n体积减少 %.1f%%", saved * 100.0 / originalBytes)
                                : "\n体积比原图更大：原图可能已经是高压缩的 JPEG，或输出选择了无损 PNG。");
                    }

                    final String summaryText = summary;
                    final String outFileName = outName;
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summaryText + "\n\n请选择保存位置。");
                        host.createDocument(outFileName, mimeOf(outFormat), target2 -> {
                            final Cancel publishCancel = new Cancel();
                            host.onCancel(publishCancel::cancel);
                            host.setRunning(true);
                            host.background(() -> {
                                try {
                                    MediaIo.publish(host.activity(), target2, ready);
                                    host.post(() -> {
                                        host.setRunning(false);
                                        host.show(summaryText + "\n\n已保存：" + outFileName);
                                    });
                                } finally {
                                    // Cache files are ours to remove; the user's copy is already out.
                                    if (ready.exists() && !ready.delete()) {
                                        ready.deleteOnExit();
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
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });
    }

    /** Rotates by a right angle using a Matrix; 0° returns the input unchanged. */
    static Bitmap rotateQuarter(Bitmap src, int degrees) {
        int normalized = EditCore.normalize(degrees);
        if (normalized == 0) {
            return src;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(normalized);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** The crop box for a preset index; the indices match the spinner order in {@link #build()}. */
    static EditCore.Box cropBox(int width, int height, int index) {
        switch (index) {
            case 1: return EditCore.centeredAspect(width, height, 1, 1);
            case 2: return EditCore.centeredAspect(width, height, 16, 9);
            case 3: return EditCore.centeredAspect(width, height, 4, 3);
            case 4: return EditCore.centeredAspect(width, height, 3, 4);
            case 5: return EditCore.inset(width, height, 10);
            default: throw new IllegalArgumentException("未知的裁剪方式");
        }
    }

    public static int parsePositive(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(trimmed);
            if (value <= 0) {
                return fallback;
            }
            return (int) Math.min(value, Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Keeps the original stem so the user can tell which picture this came from. */
    public static String suggestName(String sourceName, String ext) {
        String stem = sourceName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        if (stem.length() > 60) {
            stem = stem.substring(0, 60);
        }
        return stem + "." + ext;
    }

    public static String mimeOf(ImgSizing.Format format) {
        switch (format) {
            case PNG: return "image/png";
            case WEBP: return "image/webp";
            default: return "image/jpeg";
        }
    }
}
