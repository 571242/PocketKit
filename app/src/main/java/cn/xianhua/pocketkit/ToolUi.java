package cn.xianhua.pocketkit;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.TextView;

/** Small native visual language shared by the home page and tool forms. */
public final class ToolUi {
    private ToolUi() {}
    public static int dp(android.content.Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    public static GradientDrawable fill(int color, float radius, int strokeColor, int strokeWidth) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(radius); if (strokeWidth > 0) shape.setStroke(strokeWidth, strokeColor); return shape; }
    public static RippleDrawable ripple(android.content.Context c, int color, float radius, int strokeColor, int strokeWidth) { return new RippleDrawable(ColorStateList.valueOf(Color.luminance(color) > 0.3f ? Color.argb(28, 0, 0, 0) : Color.argb(48, 255, 255, 255)), fill(color, radius, strokeColor, strokeWidth), fill(Color.WHITE, radius, Color.TRANSPARENT, 0)); }
    public static void text(TextView view, int color, float size) { view.setTextColor(color); view.setTextSize(size); view.setIncludeFontPadding(true); }
    public static void touch(View view, int minimumHeight) { view.setMinimumHeight(minimumHeight); view.setFocusable(true); }
}

