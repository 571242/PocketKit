package cn.xianhua.pocketkit.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.core.TicketClickGuard;
import cn.xianhua.pocketkit.core.TicketClickPlan;
import java.util.ArrayList;

/**
 * 手动录制抢票点位：用户自己切到购票 App、自己翻页、自己点位置，这里只记录原始屏幕坐标。
 * 录制期间不点击、不滑动、不提交任何购票操作，也不声称抢票结果；停止、撤销、取消都由用户手动完成。
 */
public final class TicketRecorderService extends Service {
    private static final String CHANNEL = "ticket_record", STOP = "cn.xianhua.pocketkit.ticket.RECORD_STOP";
    private static final int NOTIFICATION_ID = 711, MAX_POINTS = 8, BAR_WIDTH = 268, FILL = -1, CHILD = -2;
    private static final int ACCENT = Color.rgb(101, 206, 193), INK = Color.rgb(230, 244, 242);
    private static final int MUTED = Color.rgb(160, 184, 183), WARN = Color.rgb(255, 209, 102);
    private static final int SURFACE = Color.rgb(17, 35, 38), PRESSED = Color.rgb(24, 52, 56);
    private static final String CANCEL_PICK = "已取消这次取点，控件已恢复。";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<TicketClickPlan.Point> points = new ArrayList<>();
    private final ArrayList<String> labels = new ArrayList<>();
    private WindowManager manager;
    private WindowManager.LayoutParams barParams;
    private LinearLayout bar;
    private TextView statusText;
    private Button closeButton, bindButton, pickButton, undoButton, saveButton;
    private PickLayer picker;
    private String packageName = "";
    private int recordedWidth, recordedHeight;
    private boolean screenPinned;

    public static void start(Context context) { context.startForegroundService(new Intent(context, TicketRecorderService.class)); }
    public static void stop(Context context) { context.stopService(new Intent(context, TicketRecorderService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "抢票点位录制", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("悬浮窗权限未开启，请先允许“显示在其他应用上层”。");
            TicketTapService.stop("开始录制点位，自动点击已停止"); // 别让自动手势污染录到的坐标。
            if (bar == null) createBar();
            if (!bar.isAttachedToWindow()) manager.addView(bar, barParams);
            refresh();
            toast(TicketTapService.isEnabled() ? "先切到购票App，再点“绑定当前App”。"
                    : "无障碍未开启，无法绑定App：请在系统设置中启用“拾用”的无障碍服务。");
        } catch (RuntimeException ex) {
            toast(ex.getMessage() == null ? "录制未能启动，请检查悬浮窗权限。" : ex.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent close = PendingIntent.getService(this, 711,
                new Intent(this, TicketRecorderService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("正在录制抢票点位")
                .setContentText("已记录 " + points.size() + " 个点 · 用悬浮栏取点，或在这里停止录制")
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止录制", close).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    /** 小控制栏：拖动行 + 取消 + 状态，两行按钮；录制期间关闭入口始终存在。 */
    private void createBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setPadding(dp(12), dp(6), dp(12), dp(10));
        bar.setBackground(ToolUi.fill(SURFACE, dp(16), ACCENT, dp(1)));
        bar.addView(label("点位录制 · 拖动本栏移动\n只记录坐标，不自动点击购票App", 11, MUTED));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        closeButton = button("取消", v -> closeBar());
        closeButton.setContentDescription("取消取点，或取消本次录制并关闭悬浮栏");
        closeButton.setTextColor(WARN);
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(72), dp(40)));
        statusText = label("", 11, INK);
        statusText.setMaxLines(4);
        header.addView(statusText, new LinearLayout.LayoutParams(0, CHILD, 1));
        bar.addView(header);
        bindButton = button("绑定当前App", v -> bindCurrent());
        pickButton = button("加一个点", v -> beginPick());
        undoButton = button("撤销", v -> undo());
        saveButton = button("保存", v -> save());
        bar.addView(buttonRow(bindButton, pickButton));
        bar.addView(buttonRow(undoButton, saveButton));
        barParams = new WindowManager.LayoutParams(
                Math.min(dp(BAR_WIDTH), getResources().getDisplayMetrics().widthPixels - dp(16)),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        barParams.alpha = 0.8f;
        barParams.gravity = Gravity.TOP | Gravity.START;
        barParams.x = dp(12);
        barParams.y = dp(120);
        bar.setOnTouchListener(drag); // 按钮自己消费点击，其余区域拖动整栏。
    }

    private void closeBar() {
        if (picker != null) { cancelPick(CANCEL_PICK); return; }
        toast("已取消录制，本次点位没有保存。");
        stopSelf();
    }

    private void bindCurrent() {
        if (!TicketTapService.isEnabled()) { toast("无障碍未开启：请在系统设置中启用“拾用”的无障碍服务，再回来绑定。"); return; }
        String foreground = TicketTapService.foregroundPackage();
        if (foreground == null || foreground.isEmpty()) { toast("读不到当前前台应用：请让购票App留在前台，再点“绑定当前App”。"); return; }
        String problem = reject(foreground);
        if (problem != null) { toast(problem); return; }
        if (!foreground.equals(packageName) && !points.isEmpty()) {
            resetPoints(); // 换绑到别的 App 后旧点位属于另一个界面，必须重新采。
            toast("目标App已换成 " + appLabel(foreground) + "，已清空原来的点位，请重新采点。");
        }
        packageName = foreground;
        refresh();
        toast("已绑定：" + appLabel(packageName));
    }

    /** 返回拒绝原因，null 表示可以绑定。 */
    private String reject(String candidate) {
        if (getPackageName().equals(candidate)) return "不能绑定“拾用”自己，请切到购票App再绑定。";
        // 系统设置/系统界面/各家桌面这些非购票包一律拒绝，策略与自动点击侧共用同一份判断。
        if (!TicketClickGuard.safePackage(candidate)) return "不能绑定系统设置／系统界面／桌面，请切到购票App再绑定。";
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null && candidate.equals(home.activityInfo.packageName)) {
            return "不能绑定桌面启动器，请切到购票App再绑定。";
        }
        try { getPackageManager().getApplicationInfo(candidate, 0); return null; }
        catch (PackageManager.NameNotFoundException ex) { return "读不到应用信息（" + candidate + "），未绑定。"; }
    }

    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException ex) { return pkg; }
    }

    private void beginPick() {
        if (packageName.isEmpty()) { toast("请先切到购票App，再点“绑定当前App”。"); return; }
        if (points.size() >= MAX_POINTS) { toast("最多记录 " + MAX_POINTS + " 个点，请先撤销或保存。"); return; }
        if (picker != null) return;
        String foreground = TicketTapService.foregroundPackage();
        if (foreground == null || foreground.isEmpty()) { toast("读不到当前前台应用：请确认购票App在前台且无障碍服务可用，再取点。"); return; }
        if (!packageName.equals(foreground)) {
            toast("当前前台是 " + appLabel(foreground) + "，与绑定的 " + appLabel(packageName)
                    + " 不一致。请切回购票App；要改绑定请重新点“绑定当前App”。");
            return;
        }
        if (!screenMatches()) {
            resetPoints();
            toast("屏幕方向或分辨率已变化，已清空原来的点位，请重新采点。");
            return;
        }
        handler.post(this::showPickLayer); // 不在按钮的输入派发里摘窗口。
    }

    private void resetPoints() {
        points.clear();
        labels.clear();
        screenPinned = false;
        refresh();
    }

    /** true 表示当前屏幕与首笔采点时一致；还没固定过就顺手固定。 */
    private boolean screenMatches() {
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        if (!screenPinned) {
            recordedWidth = metrics.widthPixels;
            recordedHeight = metrics.heightPixels;
            screenPinned = true;
            return true;
        }
        return recordedWidth == metrics.widthPixels && recordedHeight == metrics.heightPixels;
    }

    private void showPickLayer() {
        if (picker != null) return;
        if (!Settings.canDrawOverlays(this)) { toast("悬浮窗权限已失效，无法取点。"); return; }
        boolean barWasUp = bar != null && bar.isAttachedToWindow();
        try {
            if (barWasUp) manager.removeViewImmediate(bar);
            picker = new PickLayer();
            manager.addView(picker, pickParams());
            manager.addView(bar, barParams); // 悬浮栏压在选点层之上：拖动和取消入口始终在。
            refresh();
            toast("点一下要连点的位置：这一下只用于取点，不会点到下面的App。");
        } catch (RuntimeException ex) {
            // 选点层已加上而悬浮栏加失败时，绝不留下一张吃掉全部触摸的遮罩。
            dropPicker();
            try {
                if (barWasUp && bar != null && !bar.isAttachedToWindow()) manager.addView(bar, barParams);
            } catch (RuntimeException restoreFailure) {
                toast("悬浮权限不可用，录制已停止。");
                stopSelf();
                return;
            }
            refresh();
            toast("取点层未能显示：" + (ex.getMessage() == null ? "请重试。" : ex.getMessage()));
        }
    }

    private WindowManager.LayoutParams pickParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(FILL, FILL,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.alpha = 0.8f;
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        return params;
    }

    private void finishPick(int rawX, int rawY) {
        dropPicker();
        DisplayMetrics real = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(real);
        int x = Math.max(0, Math.min(rawX, real.widthPixels - 1));
        int y = Math.max(0, Math.min(rawY, real.heightPixels - 1));
        try { points.add(new TicketClickPlan.Point(x, y)); }
        catch (IllegalArgumentException ex) { refresh(); toast("这个坐标无效，未记录。"); return; }
        // 首点文案可能随开售状态变化，固定留空；后续点原样保留读到的字样，执行器要靠它严格匹配。
        String label = points.size() == 1 ? "" : TicketClickGuard.label(TicketTapService.textAt(x, y));
        labels.add(label);
        refresh();
        toast("已记录第 " + points.size() + " 点（" + x + ", " + y + "）"
                + (label.isEmpty() ? "（未读到文字，按坐标定位）" : "（文字：“" + label + "”）")
                + "。可继续操作购票App后加点。");
    }

    private void cancelPick(String message) {
        if (picker == null) return;
        dropPicker();
        refresh();
        toast(message);
    }

    private void dropPicker() {
        if (picker != null && picker.isAttachedToWindow()) manager.removeViewImmediate(picker);
        picker = null;
    }

    private void undo() {
        if (points.isEmpty()) { toast("还没有点位可以撤销。"); return; }
        TicketClickPlan.Point removed = points.remove(points.size() - 1);
        labels.remove(labels.size() - 1);
        if (points.isEmpty()) screenPinned = false; // 一点不剩，就没有可比的采点方向了。
        refresh();
        toast("已撤销最后一点（" + removed.x + ", " + removed.y + "），剩 " + points.size() + " 点。");
    }

    private void save() {
        if (packageName.isEmpty() || points.isEmpty()) {
            toast(packageName.isEmpty() ? "还没有绑定目标App，无法保存。" : "还没有记录任何点位，无法保存。");
            return;
        }
        if (!screenMatches()) {
            // 坐标属于采点时的那个方向，绝不能拿现在的宽高把旧坐标冒充成新方向的点位。
            resetPoints();
            toast("屏幕方向或分辨率已变化，屏幕上所有点已作废，请重新采点再保存。");
            return;
        }
        TicketRecipe recipe;
        try { recipe = TicketRecipe.load(this); }
        catch (IllegalArgumentException ex) { recipe = new TicketRecipe(); } // 坏数据不该拦住这次录制，覆盖即可。
        recipe.packageName = packageName;
        recipe.width = recordedWidth;
        recipe.height = recordedHeight;
        recipe.points.clear();
        recipe.labels.clear();
        recipe.points.addAll(points);
        recipe.labels.addAll(labels);
        try { recipe.save(this); }
        catch (RuntimeException ex) { toast("未保存：" + (ex.getMessage() == null ? "点位数据无效。" : ex.getMessage())); return; }
        toast("已保存 " + points.size() + " 个点 · " + appLabel(packageName) + " · "
                + recordedWidth + "×" + recordedHeight + "，录制结束。");
        stopSelf();
    }

    private void refresh() {
        boolean picking = picker != null;
        if (bindButton != null) bindButton.setEnabled(!picking);
        if (pickButton != null) pickButton.setEnabled(!picking);
        if (undoButton != null) undoButton.setEnabled(!picking && !points.isEmpty());
        if (saveButton != null) saveButton.setEnabled(!picking);
        if (closeButton != null) closeButton.setText(picking ? "取消取点" : "取消");
        if (statusText != null) {
            String target = packageName.isEmpty() ? "未绑定（先切到购票App再点绑定）"
                    : appLabel(packageName) + "\n" + packageName;
            String access = TicketTapService.isEnabled() ? "" : "\n无障碍未开启，无法绑定App";
            statusText.setText("目标：" + target + "\n点位：" + points.size() + "/" + MAX_POINTS + access);
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private final View.OnTouchListener drag = new View.OnTouchListener() {
        private float downX, downY;
        private int startX, startY;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = barParams.x;
                startY = barParams.y;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                barParams.x = startX + Math.round(event.getRawX() - downX);
                barParams.y = startY + Math.round(event.getRawY() - downY);
                clamp();
                manager.updateViewLayout(bar, barParams);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        }
    };

    private void clamp() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        barParams.width = Math.min(dp(BAR_WIDTH), screen.widthPixels - dp(16));
        int barHeight = bar != null && bar.getHeight() > 0 ? bar.getHeight() : dp(180);
        barParams.x = Math.max(0, Math.min(barParams.x, Math.max(0, screen.widthPixels - barParams.width)));
        barParams.y = Math.max(0, Math.min(barParams.y, Math.max(0, screen.heightPixels - barHeight - dp(24))));
    }

    private TextView label(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setLineSpacing(0, 1.12f);
        ToolUi.text(view, color, size);
        return view;
    }

    private Button button(String text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        ToolUi.text(button, INK, 13);
        button.setBackground(ToolUi.ripple(this, PRESSED, dp(10), ACCENT, dp(1)));
        button.setOnClickListener(action);
        return button;
    }

    private LinearLayout buttonRow(Button left, Button right) {
        LinearLayout container = new LinearLayout(this);
        container.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(44), 1);
        first.setMargins(dp(3), dp(4), dp(3), 0);
        container.addView(left, first);
        container.addView(right, new LinearLayout.LayoutParams(first));
        return container;
    }

    private void toast(String message) { Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return ToolUi.dp(this, value); }

    /** 全屏半透明取点层：吞掉这一次触摸，ACTION_UP 记录 raw 坐标，绝不点击下层应用。 */
    private final class PickLayer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PickLayer() {
            super(TicketRecorderService.this);
            setBackgroundColor(Color.argb(140, 0, 0, 0));
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setTextSize(dp(13));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(ACCENT);
            for (int i = 0; i < points.size(); i++) {
                TicketClickPlan.Point point = points.get(i);
                canvas.drawCircle(point.x, point.y, dp(18), paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawText(String.valueOf(i + 1), point.x + dp(22), point.y - dp(20), paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(15));
            canvas.drawText("点一下要连点的位置（" + (points.size() + 1) + "/" + MAX_POINTS + "）", dp(24), dp(72), paint);
            paint.setColor(Color.rgb(206, 226, 224));
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(12));
            canvas.drawText("这次触摸只用来取点，不会落到下面的App", dp(24), dp(98), paint);
            canvas.drawText("要放弃请点悬浮栏的“取消取点”", dp(24), dp(122), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                final int x = Math.round(event.getRawX());
                final int y = Math.round(event.getRawY());
                handler.post(() -> finishPick(x, y));
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handler.post(() -> cancelPick(CANCEL_PICK));
            }
            return true;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (bar != null && bar.isAttachedToWindow()) {
            clamp();
            manager.updateViewLayout(bar, barParams);
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (manager != null) {
            if (picker != null && picker.isAttachedToWindow()) manager.removeViewImmediate(picker);
            if (bar != null && bar.isAttachedToWindow()) manager.removeViewImmediate(bar);
        }
        picker = null;
        bar = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
