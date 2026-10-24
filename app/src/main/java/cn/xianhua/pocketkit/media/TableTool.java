package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 031 图片表格识别：把表格照片里的文字按行列还原出来。
 *
 * <p>The recognition is OCR (ML Kit) and the <em>table structure</em> is inferred from the geometry of
 * the recognised text boxes — see {@link TableCore}. The screen is explicit that merged cells and
 * borderless adjacent columns will not come out right, and the preview is shown before saving so a
 * wrong grouping is visible rather than discovered later in a spreadsheet.
 */
public final class TableTool implements Tool {

    private final Host host;

    public TableTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("把表格照片转成可复制的表格（TSV / CSV）。");
        host.note("识别文字用离线 OCR；行列结构是按文字框的位置推断的，不是理解表格语义。"
                + "因此合并单元格、没有空白分隔的相邻列、跨页表格会不准——结果先给你预览，"
                + "确认无误再导出。识别与结构推断都在本机完成，图片不上传。");

        final Uri[] source = {null};
        host.button("选择表格图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「识别表格」。");
        }));

        final Spinner gap = host.select("列分隔的敏感度",
                "自动（推荐）", "更敏感（容易多分列）", "更迟钝（容易并成一列）");
        final Spinner format = host.select("导出格式",
                "TSV（制表符，粘贴进表格软件最好用）", "CSV（逗号分隔）", "纯文本（对齐显示）");

        host.button("识别表格", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张表格图片。");
                return;
            }
            final double gapRatio = new double[]{2.2, 1.4, 3.5}[gap.getSelectedItemPosition()];
            final int formatIndex = format.getSelectedItemPosition();

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                TextRecognizer recognizer = null;
                Bitmap bitmap = null;
                try {
                    recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    int imgW = bitmap.getWidth();
                    int imgH = bitmap.getHeight();
                    Text recognised = com.google.android.gms.tasks.Tasks.await(
                            recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                    cancel.check();

                    List<TableCore.Cell> cells = new ArrayList<>();
                    for (Text.TextBlock block : recognised.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            android.graphics.Rect box = line.getBoundingBox();
                            if (box == null) {
                                continue;
                            }
                            // Word-level boxes give the column split something to work with; a
                            // line-level box would make every row a single cell.
                            for (Text.Element element : line.getElements()) {
                                android.graphics.Rect eb = element.getBoundingBox();
                                if (eb != null && element.getText() != null
                                        && !element.getText().trim().isEmpty()) {
                                    cells.add(new TableCore.Cell(element.getText().trim(),
                                            eb.left, eb.top, eb.right, eb.bottom));
                                }
                            }
                        }
                    }
                    TableCore.Table table = TableCore.build(cells, gapRatio);
                    if (table.isEmpty()) {
                        host.post(() -> {
                            host.setRunning(false);
                            host.show("没有识别到表格内容。\n"
                                    + "可能原因：图片里没有文字、表格太小或太模糊、或文字是竖排。\n"
                                    + "请换一张更清晰、表格占画面更大的照片重试。本工具不会为没有识别到内容的图片"
                                    + "生成空表格。");
                        });
                        return;
                    }

                    String text;
                    String extension;
                    String mime;
                    String label;
                    if (formatIndex == 1) {
                        text = TableCore.toCsv(table);
                        extension = "csv";
                        mime = "text/csv";
                        label = "CSV";
                    } else if (formatIndex == 2) {
                        text = TableCore.toTsv(table).replace('\t', ' ');
                        extension = "txt";
                        mime = "text/plain";
                        label = "纯文本";
                    } else {
                        text = TableCore.toTsv(table);
                        extension = "tsv";
                        mime = "text/tab-separated-values";
                        label = "TSV";
                    }
                    File out = MediaIo.scratch(host.activity(), "." + extension);
                    try (FileOutputStream os = new FileOutputStream(out)) {
                        // UTF-8 BOM: Excel on Windows otherwise reads Chinese columns as mojibake.
                        os.write(0xEF);
                        os.write(0xBB);
                        os.write(0xBF);
                        os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    String preview = TableCore.preview(table, 12, 6);
                    String summary = "识别完成（图片 " + imgW + "×" + imgH + "，识别到 "
                            + cells.size() + " 个文字块）。\n\n" + preview
                            + "\n列敏感度：" + gap.getSelectedItem() + "\n"
                            + "导出格式：" + label + "（UTF-8 含 BOM，Excel 可直接打开中文）";
                    final File ready = out;
                    final String finalSummary = summary;
                    final String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "table." + extension);
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置。");
                        host.createDocument(name, mime, target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.show(finalSummary + "\n\n已保存：" + name));
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
                    if (recognizer != null) {
                        recognizer.close();
                    }
                }
            });
        });

        host.note("提示：拍表格时尽量正对、让表格四边平行于画面，并包含表格的外框线。"
                + "斜着拍的表格会让文字框位置错位，进而把行列分错。");
    }
}
