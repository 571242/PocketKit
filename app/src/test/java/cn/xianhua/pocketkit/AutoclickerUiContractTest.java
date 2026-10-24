package cn.xianhua.pocketkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executable UI-contract probe for the autoclicker module. No JUnit, no device, no visual claim:
 * it only proves the wiring the recorder/picker services and the home page depend on is present.
 */
public final class AutoclickerUiContractTest {
    private AutoclickerUiContractTest() { }
    public static void main(String[] args) throws Exception { verify(args.length == 0 ? Path.of(".") : Path.of(args[0])); }

    public static void verify(Path root) throws Exception {
        String main = read(root, "app/src/main/java/cn/xianhua/pocketkit/MainActivity.java");
        String tools = read(root, "app/src/main/java/cn/xianhua/pocketkit/AutomationToolsActivity.java");
        String editor = read(root, "app/src/main/java/cn/xianhua/pocketkit/AutomationEditor.java");

        // Home: C01 replaces the old About navigation slot without adding a separate footer row.
        require(main.contains("\"常用\".equals(scope) && \"C01\".equals(e.id)"), "common grid avoids duplicating the persistent autoclicker entry");
        require(main.contains("连点器") && main.contains("nav.addView(") && !main.contains("autoclickerModule("), "autoclicker shares the navigation row");
        require(!main.contains("setText(\"说明\")"), "old About navigation slot is replaced");
        require(main.contains("AutomationToolsActivity.class") && main.contains("putExtra(\"tool_id\", \"C01\")"), "navigation opens the workbench itself");

        // Workbench: the three primary entries, my-script run/edit, and a real delete confirmation.
        require(tools.contains("\"录制操作\"") && tools.contains("\"快速连点\"") && tools.contains("\"新建脚本\""), "primary workbench actions");
        require(tools.contains("addButtonTo(actions, \"运行\"") && tools.contains("addButtonTo(actions, \"编辑\""), "scripts run and edit directly");
        require(tools.contains("删除脚本？") && tools.contains("store.delete(script.id)"), "delete asks first");
        require(tools.contains("脚本还没有动作") && tools.contains("空脚本无法运行"), "empty script is refused with a way forward");

        // Permission state is live, actionable, and refreshed when the user comes back.
        require(tools.contains("AutomationRuntime.isEnabled()") && tools.contains("Settings.canDrawOverlays(this)"), "real permission state");
        require(tools.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS") && tools.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"), "permission buttons open system settings");
        require(tools.contains("void onResume") && tools.contains("private void refreshPermissions()") && tools.contains("refreshPermissions();"), "state refreshes on return");

        // Recorder contract with the service the main agent ships.
        require(tools.contains("automation.AutomationRecorderService") && tools.contains("AutomationRecorderService.start(this, target.id)") && tools.contains("AutomationRecorderService.isRecording()"), "recorder API usage");
        require(tools.contains("\"automation_recorder\"") && tools.contains("\"scriptId\"") && tools.contains("\"status\"") && tools.contains("\"message\""), "recorder result prefs");
        require(tools.contains("\"saved\"") && tools.contains("\"cancelled\"") && tools.contains("\"error\""), "all three recorder outcomes");
        require(tools.contains("pendingRecordingScriptId") && tools.contains("\"recorder_script_id\""), "pending recording survives state restore");
        require(tools.contains("checkRecordResult()") && tools.contains("moveTaskToBack(true)"), "poll consume and return to home screen");
        require(tools.contains("都会被真实执行") && tools.contains("支付"), "record warning states single-finger taps execute");

        // Picker: overlay gate, background start, and the shared poll without duplicate scheduling.
        require(tools.contains("private void pick(") && tools.contains("取点需要悬浮窗") && tools.contains("截图取模板需要无障碍服务"), "picking needs only overlay permission");
        require(tools.contains("AutomationPickerService.start(") && tools.contains("schedulePoll(250)") && tools.contains("startAfterPick"), "pick then start quick click");
        require(tools.contains("if (pollScheduled) return;"), "poll is not scheduled twice");
        require(tools.contains("private void checkPickerResult()") && tools.contains("consumePickerResult"), "picker result poll");

        // Quick click builds a real loop over the existing step model.
        require(tools.contains("Step.Type.LOOP") && tools.contains("loop.count = repeat") && tools.contains("loop.intervalMs = gap"), "quick click generates a loop script");
        require(tools.contains("0 到 100000") && tools.contains("0 到 3600000"), "quick click validates its numbers");

        // Editor: compact summaries, explicit type choice, recursive copy, folding, kept capabilities.
        require(editor.contains("private String summary(Step s)") && editor.contains("展开") && editor.contains("收起"), "steps collapse to a summary");
        require(editor.contains("chooseType") && editor.contains("\"添加什么动作\""), "adding an action asks for its type");
        require(editor.contains("freshCopy") && editor.contains("copy.id=UUID.randomUUID().toString()"), "copy recurses with fresh ids");
        require(editor.contains("高级设置（启动延迟 / 绑定应用 / 总时限）") && editor.contains("advancedOpen"), "advanced fields fold away");
        require(editor.contains("renderBranch(box,s,depth)") && editor.contains("otherwise?s.otherwise:s.children") && editor.contains("renderList(block,list,depth+1") && editor.contains("\"上移\"") && editor.contains("\"下移\""), "branch containers keep the persisted nested tree and ordering");
        require(editor.contains("rawSnapshot") && editor.contains("restoreRaw") && editor.contains("fieldOwner=s.id") && editor.contains("fieldReaders"), "raw fields and validation kept");
        require(editor.contains("case TAP:return\"点击\"") && editor.contains("后续动作使用最近匹配位置") && editor.contains("识别结果变量"), "existing controls kept");
        require(editor.contains("spinner.setSelection(selected,false)") && editor.contains("invalid"), "selector state and validation kept");
        require(editor.contains("activity.dp(44)") || editor.contains("dp(48)"), "touch targets stay 44dp or larger");

        require(tools.contains("tap.pointPending = true") && tools.contains("discardQuickClick()") && tools.contains("checkInterruptedRequests()"), "cancel and process interruption cannot run an unpicked task");
        System.out.println("AutoclickerUiContractTest PASS: shared home navigation / workbench / recorder poll / picker / quick click / editor");
    }

    private static String read(Path root, String relative) throws Exception { return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8); }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
