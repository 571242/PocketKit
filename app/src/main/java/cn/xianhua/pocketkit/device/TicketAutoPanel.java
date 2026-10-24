package cn.xianhua.pocketkit.device;

import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import cn.xianhua.pocketkit.core.TicketTiming;
import java.util.function.BooleanSupplier;

final class TicketAutoPanel {
    private TicketAutoPanel() {}

    static void build(Ui ui, EditText date, EditText time, CheckBox vibration, BooleanSupplier syncing) {
        TextView heading = ui.note("071 定时自动操作模板");
        heading.setTextSize(22);
        heading.setTextColor(ui.ink());
        ui.note("保留原有录点、时间选择和校准；预约后由通用自动化引擎执行用户录制的步骤。识别与操作均在本机完成，不保证抢到票。");
        TextView recipeSummary = ui.note("还没有录制步骤");
        TextView progress = ui.note(TicketTapService.status());
        progress.setTextColor(ui.accent());
        ui.button("开启自动点击权限", () -> {
            ui.show("请在系统无障碍设置中开启“拾用 · 自动化服务”，然后回到这里。本地识别只用于执行用户操作，不上传屏幕内容。");
            ui.activity().startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        ui.button("录制购票点击步骤", () -> {
            if (!TicketTapService.isEnabled()) throw new IllegalStateException("请先开启自动点击权限。");
            if (!Settings.canDrawOverlays(ui.activity())) {
                ui.show("请允许悬浮窗，返回后再点击录制。");
                ui.activity().startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ui.activity().getPackageName())));
                return;
            }
            TicketTapService.stop("开始录制，旧自动任务停止");
            cn.xianhua.pocketkit.automation.AutomationOverlayService.stop(ui.activity());
            TicketOverlayService.stop(ui.activity());
            TicketRecorderService.start(ui.activity());
            ui.show("切到购票 App，先绑定应用，再添加点击位置。录制时不会替你点击购票 App；可自行切换页面后继续添加。保存后回来预约。");
        });
        ui.button("调整步骤识别文字", () -> editLabels(ui));
        TicketRecipe stored;
        try { stored = TicketRecipe.load(ui.activity()); } catch (IllegalArgumentException ex) { stored = new TicketRecipe(); }
        EditText interval = ui.field("点击间隔（毫秒）", "100 至 5000，默认 300");
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setText(String.valueOf(stored.intervalMs));
        EditText timeout = ui.field("持续尝试（秒）", "1 至 60，默认 30");
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeout.setText(String.valueOf(stored.timeoutSeconds));
        CheckBox repeat = new CheckBox(ui.activity());
        repeat.setText("循环重试录制步骤（关闭则只执行一轮）");
        repeat.setTextColor(ui.ink());
        repeat.setMinHeight(ui.dp(48));
        repeat.setChecked(true);
        ui.body().addView(repeat);
        ui.button("预约自动操作", () -> {
            if (syncing.getAsBoolean()) throw new IllegalStateException("请完成网络校准后再预约。");
            if (!TicketTapService.isEnabled()) throw new IllegalStateException("请先开启自动点击权限。");
            if (!Settings.canDrawOverlays(ui.activity())) throw new IllegalStateException("请先允许悬浮窗，确保随时有停止入口。");
            TicketRecipe recipe = TicketRecipe.load(ui.activity());
            try {
                recipe.intervalMs = Integer.parseInt(interval.getText().toString().trim());
                recipe.timeoutSeconds = Integer.parseInt(timeout.getText().toString().trim());
            } catch (NumberFormatException ex) { throw new IllegalArgumentException("间隔与持续时间请填写整数。", ex); }
            recipe.validate();
            long target = TicketTiming.parseTarget(date.getText().toString(), time.getText().toString(), TicketClock.now());
            recipe.save(ui.activity());
            TicketRecorderService.stop(ui.activity());
            TicketTapService.arm(recipe, target, repeat.isChecked());
            ui.show("已预约自动操作。现在切到绑定的目标 App 并保持屏幕解锁；到点由通用引擎执行录制步骤。请先在无购买动作的页面测试点位，支付与验证码由你处理。");
        });
        ui.button("停止自动操作与控制浮窗", () -> {
            TicketTapService.stop("你已停止自动操作");
            cn.xianhua.pocketkit.automation.AutomationOverlayService.stop(ui.activity());
            TicketOverlayService.stop(ui.activity());
            TicketRecorderService.stop(ui.activity());
            ui.show("自动操作已停止。");
        });
        ui.note("步骤标签仍可调整或清空；录制器只记录模板，不执行点击。通用引擎按用户脚本运行，切错 App、锁屏或不可读取页面时应由你停止并核对点位；支付与验证码由你处理。");
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] visible = {true};
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!visible[0] || ui.activity().isDestroyed()) return;
                progress.setText(TicketTapService.status());
                try {
                    TicketRecipe current = TicketRecipe.load(ui.activity());
                    recipeSummary.setText(current.points.isEmpty() ? "还没有录制步骤" : "目标 " + current.packageName + " · " + current.points.size() + " 个步骤");
                } catch (IllegalArgumentException ex) { recipeSummary.setText("步骤无法读取，请重新录制。"); }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(refresh);
        ui.onStopAction(() -> { visible[0] = false; handler.removeCallbacks(refresh); });
        ui.onStartAction(() -> { visible[0] = true; handler.removeCallbacks(refresh); handler.post(refresh); });
        ui.addCleanup(() -> { visible[0] = false; handler.removeCallbacksAndMessages(null); });
    }

    private static void editLabels(Ui ui) {
        TicketRecipe recipe = TicketRecipe.load(ui.activity());
        recipe.validate();
        LinearLayout content = new LinearLayout(ui.activity());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        EditText[] labels = new EditText[recipe.points.size()];
        for (int i = 0; i < labels.length; i++) {
            EditText field = new EditText(ui.activity());
            field.setText(i < recipe.labels.size() ? recipe.labels.get(i) : "");
            field.setHint("步骤 " + (i + 1) + "，留空则按坐标");
            field.setSingleLine(true);
            labels[i] = field;
            content.addView(field);
        }
        new AlertDialog.Builder(ui.activity()).setTitle("步骤识别文字（首点始终按坐标）").setView(content)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    recipe.labels.clear();
                    for (EditText field : labels) recipe.labels.add(field.getText().toString().trim());
                    try { recipe.save(ui.activity()); ui.show("步骤识别文字已保存。"); }
                    catch (RuntimeException ex) { ui.fail(ex); }
                }).show();
    }
}
