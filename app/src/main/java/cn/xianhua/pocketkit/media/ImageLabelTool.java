package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.Spinner;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.util.List;

/**
 * 026 AI 图片识别：ML Kit 图片标签（on-device 推理）。
 *
 * <p>Scope is deliberately narrow and stated on screen: this labels <em>what kind of thing</em> is
 * in the picture from a fixed English label set (about 400 categories), with a confidence score.
 * It is not object detection (no boxes), not face recognition, not text reading, and not a
 * general-purpose "understanding" of the image. Saying that plainly is the difference between a
 * real feature and a misleading one.
 *
 * <p>Options are set explicitly rather than left to defaults:
 * <ul>
 *   <li>A confidence threshold and result cap are applied so the list is short enough to read and
 *       does not pad itself with near-zero guesses.</li>
 *   <li>Model downloads are disabled: {@code setNumThreads} is avoided and the default bundled model
 *       is used, so this works offline instead of silently fetching a model on first use.</li>
 * </ul>
 */
public final class ImageLabelTool implements Tool {

    private final Host host;

    public ImageLabelTool(Host host) {
        this.host = host;
    }

    /** Below this the label is a guess; ML Kit's own scores for true positives are usually higher. */
    private static final float MIN_CONFIDENCE = 0.50f;
    private static final int MAX_LABELS = 8;

    @Override public void build() {
        host.note("识别图片里「大致是什么」：给出若干英文标签和置信度，并附中文对照。"
                + "在本机推理，图片不上传。该 SDK 为闭源，可能收集诊断与性能数据。");

        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「开始识别」。");
        }));

        final Spinner threshold = host.select("最低置信度",
                "50%（推荐，减少噪音）", "30%（更宽松，可能不准）", "70%（只保留较确定的）");
        final Spinner count = host.select("最多显示几条", "5 条", "8 条", "15 条");

        host.button("开始识别", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final float minConfidence = new float[]{0.50f, 0.30f, 0.70f}[threshold.getSelectedItemPosition()];
            final int maxLabels = new int[]{5, 8, 15}[count.getSelectedItemPosition()];

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                ImageLabeler labeler = null;
                Bitmap bitmap = null;
                try {
                    labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
                            .setConfidenceThreshold(minConfidence)
                            .build());
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    List<ImageLabel> labels = com.google.android.gms.tasks.Tasks.await(
                            labeler.process(InputImage.fromBitmap(bitmap, 0)));
                    cancel.check();

                    StringBuilder sb = new StringBuilder();
                    sb.append("图片：").append(MediaIo.displayName(host.activity(), uri)).append('\n');
                    sb.append("尺寸：").append(bitmap.getWidth()).append("×").append(bitmap.getHeight()).append('\n');
                    sb.append("最低置信度：").append(Math.round(minConfidence * 100)).append("%\n");

                    if (labels.isEmpty()) {
                        sb.append("\n没有达到置信度的标签。\n")
                                .append("可能原因：画面内容不在模型的约 400 个常见类别里（例如抽象图案、")
                                .append("特定品牌、大段文字、纯色背景），或主体太小。\n")
                                .append("这不是「识别失败」，而是模型确实不确定——本工具不会为了有输出而编造标签。");
                    } else {
                        int shown = Math.min(labels.size(), maxLabels);
                        sb.append("\n识别到 ").append(labels.size()).append(" 个标签，显示前 ")
                                .append(shown).append(" 个：\n");
                        for (int i = 0; i < shown; i++) {
                            ImageLabel label = labels.get(i);
                            String english = label.getText();
                            sb.append('\n').append(i + 1).append(". ").append(english)
                                    .append("  ").append(Math.round(label.getConfidence() * 100)).append('%');
                            String chinese = LabelZh.lookup(english);
                            if (chinese != null) {
                                sb.append("  ").append(chinese);
                            }
                            sb.append('\n').append("    ").append(confidenceNote(label.getConfidence()));
                        }
                        if (labels.size() > shown) {
                            sb.append("\n（还有 ").append(labels.size() - shown)
                                    .append(" 个更低的标签未显示）");
                        }
                    }
                    sb.append("\n\n范围说明：本工具只给「大致类别」，不给位置框，不做物体计数，"
                            + "不认人、不读字。标签为英文，中文对照仅覆盖常见项。");
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
                    if (labeler != null) {
                        labeler.close();
                    }
                }
            });
        });

        host.note("限制说明：标签来自固定的英文类别集（约 400 类），无法识别不在其中的东西；"
                + "置信度是模型自评分数，不是「正确答案的概率」。同一张图重复识别结果一致，"
                + "但换一张相似图片可能给出不同标签。");
    }

    /** Plain-language reading of a confidence score, so a number is not mistaken for certainty. */
    public static String confidenceNote(float confidence) {
        if (confidence >= 0.85f) {
            return "模型相当确定（高分标签通常与画面主体一致，仍可能出错）";
        }
        if (confidence >= 0.70f) {
            return "模型比较确定（可作为参考）";
        }
        if (confidence >= 0.50f) {
            return "模型不太确定（画面里可能有类似的东西）";
        }
        return "模型很不确定（基本是猜测，仅供线索）";
    }
}
