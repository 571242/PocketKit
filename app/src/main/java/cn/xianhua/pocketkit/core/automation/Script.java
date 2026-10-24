package cn.xianhua.pocketkit.core.automation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Mutable local automation script. */
public final class Script {
    public String id = java.util.UUID.randomUUID().toString();
    public String name = "未命名脚本";
    public String targetPackage = "";
    public int width, height;
    public long updatedAt = System.currentTimeMillis(), startDelayMs, timeoutMs;
    /** Whole-script repetitions; 0 repeats until stopped. */
    public int repeatCount = 1;
    /** Delay between repetitions, never a busy loop. */
    public long repeatIntervalMs = 500;
    public List<Step> steps = new ArrayList<>();

    public Script() { }
    public void validate() {
        if (id == null || id.length() == 0 || id.length() > 128) throw new IllegalArgumentException("脚本id无效");
        if (name == null || name.length() > 200) throw new IllegalArgumentException("脚本名称无效");
        if (targetPackage == null) targetPackage = "";
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) throw new IllegalArgumentException("参考分辨率无效");
        if (startDelayMs < 0 || timeoutMs < 0 || startDelayMs > 7 * 24 * 3600_000L || timeoutMs > 7 * 24 * 3600_000L) throw new IllegalArgumentException("脚本时间无效");
        if (repeatCount < 0) throw new IllegalArgumentException("重复次数不能为负数");
        if (repeatIntervalMs < 0 || repeatIntervalMs > 7 * 24 * 3600_000L) throw new IllegalArgumentException("重复间隔无效");
        if (repeatCount == 0 && (steps == null || steps.isEmpty())) throw new IllegalArgumentException("没有步骤的脚本不能一直重复");
        if (steps == null || steps.size() > 10000) throw new IllegalArgumentException("步骤数量无效");
        java.util.HashSet<String> ids = new HashSet<>();
        for (Step step : steps) { if (step == null) throw new IllegalArgumentException("步骤不能为空"); step.validate(0, ids); checkCoordinates(step); }
    }
    private void checkCoordinates(Step step) {
        step.validatePath(width, height);
        if (width > 0 && (step.x >= width || step.x2 >= width || step.right > width)) throw new IllegalArgumentException("坐标超出参考宽度");
        if (height > 0 && (step.y >= height || step.y2 >= height || step.bottom > height)) throw new IllegalArgumentException("坐标超出参考高度");
        for (Step child : step.children) checkCoordinates(child);
        for (Step child : step.otherwise) checkCoordinates(child);
    }
    public Script copy() {
        Script s = new Script(); s.id=id; s.name=name; s.targetPackage=targetPackage; s.width=width; s.height=height;
        s.updatedAt=updatedAt; s.startDelayMs=startDelayMs; s.timeoutMs=timeoutMs;
        s.repeatCount=repeatCount; s.repeatIntervalMs=repeatIntervalMs;
        for (Step step : steps) s.steps.add(step.copy());
        return s;
    }
}
