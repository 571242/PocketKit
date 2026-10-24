package cn.xianhua.pocketkit.device;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.automation.ScriptStore;
import cn.xianhua.pocketkit.automation.TicketScriptFactory;
import cn.xianhua.pocketkit.core.TicketTiming;
import cn.xianhua.pocketkit.core.automation.Script;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** An explicit target, honest time-source status, and a user-stoppable overlay countdown. */
public final class TicketAssistantTool {
    private TicketAssistantTool() {}

    public static boolean supports(String id) { return "071".equals(id); }

    public static String limitation(String id) {
        return supports(id) ? "071保留定时与录制入口并生成通用自动化脚本；本地识别、用户操作，需无障碍/悬浮窗权限，不代付，不保证抢到票。" : "";
    }

    public static long getAccurateTime() { return TicketClock.now(); }

    public static void build(Ui ui) {
        Activity activity = ui.activity();
        SharedPreferences preferences = activity.getSharedPreferences("pocketkit_ticket", Context.MODE_PRIVATE);
        Handler handler = new Handler(Looper.getMainLooper());
        SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        clockFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        LinearLayout clock = new LinearLayout(activity);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        clock.setBackground(ToolUi.fill(Color.parseColor(dark ? "#172426" : "#FFFFFF"), ui.dp(14), Color.TRANSPARENT, 0));
        TextView title = label(activity, "北京时间", 14, ui.muted());
        clock.addView(title);
        TextView timeDisplay = label(activity, "", 32, ui.ink());
        timeDisplay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timeDisplay.setSingleLine(true);
        timeDisplay.setAutoSizeTextTypeUniformWithConfiguration(18, 32, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        clock.addView(timeDisplay, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        TextView sourceStatus = label(activity, TicketClock.status(), 12, ui.muted());
        clock.addView(sourceStatus);
        ui.body().addView(clock, new LinearLayout.LayoutParams(-1, -2));

        ZonedDateTime initial = Instant.ofEpochMilli(TicketClock.now() + 60000).atZone(TicketTiming.ZONE);
        EditText date = ui.field("开抢日期（北京时间）", "选择日期");
        date.setText(preferences.getString("date", initial.toLocalDate().toString()));
        date.setInputType(InputType.TYPE_NULL);
        date.setFocusable(false);
        date.setOnClickListener(v -> {
            LocalDate selected;
            try { selected = LocalDate.parse(date.getText().toString()); }
            catch (java.time.DateTimeException ex) { selected = Instant.ofEpochMilli(TicketClock.now()).atZone(TicketTiming.ZONE).toLocalDate(); }
            new DatePickerDialog(activity, (picker, year, month, day) -> date.setText(LocalDate.of(year, month + 1, day).toString()),
                    selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth()).show();
        });
        EditText time = ui.field("开抢时间（时:分:秒，可直接输入）", "例如 09:37:30");
        time.setSingleLine(true);
        time.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        time.setText(preferences.getString("time", String.format(Locale.US, "%02d:%02d:%02d", initial.getHour(), initial.getMinute(), initial.getSecond())));
        ui.button("选择时、分、秒", () -> selectTime(activity, time));

        LinearLayout shortcuts = new LinearLayout(activity);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        Button today = compact(ui, "今天", () -> date.setText(Instant.ofEpochMilli(TicketClock.now()).atZone(TicketTiming.ZONE).toLocalDate().toString()));
        Button tomorrow = compact(ui, "明天", () -> date.setText(Instant.ofEpochMilli(TicketClock.now()).atZone(TicketTiming.ZONE).toLocalDate().plusDays(1).toString()));
        Button trial = compact(ui, "10秒后试一下", () -> {
            ZonedDateTime value = Instant.ofEpochMilli(TicketClock.now() + 10000).atZone(TicketTiming.ZONE);
            date.setText(value.toLocalDate().toString());
            time.setText(String.format(Locale.US, "%02d:%02d:%02d", value.getHour(), value.getMinute(), value.getSecond()));
            ui.show("测试时间已填好。点击“开启悬浮倒计时”后，切到其他 App 检查震动提醒。");
        });
        shortcuts.addView(today, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        shortcuts.addView(tomorrow, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        shortcuts.addView(trial, new LinearLayout.LayoutParams(0, ui.dp(48), 1.8f));
        ui.body().addView(shortcuts, new LinearLayout.LayoutParams(-1, -2));
        CheckBox vibration = new CheckBox(activity);
        vibration.setText("开抢前 5、3、1 秒及到点震动提醒");
        vibration.setTextColor(ui.ink());
        vibration.setMinHeight(ui.dp(48));
        vibration.setChecked(preferences.getBoolean("vibrate", true));
        ui.body().addView(vibration);

        TextView countdown = ui.note("尚未开启提醒");
        countdown.setTextSize(20);
        countdown.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        countdown.setTextColor(ui.accent());
        TextView activeDescription = ui.note("");
        final boolean[] pendingPermission = {false};
        final boolean[] syncing = {false};
        TicketAutoPanel.build(ui, date, time, vibration, () -> syncing[0]);
        ui.button("在自动化工作台编辑", () -> {
            try {
                TicketRecipe legacy = TicketRecipe.load(activity);
                long now = TicketClock.now();
                Script script;
                if (legacy.points.isEmpty()) {
                    script = new Script();
                    script.name = "071 定时操作模板";
                } else {
                    script = TicketScriptFactory.fromRecipe(legacy, now, now, false);
                }
                new ScriptStore(activity).save(script);
                Intent editor = new Intent().setClassName(activity, "cn.xianhua.pocketkit.AutomationToolsActivity")
                        .putExtra("tool_id", "C01").putExtra("title", "自动化工作台")
                        .putExtra("script_id", script.id);
                activity.startActivity(editor);
            } catch (RuntimeException ex) {
                ui.show("旧录制步骤无法转为工作台脚本，原配置未改动：" + (ex.getMessage() == null ? "请先重新录制。" : ex.getMessage()));
            }
        });
        Runnable saveDraft = () -> preferences.edit().putString("date", date.getText().toString().trim())
                .putString("time", time.getText().toString().trim()).putBoolean("vibrate", vibration.isChecked()).apply();
        Runnable begin = () -> {
            if (syncing[0]) {
                ui.show("正在校准参考时间，请完成后再开启倒计时。");
                return;
            }
            long target = TicketTiming.parseTarget(date.getText().toString(), time.getText().toString(), TicketClock.now());
            saveDraft.run();
            if (!Settings.canDrawOverlays(activity)) {
                pendingPermission[0] = true;
                ui.show("请允许“显示在其他应用上层”。授权后返回，倒计时会自动开启；若时间已过，请重新选择。");
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            TicketTapService.stop("你已切换为仅倒计时");
            TicketOverlayService.start(activity, target, vibration.isChecked());
            ui.show("正在开启悬浮倒计时…点悬浮窗的“关闭”可停止；系统通知开启时也能从通知里停止。");
        };
        Button start = ui.button("开启悬浮倒计时", begin);
        Button stop = ui.button("停止提醒并关闭悬浮窗", () -> {
            pendingPermission[0] = false;
            TicketOverlayService.stop(activity);
            ui.show("已停止提醒。");
        });
        Button sync = ui.button("校准网络参考时间", () -> {
            long left = TicketOverlayService.target() - TicketClock.now();
            if (TicketTapService.isActive() || (TicketOverlayService.isRunning() && left > 0 && left < 15000)) {
                ui.show("即将开抢，暂不切换时钟来源。请在开抢前提前校准。");
                return;
            }
            if (syncing[0]) return;
            syncing[0] = true;
            sourceStatus.setText("正在获取网络参考时间…");
            ui.background(() -> {
                String outcome;
                try {
                    TicketClock.synchronize();
                    outcome = "网络参考时间已更新。往返耗时不等于真实误差，购票平台可能采用不同时间源。";
                } catch (java.io.IOException ex) {
                    outcome = "对时失败，未修改原来的时间来源：" + ex.getMessage();
                }
                final String message = outcome;
                handler.post(() -> {
                    syncing[0] = false;
                    sourceStatus.setText(TicketClock.status());
                    ui.show(message);
                });
            });
        });
        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ui.button("允许系统通知（可选）", () -> ui.ensurePermission("android.permission.POST_NOTIFICATIONS",
                    "允许后可在通知栏返回助手或停止提醒；不授权仍可从悬浮窗关闭。", () -> ui.show("系统通知已允许。")));
        }
        ui.note("自动模式执行你录制的步骤，识别与操作均在本机完成；支付与验证码由你处理，不承诺抢到票。仅倒计时模式不操作其他 App。自动预约等待会保持亮屏，长时间等待耗电；手动锁屏不会自动解锁，系统省电或平台限制可能使自动化失效。");

        final boolean[] visible = {true};
        final String[] shownError = {null};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!visible[0] || activity.isDestroyed()) return;
                long now = TicketClock.now();
                timeDisplay.setText(clockFormat.format(new Date(now)));
                if (!syncing[0]) sourceStatus.setText(TicketClock.status());
                boolean running = TicketOverlayService.isRunning();
                countdown.setText(running ? TicketTiming.remaining(TicketOverlayService.target() - now) : "尚未开启提醒");
                activeDescription.setText(running ? "当前提醒目标：" + Instant.ofEpochMilli(TicketOverlayService.target()).atZone(TicketTiming.ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) : "填写时间后点击开启，不会自动在第二天重跑。");
                start.setText(running ? "切换为仅倒计时" : "仅开启悬浮倒计时（不自动点击）");
                stop.setEnabled(running);
                sync.setEnabled(!syncing[0]);
                start.setEnabled(!syncing[0]);
                String error = TicketOverlayService.error();
                if (error != null && !error.equals(shownError[0])) {
                    shownError[0] = error;
                    ui.show(error);
                }
                handler.postDelayed(this, 100);
            }
        };
        handler.post(tick);
        ui.onStopAction(() -> {
            visible[0] = false;
            handler.removeCallbacks(tick);
            saveDraft.run();
        });
        ui.onStartAction(() -> {
            visible[0] = true;
            handler.removeCallbacks(tick);
            handler.post(tick);
            if (pendingPermission[0]) {
                pendingPermission[0] = false;
                if (Settings.canDrawOverlays(activity)) {
                    handler.post(() -> {
                        try { begin.run(); }
                        catch (RuntimeException ex) { ui.fail(ex); }
                    });
                } else {
                    ui.show("未授予悬浮窗权限，倒计时没有开启。可重新点击开启并授权。");
                }
            }
        });
        // Closing this page must not close the user-requested countdown in another app.
        ui.addCleanup(() -> {
            visible[0] = false;
            handler.removeCallbacksAndMessages(null);
        });
    }

    private static TextView label(Activity activity, String content, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static Button compact(Ui ui, String text, Runnable action) {
        Button button = new Button(ui.activity());
        ToolUi.text(button, ui.accent(), 13);
        button.setText(text);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(ToolUi.ripple(ui.activity(), Color.TRANSPARENT, ui.dp(8), Color.TRANSPARENT, 0));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static void selectTime(Activity activity, EditText field) {
        LocalTime initial;
        try { initial = LocalTime.parse(field.getText().toString()); }
        catch (java.time.DateTimeException ex) { initial = Instant.ofEpochMilli(TicketClock.now()).atZone(TicketTiming.ZONE).toLocalTime(); }
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(16, 0, 16, 0);
        NumberPicker[] pickers = new NumberPicker[3];
        int[] values = {initial.getHour(), initial.getMinute(), initial.getSecond()};
        String[] labels = {"时", "分", "秒"};
        for (int i = 0; i < 3; i++) {
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            TextView label = new TextView(activity);
            label.setText(labels[i]);
            column.addView(label);
            NumberPicker picker = new NumberPicker(activity);
            picker.setMinValue(0);
            picker.setMaxValue(i == 0 ? 23 : 59);
            picker.setFormatter(value -> String.format(Locale.US, "%02d", value));
            picker.setValue(values[i]);
            pickers[i] = picker;
            column.addView(picker);
            row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        }
        new AlertDialog.Builder(activity).setTitle("选择开抢时间（北京时间）").setView(row)
                .setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> {
                    for (NumberPicker picker : pickers) picker.clearFocus();
                    field.setText(String.format(Locale.US, "%02d:%02d:%02d", pickers[0].getValue(), pickers[1].getValue(), pickers[2].getValue()));
                }).show();
    }
}
