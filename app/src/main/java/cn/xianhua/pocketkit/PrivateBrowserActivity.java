package cn.xianhua.pocketkit;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.ScriptHandler;
import cn.xianhua.pocketkit.browser.BrowserPrivacy;
import cn.xianhua.pocketkit.browser.BrowserUrls;
import cn.xianhua.pocketkit.browser.BrowserVault;
import java.io.ByteArrayInputStream;
import java.io.File;
import org.json.*;

/** The only WebView in :private_browser. Disk operations are coordinated by BrowserHomeActivity. */
public final class PrivateBrowserActivity extends BaseToolActivity {
    // Process-level: Activity recreation must reuse the same lease, never re-lock the same JVM.
    private static BrowserVault.Lease processLease;
    private static boolean suffixSet;
    private BrowserVault vault;
    private JSONObject session;
    private WebView page;
    private EditText address;
    private TextView status, home;
    private ProgressBar progress;
    private FrameLayout viewport;
    private LinearLayout tools;
    private boolean busy, leaving, initialized;
    private int navigation, freezeAttempt;
    private boolean capturePending;
    private String protection = "";
    private String restoreUrl;
    private int restoreY;
    private ScriptHandler sessionScript;
    private Object backCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "无痕浏览").putExtra("tool_id", "N02");
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setSaveFromParentEnabled(false);
        if (Build.VERSION.SDK_INT >= 33) backCallback = Api33Back.register(this);
        try {
            if (Build.VERSION.SDK_INT < 28) throw new IllegalStateException("此工具需要 Android 9 及以上");
            vault = new BrowserVault(this);
            if (processLease == null) processLease = vault.tryLease();
            if (processLease == null) throw new IllegalStateException("另一会话正在启动或归档，请返回重试");
            session = vault.active(getIntent().getStringExtra("session_token"));
            if (!suffixSet) {
                WebView.setDataDirectorySuffix(BrowserVault.SUFFIX);
                WebView.setWebContentsDebuggingEnabled(false);
                suffixSet = true;
            }
            initialized = true;
            root.removeViewAt(1);
            buildBrowser();
            page = createPage();
            viewport.addView(page, new FrameLayout.LayoutParams(-1, -1));
            viewport.addView(home, new FrameLayout.LayoutParams(-1, -1));
            String url = session.optString("url");
            if (!url.isEmpty()) {
                sessionScript = BrowserPrivacy.restoreSession(page, session);
                restoreUrl = BrowserUrls.network(url);
                restoreY = Math.max(0, session.optInt("scrollY", 0));
                open(restoreUrl);
            }
        } catch (Exception | LinkageError ex) {
            TextView failure = new TextView(this);
            ToolUi.text(failure, ink, 16);
            failure.setText("无法安全打开会话，未继续加载网页。原冻结副本保留。\n" + ex.getMessage());
            root.addView(failure);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A second launcher tap resumes this live window; it never overwrites its profile.
        if (leaving) Toast.makeText(this, "会话正在退出，请稍后从冻结库打开", Toast.LENGTH_LONG).show();
    }

    private void buildBrowser() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        address = new EditText(this);
        address.setHint("输入网址或搜索");
        ToolUi.text(address, ink, 15);
        address.setHintTextColor(muted);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        address.setSaveEnabled(false);
        address.setSelectAllOnFocus(true);
        address.setBackground(ToolUi.fill(surface, dp(12), Color.TRANSPARENT, 0));
        address.setPadding(dp(12), dp(8), dp(12), dp(8));
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                if (!busy && !leaving) try { navigate(); } catch (Exception ex) { address.setError(ex.getMessage()); }
                return true;
            }
            return false;
        });
        bar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(control("前往", this::navigate));
        root.addView(bar);
        status = new TextView(this);
        ToolUi.text(status, muted, 12);
        status.setPadding(dp(4), dp(6), dp(4), dp(6));
        status.setText("无痕会话 · 基础抗指纹");
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        viewport = new FrameLayout(this);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        home = new TextView(this);
        ToolUi.text(home, muted, 16);
        home.setBackgroundColor(surface);
        home.setGravity(Gravity.CENTER);
        home.setPadding(dp(24), dp(24), dp(24), dp(24));
        home.setText("只留下你想留下的\n\n输入网址开始浏览\n稍后还要继续？点“冻结”\n登录所需数据会加密留在本机\n\n普通退出则清理会话\n点此查看保护范围");
        home.setOnClickListener(v -> privacy());
        HorizontalScrollView footer = new HorizontalScrollView(this);
        footer.setHorizontalScrollBarEnabled(false);
        tools = new LinearLayout(this);
        footer.addView(tools);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        tool("后退", () -> { if (page.canGoBack()) page.goBack(); });
        tool("前进", () -> { if (page.canGoForward()) page.goForward(); });
        tool("刷新", () -> page.reload());
        tool("收藏", this::bookmarks);
        tool("冻结", this::confirmFreeze);
        tool("退出", this::finish);
        tool("说明", this::privacy);
    }
    private Button control(String label, Work action) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false);
        ToolUi.text(button, accent, 14);
        button.setMinHeight(dp(48)); button.setMinWidth(dp(64));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));
        button.setOnClickListener(v -> { if (!busy && !leaving) try { action.run(); } catch (Exception ex) { toast(ex.getMessage()); } });
        return button;
    }
    private void tool(String label, Work action) { tools.addView(control(label, action)); }
    private void privacy() {
        new AlertDialog.Builder(this).setTitle("基础防护，不是网络匿名")
                .setMessage(protection + "\n\n拦截已知跟踪域名、第三方 Cookie、外部 App 跳转；不向网页提供相机、麦克风、定位和本地文件。\n\n仍可能通过 IP、登录账号、Canvas、音频、字体、Worker 等识别设备；页面级脚本防护可能被检测或绕过。\n\n冻结保存整个隔离会话，不只是当前站点。网页重新加载后尝试恢复登录及阅读位置，不保证恢复表单、网页内存或未完成交易。网站仍可能让登录失效。")
                .setPositiveButton("知道了", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createPage() throws Exception {
        WebView view = new WebView(this);
        page = view; // Own the view even if configuration fails, so exit can destroy it.
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false); s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // API 26+ form protection is the autofill exclusion above, not deprecated saveFormData.
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true); s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(false);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        protection = BrowserPrivacy.apply(this, view, session);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        ServiceWorkerController service = ServiceWorkerController.getInstance();
        service.getServiceWorkerWebSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        service.getServiceWorkerWebSettings().setAllowContentAccess(false);
        service.getServiceWorkerWebSettings().setAllowFileAccess(false);
        service.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                return BrowserPrivacy.tracker(request.getUrl().getHost()) ? blocked() : null;
            }
        });
        view.setDownloadListener((url, ua, disposition, mime, length) -> toast("此轻量浏览器未启用文件下载"));
        view.setWebViewClient(new WebViewClient() {
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
                if (v != page || leaving) return;
                navigation++;
                home.setVisibility(View.GONE);
                address.setText(url);
                status.setText(url.startsWith("https://") ? "无痕 · HTTPS · 基础抗指纹" : "无痕 · HTTP 未加密 · 基础抗指纹");
                if (restoreUrl != null && !restoreUrl.equals(url)) {
                    restoreUrl = null;
                    if (sessionScript != null) { sessionScript.remove(); sessionScript = null; }
                }
            }
            @Override public void onPageCommitVisible(WebView v, String url) {
                if (sessionScript != null && BrowserUrls.allowed(url) && BrowserUrls.origin(url).equals(session.optString("origin"))) {
                    sessionScript.remove(); sessionScript = null;
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (v != page || leaving) return;
                if (url.equals(restoreUrl)) {
                    int y = restoreY, version = navigation;
                    restoreUrl = null;
                    // shortcut: dynamic layouts may shift after load; reading position is best effort.
                    v.postDelayed(() -> { if (v == page && navigation == version && !leaving) v.scrollTo(0, y); }, 300);
                }
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel(); if (v == page) status.setText("证书校验失败，已阻止访问");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (v == page && request.isForMainFrame()) status.setText("加载失败，请检查地址、网络或系统 WebView 后刷新");
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (v == page) { toast("网页进程停止，当前临时会话将清理；已有冻结副本保留"); leave(); }
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int value) {
                if (v != page || busy || leaving) return;
                progress.setProgress(value); progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                callback.onReceiveValue(null); toast("此浏览器不向网页提供本地文件"); return true;
            }
        });
        return view;
    }
    private static WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }
    private void navigate() {
        open(BrowserUrls.address(address.getText().toString()));
        address.clearFocus();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
    }
    private void open(String url) {
        if (page == null || busy || leaving) return;
        home.setVisibility(View.GONE); page.loadUrl(BrowserUrls.network(url));
    }

    private JSONArray bookmarksData() throws JSONException { return new JSONArray(getSharedPreferences("private_browser_saved", 0).getString("bookmarks", "[]")); }
    private void storeBookmarks(JSONArray items) {
        if (!getSharedPreferences("private_browser_saved", 0).edit().putString("bookmarks", items.toString()).commit()) throw new IllegalStateException("收藏保存失败");
    }
    private void bookmarks() throws Exception {
        JSONArray items = bookmarksData();
        String[] labels = new String[items.length() + 1];
        labels[0] = "＋ 收藏当前网页";
        for (int i = 0; i < items.length(); i++) labels[i + 1] = items.getJSONObject(i).optString("title") + "\n" + items.getJSONObject(i).optString("url");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("收藏夹 · 长按删除")
                .setItems(labels, (d, which) -> {
                    try { if (which == 0) saveBookmark(); else open(items.getJSONObject(which - 1).getString("url")); }
                    catch (Exception ex) { toast(ex.getMessage()); }
                }).setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(d -> dialog.getListView().setOnItemLongClickListener((parent, row, position, id) -> {
            if (position == 0) return true;
            new AlertDialog.Builder(this).setTitle("删除此收藏？").setPositiveButton("删除", (c, w) -> {
                try { items.remove(position - 1); storeBookmarks(items); dialog.dismiss(); bookmarks(); }
                catch (Exception ex) { toast(ex.getMessage()); }
            }).setNegativeButton("取消", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return true;
        }));
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private void saveBookmark() {
        if (page == null || !BrowserUrls.allowed(page.getUrl())) { toast("先打开网页再收藏"); return; }
        String original = BrowserUrls.network(page.getUrl());
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        TextView warning = new TextView(this); warning.setText("收藏会长期保留标题和完整网址（不加密）；请移除网址中的令牌等敏感参数。"); form.addView(warning);
        EditText title = new EditText(this); title.setHint("标题"); title.setSingleLine(true); title.setSaveEnabled(false); title.setText(page.getTitle()); form.addView(title);
        EditText url = new EditText(this); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); url.setSaveEnabled(false); url.setText(original); form.addView(url);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("加入收藏").setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String target = BrowserUrls.network(url.getText().toString());
                String name = title.getText().toString().trim();
                JSONObject entry = new JSONObject().put("title", name.isEmpty() ? target : name).put("url", target);
                JSONArray items = bookmarksData(); boolean replaced = false;
                for (int i = 0; i < items.length(); i++) if (target.equals(items.getJSONObject(i).optString("url"))) { items.put(i, entry); replaced = true; break; }
                if (!replaced) items.put(entry);
                storeBookmarks(items); dialog.dismiss(); toast("已收藏");
            } catch (Exception ex) { url.setError(ex.getMessage()); }
        }));
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private void confirmFreeze() {
        if (page == null || !BrowserUrls.allowed(page.getUrl())) { toast("先打开需要冻结的网页"); return; }
        new AlertDialog.Builder(this).setTitle("冻结会话并退出？")
                .setMessage("将整个隔离会话的登录凭据、站点存储及当前页阅读位置加密保存到本机，之后可从冻结库继续。\n\n未提交表单、上传与进行中的交易不保证恢复。网站自身过期或风控仍可能要求重新登录。")
                .setPositiveButton("冻结并退出", (d, w) -> freeze()).setNegativeButton("取消", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void freeze() {
        if (busy || leaving || page == null) return;
        busy = true;
        final String url = page.getUrl(), title = page.getTitle();
        final int version = navigation, y = page.getScrollY();
        status.setText("正在保存站点会话…");
        page.stopLoading();
        capturePending = true;
        final int attempt = ++freezeAttempt;
        handler.postDelayed(() -> {
            if (capturePending && freezeAttempt == attempt && !leaving) freezeFailed(new IllegalStateException("网页未响应，未冻结；可刷新后重试"));
        }, 15000);
        page.evaluateJavascript("(()=>{const data=Object.create(null);for(let i=0;i<sessionStorage.length;i++){const k=sessionStorage.key(i);data[k]=sessionStorage.getItem(k);}return JSON.stringify({origin:location.origin,storage:data});})()", raw -> {
            try {
                if (!capturePending || freezeAttempt != attempt || leaving || page == null) return;
                capturePending = false;
                if (version != navigation || !url.equals(page.getUrl())) throw new IllegalStateException("网页发生跳转，未冻结；请在页面稳定后重试");
                if (raw == null || raw.length() > 1024 * 1024) throw new IllegalStateException("站点会话数据过大，未冻结");
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) throw new IllegalStateException("网页暂不允许读取会话数据，请加载完成后重试");
                JSONObject captured = new JSONObject((String)decoded);
                String origin = BrowserUrls.origin(url);
                if (!origin.equals(captured.getString("origin"))) throw new IllegalStateException("网页来源发生变化，未冻结");
                JSONObject storage = captured.getJSONObject("storage");
                if (storage.length() > 0 && !BrowserPrivacy.earlyScripts()) throw new IllegalStateException("此站点需要新版 Android System WebView 才能可靠恢复登录，请更新内核后重试");
                JSONObject frozen = new JSONObject(session.toString()).put("url", BrowserUrls.network(url))
                        .put("title", title == null || title.trim().isEmpty() ? url : title).put("scrollY", Math.max(0, y))
                        .put("origin", origin).put("sessionStorage", storage);
                page.getSettings().setJavaScriptEnabled(false);
                page.getSettings().setBlockNetworkLoads(true);
                page.onPause();
                ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
                workers.execute(() -> {
                    try {
                        CookieManager.getInstance().flush();
                        vault.requestFreeze(frozen);
                        runOnUiThread(this::leave);
                    } catch (Exception ex) { runOnUiThread(() -> freezeFailed(ex)); }
                });
            } catch (Exception ex) { freezeFailed(ex); }
        });
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void freezeFailed(Exception ex) {
        capturePending = false;
        busy = false;
        if (page != null && !leaving) {
            page.getSettings().setJavaScriptEnabled(true); page.getSettings().setBlockNetworkLoads(false); page.onResume();
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(false);
            status.setText("未冻结：" + ex.getMessage());
        }
    }

    private void destroyPage() {
        if (page == null) return;
        WebView old = page; page = null;
        old.stopLoading(); old.getSettings().setBlockNetworkLoads(true); old.getSettings().setJavaScriptEnabled(false);
        if (old.getParent() != null) ((ViewGroup)old.getParent()).removeView(old);
        old.setWebChromeClient(null); old.setWebViewClient(new WebViewClient()); old.destroy();
    }
    private void leave() {
        if (leaving) return;
        leaving = true;
        try { if (initialized) CookieManager.getInstance().flush(); } catch (RuntimeException ignored) { }
        try { destroyPage(); } catch (RuntimeException ignored) { }
        final File profile = vault == null ? null : vault.profile;
        final Intent back = new Intent(this, BrowserHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("returning", true);
        super.finishAndRemoveTask();
        // CookieManager.flush() only schedules a background commit. Wait bounded for the store to
        // settle, otherwise freezing right after flush can lose freshly set login cookies. Home
        // probes the same file before archiving, so an unsettled store is reported, not hidden.
        workers.execute(() -> {
            settledCookies(profile);
            runOnUiThread(() -> handler.postDelayed(() -> {
                startActivity(back);
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 200));
        });
    }

    /** @return true when the cookie store stopped changing; false on the bounded timeout. */
    private static boolean settledCookies(File profile) {
        if (profile == null) return true;
        File cookies = new File(profile, "Default/Cookies");
        long last = -1, stableSince = 0;
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            long now = SystemClock.elapsedRealtime();
            long stamp = cookies.lastModified();
            boolean journal = new File(profile, "Default/Cookies-journal").exists();
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
        if (!initialized || page == null || !BrowserUrls.allowed(page.getUrl())) { leave(); return; }
        new AlertDialog.Builder(this).setTitle("结束这次浏览")
                .setMessage("稍后继续就冻结；清除退出会丢弃本次变化，但保留已有冻结副本。")
                .setPositiveButton("冻结并退出", (d, w) -> freeze())
                .setNegativeButton("清除并退出", (d, w) -> leave()).setNeutralButton("继续浏览", null).show().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void back() { if (!busy && page != null && page.canGoBack()) page.goBack(); else finish(); }
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
    @Override protected void onPause() { if (page != null) page.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (page != null && !busy) page.onResume(); }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) Api33Back.unregister(this, backCallback);
        try { destroyPage(); } catch (RuntimeException ignored) { }
        // A configuration change also destroys the Activity. Killing then would take the new
        // instance down with it, so only a real finish ends this dedicated process.
        if (isFinishing()) {
            // Do not release the file lease manually: cleanup waits for process death, not Activity death.
            handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 200);
        }
        super.onDestroy();
    }
    private void toast(String text) { Toast.makeText(this, text == null ? "操作未完成" : text, Toast.LENGTH_LONG).show(); }
}
