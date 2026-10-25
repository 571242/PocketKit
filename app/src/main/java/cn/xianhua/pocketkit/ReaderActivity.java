package cn.xianhua.pocketkit;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.reader.DocumentFormat;
import cn.xianhua.pocketkit.reader.HtmlPolicy;
import cn.xianhua.pocketkit.reader.ReaderChapter;
import cn.xianhua.pocketkit.reader.ReaderDocument;
import cn.xianhua.pocketkit.reader.ReaderIo;
import cn.xianhua.pocketkit.reader.ReaderLoader;
import cn.xianhua.pocketkit.reader.ReaderPage;
import cn.xianhua.pocketkit.reader.ReaderSearch;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * R01 — one reading screen for Markdown, text, offline HTML, EPUB, PDF and the three OOXML formats.
 *
 * <p>It receives a file from another app ({@code VIEW} with data, or {@code SEND} with a stream) or
 * lets the user pick one. The document is parsed on a worker into a sanitized model, and only that
 * model reaches a locked-down WebView: no scripts, no network, no file access. A web link is never
 * followed inside the WebView — the user has to confirm opening it in a browser.
 */
public final class ReaderActivity extends BaseToolActivity {
    private static final int PICK = 7901;
    private static final String PREF_ZOOM = "reader_zoom";
    private static final int[] ZOOM_STEPS = {100, 115, 130, 150, 175};

    private WebView page;
    private TextView where;
    private TextView progressText;
    private ProgressBar progress;
    private HorizontalScrollView footer;
    private LinearLayout footerRow;
    private Button previous;
    private Button next;
    private boolean dark;
    private String template;
    private java.io.File pdfFile;
    private Button chooseFile;
    private String progressKey = "";
    private boolean restoring, loading;
    private int loadVersion, renderVersion;

    private ReaderDocument document;
    private String sourceName = "";
    private int section;
    private int pendingScroll;
    private String pendingFind;
    private int findSection = -1;

    @Override protected void onCreate(Bundle state) {
        // A share sheet or "open with" launch carries no extras at all, so the two base values that
        // the header reads must exist before super builds the view.
        if (!getIntent().hasExtra("title")) getIntent().putExtra("title", "轻量阅读器");
        if (!getIntent().hasExtra("tool_id")) getIntent().putExtra("tool_id", "R01");
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        root.removeViewAt(1); // the reading area owns the full height; the shared scroll body is not used
        buildChrome();
        loadTemplate();
        if (state != null && state.getString("source") != null) {
            restoring = true;
            section = state.getInt("section");
            pendingScroll = state.getInt("scroll");
            reopen(Uri.parse(state.getString("source")), state.getString("name"));
        } else {
            handleIncoming(getIntent(), true);
        }
    }

    // ---------------------------------------------------------------- view

    private void buildChrome() {
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(0, dp(4), 0, dp(6));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(line, new LinearLayout.LayoutParams(-1, -2));
        where = new TextView(this);
        where.setEllipsize(TextUtils.TruncateAt.END);
        where.setMaxLines(2);
        ToolUi.text(where, ink, 14);
        where.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.addView(where, new LinearLayout.LayoutParams(0, -2, 1));
        progressText = new TextView(this);
        ToolUi.text(progressText, muted, 12);
        progressText.setPadding(dp(8), 0, 0, 0);
        line.addView(progressText);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        info.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        page = new WebView(this);
        lockDown(page);
        page.setBackgroundColor(Color.parseColor(dark ? "#101B2B" : "#F3F6FB"));
        page.setWebViewClient(new Guard());
        page.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        page.setOnScrollChangeListener((v, x, y, oldX, oldY) -> showProgress());
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = new HorizontalScrollView(this);
        footer.setHorizontalScrollBarEnabled(false);
        footerRow = new LinearLayout(this);
        footer.addView(footerRow);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        previous = small("上一章", this::previousSection);
        next = small("下一章", this::nextSection);
        small("目录", this::chooseSection);
        small("查找", this::findInDocument);
        small("字号", this::chooseZoom);
        chooseFile = small("选文件", this::pick);
    }

    private Button small(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        ToolUi.text(button, accent, 14);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));
        button.setOnClickListener(v -> { try { action.run(); } catch (Exception ex) { report(ex); } });
        footerRow.addView(button);
        return button;
    }

    /**
     * A document may not script, may not load anything, and may not read a local file. The reading HTML
     * is a data string with a null base URL, so even a stray reference has no origin to resolve against.
     */
    private static void lockDown(WebView view) {
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
        settings.setTextZoom(100);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setDefaultTextEncodingName("utf-8");
        view.setVerticalScrollBarEnabled(true);
    }

    private final class Guard extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !request.isForMainFrame() || !request.hasGesture() || intercept(request.getUrl());
        }

        @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            if ("data".equals(scheme) || "about".equals(scheme)) return null;
            return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
        }

        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            toast("阅读页面被系统回收，请重新打开文档");
            ((ViewGroup) view.getParent()).removeView(view); view.destroy(); page = null; finish(); return true;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return intercept(Uri.parse(url));
        }

        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (isFinishing() || isDestroyed() || view != page) return;
            final int scroll = pendingScroll;
            final String query = pendingFind;
            pendingScroll = 0;
            pendingFind = null;
            view.post(() -> {
                if (isFinishing() || isDestroyed() || view != page) return;
                if (scroll > 0) view.scrollTo(0, scroll);
                if (query != null) {
                    findSection = section;
                    view.findAllAsync(query);
                }
                showProgress();
            });
        }
    }

    /** A jump inside the book is ours; a web address needs the reader's explicit go-ahead. */
    private boolean intercept(Uri target) {
        int index = ReaderChapter.anchorIndex(target == null ? null : target.toString());
        if (index >= 0) {
            showSection(index);
            return true;
        }
        String scheme = target == null || target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            // Anything else (file:, content:, javascript:, intent:) is refused rather than handed on.
            toast("这个链接不在阅读器里打开");
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle("在浏览器中打开？")
                .setMessage(target.toString())
                .setPositiveButton("打开", (dialog, which) -> openOutside(target))
                .setNegativeButton("留在阅读器", null)
                .show();
        return true;
    }

    private void openOutside(Uri target) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, target));
        } catch (ActivityNotFoundException noBrowser) {
            toast("这台设备没有能打开该链接的应用");
        }
    }

    // ---------------------------------------------------------------- input

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent, false);
    }

    private void handleIncoming(Intent intent, boolean first) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null && intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) uri = (Uri) stream;
        }
        if (uri == null) {
            if (first) where.setText("还没有打开文档 · 用下方「选文件」选择");
            return;
        }
        if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException temporaryGrantOnly) {
                // A one-shot grant is enough: the bytes are read once and the model is kept in memory.
            }
        }
        open(uri, displayName(uri));
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, DocumentFormat.pickerMimeTypes())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK);
        } catch (RuntimeException noPicker) {
            report(new IllegalStateException("没有可用的文件选择器"));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException temporaryGrantOnly) {
            // Read once below; nothing keeps the grant alive afterwards.
        }
        open(data.getData(), displayName(data.getData()));
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isBlank()) return name;
            }
        } catch (RuntimeException providerRefused) {
            // Falls through to the URI's own last segment.
        }
        String path = uri.getLastPathSegment();
        return path == null ? "文档" : path;
    }

    // ---------------------------------------------------------------- loading

    private void reopen(Uri uri, String name) {
        open(uri, name == null ? displayName(uri) : name);
    }

    private void open(Uri uri, String name) {
        if (!"content".equals(uri.getScheme())) { report(new IOException("请通过系统文件选择器选择文档，或从其他应用分享")); return; }
        saveReadingPosition();
        final int ticket = ++loadVersion; renderVersion++;
        where.setText(name + "\n正在解析…"); progressText.setText(""); progress.setProgress(0); setFooterEnabled(false);
        workers.execute(() -> {
            java.io.File cached = null;
            try {
                byte[] bytes = read(uri);
                String key = cn.xianhua.pocketkit.filetools.DuplicateScan.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                ReaderDocument parsed;
                if (DocumentFormat.sniff(name, bytes) == DocumentFormat.Kind.PDF) {
                    java.io.File directory = new java.io.File(getCacheDir(), "reader-pdf");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法建立阅读缓存");
                    cached = java.io.File.createTempFile("page-", ".pdf", directory);
                    try (java.io.OutputStream out = new java.io.FileOutputStream(cached)) { out.write(bytes); }
                    int count = cn.xianhua.pocketkit.reader.ReaderPdf.count(cached);
                    java.util.ArrayList<ReaderDocument.Section> sections = new java.util.ArrayList<>();
                    for (int i = 0; i < count; i++) sections.add(new ReaderDocument.Section("第 " + (i + 1) + " 页", "", ""));
                    parsed = new ReaderDocument(ReaderDocument.Format.PDF, ReaderLoader.titleOf(name), sections,
                            "PDF 逐页显示原样画面，支持双指缩放；不含 OCR 或全文查找，加密 PDF 需先解密。");
                } else parsed = ReaderLoader.load(name, bytes);
                if (parsed.sections().isEmpty()) throw new IOException("文档没有可显示的正文");
                final java.io.File readyPdf = cached;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || ticket != loadVersion) { if (readyPdf != null) readyPdf.delete(); return; }
                    if (pdfFile != null) pdfFile.delete(); pdfFile = readyPdf;
                    progressKey = key;
                    if (!restoring) {
                        section = getPreferences(0).getInt(key + ".section", 0);
                        pendingScroll = getPreferences(0).getInt(key + ".scroll", 0);
                    }
                    restoring = false;
                    show(parsed, uri, name);
                });
            } catch (Exception | LinkageError | OutOfMemoryError failure) {
                if (cached != null) cached.delete();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || ticket != loadVersion) return;
                    setFooterEnabled(document != null); report(failure);
                });
            }
        });
    }

    /** One bounded read: the whole document is needed to parse it, but never more than the stated cap. */
    private byte[] read(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取这个文件（提供方没有返回内容）。");
            return ReaderIo.readAll(input, DocumentFormat.MAX_FILE_BYTES);
        }
    }

    private void show(ReaderDocument parsed, Uri uri, String name) {
        if (isFinishing() || isDestroyed()) return;
        document = parsed;
        setSourceUri(uri);
        sourceName = name;
        if (section < 0 || section >= parsed.sections().size()) section = 0;
        boolean pdf = parsed.format() == ReaderDocument.Format.PDF;
        previous.setText(pdf ? "上一页" : "上一章");
        next.setText(pdf ? "下一页" : "下一章");
        setFooterEnabled(true);
        int restore = pendingScroll;
        render(true);
        pendingScroll = restore;
        if (!parsed.limitation().isBlank()) toast(parsed.limitation());
    }

    private void loadTemplate() {
        workers.execute(() -> {
            try (InputStream input = getAssets().open(ReaderPage.TEMPLATE_ASSET)) {
                byte[] bytes = ReaderIo.readAll(input, 256 * 1024);
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                runOnUiThread(() -> { template = text; if (document != null) render(true); });
            } catch (IOException broken) {
                runOnUiThread(() -> report(new IllegalStateException("阅读页面模板读取失败：" + broken.getMessage())));
            }
        });
    }

    // ---------------------------------------------------------------- rendering

    private void render(boolean resetScroll) {
        if (document == null || page == null) return;
        if (template == null) { where.setText(sourceName + "\n正在准备阅读页面…"); return; }
        pendingScroll = resetScroll ? 0 : page.getScrollY();
        final int ticket = ++renderVersion;
        final ReaderDocument reading = document;
        final int index = section;
        if (reading.format() == ReaderDocument.Format.PDF) {
            final java.io.File file = pdfFile;
            setFooterEnabled(false); where.setText("正在显示第 " + (index + 1) + " 页…");
            workers.execute(() -> {
                try {
                    ReaderDocument.Section content = cn.xianhua.pocketkit.reader.ReaderPdf.page(file, index, getResources().getDisplayMetrics().widthPixels * 2);
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && ticket == renderVersion && reading == document) applyPage(content, true); });
                } catch (Exception | OutOfMemoryError error) {
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && ticket == renderVersion) { setFooterEnabled(true); report(error); } });
                }
            });
        } else applyPage(reading.sections().get(index), false);
    }

    private void applyPage(ReaderDocument.Section content, boolean pdf) {
        String html = ReaderPage.render(pdf ? template.replace("img-src 'none'", "img-src data:") : template, content, dark, ReaderPage.DEFAULT_FONT);
        page.getSettings().setTextZoom(Math.round(zoom() * getResources().getConfiguration().fontScale));
        page.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        where.setText(content.title()); setFooterEnabled(true); showProgress();
    }

    private void showSection(int index) {
        if (document == null || loading) return;
        saveReadingPosition();
        if (index < 0 || index >= document.sections().size()) {
            toast("已经到头了");
            return;
        }
        section = index;
        render(true);
    }

    private void previousSection() { showSection(section - 1); }

    private void nextSection() { showSection(section + 1); }

    private void setFooterEnabled(boolean enabled) {
        loading = !enabled;
        for (int i = 0; i < footerRow.getChildCount(); i++) footerRow.getChildAt(i).setEnabled(enabled);
        if (chooseFile != null) chooseFile.setEnabled(true);
        if (enabled && document != null) { previous.setEnabled(section > 0); next.setEnabled(section + 1 < document.sections().size()); }
    }

    /** Chapter-and-page position plus a scroll-refined percentage; cheap enough to run on every scroll. */
    private void showProgress() {
        if (document == null || page == null) return;
        int total = document.sections().size();
        int height = Math.round(page.getContentHeight() * page.getScale());
        int span = height - page.getHeight();
        double within = span <= 0 ? 1 : Math.min(1, Math.max(0, page.getScrollY() / (double) span));
        int percent = (int) Math.round(Math.min(1, (section + within) / total) * 100);
        boolean pdf = document.format() == ReaderDocument.Format.PDF;
        progressText.setText((pdf ? "第 " : "第 ") + (section + 1) + "/" + total + (pdf ? " 页" : " 章") + " · " + percent + "%");
        progress.setProgress((int) Math.round(percent * 10));
    }

    private void chooseSection() {
        if (document == null) { toast("还没有打开文档"); return; }
        List<ReaderDocument.Section> sections = document.sections();
        String[] labels = new String[sections.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.format(Locale.ROOT, "%d/%d  %s", i + 1, labels.length, sections.get(i).title());
        }
        new AlertDialog.Builder(this)
                .setTitle(document.title())
                .setSingleChoiceItems(labels, section, (dialog, which) -> {
                    dialog.dismiss();
                    showSection(which);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseZoom() {
        if (document != null && document.format() == ReaderDocument.Format.PDF) {
            new AlertDialog.Builder(this).setTitle("页面缩放").setItems(new String[]{"放大", "缩小"}, (dialog, which) -> { if (which == 0) page.zoomIn(); else page.zoomOut(); }).show(); return;
        }
        String[] labels = new String[ZOOM_STEPS.length];
        int current = 0;
        for (int i = 0; i < ZOOM_STEPS.length; i++) {
            labels[i] = ZOOM_STEPS[i] + "%" + (ZOOM_STEPS[i] == zoom() ? "（当前）" : "");
            if (ZOOM_STEPS[i] == zoom()) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("字号")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    getPreferences(0).edit().putInt(PREF_ZOOM, ZOOM_STEPS[which]).apply();
                    page.getSettings().setTextZoom(Math.round(ZOOM_STEPS[which] * getResources().getConfiguration().fontScale));
                    showProgress();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int zoom() {
        int saved = getPreferences(0).getInt(PREF_ZOOM, 100);
        for (int step : ZOOM_STEPS) if (step == saved) return saved;
        return 100;
    }

    /**
     * The count comes from the parsed text, so it always matches the document; the highlight is the
     * WebView's own find, which needs no script and keeps working with JavaScript disabled.
     */
    private void findInDocument() {
        if (document == null) { toast("还没有打开文档"); return; }
        if (document.format() == ReaderDocument.Format.PDF) { toast("PDF 为逐页画面预览，暂不提供全文查找"); return; }
        final EditText input = new EditText(this);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        ToolUi.text(input, ink, 16);
        input.setHint("要查找的文字");
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("查找")
                .setView(input)
                .setPositiveButton("查找", (dialog, which) -> runFind(input.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runFind(String query) {
        if (query.isBlank()) return;
        List<ReaderSearch.Hit> hits = ReaderSearch.find(document, query, 500);
        if (hits.isEmpty()) {
            toast("没有找到「" + query.trim() + "」");
            where.setText(document.sections().get(section).title());
            return;
        }
        int target = section;
        for (ReaderSearch.Hit hit : hits) {
            if (hit.section() >= section) { target = hit.section(); break; }
            target = hit.section();
        }
        where.setText(document.sections().get(target).title() + " · 共 " + hits.size() + " 处");
        if (target != section) {
            section = target;
            pendingFind = query;
            render(true);
        } else {
            page.findAllAsync(query);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private void setFooterEnabledState() { setFooterEnabled(document != null); }

    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void report(Throwable failure) {
        if (isFinishing() || isDestroyed()) return;
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        new AlertDialog.Builder(this)
                .setTitle("这个文件没有打开")
                .setMessage(message)
                .setPositiveButton("选另一个文件", (dialog, which) -> pick())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void saveReadingPosition() {
        if (document != null && page != null && !loading && !progressKey.isEmpty())
            getPreferences(0).edit().putInt(progressKey + ".section", section).putInt(progressKey + ".scroll", page.getScrollY()).apply();
    }

    @Override protected void onPause() { saveReadingPosition(); super.onPause(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveReadingPosition();
        super.onSaveInstanceState(state);
        if (document != null) {
            state.putInt("section", section);
            state.putInt("scroll", page.getScrollY());
            Uri uri = sourceUri;
            if (uri != null) {
                state.putString("source", uri.toString());
                state.putString("name", sourceName);
            }
        }
    }

    private Uri sourceUri;

    @Override protected void onDestroy() {
        loadVersion++; renderVersion++;
        if (pdfFile != null) { pdfFile.delete(); pdfFile = null; }
        if (page != null) {
            page.stopLoading();
            page.setWebViewClient(null);
            page.removeAllViews();
            page.destroy();
            page = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- tool contract

    public static boolean supports(String id) { return "R01".equals(id); }

    public static String limitation(String id) {
        return "轻量阅读器：打开 Markdown、纯文本、离线 HTML、EPUB、PDF 与 DOCX / XLSX / PPTX。"
                + "Office 仅预览文字与表格，不还原版式；PDF 逐页显示，可双指缩放，不含 OCR 或全文查找。"
                + "支持 UTF-8、GB18030 与带 BOM 的 UTF-16；纯文本最多 8 MiB，文档最多 64 MiB。"
                + "旧版 Office、Kindle、DRM 与加密 PDF 不支持；文档外链需确认，网页脚本和外部资源不加载。";
    }

    private void setSourceUri(Uri uri) { sourceUri = uri; }
}
