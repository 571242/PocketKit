package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.SeekBar;
import android.widget.Spinner;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 027 人脸融合：把两张照片里最大的人脸对齐后做柔和混合。
 *
 * <p>It is a real composite (see {@link FaceBlendCore}) and it is labelled 非 AI 合成 rather than
 * "AI 换脸": the result carries features of both people through a cross-fade, and no new facial
 * structure is generated. Saying that plainly is the honest description of what the maths does.
 *
 * <p>Landmark detection runs on-device; the photos never leave the phone.
 */
public final class FaceBlendTool implements Tool {

    private final Host host;

    public FaceBlendTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("把两张照片里最大的人脸对齐后柔和混合，得到一张「两张脸叠在一起」的合成图。");
        host.note("非 AI 合成：这是基于人脸关键点对齐 + 羽化掩膜的交叉渐变，不是生成式换脸。"
                + "它不能改变姿态与光照，也不会凭空生成五官；两张脸的角度差得太远时结果会重影。"
                + "人检与合成都在本机完成，照片不上传。");

        final Uri[] baseUri = {null};
        final Uri[] overlayUri = {null};
        final android.widget.TextView picked = host.note("尚未选择照片（需要两张：底图和叠加图）");

        host.button("选择底图（脸的位置以这张为准）", () -> host.pickDocument("image/*", uri -> {
            baseUri[0] = uri;
            picked.setText(status(baseUri[0], overlayUri[0]));
        }));
        host.button("选择叠加图（要叠上去的脸）", () -> host.pickDocument("image/*", uri -> {
            overlayUri[0] = uri;
            picked.setText(status(baseUri[0], overlayUri[0]));
        }));

        host.note("混合强度：100% 时中心完全用叠加图的脸，50% 时两张脸各占一半。");
        final SeekBar blendBar = new SeekBar(host.activity());
        blendBar.setMax(100);
        blendBar.setProgress(FaceBlendCore.defaultBlend());
        final android.widget.TextView blendLabel = host.note("混合强度：50%");
        blendBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                blendLabel.setText("混合强度：" + progress + "%"
                        + (progress >= 90 ? "（几乎只用叠加图）" : progress <= 15 ? "（几乎看不出叠加图）" : ""));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        host.body().addView(blendBar, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final Spinner feather = host.select("边缘羽化（越大越自然，但会带进更多周围内容）",
                "窄（只融合脸部中心）", "适中（推荐）", "宽（连头发肩膀一起过渡）");
        final Spinner output = host.select("输出内容", "只导出合成图", "合成图 + 人脸检测标注图");

        host.button("开始合成", () -> {
            if (baseUri[0] == null || overlayUri[0] == null) {
                host.show("未完成：需要两张照片。请先选「底图」，再选「叠加图」。");
                return;
            }
            final int blend = Math.max(0, blendBar.getProgress());
            final double featherRun = new double[]{35, 55, 75}[feather.getSelectedItemPosition()];
            final boolean withMarks = output.getSelectedItemPosition() == 1;

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                FaceDetector detector = null;
                List<File> temp = new ArrayList<>();
                try {
                    detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .build());

                    Bitmap baseBitmap = ImageIo.decode(host.activity(), baseUri[0],
                            ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    Bitmap overlayBitmap = ImageIo.decode(host.activity(), overlayUri[0],
                            ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    cancel.check();

                    List<FaceBlendCore.FaceBox> baseFaces = detect(detector, baseBitmap, cancel);
                    List<FaceBlendCore.FaceBox> overlayFaces = detect(detector, overlayBitmap, cancel);
                    cancel.check();

                    if (baseFaces.isEmpty()) {
                        baseBitmap.recycle();
                        overlayBitmap.recycle();
                        host.post(() -> {
                            host.setRunning(false);
                            host.show("未完成：底图里没有检测到人脸，因此无法定位要合成的区域。\n"
                                    + "请换一张人脸清晰、占画面较大、没有被遮挡的照片。"
                                    + "本工具不会在没有检测到脸的情况下随便找一块区域合成。");
                        });
                        return;
                    }
                    if (overlayFaces.isEmpty()) {
                        baseBitmap.recycle();
                        overlayBitmap.recycle();
                        host.post(() -> {
                            host.setRunning(false);
                            host.show("未完成：叠加图里没有检测到人脸。请换一张有人脸的照片。");
                        });
                        return;
                    }

                    FaceBlendCore.FaceBox baseFace = FaceBlendCore.largest(baseFaces);
                    FaceBlendCore.FaceBox overlayFace = FaceBlendCore.largest(overlayFaces);

                    int baseW = baseBitmap.getWidth();
                    int baseH = baseBitmap.getHeight();
                    int[] basePixels = new int[baseW * baseH];
                    baseBitmap.getPixels(basePixels, 0, baseW, 0, 0, baseW, baseH);
                    int overW = overlayBitmap.getWidth();
                    int overH = overlayBitmap.getHeight();
                    int[] overPixels = new int[overW * overH];
                    overlayBitmap.getPixels(overPixels, 0, overW, 0, 0, overW, overH);
                    baseBitmap.recycle();
                    overlayBitmap.recycle();

                    int[] result = FaceBlendCore.blend(basePixels, baseW, baseH, overPixels, overW, overH,
                            baseFace, overlayFace, blend, featherRun, cancel);

                    if (withMarks) {
                        markFace(result, baseW, baseH, baseFace);
                    }
                    Bitmap composed = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888);
                    composed.setPixels(result, 0, baseW, 0, 0, baseW, baseH);
                    byte[] png = ImageIo.encode(composed, ImgSizing.Format.PNG, 100);
                    composed.recycle();
                    File image = MediaIo.scratch(host.activity(), ".png");
                    temp.add(image);
                    try (FileOutputStream os = new FileOutputStream(image)) {
                        os.write(png);
                    }

                    if (withMarks) {
                        Bitmap markedBase = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888);
                        markedBase.setPixels(basePixels, 0, baseW, 0, 0, baseW, baseH);
                        for (FaceBlendCore.FaceBox f : baseFaces) {
                            drawBox(markedBase, f, 0xFFFFC107);
                        }
                        Bitmap markedOverlay = Bitmap.createBitmap(overW, overH, Bitmap.Config.ARGB_8888);
                        markedOverlay.setPixels(overPixels, 0, overW, 0, 0, overW, overH);
                        for (FaceBlendCore.FaceBox f : overlayFaces) {
                            drawBox(markedOverlay, f, 0xFF03A9F4);
                        }
                        File b = MediaIo.scratch(host.activity(), ".png");
                        temp.add(b);
                        try (FileOutputStream os = new FileOutputStream(b)) {
                            os.write(ImageIo.encode(markedBase, ImgSizing.Format.PNG, 100));
                        }
                        markedBase.recycle();
                        File o = MediaIo.scratch(host.activity(), ".png");
                        temp.add(o);
                        try (FileOutputStream os = new FileOutputStream(o)) {
                            os.write(ImageIo.encode(markedOverlay, ImgSizing.Format.PNG, 100));
                        }
                        markedOverlay.recycle();
                    }

                    String summary = "合成完成：" + baseW + "×" + baseH + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length)
                            + "\n底图检测到 " + baseFaces.size() + " 张脸，叠加图 " + overlayFaces.size()
                            + " 张；本次使用各自最大的一张。\n"
                            + "混合强度 " + blend + "%，羽化 " + feather.getSelectedItem() + "。\n"
                            + "这是非 AI 的交叉渐变合成，不是生成式换脸：结果是两张脸叠在一起的效果，"
                            + "姿态与光照仍来自底图。";
                    final File ready = image;
                    final String finalSummary = summary;
                    final String name = ImageCompressTool.suggestName(
                            MediaIo.displayName(host.activity(), baseUri[0]), "blend.png");
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(finalSummary + "\n\n请选择保存位置。");
                        host.createDocument(name, "image/png", target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.show(finalSummary + "\n\n已保存：" + name));
                            } finally {
                                for (File f : temp) {
                                    if (f.exists()) {
                                        f.delete();
                                    }
                                }
                            }
                        }));
                    });
                } catch (Cancel.Stop stopped) {
                    for (File f : temp) {
                        if (f.exists()) {
                            f.delete();
                        }
                    }
                    host.post(() -> {
                        host.setRunning(false);
                        host.show("已取消，未写出文件。");
                    });
                } catch (Exception ex) {
                    for (File f : temp) {
                        if (f.exists()) {
                            f.delete();
                        }
                    }
                    host.post(() -> {
                        host.setRunning(false);
                        host.fail(ex);
                    });
                } finally {
                    if (detector != null) {
                        detector.close();
                    }
                }
            });
        });

        host.note("效果好的条件：两张脸朝向接近、都正对镜头、光照方向差别不大。"
                + "侧脸配正脸、或一张逆光一张顺光时，叠出来会明显不自然——这是交叉渐变的固有结果，"
                + "不是参数没调好。");
    }

    private static String status(Uri base, Uri overlay) {
        return "底图：" + (base == null ? "未选择" : MediaIo.displayName(null, base))
                + "\n叠加图：" + (overlay == null ? "未选择" : MediaIo.displayName(null, overlay));
    }

    /** Runs ML Kit's face detector and converts its results into the blend core's own type. */
    private static List<FaceBlendCore.FaceBox> detect(FaceDetector detector, Bitmap bitmap, Cancel cancel)
            throws Exception {
        List<Face> faces = com.google.android.gms.tasks.Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)));
        cancel.check();
        List<FaceBlendCore.FaceBox> out = new ArrayList<>();
        for (Face face : faces) {
            android.graphics.Rect box = face.getBoundingBox();
            FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
            FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
            boolean hasEyes = leftEye != null && rightEye != null;
            out.add(new FaceBlendCore.FaceBox(box.left, box.top, box.right, box.bottom,
                    hasEyes ? (int) leftEye.getPosition().x : 0,
                    hasEyes ? (int) leftEye.getPosition().y : 0,
                    hasEyes ? (int) rightEye.getPosition().x : 0,
                    hasEyes ? (int) rightEye.getPosition().y : 0,
                    hasEyes));
        }
        return out;
    }

    /** Outlines the region the blend acted on, so the user sees what was touched. */
    private static void markFace(int[] argb, int width, int height, FaceBlendCore.FaceBox face) {
        int radiusX = (int) (face.width() * 0.62);
        int radiusY = (int) (face.height() * 0.72);
        int cx = face.centerX();
        int cy = face.centerY();
        for (int angle = 0; angle < 3600; angle++) {
            double rad = Math.toRadians(angle / 10.0);
            int x = (int) Math.round(cx + Math.cos(rad) * radiusX);
            int y = (int) Math.round(cy + Math.sin(rad) * radiusY);
            if (x >= 0 && x < width && y >= 0 && y < height) {
                argb[y * width + x] = 0xFF00C853;
            }
        }
    }

    private static void drawBox(Bitmap bitmap, FaceBlendCore.FaceBox face, int color) {
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(2f, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 200f));
        canvas.drawRect(face.left, face.top, face.right, face.bottom, paint);
    }
}
