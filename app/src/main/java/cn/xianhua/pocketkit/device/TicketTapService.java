package cn.xianhua.pocketkit.device;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import cn.xianhua.pocketkit.automation.AdSkipController;
import cn.xianhua.pocketkit.automation.AutomationRuntime;
import cn.xianhua.pocketkit.automation.TicketScriptFactory;
import java.util.ArrayDeque;

/** Compatibility adapter: the legacy 071 entry now starts the shared automation runtime. */
public final class TicketTapService extends AccessibilityService {
    private static volatile TicketTapService instance;
    private static volatile String lastStatus = "尚未预约自动操作";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AdSkipController adSkip;

    public static boolean isEnabled() { return instance != null; }
    public static boolean isActive() { return AutomationRuntime.isActive(); }
    public static String status() {
        String runtimeStatus = AutomationRuntime.status();
        return runtimeStatus == null || runtimeStatus.trim().isEmpty() ? lastStatus : runtimeStatus;
    }
    public static boolean safePackage(String value) {
        return cn.xianhua.pocketkit.core.TicketClickGuard.safePackage(value);
    }

    private AccessibilityNodeInfo purchaseRoot() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && safePackage(String.valueOf(root.getPackageName()))) return root;
                if (root != null) root.recycle();
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && safePackage(String.valueOf(root.getPackageName()))) return root;
        if (root != null) root.recycle();
        return null;
    }

    public static String foregroundPackage() {
        TicketTapService service = instance;
        if (service == null) return "";
        AccessibilityNodeInfo root = service.purchaseRoot();
        if (root == null) return "";
        String value = String.valueOf(root.getPackageName());
        root.recycle();
        return value;
    }

    public static String textAt(int x, int y) {
        TicketTapService service = instance;
        if (service == null) return "";
        AccessibilityNodeInfo root = service.purchaseRoot();
        if (root == null) return "";
        String result = "";
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        Rect bounds = new Rect();
        int seen = 0;
        while (!nodes.isEmpty() && seen++ < 400) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            node.getBoundsInScreen(bounds);
            if (bounds.contains(x, y) && !node.isPassword()) {
                CharSequence text = node.getText();
                if (text == null || text.length() == 0) text = node.getContentDescription();
                if (text != null && text.length() <= 40) result = text.toString();
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) nodes.add(child);
                }
            }
            node.recycle();
        }
        while (!nodes.isEmpty()) nodes.removeFirst().recycle();
        return result;
    }

    public static void arm(TicketRecipe recipe, long targetEpochMs, boolean repeat) {
        TicketTapService service = instance;
        if (service == null) throw new IllegalStateException("请先在系统设置中启用自动化服务。");
        recipe.validate();
        stop("旧任务已替换");
        if (service.adSkip != null) service.adSkip.suspend();
        cn.xianhua.pocketkit.core.automation.Script script = TicketScriptFactory.fromRecipe(
                recipe, targetEpochMs, TicketClock.now(), repeat);
        AutomationRuntime.start(service, script);
        lastStatus = "已预约，到点由自动化工作台执行";
    }

    public static void stop(String reason) {
        AutomationRuntime.stop(reason);
        lastStatus = reason == null || reason.trim().isEmpty() ? "自动操作已停止" : reason;
    }

    @Override protected void onServiceConnected() {
        instance = this;
        android.accessibilityservice.AccessibilityServiceInfo info=getServiceInfo();
        if(info!=null){info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;info.eventTypes|=AccessibilityEvent.TYPE_WINDOWS_CHANGED|AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED|AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;setServiceInfo(info);}
        adSkip = new AdSkipController(this);
        AutomationRuntime.attach(this);
        lastStatus = "自动化服务已连接";
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (adSkip == null) return;
        if (AutomationRuntime.isActive()) adSkip.suspend();
        else adSkip.onAccessibilityEvent(event);
    }

    private boolean volumeStopConsumed;
    @Override protected boolean onKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && AutomationRuntime.isActive()) {
            volumeStopConsumed = true;
            AutomationRuntime.stop("音量下键急停");
            return true;
        }
        if (volumeStopConsumed) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) volumeStopConsumed = false;
            return true;
        }
        return false;
    }

    @Override public void onInterrupt() {
        if(adSkip!=null)adSkip.suspend();
        AutomationRuntime.stop("无障碍服务中断，自动操作已停止");
        lastStatus = "无障碍服务中断，自动操作已停止";
    }

    @Override public void onDestroy() {
        AutomationRuntime.stop("无障碍服务已关闭");
        AutomationRuntime.detach(this);
        if (adSkip != null) adSkip.suspend();
        adSkip = null;
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
