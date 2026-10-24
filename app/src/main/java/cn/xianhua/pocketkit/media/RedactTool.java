package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.widget.Spinner;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A04 自动打码与文本脱敏。
 *
 * <p>Two mechanisms, kept separate because they have different reliability:
 * <ul>
 *   <li>Text: ML Kit recognises it, then {@link RedactCore} locates phone numbers, e-mail addresses
 *       and card numbers by pattern, and those exact word boxes are covered. Patterns are
 *       verifiable, which is why no name/address guesswork is attempted.</li>
 *   <li>Faces: ML Kit detects them and each box is covered. No identity is computed or stored —
 *       only the boxes are used, and the image never leaves the device.</li>
 * </ul>
 * Boxes are drawn opaque, and the screen says the covered region cannot be recovered from the
 * output, so the user does not treat it as reversible.
 */
public final class RedactTool implements Tool {

    private final Host host;

    public RedactTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("识别文字与人脸并打码。在本机完成，图片不上传。人脸检测可能收集诊断与性能数据，"
                + "可能收集诊断与性能数据。");

        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n选好下面的项目后点「开始打码」。");
        }));

        final android.widget.CheckBox phones = host.checkbox("遮住电话号码", true);
        final android.widget.CheckBox emails = host.checkbox("遮住邮箱地址", true);
        final android.widget.CheckBox cards = host.checkbox("遮住银行卡号 / 长数字串", true);
        final android.widget.CheckBox faces = host.checkbox("遮住人脸", true);
        final Spinner style = host.select("遮挡方式", "黑色实心块（推荐）", "白色实心块", "马赛克方块");
        final Spinner format = host.select("输出格式", "PNG（无压缩损失）", "JPEG");

        host.button("开始打码", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            if (!phones.isChecked() && !emails.isChecked() && !cards.isChecked() && !faces.isChecked()) {
                host.show("未完成：至少勾选一项要遮住的内容。");
                return;
            }
            final boolean doPhones = phones.isChecked();
            final boolean doEmails = emails.isChecked();
            final boolean doCards = cards.isChecked();
            final boolean doFaces = faces.isChecked();
            final int styleIndex = style.getSelectedItemPosition();
            final ImgSizing.Format outFormat = ImgSizing.Format.of((String) format.getSelectedItem());

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                TextRecognizer recognizer = null;
                FaceDetector detector = null;
                Bitmap bitmap = null;
                File prepared = null;
                try {
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    InputImage image = InputImage.fromBitmap(bitmap, 0);
                    List<Rect> boxes = new ArrayList<>();
                    List<String> notes = new ArrayList<>();

                    if (doPhones || doEmails || doCards) {
                        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                        Text text = com.google.android.gms.tasks.Tasks.await(recognizer.process(image));
                        cancel.check();
                        int hits = 0;
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                String content = line.getText();
                                List<RedactCore.Span> spans = RedactCore.find(content, doPhones, doEmails, doCards);
                                if (spans.isEmpty()) {
                                    continue;
                                }
                                // Cover the whole line rather than a sub-range: splitting a line's box
                                // into per-character boxes needs per-symbol geometry, and a box that is
                                // one line too wide is a smaller error than a phone number left legible.
                                boxes.add(line.getBoundingBox());
                                hits += spans.size();
                                String trimmed = content.trim();
                                notes.add("文字：" + (trimmed.length() > 40
                                        ? trimmed.substring(0, 40) + "…" : trimmed));
                            }
                        }
                        notes.add("定位到 " + hits + " 处敏感文本。");
                    }

                    if (doFaces) {
                        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                                .build());
                        List<Face> detected = com.google.android.gms.tasks.Tasks.await(detector.process(image));
                        cancel.check();
                        for (Face face : detected) {
                            boxes.add(face.getBoundingBox());
                        }
                        notes.add("检测到 " + detected.size() + " 张人脸。");
                    }

                    if (boxes.isEmpty()) {
                        notes.add("没有找到需要遮挡的内容。若确实有敏感信息，可能是文字太小或图片模糊；"
                                + "本工具按识别结果打码，识别不到就不会遮盖。");
                    } else {
                        applyMasks(bitmap, boxes, styleIndex, cancel);
                    }
                    cancel.check();

                    byte[] data = ImageIo.encodeFlattened(bitmap, outFormat, 95);
                    prepared = MediaIo.scratch(host.activity(), "." + outFormat.ext);
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(data);
                    }

                    String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "masked." + outFormat.ext);
                    String summary = String.join("\n", notes)
                            + "\n\n遮挡区域在输出文件中不可还原。原始文件不会被修改。";
                    final File ready = prepared;
                    final String finalName = name;
                    final String finalSummary = summary;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置。");
                        host.createDocument(finalName, ImageCompressTool.mimeOf(outFormat), target ->
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
                    if (recognizer != null) {
                        recognizer.close();
                    }
                    if (detector != null) {
                        detector.close();
                    }
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });

        host.note("限制说明：文字脱敏覆盖的是识别到的那一行，因此同一行里的其他内容也会被一起遮住；"
                + "人脸检测对侧脸、遮挡和小尺寸人脸可能漏检。打码不是「涂改后仍可读」的模糊，"
                + "而是实心覆盖，请确认无误后再分享。");
    }

    /** Draws the masks. Mosaic uses block averaging over the box so nothing inside survives. */
    static void applyMasks(Bitmap bitmap, List<Rect> boxes, int style, Cancel cancel) throws Cancel.Stop {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        if (style == 1) {
            paint.setColor(Color.WHITE);
        } else if (style == 0) {
            paint.setColor(Color.BLACK);
        }
        for (Rect box : boxes) {
            cancel.check();
            if (box == null) {
                continue;
            }
            Rect clipped = new Rect(
                    Math.max(0, box.left), Math.max(0, box.top),
                    Math.min(bitmap.getWidth(), box.right), Math.min(bitmap.getHeight(), box.bottom));
            if (clipped.width() <= 0 || clipped.height() <= 0) {
                continue;
            }
            if (style == 2) {
                mosaic(bitmap, clipped);
            } else {
                canvas.drawRect(clipped, paint);
            }
        }
    }

    /** Averages each block and paints it back, so the covered pixels cannot be recovered. */
    private static void mosaic(Bitmap bitmap, Rect area) {
        int block = Math.max(6, Math.min(area.width(), area.height()) / 12);
        int w = area.width();
        int h = area.height();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, area.left, area.top, w, h);
        for (int by = 0; by < h; by += block) {
            for (int bx = 0; bx < w; bx += block) {
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int y = by; y < Math.min(by + block, h); y++) {
                    for (int x = bx; x < Math.min(bx + block, w); x++) {
                        int c = pixels[y * w + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                int color = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                for (int y = by; y < Math.min(by + block, h); y++) {
                    for (int x = bx; x < Math.min(bx + block, w); x++) {
                        pixels[y * w + x] = color;
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, area.left, area.top, w, h);
    }
}
