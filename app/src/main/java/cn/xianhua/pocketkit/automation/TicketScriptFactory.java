package cn.xianhua.pocketkit.automation;

import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import cn.xianhua.pocketkit.device.TicketRecipe;
import java.util.UUID;

/** Converts the legacy 071 recipe to the shared, scene-neutral automation engine. */
public final class TicketScriptFactory {
    private TicketScriptFactory() {}

    public static Script fromRecipe(TicketRecipe recipe, long targetEpochMs, long nowEpochMs, boolean repeat) {
        recipe.validate();
        Script script = new Script();
        script.id = UUID.randomUUID().toString();
        script.name = "071 定时操作模板";
        script.targetPackage = recipe.packageName;
        script.width = recipe.width;
        script.height = recipe.height;
        script.updatedAt = System.currentTimeMillis();
        script.startDelayMs = Math.max(0L, targetEpochMs - nowEpochMs);
        script.timeoutMs = recipe.timeoutSeconds * 1000L;

        if (repeat) {
            Step loop = new Step(Step.Type.LOOP);
            loop.id = UUID.randomUUID().toString();
            loop.label = "循环录制步骤，直到停止或超时";
            loop.count = 0;
            appendPoints(loop.children, recipe, recipe.intervalMs);
            script.steps.add(loop);
        } else {
            appendPoints(script.steps, recipe, recipe.intervalMs);
        }
        script.validate();
        return script;
    }

    private static void appendPoints(java.util.List<Step> target, TicketRecipe recipe, int intervalMs) {
        for (int i = 0; i < recipe.points.size(); i++) {
            cn.xianhua.pocketkit.core.TicketClickPlan.Point point = recipe.points.get(i);
            String rawLabel = i < recipe.labels.size() ? recipe.labels.get(i) : null;
            String label = rawLabel == null ? "" : rawLabel.trim();
            if (!label.isEmpty()) {
                Step waitText = new Step(Step.Type.WAIT_TEXT);
                waitText.id = UUID.randomUUID().toString();
                waitText.text = label;
                waitText.textSource = "nodes";
                waitText.matchMode = "contains";
                waitText.intervalMs = Math.min(intervalMs, 1000);
                waitText.label = "等待识别：" + label;
                target.add(waitText);
            }
            Step tap = new Step(Step.Type.TAP);
            tap.id = UUID.randomUUID().toString();
            tap.x = point.x;
            tap.y = point.y;
            tap.label = label;
            tap.useLastMatch = !label.isEmpty();
            target.add(tap);
            Step wait = new Step(Step.Type.WAIT);
            wait.id = UUID.randomUUID().toString();
            wait.durationMs = intervalMs;
            wait.label = "录制点位间隔";
            target.add(wait);
        }
    }
}
