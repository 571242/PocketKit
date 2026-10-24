package cn.xianhua.pocketkit.local;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 手持弹幕（054）与时间屏幕（062）的全屏显示。
 *
 * 用全屏 Dialog 而不是新建 Activity：不用改 Manifest，也不占用宿主的界面状态，
 * 关掉就回到原来的输入框。只改这一个窗口的亮度，不动系统设置。
 */
final class DanmakuScreen {

    private DanmakuScreen() {
    }

    /** 全屏弹幕。 */
    static void show(Activity host, String text, int sizeIndex) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        float sp = sizeIndex == 0 ? 56f : sizeIndex == 1 ? 88f : 128f;
        TextView v = new TextView(host);
        v.setText(text.trim());
        v.setTextSize(sp);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(24, 24, 24, 24);

        LinearLayout root = new LinearLayout(host);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.addView(v, new LinearLayout.LayoutParams(-1, -2));

        Dialog d = fullScreen(host, root);
        // 点一下屏幕就退出，不需要找返回键
        root.setOnClickListener(x -> d.dismiss());
        d.show();
    }

    /** 全屏大字时钟。 */
    static void showClock(Activity host) {
        TextView time = new TextView(host);
        time.setTextSize(72f);
        time.setTextColor(Color.WHITE);
        time.setGravity(Gravity.CENTER);

        TextView date = new TextView(host);
        date.setTextSize(20f);
        date.setTextColor(Color.parseColor("#93A7A9"));
        date.setGravity(Gravity.CENTER);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.addView(time, new LinearLayout.LayoutParams(-1, -2));
        root.addView(date, new LinearLayout.LayoutParams(-1, -2));

        Dialog d = fullScreen(host, root);
        root.setOnClickListener(x -> d.dismiss());

        Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                time.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d",
                        now.getHour(), now.getMinute(), now.getSecond()));
                date.setText(now.toLocalDate() + "  "
                        + cn.xianhua.pocketkit.core.DateDiff
                        .weekdayName(now.getDayOfWeek().getValue()));
                handler.postDelayed(this, 1000);
            }
        };
        d.setOnDismissListener(x -> handler.removeCallbacks(tick));
        d.show();
        tick.run();
    }

    /** 全屏、黑底、常亮的 Dialog。窗口销毁时亮度随窗口一起消失，不需要恢复。 */
    private static Dialog fullScreen(Activity host, ViewGroup content) {
        Dialog d = new Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = 1.0f;
            w.setAttributes(lp);
        }
        d.setContentView(content);
        d.setCanceledOnTouchOutside(true);
        return d;
    }
}
