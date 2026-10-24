package cn.xianhua.pocketkit;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.automation.AutomationRecorderService;
import cn.xianhua.pocketkit.automation.AutomationRuntime;
import cn.xianhua.pocketkit.automation.AutomationOverlayService;
import cn.xianhua.pocketkit.automation.AutomationPickerService;
import cn.xianhua.pocketkit.automation.ProjectionCaptureService;
import cn.xianhua.pocketkit.automation.ScriptCodec;
import cn.xianhua.pocketkit.automation.ScriptStore;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** C01 连点器: recorder, quick click, and the native visual script workbench. */
public final class AutomationToolsActivity extends BaseToolActivity {
    private static final int OPEN_SCRIPT = 701;
    private static final int SAVE_SCRIPT = 702;
    private static final int REQUEST_PROJECTION = 703;
    private static final String RECORDER_PREFS = "automation_recorder";
    private static final String PICKER_PREFS = "automation_picker";
    private ScriptStore store;
    private Script current;
    private AutomationEditor editor;
    private TextView status, accessibilityState, overlayState;
    private String draft;
    private Script selectedScript;
    private String selectedScriptId;
    private String pendingScriptId, pendingStepId;
    private String exportScriptId, exportPayload;
    private Bundle rawEditorFields;
    private boolean pickerPending, startAfterPick;
    private String pickerResultStatus, pickerResultMessage;
    private String pendingRecordingScriptId;
    private boolean recorderPending, recordingCreatedNew;
    private long requestStartedAt;
    private boolean pollScheduled;
    private final Runnable runtimeListener = this::refreshStatus;
    private final Handler pickerPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() { @Override public void run() {
        pollScheduled = false;
        if (pickerPending) checkPickerResult();
        if (recorderPending) checkRecordResult();
        checkInterruptedRequests();
        if (pickerPending || recorderPending) schedulePoll(250);
    } };
    private Object backCallback;
    private String editorStateToken;

    @android.annotation.TargetApi(33)
    private static final class Api33Back {
        static Object register(AutomationToolsActivity activity) {
            OnBackInvokedCallback callback = () -> {
                if (activity.editor == null) activity.finish();
                else if (activity.saveDraftBeforeLeaving()) activity.showWorkbench();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(AutomationToolsActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }

    public static boolean supports(String id) { return "C01".equals(id); }
    public static String limitation(String id) {
        return supports(id) ? "仅支持可视化本地动作；无 JavaScript 编辑器，不绕过权限或安全窗口。" : "未知工具";
    }

    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "连点器");
        super.onCreate(state);
        store = new ScriptStore(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 910);
        if (Build.VERSION.SDK_INT >= 33) backCallback = Api33Back.register(this);
        if (state != null && state.getString("automation_state_token") != null) {
            editorStateToken = state.getString("automation_state_token");
            try { state = cn.xianhua.pocketkit.automation.AutomationEditorState.load(this, editorStateToken); }
            catch (RuntimeException ex) { fail(ex); state = null; }
        }
        if (state != null) { rawEditorFields=state.getBundle("raw_editor_fields"); draft=state.getString("automation_draft"); pendingScriptId=state.getString("pending_script_id"); pendingStepId=state.getString("pending_step_id"); pickerPending=state.getBoolean("picker_pending",false); startAfterPick=state.getBoolean("start_after_pick",false); selectedScriptId=state.getString("selected_script_id"); exportScriptId=state.getString("export_script_id"); exportPayload=state.getString("export_payload"); pendingRecordingScriptId=state.getString("recorder_script_id"); recorderPending=state.getBoolean("recorder_pending",false); recordingCreatedNew=state.getBoolean("recording_created_new",false); }
        showWorkbench();
        if (selectedScriptId != null) try { selectedScript=store.load(selectedScriptId); } catch (RuntimeException ex) { fail(ex); }
        if (draft != null && !draft.isEmpty()) { try { openEditor(ScriptCodec.decodeDraft(draft)); } catch (RuntimeException ex) { fail(ex); } }
        String requested = getIntent().getStringExtra("script_id");
        if (requested != null && editor == null) try { openEditor(store.load(requested)); } catch (RuntimeException ex) { fail(ex); }
        // The recorder also reopens this page itself; the poll below stays the single source of the outcome.
        if (pickerPending || recorderPending) schedulePoll(0);
    }

    private void showWorkbench() {
        body.removeAllViews();
        if (current != null && editor != null) { selectedScript = current; selectedScriptId = current.id; }
        current = null; editor = null; accessibilityState = null; overlayState = null;
        status = note(AutomationRuntime.status());
        status.setTextColor(accent);
        addButton("录制操作", this::startRecording);
        addButton("快速连点", this::quickClick);
        addButton("新建脚本", () -> openEditor(newScript("新建脚本")));
        LinearLayout first = row();
        addButtonTo(first, "从模板开始", this::chooseTemplate);
        addButtonTo(first, "导入脚本", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), OPEN_SCRIPT));
        body.addView(first);
        LinearLayout second = row();
        addButtonTo(second, "导出脚本", this::chooseExport);
        addButtonTo(second, "运行日志", this::showLogs);
        body.addView(second);
        renderPermissions();
        renderScripts();
        refreshStatus();
    }

    /** Permission state is shown, explained, and one tap from the real system screen. */
    private void renderPermissions() {
        LinearLayout block = new LinearLayout(this); block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(6), dp(8), dp(6));
        block.setBackground(ToolUi.fill(surface, dp(12), Color.TRANSPARENT, 0));
        accessibilityState = permissionRow(block, () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        overlayState = permissionRow(block, this::openOverlaySettings);
        TextView hint = new TextView(this); hint.setText("无障碍服务执行动作；悬浮窗显示录制与连点控制条。从系统设置返回本页会自动刷新。");
        ToolUi.text(hint, muted, 13); hint.setPadding(0, dp(6), 0, dp(4)); block.addView(hint);
        Button detail = compact("权限与采集说明"); detail.setOnClickListener(v -> showPermissions());
        block.addView(detail, new LinearLayout.LayoutParams(-1, dp(48)));
        body.addView(block, new LinearLayout.LayoutParams(-1, -2));
        if (android.os.Build.VERSION.SDK_INT < 30) {
            LinearLayout capture = row();
            addButtonTo(capture, "授权屏幕捕获", this::requestProjection);
            addButtonTo(capture, "停止屏幕捕获", () -> { ProjectionCaptureService.stop(this); Toast.makeText(this, "屏幕捕获已停止", Toast.LENGTH_SHORT).show(); });
            body.addView(capture);
        }
    }

    private TextView permissionRow(LinearLayout parent, Runnable settings) {
        LinearLayout row = row();
        TextView text = new TextView(this); ToolUi.text(text, muted, 14);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = compact("设置"); open.setOnClickListener(v -> settings.run());
        row.addView(open, new LinearLayout.LayoutParams(dp(76), dp(48)));
        parent.addView(row);
        return text;
    }

    private void openOverlaySettings() { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }

    private void renderScripts() {
        try {
            List<Script> scripts = store.list();
            if (scripts.isEmpty()) { note("还没有脚本。录制一段操作，或从快速连点开始。"); return; }
            scripts.sort(Comparator.comparingLong((Script s) -> s.updatedAt).reversed());
            note("我的脚本");
            for (Script script : scripts) addScriptRow(script);
        } catch (RuntimeException ex) { fail(ex); }
    }

    private void addScriptRow(Script script) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackground(ToolUi.ripple(this, surface, dp(12), Color.TRANSPARENT, 0));
        TextView title = new TextView(this); title.setText(script.name == null || script.name.isEmpty() ? "未命名脚本" : script.name); ToolUi.text(title, ink, 17);
        box.addView(title);
        TextView detail = new TextView(this); detail.setText((script.steps == null ? 0 : script.steps.size()) + " 个顶层动作" + (script.repeatCount == 0 ? " · 循环直到停止" : " · 整体 " + script.repeatCount + " 遍") + (script.targetPackage == null || script.targetPackage.isEmpty() ? " · 未绑定应用" : " · " + script.targetPackage)); ToolUi.text(detail, muted, 13); detail.setSingleLine(true); box.addView(detail);
        LinearLayout actions = row();
        addButtonTo(actions, "运行", () -> { selectedScript = script; selectedScriptId = script.id; startScript(script); });
        addButtonTo(actions, "编辑", () -> openEditor(script));
        addButtonTo(actions, "更多", () -> scriptMenu(script));
        box.addView(actions);
        body.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private void scriptMenu(Script script) {
        new AlertDialog.Builder(this).setTitle(script.name).setItems(new String[]{"启动", "编辑", "复制", "重命名", "删除", "导出"}, (d, which) -> {
            try {
                if (which == 0) { selectedScript = script; selectedScriptId = script.id; startScript(script); }
                else if (which == 1) openEditor(script);
                else if (which == 2) { Script copy = ScriptCodec.decodeDraft(ScriptCodec.encodeDraft(script)); copy.id = UUID.randomUUID().toString(); copy.name = script.name + " 副本"; store.saveDraft(copy); showWorkbench(); }
                else if (which == 3) rename(script);
                else if (which == 4) confirmDelete(script);
                else { selectedScript = script; selectedScriptId = script.id; chooseExport(); }
            } catch (RuntimeException ex) { fail(ex); }
        }).show();
    }

    private void confirmDelete(Script script) {
        new AlertDialog.Builder(this).setTitle("删除脚本？").setMessage("删除「" + (script.name == null ? "未命名脚本" : script.name) + "」后无法在应用内恢复，已导出的文件不受影响。按悬浮窗急停可终止正在运行的任务。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> { try { store.delete(script.id); showWorkbench(); } catch (RuntimeException ex) { fail(ex); } }).show();
    }

    private void rename(Script script) {
        final EditText input = new EditText(this); input.setSingleLine(true); input.setText(script.name);
        new AlertDialog.Builder(this).setTitle("重命名").setView(input).setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> { script.name = input.getText().toString().trim(); if (script.name.isEmpty()) script.name = "未命名脚本"; store.save(script); showWorkbench(); }).show();
    }

    private void openEditor(Script script) {
        current = script; if (current.steps == null) current.steps = new ArrayList<>();
        body.removeAllViews();
        accessibilityState = null; overlayState = null;
        status = note(AutomationRuntime.status()); status.setTextColor(accent);
        editor = new AutomationEditor(this, current, new AutomationEditor.Listener() {
            @Override public void requestPick(Step step, String mode) { pick(step, mode, false); }
            @Override public void chooseTemplate(Step step) { try { editor.pull(); pendingStepId=step.id; chooseImageTemplate(step); } catch (RuntimeException ex) { fail(ex); } }
            @Override public void changed() { }
        });
        body.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        if (rawEditorFields != null) { editor.restoreRaw(rawEditorFields); rawEditorFields = null; }
        selectedScript = current; selectedScriptId = current.id;
        addButton("保存脚本", this::saveEditor);
        addButton("继续录制动作", this::startRecording);
        addButton("启动此脚本", () -> { if (saveEditor()) startScript(current); });
        addButton("暂停 / 恢复", () -> { if (AutomationRuntime.isPaused()) AutomationRuntime.resume(); else AutomationRuntime.pause(); refreshStatus(); });
        addButton("急停", () -> { AutomationRuntime.stop("用户急停"); refreshStatus(); });
        LinearLayout tail = row();
        addButtonTo(tail, "导出此脚本", this::chooseExport);
        addButtonTo(tail, "返回脚本列表", () -> { if (saveDraftBeforeLeaving()) showWorkbench(); });
        body.addView(tail);
    }

    private boolean saveEditor() { if (captureBusy()) return false; if (editor == null || current == null) return false; try { editor.pull(); store.saveDraft(current); draft=ScriptCodec.encodeDraft(current); Toast.makeText(this, "草稿已保存", Toast.LENGTH_SHORT).show(); return true; } catch (RuntimeException ex) { fail(ex); return false; } }

    private void refreshStatus() { String text = AutomationRuntime.status(); if (AutomationRecorderService.isRecording()) text += " · 录制中"; if (status != null) status.setText(text); refreshPermissions(); }

    private void refreshPermissions() {
        boolean accessible = AutomationRuntime.isEnabled(), overlay = Settings.canDrawOverlays(this);
        if (accessibilityState != null) { accessibilityState.setText("无障碍服务 · " + (accessible ? "已开启" : "未开启")); accessibilityState.setTextColor(accessible ? muted : Color.parseColor("#B3261E")); }
        if (overlayState != null) { overlayState.setText("悬浮窗 · " + (overlay ? "已允许" : "未允许")); overlayState.setTextColor(overlay ? muted : Color.parseColor("#B3261E")); }
    }

    // ---- run -----------------------------------------------------------------

    private void startScript(Script script) {
        if (captureBusy()) return;
        if (script == null) return;
        if (script.steps == null || script.steps.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("脚本还没有动作").setMessage("空脚本无法运行，先添加一个动作再启动。")
                    .setNegativeButton("关闭", null).setPositiveButton("添加动作", (d, w) -> openEditor(script)).show();
            return;
        }
        if (!AutomationRuntime.isEnabled()) { showPermissionPrompt("执行动作需要无障碍服务。"); return; }
        if (!Settings.canDrawOverlays(this)) { showPermissionPrompt("启动脚本需要悬浮窗来控制开始、暂停和急停。"); return; }
        try { AutomationOverlayService.prepare(this, script); refreshStatus(); Toast.makeText(this, "已准备启动：切到目标应用后点悬浮条的「开始」", Toast.LENGTH_LONG).show(); moveTaskToBack(true); }
        catch (RuntimeException ex) { fail(ex); }
    }

    private void showPermissionPrompt(String reason) {
        new AlertDialog.Builder(this).setTitle("需要开启权限").setMessage(reason + "开启后返回本页会自动刷新状态。")
                .setNegativeButton("关闭", null)
                .setNeutralButton("无障碍设置", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setPositiveButton("悬浮窗设置", (d, w) -> openOverlaySettings()).show();
    }

    // ---- recording -----------------------------------------------------------

    private void startRecording() {
        if (captureBusy()) return;
        if (!AutomationRuntime.isEnabled()) { showPermissionPrompt("录制需要无障碍服务来记录操作。"); return; }
        if (!Settings.canDrawOverlays(this)) { showPermissionPrompt("录制时需要悬浮条来结束录制。"); return; }
        new AlertDialog.Builder(this).setTitle("开始录制操作")
                .setMessage("录制会把你在其他应用里的单指操作记成动作：点按松手、长按松手、拖动结束都会被真实执行一次。\n\n不要在购买、支付、转账页面录制。结束录制请点顶部悬浮条，回到本页即可编辑保存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始录制", (d, w) -> beginRecording()).show();
    }

    private void beginRecording() {
        Script target = null;
        try {
            recordingCreatedNew = current == null;
            target = current != null ? current : newScript("录制脚本");
            if (editor != null) editor.pull();
            store.saveDraft(target);
            getSharedPreferences(RECORDER_PREFS, MODE_PRIVATE).edit().clear().apply();
            pendingRecordingScriptId = target.id; recorderPending = true; requestStartedAt = android.os.SystemClock.elapsedRealtime();
            AutomationRecorderService.start(this, target.id);
            schedulePoll(250);
            Toast.makeText(this, "录制已开始，点顶部悬浮条结束", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } catch (RuntimeException ex) { if (target != null) discardEmptyRecording(target.id); pendingRecordingScriptId = null; recorderPending = false; fail(ex); }
    }

    /** Recorder results are polled here; the service writes them when it ends. Returns true when consumed. */
    private boolean checkRecordResult() {
        if (pendingRecordingScriptId == null) return false;
        SharedPreferences prefs = getSharedPreferences(RECORDER_PREFS, MODE_PRIVATE);
        String scriptId = prefs.getString("scriptId", null), state = prefs.getString("status", null);
        if (scriptId == null || state == null || !scriptId.equals(pendingRecordingScriptId)) return false;
        String message = prefs.getString("message", "");
        prefs.edit().clear().apply();
        String targetId = pendingRecordingScriptId;
        pendingRecordingScriptId = null; recorderPending = false;
        if (!"saved".equals(state)) discardEmptyRecording(targetId);
        recordingCreatedNew = false;
        if ("saved".equals(state)) {
            try {
                Script saved = store.load(targetId);
                if (current != null && targetId.equals(current.id)) { current = saved; openEditor(saved); }
                else { showWorkbench(); openEditor(saved); }
                Toast.makeText(this, "录制完成，已打开脚本", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException ex) { showWorkbench(); fail(ex); }
        } else if ("error".equals(state)) {
            showWorkbench();
            new AlertDialog.Builder(this).setTitle("录制结束").setMessage((message == null || message.trim().isEmpty() ? "录制未能保存。" : message) + "\n原来的脚本没有改动。").setPositiveButton("知道了", null).show();
        } else if ("cancelled".equals(state)) {
            if (editor == null) showWorkbench();
            Toast.makeText(this, "录制已取消，脚本没有改动", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---- quick click ---------------------------------------------------------

    private void quickClick() {
        if (captureBusy()) return;
        if (!AutomationRuntime.isEnabled()) { showPermissionPrompt("连点需要无障碍服务来执行点击。"); return; }
        if (!Settings.canDrawOverlays(this)) { showPermissionPrompt("快速连点需要悬浮窗取点与播放控制。"); return; }
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText count = numberInput("10"), interval = numberInput("300");
        TextView hint = new TextView(this); hint.setText("下一步在目标应用里点一次要连点的位置，取到点后从悬浮条启动。");
        ToolUi.text(hint, muted, 13); hint.setPadding(0, 0, 0, dp(4)); form.addView(hint);
        form.addView(formField("重复次数（0=持续到手动停止）", count));
        form.addView(formField("每次间隔（毫秒）", interval));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("快速连点").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("下一步：取点", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int repeat, gap;
            try {
                repeat = Integer.parseInt(count.getText().toString().trim());
                gap = Integer.parseInt(interval.getText().toString().trim());
                if (repeat < 0 || repeat > 100000) throw new NumberFormatException("次数需在 0 到 100000 之间，0 表示持续");
                if (gap < 0 || gap > 3600000) throw new NumberFormatException("间隔需在 0 到 3600000 毫秒之间");
            } catch (NumberFormatException ex) {
                Toast.makeText(this, ex.getMessage() == null ? "请填写整数" : ex.getMessage(), Toast.LENGTH_SHORT).show(); return;
            }
            dialog.dismiss(); startQuickClick(repeat, gap);
        }));
        dialog.show();
    }

    private void startQuickClick(int repeat, int gap) {
        try {
            Script script = newScript(repeat == 0 ? "持续连点" : "快速连点 " + repeat + " 次");
            Step loop = new Step(Step.Type.LOOP); loop.count = repeat; loop.intervalMs = gap;
            Step tap = new Step(Step.Type.TAP); tap.label = "连点目标"; tap.pointPending = true;
            loop.children.add(tap); script.steps.add(loop);
            current = script; store.saveDraft(script);
            openEditor(script);
            pick(tap, "point", true);
        } catch (RuntimeException ex) { fail(ex); }
    }

    private EditText numberInput(String value) {
        EditText input = new EditText(this); input.setText(value); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ToolUi.text(input, ink, 16); input.setMinHeight(dp(48)); input.setBackground(ToolUi.ripple(this, surface, dp(10), Color.parseColor("#5A7774"), dp(1)));
        return input;
    }

    private LinearLayout formField(String title, EditText input) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this); label.setText(title); ToolUi.text(label, muted, 13); label.setPadding(0, dp(8), 0, dp(2)); box.addView(label);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2)); return box;
    }

    // ---- picking -------------------------------------------------------------

    private void pick(Step step, String mode, boolean startAfter) {
        if (captureBusy()) return;
        if (step == null || current == null) return;
        if (!Settings.canDrawOverlays(this)) { showPermissionPrompt("取点需要悬浮窗显示取点条。"); return; }
        if ("template".equals(mode) && !AutomationRuntime.isEnabled()) { showPermissionPrompt("截图取模板需要无障碍服务。"); return; }
        try {
            if (editor != null) editor.pull();
            store.saveDraft(current);
            pendingScriptId = current.id; pendingStepId = step.id; startAfterPick = startAfter;
            getSharedPreferences(PICKER_PREFS, MODE_PRIVATE).edit().clear().apply();
            pickerPending = true; requestStartedAt = android.os.SystemClock.elapsedRealtime();
            AutomationPickerService.start(this, current.id, step.id, mode);
            schedulePoll(250);
            Toast.makeText(this, "在目标应用顶部悬浮条点「开始取点」完成取点", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } catch (RuntimeException ex) { pickerPending = false; startAfterPick = false; fail(ex); }
    }

    private void schedulePoll(int delayMs) {
        if (pollScheduled) return;
        pollScheduled = true;
        if (delayMs <= 0) pickerPollHandler.post(pollTick); else pickerPollHandler.postDelayed(pollTick, delayMs);
    }

    private boolean captureBusy() {
        if (!pickerPending && !recorderPending && !AutomationPickerService.isPicking() && !AutomationRecorderService.isRecording()) return false;
        Toast.makeText(this, "请先在悬浮条完成或取消当前取点/录制", Toast.LENGTH_LONG).show(); return true;
    }
    private void discardEmptyRecording(String id) {
        if (!recordingCreatedNew || id == null) return;
        try { Script value = store.load(id); if (value.steps.isEmpty()) store.delete(id); }
        catch (RuntimeException error) { android.util.Log.w("PocketKit", "Empty recording cleanup failed", error); }
    }
    private void discardQuickClick() {
        try { if (pendingScriptId != null) store.delete(pendingScriptId); }
        catch (RuntimeException error) { android.util.Log.w("PocketKit", "Unfinished quick-click cleanup failed", error); }
        if (pendingScriptId != null && pendingScriptId.equals(selectedScriptId)) { selectedScript = null; selectedScriptId = null; }
        current = null; editor = null; draft = null; rawEditorFields = null; showWorkbench();
    }
    private void checkInterruptedRequests() {
        if (android.os.SystemClock.elapsedRealtime() - requestStartedAt < 3000) return;
        if (pickerPending && !AutomationPickerService.isPicking()) {
            pickerPending = false; if (startAfterPick) discardQuickClick(); startAfterPick = false;
            show("取点已中断，原位置未改动。请重新取点。");
        }
        if (recorderPending && !AutomationRecorderService.isRecording()) {
            recorderPending = false; discardEmptyRecording(pendingRecordingScriptId); recordingCreatedNew = false; pendingRecordingScriptId = null;
            if (editor == null) showWorkbench();
            show("录制已中断，原脚本未改动。已完成动作可从“未完成录制”草稿恢复。");
        }
    }
    private void checkPickerResult() {
        if (!pickerPending || pendingScriptId == null || !consumePickerResult(pendingScriptId, pendingStepId)) return;
        pickerPending = false;
        if (!"saved".equals(pickerResultStatus)) {
            if (startAfterPick) discardQuickClick();
            startAfterPick = false;
            if ("error".equals(pickerResultStatus)) show(pickerResultMessage);
            else Toast.makeText(this, "已取消取点，原位置未改动", Toast.LENGTH_SHORT).show();
            return;
        }
        if (current == null || !pendingScriptId.equals(current.id)) { startAfterPick = false; return; }
        reloadAfterPicker(pendingScriptId, pendingStepId);
    }

    private boolean consumePickerResult(String sid, String stid) {
        if (sid == null || stid == null) return false;
        SharedPreferences prefs = getSharedPreferences(PICKER_PREFS, MODE_PRIVATE);
        boolean ok = sid.equals(prefs.getString("scriptId", null)) && stid.equals(prefs.getString("stepId", null));
        if (ok) { pickerResultStatus = prefs.getString("status", "saved"); pickerResultMessage = prefs.getString("message", ""); prefs.edit().clear().apply(); }
        return ok;
    }

    private void reloadAfterPicker(String scriptId, String stepId) {
        if (current == null || scriptId == null || !scriptId.equals(current.id)) return;
        pickerPending = false;
        try { current = store.load(scriptId); selectedScript = current; openEditor(current); }
        catch (RuntimeException ex) { fail(ex); return; }
        if (startAfterPick) {
            startAfterPick = false;
            if (saveEditor()) startScript(current);
        }
    }

    @Override protected void onStart() { super.onStart(); AutomationRuntime.addListener(runtimeListener); checkPickerResult(); checkRecordResult(); if (pickerPending || recorderPending) schedulePoll(0); }
    @Override protected void onStop() { pickerPollHandler.removeCallbacks(pollTick); pollScheduled = false; AutomationRuntime.removeListener(runtimeListener); super.onStop(); }

    // ---- templates, transfer, capture ---------------------------------------

    private void chooseTemplate() {
        new AlertDialog.Builder(this).setTitle("选择内置模板").setItems(new String[]{"连点", "识字后点", "图片出现点", "条件循环"}, (d, which) -> openEditor(template(which))).show();
    }

    private Script template(int which) {
        Script s = newScript(new String[]{"连点", "识字后点", "图片出现点", "条件循环"}[which]);
        if (which == 0) { Step loop=new Step(Step.Type.LOOP); loop.count=10; loop.intervalMs=300; Step tap=new Step(Step.Type.TAP); tap.label="点击目标"; loop.children.add(tap); s.steps.add(loop); }
        else if (which == 1) { Step wait = new Step(Step.Type.WAIT_TEXT); wait.text = "准备好了"; wait.timeoutMs = 10000; s.steps.add(wait); Step tap=new Step(Step.Type.TAP); tap.useLastMatch=true; s.steps.add(tap); }
        else if (which == 2) { Step wait = new Step(Step.Type.WAIT_IMAGE); wait.threshold = .85; wait.timeoutMs = 10000; s.steps.add(wait); Step tap=new Step(Step.Type.TAP); tap.useLastMatch=true; s.steps.add(tap); }
        else { Step loop = new Step(Step.Type.LOOP); loop.count = 3; loop.intervalMs = 500; Step condition=new Step(Step.Type.IF_TEXT); condition.text="准备好了"; Step tap=new Step(Step.Type.TAP); tap.useLastMatch=true; condition.children.add(tap); Step wait=new Step(Step.Type.WAIT); wait.durationMs=500; condition.otherwise.add(wait); loop.children.add(condition); s.steps.add(loop); }
        return s;
    }

    private Script newScript(String name) { Script s = new Script(); s.id = UUID.randomUUID().toString(); s.name = name; s.steps = new ArrayList<>(); s.updatedAt = System.currentTimeMillis(); return s; }

    private void chooseExport() {
        try { if(editor!=null)editor.pull(); } catch(RuntimeException ex){ fail(ex); return; }
        Script target = current != null ? current : selectedScript;
        if (target == null) { Toast.makeText(this, "先选择要导出的脚本", Toast.LENGTH_SHORT).show(); return; }
        try { selectedScript=target; selectedScriptId=target.id; exportScriptId=target.id; exportPayload=store.exportScript(target); if(exportPayload.length()>ScriptStore.MAX_TRANSFER_BYTES)throw new IllegalArgumentException("脚本导出超过16MiB上限"); startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, (target.name == null ? "script" : target.name) + ".json"), SAVE_SCRIPT); } catch (RuntimeException ex) { fail(ex); }
    }

    private void chooseImageTemplate(Step step) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra("step_id", step.id), 704); }

    private void showPermissions() {
        new AlertDialog.Builder(this).setTitle("权限与后台运行").setMessage("基础点击与单指录制只需无障碍服务和悬浮窗，不需要录屏。通知权限是可选的停止入口；即使关闭通知，也可从悬浮栏操作。\n\n截图识别：Android 11 及以上使用无障碍截图；Android 8–10 需要你逐次授权屏幕捕获。安全窗口无法截图，不会绕过。\n\n后台中断：在应用详情检查电池限制、后台运行、悬浮窗或后台弹出权限。不同品牌的位置不同，不会替你更改系统设置。无障碍显示已开但无法操作时，先关闭再重新开启。\n\n侧载应用若无法开启无障碍，请到系统应用详情的菜单检查“允许受限设置”（仅在你信任此安装包时开启）。播放时按音量下键停止后续动作；已发给系统的当前手势不能撤回。")
                .setNegativeButton("关闭", null).setNeutralButton("通知设置", (d,w) -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())))
                .setPositiveButton("应用与后台设置", (d,w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }

    private void requestProjection() {
        android.media.projection.MediaProjectionManager manager = (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager != null) startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    private void showLogs() { StringBuilder out = new StringBuilder(AutomationRuntime.status()); for (String line : AutomationRuntime.logs()) out.append('\n').append(line); show(out.toString()); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        if (request == REQUEST_PROJECTION) { try { ProjectionCaptureService.start(this, result, data); Toast.makeText(this, "屏幕捕获已授权，可开始采集。", Toast.LENGTH_SHORT).show(); } catch (RuntimeException ex) { fail(ex); } return; }
        final Uri uri=data.getData(); final String imageStepId=pendingStepId; final String savedExportPayload=exportPayload;
        new Thread(() -> { try {
            if (request == OPEN_SCRIPT) { try (InputStream in=getContentResolver().openInputStream(uri)) { byte[] bytes=readAll(in); if(bytes.length==0)throw new IllegalArgumentException("导入文件为空"); Script imported=store.importScript(new String(bytes,StandardCharsets.UTF_8)); store.save(imported); runOnUiThread(this::showWorkbench); } }
            else if (request == SAVE_SCRIPT && savedExportPayload != null) { try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IllegalStateException("脚本导出文件无法打开");out.write(savedExportPayload.getBytes(StandardCharsets.UTF_8));} runOnUiThread(()->Toast.makeText(this,"脚本已导出",Toast.LENGTH_SHORT).show()); }
            else if (request == 704 && imageStepId != null) { try(InputStream in=getContentResolver().openInputStream(uri)){byte[] bytes=readAll(in);String templateId=new cn.xianhua.pocketkit.automation.TemplateStore(this).saveBytes(bytes);runOnUiThread(()->{if(editor!=null){Step step=editor.find(imageStepId);if(step!=null){step.templateId=templateId;editor.refreshStep(step.id);}}});} }
        } catch(Exception ex){runOnUiThread(()->fail(ex));} }).start();
    }

    private byte[] readAll(InputStream in) throws Exception { if (in == null) throw new IllegalArgumentException("无法读取文件"); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; long total = 0; while ((n = in.read(b)) > 0) { total += n; if (total > ScriptStore.MAX_TRANSFER_BYTES) throw new IllegalArgumentException("文件超过16MiB传输上限"); out.write(b, 0, n); } return out.toByteArray(); }

    private void addButton(String text, Runnable action) { Button b = button(text, action); b.setAllCaps(false); }
    private void addButtonTo(LinearLayout parent, String text, Runnable action) { Button b = compact(text); b.setOnClickListener(v -> { try { action.run(); } catch (RuntimeException ex) { fail(ex); } }); parent.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1)); }
    private Button compact(String text) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); ToolUi.text(b, accent, 14); ToolUi.touch(b, dp(48)); b.setBackground(ToolUi.ripple(this, surface, dp(10), Color.TRANSPARENT, 0)); return b; }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(4), 0, dp(4)); return row; }

    private boolean saveDraftBeforeLeaving() {
        if (captureBusy()) return false;
        if (editor == null || current == null) return true;
        try { editor.pull(); store.saveDraft(current); draft=ScriptCodec.encodeDraft(current); return true; } catch (RuntimeException ex) { fail(ex); return false; }
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK && editor != null) { if (saveDraftBeforeLeaving()) showWorkbench(); return true; } return super.onKeyUp(keyCode,event); }
    @Override public void finish() { if (editor != null && !isFinishing()) { if (saveDraftBeforeLeaving()) showWorkbench(); } else super.finish(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (editor != null && current != null) {
            try { editor.pull(); } catch (RuntimeException ex) { /* Raw text is preserved separately. */ }
            try { draft = ScriptCodec.encodeDraft(current); if (!pickerPending && !recorderPending) store.saveDraft(current); } catch (RuntimeException ex) { fail(ex); }
        } else { draft = null; }
        Bundle state = new Bundle();
        state.putString("automation_draft", draft);
        state.putString("pending_script_id", pendingScriptId);
        state.putString("pending_step_id", pendingStepId);
        state.putBoolean("picker_pending", pickerPending);
        state.putBoolean("start_after_pick", startAfterPick);
        state.putString("selected_script_id", selectedScriptId);
        state.putString("export_script_id", exportScriptId);
        state.putString("export_payload", exportPayload);
        state.putString("recorder_script_id", pendingRecordingScriptId);
        state.putBoolean("recorder_pending", recorderPending);
        state.putBoolean("recording_created_new", recordingCreatedNew);
        if (editor != null) state.putBundle("raw_editor_fields", editor.rawSnapshot());
        try {
            editorStateToken = cn.xianhua.pocketkit.automation.AutomationEditorState.save(this, editorStateToken, state);
            out.putString("automation_state_token", editorStateToken);
        } catch (RuntimeException ex) { fail(ex); }
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() { super.onResume(); refreshStatus(); checkRecordResult(); checkPickerResult(); if(pickerPending||recorderPending)schedulePoll(0); }
    /** The recorder's own 「完成」 brings this page back with script_id; the notification opens it too. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (checkRecordResult()) return;
        boolean hadPicker = pickerPending;
        checkPickerResult();
        if (hadPicker && !pickerPending) return;
        String requested = intent == null ? null : intent.getStringExtra("script_id");
        if (requested == null || (current != null && requested.equals(current.id))) return;
        try { openEditor(store.load(requested)); } catch (RuntimeException ex) { fail(ex); }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if(hasFocus){checkRecordResult();checkPickerResult();} }
    @Override protected void onDestroy() { if (Build.VERSION.SDK_INT >= 33 && backCallback != null) Api33Back.unregister(this, backCallback); super.onDestroy(); }
}
