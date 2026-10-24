package cn.xianhua.pocketkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;

/** Source wiring and pure summary checks only; not a rendered UI or device test. */
public final class AutomationEditorFlowContractTest {
    private AutomationEditorFlowContractTest() { }
    public static void main(String[] args) throws Exception { verify(args.length == 0 ? Path.of(".") : Path.of(args[0])); }

    public static void verify(Path root) throws Exception {
        String editor = new String(Files.readAllBytes(root.resolve("app/src/main/java/cn/xianhua/pocketkit/AutomationEditor.java")), StandardCharsets.UTF_8);
        require(editor.contains("private void addRepeat(") && editor.contains("\"脚本重复运行\""), "visible repeat block");
        require(editor.contains("\"只执行一次\"") && editor.contains("\"指定次数\"") && editor.contains("\"直到手动停止\""), "three repeat modes");
        require(editor.contains("script.repeatCount=1") && editor.contains("script.repeatCount=0") && editor.contains("script.repeatCount=Math.max(2"), "mode mapping");
        require(editor.contains("script.repeatIntervalMs=parsed") && editor.contains("script.repeatCount=parsed"), "repeat values reach script model");
        require(editor.indexOf("addRepeat(this)") < editor.indexOf("高级设置（启动延迟"), "repeat is not hidden in advanced settings");
        require(editor.contains("rawFields.remove(countKey)") && !editor.contains("rawText("), "mode change clears stale count cache without a second raw-value alias");
        require(editor.contains("if(parsed<2)") && !editor.contains("parsed==1?2:parsed"), "specified count is validated, not silently changed");
        require(editor.contains("updateSummary.run()"), "repeat summary updates after edits");

        require(editor.contains("CLICK_MATCH=\"识别到后直接点击目标\"") && editor.contains("s.clickMatch,v->s.clickMatch=v"), "explicit click-on-match switch");
        require(editor.contains("if(canClickMatch(t))b.addView(click)"), "switch is limited to recognition actions");
        require(editor.contains("if(!canClickMatch(vals[pos]))s.clickMatch=false"), "changing type clears stale click flag");
        require(!editor.contains("识别后使用最近匹配位置"), "ineffective recognition-side legacy switch removed");
        require(editor.contains("\"识别到文字\"") && editor.contains("\"未识别到文字\"") && editor.contains("\"识别到图片\"") && editor.contains("\"未识别到图片\""), "explicit recognition branches");
        require(editor.contains("otherwise?s.otherwise:s.children") && editor.contains("renderList(block,list,depth+1"), "both persisted branches render recursively");
        require(editor.contains("preview(list)") && editor.contains("list.size()+\" 个动作"), "branch counts and summaries");
        require(editor.contains("\"添加识别到后的动作\"") && editor.contains("\"添加未识别到的动作\"") && editor.contains("\"添加循环动作\"") && editor.contains("\"添加条件成立后的动作\""), "branch-specific add buttons");
        require(editor.contains("addBlock(box,\"添加下一动作\",owner,index+1)") && editor.contains("list.add(at,step)"), "successor inserts immediately after current row");
        require(!editor.contains("执行这些动作后结束"), "branch does not falsely claim to stop the script");

        require(editor.contains("chooseType(") && editor.contains("pickGroup(") && editor.contains("\"常用动作…\"") && editor.contains("\"全部动作（按类别）…\""), "categorised picker");
        require(editor.contains("rawSnapshot") && editor.contains("restoreRaw") && editor.contains("fieldReaders") && editor.contains("throw new IllegalArgumentException(invalid)"), "draft input and validation retained");
        require(editor.contains("freshCopy") && editor.contains("\"上移\"") && editor.contains("\"下移\"") && editor.contains("\"删除\""), "copy and ordering retained");
        require(editor.contains("requestPick(s,mode)") && editor.contains("listener.chooseTemplate(s)") && editor.contains("activity.dp(48)"), "pickers and touch targets retained");

        Step tap = new Step(Step.Type.TAP);
        Step text = new Step(Step.Type.WAIT_TEXT); text.text = "继续"; text.clickMatch = true;
        Step image = new Step(Step.Type.IF_IMAGE); image.clickMatch = true;
        List<Step> branch = new ArrayList<>(Arrays.asList(tap, text, image));
        eq("点击 → 等待并点击“继续” 等 3 个动作", AutomationEditor.preview(branch), "preview");
        eq("还没有动作", AutomationEditor.preview(new ArrayList<Step>()), "empty preview");
        Script script = new Script();
        require(script.repeatCount == 1 && script.repeatIntervalMs == 500 && !new Step(Step.Type.WAIT_TEXT).clickMatch, "legacy-safe defaults");
        for (Step.Type type : Step.Type.values()) {
            boolean expected = type == Step.Type.WAIT_TEXT || type == Step.Type.WAIT_IMAGE || type == Step.Type.IF_TEXT || type == Step.Type.IF_IMAGE;
            require(AutomationEditor.canClickMatch(type) == expected, "click-match type " + type);
        }
        java.util.Set<Step.Type> grouped = new java.util.HashSet<>();
        for (Step.Type[] group : AutomationEditor.GROUP_TYPES) for (Step.Type type : group) require(grouped.add(type), "no duplicate grouped action");
        require(grouped.size() == Step.Type.values().length, "all actions remain reachable");
        System.out.println("AutomationEditorFlowContractTest PASS: repeat / cache reset / click-on-match / branch wording / insertion / categorised picker");
    }
    private static void eq(String expected, String actual, String label) { if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual); }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
