package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 033 拍照计数：在对比明显的背景上数独立物体。
 *
 * <p>The screen states 非 AI and what it can and cannot do, because a counter that quietly miscounts
 * touching objects is worse than one that explains its limits. The overlay marks each counted blob
 * so the user verifies the number rather than trusting it.
 */
public final class CountTool implements Tool {

    private final Host host;

    public CountTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("数出画面里有多少个独立物体（药片、硬币、零件、圆点等）。");
        host.note("非 AI 计数：用的是连通区域分析，不是目标检测。它在「背景干净、物体分开、对比明显」时准确；"
                + "物体互相碰到、背景花纹杂乱、反光严重时会数错。请在结果图上核对，不要直接当作结论。");

        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「开始计数」。");
        }));

        final Spinner minArea = host.select("最小物体大小（占画面比例）",
                "0.05%（很小，可能把噪点算进来）", "0.2%（推荐）", "0.5%", "2%（只数大物体）");
        final Spinner output = host.select("导出内容",
                "只导出标注结果图", "标注结果图 + 计数明细 TXT");

        host.button("开始计数", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final double ratio = new double[]{0.0005, 0.002, 0.005, 0.02}[minArea.getSelectedItemPosition()];
            final boolean withDetail = output.getSelectedItemPosition() == 1;

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    // Detection runs on a reduced copy: connected-component labelling scales with
                    // pixel count, and a 12 MP photo gives no better blob separation than a 2 MP one.
                    Bitmap source8 = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    int fullW = source8.getWidth();
                    int fullH = source8.getHeight();
                    int[] full = new int[fullW * fullH];
                    source8.getPixels(full, 0, fullW, 0, 0, fullW, fullH);
                    source8.recycle();
                    cancel.check();

                    int detectEdge = 1400;
                    int[] smallSize = ScanCore.downscaledSize(fullW, fullH, detectEdge);
                    int[] small = ScanCore.downscale(full, fullW, fullH, detectEdge);
                    CountCore.Result result = CountCore.count(small, smallSize[0], smallSize[1], ratio);
                    cancel.check();

                    // Scale the blob geometry back to full resolution before marking, so the overlay
                    // lines up with the pixels the user sees.
                    double k = fullW / (double) smallSize[0];
                    java.util.List<CountCore.Blob> scaled = new java.util.ArrayList<>();
                    for (CountCore.Blob b : result.blobs) {
                        scaled.add(new CountCore.Blob(
                                (int) Math.round(b.area * k * k),
                                (int) Math.round(b.minX * k), (int) Math.round(b.minY * k),
                                (int) Math.round(b.maxX * k), (int) Math.round(b.maxY * k),
                                (int) Math.round(b.centerX * k), (int) Math.round(b.centerY * k)));
                    }
                    CountCore.markBlobs(full, fullW, fullH, scaled);
                    cancel.check();

                    Bitmap marked = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888);
                    marked.setPixels(full, 0, fullW, 0, 0, fullW, fullH);
                    byte[] png = ImageIo.encode(marked, ImgSizing.Format.PNG, 100);
                    marked.recycle();
                    prepared = MediaIo.scratch(host.activity(), ".png");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(png);
                    }

                    String detail = CountCore.describe(result, smallSize[0], smallSize[1], ratio)
                            + "\n标注说明：绿色圆圈标出的就是被计数的物体。请对照原图核对，"
                            + "尤其是物体互相接触或边缘反光的位置。";
                    String summary = "计数完成。\n" + detail
                            + "\n\n标注图大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length)
                            + "（原图 " + fullW + "×" + fullH + "，检测在 "
                            + smallSize[0] + "×" + smallSize[1] + " 上进行）。";

                    final File ready = prepared;
                    final String finalSummary = summary;
                    final String baseName = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), uri), "count.png");
                    prepared = null;

                    if (!withDetail) {
                        host.post(() -> {
                            host.setRunning(false);
                            host.show(finalSummary + "\n\n请选择标注图的保存位置。");
                            host.createDocument(baseName, "image/png", target -> host.background(() -> {
                                try {
                                    MediaIo.publish(host.activity(), target, ready);
                                    host.post(() -> host.show(finalSummary + "\n\n已保存标注图。"));
                                } finally {
                                    if (ready.exists()) {
                                        ready.delete();
                                    }
                                }
                            }));
                        });
                        return;
                    }

                    // Detail export: the per-blob table, written beside the image.
                    StringBuilder csv = new StringBuilder();
                    csv.append("序号\t面积(像素)\t中心X\t中心Y\t宽\t高\n");
                    for (int i = 0; i < scaled.size(); i++) {
                        CountCore.Blob b = scaled.get(i);
                        csv.append(i + 1).append('\t').append(b.area).append('\t')
                                .append(b.centerX).append('\t').append(b.centerY).append('\t')
                                .append(b.width()).append('\t').append(b.height()).append('\n');
                    }
                    File detailFile = MediaIo.scratch(host.activity(), ".txt");
                    try (FileOutputStream os = new FileOutputStream(detailFile)) {
                        os.write(0xEF);
                        os.write(0xBB);
                        os.write(0xBF);
                        os.write(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n先选标注图的保存位置，再选明细表的保存位置。");
                        host.createDocument(baseName, "image/png", target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.createDocument("count-detail.txt", "text/plain",
                                        detailTarget -> host.background(() -> {
                                            try {
                                                MediaIo.publish(host.activity(), detailTarget, detailFile);
                                                host.post(() -> host.show(finalSummary
                                                        + "\n\n已保存标注图与计数明细（UTF-8，制表符分隔）。"));
                                            } catch (Exception ex) {
                                                host.post(() -> host.fail(ex));
                                            } finally {
                                                detailFile.delete();
                                            }
                                        })));
                            } catch (Exception ex) {
                                host.post(() -> host.fail(ex));
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

        host.note("拍摄建议：把物体摊平不要互相压住、用与物体颜色对比明显的纯色背景、避免强反光、"
                + "让物体占满画面中央。这三点对结果的影响比调整上面的参数大得多。");
    }
}
