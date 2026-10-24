package cn.xianhua.pocketkit.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.xianhua.pocketkit.DeviceToolsActivity;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.core.TicketTiming;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** The user-started countdown belongs to a service, not a purchase-page Activity. */
public final class TicketOverlayService extends Service {
    private static final String CHANNEL = "ticket_countdown";
    private static final int NOTIFICATION_ID = 710;
    private static final String STOP = "cn.xianhua.pocketkit.ticket.STOP";
    private static volatile boolean running;
    private static volatile long activeTarget;
    private static volatile String lastError;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private TextView clockText, remainingText, targetText;
    private boolean vibrationEnabled;
    private boolean automatic;

    public static void startAutomatic(Context context, long target, boolean vibration) {
        lastError = null;
        context.startForegroundService(new Intent(context, TicketOverlayService.class)
                .putExtra("target", target).putExtra("vibrate", vibration).putExtra("automatic", true));
    }
    private TicketTiming.Alerts alerts;

    public static boolean isRunning() { return running; }
    public static long target() { return activeTarget; }
    public static String error() { return lastError; }

    public static void start(Context context, long target, boolean vibration) {
        lastError = null;
        context.startForegroundService(new Intent(context, TicketOverlayService.class)
                .putExtra("target", target).putExtra("vibrate", vibration));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, TicketOverlayService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        timeFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "开抢倒计时", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        long target = intent.getLongExtra("target", 0);
        try {
            Notification notification = notification(target);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("悬浮窗权限未开启，请返回助手重新授权。");
            if (target <= TicketClock.now()) throw new IllegalStateException("开抢时间已经过去，请重新设置。");
            if (overlay == null) createOverlay();
            activeTarget = target;
            automatic = intent.getBooleanExtra("automatic", false);
            SimpleDateFormat targetFormat = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            targetFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            targetText.setText("目标 " + targetFormat.format(new Date(target)));
            vibrationEnabled = intent.getBooleanExtra("vibrate", true);
            alerts = new TicketTiming.Alerts();
            running = true;
            lastError = null;
            handler.removeCallbacks(tick);
            handler.post(tick);
        } catch (RuntimeException ex) {
            lastError = ex.getMessage() == null ? "悬浮倒计时未能启动，请检查系统权限。" : ex.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(long target) {
        Intent page = new Intent(this, DeviceToolsActivity.class).putExtra("tool_id", "071").putExtra("title", "抢票助手");
        PendingIntent open = PendingIntent.getActivity(this, 710, page, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close = PendingIntent.getService(this, 711, new Intent(this, TicketOverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("开抢倒计时运行中")
                .setContentText("北京时间 " + format.format(new Date(target)) + " · 点击返回，或停止提醒")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止提醒", close).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void createOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        overlay.setPadding(dp(12), dp(6), dp(8), dp(10));
        overlay.setBackground(ToolUi.fill(Color.rgb(17, 35, 38), dp(14), Color.rgb(101, 206, 193), dp(1)));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        targetText = text(11, Color.rgb(186, 210, 207));
        header.addView(targetText, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(Color.WHITE);
        close.setAllCaps(false);
        close.setPadding(0, 0, 0, 0);
        close.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(8), Color.TRANSPARENT, 0));
        close.setContentDescription("停止倒计时并关闭悬浮窗");
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        overlay.addView(header);
        remainingText = text(21, Color.rgb(255, 209, 102));
        remainingText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        remainingText.setSingleLine(true);
        remainingText.setAutoSizeTextTypeUniformWithConfiguration(13, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        overlay.addView(remainingText, new LinearLayout.LayoutParams(-1, dp(32)));
        clockText = text(12, Color.rgb(101, 206, 193));
        overlay.addView(clockText);
        TextView move = text(10, Color.rgb(160, 184, 183));
        move.setText("拖动移动 · 到点后自动关闭");
        overlay.addView(move);

        params = new WindowManager.LayoutParams(Math.min(dp(248), getResources().getDisplayMetrics().widthPixels - dp(16)),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        // Android 12 limits obscuring opacity for touches passing to another application's window.
        params.alpha = 0.8f;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(96);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private int windowX, windowY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX(); startY = event.getRawY();
                        windowX = params.x; windowY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = windowX + Math.round(event.getRawX() - startX);
                        params.y = windowY + Math.round(event.getRawY() - startY);
                        constrainPosition();
                        manager.updateViewLayout(overlay, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default: return true;
                }
            }
        });
        manager.addView(overlay, params);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void constrainPosition() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        params.x = Math.max(0, Math.min(params.x, Math.max(0, metrics.widthPixels - overlay.getWidth())));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, metrics.heightPixels - overlay.getHeight() - dp(32))));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || overlay == null) return;
            if (!Settings.canDrawOverlays(TicketOverlayService.this)) {
                lastError = "悬浮窗权限已撤销，提醒已停止。";
                stopSelf();
                return;
            }
            long now = TicketClock.now();
            long remaining = activeTarget - now;
            if (remaining < -60000) {
                stopSelf();
                return;
            }
            boolean keepScreen = automatic ? TicketTapService.isActive() : TicketTiming.keepScreenOn(remaining);
            int screenFlag = keepScreen ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0;
            int updatedFlags = (params.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) | screenFlag;
            if (params.flags != updatedFlags) {
                params.flags = updatedFlags;
                manager.updateViewLayout(overlay, params);
            }
            remainingText.setText(TicketTiming.remaining(remaining));
            clockText.setText(automatic ? TicketTapService.status() : "北京时间 " + timeFormat.format(new Date(now)));
            int stage = alerts.poll(remaining);
            if (stage >= 0 && vibrationEnabled) {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(stage == 3 ? 500 : 100 + stage * 40, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
            handler.postDelayed(this, remaining > 10000 ? 500 : remaining > 0 ? 32 : 500);
        }
    };

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay != null && overlay.isAttachedToWindow()) {
            constrainPosition();
            manager.updateViewLayout(overlay, params);
        }
    }

    @Override public void onDestroy() {
        running = false;
        activeTarget = 0;
        handler.removeCallbacksAndMessages(null);
        if (overlay != null && overlay.isAttachedToWindow()) manager.removeView(overlay);
        overlay = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
