package cn.xianhua.pocketkit;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.xianhua.pocketkit.local.CalcTools;
import cn.xianhua.pocketkit.local.ColorTools;
import cn.xianhua.pocketkit.local.LifeTools;
import cn.xianhua.pocketkit.local.LocalTool;
import cn.xianhua.pocketkit.local.MoneyTools;
import cn.xianhua.pocketkit.local.TextTools;

/**
 * 本地工具宿主（002 003 035–066 与 B02 B05）。
 *
 * 每个工具是一个 LocalTool 实现，自己在 install() 里用本类的 uiXxx 方法搭界面。
 * 之所以要这层 ui* 桥接：BaseToolActivity 的组装方法是 protected，而工具类在
 * local 子包里，跨包访问 protected 成员是不允许的；本类与 BaseToolActivity 同包，
 * 由它转发即可，不改动公共基类。
 *
 * supports(id) / limitation(id) 是主界面判断入口状态的依据。
 */
public class LocalToolsActivity extends BaseToolActivity {

    private static final Map<String, LocalTool> TOOLS = new LinkedHashMap<>();
    private static final Map<String, String> LIMITS = new LinkedHashMap<>();

    static {
        register(new ColorTools());
        register(new TextTools());
        register(new CalcTools());
        register(new LifeTools());
        register(new MoneyTools());
    }

    private static void register(LocalTool tool) {
        for (String id : tool.ids()) {
            TOOLS.put(id, tool);
        }
        for (Map.Entry<String, String> e : tool.limitations().entrySet()) {
            LIMITS.put(e.getKey(), e.getValue());
        }
    }

    /** 主界面用：该入口是否有真实实现。 */
    public static boolean supports(String id) {
        return id != null && TOOLS.containsKey(id);
    }

    /** 主界面用：入口说明或已知边界。空字符串表示无特别说明。 */
    public static String limitation(String id) {
        if (id == null) {
            return "缺少 tool_id。";
        }
        if (!TOOLS.containsKey(id)) {
            return LIMITS.getOrDefault(id, "暂时没有这个工具。");
        }
        return LIMITS.getOrDefault(id, "");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LocalTool tool = TOOLS.get(toolId);
        if (tool == null) {
            String why = limitation(toolId);
            note(toolId.isEmpty() ? "没有指定要打开的工具。" : "工具 " + toolId + " 暂时不可用。");
            if (!why.isEmpty()) {
                note(why);
            }
            return;
        }
        activeTool = tool;
        String why = LIMITS.get(toolId);
        if (why != null && !why.isEmpty()) {
            note(why);
        }
        try {
            tool.install(this);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private LocalTool activeTool;

    @Override protected void onDestroy() {
        // 工具实例在多个 Activity 之间共享，必须在这里释放它持有的 Activity 引用。
        if (activeTool != null) {
            try {
                activeTool.release();
            } catch (RuntimeException ignored) {
                // 释放失败不应影响退出
            }
            activeTool = null;
        }
        super.onDestroy();
    }

    // ---------- 给 local 子包用的界面桥接 ----------

    /** 当前要打开的工具 id。 */
    public String toolIdOf() {
        return toolId == null ? "" : toolId;
    }

    public EditText uiField(String label, String hint) {
        return field(label, hint);
    }

    public Spinner uiSelect(String label, String... choices) {
        return select(label, choices);
    }

    public Button uiButton(String label, Runnable action) {
        return button(label, action);
    }

    public TextView uiNote(String text) {
        return note(text);
    }

    public void uiShow(String text) {
        show(text);
    }

    public void uiFail(Throwable ex) {
        fail(ex);
    }

    public void uiBackground(Work job) {
        background(job);
    }

    public LinearLayout uiBody() {
        return body;
    }

    public int uiDp(float n) {
        return dp(n);
    }

    /** 与基类同一套配色，供 local 子包里的自制控件复用。 */
    public int uiInk() {
        return ink;
    }

    public int uiMuted() {
        return muted;
    }

    public int uiAccent() {
        return accent;
    }

    public int uiSurface() {
        return surface;
    }

    /** 在当前界面追加一个可交互的自定义视图（例如色块行）。 */
    public void uiAdd(View v, int heightDp) {
        body.addView(v, new LinearLayout.LayoutParams(-1, dp(heightDp)));
    }

    public void uiAdd(View v) {
        body.addView(v, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 工具在多线程里更新界面用。 */
    public void uiRunOnUiThread(Runnable r) {
        runOnUiThread(r);
    }

    /** 给界面加一条分隔空隙。 */
    public void uiGap() {
        View v = new View(this);
        body.addView(v, new LinearLayout.LayoutParams(-1, dp(12)));
    }

    /** 追加只读的小号文本，用于展示长结果。 */
    public TextView uiOutputBlock() {
        TextView v = new TextView(this);
        v.setTextSize(15);
        v.setTextColor(ink);
        v.setTextIsSelectable(true);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackgroundColor(surface);
        body.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    /**
     * 追加一个可反复重绘的容器。
     *
     * 需要动态刷新的面板必须用它，而不是直接操作 uiBody()：
     * body 里同时装着输入框与按钮，直接清空会把用户正在填的表单一起删掉，
     * 而不断往 body 追加又会让界面无限增长。
     */
    public LinearLayout uiContainer() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        body.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }
}
