package cn.xianhua.pocketkit.core.automation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Mutable persisted step model. Coordinates are physical pixels in the recorded orientation. */
public final class Step {
    public static final int MAX_GESTURE_PATH_VALUES = 1024;
    public enum Type { TAP, LONG_PRESS, SWIPE, WAIT, WAIT_UNTIL, INPUT_TEXT, BACK, HOME, RECENTS,
        WAIT_TEXT, WAIT_IMAGE, OCR, IF_TEXT, IF_IMAGE, LOOP, SET_VARIABLE, IF_VARIABLE, STOP }

    public String id = UUID.randomUUID().toString();
    public String label = "";
    public String text = "";
    public String templateId = "";
    public String variable = "";
    public String value = "";
    public String operator = "";
    public String textSource = "ocr";
    public String matchMode = "contains";
    public Type type;
    public int x, y, x2, y2, left, top, right, bottom;
    /** Optional sampled screen coordinates, x/y pairs, for a recorded single-finger swipe. */
    public int[] gesturePath = new int[0];
    public long durationMs, timeoutMs, intervalMs, atEpochMs;
    public int count;
    public double threshold = 0.85;
    public boolean useLastMatch, pointPending;
    /** WAIT_TEXT/WAIT_IMAGE/IF_TEXT/IF_IMAGE: click the matched center, then continue or take the hit branch. */
    public boolean clickMatch;
    public List<Step> children = new ArrayList<>();
    public List<Step> otherwise = new ArrayList<>();

    public Step() { }
    public Step(Type type) { this.type = type; }

    public Step copy() {
        Step s = new Step(type);
        s.id=id; s.label=label; s.text=text; s.templateId=templateId; s.variable=variable; s.value=value;
        s.operator=operator; s.textSource=textSource; s.matchMode=matchMode; s.x=x; s.y=y; s.x2=x2; s.y2=y2;
        s.left=left; s.top=top; s.right=right; s.bottom=bottom; s.durationMs=durationMs; s.timeoutMs=timeoutMs;
        s.intervalMs=intervalMs; s.atEpochMs=atEpochMs; s.count=count; s.threshold=threshold; s.useLastMatch=useLastMatch;
        s.gesturePath = gesturePath.clone(); s.pointPending = pointPending;
        s.clickMatch = clickMatch;
        for (Step child : children) s.children.add(child.copy());
        for (Step child : otherwise) s.otherwise.add(child.copy());
        return s;
    }

    public void validate(int depth) { validate(depth, new HashSet<String>()); }
    void validate(int depth, Set<String> ids) {
        if (depth > 32) throw new IllegalArgumentException("步骤嵌套超过32层");
        if (ids.size() >= 10000) throw new IllegalArgumentException("步骤总数超过10000");
        if (type == null) throw new IllegalArgumentException("步骤动作不能为空");
        if (pointPending) throw new IllegalArgumentException("请先完成真实取点，再运行此脚本");
        if (id == null || id.length() == 0 || id.length() > 128 || !ids.add(id)) throw new IllegalArgumentException("步骤id无效或重复");
        if (timeoutMs < 0 || intervalMs < 0 || durationMs < 0 || atEpochMs < 0) throw new IllegalArgumentException("时间参数不能为负数");
        if (timeoutMs > 7 * 24 * 3600_000L || intervalMs > 7 * 24 * 3600_000L || durationMs > 7 * 24 * 3600_000L) throw new IllegalArgumentException("时间参数过大");
        if (threshold < 0 || threshold > 1 || Double.isNaN(threshold) || Double.isInfinite(threshold)) throw new IllegalArgumentException("匹配阈值无效");
        if (type == Type.LOOP && count < 0) throw new IllegalArgumentException("循环次数不能为负数");
        if (type == Type.SWIPE && durationMs == 0) durationMs = 300;
        if ((type == Type.SWIPE || type == Type.LONG_PRESS) && durationMs > 60000)
            throw new IllegalArgumentException("系统手势最长60秒");
        validatePath(0, 0);
        if (x < 0 || y < 0 || x2 < 0 || y2 < 0 || left < 0 || top < 0 || right < 0 || bottom < 0) throw new IllegalArgumentException("坐标不能为负数");
        boolean noRoi = left == 0 && top == 0 && right == 0 && bottom == 0;
        if (!noRoi && (right <= left || bottom <= top)) throw new IllegalArgumentException("ROI无效");
        if ((type == Type.WAIT_TEXT || type == Type.IF_TEXT) && text.isEmpty()) throw new IllegalArgumentException("文字识别查询不能为空");
        if ((type == Type.WAIT_IMAGE || type == Type.IF_IMAGE) && templateId.isEmpty()) throw new IllegalArgumentException("图片模板不能为空");
        if (type == Type.OCR && variable.isEmpty()) throw new IllegalArgumentException("OCR变量不能为空");
        if ((type == Type.SET_VARIABLE || type == Type.IF_VARIABLE) && variable.isEmpty()) throw new IllegalArgumentException("变量名不能为空");
        if (!"ocr".equals(textSource) && !"nodes".equals(textSource)) throw new IllegalArgumentException("文字来源无效");
        if (!"contains".equals(matchMode) && !"exact".equals(matchMode) && !"regex".equals(matchMode)) throw new IllegalArgumentException("匹配模式无效");
        if (!operator.isEmpty() && !isOperator(operator)) throw new IllegalArgumentException("变量操作符无效");
        if (clickMatch && !(type == Type.WAIT_TEXT || type == Type.WAIT_IMAGE || type == Type.IF_TEXT || type == Type.IF_IMAGE))
            throw new IllegalArgumentException("只有识别动作可以命中后点击");
        if (children == null || otherwise == null) throw new IllegalArgumentException("子步骤列表不能为空");
        for (Step child : children) { if (child == null) throw new IllegalArgumentException("子步骤不能为空"); child.validate(depth + 1, ids); }
        for (Step child : otherwise) { if (child == null) throw new IllegalArgumentException("否则步骤不能为空"); child.validate(depth + 1, ids); }
    }
    public void validatePath(int width, int height) {
        if (gesturePath == null || gesturePath.length % 2 != 0 || gesturePath.length > MAX_GESTURE_PATH_VALUES
                || (gesturePath.length != 0 && (type != Type.SWIPE || gesturePath.length < 4)))
            throw new IllegalArgumentException("滑动轨迹无效");
        for (int i = 0; i < gesturePath.length; i += 2)
            if (gesturePath[i] < 0 || gesturePath[i + 1] < 0 || width > 0 && gesturePath[i] >= width
                    || height > 0 && gesturePath[i + 1] >= height)
                throw new IllegalArgumentException("滑动轨迹超出屏幕");
    }
    private static boolean isOperator(String op) { return "equals".equals(op) || "notEquals".equals(op) || "contains".equals(op) || "regex".equals(op) || "greater".equals(op) || "greaterOrEqual".equals(op) || "less".equals(op) || "lessOrEqual".equals(op) || "==".equals(op) || "!=".equals(op) || ">".equals(op) || ">=".equals(op) || "<".equals(op) || "<=".equals(op); }
}
