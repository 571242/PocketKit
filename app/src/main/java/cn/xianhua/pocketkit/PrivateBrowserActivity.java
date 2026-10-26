package cn.xianhua.pocketkit;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.ScriptHandler;
import cn.xianhua.pocketkit.browser.BrowserPrivacy;
import cn.xianhua.pocketkit.browser.BrowserUrls;
import cn.xianhua.pocketkit.browser.BrowserVault;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The only WebView host in :private_browser: one thin top bar, real tabs, no bottom toolbar.
 * Disk operations are coordinated by BrowserHomeActivity.
 */
public final class PrivateBrowserActivity extends Activity {
    private static final int MAX_TABS = 8;
    private static final long EXTRAS_DEADLINE_MS = 6000;

    // Process-level: Activity recreation must reuse the same lease, never re-lock the same JVM.
    private static BrowserVault.Lease processLease;
    private static boolean suffixSet;

    private static final class Tab {
        WebView view;
        String url = "";
        String title = "";
        int version;
        String restoreUrl, restoreOrigin;
        int restoreY;
        ScriptHandler script;
    }

    /** One row in the switcher sheet: tabs and bookmarks share the same list. */
    private static final class Row {
        final String title;
        final String subtitle;
        Runnable run, close;
        boolean highlight;
        Row(String title, String subtitle) { this.title = title; this.subtitle = subtitle; }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BrowserVault vault;
    private JSONObject session;
    private int active = -1;
    private FrameLayout root, viewport, sheetHost;
    private LinearLayout column, chrome, startPage, busyView;
    private EditText address;
    private TextView backGlyph, forwardGlyph, tabCount, menuGlyph;
    private ProgressBar progress;
    private int ink, muted, surface, accent, base, outline;
    private boolean busy, leaving, initialized;
    private int freezeAttempt;
    private boolean capturePending;
    private Object backCallback;

    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "无痕浏览").putExtra("tool_id", "N02");
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) backCallback = Api33Back.register(this);
        try {
            if (Build.VERSION.SDK_INT < 28) throw new IllegalStateException("需要 Android 9 及以上");
            vault = new BrowserVault(this);
            if (processLease == null) processLease = vault.tryLease();
            if (processLease == null) throw new IllegalStateException("另一个无痕窗口正在运行，请先返回它");
            session = vault.active(getIntent().getStringExtra("session_token"));
            if (!suffixSet) {
                WebView.setDataDirectorySuffix(BrowserVault.SUFFIX);
                WebView.setWebContentsDebuggingEnabled(false);
                suffixSet = true;
            }
            initialized = true;
            protectServiceWorker();
            restore();
        } catch (Exception | LinkageError ex) {
            // No WebView here: if creating one is what failed, retrying it would fail the same way.
            startPage.removeAllViews();
            TextView text = new TextView(this);
            ToolUi.text(text, ink, 15);
            text.setGravity(Gravity.CENTER);
            text.setPadding(dp(28), dp(28), dp(28), dp(28));
            text.setText("无法安全打开会话，未加载网页。\n" + ex.getMessage());
            startPage.addView(text, new LinearLayout.LayoutParams(-1, -2));
            startPage.bringToFront();
            startPage.setVisibility(View.VISIBLE);
            startPage.setOnClickListener(v -> leave());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (leaving) Toast.makeText(this, "正在退出", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- layout

    private void buildUi() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E7EEFC" : "#172D4D");
        muted = Color.parseColor(dark ? "#A8B8D1" : "#61718A");
        surface = Color.parseColor(dark ? "#1B293E" : "#FFFFFF");
        accent = Color.parseColor(dark ? "#9BBCFF" : "#2264DC");
        base = Color.parseColor(dark ? "#101B2B" : "#F3F6FB");
        outline = Color.parseColor(dark ? "#40516C" : "#D7E0ED");

        root = new FrameLayout(this);
        root.setBackgroundColor(base);
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
            column.setPaddingRelative(left, top, right, bottom);
            if (sheetHost != null) sheetHost.setPaddingRelative(left, top, right, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        // One thin bar on top, nothing at the bottom: the page owns the rest of the screen.
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        chrome = buildChrome();
        column.addView(chrome, new LinearLayout.LayoutParams(-1, -2));

        viewport = new FrameLayout(this);
        column.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));

        startPage = new LinearLayout(this);
        startPage.setOrientation(LinearLayout.VERTICAL);
        startPage.setGravity(Gravity.CENTER);
        startPage.setBackgroundColor(base);
        startPage.setOnClickListener(v -> focusAddress());
        viewport.addView(startPage, new FrameLayout.LayoutParams(-1, -1));
        rebuildStartPage();
        sheetHost = new FrameLayout(this);
        sheetHost.setVisibility(View.GONE);
        root.addView(sheetHost, new FrameLayout.LayoutParams(-1, -1));

        busyView = new LinearLayout(this);
        busyView.setOrientation(LinearLayout.VERTICAL);
        busyView.setGravity(Gravity.CENTER);
        busyView.setPadding(dp(24), dp(18), dp(24), dp(18));
        busyView.setBackground(ToolUi.fill(surface, dp(18), outline, dp(1)));
        ProgressBar wheel = new ProgressBar(this);
        busyView.addView(wheel, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView busyText = new TextView(this);
        ToolUi.text(busyText, ink, 14);
        busyText.setId(android.R.id.text1);
        busyText.setPadding(0, dp(10), 0, 0);
        busyView.addView(busyText);
        busyView.setVisibility(View.GONE);
        FrameLayout.LayoutParams busyLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(busyView, busyLayout);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        root.requestApplyInsets();
    }

    private void rebuildStartPage() {
        startPage.removeAllViews();
        TextView title = new TextView(this);
        title.setText("无痕浏览");
        ToolUi.text(title, ink, 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        startPage.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(this);
        hint.setText("退出即清除，不留记录");
        ToolUi.text(hint, muted, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        startPage.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout buildChrome() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(surface);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(4), dp(6));
        bar.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));

        backGlyph = glyph("←", 20);
        backGlyph.setOnClickListener(v -> { Tab t = current(); if (!busy && !leaving && t != null && t.view.canGoBack()) t.view.goBack(); });
        row.addView(backGlyph, new LinearLayout.LayoutParams(dp(38), -1));

        forwardGlyph = glyph("→", 20);
        forwardGlyph.setOnClickListener(v -> { Tab t = current(); if (!busy && !leaving && t != null && t.view.canGoForward()) t.view.goForward(); });
        row.addView(forwardGlyph, new LinearLayout.LayoutParams(dp(38), -1));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setSaveEnabled(false);
        address.setHint("搜索或输入网址");
        address.setHintTextColor(muted);
        address.setTextColor(ink);
        address.setTextSize(14);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        address.setBackground(ToolUi.ripple(this, base, dp(17), Color.TRANSPARENT, 0));
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setCursorVisible(false);
        // Tapping an already-focused bar only brings the keyboard back; focus itself is handled below.
        address.setOnClickListener(v -> {
            if (!address.hasFocus()) return;
            address.setCursorVisible(true);
            InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
        });
        address.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                Tab t = current();
                address.setText(t == null || t.url.isEmpty() ? "" : t.url);
                address.setSelection(address.getText().length());
                address.setCursorVisible(true);
                        address.postDelayed(() -> {
                    InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                    if (ime != null) ime.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
                }, 120);
            } else {
                address.setCursorVisible(false);
                InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if (ime != null) ime.hideSoftInputFromWindow(address.getWindowToken(), 0);
                syncAddress();
            }
        });
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                if (!busy && !leaving) try { navigate(); } catch (Exception ex) { address.setError(ex.getMessage()); }
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressLayout = new LinearLayout.LayoutParams(0, dp(36), 1);
        addressLayout.setMargins(dp(2), 0, dp(2), 0);
        row.addView(address, addressLayout);

        tabCount = glyph("1", 12);
        tabCount.setTypeface(null, Typeface.BOLD);
        tabCount.setBackground(ToolUi.ripple(this, base, dp(12), Color.TRANSPARENT, 0));
        tabCount.setOnClickListener(v -> { if (!busy && !leaving) openTabs(); });
        LinearLayout.LayoutParams tabLayout = new LinearLayout.LayoutParams(dp(24), dp(24));
        row.addView(tabCount, tabLayout);

        menuGlyph = glyph("⋮", 20);
        menuGlyph.setOnClickListener(v -> { if (!busy && !leaving) showMenu(); });
        LinearLayout.LayoutParams menuLayout = new LinearLayout.LayoutParams(dp(34), -1);
        menuLayout.setMargins(dp(2), 0, 0, 0);
        row.addView(menuGlyph, menuLayout);

        View divider = new View(this);
        divider.setBackgroundColor(outline);
        bar.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.INVISIBLE);
        bar.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        return bar;
    }

    private TextView glyph(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        ToolUi.text(view, ink, size);
        view.setGravity(Gravity.CENTER);
        view.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(18), Color.TRANSPARENT, 0));
        return view;
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private Tab current() { return active >= 0 && active < tabs.size() ? tabs.get(active) : null; }

    // ---------------------------------------------------------------- tabs

    private Tab newTab(String url, JSONObject restore) { return newTab(url, restore, true); }

    /** A registration-only tab: same wiring as a real one, no load. */
    private Tab newBlankTab() {
        Tab tab = new Tab();
        tab.view = new WebView(this);
        configure(tab.view);
        tab.view.setWebViewClient(client(tab));
        tab.view.setWebChromeClient(chromeClient(tab));
        tab.view.setVisibility(View.GONE);
        viewport.addView(tab.view, new FrameLayout.LayoutParams(-1, -1));
        return tab;
    }

    /** background=true keeps the user on the page they tapped inside; used for popup ads. */
    private Tab newTab(String url, JSONObject restore, boolean foreground) {
        if (tabs.size() >= MAX_TABS) { toast("最多同时打开 " + MAX_TABS + " 个标签页"); return null; }
        Tab tab = newBlankTab();
        tabs.add(tab);
        if (url != null && !url.isEmpty()) {
            String target;
            try { target = BrowserUrls.network(url); }
            catch (RuntimeException ex) {
                // A rejected address must not leave a half-wired blank tab behind.
                tabs.remove(tab);
                destroyTab(tab);
                toast(ex.getMessage());
                return null;
            }
            tab.url = target;
            if (restore != null) {
                tab.restoreOrigin = BrowserUrls.origin(target);
                tab.restoreUrl = target;
                tab.restoreY = Math.max(0, restore.optInt("scrollY", 0));
                try { tab.script = BrowserPrivacy.restoreSession(tab.view, restore); }
                catch (Exception ignored) { tab.script = null; tab.restoreUrl = null; }
            }
            tab.view.loadUrl(target);
        }
        if (foreground) select(tabs.size() - 1); else syncTabs();
        return tab;
    }

    private void select(int index) {
        if (index < 0 || index >= tabs.size()) return;
        active = index;
        if (address != null && address.hasFocus()) address.clearFocus();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (tab.view != null) tab.view.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        Tab tab = current();
        boolean blank = tab == null || tab.url.isEmpty();
        if (blank) startPage.bringToFront();
        startPage.setVisibility(blank ? View.VISIBLE : View.GONE);
        progress.setVisibility(View.INVISIBLE);
        syncAddress();
        syncNav();
        syncTabs();
        hideKeyboard();
    }

    private void syncTabs() { if (tabCount != null) tabCount.setText(String.valueOf(Math.max(1, tabs.size()))); }

    private void syncNav() {
        Tab tab = current();
        boolean back = tab != null && tab.view != null && tab.view.canGoBack();
        boolean forward = tab != null && tab.view != null && tab.view.canGoForward();
        // Both arrows stay in place and only dim: hiding them would make the address bar jump.
        backGlyph.setAlpha(back ? 1f : 0.3f);
        forwardGlyph.setAlpha(forward ? 1f : 0.3f);
        backGlyph.setTextColor(ink);
        forwardGlyph.setTextColor(ink);
    }

    private void syncAddress() {
        if (address == null || address.hasFocus()) return;
        Tab tab = current();
        String label = tab == null || tab.url.isEmpty() ? "" : compact(tab.url);
        if (!label.contentEquals(address.getText())) address.setText(label);
    }

    private String compact(String url) {
        try {
            String value = BrowserUrls.network(url);
            int scheme = value.indexOf("://");
            String rest = scheme < 0 ? value : value.substring(scheme + 3);
            if (rest.startsWith("www.")) rest = rest.substring(4);
            String host = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            String path = rest.substring(host.length());
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String label = host + path;
            return label.length() > 64 ? label.substring(0, 63) + "…" : label;
        } catch (RuntimeException ex) { return url; }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.remove(index);
        destroyTab(tab);
        if (tabs.isEmpty()) {
            // Closing the last tab leaves a fresh blank one; it never dumps the user out.
            hideSheet();
            tabs.add(newBlankTab());
            active = 0;
            select(0);
            return;
        }
        if (index < active) active--;
        else if (index == active) active = Math.min(index, tabs.size() - 1);
        select(active);
        if (sheetHost.getVisibility() == View.VISIBLE) openTabs();
    }

    private void destroyTab(Tab tab) {
        WebView view = tab.view;
        tab.view = null;
        if (view == null) return;
        try {
            view.stopLoading();
            WebSettings settings = view.getSettings();
            settings.setBlockNetworkLoads(true);
            settings.setJavaScriptEnabled(false);
            if (view.getParent() != null) ((ViewGroup)view.getParent()).removeView(view);
            view.setWebChromeClient(null);
            view.setWebViewClient(new WebViewClient());
            view.destroy();
        } catch (RuntimeException ignored) { }
    }

    // ---------------------------------------------------------------- chrome

    private void focusAddress() {
        if (busy || leaving) return;
        Tab tab = current();
        address.setText(tab == null || tab.url.isEmpty() ? "" : tab.url);
        address.requestFocus();
        address.setSelection(address.getText().length());
        address.setCursorVisible(true);
        InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        if (address == null) return;
        InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void openUrl(String url) {
        Tab tab = current();
        if (tab == null) { newTab(url, null); return; }
        if (!BrowserUrls.allowed(url)) { toast("只支持 HTTP / HTTPS 网页"); return; }
        tab.url = url;
        startPage.setVisibility(View.GONE);
        tab.view.loadUrl(url);
    }

    private void navigate() {
        Tab tab = current();
        if (tab == null || busy || leaving) return;
        openUrl(BrowserUrls.address(address.getText().toString()));
        address.clearFocus();
    }

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false); s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true); s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // Real windows let target=_blank and popups become tabs instead of replacing this page.
        s.setSupportMultipleWindows(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        try { BrowserPrivacy.apply(this, view, session); }
        catch (Exception ex) { toast("页面防护未完全启用：" + ex.getMessage()); }
    }

    private void protectServiceWorker() {
        ServiceWorkerController service = ServiceWorkerController.getInstance();
        service.getServiceWorkerWebSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        service.getServiceWorkerWebSettings().setAllowContentAccess(false);
        service.getServiceWorkerWebSettings().setAllowFileAccess(false);
        service.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return BrowserPrivacy.tracker(request.getUrl().getHost()) ? blocked() : null;
            }
        });
    }

    private WebViewClient client(final Tab tab) {
        return new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (busy || leaving) return true;
                boolean denied = !BrowserUrls.allowed(request.getUrl().toString());
                if (denied && request.isForMainFrame()) toast("已拦截外部应用或非网页链接");
                return denied;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme)) return blocked();
                return !request.isForMainFrame() && BrowserPrivacy.tracker(request.getUrl().getHost()) ? blocked() : null;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (v != tab.view || leaving) return;
                tab.version++;
                tab.url = url == null ? "" : url;
                if (tab != current()) return;
                if (!tab.url.isEmpty()) startPage.setVisibility(View.GONE);
                syncAddress();
                syncNav();
                syncTabs();
            }
            @Override public void onPageCommitVisible(WebView v, String url) {
                if (tab.script != null && BrowserUrls.allowed(url) && BrowserUrls.origin(url).equals(tab.restoreOrigin)) {
                    tab.script.remove(); tab.script = null;
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (v != tab.view || leaving) return;
                String title = v.getTitle();
                if (title != null && !title.trim().isEmpty()) tab.title = title.trim();
                if (tab.restoreUrl != null && tab.restoreUrl.equals(url)) {
                    int y = tab.restoreY, version = tab.version;
                    tab.restoreUrl = null;
                    v.postDelayed(() -> { if (v == tab.view && tab.version == version && !leaving) v.scrollTo(0, y); }, 300);
                }
                if (tab != current()) return;
                syncAddress();
                syncNav();
                syncTabs();
            }
            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, SslError error) {
                h.cancel();
                if (v == tab.view && tab == current()) toast("证书校验失败，已阻止访问");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (v == tab.view && request.isForMainFrame() && tab == current()) toast("加载失败，请检查地址、网络或系统 WebView");
            }
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                if (v == tab.view) {
                    int index = tabs.indexOf(tab);
                    toast("网页进程停止，已关闭该标签页");
                    if (index >= 0) closeTab(index);
                }
                return true;
            }
        };
    }

    private WebChromeClient chromeClient(final Tab tab) {
        return new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int value) {
                if (v != tab.view || busy || leaving || tab != current()) return;
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
            }
            @Override public void onReceivedTitle(WebView v, String title) {
                if (v == tab.view && title != null && !title.trim().isEmpty()) tab.title = title.trim();
            }
            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // No tap means no user intent: that is an ad. Drop it and keep the page as it is.
                if (!isUserGesture || busy || leaving) return true;
                if (!(resultMsg.obj instanceof WebView.WebViewTransport)) return true;
                // Open behind the current page, so nothing can take the page away mid-read.
                Tab opened = newTab(null, null, false);
                if (opened == null) return true;
                WebView.WebViewTransport transport = (WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(opened.view);
                resultMsg.sendToTarget();
                toast("已在后台标签页打开");
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                for (int i = 0; i < tabs.size(); i++) {
                    if (tabs.get(i).view == window) { closeTab(i); return; }
                }
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                callback.onReceiveValue(null);
                toast("此浏览器不向网页提供本地文件");
                return true;
            }
        };
    }

    private static WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }

    // ---------------------------------------------------------------- sheet

    private void showSheet(String heading, List<Row> rows) {
        hideSheet();
        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        scrim.setOnClickListener(v -> hideSheet());
        sheetHost.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(ToolUi.fill(surface, dp(22), outline, dp(1)));
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText(heading);
        ToolUi.text(title, ink, 16);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(6), dp(4), dp(6), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        for (Row row : rows) panel.addView(rowView(row), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToOutline(false);
        scroll.addView(panel);
        sheetHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM));
        sheetHost.setVisibility(View.VISIBLE);

        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(16);
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - dp(16);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int limit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.66f);
        FrameLayout.LayoutParams sheetLayout = new FrameLayout.LayoutParams(-1, Math.min(panel.getMeasuredHeight() + dp(12), limit), Gravity.BOTTOM);
        sheetLayout.setMargins(dp(8), 0, dp(8), dp(8));
        scroll.setLayoutParams(sheetLayout);
    }

    private View rowView(final Row row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(11), dp(10), dp(11));
        line.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(row.title);
        ToolUi.text(label, row.highlight ? accent : ink, 15);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(label, new LinearLayout.LayoutParams(-1, -2));
        if (row.subtitle != null && !row.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(row.subtitle);
            ToolUi.text(sub, muted, 12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        if (row.close != null) {
            TextView close = new TextView(this);
            close.setText("✕");
            ToolUi.text(close, muted, 14);
            close.setGravity(Gravity.CENTER);
            close.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(16), Color.TRANSPARENT, 0));
            close.setOnClickListener(v -> row.close.run());
            line.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        line.setOnClickListener(v -> { hideSheet(); if (row.run != null) row.run.run(); });
        return line;
    }

    private void hideSheet() {
        if (sheetHost != null) {
            sheetHost.removeAllViews();
            sheetHost.setVisibility(View.GONE);
        }
    }

    private void openTabs() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            final int index = i;
            String label = tab.title == null || tab.title.trim().isEmpty()
                    ? (tab.url.isEmpty() ? "新建标签页" : tab.url) : tab.title;
            Row row = new Row(label, tab.url.isEmpty() ? "空白页" : compact(tab.url));
            row.highlight = i == active;
            row.run = () -> select(index);
            if (tabs.size() > 1 || !tab.url.isEmpty()) row.close = () -> closeTab(index);
            rows.add(row);
        }
        Row add = new Row("＋ 新标签页", "打开空白页");
        add.run = () -> { if (newTab(null, null) != null) focusAddress(); };
        rows.add(add);
        showSheet("标签页 · " + tabs.size(), rows);
    }

    // ---------------------------------------------------------------- bookmarks

    private JSONArray bookmarksData() throws Exception {
        return new JSONArray(getSharedPreferences("private_browser_saved", 0).getString("bookmarks", "[]"));
    }

    private void storeBookmarks(JSONArray items) {
        if (!getSharedPreferences("private_browser_saved", 0).edit().putString("bookmarks", items.toString()).commit())
            throw new IllegalStateException("收藏保存失败");
    }

    private void openBookmarks() {
        List<Row> rows = new ArrayList<>();
        Row add = new Row("＋ 收藏当前页", "");
        add.run = this::saveBookmark;
        rows.add(add);
        try {
            JSONArray items = bookmarksData();
            for (int i = 0; i < items.length(); i++) {
                final JSONObject entry = items.getJSONObject(i);
                final int index = i;
                Row row = new Row(entry.optString("title", "未命名"), entry.optString("url"));
                row.run = () -> {
                    try { openUrl(BrowserUrls.network(entry.optString("url"))); }
                    catch (Exception ex) { toast(ex.getMessage()); }
                };
                row.close = () -> confirmDeleteBookmark(index);
                rows.add(row);
            }
        } catch (Exception ex) {
            toast("收藏读取失败：" + ex.getMessage());
        }
        showSheet("收藏夹", rows);
    }

    private void confirmDeleteBookmark(int index) {
        new AlertDialog.Builder(this).setTitle("删除此收藏？")
                .setMessage("不会删除网页，只是从收藏夹移走。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        JSONArray items = bookmarksData();
                        items.remove(index);
                        storeBookmarks(items);
                        openBookmarks();
                    } catch (Exception ex) { toast(ex.getMessage()); }
                }).setNegativeButton("取消", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void saveBookmark() {
        Tab tab = current();
        if (tab == null || !BrowserUrls.allowed(tab.url)) { toast("先打开网页再收藏"); return; }
        final String original = BrowserUrls.network(tab.url);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), dp(6));
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        EditText title = new EditText(this);
        title.setHint("标题");
        title.setSingleLine(true);
        title.setSaveEnabled(false);
        title.setText(tab.title);
        form.addView(title);
        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setSingleLine(true);
        url.setSaveEnabled(false);
        url.setText(original);
        form.addView(url);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("加入收藏").setView(form)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String target = BrowserUrls.network(url.getText().toString());
                String name = title.getText().toString().trim();
                JSONObject entry = new JSONObject().put("title", name.isEmpty() ? target : name).put("url", target);
                JSONArray items = bookmarksData();
                boolean replaced = false;
                for (int i = 0; i < items.length(); i++) {
                    if (target.equals(items.getJSONObject(i).optString("url"))) { items.put(i, entry); replaced = true; break; }
                }
                if (!replaced) items.put(entry);
                storeBookmarks(items);
                dialog.dismiss();
                toast("已收藏");
            } catch (Exception ex) { url.setError(ex.getMessage()); }
        }));
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        Tab tab = current();
        boolean live = tab != null && BrowserUrls.allowed(tab.url);
        PopupMenu popup = new PopupMenu(this, menuGlyph);
        popup.getMenu().add("新标签页");
        popup.getMenu().add("标签页");
        if (live) { popup.getMenu().add("刷新"); popup.getMenu().add("收藏当前页"); }
        popup.getMenu().add("收藏夹");
        if (tabs.size() > 1) popup.getMenu().add("关闭当前标签页");
        popup.getMenu().add("冻结并退出");
        popup.getMenu().add("退出");
        popup.getMenu().add("说明");
        popup.setOnMenuItemClickListener(item -> {
            String label = String.valueOf(item.getTitle());
            if ("新标签页".equals(label)) { newTab(null, null); focusAddress(); }
            else if ("标签页".equals(label)) openTabs();
            else if ("刷新".equals(label)) { Tab t = current(); if (t != null) t.view.reload(); }
            else if ("收藏当前页".equals(label)) saveBookmark();
            else if ("收藏夹".equals(label)) openBookmarks();
            else if ("关闭当前标签页".equals(label)) closeTab(active);
            else if ("冻结并退出".equals(label)) confirmFreeze();
            else if ("退出".equals(label)) finish();
            else if ("说明".equals(label)) explain();
            return true;
        });
        popup.show();
    }

    private void explain() {
        new AlertDialog.Builder(this).setTitle("无痕浏览")
                .setMessage("浏览数据只存在本机，退出即清除；冻结会把登录状态加密保存在手机上，下次点开继续。")
                .setPositiveButton("知道了", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ---------------------------------------------------------------- freeze

    private void confirmFreeze() {
        Tab tab = current();
        if (tab == null || !BrowserUrls.allowed(tab.url)) { toast("先打开要冻结的网页"); return; }
        new AlertDialog.Builder(this).setTitle("冻结并退出？")
                .setMessage("会把当前所有标签页的登录状态加密存在本机，下次点开继续。")
                .setPositiveButton("冻结并退出", (d, w) -> freeze())
                .setNegativeButton("取消", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void freeze() {
        final Tab primary = current();
        if (primary == null || primary.view == null || busy || leaving) return;
        if (!BrowserUrls.allowed(primary.url)) { toast("先打开要冻结的网页"); return; }
        busy = true;
        showBusy("正在保存会话…");
        final String url = primary.view.getUrl();
        final String title = primary.view.getTitle();
        final int version = primary.version;
        final int y = primary.view.getScrollY();
        primary.view.stopLoading();
        final int attempt = ++freezeAttempt;
        capturePending = true;
        handler.postDelayed(() -> {
            if (capturePending && freezeAttempt == attempt && !leaving)
                freezeFailed(new IllegalStateException("网页未响应，未冻结；可刷新后重试"));
        }, 15000);
        captureStorage(primary.view, raw -> {
            try {
                if (!capturePending || freezeAttempt != attempt || leaving || primary.view == null) return;
                capturePending = false;
                if (version != primary.version || url == null || !url.equals(primary.view.getUrl()))
                    throw new IllegalStateException("网页发生跳转，未冻结；请在页面稳定后重试");
                if (raw == null || raw.length() > 1024 * 1024) throw new IllegalStateException("站点会话数据过大，未冻结");
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) throw new IllegalStateException("网页暂不允许读取会话数据，请加载完成后重试");
                JSONObject captured = new JSONObject((String)decoded);
                String origin = BrowserUrls.origin(url);
                if (!origin.equals(captured.getString("origin"))) throw new IllegalStateException("网页来源发生变化，未冻结");
                JSONObject storage = captured.getJSONObject("storage");
                if (storage.length() > 0 && !BrowserPrivacy.earlyScripts())
                    throw new IllegalStateException("此站点需要新版 Android System WebView 才能可靠恢复登录，请更新内核后重试");
                JSONObject frozen = new JSONObject(session.toString())
                        .put("url", BrowserUrls.network(url))
                        .put("title", title == null || title.trim().isEmpty() ? url : title)
                        .put("scrollY", Math.max(0, y))
                        .put("origin", origin)
                        .put("sessionStorage", storage);
                collectExtras(frozen, 0, tabs.indexOf(primary), SystemClock.elapsedRealtime() + EXTRAS_DEADLINE_MS);
            } catch (Exception ex) { freezeFailed(ex); }
        });
    }

    /** Other tabs are best effort: a silent tab must not block freezing the active one. */
    private void collectExtras(final JSONObject frozen, final int index, final int skip, final long deadline) {
        if (index >= tabs.size() || SystemClock.elapsedRealtime() > deadline) { commitFreeze(frozen); return; }
        final Tab tab = tabs.get(index);
        if (index == skip || tab.view == null || tab.url.isEmpty() || !BrowserUrls.allowed(tab.url)) {
            collectExtras(frozen, index + 1, skip, deadline);
            return;
        }
        captureStorage(tab.view, raw -> {
            try {
                if (raw == null || raw.length() > 1024 * 1024) throw new IllegalStateException();
                // Metadata is capped at 1 MiB by the vault; stop adding tabs before that point.
                if (frozen.toString().length() + raw.length() > 700 * 1024) throw new IllegalStateException();
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) throw new IllegalStateException();
                JSONObject captured = new JSONObject((String)decoded);
                String origin = BrowserUrls.origin(tab.url);
                if (!origin.equals(captured.getString("origin"))) throw new IllegalStateException();
                JSONArray extras = frozen.optJSONArray("tabs");
                if (extras == null) { extras = new JSONArray(); frozen.put("tabs", extras); }
                String title = tab.title == null ? "" : tab.title.trim();
                extras.put(new JSONObject()
                        .put("url", BrowserUrls.network(tab.url))
                        .put("title", title.isEmpty() ? tab.url : title)
                        .put("scrollY", Math.max(0, tab.view == null ? 0 : tab.view.getScrollY()))
                        .put("origin", origin)
                        .put("sessionStorage", captured.getJSONObject("storage")));
            } catch (Exception ignored) { }
            collectExtras(frozen, index + 1, skip, deadline);
        });
    }

    private void commitFreeze(JSONObject frozen) {
        try {
            for (Tab tab : tabs) {
                if (tab.view == null) continue;
                tab.view.getSettings().setJavaScriptEnabled(false);
                tab.view.getSettings().setBlockNetworkLoads(true);
                tab.view.onPause();
            }
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
        } catch (RuntimeException ignored) { }
        workers.execute(() -> {
            try {
                CookieManager.getInstance().flush();
                vault.requestFreeze(frozen);
                runOnUiThread(this::leave);
            } catch (Exception ex) { runOnUiThread(() -> freezeFailed(ex)); }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void freezeFailed(Exception ex) {
        capturePending = false;
        busy = false;
        hideBusy();
        if (leaving) return;
        for (Tab tab : tabs) {
            if (tab.view == null) continue;
            tab.view.getSettings().setJavaScriptEnabled(true);
            tab.view.getSettings().setBlockNetworkLoads(false);
            tab.view.onResume();
        }
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(false);
        toast("未冻结：" + ex.getMessage());
    }

    private static void captureStorage(WebView view, final ValueCallback<String> done) {
        view.evaluateJavascript("(()=>{const data=Object.create(null);for(let i=0;i<sessionStorage.length;i++){"
                + "const k=sessionStorage.key(i);data[k]=sessionStorage.getItem(k);}"
                + "return JSON.stringify({origin:location.origin,storage:data});})()", done);
    }

    // ---------------------------------------------------------------- lifecycle

    private void restore() {
        String url = session.optString("url");
        if (!url.isEmpty()) {
            newTab(url, session);
        } else {
            newTab(null, null);
        }
        JSONArray extras = session.optJSONArray("tabs");
        if (extras != null) {
            for (int i = 0; i < extras.length(); i++) {
                try {
                    JSONObject info = extras.getJSONObject(i);
                    String extraUrl = BrowserUrls.network(info.optString("url"));
                    if (tabs.size() >= MAX_TABS) break;
                    newTab(extraUrl, info, false);
                } catch (Exception ignored) { }
            }
        }
        select(0);
    }

    private void showBusy(String text) {
        TextView label = busyView.findViewById(android.R.id.text1);
        if (label != null) label.setText(text);
        busyView.setVisibility(View.VISIBLE);
    }

    private void hideBusy() { if (busyView != null) busyView.setVisibility(View.GONE); }

    private void leave() {
        if (leaving) return;
        leaving = true;
        hideSheet();
        hideBusy();
        try { if (initialized) CookieManager.getInstance().flush(); } catch (RuntimeException ignored) { }
        for (Tab tab : new ArrayList<>(tabs)) destroyTab(tab);
        tabs.clear();
        final java.io.File profile = vault == null ? null : vault.profile;
        final Intent back = new Intent(this, BrowserHomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("returning", true);
        super.finishAndRemoveTask();
        // CookieManager.flush() only schedules a background commit. Wait bounded for the store to
        // settle, otherwise freezing right after flush can lose freshly set login cookies.
        workers.execute(() -> {
            settledCookies(profile);
            runOnUiThread(() -> handler.postDelayed(() -> {
                startActivity(back);
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 200));
        });
    }

    /** @return true when the cookie store stopped changing; false on the bounded timeout. */
    private static boolean settledCookies(java.io.File profile) {
        if (profile == null) return true;
        java.io.File cookies = new java.io.File(profile, "Default/Cookies");
        long last = -1, stableSince = 0;
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            long now = SystemClock.elapsedRealtime();
            long stamp = cookies.lastModified();
            boolean journal = new java.io.File(profile, "Default/Cookies-journal").exists();
            if (stamp > 0 && stamp == last && !journal) {
                if (stableSince == 0) stableSince = now;
                if (now - stableSince >= 200) return true;
            } else { last = stamp; stableSince = 0; }
            try { Thread.sleep(100); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    @Override public void finish() {
        if (leaving) { super.finish(); return; }
        if (busy) { toast("正在冻结，请稍候"); return; }
        hideSheet();
        Tab tab = current();
        if (!initialized || tab == null || !BrowserUrls.allowed(tab.url)) { leave(); return; }
        new AlertDialog.Builder(this).setTitle("结束浏览")
                .setMessage("冻结可以下次继续，清除则丢弃这次记录。")
                .setPositiveButton("冻结并退出", (d, w) -> freeze())
                .setNegativeButton("清除并退出", (d, w) -> leave())
                .setNeutralButton("继续浏览", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void back() {
        if (sheetHost != null && sheetHost.getVisibility() == View.VISIBLE) { hideSheet(); return; }
        if (address != null && address.hasFocus()) { address.clearFocus(); return; }
        Tab tab = current();
        if (!busy && tab != null && tab.view != null && tab.view.canGoBack()) { tab.view.goBack(); return; }
        finish();
    }

    @SuppressLint("GestureBackNavigation") @Override public void onBackPressed() { back(); }

    @android.annotation.TargetApi(33)
    private static final class Api33Back {
        static Object register(PrivateBrowserActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::back;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(PrivateBrowserActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)callback);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.clear(); }

    @Override protected void onPause() {
        for (Tab tab : tabs) if (tab.view != null) tab.view.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) for (Tab tab : tabs) if (tab.view != null) tab.view.onResume();
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) Api33Back.unregister(this, backCallback);
        for (Tab tab : new ArrayList<>(tabs)) destroyTab(tab);
        tabs.clear();
        workers.shutdownNow();
        // A configuration change also destroys the Activity. Killing then would take the new
        // instance down with it, so only a real finish ends this dedicated process.
        if (isFinishing()) handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 200);
        super.onDestroy();
    }

    private final java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void toast(String text) { Toast.makeText(this, text == null ? "操作未完成" : text, Toast.LENGTH_LONG).show(); }
}
