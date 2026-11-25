package cn.xianhua.pocketkit.reading.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.reading.ReadingModels.Progress;
import cn.xianhua.pocketkit.reading.ReadingStore;
import java.io.ByteArrayInputStream;

/**
 * Shared chrome for the four reading-centre screens.
 *
 * <p>Same palette and touch metrics as {@link cn.xianhua.pocketkit.BaseToolActivity}, so the new
 * screens look like the rest of the app without copying its colours into every activity. Also holds
 * the locked-down WebView settings used by the online reader, kept identical to the local reader's
 * boundary: no scripts, no network, no file or content access.
 */
final class ReadingUi {
    private ReadingUi() { }

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int ink(Context c) { return Color.parseColor(dark(c) ? "#E7EEFC" : "#172D4D"); }
    static int muted(Context c) { return Color.parseColor(dark(c) ? "#A8B8D1" : "#61718A"); }
    static int surface(Context c) { return Color.parseColor(dark(c) ? "#1B293E" : "#FFFFFF"); }
    static int accent(Context c) { return Color.parseColor(dark(c) ? "#9BBCFF" : "#2264DC"); }
    static int base(Context c) { return Color.parseColor(dark(c) ? "#101B2B" : "#F3F6FB"); }
    static int outline(Context c) { return Color.parseColor(dark(c) ? "#40516C" : "#D7E0ED"); }
    static int danger(Context c) { return Color.parseColor(dark(c) ? "#F2B8B5" : "#B3261E"); }

    static int dp(Context c, float v) { return ToolUi.dp(c, v); }

    /** Edge-to-edge system-bar / IME insets while keeping the screen's own side and top padding. */
    static void insets(android.app.Activity host, android.view.View root, int padH, int padTop) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(host, padH) + left, dp(host, padTop) + top, dp(host, padH) + right, bottom);
            return insets;
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) host.getWindow().setDecorFitsSystemWindows(false);
        root.requestApplyInsets();
    }

    /** Borderless 48dp text button, used for toolbars and inline row actions. */
    static Button pill(Context c, String label, int color, Runnable action) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        ToolUi.text(b, color, 14);
        b.setMinHeight(dp(c, 48));
        b.setMinWidth(dp(c, 48));
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        b.setBackground(ToolUi.ripple(c, Color.TRANSPARENT, dp(c, 12), Color.TRANSPARENT, 0));
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Filled primary button, 48dp tall, full width unless the caller overrides its layout. */
    static Button filled(Context c, String label, int fillColor, Runnable action) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        ToolUi.text(b, Color.WHITE, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(c, 50));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setBackground(ToolUi.ripple(c, fillColor, dp(c, 15), Color.TRANSPARENT, 0));
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    static TextView label(Context c, String text, int color, float size) {
        TextView t = new TextView(c);
        t.setText(text);
        ToolUi.text(t, color, size);
        return t;
    }

    static TextView bold(Context c, String text, int color, float size) {
        TextView t = label(c, text, color, size);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    static TextView heading(Context c, String text) {
        TextView t = label(c, text, ink(c), 15);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(c, 18), 0, dp(c, 6));
        return t;
    }

    static TextView note(Context c, String text) {
        TextView t = label(c, text, muted(c), 13);
        t.setPadding(0, dp(c, 4), 0, dp(c, 8));
        return t;
    }

    /** Rounded surface card with a hairline outline; children stack vertically. */
    static LinearLayout card(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        row.setBackground(ToolUi.ripple(c, surface(c), dp(c, 14), outline(c), dp(c, 1)));
        return row;
    }

    /** Centered empty-state line: says why it is empty and what to do next. */
    static TextView empty(Context c, String text) {
        TextView t = label(c, text, muted(c), 14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 26), dp(c, 20), dp(c, 26));
        return t;
    }

    static String reason(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) message = failure == null ? "未知错误" : failure.getClass().getSimpleName();
        return message;
    }

    /** Legacy progress_json payloads cross a Binder; keep a bound so a huge catalogue fails loudly, not with a crash. */
    private static final int MAX_PROGRESS_JSON = 512 * 1024;

    /**
     * Resolve the record the reader/player was opened with. Prefers a stored id — a large catalogue
     * can exceed the Binder/Intent limit, so the hub saves progress first and passes only its id,
     * looked up here through the store. Falls back to the legacy {@code progress_json} extra, bounded.
     */
    static Progress readProgress(android.content.Intent intent, ReadingStore store) {
        String id = intent.getStringExtra("progress_id");
        if (id != null && !id.isBlank() && store != null) {
            Progress found = findProgress(store, id, intent.getStringExtra("progress_kind"));
            if (found != null) return found;
        }
        String raw = intent.getStringExtra("progress_json");
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > MAX_PROGRESS_JSON) throw new IllegalArgumentException("阅读记录过大，无法打开");
        try {
            return Progress.fromJson(new org.json.JSONObject(raw));
        } catch (Exception bad) {
            throw new IllegalArgumentException("阅读记录已损坏：" + reason(bad), bad);
        }
    }

    private static Progress findProgress(ReadingStore store, String id, String kind) {
        for (Progress p : store.history(kind == null || kind.isBlank() ? null : kind)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    /** Same boundary as the offline reader: the chapter text is the only thing this view may render. */
    static void lockDown(WebView view, int background) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSupportMultipleWindows(false);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        view.setBackgroundColor(background);
        view.setOverScrollMode(WebView.OVER_SCROLL_IF_CONTENT_SCROLLS);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return true; // no navigation is ever followed from a chapter body
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("data".equals(scheme) || "about".equals(scheme)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
    }
}
