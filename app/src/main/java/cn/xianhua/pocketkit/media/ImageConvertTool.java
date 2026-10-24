package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

/**
 * 014 图片转 Base64 与 015 图片水印。
 *
 * <p>Base64 output is written to a text file through the SAF rather than shown in the result pane:
 * a 100 KB photo becomes about 137 KB of text, and pasting that into a TextView would freeze the UI.
 */
public final class ImageConvertTool implements Tool {

    private final Host host;
    private final String toolId;

    public ImageConvertTool(Host host, String toolId) {
        this.host = host;
        this.toolId = toolId;
    }

    @Override public void build() {
        if ("015".equals(toolId)) {
            buildWatermark();
        } else {
            buildBase64();
        }
    }

    // ------------------------------------------------------------ 014

    private void buildBase64() {
        host.note("把图片编码成 Base64 文本，或把 Base64 文本还原成图片。用于嵌入网页、配置文件或接口测试。");
        final Spinner direction = host.select("方向", "图片 → Base64 文本", "Base64 文本 → 图片");
        final Uri[] source = {null};
        final android.widget.TextView picked = host.note("尚未选择图片（仅「图片 → Base64」需要）");
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            long size = MediaIo.size(host.activity(), uri);
            picked.setText("已选择：" + MediaIo.displayName(host.activity(), uri)
                    + (size > 0 ? "（" + cn.xianhua.pocketkit.core.SizeFmt.bytes(size)
                    + "，编码后约 " + cn.xianhua.pocketkit.core.SizeFmt.bytes((long) (size * 4 / 3.0)) + "）" : ""));
        }));
        final Spinner prefix = host.select("是否带 data URI 前缀",
                "纯 Base64", "带 data:image/...;base64, 前缀");
        final EditText base64Input = host.field("粘贴 Base64 文本（仅「Base64 → 图片」需要）", "粘贴以 data:image 开头或纯 Base64 都可以");

        host.button("开始转换", () -> {
            if (direction.getSelectedItemPosition() == 0) {
                Uri uri = source[0];
                if (uri == null) {
                    host.show("未完成：请先选择一张图片。");
                    return;
                }
                final boolean withPrefix = prefix.getSelectedItemPosition() == 1;
                final Cancel cancel = new Cancel();
                host.onCancel(cancel::cancel);
                host.setRunning(true);
                host.background(() -> {
                    File prepared = null;
                    try {
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = MediaIo.openInput(host.activity(), uri)) {
                            MediaIo.copy(in, buffer, cancel, MediaIo.MAX_BYTES, null);
                        }
                        cancel.check();
                        byte[] bytes = buffer.toByteArray();
                        String encoded = Base64.getEncoder().encodeToString(bytes);
                        String mime = mimeOf(MediaIo.displayName(host.activity(), uri));
                        String text = withPrefix ? "data:" + mime + ";base64," + encoded : encoded;

                        byte[] textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        prepared = MediaIo.scratch(host.activity(), ".txt");
                        try (FileOutputStream os = new FileOutputStream(prepared)) {
                            os.write(textBytes);
                        }
                        String name = ImageCompressTool.suggestName(
                                MediaIo.displayName(host.activity(), uri), "base64.txt");
                        // Only a short head goes to the screen; the full text is in the saved file.
                        String head = text.length() > 240 ? text.substring(0, 240) + "…" : text;
                        String summary = "编码完成。\n原始 " + bytes.length + " 字节 → Base64 "
                                + encoded.length() + " 字符\n"
                                + (withPrefix ? "已带 data URI 前缀（" + mime + "）" : "纯 Base64，无前缀") + "\n\n"
                                + head + "\n\n完整内容在保存的 TXT 里（结果太长，不直接显示以免界面卡住）。";
                        final File ready = prepared;
                        prepared = null;
                        host.post(() -> {
                            host.setRunning(false);
                            host.show(summary + "\n\n请选择保存位置。");
                            host.createDocument(name, "text/plain", target -> host.background(() -> {
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
            } else {
                String raw = base64Input.getText().toString().trim();
                if (raw.isEmpty()) {
                    host.show("未完成：请先粘贴 Base64 文本。");
                    return;
                }
                final Cancel cancel = new Cancel();
                host.onCancel(cancel::cancel);
                host.setRunning(true);
                host.background(() -> {
                    File prepared = null;
                    try {
                        byte[] bytes = decodeBase64(raw);
                        cancel.check();
                        // Verify it really is an image before writing anything: a text file would
                        // otherwise be saved with an image extension and fail to open anywhere.
                        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        if (options.outWidth <= 0 || options.outHeight <= 0) {
                            throw new Exception("这段 Base64 解出来不是系统能识别的图片。"
                                    + "请确认它来自图片（JPEG / PNG / WebP / GIF），而不是其他文件。");
                        }
                        String format = options.outMimeType == null ? "image/jpeg" : options.outMimeType;
                        String extension = format.contains("png") ? "png"
                                : format.contains("webp") ? "webp" : "jpg";
                        prepared = MediaIo.scratch(host.activity(), "." + extension);
                        try (FileOutputStream os = new FileOutputStream(prepared)) {
                            os.write(bytes);
                        }
                        String name = "decoded-" + System.currentTimeMillis() + "." + extension;
                        String summary = "解码完成：" + options.outWidth + "×" + options.outHeight + "，"
                                + format + "，" + cn.xianhua.pocketkit.core.SizeFmt.bytes(bytes.length);
                        final File ready = prepared;
                        prepared = null;
                        host.post(() -> {
                            host.setRunning(false);
                            host.show(summary + "\n\n请选择保存位置。");
                            host.createDocument(name, format, target -> host.background(() -> {
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
            }
        });

        host.note("提醒：Base64 文本比原图大约 33%。若图片里有隐私内容，Base64 并不提供任何保护，谁都能解回来。");
    }

    /**
     * 输入文本的字符上限：8 Mi 字符，解码后约 6 MiB；给低内存设备留出余量。
     *
     * 再大的图片这个工具也处理不了：原文、去空白副本、解码结果会同时在内存里
     * （约输入的 2.3 倍），低端机直接 OutOfMemoryError，而 OOM 不是普通异常，
     * 界面兜不住。所以在做任何复制之前先按字符数拒绝。
     */
    static final int MAX_BASE64_CHARS = 8 * 1024 * 1024;

    /** 长度闸门单列出来，测试可以只传长度，不必真的分配几十 MB 的字符串。 */
    static void checkLength(int length) throws Exception {
        if (length > MAX_BASE64_CHARS) {
            throw new Exception("Base64 文本过长（" + length + " 字符，上限 " + MAX_BASE64_CHARS
                    + "）。请先压缩或缩小图片，再重新编码。");
        }
    }

    /** Accepts a data URI or raw Base64, ignoring whitespace; reports why when it is malformed. */
    public static byte[] decodeBase64(String text) throws Exception {
        checkLength(text.length());
        String body = text.trim();
        int comma = body.indexOf(',');
        if (body.regionMatches(true, 0, "data:", 0, 5) && comma > 0) {
            String meta = body.substring(0, comma);
            if (!meta.toLowerCase(java.util.Locale.US).contains("base64")) {
                throw new Exception("这段 data URI 不是 base64 编码（缺少「base64」标记），无法解码。");
            }
            body = body.substring(comma + 1);
        }
        body = body.replaceAll("\\s+", "");
        if (body.isEmpty()) {
            throw new Exception("Base64 内容为空。");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            // Try the URL-safe alphabet before giving up: both are common in the wild.
            try {
                return Base64.getUrlDecoder().decode(body);
            } catch (IllegalArgumentException ignored) {
                throw new Exception("这段内容不是合法的 Base64（可能含非法字符或长度不对）。"
                        + "请检查是否复制完整、是否混入了其他文字。");
            }
        }
    }

    public static String mimeOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    // ------------------------------------------------------------ 015

    private void buildWatermark() {
        host.note("给图片加文字水印。可以调位置、大小、透明度与颜色。");
        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n填好水印内容后点「添加水印」。");
        }));

        final EditText mark = host.field("水印文字", "例如：仅供办理业务使用");
        final Spinner position = host.select("位置",
                "右下角", "左下角", "右上角", "左上角", "正中");
        final Spinner opacity = host.select("不透明度", "30%", "50%", "70%", "90%");
        final Spinner color = host.select("颜色", "白色（带黑边，深浅底都看得见）", "黑色", "中灰", "红色");
        final Spinner sizePercent = host.select("字号（占图片宽度比例）", "2%", "3.5%", "5%", "8%", "12%");
        final Spinner repeat = host.select("平铺", "单个水印", "整图平铺（防盗图）");
        final Spinner outFormat = host.select("输出格式", "JPEG", "PNG");

        host.button("添加水印", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            String text = mark.getText().toString();
            if (text.trim().isEmpty()) {
                host.show("未完成：请填写水印文字。");
                return;
            }
            final int posIndex = position.getSelectedItemPosition();
            final int alpha = new int[]{77, 128, 179, 230}[opacity.getSelectedItemPosition()];
            final int colorIndex = color.getSelectedItemPosition();
            final double sizeRatio = new double[]{0.02, 0.035, 0.05, 0.08, 0.12}[sizePercent.getSelectedItemPosition()];
            final boolean tiled = repeat.getSelectedItemPosition() == 1;
            final ImgSizing.Format format = ImgSizing.Format.of((String) outFormat.getSelectedItem());

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                Bitmap bitmap = null;
                try {
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    Canvas canvas = new Canvas(bitmap);
                    float size = (float) (bitmap.getWidth() * sizeRatio);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setTextSize(size);
                    int baseColor;
                    switch (colorIndex) {
                        case 1: baseColor = Color.BLACK; break;
                        case 2: baseColor = 0xFF808080; break;
                        case 3: baseColor = 0xFFD32F2F; break;
                        default: baseColor = Color.WHITE; break;
                    }
                    paint.setColor(baseColor);
                    paint.setAlpha(alpha);
                    if (colorIndex == 0) {
                        // A dark outline: a white-only watermark disappears on a bright photo.
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(Math.max(2f, size / 14f));
                        paint.setColor(0xFF000000);
                        paint.setAlpha(Math.min(255, alpha + 40));
                        drawMarks(canvas, text, bitmap, posIndex, tiled, paint);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(baseColor);
                        paint.setAlpha(alpha);
                    }
                    drawMarks(canvas, text, bitmap, posIndex, tiled, paint);
                    cancel.check();

                    byte[] data = ImageIo.encodeFlattened(bitmap, format, 92);
                    prepared = MediaIo.scratch(host.activity(), "." + format.ext);
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(data);
                    }
                    String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "watermark." + format.ext);
                    String summary = "已添加水印：" + text + "\n位置 "
                            + position.getSelectedItem() + "，不透明度 " + opacity.getSelectedItem()
                            + (tiled ? "，整图平铺" : "") + "\n输出 "
                            + bitmap.getWidth() + "×" + bitmap.getHeight() + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length);
                    final File ready = prepared;
                    final String finalName = name;
                    final String finalSummary = summary;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置（原图不受影响）。");
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
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });
        host.note("水印是烤进像素的，不能再分离。它用于表明用途，不能防止被裁剪或再截图。");
    }

    /** Places the watermark: one corner, the centre, or tiled across the whole picture. */
    private static void drawMarks(Canvas canvas, String text, Bitmap bitmap, int position,
                                  boolean tiled, Paint paint) {
        float w = bitmap.getWidth();
        float h = bitmap.getHeight();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        float pad = Math.max(8f, w * 0.02f);
        if (tiled) {
            float textWidth = paint.measureText(text);
            float stepX = textWidth + pad * 3;
            float stepY = textHeight + pad * 3;
            canvas.save();
            // Rotate the whole tiled pattern so it cannot be cropped out by a straight cut.
            canvas.rotate(-30, w / 2, h / 2);
            for (float y = -h; y < h * 2; y += stepY) {
                for (float x = -w; x < w * 2; x += stepX) {
                    canvas.drawText(text, x, y, paint);
                }
            }
            canvas.restore();
            return;
        }
        float textWidth = paint.measureText(text);
        float x;
        float y;
        switch (position) {
            case 1: x = pad; y = h - pad; break;
            case 2: x = w - textWidth - pad; y = pad - metrics.ascent; break;
            case 3: x = pad; y = pad - metrics.ascent; break;
            case 4: x = (w - textWidth) / 2; y = (h - textHeight) / 2 - metrics.ascent; break;
            default: x = w - textWidth - pad; y = h - pad; break;
        }
        canvas.drawText(text, x, y, paint);
    }
}
