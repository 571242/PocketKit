package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 024 表情制作：把图片做成聊天表情，带白边、可选文字与常见表情尺寸。
 *
 * <p>The image work is the same shape as the other tools here: decode within a budget, compose on a
 * canvas, encode, save through the SAF. The parts specific to stickers are the outline cut-out and
 * the caption, both drawn as vector operations on the bitmap.
 *
 * <p>The outline is a genuine edge pass over the alpha channel: for each transparent pixel that
 * neighbours an opaque one, a stroke pixel is added. That is what makes a cut-out sticker read
 * clearly on a coloured chat background; simply drawing a border around the bounding box would look
 * wrong on any non-rectangular subject.
 */
public final class StickerTool implements Tool {

    private final Host host;

    public StickerTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("把图片做成表情图：可加白边、加文字、裁成常见表情尺寸。全过程在本机完成。");

        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n选好下面项目后点「生成表情」。");
        }));

        final Spinner sizePreset = host.select("尺寸",
                "240×240（微信小表情常用）", "512×512（通用）", "240×240 保持原图比例（居中留白）",
                "原图尺寸");
        final Spinner outline = host.select("白边", "不加", "细", "中", "粗");
        final Spinner caption = host.select("文字位置", "不加文字", "底部（白字带黑影）", "顶部（白字带黑影）");
        final EditText captionText = host.field("文字内容（选「不加文字」时忽略）", "例如：哈哈哈");
        final Spinner background = host.select("背景", "透明（PNG，推荐）", "白色", "黑色");
        final Spinner shape = host.select("形状", "原样", "圆形裁剪", "圆角方形");
        final Spinner outFormat = host.select("输出格式", "PNG（保留透明）", "JPEG（不支持透明）");

        host.button("生成表情", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final int sizeIndex = sizePreset.getSelectedItemPosition();
            final int outlineIndex = outline.getSelectedItemPosition();
            final int captionIndex = caption.getSelectedItemPosition();
            final String text = captionText.getText().toString();
            if (captionIndex > 0 && text.trim().isEmpty()) {
                host.show("未完成：你选择了加文字，但文字内容为空。请填写文字，或把文字位置改回「不加文字」。");
                return;
            }
            final int backgroundIndex = background.getSelectedItemPosition();
            final int shapeIndex = shape.getSelectedItemPosition();
            final ImgSizing.Format format = ImgSizing.Format.of((String) outFormat.getSelectedItem());
            if (format == ImgSizing.Format.JPEG && backgroundIndex == 0) {
                host.show("未完成：JPEG 不支持透明背景。请把背景改成白色或黑色，或把输出格式改成 PNG。");
                return;
            }

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    Bitmap src = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    int[] size = targetSize(src.getWidth(), src.getHeight(), sizeIndex);
                    Bitmap composed = compose(src, size[0], size[1], outlineIndex, captionIndex,
                            text, backgroundIndex, shapeIndex, cancel);
                    src.recycle();
                    cancel.check();

                    byte[] data = ImageIo.encodeFlattened(composed, format, 95);
                    int outW = composed.getWidth();
                    int outH = composed.getHeight();
                    composed.recycle();

                    prepared = MediaIo.scratch(host.activity(), "." + format.ext);
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(data);
                    }
                    String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "sticker." + format.ext);
                    String summary = "已生成表情 " + outW + "×" + outH + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length)
                            + "\n白边：" + outline.getSelectedItem()
                            + "；形状：" + shape.getSelectedItem()
                            + "；背景：" + background.getSelectedItem()
                            + (captionIndex > 0 ? "；文字：" + text.trim() : "");
                    final File ready = prepared;
                    final String finalName = name;
                    final String finalSummary = summary;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置。");
                        host.createDocument(finalName, ImageCompressTool.mimeOf(format), target ->
                                host.background(() -> {
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
        });

        host.note("白边是对透明区域做边缘检测后描出来的，因此贴着人物轮廓走，不是套一个方框。"
                + "要保留透明背景请用 PNG；JPEG 会把透明处填白。");
    }

    /** Output size for a preset. Preset 2 fits the image inside a square without cropping it. */
    public static int[] targetSize(int srcW, int srcH, int preset) {
        switch (preset) {
            case 0:
                return new int[]{240, 240};
            case 1:
                return new int[]{512, 512};
            case 2:
                return new int[]{240, 240};
            default:
                return new int[]{srcW, srcH};
        }
    }

    /**
     * Composes the sticker.
     *
     * <p>Order matters: background first, then the image (cropped square for presets 0/1 or fitted
     * for preset 2), then the outline (which samples the composed alpha, so it follows the visible
     * silhouette), then the caption on top so the outline never covers it.
     */
    Bitmap compose(Bitmap src, int width, int height, int outline, int captionIndex, String text,
                   int backgroundIndex, int shapeIndex, Cancel cancel) throws Cancel.Stop {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        switch (backgroundIndex) {
            case 1: canvas.drawColor(Color.WHITE); break;
            case 2: canvas.drawColor(Color.BLACK); break;
            default: break;   // transparent
        }

        boolean squareCrop = (width == 240 && height == 240) || (width == 512 && height == 512);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (squareCrop && shapeIndex != 0) {
            // Round or rounded-rect clipping goes through a Path so the edge stays smooth.
            Path clip = new Path();
            if (shapeIndex == 1) {
                clip.addCircle(width / 2f, height / 2f, Math.min(width, height) / 2f, Path.Direction.CW);
            } else {
                float radius = Math.min(width, height) * 0.18f;
                clip.addRoundRect(new RectF(0, 0, width, height), radius, radius, Path.Direction.CW);
            }
            canvas.save();
            canvas.clipPath(clip);
            drawFitted(canvas, src, width, height, squareCrop, paint);
            canvas.restore();
        } else {
            drawFitted(canvas, src, width, height, squareCrop, paint);
        }
        cancel.check();

        if (outline > 0) {
            drawOutline(out, outline == 1 ? 2 : outline == 2 ? 4 : 7, Color.WHITE);
        }
        cancel.check();

        if (captionIndex > 0 && text != null && !text.trim().isEmpty()) {
            drawCaption(canvas, text.trim(), width, height, captionIndex == 1);
        }
        return out;
    }

    /** Centre-crops (square presets) or letterboxes (other presets) the source into the canvas. */
    private static void drawFitted(Canvas canvas, Bitmap src, int width, int height,
                                   boolean squareCrop, Paint paint) {
        Rect dst = new Rect(0, 0, width, height);
        if (squareCrop) {
            int edge = Math.min(src.getWidth(), src.getHeight());
            int left = (src.getWidth() - edge) / 2;
            int top = (src.getHeight() - edge) / 2;
            canvas.drawBitmap(src, new Rect(left, top, left + edge, top + edge), dst, paint);
            return;
        }
        float scale = Math.min(width / (float) src.getWidth(), height / (float) src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        int dx = (width - w) / 2;
        int dy = (height - h) / 2;
        canvas.drawBitmap(src, null, new Rect(dx, dy, dx + w, dy + h), paint);
    }

    /**
     * Adds a stroke around the opaque region.
     *
     * <p>Implemented as a two-pass alpha dilation over an int array rather than a BlurMaskFilter:
     * dilation gives a predictable, evenly thick border, while a blur would fade the edge and make a
     * thick outline look muddy on a small sticker.
     */
    public static void drawOutline(Bitmap bitmap, int thickness, int color) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] current = new int[w * h];
        System.arraycopy(pixels, 0, current, 0, pixels.length);
        for (int pass = 0; pass < thickness; pass++) {
            int[] next = current.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int index = y * w + x;
                    if ((current[index] >>> 24) >= 128) {
                        continue;
                    }
                    // An opaque 4-neighbour means this pixel is on the silhouette.
                    boolean near = (x > 0 && (current[index - 1] >>> 24) >= 128)
                            || (x < w - 1 && (current[index + 1] >>> 24) >= 128)
                            || (y > 0 && (current[index - w] >>> 24) >= 128)
                            || (y < h - 1 && (current[index + w] >>> 24) >= 128);
                    if (near) {
                        next[index] = color;
                    }
                }
            }
            current = next;
        }
        bitmap.setPixels(current, 0, w, 0, 0, w, h);
    }

    /** Draws the caption with a hard drop shadow so it stays legible on any picture. */
    private static void drawCaption(Canvas canvas, String text, int width, int height, boolean bottom) {
        float size = Math.max(14f, width * 0.10f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, size / 7f));
        paint.setColor(Color.BLACK);
        float cx = width / 2f;
        float cy = bottom ? height - size * 0.6f : size * 1.4f;
        // The stroke is drawn twice so diagonal strokes fill in rather than showing gaps.
        canvas.drawText(text, cx, cy, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawText(text, cx, cy, paint);
    }
}
