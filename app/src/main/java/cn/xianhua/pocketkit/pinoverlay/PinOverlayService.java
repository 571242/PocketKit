package cn.xianhua.pocketkit.pinoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import cn.xianhua.pocketkit.PinImageActivity;
import cn.xianhua.pocketkit.ToolUi;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-started pinned reference image: exactly one image in its own overlay window, plus a small
 * control bar that never covers the screen. Never reads the screen, never injects input.
 */
public final class PinOverlayService extends Service {
    public static final String ACTION_STOP = "cn.xianhua.pocketkit.pin.ACTION_STOP";
    public static final String EXTRA_PATH = "path";
    private static final String CHANNEL = "pin_overlay";
    private static final int NOTIFICATION_ID = 993;
    private int PANEL = Color.rgb(242, 245, 250), INK = Color.rgb(23, 45, 77), EDGE = Color.rgb(34, 100, 220);
    private static final int BLOCK_DP = 64, MIN_DP = 96, BAR_MAX_DP = 340;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WindowManager manager;
    private FrameLayout frame;
    private ImageView image;
    private WindowManager.LayoutParams picParams;
    private LinearLayout bar;
    private WindowManager.LayoutParams barParams;
    private Button transparency;
    private Bitmap bitmap;
    private float alpha = 1f;
    private boolean collapsed;
    private int width, height;          // on-screen size right now
    private int keptWidth, keptHeight;  // size to restore when expanding again
    private volatile boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        updateColors();
        manager = getSystemService(WindowManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "贴屏参考图", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("悬浮窗权限没开，参考图没有贴上；回应用里重新授权后再试");
            String path = intent == null ? null : intent.getStringExtra(EXTRA_PATH);
            if (path == null) { stopSelf(); return START_NOT_STICKY; }
            // One pin at a time: a second start replaces the image instead of stacking windows.
            clearPin();
            ensureBar();
            int side = PinGeometry.decodeSide(screenWidth(), screenHeight());
            File file = new File(path);
            worker.execute(() -> {
                Bitmap decoded;
                try {
                    decoded = PinImageLoader.decode(file, side);
                } catch (IOException | OutOfMemoryError | RuntimeException error) {
                    file.delete();
                    main.post(() -> {
                        if (destroyed) return;
                        Toast.makeText(this, message(error, "图片未能解码"), Toast.LENGTH_LONG).show();
                        stopSelf();
                    });
                    return;
                }
                file.delete();
                main.post(() -> {
                    if (destroyed) { decoded.recycle(); return; }
                    try { show(decoded); }
                    catch (RuntimeException error) {
                        decoded.recycle();
                        Toast.makeText(this, message(error, "参考图未能贴上"), Toast.LENGTH_LONG).show();
                        stopSelf();
                    }
                });
            });
        } catch (RuntimeException error) {
            Toast.makeText(this, message(error, "贴屏未启动"), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent close = PendingIntent.getService(this, NOTIFICATION_ID + 1,
                new Intent(this, PinOverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, PinImageActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("拾用 · 贴屏参考图")
                .setContentText("拖动移动，双指缩放；操控条调不透明度、大小、收起或关闭")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void show(Bitmap decoded) {
        bitmap = decoded;
        int[] size = PinGeometry.fit(decoded.getWidth(), decoded.getHeight(), maxWidth(), maxHeight());
        width = keptWidth = size[0];
        height = keptHeight = size[1];
        frame = new FrameLayout(this);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImageBitmap(decoded);
        image.setAlpha(alpha);
        image.setContentDescription("贴屏参考图。单指拖动移动，双指捏合缩放；收起成小块后轻点展开");
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        // A thin outline is the only decoration: it shows the pin's touch area without hiding pixels.
        // Foreground, not background: the ImageView covers a background stroke completely.
        frame.setForeground(ToolUi.fill(Color.TRANSPARENT, dp(10), EDGE, dp(1)));
        picParams = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        picParams.gravity = Gravity.TOP | Gravity.START;
        int[] at = PinGeometry.place(width, height, screenWidth(), screenHeight());
        picParams.x = at[0];
        picParams.y = at[1];
        attachTouch();
        manager.addView(frame, picParams);
        refresh();
    }

    private void attachTouch() {
        final ScaleGestureDetector scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            int baseWidth, baseHeight;
            float factor;
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (collapsed || destroyed) return false;
                baseWidth = width;
                baseHeight = height;
                factor = 1f;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (destroyed) return true;
                factor *= detector.getScaleFactor();
                int[] size = PinGeometry.scaled(baseWidth, baseHeight, factor, dp(MIN_DP), maxWidth(), maxHeight());
                resize(size[0], size[1]);
                return true;
            }
        });
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        frame.setOnTouchListener(new View.OnTouchListener() {
            float fromX, fromY; int startX, startY; boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                scale.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        fromX = event.getRawX(); fromY = event.getRawY();
                        startX = picParams.x; startY = picParams.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // Two fingers belong to the pinch; dragging at the same time fights it.
                        if (scale.isInProgress() || event.getPointerCount() > 1) return true;
                        if (Math.abs(event.getRawX() - fromX) > slop || Math.abs(event.getRawY() - fromY) > slop) moved = true;
                        if (!moved) return true;
                        int[] at = PinGeometry.onScreen(startX + Math.round(event.getRawX() - fromX),
                                startY + Math.round(event.getRawY() - fromY), width, height,
                                screenWidth(), screenHeight(), dp(BLOCK_DP));
                        picParams.x = at[0];
                        picParams.y = at[1];
                        layout();
                        return true;
                    case MotionEvent.ACTION_UP:
                        // The block is the only way back to full size, so a plain tap must expand it.
                        if (!moved && !scale.isInProgress() && collapsed) expand();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void resize(int nextWidth, int nextHeight) {
        int centerX = picParams.x + width / 2, centerY = picParams.y + height / 2;
        width = keptWidth = nextWidth;
        height = keptHeight = nextHeight;
        picParams.width = nextWidth;
        picParams.height = nextHeight;
        int[] at = PinGeometry.onScreen(centerX - nextWidth / 2, centerY - nextHeight / 2, nextWidth, nextHeight,
                screenWidth(), screenHeight(), dp(BLOCK_DP));
        picParams.x = at[0];
        picParams.y = at[1];
        layout();
    }

    /** The window itself shrinks, so the touch area stops covering the app underneath. */
    private void collapse() {
        if (collapsed || frame == null) return;
        int block = dp(BLOCK_DP);
        int[] at = PinGeometry.collapse(picParams.x, picParams.y, width, height, block);
        collapsed = true;
        picParams.width = picParams.height = block;
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int[] fixed = PinGeometry.onScreen(at[0], at[1], block, block, screenWidth(), screenHeight(), block);
        picParams.x = fixed[0];
        picParams.y = fixed[1];
        layout();
        refresh();
    }

    private void expand() {
        if (!collapsed || frame == null) return;
        // The kept size may predate a rotation, so it is clamped through the current screen.
        int[] size = PinGeometry.scaled(keptWidth, keptHeight, 1f, dp(MIN_DP), maxWidth(), maxHeight());
        int centerX = picParams.x + width / 2, centerY = picParams.y + height / 2;
        collapsed = false;
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        picParams.width = width = keptWidth = size[0];
        picParams.height = height = keptHeight = size[1];
        int[] at = PinGeometry.onScreen(centerX - width / 2, centerY - height / 2, width, height,
                screenWidth(), screenHeight(), dp(BLOCK_DP));
        picParams.x = at[0];
        picParams.y = at[1];
        layout();
        refresh();
    }

    private void ensureBar() {
        if (bar != null) return;
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(ToolUi.fill(PANEL, dp(20), INK, dp(1)));
        transparency = barButton(this::cycleAlpha);
        barButton(this::shrink);
        barButton(this::grow);
        Button toggle = barButton(this::toggle);
        toggle.setText("收起");
        barButton(this::stopSelf);
        barParams = new WindowManager.LayoutParams(Math.min(dp(BAR_MAX_DP), screenWidth() - dp(16)), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        barParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barParams.y = dp(88);
        manager.addView(bar, barParams);
        refresh();
    }

    private void updateColors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        PANEL = dark ? 0xFF1B293E : 0xFFF2F5FA;
        INK = dark ? 0xFFE7EEFC : 0xFF172D4D;
        EDGE = dark ? 0xFF9BBCFF : 0xFF2264DC;
        if (bar != null) {
            bar.setBackground(ToolUi.fill(PANEL, dp(20), INK, dp(1)));
            for (int i = 0; i < bar.getChildCount(); i++) ((Button) bar.getChildAt(i)).setTextColor(INK);
        }
    }

    private Button barButton(Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setTextSize(12);
        view.setTextColor(INK);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(14), Color.TRANSPARENT, 0));
        view.setOnClickListener(v -> {
            try { action.run(); }
            catch (RuntimeException error) { Toast.makeText(this, message(error, "操作未完成"), Toast.LENGTH_SHORT).show(); }
        });
        bar.addView(view, new LinearLayout.LayoutParams(0, dp(46), 1));
        return view;
    }

    /** Labels only: the same bar serves before and after the bitmap arrives. */
    private void refresh() {
        if (bar == null || bar.getChildCount() < 5) return;
        String percent = PinGeometry.percent(alpha) + "%";
        transparency.setText(percent);
        transparency.setContentDescription("不透明度 " + percent + "，轻点切换到下一档");
        ((Button) bar.getChildAt(1)).setText("缩小");
        ((Button) bar.getChildAt(2)).setText("放大");
        ((Button) bar.getChildAt(3)).setText(collapsed ? "展开" : "收起");
        ((Button) bar.getChildAt(4)).setText("关闭");
    }

    private void cycleAlpha() {
        alpha = PinGeometry.nextAlpha(alpha);
        if (image != null) image.setAlpha(alpha);
        refresh();
    }

    private void shrink() { stepSize(.82f); }

    private void grow() { stepSize(1.22f); }

    private void stepSize(float factor) {
        if (frame == null) return;
        // A collapsed pin is a fixed block; a size button brings it back first.
        if (collapsed) { expand(); return; }
        int[] size = PinGeometry.scaled(width, height, factor, dp(MIN_DP), maxWidth(), maxHeight());
        resize(size[0], size[1]);
    }

    private void toggle() {
        if (collapsed) expand(); else collapse();
    }

    private void layout() {
        if (frame != null && frame.isAttachedToWindow()) {
            try { manager.updateViewLayout(frame, picParams); } catch (RuntimeException gone) { /* window already gone */ }
        }
    }

    /** Rotation or a changed visible area must never leave the pin or the bar out of reach. */
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateColors();
        if (bar != null && barParams != null) {
            barParams.width = Math.min(dp(BAR_MAX_DP), screenWidth() - dp(16));
            try { manager.updateViewLayout(bar, barParams); } catch (RuntimeException gone) { /* gone */ }
        }
        if (frame == null) return;
        int block = dp(BLOCK_DP);
        if (collapsed) {
            picParams.width = picParams.height = block;
            int[] at = PinGeometry.onScreen(picParams.x, picParams.y, block, block,
                    screenWidth(), screenHeight(), block);
            picParams.x = at[0];
            picParams.y = at[1];
        } else {
            int[] size = PinGeometry.scaled(keptWidth, keptHeight, 1f, dp(MIN_DP), maxWidth(), maxHeight());
            width = keptWidth = size[0];
            height = keptHeight = size[1];
            picParams.width = width;
            picParams.height = height;
            int[] at = PinGeometry.onScreen(picParams.x, picParams.y, width, height,
                    screenWidth(), screenHeight(), dp(BLOCK_DP));
            picParams.x = at[0];
            picParams.y = at[1];
        }
        layout();
    }

    private void clearPin() {
        if (frame != null && frame.isAttachedToWindow()) {
            try { manager.removeView(frame); } catch (RuntimeException gone) { /* gone */ }
        }
        frame = null;
        image = null;
        collapsed = false;
        alpha = 1f;
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
    }

    private int maxWidth() { return Math.max(dp(MIN_DP), screenWidth() - dp(8)); }

    private int maxHeight() { return Math.max(dp(MIN_DP), screenHeight() - dp(160)); }

    private int screenWidth() { return metrics().widthPixels; }

    private int screenHeight() { return metrics().heightPixels; }

    private DisplayMetrics metrics() {
        DisplayMetrics value = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(value);
        return value;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String message(Throwable error, String fallback) {
        String text = error == null ? null : error.getMessage();
        return text == null || text.trim().isEmpty() ? fallback : text;
    }

    @Override public void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        clearPin();
        if (bar != null && bar.isAttachedToWindow()) {
            try { manager.removeView(bar); } catch (RuntimeException gone) { /* gone */ }
        }
        bar = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
