package cn.xianhua.pocketkit;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import cn.xianhua.pocketkit.device.TicketAssistantTool;
import cn.xianhua.pocketkit.device.TicketOverlayService;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** No third-party runner; exercises the real app's windows and foreground service. */
public final class PocketKitRegressionInstrumentation extends Instrumentation {
    private final List<String> passed = new ArrayList<>();
    private Bundle proxyArguments;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && "true".equals(arguments.getString("proxy"))) proxyArguments = arguments;
        start();
    }

    @Override public void onStart() {
        if (proxyArguments != null) { new ProxyRegressionSuite(this).run(proxyArguments); return; }
        Bundle result = new Bundle();
        try {
            Activity ruler = open("072", "尺子");
            runOnMainSync(() -> button(ruler.getWindow().getDecorView(), "进入全屏贴边").performClick());
            waitForIdleSync();
            SystemClock.sleep(300);
            require(!contains(ruler.getWindow().getDecorView(), "未完成"), "ruler full-screen opens without DecorView crash");
            require(activeContains("退出全屏"), "full-screen ruler window is present");
            screenshot("ruler-fullscreen.png");
            clickActive("退出全屏");
            require(!activeContains("退出全屏"), "full-screen closes cleanly");
            for (int i = 0; i < 2; i++) {
                runOnMainSync(() -> button(ruler.getWindow().getDecorView(), "进入全屏贴边").performClick());
                waitForIdleSync();
                clickActive("退出全屏");
            }
            require(!contains(ruler.getWindow().getDecorView(), "未完成"), "repeated ruler open/close");
            runOnMainSync(ruler::finish);
            waitForIdleSync();

            Activity ticket = open("071", "抢票助手");
            List<EditText> fields = new ArrayList<>();
            runOnMainSync(() -> edits(ticket.getWindow().getDecorView(), fields));
            require(fields.size() >= 2, "date and arbitrary time fields exist");
            ZonedDateTime tomorrow = Instant.ofEpochMilli(TicketAssistantTool.getAccurateTime()).atZone(ZoneId.of("Asia/Shanghai")).plusDays(1);
            runOnMainSync(() -> {
                fields.get(0).setText(tomorrow.toLocalDate().toString());
                fields.get(1).setText("09:37:30");
                button(ticket.getWindow().getDecorView(), "选择时、分、秒").performClick();
            });
            waitForIdleSync();
            SystemClock.sleep(200);
            require(activeContains("秒"), "custom time picker includes seconds");
            clickActive("确定");
            require(fields.get(1).getText().toString().equals("09:37:30"), "non-hour picker value is retained");
            screenshot("ticket-custom-time.png");
            runOnMainSync(() -> button(ticket.getWindow().getDecorView(), "开启悬浮倒计时").performClick());
            waitForIdleSync();
            SystemClock.sleep(300);
            require(TicketOverlayService.isRunning(), "custom non-hour target starts foreground countdown");
            long expected = tomorrow.toLocalDate().atTime(9, 37, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            require(TicketOverlayService.target() == expected, "custom date/time reaches actual service unchanged");
            runOnMainSync(() -> TicketOverlayService.stop(ticket));
            waitForIdleSync();
            SystemClock.sleep(200);
            require(!TicketOverlayService.isRunning(), "manual stop removes service");

            runOnMainSync(() -> {
                fields.get(0).setText("2020-01-01");
                fields.get(1).setText("09:37:30");
                button(ticket.getWindow().getDecorView(), "开启悬浮倒计时").performClick();
            });
            waitForIdleSync();
            require(!TicketOverlayService.isRunning() && contains(ticket.getWindow().getDecorView(), "时间已经过去"), "past date is rejected, not silently moved to tomorrow");
            ZonedDateTime near = Instant.ofEpochMilli(TicketAssistantTool.getAccurateTime() + 8000).atZone(ZoneId.of("Asia/Shanghai"));
            runOnMainSync(() -> {
                fields.get(0).setText(near.toLocalDate().toString());
                fields.get(1).setText(near.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                button(ticket.getWindow().getDecorView(), "开启悬浮倒计时").performClick();
            });
            waitForIdleSync();
            SystemClock.sleep(300);
            require(TicketOverlayService.isRunning(), "short test target starts");
            runOnMainSync(ticket::finish);
            waitForIdleSync();
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            SystemClock.sleep(400);
            require(TicketOverlayService.isRunning(), "closing page and switching app does not stop countdown");
            screenshot("ticket-overlay-background.png");
            long until = SystemClock.elapsedRealtime() + 11000;
            while (TicketAssistantTool.getAccurateTime() < TicketOverlayService.target() + 500 && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(100);
            }
            require(TicketOverlayService.isRunning(), "service remains alive at target time in background");
            require(activeContains("开抢时间已到"), "background overlay reaches target state");
            screenshot("ticket-overlay-arrived.png");
            clickActive("关闭");
            SystemClock.sleep(200);
            require(!TicketOverlayService.isRunning(), "overlay close stops the foreground service");
            result.putString("stream", "PASS " + passed.size() + " runtime checks\n" + String.join("\n", passed) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable ex) {
            runOnMainSync(() -> TicketOverlayService.stop(getTargetContext()));
            result.putString("stream", "FAIL after " + passed.size() + " checks: " + ex + "\n" + String.join("\n", passed) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Activity open(String id, String title) {
        Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), DeviceToolsActivity.class)
                .putExtra("tool_id", id).putExtra("title", title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        return activity;
    }
    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed.add(message);
    }
    private Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().contains(text)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = buttonOrNull(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        throw new AssertionError("button not found: " + text);
    }
    private Button buttonOrNull(View view, String text) {
        try { return button(view, text); } catch (AssertionError ex) { return null; }
    }
    private boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private void edits(View view, List<EditText> fields) {
        if (view instanceof EditText) fields.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) edits(group.getChildAt(i), fields);
        }
    }
    private boolean activeContains(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return false;
        return !root.findAccessibilityNodeInfosByText(text).isEmpty();
    }
    private void clickActive(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) throw new AssertionError("no active window for " + text);
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                waitForIdleSync();
                SystemClock.sleep(200);
                return;
            }
        }
        throw new AssertionError("active button not found: " + text);
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("screenshot unavailable");
        File directory = getTargetContext().getExternalFilesDir("regression");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
