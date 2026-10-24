package cn.xianhua.pocketkit.core.automation;

import java.util.ArrayDeque;

/** A recording is transactional: cancel never overwrites the original script. */
public final class RecordingSession {
    public static final int MAX_ACTIONS = 500;
    public static final int MAX_PATH_VALUES = Step.MAX_GESTURE_PATH_VALUES;
    public static final long MAX_GESTURE_MS = 10000;
    private final Script script;
    private final ArrayDeque<Integer> undo = new ArrayDeque<>();
    private long lastCompletedAt;

    public RecordingSession(Script original, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("屏幕尺寸无效");
        if (original.width > 0 && (original.width != width || original.height != height))
            throw new IllegalArgumentException("屏幕尺寸已变化，请新建脚本录制，避免混入旧坐标");
        script = original.copy();
        script.width = width;
        script.height = height;
    }

    public Step gesture(int[] points, long durationMs, int touchSlop) {
        if (points == null || points.length < 2 || points.length % 2 != 0 || points.length > MAX_PATH_VALUES)
            throw new IllegalArgumentException("手势轨迹无效");
        if (durationMs < 0 || durationMs > MAX_GESTURE_MS)
            throw new IllegalArgumentException("单次录制手势最长10秒，请分段操作");
        boolean moved = false;
        for (int i = 0; i < points.length; i += 2) {
            if (points[i] < 0 || points[i] >= script.width || points[i + 1] < 0 || points[i + 1] >= script.height)
                throw new IllegalArgumentException("手势超出屏幕");
            long dx = points[i] - points[0], dy = points[i + 1] - points[1];
            if (dx * dx + dy * dy > (long) touchSlop * touchSlop) moved = true;
        }
        Step s = new Step(moved ? Step.Type.SWIPE : durationMs >= 500 ? Step.Type.LONG_PRESS : Step.Type.TAP);
        s.x = points[0]; s.y = points[1];
        if (moved) {
            s.x2 = points[points.length - 2]; s.y2 = points[points.length - 1];
            s.gesturePath = points.clone();
        }
        s.durationMs = Math.max(30, durationMs);
        s.label = moved ? "录制滑动" : s.type == Step.Type.LONG_PRESS ? "录制长按" : "录制点击";
        return s;
    }

    public void append(Step action, long startedAt, long completedAt) {
        if (undo.size() >= MAX_ACTIONS) throw new IllegalStateException("已录制500个动作，请保存后分段录制");
        action.validate(0);
        int before = script.steps.size();
        long gap = lastCompletedAt == 0 ? 0 : Math.max(0, startedAt - lastCompletedAt);
        if (gap >= 50) {
            Step wait = new Step(Step.Type.WAIT);
            wait.durationMs = Math.min(gap, 7 * 24 * 3600_000L);
            wait.label = "录制操作间隔";
            script.steps.add(wait);
        }
        script.steps.add(action.copy());
        undo.addLast(before);
        lastCompletedAt = completedAt;
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        int size = undo.removeLast();
        while (script.steps.size() > size) script.steps.remove(script.steps.size() - 1);
        breakTiming();
        return true;
    }
    public void breakTiming() { lastCompletedAt = 0; }
    public int actionCount() { return undo.size(); }
    public Script snapshot() { Script result = script.copy(); result.updatedAt = System.currentTimeMillis(); return result; }
}
