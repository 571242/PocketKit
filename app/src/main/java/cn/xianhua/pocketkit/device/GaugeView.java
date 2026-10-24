package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 指南针表盘与水平仪气泡共用的一块画布。
 *
 * 两种模式外形不同，但都需要「圆盘 + 刻度 + 一个指示物」，
 * 分成两个 View 只会把同样的圆心计算写两遍。
 */
public final class GaugeView extends View {

    public static final int MODE_COMPASS = 0;
    public static final int MODE_BUBBLE = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needle = new Path();

    private int mode = MODE_COMPASS;
    private float heading;
    private float bubbleX;
    private float bubbleY;
    private boolean levelled;
    private int ink = Color.DKGRAY;
    private int rule = Color.LTGRAY;
    private int accent = Color.BLUE;

    public GaugeView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setColors(int ink, int rule, int accent) {
        this.ink = ink;
        this.rule = rule;
        this.accent = accent;
        invalidate();
    }

    public void setCompass(float headingDeg) {
        this.mode = MODE_COMPASS;
        this.heading = SensorMath.normalizeHeading(headingDeg);
        invalidate();
    }

    public void setBubble(float x, float y, boolean levelled) {
        this.mode = MODE_BUBBLE;
        this.bubbleX = x;
        this.bubbleY = y;
        this.levelled = levelled;
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = MeasureSpec.getSize(widthSpec);
        int want = (int) (220 * getResources().getDisplayMetrics().density);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : want;
        setMeasuredDimension(size, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(8);
        if (radius <= 0) {
            return;
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(rule);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setColor(ink);
        if (mode == MODE_COMPASS) {
            drawCompass(canvas, cx, cy, radius);
        } else {
            drawBubble(canvas, cx, cy, radius);
        }
    }

    private void drawCompass(Canvas canvas, float cx, float cy, float radius) {
        canvas.save();
        // 表盘随设备反向旋转，让盘上的「北」始终指向真实北方。
        canvas.rotate(-heading, cx, cy);

        paint.setStyle(Paint.Style.STROKE);
        for (int deg = 0; deg < 360; deg += 15) {
            boolean major = deg % 45 == 0;
            paint.setStrokeWidth(dp(major ? 2 : 1));
            paint.setColor(major ? ink : rule);
            double rad = Math.toRadians(deg - 90);
            float outer = radius;
            float inner = radius - dp(major ? 12 : 6);
            canvas.drawLine(
                    cx + (float) Math.cos(rad) * inner, cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer, cy + (float) Math.sin(rad) * outer, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(14));
        String[] labels = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(i * 90 - 90);
            float r = radius - dp(26);
            paint.setColor(i == 0 ? accent : ink);
            float x = cx + (float) Math.cos(rad) * r;
            float y = cy + (float) Math.sin(rad) * r - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(labels[i], x, y, paint);
        }
        canvas.restore();

        // 指针不跟着表盘转，它指向设备朝向。
        needle.reset();
        needle.moveTo(cx, cy - radius + dp(30));
        needle.lineTo(cx - dp(7), cy + dp(6));
        needle.lineTo(cx + dp(7), cy + dp(6));
        needle.close();
        paint.setColor(accent);
        canvas.drawPath(needle, paint);

        paint.setColor(rule);
        canvas.drawCircle(cx, cy, dp(4), paint);
    }

    private void drawBubble(Canvas canvas, float cx, float cy, float radius) {
        float gap = radius - dp(30);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(rule);
        canvas.drawCircle(cx, cy, gap, paint);
        canvas.drawCircle(cx, cy, gap / 2f, paint);
        canvas.drawLine(cx - gap, cy, cx + gap, cy, paint);
        canvas.drawLine(cx, cy - gap, cx, cy + gap, paint);

        float bx = cx + bubbleX * gap;
        float by = cy + bubbleY * gap;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(levelled ? accent : rule);
        canvas.drawCircle(bx, by, dp(14), paint);

        // 目标圈：气泡进入即判定为已调平。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(accent);
        canvas.drawArc(new RectF(cx - dp(14), cy - dp(14), cx + dp(14), cy + dp(14)), 0, 360, false, paint);
    }

    private float dp(float n) {
        return n * getResources().getDisplayMetrics().density;
    }
}
