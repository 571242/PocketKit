package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * 009 渐变壁纸：自己在画布上生成渐变图。
 *
 * <p>Generated locally with {@link Shader} gradients — no download, no network, and no copied
 * artwork. The presets are colour ramp definitions rather than links, so nothing here depends on a
 * third party's availability or licence.
 */
public final class GradientTool implements Tool {

    private final Host host;

    public GradientTool(Host host) {
        this.host = host;
    }

    /** Name plus the colour stops, so the UI and the renderer read from one place. */
    private static final String[] PRESET_NAMES = {
            "冷雾（浅底）", "深渍（暗底）", "青蓝", "暮紫", "琥珀", "松绿", "单色微光"
    };

    private static final int[][] PRESET_COLORS = {
            {0xFFEDF1EF, 0xFFC7D2CF, 0xFF9FB3B0},
            {0xFF0F1517, 0xFF16262A, 0xFF0A2F2C},
            {0xFF0E6E6B, 0xFF45B0A8, 0xFFB8E3DE},
            {0xFF2A1B3D, 0xFF5B3A7E, 0xFFD9A7C7},
            {0xFF3A1F0B, 0xFFB9660E, 0xFFF2C57C},
            {0xFF10301F, 0xFF2E7D4F, 0xFFA8D5B5},
            {0xFF182A2E, 0xFF182A2E, 0xFF182A2E},
    };

    @Override public void build() {
        host.note("本机用 Canvas 直接生成渐变壁纸，不下载任何图片、不联网、不使用他人素材。");

        final Spinner preset = host.select("配色", PRESET_NAMES);
        final Spinner sizePreset = host.select("尺寸",
                "1080×1920（手机竖屏）", "1440×2560（高分竖屏）", "1080×1080（方形）",
                "2160×3840（4K 竖屏）");
        final Spinner kind = host.select("渐变方式", "线性（对角）", "线性（纵向）", "径向");
        final EditText customColors = host.field("自定义颜色（可留空；填了则覆盖配色）", "#0E6E6B,#45B0A8,#B8E3DE");
        host.note("噪点可让渐变不那么像数码生成的，但会增加 JPEG 体积；作为壁纸 PNG 通常已足够。");
        final Spinner noise = host.select("噪点", "无", "轻微", "明显");
        final SeekBar angle = new SeekBar(host.activity());
        angle.setMax(360);
        angle.setProgress(45);
        host.note("渐变角度：" + 45 + "°");
        final android.widget.TextView angleLabel = host.note("渐变角度：45°");
        angle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                angleLabel.setText("渐变角度：" + progress + "°");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        host.body().addView(angle, new android.widget.LinearLayout.LayoutParams(-1, -2));

        host.button("生成壁纸", () -> {
            final int[][] sizes = {{1080, 1920}, {1440, 2560}, {1080, 1080}, {2160, 3840}};
            final int[] size = sizes[sizePreset.getSelectedItemPosition()];
            final int presetIndex = preset.getSelectedItemPosition();
            final int kindIndex = kind.getSelectedItemPosition();
            final int noiseLevel = noise.getSelectedItemPosition();
            final int angleDegrees = angle.getProgress();

            int[] colors;
            String parseError = null;
            String custom = customColors.getText().toString().trim();
            if (!custom.isEmpty()) {
                try {
                    colors = parseHexList(custom);
                } catch (IllegalArgumentException ex) {
                    parseError = ex.getMessage();
                    colors = null;
                }
            } else {
                colors = PRESET_COLORS[Math.min(presetIndex, PRESET_COLORS.length - 1)];
            }
            if (parseError != null) {
                host.show("未完成：" + parseError);
                return;
            }
            final int[] ramp = colors;

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    Bitmap bitmap = render(size[0], size[1], ramp, kindIndex, angleDegrees, noiseLevel);
                    cancel.check();
                    byte[] png = ImageIo.encode(bitmap, ImgSizing.Format.PNG, 100);
                    bitmap.recycle();
                    prepared = MediaIo.scratch(host.activity(), ".png");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(png);
                    }
                    String name = "gradient-" + size[0] + "x" + size[1] + ".png";
                    String summary = "已生成壁纸 " + size[0] + "×" + size[1] + "，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length)
                            + "\n配色 " + (custom.isEmpty() ? PRESET_NAMES[presetIndex] : "自定义")
                            + "，" + kind.getSelectedItem() + "，角度 " + angleDegrees + "°。";
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置。");
                        host.createDocument(name, "image/png", target -> host.background(() -> {
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

        host.note("生成的 PNG 可直接设为壁纸。文字不会被渲染到图上，因此图像本身保持干净。");
    }

    /** Parses "#RRGGBB,#RRGGBB" into colours, rejecting anything malformed. */
    public static int[] parseHexList(String text) {
        String[] parts = text.split("[,，\\s]+");
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (!p.startsWith("#")) {
                p = "#" + p;
            }
            if (!p.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("颜色「" + part.trim()
                        + "」格式不对，请用 #RRGGBB 的六位十六进制写法，例如 #45B0A8。多个颜色用逗号分隔。");
            }
            colors.add(0xFF000000 | Integer.parseInt(p.substring(1), 16));
        }
        if (colors.size() < 2) {
            throw new IllegalArgumentException("至少需要两个颜色才能形成渐变，例如 #0E6E6B,#45B0A8");
        }
        if (colors.size() > 6) {
            throw new IllegalArgumentException("颜色最多 6 个，当前填了 " + colors.size() + " 个");
        }
        int[] out = new int[colors.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = colors.get(i);
        }
        return out;
    }

    /** Draws the gradient, optionally with noise, and returns the finished bitmap. */
    Bitmap render(int width, int height, int[] colors, int kind, int angleDegrees, int noiseLevel) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader shader;
        if (kind == 2) {
            shader = new RadialGradient(width / 2f, height / 2f, Math.max(width, height) * 0.72f,
                    colors, evenPositions(colors.length), Shader.TileMode.CLAMP);
        } else {
            double radians = Math.toRadians(angleDegrees);
            float cx = width / 2f;
            float cy = height / 2f;
            float half = (float) (Math.abs(Math.cos(radians)) * width / 2 + Math.abs(Math.sin(radians)) * height / 2);
            float dx = (float) (Math.cos(radians) * half);
            float dy = (float) (Math.sin(radians) * half);
            shader = new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                    colors, evenPositions(colors.length), Shader.TileMode.CLAMP);
        }
        paint.setShader(shader);
        canvas.drawRect(0, 0, width, height, paint);

        if (noiseLevel > 0) {
            addNoise(bitmap, noiseLevel == 1 ? 6 : 14);
        }
        return bitmap;
    }

    /** Evenly spaced stops across 0..1, so three colours give three equal bands. */
    public static float[] evenPositions(int count) {
        float[] positions = new float[count];
        for (int i = 0; i < count; i++) {
            positions[i] = count == 1 ? 0f : i / (float) (count - 1);
        }
        return positions;
    }

    /**
     * Adds monochrome noise by nudging a subset of pixels.
     *
     * <p>A per-pixel {@code getPixel}/{@code setPixel} loop over a 4K image would be far too slow,
     * so the noise works on a bulk int array — one read, one write, one random walk.
     */
    static void addNoise(Bitmap bitmap, int strength) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        Random random = new Random(20261013L);
        int step = Math.max(1, pixels.length / 400_000);
        for (int i = 0; i < pixels.length; i += step) {
            int delta = random.nextInt(strength * 2 + 1) - strength;
            int c = pixels[i];
            int r = clampByte(((c >> 16) & 0xFF) + delta);
            int g = clampByte(((c >> 8) & 0xFF) + delta);
            int b = clampByte((c & 0xFF) + delta);
            pixels[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
