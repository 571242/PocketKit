package cn.xianhua.pocketkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Executable C-path contract probe; no device or visual claim. */
public final class AutomationUiContractTest {
    private AutomationUiContractTest() { }
    public static void main(String[] args) throws Exception { verify(args.length == 0 ? Path.of(".") : Path.of(args[0])); }
    public static void verify(Path root) throws Exception {
        String a=read(root,"app/src/main/java/cn/xianhua/pocketkit/AutomationToolsActivity.java");
        String e=read(root,"app/src/main/java/cn/xianhua/pocketkit/AutomationEditor.java");
        String p=read(root,"app/src/main/java/cn/xianhua/pocketkit/automation/AutomationPickerService.java");
        String o=read(root,"app/src/main/java/cn/xianhua/pocketkit/automation/AutomationOverlayService.java");
        require(a.contains("extends BaseToolActivity")&&a.contains("supports(String id)"),"C01 host");
        require(a.replaceAll("\\s+", "").contains("pendingScriptId=current.id")&&a.contains("store.saveDraft(current)")&&a.contains("consumePickerResult")&&a.contains("store.load(scriptId)")&&a.contains("encodeDraft"),"picker draft save/reload");
        require(a.contains("script_id")&&a.contains("导出此脚本")&&a.contains("ACTION_CREATE_DOCUMENT"),"entry and export");
        require(a.contains("new Thread")&&a.contains("readAll(in)")&&a.contains("TemplateStore(this).saveBytes"),"SAF off-main IO");
        require(a.contains("AutomationRuntime.addListener")&&a.contains("AutomationRuntime.removeListener")&&a.contains("POST_NOTIFICATIONS")&&a.contains("OnBackInvokedDispatcher")&&!a.contains("registerReceiver"),"runtime/lint/back lifecycle");
        require(e.contains("renderBranch(box,s,depth)")&&e.contains("otherwise?s.otherwise:s.children")&&e.contains("renderList(block,list,depth+1")&&e.contains("上移")&&e.contains("下移"),"branch containers render the nested tree and ordering");
        require(e.contains("后续动作使用最近匹配位置")&&e.contains("识别结果变量")&&e.contains("识别/循环间隔")&&e.contains("大于"),"runner controls");
        require(e.contains("case TAP:return\"点击\"")&&e.contains("启动延迟（秒）")&&e.contains("invalid")&&e.contains("rawSnapshot")&&e.contains("restoreRaw")&&e.contains("spinner.setSelection(selected,false)")&&e.contains("fieldOwner=s.id")&&e.contains("fieldReaders"),"Chinese controls, validation and selector state");
        require(p.contains("startForegroundService")&&p.contains("开始采集")&&p.contains("RESULT_ACTION")&&p.contains("AtomicBoolean")&&p.contains("尚未选择目标")&&p.contains("createReadyStrip")&&p.contains("script.width")&&p.contains("captureWorker")&&p.contains("new TemplateStore(this).delete"),"picker foreground/fallback/reference/rollback");
        require(p.contains("cancelWork")&&p.contains("token!=generation")&&p.contains("AutomationOverlayService.setCaptureHidden(false)"),"picker cancel/restore");
        require(o.contains("AutomationRuntime.stop(reason)")&&o.contains("notifyFailure")&&o.contains("AutomationRuntime.controlsReady()")&&o.contains("AutomationRuntime.controlsClosed()")&&o.contains("Looper.myLooper()"),"overlay error/lifecycle/hide timing");
        require(a.contains("Step.Type.LOOP")&&a.contains("loop.children"),"real loop template");
        System.out.println("AutomationUiContractTest PASS: save-reload/export/SAF/tree/picker/overlay/runtime");
    }
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
