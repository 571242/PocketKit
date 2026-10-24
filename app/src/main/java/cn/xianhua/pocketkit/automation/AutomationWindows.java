package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/** Use a trusted accessibility window when connected; never obscure injected touches with an app overlay. */
final class AutomationWindows {
    final WindowManager manager;
    private final int type;
    private final float density;
    AutomationWindows(Context context) {
        AccessibilityService service = AutomationRuntime.service();
        manager = (WindowManager) (service == null ? context : service).getSystemService(Context.WINDOW_SERVICE);
        type = service == null ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        density = context.getResources().getDisplayMetrics().density;
    }
    int dp(int value) { return Math.round(value * density); }
    DisplayMetrics metrics() { DisplayMetrics value = new DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(value); return value; }
    WindowManager.LayoutParams fullscreen() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return p;
    }
    WindowManager.LayoutParams bar() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Math.min(dp(304), metrics().widthPixels - dp(16)), -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START; p.x = dp(8); p.y = dp(100);
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) p.alpha = .8f;
        return p;
    }
    void draggable(View handle, View window, WindowManager.LayoutParams p) { draggable(handle, window, p, null, null); }
    /**
     * Drag with optional edge snap on a real move and optional long press. The touch listener consumes DOWN, so a
     * View long-click listener would never fire; the hold timer lives here, and a fired hold suppresses the click.
     */
    void draggable(View handle, View window, WindowManager.LayoutParams p, Runnable onSnap, Runnable onLongPress) {
        final Hold hold = onLongPress == null ? null : new Hold(onLongPress);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int wx, wy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x=event.getRawX(); y=event.getRawY(); wx=p.x; wy=p.y; moved=false;
                        if (hold != null) { hold.fired = false; v.postDelayed(hold, ViewConfiguration.getLongPressTimeout()); }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX()-x)>dp(8)||Math.abs(event.getRawY()-y)>dp(8)) { moved=true; clear(v, hold); }
                        DisplayMetrics m=metrics();
                        p.x=Math.max(0, Math.min(m.widthPixels-window.getWidth(), wx+Math.round(event.getRawX()-x)));
                        p.y=Math.max(dp(24), Math.min(m.heightPixels-window.getHeight()-dp(24), wy+Math.round(event.getRawY()-y)));
                        if (window.isAttachedToWindow()) manager.updateViewLayout(window,p);
                        return true;
                    case MotionEvent.ACTION_UP:
                        clear(v, hold);
                        if (hold != null && hold.fired) return true;
                        if (!moved) { v.performClick(); return true; }
                        if (onSnap != null && window.isAttachedToWindow()) onSnap.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL: clear(v, hold); return true;
                    default: return true;
                }
            }
        });
    }
    /** Snap zone: 0 left edge, 1 right edge, -1 released away from both. */
    static int edgeFor(int x, int windowWidth, int screenWidth) {
        int zone = Math.max(1, Math.round(screenWidth * .1f));
        if (x <= zone) return 0;
        if (x + windowWidth >= screenWidth - zone) return 1;
        return -1;
    }
    /** Nearest edge by window centre; used when a wide window collapses into an edge ball. */
    static int snapEdge(int x, int windowWidth, int screenWidth) { return x + windowWidth / 2 < screenWidth / 2 ? 0 : 1; }
    static int edgeX(int edge, int windowWidth, int screenWidth) { return edge == 1 ? Math.max(0, screenWidth - windowWidth) : 0; }
    /** Visible-area clamp; an oversized window lands on the smaller bound instead of off screen. */
    static int clampWithin(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    void remove(View view) { if (view != null && view.isAttachedToWindow()) try { manager.removeView(view); } catch (IllegalArgumentException removed) { /* The accessibility window can be removed by the system first. */ } }
    private static void clear(View view, Hold hold) { if (hold != null) view.removeCallbacks(hold); }
    private static final class Hold implements Runnable {
        final Runnable action; boolean fired;
        Hold(Runnable action) { this.action = action; }
        public void run() { fired = true; action.run(); }
    }
}
