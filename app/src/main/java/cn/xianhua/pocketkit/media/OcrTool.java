package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.Spinner;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 034 OCR 图片文字识别，含 D03 离线识别与导出 TXT。
 *
 * <p>The Chinese model is bundled in the APK, so recognition itself needs no download and works
 * with the phone offline. The SDK that runs it is closed-source and may report diagnostics, which
 * the screen states; the app does not upload the image itself.
 *
 * <p>Text never leaves the device unless the user saves the TXT into a location they pick.
 */
public final class OcrTool implements Tool {

    private final Host host;

    public OcrTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("识别图片中的文字，可导出为 TXT。中文模型已随应用打包，断网也能识别；"
                + "识别在本机完成，图片不会上传。该 SDK 为闭源，可能收集诊断与性能数据。");

        final Uri[] source = {null};
        final StringBuilder lastText = new StringBuilder();
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「开始识别」。");
        }));

        final Spinner script = host.select("文字类型", "中文 / 英文混合（推荐）", "仅英文与数字");
        final Spinner layout = host.select("导出时的换行方式", "按识别到的段落", "按识别到的行");

        host.button("开始识别", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片");
                return;
            }
            final boolean chinese = script.getSelectedItemPosition() == 0;
            final boolean byParagraph = layout.getSelectedItemPosition() == 0;
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                TextRecognizer recognizer = null;
                Bitmap bitmap = null;
                try {
                    recognizer = chinese
                            ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                            : TextRecognition.getClient(new com.google.mlkit.vision.text.latin.TextRecognizerOptions.Builder().build());
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    Text result = com.google.android.gms.tasks.Tasks.await(
                            recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                    cancel.check();

                    String text = byParagraph ? joinParagraphs(result) : joinLines(result);
                    lastText.setLength(0);
                    lastText.append(text);

                    StringBuilder sb = new StringBuilder();
                    if (text.trim().isEmpty()) {
                        sb.append("没有识别到文字。\n可能原因：图片里没有文字、文字太小或太模糊、或文字是竖排/艺术字。"
                                + "换一张更清晰、拍摄更正对文字的图片通常能显著改善。");
                    } else {
                        int lines = 0;
                        for (Text.TextBlock block : result.getTextBlocks()) {
                            lines += block.getLines().size();
                        }
                        sb.append("识别完成：").append(result.getTextBlocks().size()).append(" 个段落，")
                                .append(lines).append(" 行，").append(countChars(text)).append(" 个字符。\n");
                        sb.append("识别语言：").append(chinese ? "中文 / 英文混合" : "仅英文与数字").append('\n');
                        // Actually truncate the preview. Appending the whole text and then claiming
                        // "the first 4000 characters" showed everything and contradicted itself; the
                        // full result is still kept in lastText for the TXT export.
                        sb.append("\n———— 识别结果 ————\n").append(preview(text, PREVIEW_CHARS));
                        if (countChars(text) > PREVIEW_CHARS) {
                            sb.append("\n\n（以上为前 ").append(PREVIEW_CHARS)
                                    .append(" 个字符的预览，共 ").append(countChars(text))
                                    .append(" 个字符；导出 TXT 会包含完整结果）");
                        }
                    }
                    String report = sb.toString();
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(report);
                    });
                } catch (Cancel.Stop stopped) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.show("已取消。");
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
                    if (recognizer != null) {
                        // Detector.close() returns void; the model is released synchronously.
                        recognizer.close();
                    }
                }
            });
        });

        host.button("导出为 TXT", () -> {
            if (lastText.length() == 0) {
                host.show("未完成：请先识别一次文字，再导出。");
                return;
            }
            final String text = lastText.toString();
            final String name = "ocr-" + System.currentTimeMillis() + ".txt";
            host.createDocument(name, "text/plain", target -> host.background(() -> {
                try {
                    File prepared = MediaIo.scratch(host.activity(), ".txt");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        // UTF-8 with a BOM: Windows Notepad still mis-detects plain UTF-8 Chinese.
                        os.write(0xEF);
                        os.write(0xBB);
                        os.write(0xBF);
                        os.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                    MediaIo.publish(host.activity(), target, prepared);
                    long size = prepared.length();
                    prepared.delete();
                    host.post(() -> host.show("已导出 TXT：" + name + "\n共 "
                            + countChars(text) + " 个字符，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(size)
                            + "\n编码 UTF-8（含 BOM，Windows 记事本可直接识别中文）。"));
                } catch (Exception ex) {
                    host.post(() -> host.fail(ex));
                }
            }));
        });

        host.note("竖排文字与手写体识别率明显更低，这是模型本身的限制；本工具不做额外声称。");
    }

    /** Blocks joined with a blank line, so paragraphs survive the round trip to TXT. */
    static String joinParagraphs(Text result) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            String text = block.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.trim());
        }
        return sb.toString();
    }

    static String joinLines(Text result) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                sb.append(text.trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /** How much recognised text goes into the reading pane before it is cut off. */
    public static final int PREVIEW_CHARS = 4000;

    /**
     * Truncates for display. Cut on a code-point boundary so a surrogate pair (an emoji or a rare
     * CJK character) is never split into two broken halves.
     */
    public static String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (countChars(text) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end);
    }

    public static int countChars(String text) {
        // Code points, not chars: an emoji or a rare CJK extension character is one character.
        return text.codePointCount(0, text.length());
    }
}
