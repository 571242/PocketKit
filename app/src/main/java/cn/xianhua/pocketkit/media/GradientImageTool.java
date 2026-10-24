package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 008 渐变图制作：任意尺寸的渐变图，带可选的叠加形状与网格。
 *
 * <p>Separate from 009 (wallpaper) because the jobs differ: 009 produces a phone wallpaper from a
 * curated palette, while this one is a general drawing surface for a gradient background — free
 * dimensions, several gradient geometries, and optional shape overlays. Both share the same
 * {@link GradientTool} colour parsing so the two screens cannot disagree about hex syntax.
 *
 * <p>Everything is drawn locally with {@link Shader}; nothing is downloaded.
 */
public final class GradientImageTool implements Tool {

    private final Host host;

    public GradientImageTool(Host host) {
        this.host = host;
    }

    private static final String[] PRESET_NAMES = {
            "两色：青→浅青", "两色：暗→亮", "三色：冷雾", "三色：日落",
            "四色：光谱", "单色深→浅"
    };

    private static final int[][] PRESET_COLORS = {
            {0xFF0E6E6B, 0xFFB8E3DE},
            {0xFF0F1517, 0xFFE3ECEA},
            {0xFFEDF1EF, 0xFFB8C9C6, 0xFF7E9A97},
            {0xFF3A1F0B, 0xFFB9660E, 0xFFF2C57C},
            {0xFF2E7D4F, 0xFF1565C0, 0xFF6A1B9A, 0xFFD32F2F},
            {0xFF182A2E, 0xFF8FA8AB},
    };

    @Override public void build() {
        host.note("在本机画布上生成渐变图。可以做背景图、配图底、封面底纹。不联网、不下载素材。");

        final Spinner preset = host.select("配色", PRESET_NAMES);
        final EditText custom = host.field("自定义颜色（可留空；填了则覆盖配色，2~6 个）",
                "#0E6E6B,#45B0A8,#B8E3DE");
        final EditText width = host.field("宽度（像素）", "例如 1080");
        final EditText height = host.field("高度（像素）", "例如 1350");
        final Spinner kind = host.select("渐变几何",
                "线性（按角度）", "径向（从中心向外）", "扫描（绕中心一圈）", "线性（四角对称）");
        final Spinner shape = host.select("叠加形状",
                "无", "同心圆（淡）", "细网格", "斜线纹理", "大色块（布莱恩风格）");

        final android.widget.TextView angleLabel = host.note("渐变角度：45°");
        final SeekBar angle = new SeekBar(host.activity());
        angle.setMax(360);
        angle.setProgress(45);
        angle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                angleLabel.setText("渐变角度：" + progress + "°");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        host.body().addView(angle, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final Spinner grain = host.select("颗粒", "无", "轻微", "明显");
        final Spinner outFormat = host.select("输出格式", "PNG（无损）", "JPEG");

        host.button("生成渐变图", () -> {
            int w = ImageCompressTool.parsePositive(width.getText().toString(), 0);
            int h = ImageCompressTool.parsePositive(height.getText().toString(), 0);
            if (w <= 0 || h <= 0) {
                host.show("未完成：请填写宽度和高度（像素），例如 1080 和 1350。");
                return;
            }
            if (w < 8 || h < 8) {
                host.show("未完成：宽高至少 8 像素。太小看不到渐变效果。");
                return;
            }
            // Guard the allocation: a wrong extra zero would otherwise ask for gigabytes.
            long pixels = (long) w * h;
            if (pixels > 40_000_000L) {
                host.show("未完成：尺寸过大（" + w + "×" + h + " = " + (pixels / 1_000_000)
                        + " 百万像素）。请把总像素控制在 4000 万以内，例如 6000×6000。");
                return;
            }

            int[] ramp;
            try {
                String text = custom.getText().toString().trim();
                ramp = text.isEmpty()
                        ? PRESET_COLORS[Math.min(preset.getSelectedItemPosition(), PRESET_COLORS.length - 1)]
                        : GradientTool.parseHexList(text);
            } catch (IllegalArgumentException ex) {
                host.show("未完成：" + ex.getMessage());
                return;
            }

            final int fw = w;
            final int fh = h;
            final int[] fRamp = ramp;
            final int kindIndex = kind.getSelectedItemPosition();
            final int shapeIndex = shape.getSelectedItemPosition();
            final int grainLevel = grain.getSelectedItemPosition();
            final int degrees = angle.getProgress();
            final ImgSizing.Format format = ImgSizing.Format.of((String) outFormat.getSelectedItem());
            final String paletteName = custom.getText().toString().trim().isEmpty()
                    ? PRESET_NAMES[preset.getSelectedItemPosition()] : "自定义";

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    Bitmap bitmap = render(fw, fh, fRamp, kindIndex, degrees, shapeIndex, grainLevel);
                    cancel.check();
                    byte[] data = ImageIo.encodeFlattened(bitmap, format, 95);
                    bitmap.recycle();
                    prepared = MediaIo.scratch(host.activity(), "." + format.ext);
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(data);
                    }
                    String name = "gradient-" + fw + "x" + fh + "." + format.ext;
                    String summary = "已生成渐变图 " + fw + "×" + fh + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(data.length)
                            + "\n配色：" + paletteName
                            + "；几何：" + kind.getSelectedItem()
                            + "；叠加：" + shape.getSelectedItem()
                            + (kindIndex == 0 || kindIndex == 3 ? "；角度 " + degrees + "°" : "");
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置。");
                        host.createDocument(name, ImageCompressTool.mimeOf(format), target ->
                                host.background(() -> {
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

        host.note("角度只对线性渐变有意义（径向与扫描忽略它）。叠加形状用半透明绘制，"
                + "因此不会盖掉底下的渐变，颜色仍然连续。");
    }

    /** Draws the gradient and the optional overlay. Kept separate from the UI so it is testable. */
    Bitmap render(int width, int height, int[] colors, int kind, int degrees, int shape, int grain) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] stops = GradientTool.evenPositions(colors.length);
        Shader shader;
        switch (kind) {
            case 1:
                shader = new RadialGradient(width / 2f, height / 2f,
                        (float) (Math.hypot(width, height) / 2.0), colors, stops, Shader.TileMode.CLAMP);
                break;
            case 2:
                shader = new SweepGradient(width / 2f, height / 2f, colors, stops);
                break;
            case 3:
                // Four-corner symmetry: the gradient runs along the diagonal and reflects back, so
                // opposite corners match without needing a separate mirror pass.
                double r = Math.toRadians(45);
                float half = (float) (Math.abs(Math.cos(r)) * width / 2 + Math.abs(Math.sin(r)) * height / 2);
                shader = new LinearGradient(width / 2f - half, height / 2f - half,
                        width / 2f + half, height / 2f + half, colors, stops, Shader.TileMode.MIRROR);
                break;
            default:
                double rad = Math.toRadians(degrees);
                float cx = width / 2f;
                float cy = height / 2f;
                float reach = (float) (Math.abs(Math.cos(rad)) * width / 2 + Math.abs(Math.sin(rad)) * height / 2);
                shader = new LinearGradient(cx - (float) (Math.cos(rad) * reach),
                        cy - (float) (Math.sin(rad) * reach),
                        cx + (float) (Math.cos(rad) * reach),
                        cy + (float) (Math.sin(rad) * reach),
                        colors, stops, Shader.TileMode.CLAMP);
                break;
        }
        paint.setShader(shader);
        canvas.drawRect(0, 0, width, height, paint);

        if (shape > 0) {
            drawOverlay(canvas, width, height, shape, colors);
        }
        if (grain > 0) {
            GradientTool.addNoise(bitmap, grain == 1 ? 5 : 12);
        }
        return bitmap;
    }

    /**
     * Overlays are drawn with a fixed low alpha so the gradient stays readable through them.
     * The ink colour adapts to the gradient's average brightness, otherwise a white grid vanishes
     * on a light gradient and a dark one vanishes on a dark gradient.
     */
    private static void drawOverlay(Canvas canvas, int width, int height, int shape, int[] colors) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean lightBase = averageLuma(colors) > 140;
        int ink = lightBase ? 0xFF000000 : 0xFFFFFFFF;
        switch (shape) {
            case 1: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ink);
                paint.setAlpha(38);
                paint.setStrokeWidth(Math.max(1f, Math.min(width, height) / 160f));
                int rings = 7;
                for (int i = 1; i <= rings; i++) {
                    float radius = Math.min(width, height) * 0.5f * i / rings;
                    canvas.drawCircle(width / 2f, height / 2f, radius, paint);
                }
                break;
            }
            case 2: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ink);
                paint.setAlpha(30);
                paint.setStrokeWidth(1f);
                int step = Math.max(16, Math.min(width, height) / 24);
                for (int x = step; x < width; x += step) {
                    canvas.drawLine(x, 0, x, height, paint);
                }
                for (int y = step; y < height; y += step) {
                    canvas.drawLine(0, y, width, y, paint);
                }
                break;
            }
            case 3: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ink);
                paint.setAlpha(34);
                paint.setStrokeWidth(Math.max(1f, Math.min(width, height) / 200f));
                int step = Math.max(24, Math.min(width, height) / 16);
                // Diagonal hatching drawn far enough outside the canvas to cover every corner.
                for (int offset = -height; offset < width + height; offset += step) {
                    canvas.drawLine(offset, 0, offset + height, height, paint);
                }
                break;
            }
            case 4: {
                // Large translucent blocks: a poster-like accent without introducing new colours.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ink);
                paint.setAlpha(26);
                float block = Math.min(width, height) * 0.42f;
                canvas.drawRect(-block * 0.15f, height - block, block, height, paint);
                canvas.drawRect(width - block, block * 0.12f, width + block * 0.1f, block * 1.1f, paint);
                paint.setAlpha(20);
                canvas.drawCircle(width * 0.72f, height * 0.34f, block * 0.34f, paint);
                break;
            }
            default:
                break;
        }
    }

    /** Average Rec.601 luma of the ramp, used to pick a visible overlay ink. */
    public static int averageLuma(int[] colors) {
        if (colors.length == 0) {
            return 0;
        }
        long total = 0;
        for (int c : colors) {
            total += (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
        }
        return (int) (total / colors.length);
    }
}
