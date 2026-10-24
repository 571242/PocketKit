package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 004 取色器：从图片里点选取色。
 *
 * <p>Deliberately image-based rather than camera-based: no camera permission, no preview surface,
 * and the colour a user reads is exactly a pixel of a picture they chose. The one real difficulty
 * is that an {@link ImageView} with {@code FIT_CENTER} scales and letterboxes the bitmap, so a tap
 * coordinate must be mapped back through the view's actual matrix — otherwise a tap lands on the
 * wrong pixel whenever the aspect ratio does not match the view.
 */
public final class ColorPickerTool implements Tool {

    private final Host host;

    public ColorPickerTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("打开一张图片，点击画面任意位置取色。也可以直接输入坐标取色。全程在本机完成。");

        final Bitmap[] image = {null};
        final ImageView[] view = {null};
        final android.widget.TextView[] readout = {null};

        host.button("选择图片", () -> host.pickDocument("image/*", uri -> host.background(() -> {
            try {
                Bitmap decoded = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, new Cancel());
                host.post(() -> {
                    Bitmap previous = image[0];
                    image[0] = decoded;
                    if (view[0] != null) {
                        view[0].setImageBitmap(decoded);
                    }
                    if (readout[0] != null) {
                        readout[0].setText("图片已载入：" + decoded.getWidth() + "×" + decoded.getHeight()
                                + "\n点击图片取色。");
                    }
                    if (previous != null && !previous.isRecycled()) {
                        previous.recycle();
                    }
                });
            } catch (Exception ex) {
                host.post(() -> host.fail(ex));
            }
        })));

        // The image sits in a fixed-height frame so a tap coordinate is stable while scrolling.
        FrameLayout frame = new FrameLayout(host.activity());
        frame.setBackgroundColor(Color.parseColor("#33000000"));
        final ImageView iv = new ImageView(host.activity());
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        int height = host.dp(260);
        host.body().addView(frame, new LinearLayout.LayoutParams(-1, height));
        view[0] = iv;

        final TextView readoutView = host.note("尚未载入图片。");
        readout[0] = readoutView;

        iv.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return true;
            }
            Bitmap bitmap = image[0];
            if (bitmap == null) {
                readoutView.setText("请先选择一张图片。");
                return true;
            }
            int[] pixel = mapTapToPixel(iv, bitmap, event.getX(), event.getY());
            if (pixel == null) {
                readoutView.setText("这一点在图片之外（留白区域）。请点在画面上。");
                return true;
            }
            int color = bitmap.getPixel(pixel[0], pixel[1]);
            readoutView.setText(describe(color, pixel[0], pixel[1]));
            return true;
        });

        final android.widget.EditText coords = host.field("直接输入坐标取色（x,y）", "例如 120,340");
        host.button("按坐标取色", () -> {
            Bitmap bitmap = image[0];
            if (bitmap == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            String text = coords.getText().toString().trim();
            String[] parts = text.split("[,，\\s]+");
            if (parts.length < 2) {
                host.show("未完成：请按「x,y」格式填写坐标，例如 120,340");
                return;
            }
            int x;
            int y;
            try {
                x = Integer.parseInt(parts[0].trim());
                y = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                host.show("未完成：坐标必须是整数，例如 120,340");
                return;
            }
            if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
                host.show("未完成：坐标超出图片范围。这张图是 "
                        + bitmap.getWidth() + "×" + bitmap.getHeight() + "，x 应小于 "
                        + bitmap.getWidth() + "，y 应小于 " + bitmap.getHeight() + "。");
                return;
            }
            int color = bitmap.getPixel(x, y);
            readoutView.setText(describe(color, x, y));
            host.show(describe(color, x, y));
        });

        host.note("说明：取到的是图片原始像素的颜色。若图片经过缩放显示，点击位置会按显示矩阵换算回原图像素，"
                + "因此取色与屏幕缩放无关。JPEG 是有损格式，相邻像素颜色可能略有差异。");
    }

    /**
     * Maps a view-space tap to bitmap pixel coordinates through the ImageView's own matrix.
     *
     * <p>{@code imageMatrix} holds the scale and translation the view applied, so inverting it is
     * the exact answer — re-deriving the fit maths by hand would drift from the framework's
     * rounding. Returns null when the tap is inside the view but outside the drawn image
     * (the letterbox bands), which is why they are reported rather than silently clamped.
     */
    public static int[] mapTapToPixel(ImageView view, Bitmap bitmap, float viewX, float viewY) {
        Matrix matrix = new Matrix();
        if (!view.getImageMatrix().invert(matrix)) {
            // No transform reported: the view must be drawing 1:1 (FIT_XY or an exact-size bitmap).
            int x = (int) viewX;
            int y = (int) viewY;
            return x >= 0 && y >= 0 && x < bitmap.getWidth() && y < bitmap.getHeight()
                    ? new int[]{x, y} : null;
        }
        float[] point = new float[]{viewX, viewY};
        matrix.mapPoints(point);
        int x = Math.round(point[0]);
        int y = Math.round(point[1]);
        if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
            return null;
        }
        return new int[]{x, y};
    }

    /** Colour in the forms a designer actually needs: hex for the web, and the RGB triple. */
    public static String describe(int color, int x, int y) {
        // Bit extraction rather than Color.red/green/blue: it is the same answer, it is faster in a
        // tap handler, and it keeps this method runnable off-device for the self-test.
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        String hex = String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b);
        double[] hsl = toHsl(r, g, b);
        return "坐标 (" + x + ", " + y + ")\n"
                + "HEX  " + hex + "\n"
                + "RGB  " + r + ", " + g + ", " + b + "\n"
                + "HSL  " + Math.round(hsl[0]) + "°, " + Math.round(hsl[1] * 100) + "%, "
                + Math.round(hsl[2] * 100) + "%\n"
                + "关于图片的原始像素，未经任何压缩转换。";
    }

    /** HSL where h is 0..360 and s/l are 0..1. */
    public static double[] toHsl(int r, int g, int b) {
        double rf = r / 255.0;
        double gf = g / 255.0;
        double bf = b / 255.0;
        double max = Math.max(rf, Math.max(gf, bf));
        double min = Math.min(rf, Math.min(gf, bf));
        double l = (max + min) / 2;
        double h;
        double s;
        if (max == min) {
            h = 0;
            s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rf) {
                h = (gf - bf) / d + (gf < bf ? 6 : 0);
            } else if (max == gf) {
                h = (bf - rf) / d + 2;
            } else {
                h = (rf - gf) / d + 4;
            }
            h *= 60;
        }
        return new double[]{h, s, l};
    }
}
