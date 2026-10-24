package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import cn.xianhua.pocketkit.media.gif.GifReader;
import cn.xianhua.pocketkit.media.gif.GifWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 007 GIF 合成与分解。
 *
 * <p>Neither direction uses AWT: writing goes through the vendored NeuQuant/LZW core plus
 * {@link GifWriter}; reading goes through {@link GifReader}, which implements disposal methods,
 * transparency, local palettes, interlacing and per-frame delays itself. See
 * {@code docs/media-implementation.md} for the exact source provenance of the vendored files.
 *
 * <p>Decomposition writes every frame as its own PNG. It does not take the first frame and call it
 * a job: each frame is decoded with its own composited canvas, so a moving animation yields visibly
 * different pictures.
 */
public final class GifTool implements Tool {

    private final Host host;

    public GifTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        buildCompose();
        buildSplit();
    }

    // ------------------------------------------------------------ 合成

    private void buildCompose() {
        host.note("── 多张图片合成 GIF ──");
        host.note("按选择顺序成帧。每帧单独量化并写入局部调色板，因此画面变化大时不会糊成一片。");

        final List<Uri> picked = new ArrayList<>();
        final android.widget.TextView pickedInfo = host.note("尚未选择图片");
        host.button("选择图片（可多选）", () -> host.pickDocuments("image/*", uris -> {
            picked.clear();
            picked.addAll(uris);
            pickedInfo.setText("已选择 " + uris.size() + " 张，将合成 " + uris.size() + " 帧。");
        }));

        final EditText delayField = host.field("每帧延迟（毫秒）", "例如 200");
        final Spinner loop = host.select("循环", "无限循环", "只播一次", "循环 3 次");
        final EditText maxEdge = host.field("输出长边像素（留空表示用第 1 张的尺寸）", "例如 480");
        final Spinner fitMode = host.select("尺寸不一的图片如何处理",
                "等比缩放到统一画布并居中（留透明边）", "等比缩放后裁满画布（不留边）");

        host.button("合成 GIF", () -> {
            if (picked.size() < 2) {
                host.show("未完成：合成 GIF 至少需要 2 张图片，当前选了 " + picked.size() + " 张。");
                return;
            }
            int delayMs = ImageCompressTool.parsePositive(delayField.getText().toString(), 200);
            if (delayMs < 20) {
                host.show("未完成：每帧延迟至少 20 毫秒；更小的值播放器会当作「尽可能快」，看起来像卡住。");
                return;
            }
            int loopCount = new int[]{0, 1, 3}[loop.getSelectedItemPosition()];
            int edge = ImageCompressTool.parsePositive(maxEdge.getText().toString(), 0);
            final int finalDelay = delayMs;
            final int finalLoop = loopCount;
            final int finalEdge = edge;
            final int fit = fitMode.getSelectedItemPosition();
            List<Uri> sources = new ArrayList<>(picked);

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    List<Bitmap> decoded = new ArrayList<>();
                    int width = 0;
                    int height = 0;
                    for (Uri uri : sources) {
                        Bitmap bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS,
                                true, false, cancel);
                        decoded.add(bitmap);
                        width = Math.max(width, bitmap.getWidth());
                        height = Math.max(height, bitmap.getHeight());
                    }
                    if (finalEdge > 0) {
                        int[] size = ImgSizing.fitLongEdge(width, height, finalEdge);
                        width = size[0];
                        height = size[1];
                    }
                    // GIF has no fractional dimensions; keep both even-free but at least 1.
                    width = Math.max(1, width);
                    height = Math.max(1, height);

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    boolean transparent = fit == 0;
                    try (GifWriter writer = new GifWriter(buffer, width, height, finalLoop)) {
                        int index = 1;
                        for (Bitmap source : decoded) {
                            cancel.check();
                            int[] frame = composeFrame(source, width, height, cancel, transparent);
                            writer.addFrame(frame, finalDelay, transparent);
                            index++;
                        }
                    }
                    for (Bitmap b : decoded) {
                        if (!b.isRecycled()) {
                            b.recycle();
                        }
                    }
                    cancel.check();

                    byte[] gif = buffer.toByteArray();
                    prepared = MediaIo.scratch(host.activity(), ".gif");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(gif);
                    }
                    // Verify the result by reading it back before offering it: a GIF that we cannot
                    // parse ourselves is not something to hand the user and hope for.
                    GifReader.Result verify = new GifReader(gif).read();
                    String name = "animation-" + System.currentTimeMillis() + ".gif";
                    String summary = "已合成 GIF：" + verify.frames.size() + " 帧，"
                            + width + "×" + height + "，每帧 " + finalDelay + "ms，"
                            + (finalLoop == 0 ? "无限循环" : "循环 " + finalLoop + " 次")
                            + "\n文件大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(gif.length)
                            + "\n已回读校验：解出 " + verify.frames.size() + " 帧，尺寸 "
                            + verify.width + "×" + verify.height
                            + (verify.usesTransparency ? "，含透明" : "");
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置。");
                        host.createDocument(name, "image/gif", target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.show(summary + "\n\n已保存：" + name));
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
        });

        host.note("提示：GIF 只有 256 色，照片类内容会有色带，这是格式本身的限制，不是压缩质量设置能解决的。"
                + "画面变化剧烈时色带更明显。");
    }

    /**
     * Composes one source image into a canvas-sized ARGB frame.
     *
     * @param transparent when true the unpainted area stays transparent, which is what a user
     *                    choosing "keep the whole picture" expects; otherwise it is filled white
     */
    static int[] composeFrame(Bitmap source, int width, int height, Cancel cancel, boolean transparent)
            throws Cancel.Stop {
        Bitmap canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(canvas);
        if (!transparent) {
            c.drawColor(android.graphics.Color.WHITE);
        }
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min(width / (float) source.getWidth(), height / (float) source.getHeight());
        if (scale > 0 && scale != 1f) {
            // Scale only when it actually changes something, and never enlarge past the canvas.
            int w = Math.max(1, Math.round(source.getWidth() * scale));
            int h = Math.max(1, Math.round(source.getHeight() * scale));
            android.graphics.Rect dst = new android.graphics.Rect((width - w) / 2, (height - h) / 2,
                    (width - w) / 2 + w, (height - h) / 2 + h);
            c.drawBitmap(source, null, dst, paint);
        } else {
            c.drawBitmap(source, (width - source.getWidth()) / 2f,
                    (height - source.getHeight()) / 2f, paint);
        }
        int[] argb = new int[width * height];
        canvas.getPixels(argb, 0, width, 0, 0, width, height);
        canvas.recycle();
        return argb;
    }

    // ------------------------------------------------------------ 分解

    private void buildSplit() {
        host.note("── GIF 分解为逐帧图片 ──");
        final Uri[] source = {null};
        host.button("选择 GIF", () -> host.pickDocument("image/gif", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「分解为逐帧图片」。");
        }));
        final Spinner what = host.select("导出内容", "每一帧各一张 PNG（完整合成后的画面）", "只导出第 1 帧");
        final EditText maxFrames = host.field("最多导出几帧（留空表示全部）", "例如 60");
        final Spinner scale = host.select("输出缩放", "原始尺寸", "长边缩到 720", "长边缩到 320（方便预览）");
        host.button("分解为逐帧图片", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一个 GIF 文件。");
                return;
            }
            final boolean allFrames = what.getSelectedItemPosition() == 0;
            final int limit = ImageCompressTool.parsePositive(maxFrames.getText().toString(), 0);
            final int[] scaleEdges = {0, 720, 320};
            final int targetEdge = scaleEdges[scale.getSelectedItemPosition()];

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                try {
                    // Read the whole GIF into memory: a GIF that cannot be read is an error the user
                    // must see now, not a half-finished set of files.
                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    try (java.io.InputStream in = MediaIo.openInput(host.activity(), uri)) {
                        MediaIo.copy(in, raw, cancel, MediaIo.MAX_BYTES, null);
                    }
                    cancel.check();
                    GifReader.Result result = new GifReader(raw.toByteArray()).read();

                    List<int[]> frames = new ArrayList<>();
                    List<Integer> delays = new ArrayList<>();
                    int count = allFrames ? result.frames.size() : 1;
                    if (limit > 0 && limit < count) {
                        count = limit;
                    }
                    for (int i = 0; i < count; i++) {
                        frames.add(result.frames.get(i).argb);
                        delays.add(result.frames.get(i).delayMs);
                    }
                    cancel.check();

                    List<File> files = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    int outW = result.width;
                    int outH = result.height;
                    if (targetEdge > 0) {
                        int[] size = ImgSizing.fitLongEdge(result.width, result.height, targetEdge);
                        outW = size[0];
                        outH = size[1];
                    }
                    for (int i = 0; i < frames.size(); i++) {
                        cancel.check();
                        Bitmap bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888);
                        bitmap.setPixels(frames.get(i), 0, result.width, 0, 0, result.width, result.height);
                        Bitmap out = outW == result.width && outH == result.height
                                ? bitmap : ImageIo.scale(bitmap, outW, outH);
                        // PNG with alpha preserved: a frame that was transparent in the GIF must stay
                        // transparent, otherwise the compositing information is lost.
                        byte[] png = ImageIo.encode(out, ImgSizing.Format.PNG, 100);
                        bitmap.recycle();
                        if (out != bitmap && !out.isRecycled()) {
                            out.recycle();
                        }
                        File file = MediaIo.scratch(host.activity(), ".png");
                        try (FileOutputStream os = new FileOutputStream(file)) {
                            os.write(png);
                        }
                        files.add(file);
                        names.add(String.format(java.util.Locale.US, "frame-%03d.png", i + 1));
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("GIF 分解完成。\n");
                    sb.append("原始信息：").append(result.width).append("×").append(result.height)
                            .append("，共 ").append(result.frames.size()).append(" 帧");
                    sb.append(result.loopCount == 0 ? "，无限循环" : "，循环 " + result.loopCount + " 次");
                    sb.append('\n');
                    sb.append("用了局部调色板的帧：").append(result.localPaletteFrames).append('\n');
                    sb.append("包含透明：").append(result.usesTransparency ? "是" : "否").append('\n');
                    sb.append("出现的 disposal 方法：");
                    List<String> methods = new ArrayList<>();
                    if ((result.disposalMethodsUsed & 0b0001) != 0) {
                        methods.add("0/1（保留）");
                    }
                    if ((result.disposalMethodsUsed & 0b0100) != 0) {
                        methods.add("2（还原背景）");
                    }
                    if ((result.disposalMethodsUsed & 0b1000) != 0) {
                        methods.add("3（还原上一帧）");
                    }
                    sb.append(String.join("、", methods)).append('\n');
                    if (limit > 0 && limit < result.frames.size() && allFrames) {
                        sb.append("（按设置只导出了前 ").append(count).append(" 帧）\n");
                    }
                    sb.append("导出 ").append(files.size()).append(" 个 PNG，")
                            .append(outW).append("×").append(outH).append("。");
                    if (!allFrames) {
                        sb.append("\n注意：你选择了「只导出第 1 帧」，因此这不是完整分解。");
                    }
                    String summary = sb.toString();

                    final List<File> ready = files;
                    final List<String> readyNames = names;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n接下来逐个选择保存位置（共 " + ready.size() + " 个文件）。");
                        saveNext(ready, readyNames, 0, summary);
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
                }
            });
        });

        host.note("分解是逐帧真解码：循环、每帧延迟、透明与 disposal（0/1/2/3）都会按规范还原，"
                + "所以移动的画面会得到内容不同的多张图，而不是同一张重复。");
    }

    private void saveNext(List<File> files, List<String> names, int index, String summary) {
        if (index >= files.size()) {
            host.show(summary + "\n\n已全部保存。");
            return;
        }
        File file = files.get(index);
        String name = names.get(index);
        host.createDocument(name, "image/png", target -> host.background(() -> {
            try {
                MediaIo.publish(host.activity(), target, file);
                host.post(() -> saveNext(files, names, index + 1, summary));
            } catch (Exception ex) {
                host.post(() -> {
                    host.fail(ex);
                    // Keep going: one refused destination should not abandon the remaining frames.
                    saveNext(files, names, index + 1, summary);
                });
            } finally {
                if (index == files.size() - 1) {
                    for (File f : files) {
                        if (f.exists()) {
                            f.delete();
                        }
                    }
                }
            }
        }));
    }
}
