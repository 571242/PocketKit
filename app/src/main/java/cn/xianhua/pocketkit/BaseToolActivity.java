package cn.xianhua.pocketkit;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared native form chrome; each tool owns its actual inputs and behavior. */
public abstract class BaseToolActivity extends Activity {
    protected String toolId;
    protected LinearLayout body;
    protected LinearLayout root;
    protected final ExecutorService workers = Executors.newSingleThreadExecutor();
    protected int ink, muted, surface, accent;
    private int outline;
    private TextView output;
    private int nextFieldId = 1100;

    public interface Work { void run() throws Exception; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E7EEFC" : "#172D4D");
        muted = Color.parseColor(dark ? "#A8B8D1" : "#61718A");
        surface = Color.parseColor(dark ? "#1B293E" : "#FFFFFF");
        accent = Color.parseColor(dark ? "#9BBCFF" : "#2264DC");
        int base = Color.parseColor(dark ? "#101B2B" : "#F3F6FB");
        outline = Color.parseColor(dark ? "#40516C" : "#D7E0ED");
        toolId = getIntent().getStringExtra("tool_id");
        if (toolId == null) toolId = "";
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(base);
        root.setPadding(dp(16), 0, dp(16), 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(16) + left, top, dp(16) + right, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("返回"); ToolUi.text(back, accent, 15); ToolUi.touch(back, dp(48)); back.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0)); back.setOnClickListener(v -> finish());
        if (toolId.isEmpty()) back.setVisibility(View.GONE);
        header.addView(back, new LinearLayout.LayoutParams(dp(76), dp(52)));
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title")); ToolUi.text(title, ink, 21); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0, dp(12), 0, dp(24));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        output = new TextView(this); ToolUi.text(output, ink, 16); output.setTextIsSelectable(true);
        output.setPadding(dp(14), dp(14), dp(14), dp(14)); output.setBackground(ToolUi.fill(surface, dp(14), Color.TRANSPARENT, 0));
        setContentView(root);
        // PhoneWindow needs the DecorView created by setContentView before exposing its controller.
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        root.requestApplyInsets();
    }
    protected int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    protected TextView note(String text) {
        TextView v = new TextView(this); v.setText(text); ToolUi.text(v, muted, 14); v.setPadding(0, dp(8), 0, dp(8)); body.addView(v); return v;
    }
    protected EditText field(String label, String hint) {
        note(label); EditText v = new EditText(this); v.setId(nextFieldId++); v.setTextColor(ink); v.setHintTextColor(muted); v.setHint(hint);
        ToolUi.text(v, ink, 16); v.setMinHeight(dp(52)); v.setPadding(dp(16), dp(12), dp(16), dp(12)); v.setBackground(ToolUi.ripple(this, surface, dp(16), outline, dp(1))); body.addView(v, new LinearLayout.LayoutParams(-1, -2)); return v;
    }
    protected Spinner select(String label, String... choices) {
        note(label); Spinner spinner = new Spinner(this); spinner.setId(nextFieldId++);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices) {
            @Override public View getView(int p, View convert, android.view.ViewGroup parent) {
                TextView view = (TextView)super.getView(p, convert, parent); ToolUi.text(view, ink, 16); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setBackground(ToolUi.ripple(this, surface, dp(14), outline, dp(1))); spinner.setPadding(dp(12), 0, dp(12), 0);
        body.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52))); return spinner;
    }
    private boolean isDestructive(String label) { return label.contains("删除") || label.contains("清空") || label.contains("移除") || label.contains("清除"); }
    protected Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); ToolUi.text(b, isDestructive(label) ? Color.WHITE : surface, 15); b.setAllCaps(false); b.setMinHeight(dp(50)); b.setPadding(dp(16), dp(10), dp(16), dp(10)); b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); b.setStateListAnimator(null); b.setBackground(ToolUi.ripple(this, isDestructive(label) ? Color.parseColor("#B3261E") : accent, dp(15), Color.TRANSPARENT, 0));
        b.setOnClickListener(v -> { try { action.run(); } catch (Exception | LinkageError ex) { fail(ex); } });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.setMargins(0, dp(8), 0, 0);
        body.addView(b, layout); return b;
    }
    protected void show(String text) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> show(text)); return;
        }
        if (isFinishing() || isDestroyed()) return;
        if (output.getParent() == null) body.addView(output, new LinearLayout.LayoutParams(-1, -2));
        output.setText(text == null ? "" : text);
    }
    protected void fail(Throwable ex) {
        String message = ex.getMessage(); show("未完成：" + (message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message));
    }
    protected void background(Work job) {
        show("正在处理…");
        workers.execute(() -> { try { job.run(); } catch (Exception | LinkageError ex) { runOnUiThread(() -> fail(ex)); } });
    }
    @Override protected void onDestroy() { workers.shutdownNow(); super.onDestroy(); }
}
