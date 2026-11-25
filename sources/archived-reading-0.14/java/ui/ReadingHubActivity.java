package cn.xianhua.pocketkit.reading.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.Detail;
import cn.xianhua.pocketkit.reading.ReadingModels.Item;
import cn.xianhua.pocketkit.reading.ReadingModels.Progress;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import cn.xianhua.pocketkit.reading.ReadingStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阅读中心首页：小说 / 漫画 / 视频切换、跨源搜索、继续阅读/观看、收藏。
 *
 * <p>搜索按当前类型只并发查询已启用的源，最多 {@link #MAX_PARALLEL} 个同时进行，结果逐源追加；
 * 每次搜索都带一个代号，换词或取消就把旧的代号作废，晚到的结果直接丢弃。单个源失败只在页脚
 * 记一笔，不拖垮其他源。
 */
public final class ReadingHubActivity extends Activity {
    private static final int MAX_PARALLEL = 3;
    private static final int RESULT_LIMIT = 20;

    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_PARALLEL);
    private final List<Future<?>> searchTasks = new CopyOnWriteArrayList<>();
    private final AtomicInteger searchSeq = new AtomicInteger();
    private volatile int activeSeq;

    private ReadingStore store;
    private ReadingEngine engine;

    private String kind = "novel";
    private LinearLayout novelTab, mangaTab, videoTab;
    private EditText searchField;
    private LinearLayout collBox, resultBox;
    private TextView resultHeader;
    private ScrollView scroller;

    private int found;
    private boolean firstResume = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            store = new ReadingStore(this);
            engine = new ReadingEngine();
        } catch (RuntimeException problem) {
            toast("阅读数据没能打开：" + ReadingUi.reason(problem));
        }
        if (state != null) kind = state.getString("kind", "novel");
        buildUi();
        if (state != null) {
            String query = state.getString("query", "");
            searchField.setText(query);
            if (!query.trim().isEmpty()) startSearch();
            else loadCollections();
        } else {
            loadCollections();
        }
    }

    // ---------------------------------------------------------------- chrome

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReadingUi.base(this));
        root.setPadding(ReadingUi.dp(this, 16), ReadingUi.dp(this, 8), ReadingUi.dp(this, 16), 0);
        ReadingUi.insets(this, root, 16, 8);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ReadingUi.bold(this, "阅读", ReadingUi.ink(this), 21);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(ReadingUi.pill(this, "源管理", ReadingUi.accent(this),
                () -> startActivity(new Intent(this, ReadingSourcesActivity.class))));
        root.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(0, ReadingUi.dp(this, 6), 0, ReadingUi.dp(this, 6));
        tabs.setBackground(ToolUi.fill(ReadingUi.surface(this), ReadingUi.dp(this, 20), 0, 0));
        novelTab = tabButton("小说", tabs);
        mangaTab = tabButton("漫画", tabs);
        videoTab = tabButton("视频", tabs);
        root.addView(tabs);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0, ReadingUi.dp(this, 4), 0, ReadingUi.dp(this, 8));
        searchField = new EditText(this);
        ToolUi.text(searchField, ReadingUi.ink(this), 16);
        searchField.setHintTextColor(ReadingUi.muted(this));
        searchField.setHint("书名、漫画名或视频名");
        searchField.setSingleLine(true);
        searchField.setMinHeight(ReadingUi.dp(this, 48));
        searchField.setPadding(ReadingUi.dp(this, 14), 0, ReadingUi.dp(this, 14), 0);
        searchField.setBackground(ToolUi.fill(ReadingUi.surface(this), ReadingUi.dp(this, 14), ReadingUi.outline(this), ReadingUi.dp(this, 1)));
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, -2, 1));
        searchRow.addView(ReadingUi.pill(this, "搜索", ReadingUi.accent(this), this::startSearch));
        searchRow.addView(ReadingUi.pill(this, "取消", ReadingUi.muted(this), this::cancelSearch));
        root.addView(searchRow);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, ReadingUi.dp(this, 24));
        resultHeader = ReadingUi.note(this, "");
        content.addView(resultHeader);
        collBox = new LinearLayout(this);
        collBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(collBox);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultBox);
        scroller.addView(content);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (s.toString().trim().isEmpty()) cancelSearch();
            }
            public void afterTextChanged(Editable s) { }
        });
        setKind(kind);
    }

    private LinearLayout tabButton(String label, LinearLayout parent) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        TextView caption = ReadingUi.label(this, label, ReadingUi.muted(this), 15);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption, new LinearLayout.LayoutParams(-1, ReadingUi.dp(this, 44)));
        item.setFocusable(true);
        item.setContentDescription(label);
        item.setTag(label);
        parent.addView(item, new LinearLayout.LayoutParams(0, ReadingUi.dp(this, 44), 1));
        return item;
    }

    private void setKind(String next) {
        kind = next;
        styleTab(novelTab, "novel".equals(kind));
        styleTab(mangaTab, "manga".equals(kind));
        styleTab(videoTab, "video".equals(kind));
        cancelSearch();
        loadCollections();
    }

    private void styleTab(LinearLayout tab, boolean active) {
        tab.setSelected(active);
        int accent = ReadingUi.accent(this);
        int tint = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent));
        TextView caption = (TextView) tab.getChildAt(0);
        caption.setTextColor(active ? accent : ReadingUi.muted(this));
        caption.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tab.setBackground(ToolUi.ripple(this, active ? tint : Color.TRANSPARENT, ReadingUi.dp(this, 15), 0, 0));
        tab.setOnClickListener(v -> {
            Object tag = tab.getTag();
            String target = "小说".equals(tag) ? "novel" : ("漫画".equals(tag) ? "manga" : "video");
            if (!kind.equals(target)) { hideKeyboard(); setKind(target); }
        });
    }

    // ---------------------------------------------------------------- collections

    private void loadCollections() {
        if (store == null) return;
        collBox.removeAllViews();
        resultBox.removeAllViews();
        resultHeader.setText("");
        List<Progress> rows;
        try {
            rows = store.history(kind);
        } catch (RuntimeException problem) {
            collBox.addView(ReadingUi.empty(this, "继续阅读记录读不出来：" + ReadingUi.reason(problem)));
            return;
        }
        List<Progress> recent = new ArrayList<>(rows);
        recent.sort(Comparator.comparingLong((Progress p) -> p.updatedAt).reversed());
        List<Progress> favorites = new ArrayList<>();
        for (Progress p : recent) if (p.favorite) favorites.add(p);

        collBox.addView(ReadingUi.heading(this, "video".equals(kind) ? "继续观看" : ("manga".equals(kind) ? "继续看漫画" : "继续阅读")));
        if (recent.isEmpty()) {
            collBox.addView(ReadingUi.empty(this, "还没有记录。搜到作品点开就会自动记在这里。"));
        } else {
            int limit = Math.min(recent.size(), 12);
            for (int i = 0; i < limit; i++) collBox.addView(resumeRow(recent.get(i)));
        }
        collBox.addView(ReadingUi.heading(this, "收藏"));
        if (favorites.isEmpty()) {
            collBox.addView(ReadingUi.empty(this, "没有收藏。点开任意记录右侧的 ☆ 就能收进这里。"));
        } else {
            for (Progress p : favorites) collBox.addView(resumeRow(p));
        }
    }

    private View resumeRow(Progress p) {
        LinearLayout card = ReadingUi.card(this);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String name = p.detail != null && p.detail.item != null && p.detail.item.title != null && !p.detail.item.title.isBlank()
                ? p.detail.item.title : "未命名作品";
        int total = p.detail != null && p.detail.chapters != null ? p.detail.chapters.size() : 0;
        String unit = "video".equals(p.kind) ? "集" : ("manga".equals(p.kind) ? "话" : "章");
        String where = total > 0 ? " · 第 " + Math.min(p.chapterIndex + 1, total) + "/" + total + " " + unit : "";
        TextView title = ReadingUi.label(this, name + where, ReadingUi.ink(this), 15);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(ReadingUi.pill(this, p.favorite ? "★" : "☆", ReadingUi.accent(this), () -> toggleFavorite(p)));
        card.addView(line);
        card.setOnClickListener(v -> launch(p));
        return card;
    }

    private void toggleFavorite(Progress p) {
        p.favorite = !p.favorite;
        p.updatedAt = System.currentTimeMillis();
        try {
            store.saveProgress(p);
        } catch (RuntimeException problem) {
            toast("收藏没保存成功：" + ReadingUi.reason(problem));
            return;
        }
        loadCollections();
    }

    private void launch(Progress p) {
        // Save first, then hand over only the id: a large catalogue can exceed the Binder limit
        // if it travels inside the Intent. The reader looks the record back up from the store.
        try {
            store.saveProgress(p);
        } catch (RuntimeException problem) {
            toast("进度没能保存：" + ReadingUi.reason(problem));
            return;
        }
        Intent intent;
        if ("video".equals(p.kind)) intent = new Intent(this, ReadingPlayerActivity.class);
        else if ("manga".equals(p.kind)) intent = new Intent(this, MangaReaderActivity.class);
        else intent = new Intent(this, OnlineReaderActivity.class);
        intent.putExtra("progress_id", p.id);
        intent.putExtra("progress_kind", p.kind);
        startActivity(intent);
    }

    // ---------------------------------------------------------------- search

    private void startSearch() {
        hideKeyboard();
        if (store == null) return;
        String query = searchField.getText().toString().trim();
        cancelTasks();
        final int seq = searchSeq.incrementAndGet();
        activeSeq = seq;
        found = 0;
        collBox.setVisibility(View.GONE);
        resultBox.removeAllViews();

        if (query.isEmpty()) { resultHeader.setText(""); resultBox.addView(ReadingUi.empty(this, "输入书名、漫画名或视频名再点搜索。")); return; }

        List<Source> pool = new ArrayList<>();
        try {
            for (Source s : store.sources()) if (s.enabled && kind.equals(s.kind)) pool.add(s);
        } catch (RuntimeException problem) {
            resultHeader.setText("");
            resultBox.addView(ReadingUi.empty(this, "源清单读不出来：" + ReadingUi.reason(problem)));
            return;
        }
        if (pool.isEmpty()) {
            resultHeader.setText("");
            String label = "novel".equals(kind) ? "小说" : ("manga".equals(kind) ? "漫画" : "视频");
            resultBox.addView(ReadingUi.empty(this, "没有已启用的" + label + "源。到「源管理」添加或启用一个再搜。"));
            return;
        }

        resultHeader.setText("正在 " + pool.size() + " 个源里搜「" + query + "」…");
        final AtomicInteger remaining = new AtomicInteger(pool.size());
        final AtomicInteger failures = new AtomicInteger();
        final List<String> failedNames = java.util.Collections.synchronizedList(new ArrayList<>());

        for (final Source source : pool) {
            searchTasks.add(workers.submit(() -> {
                if (seq != activeSeq) return;
                List<Item> hits = null;
                String error = null;
                try {
                    String issue = ReadingEngine.supportIssue(source);
                    if (issue != null && !issue.isEmpty()) error = issue;
                    else hits = engine.search(source, query);
                } catch (Exception failure) {
                    error = ReadingUi.reason(failure);
                }
                final List<Item> ready = hits;
                final String why = error;
                runOnUiThread(() -> {
                    if (seq != activeSeq || isFinishing() || isDestroyed()) return;
                    if (why != null) {
                        failures.incrementAndGet();
                        failedNames.add(source.name == null ? source.id : source.name);
                    } else {
                        addResults(source, ready);
                    }
                    if (remaining.decrementAndGet() == 0) finishSearch(failures.get(), failedNames);
                });
            }));
        }
    }

    private void addResults(Source source, List<Item> hits) {
        if (hits == null || hits.isEmpty()) return;
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(ReadingUi.note(this, "来自 " + (source.name == null ? source.id : source.name) + " · " + hits.size() + " 条"));
        int cap = Math.min(hits.size(), RESULT_LIMIT);
        for (int i = 0; i < cap; i++) {
            group.addView(itemRow(source, hits.get(i)));
            found++;
        }
        resultBox.addView(group);
    }

    private View itemRow(Source source, Item item) {
        LinearLayout card = ReadingUi.card(this);
        String title = item.title == null || item.title.isBlank() ? "未命名" : item.title;
        card.addView(ReadingUi.label(this, title, ReadingUi.ink(this), 15));
        StringBuilder meta = new StringBuilder();
        if (item.author != null && !item.author.isBlank()) meta.append(item.author);
        if (item.description != null && !item.description.isBlank()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(item.description.length() > 60 ? item.description.substring(0, 60) + "…" : item.description);
        }
        if (meta.length() > 0) card.addView(ReadingUi.note(this, meta.toString()));
        card.setOnClickListener(v -> openItem(source, item));
        return card;
    }

    private void finishSearch(int failures, List<String> failedNames) {
        int size = found;
        if (size == 0) {
            String why = failures > 0
                    ? "没有匹配结果；有 " + failures + " 个源这次没搜到（" + join(failedNames) + "），可稍后重试或换源。"
                    : "没有找到相关结果，换个关键词试试。";
            resultBox.addView(ReadingUi.empty(this, why));
        }
        resultHeader.setText(size == 0 ? "0 条结果" : "共 " + size + " 条结果" + (failures > 0 ? " · " + failures + " 个源失败" : ""));
    }

    private String join(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size() && i < 3; i++) {
            if (i > 0) out.append("、");
            out.append(names.get(i));
        }
        if (names.size() > 3) out.append(" 等");
        return out.toString();
    }

    private void cancelSearch() {
        cancelTasks();
        searchSeq.incrementAndGet();
        activeSeq = searchSeq.get();
        collBox.setVisibility(View.VISIBLE);
        resultBox.removeAllViews();
        resultHeader.setText("");
    }

    /** Interrupt running searches and drop queued ones, so a stale source cannot write back. */
    private void cancelTasks() {
        for (Future<?> task : searchTasks) task.cancel(true);
        searchTasks.clear();
    }

    // ---------------------------------------------------------------- open

    private void openItem(Source source, Item item) {
        // A web source is only ever a browser entry; it must not be treated as a book.
        if ("web".equals(source.kind)) { openWeb(item.url); return; }
        String issue = ReadingEngine.supportIssue(source);
        if (issue != null && !issue.isEmpty()) {
            toast(issue);
            return;
        }
        final Progress progress = new Progress();
        progress.sourceId = source.id;
        progress.kind = kind;
        progress.chapterIndex = 0;
        progress.scrollY = 0;
        progress.positionMs = 0;
        progress.favorite = false;
        progress.updatedAt = System.currentTimeMillis();
        toast("正在打开…");
        workers.execute(() -> {
            Detail detail;
            try {
                detail = engine.detail(source, item);
            } catch (Exception failure) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showOpenFailure(failure); });
                return;
            }
            progress.detail = detail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (detail == null || detail.chapters == null || detail.chapters.isEmpty()) {
                    toast("这个源没有返回可读章节");
                    return;
                }
                launch(progress);
            });
        });
    }

    private void showOpenFailure(Throwable failure) {
        new AlertDialog.Builder(this)
                .setTitle("打不开这个作品")
                .setMessage(ReadingUi.reason(failure) + "\n\n可以换一个源，或到「源管理」检查这个源。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openWeb(String url) {
        if (url == null || url.isBlank()) { toast("这个网页条目没有地址"); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException noBrowser) {
            toast("这台设备没有能打开网页的应用");
        }
    }

    // ---------------------------------------------------------------- misc

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("kind", kind);
        state.putString("query", searchField == null ? "" : searchField.getText().toString());
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from the reader/player: refresh favourites and "continue" rows without losing a search in progress.
        if (firstResume) { firstResume = false; return; }
        if (searchField == null || searchField.getText().toString().trim().isEmpty()) loadCollections();
    }

    @Override protected void onDestroy() {
        cancelTasks();
        searchSeq.incrementAndGet(); // every in-flight search is now stale
        workers.shutdownNow();
        super.onDestroy();
    }
}
