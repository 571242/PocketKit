package cn.xianhua.pocketkit.reading.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Progress;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import cn.xianhua.pocketkit.reading.ReadingStore;
import cn.xianhua.pocketkit.reading.SourceHttp;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 漫画阅读：纵向条漫流。
 *
 * <p>正文由 {@link ReadingEngine#chapterImages} 解析成一串图片地址，再逐张经
 * {@link SourceHttp#fetchBytes} 下载（带源自己的 Referer / User-Agent），解码后按原始比例排列。
 * 只保留当前一话的位图，翻页时释放；失败的一张显示占位而不是整话报废。
 */
public final class MangaReaderActivity extends Activity {
    private static final int IMAGE_MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_WIDTH = 1600;

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private int loadVersion;

    private ReadingStore store;
    private ReadingEngine engine;
    private Source source;
    private Progress progress;

    private ScrollView scroller;
    private LinearLayout pages;
    private TextView where, progressText;
    private ProgressBar bar;
    private View prevButton, nextButton;

    private int chapterIndex;
    private int pendingScroll;
    private boolean rendered;
    private final List<Bitmap> bitmaps = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!openIntent()) return;
        if (state != null) {
            chapterIndex = state.getInt("chapter", progress.chapterIndex);
            pendingScroll = state.getInt("scroll", progress.scrollY);
        } else {
            chapterIndex = progress.chapterIndex;
            pendingScroll = progress.scrollY;
        }
        buildUi();
        openChapter(chapterIndex, pendingScroll);
    }

    private boolean openIntent() {
        try {
            store = new ReadingStore(this);
            engine = new ReadingEngine();
        } catch (RuntimeException problem) {
            failAndClose("阅读数据没能打开：" + ReadingUi.reason(problem));
            return false;
        }
        try {
            progress = ReadingUi.readProgress(getIntent(), store);
        } catch (RuntimeException bad) {
            failAndClose(ReadingUi.reason(bad));
            return false;
        }
        if (progress == null) { failAndClose("没有要打开的漫画记录"); return false; }
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
        ReadingUi.insets(this, root, 0, 6);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(ReadingUi.dp(this, 16), 0, ReadingUi.dp(this, 16), 0);
        info.addView(headerRow());
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        info.addView(bar, new LinearLayout.LayoutParams(-1, ReadingUi.dp(this, 3)));
        root.addView(info);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(ReadingUi.base(this));
        pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(pages, new LinearLayout.LayoutParams(-1, -2));
        scroller.setOnScrollChangeListener((v, x, y, ox, oy) -> showProgress());
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        HorizontalScrollView footerRow = new HorizontalScrollView(this);
        footerRow.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(ReadingUi.dp(this, 6), 0, ReadingUi.dp(this, 6), 0);
        prevButton = ReadingUi.pill(this, "上一话", ReadingUi.accent(this), () -> jump(-1));
        nextButton = ReadingUi.pill(this, "下一话", ReadingUi.accent(this), () -> jump(1));
        buttons.addView(prevButton);
        buttons.addView(nextButton);
        buttons.addView(ReadingUi.pill(this, "目录", ReadingUi.accent(this), this::chooseChapter));
        buttons.addView(ReadingUi.pill(this, "重载", ReadingUi.accent(this), () -> openChapter(chapterIndex, 0)));
        buttons.addView(ReadingUi.pill(this, "换源", ReadingUi.muted(this), this::backToHub));
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
                ? progress.detail.item.title : "漫画";
        where = ReadingUi.bold(this, title, ReadingUi.ink(this), 15);
        where.setMaxLines(1);
        where.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(where, new LinearLayout.LayoutParams(0, -2, 1));
        progressText = ReadingUi.label(this, "", ReadingUi.muted(this), 12);
        progressText.setPadding(ReadingUi.dp(this, 8), 0, 0, 0);
        line.addView(progressText);
        return line;
    }

    // ---------------------------------------------------------------- chapter

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
        where.setText(chapter.name == null || chapter.name.isBlank() ? "第 " + (index + 1) + " 话" : chapter.name);
        progressText.setText((index + 1) + "/" + chapters.size() + " · 解析图片…");
        clearPages();
        pages.addView(ReadingUi.empty(this, "正在解析这一话的图片…"));

        workers.execute(() -> {
            List<String> urls = null;
            String error = null;
            try {
                if (Thread.currentThread().isInterrupted()) return;
                String issue = ReadingEngine.supportIssue(source);
                if (issue != null && !issue.isEmpty()) error = issue;
                else urls = engine.chapterImages(source, chapter);
            } catch (Exception failure) {
                error = ReadingUi.reason(failure);
            }
            final List<String> ready = urls;
            final String why = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || ticket != loadVersion) return;
                if (why != null || ready == null || ready.isEmpty()) {
                    clearPages();
                    if (!rendered) { chapterIndex = previous; progress.chapterIndex = previous; where.setText(chapterTitle(previous)); }
                    pages.addView(ReadingUi.empty(this, "这一话没取到：" + (why == null ? "没有任何图片" : why)));
                    setChapterEnabled(true);
                    progressText.setText((chapterIndex + 1) + "/" + chapters.size());
                    return;
                }
                renderImages(ready, chapter, ticket);
            });
        });
    }

    /** Downloads and lays out images one by one so a slow host never blocks the whole chapter. */
    private void renderImages(List<String> urls, Chapter chapter, int ticket) {
        clearPages();
        rendered = true;
        Map<String, String> headers = imageHeaders(chapter);
        final int[] loaded = {0};
        for (int i = 0; i < urls.size(); i++) {
            TextView placeholder = ReadingUi.note(this, "第 " + (i + 1) + "/" + urls.size() + " 张加载中…");
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setPadding(0, ReadingUi.dp(this, 24), 0, ReadingUi.dp(this, 24));
            pages.addView(placeholder);
            final int slot = i;
            final String url = urls.get(i);
            workers.execute(() -> {
                Bitmap bmp = null;
                String fail = null;
                try {
                    SourceHttp.BinaryResponse bytes = SourceHttp.fetchBytes(url, headers, IMAGE_MAX_BYTES);
                    bmp = decode(bytes.bytes, MAX_IMAGE_WIDTH);
                    if (bmp == null) fail = "图片解码失败";
                } catch (Exception failure) {
                    fail = ReadingUi.reason(failure);
                }
                final Bitmap image = bmp;
                final String error = fail;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || ticket != loadVersion) {
                        if (image != null) image.recycle();
                        return;
                    }
                    ViewGroup parent = pages;
                    int at = parent.indexOfChild(placeholder);
                    if (at < 0) { if (image != null) image.recycle(); return; }
                    parent.removeViewAt(at);
                    if (image == null) {
                        TextView broken = ReadingUi.note(this, "第 " + (slot + 1) + " 张没加载出来" + (error == null ? "" : "：" + error));
                        broken.setGravity(Gravity.CENTER);
                        parent.addView(broken, at);
                    } else {
                        bitmaps.add(image);
                        ImageView view = new ImageView(this);
                        view.setAdjustViewBounds(true);
                        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        view.setImageBitmap(image);
                        view.setBackgroundColor(Color.parseColor("#0B1220"));
                        parent.addView(view, at, new LinearLayout.LayoutParams(-1, -2));
                    }
                    loaded[0]++;
                    progressText.setText((chapterIndex + 1) + "/" + progress.detail.chapters.size() + " · " + loaded[0] + "/" + urls.size() + " 张");
                    if (loaded[0] == urls.size()) {
                        setChapterEnabled(true);
                        final int target = pendingScroll;
                        pendingScroll = 0;
                        scroller.post(() -> { if (target > 0) scroller.scrollTo(0, target); showProgress(); });
                    }
                });
            });
        }
        setChapterEnabled(true);
    }

    private Map<String, String> imageHeaders(Chapter chapter) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        JSONObject rule = source == null ? null : source.rule;
        Object raw = rule == null ? null : (rule.has("header") ? rule.opt("header") : rule.opt("headers"));
        JSONObject object = null;
        if (raw instanceof JSONObject) object = (JSONObject) raw;
        else if (raw instanceof String && !((String) raw).isBlank()) {
            try { object = new JSONObject((String) raw); } catch (Exception ignored) { }
        }
        if (object != null) {
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key, "");
                if (!value.isBlank()) headers.put(key, value);
            }
        }
        if (!contains(headers, "Referer") && chapter.url != null && !chapter.url.isBlank()) headers.put("Referer", chapter.url);
        if (!contains(headers, "User-Agent")) headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        return headers;
    }

    private static boolean contains(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static Bitmap decode(byte[] bytes, int maxWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void clearPages() {
        if (pages != null) pages.removeAllViews();
        for (Bitmap bmp : bitmaps) if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        bitmaps.clear();
        rendered = false;
    }

    private String chapterTitle(int index) {
        List<Chapter> chapters = progress.detail.chapters;
        if (index < 0 || index >= chapters.size()) return "漫画";
        String name = chapters.get(index).name;
        return name == null || name.isBlank() ? "第 " + (index + 1) + " 话" : name;
    }

    // ---------------------------------------------------------------- navigation

    private void jump(int delta) {
        int target = chapterIndex + delta;
        if (target < 0) { toast("已经是第一话"); return; }
        if (target >= progress.detail.chapters.size()) { toast("已经是最后一话"); return; }
        savePosition();
        openChapter(target, 0);
    }

    private void chooseChapter() {
        List<Chapter> chapters = progress.detail.chapters;
        String[] labels = new String[chapters.size()];
        for (int i = 0; i < labels.length; i++) {
            String name = chapters.get(i).name;
            labels[i] = (i + 1) + "/" + labels.length + "  " + (name == null || name.isBlank() ? "第 " + (i + 1) + " 话" : name);
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

    private void backToHub() { finish(); }

    // ---------------------------------------------------------------- progress

    private void setChapterEnabled(boolean enabled) {
        if (prevButton != null) prevButton.setEnabled(enabled && chapterIndex > 0);
        if (nextButton != null) nextButton.setEnabled(enabled && chapterIndex + 1 < progress.detail.chapters.size());
    }

    private void showProgress() {
        if (progress == null || scroller == null || progress.detail == null) return;
        int total = progress.detail.chapters.size();
        int height = scroller.getChildAt(0) == null ? 0 : scroller.getChildAt(0).getHeight();
        int span = height - scroller.getHeight();
        double within = span <= 0 ? 1 : Math.min(1, Math.max(0, scroller.getScrollY() / (double) span));
        int percent = (int) Math.round(Math.min(1, (chapterIndex + within) / total) * 100);
        progressText.setText((chapterIndex + 1) + "/" + total + " 话 · " + percent + "%");
        bar.setProgress((int) Math.round(percent * 10));
    }

    private void savePosition() {
        if (progress == null || scroller == null || !rendered) return;
        progress.chapterIndex = chapterIndex;
        progress.scrollY = scroller.getScrollY();
        progress.updatedAt = System.currentTimeMillis();
        try { store.saveProgress(progress); } catch (RuntimeException ignored) { }
    }

    @Override protected void onPause() { savePosition(); super.onPause(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("chapter", chapterIndex);
        state.putInt("scroll", scroller == null ? pendingScroll : scroller.getScrollY());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        loadVersion++;
        workers.shutdownNow();
        clearPages();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- misc

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
