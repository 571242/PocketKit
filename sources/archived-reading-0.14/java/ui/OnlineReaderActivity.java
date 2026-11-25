package cn.xianhua.pocketkit.reading.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.reader.HtmlPolicy;
import cn.xianhua.pocketkit.reader.ReaderDocument;
import cn.xianhua.pocketkit.reader.ReaderIo;
import cn.xianhua.pocketkit.reader.ReaderPage;
import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Progress;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import cn.xianhua.pocketkit.reading.ReadingStore;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线阅读：复用本地轻量阅读器的 reader.html 模板与字体/深色样式，只把引擎取回的纯文本转义后显示。
 *
 * <p>WebView 与本地的安全边界一致：禁脚本、禁网络、禁文件/内容访问。章节正文来自
 * {@link ReadingEngine#chapter}，先查 {@link ReadingStore#cachedChapter} 缓存，命中就不再联网。
 * 取章失败时不把错误页盖到读者眼前，也不把没载入的章节写进进度。
 */
public final class OnlineReaderActivity extends Activity {
    private static final int[] ZOOM_STEPS = {100, 115, 130, 150, 175};

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private int loadVersion;

    private ReadingStore store;
    private ReadingEngine engine;
    private Source source;
    private Progress progress;
    private String template;

    private WebView page;
    private TextView where;
    private TextView progressText;
    private ProgressBar bar;
    private HorizontalScrollView footerRow;
    private View prevButton, nextButton;

    private boolean dark;
    private int chapterIndex;
    private int pendingScroll;
    private boolean loading;
    private String lastText;
    private boolean rendered; // a chapter body is on screen; a failed load must keep it and never touch the index

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!openIntent()) return;
        boolean savedDark = getPreferences(0).getBoolean("online_reader_dark", ReadingUi.dark(this));
        dark = savedDark;
        if (state != null) {
            chapterIndex = state.getInt("chapter", progress.chapterIndex);
            pendingScroll = state.getInt("scroll", progress.scrollY);
            dark = state.getBoolean("dark", dark);
        } else {
            chapterIndex = progress.chapterIndex;
            pendingScroll = progress.scrollY;
        }
        buildUi();
        loadTemplate();
    }

    private boolean openIntent() {
        try {
            store = new ReadingStore(this);
            engine = new ReadingEngine();
        } catch (RuntimeException problem) {
            failAndClose("阅读数据没能打开：" + ReadingUi.reason(problem));
            return false;
        }
        // Prefer the stored record id (a large catalogue cannot ride inside the Intent); legacy progress_json still works.
        try {
            progress = ReadingUi.readProgress(getIntent(), store);
        } catch (RuntimeException bad) {
            failAndClose(ReadingUi.reason(bad));
            return false;
        }
        if (progress == null) { failAndClose("没有要打开的阅读记录"); return false; }
        if (progress.detail == null || progress.detail.chapters == null || progress.detail.chapters.isEmpty()) {
            failAndClose("这条记录没有目录，无法阅读");
            return false;
        }
        source = store.source(progress.sourceId);
        if (source == null) { failAndClose("这个作品所属的源已被删除，请回阅读中心重新打开"); return false; }
        String issue = ReadingEngine.supportIssue(source);
        if (issue != null && !issue.isEmpty()) { failAndClose(issue); return false; }
        return true;
    }

    // ---------------------------------------------------------------- chrome

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReadingUi.base(this));
        ReadingUi.insets(this, root, 16, 6);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(headerRow());
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        info.addView(bar, new LinearLayout.LayoutParams(-1, ReadingUi.dp(this, 3)));
        root.addView(info);

        page = new WebView(this);
        ReadingUi.lockDown(page, Color.parseColor(dark ? "#101B2B" : "#F3F6FB"));
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> showProgress());
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

        footerRow = new HorizontalScrollView(this);
        footerRow.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        prevButton = ReadingUi.pill(this, "上一章", ReadingUi.accent(this), () -> jump(-1));
        nextButton = ReadingUi.pill(this, "下一章", ReadingUi.accent(this), () -> jump(1));
        buttons.addView(prevButton);
        buttons.addView(nextButton);
        buttons.addView(ReadingUi.pill(this, "目录", ReadingUi.accent(this), this::chooseChapter));
        buttons.addView(ReadingUi.pill(this, "字号", ReadingUi.accent(this), this::chooseZoom));
        buttons.addView(ReadingUi.pill(this, "夜间", ReadingUi.accent(this), this::toggleDark));
        footerRow.addView(buttons);
        root.addView(footerRow, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        setChapterEnabled(false);
    }

    private LinearLayout headerRow() {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ReadingUi.dp(this, 4), 0, ReadingUi.dp(this, 4));
        String title = progress.detail.item != null && progress.detail.item.title != null && !progress.detail.item.title.isBlank()
                ? progress.detail.item.title : "阅读";
        where = ReadingUi.bold(this, title, ReadingUi.ink(this), 15);
        where.setMaxLines(1);
        where.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(where, new LinearLayout.LayoutParams(0, -2, 1));
        progressText = ReadingUi.label(this, "", ReadingUi.muted(this), 12);
        progressText.setPadding(ReadingUi.dp(this, 8), 0, 0, 0);
        line.addView(progressText);
        return line;
    }

    // ---------------------------------------------------------------- template

    private void loadTemplate() {
        workers.execute(() -> {
            try (InputStream input = getAssets().open(ReaderPage.TEMPLATE_ASSET)) {
                byte[] bytes = ReaderIo.readAll(input, 256 * 1024);
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    template = text;
                    if (!isFinishing() && !isDestroyed()) openChapter(chapterIndex, pendingScroll);
                });
            } catch (Exception broken) {
                runOnUiThread(() -> failAndClose("阅读页面模板读取失败：" + ReadingUi.reason(broken)));
            }
        });
    }

    // ---------------------------------------------------------------- chapter load

    private void openChapter(int index, int scroll) {
        final List<Chapter> chapters = progress.detail.chapters;
        if (index < 0 || index >= chapters.size()) { toast("已经到头了"); return; }
        final int previous = chapterIndex;
        chapterIndex = index;
        progress.chapterIndex = index;
        final Chapter chapter = chapters.get(index);
        final int ticket = ++loadVersion;
        pendingScroll = scroll;
        setChapterEnabled(false);
        where.setText(chapter.name == null || chapter.name.isBlank() ? "第 " + (index + 1) + " 章" : chapter.name);
        progressText.setText((index + 1) + "/" + chapters.size() + " · 载入中…");
        // Never leave the previous body on screen while the next chapter loads: a failure must not
        // look like a successfully-opened chapter.
        page.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
        rendered = false;

        workers.execute(() -> {
            String text = null;
            String error = null;
            String url = chapter.url;
            if (url != null) text = store.cachedChapter(progress.sourceId, url);
            if (text == null || text.isBlank()) {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    String issue = ReadingEngine.supportIssue(source);
                    if (issue != null && !issue.isEmpty()) error = issue;
                    else {
                        text = engine.chapter(source, chapter);
                        if (url != null && text != null && !text.isBlank()) {
                            try { store.cacheChapter(progress.sourceId, url, text); } catch (Exception ignored) { }
                        }
                    }
                } catch (Exception failure) {
                    error = ReadingUi.reason(failure);
                }
            }
            final String body = text;
            final String why = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || ticket != loadVersion) return;
                if (why != null || body == null || body.isBlank()) {
                    // Never pollute the current chapter: on a first load nothing is on screen, so roll
                    // the index back so a save cannot record the unreachable chapter; on a re-load keep
                    // the body that is already open and just report the failure.
                    if (!rendered) {
                        chapterIndex = previous;
                        progress.chapterIndex = previous;
                        where.setText(chapterTitle(previous));
                    }
                    setChapterEnabled(true);
                    progressText.setText((chapterIndex + 1) + "/" + chapters.size());
                    final int retry = chapterIndex;
                    new AlertDialog.Builder(OnlineReaderActivity.this)
                            .setTitle("这一章没取到")
                            .setMessage((why == null ? "正文为空" : why) + "\n\n这一章没有载入，阅读进度会停在上一个成功打开的位置。可以重试或换一章。")
                            .setPositiveButton("重试", (d, w) -> openChapter(retry, 0))
                            .setNegativeButton("知道了", null)
                            .show();
                    return;
                }
                render(body);
            });
        });
    }

    private void render(String text) {
        if (template == null) return;
        lastText = text;
        rendered = true;
        String chapterName = progress.detail.chapters.get(chapterIndex).name;
        ReaderDocument.Section section = new ReaderDocument.Section(chapterName, toHtml(text), text);
        String html = ReaderPage.render(template, section, dark, ReaderPage.DEFAULT_FONT);
        page.getSettings().setTextZoom(Math.round(zoom() * getResources().getConfiguration().fontScale));
        page.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        page.setBackgroundColor(Color.parseColor(dark ? "#101B2B" : "#F3F6FB"));
        // Scroll after layout; loadData is async, so post it.
        final int target = pendingScroll;
        pendingScroll = 0;
        page.post(() -> { if (target > 0) page.scrollTo(0, target); showProgress(); });
        setChapterEnabled(true);
    }

    private String chapterTitle(int index) {
        List<Chapter> chapters = progress.detail.chapters;
        if (index < 0 || index >= chapters.size()) return "阅读";
        String name = chapters.get(index).name;
        return name == null || name.isBlank() ? "第 " + (index + 1) + " 章" : name;
    }

    /** Engine text is plain; escape every line and wrap as a paragraph — no HTML/JS reaches the WebView. */
    private String toHtml(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder html = new StringBuilder(normalized.length() + 32);
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            html.append("<p>").append(HtmlPolicy.escape(trimmed)).append("</p>");
        }
        if (html.length() == 0) html.append("<p>（这一章没有可显示的文字）</p>");
        return html.toString();
    }

    // ---------------------------------------------------------------- navigation

    private void jump(int delta) {
        int target = chapterIndex + delta;
        if (target < 0) { toast("已经是第一章"); return; }
        if (target >= progress.detail.chapters.size()) { toast("已经是最后一章"); return; }
        savePosition();
        openChapter(target, 0);
    }

    private void chooseChapter() {
        List<Chapter> chapters = progress.detail.chapters;
        String[] labels = new String[chapters.size()];
        for (int i = 0; i < labels.length; i++) {
            String name = chapters.get(i).name;
            labels[i] = (i + 1) + "/" + labels.length + "  " + (name == null || name.isBlank() ? "第 " + (i + 1) + " 章" : name);
        }
        new AlertDialog.Builder(this)
                .setTitle(progress.detail.item != null ? progress.detail.item.title : "目录")
                .setSingleChoiceItems(labels, chapterIndex, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != chapterIndex) { savePosition(); openChapter(which, 0); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseZoom() {
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
                    getPreferences(0).edit().putInt("online_reader_zoom", ZOOM_STEPS[which]).apply();
                    page.getSettings().setTextZoom(Math.round(ZOOM_STEPS[which] * getResources().getConfiguration().fontScale));
                    showProgress();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int zoom() {
        int saved = getPreferences(0).getInt("online_reader_zoom", 100);
        for (int step : ZOOM_STEPS) if (step == saved) return saved;
        return 100;
    }

    private void toggleDark() {
        dark = !dark;
        getPreferences(0).edit().putBoolean("online_reader_dark", dark).apply();
        // Repaint the chapter already open; do not reload the page (that would reset the scroll).
        if (page != null) {
            page.setBackgroundColor(Color.parseColor(dark ? "#101B2B" : "#F3F6FB"));
            if (rendered && lastText != null && template != null) {
                int keep = page.getScrollY();
                long mark = ++loadVersion; // the re-render below supersedes any in-flight load
                ReaderDocument.Section section = new ReaderDocument.Section(
                        progress.detail.chapters.get(chapterIndex).name, toHtml(lastText), lastText);
                String html = ReaderPage.render(template, section, dark, ReaderPage.DEFAULT_FONT);
                page.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                page.post(() -> { if (mark == loadVersion) page.scrollTo(0, keep); });
            }
        }
    }

    // ---------------------------------------------------------------- progress / state

    private void setChapterEnabled(boolean enabled) {
        loading = !enabled;
        if (prevButton != null) prevButton.setEnabled(enabled && chapterIndex > 0);
        if (nextButton != null) nextButton.setEnabled(enabled && chapterIndex + 1 < progress.detail.chapters.size());
        if (footerRow != null) footerRow.setEnabled(enabled);
    }

    private void showProgress() {
        if (progress == null || page == null || progress.detail == null) return;
        int total = progress.detail.chapters.size();
        int height = Math.round(page.getContentHeight() * page.getScale());
        int span = height - page.getHeight();
        double within = span <= 0 ? 1 : Math.min(1, Math.max(0, page.getScrollY() / (double) span));
        int percent = (int) Math.round(Math.min(1, (chapterIndex + within) / total) * 100);
        progressText.setText((chapterIndex + 1) + "/" + total + " 章 · " + percent + "%");
        bar.setProgress((int) Math.round(percent * 10));
    }

    private void savePosition() {
        // Only a body that actually rendered has a chapter + scroll worth recording.
        if (progress == null || page == null || loading || !rendered) return;
        progress.chapterIndex = chapterIndex;
        progress.scrollY = page.getScrollY();
        progress.updatedAt = System.currentTimeMillis();
        try { store.saveProgress(progress); } catch (RuntimeException ignored) { }
    }

    @Override protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (page != null && progress != null) { progress.scrollY = page.getScrollY(); progress.chapterIndex = chapterIndex; }
        super.onSaveInstanceState(state);
        state.putInt("chapter", chapterIndex);
        state.putInt("scroll", page == null ? pendingScroll : page.getScrollY());
        state.putBoolean("dark", dark);
    }

    @Override protected void onDestroy() {
        loadVersion++;
        workers.shutdownNow();
        if (page != null) {
            page.stopLoading();
            page.setWebViewClient(null);
            page.removeAllViews();
            page.destroy();
            page = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- misc

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
    }

    private void failAndClose(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
