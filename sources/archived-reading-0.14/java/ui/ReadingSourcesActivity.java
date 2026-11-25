package cn.xianhua.pocketkit.reading.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.ImportCandidate;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import cn.xianhua.pocketkit.reading.ReadingModels.Subscription;
import cn.xianhua.pocketkit.reading.ReadingStore;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 订阅与源管理：获取内置精选、URL / JSON 导入、差异预览、编辑源 JSON、启停与删除。
 *
 * <p>上网只在用户明确点击「获取」时发生，没有任何后台自动更新。导入一律先预览，冲突默认不选，
 * 不支持的项禁止选；个人改过的源默认不被覆盖（导入不替换 localModified 的项）。
 */
public final class ReadingSourcesActivity extends Activity {
    private static final String BUILTIN_ID = "builtin-curated";
    private static final String BUILTIN_NAME = "拾用精选";
    private static final String BUILTIN_URL = "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/content/book-sources.json";
    private static final String BUILTIN_ASSET = "reading/builtin-sources.json";

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private ReadingStore store;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            store = new ReadingStore(this);
            ensureBuiltin();
        } catch (RuntimeException problem) {
            toast("阅读数据没能打开：" + ReadingUi.reason(problem));
        }
        buildUi();
        rebuild();
    }

    /** Establish the built-in subscription only; its rules are not installed until the user pulls. */
    private void ensureBuiltin() {
        for (Subscription s : store.subscriptions()) if (BUILTIN_ID.equals(s.id)) return;
        Subscription sub = new Subscription();
        sub.id = BUILTIN_ID;
        sub.name = BUILTIN_NAME;
        sub.url = BUILTIN_URL;
        sub.builtin = true;
        sub.lastMessage = "";
        sub.updatedAt = "";
        store.saveSubscription(sub);
    }

    // ---------------------------------------------------------------- chrome

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ReadingUi.base(this));
        ReadingUi.insets(this, root, 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ReadingUi.dp(this, 8), ReadingUi.dp(this, 8), ReadingUi.dp(this, 8), 0);
        header.addView(ReadingUi.pill(this, "返回", ReadingUi.accent(this), this::finish));
        TextView title = ReadingUi.bold(this, "订阅与源", ReadingUi.ink(this), 21);
        title.setPadding(ReadingUi.dp(this, 4), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ReadingUi.dp(this, 16), 0, ReadingUi.dp(this, 16), ReadingUi.dp(this, 24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void rebuild() {
        if (content == null) return;
        content.removeAllViews();
        if (store == null) {
            content.addView(ReadingUi.empty(this, "阅读数据没能打开，源清单暂时不可用。"));
            return;
        }
        content.addView(ReadingUi.heading(this, "订阅"));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, 0, 0, ReadingUi.dp(this, 8));
        actions.addView(ReadingUi.pill(this, "添加订阅", ReadingUi.accent(this), this::addSubscription));
        actions.addView(ReadingUi.pill(this, "导入 JSON", ReadingUi.accent(this), this::importJson));
        content.addView(actions);

        List<Subscription> subs = store.subscriptions();
        if (subs.isEmpty()) content.addView(ReadingUi.empty(this, "没有订阅。点「添加订阅」粘贴一个 JSON 订阅地址。"));
        for (Subscription sub : subs) content.addView(subscriptionCard(sub));

        content.addView(ReadingUi.heading(this, "源"));
        List<Source> sources = store.sources();
        if (sources.isEmpty()) {
            content.addView(ReadingUi.empty(this,
                    "还没有源。先「获取精选」或导入一份 JSON，再在上面搜索。\n网络源只在点开时联网，不会在后台自动更新。"));
        }
        for (Source source : sources) content.addView(sourceCard(source));
    }

    private View subscriptionCard(Subscription sub) {
        LinearLayout card = ReadingUi.card(this);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String name = sub.name == null || sub.name.isBlank() ? sub.id : sub.name;
        TextView title = ReadingUi.label(this, name + (sub.builtin ? " · 内置" : ""), ReadingUi.ink(this), 15);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(ReadingUi.pill(this, "获取 / 更新", ReadingUi.accent(this), () -> fetchSubscription(sub)));
        card.addView(line);
        TextView url = ReadingUi.note(this, sub.url == null || sub.url.isBlank() ? "（没有地址）" : sub.url);
        card.addView(url);
        if (sub.lastMessage != null && !sub.lastMessage.isBlank()) card.addView(ReadingUi.note(this, "上次：" + sub.lastMessage));
        card.addView(ReadingUi.pill(this, "删除订阅", ReadingUi.danger(this), () -> confirmDeleteSubscription(sub)));
        return card;
    }

    private View sourceCard(Source source) {
        LinearLayout card = ReadingUi.card(this);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String name = source.name == null || source.name.isBlank() ? source.id : source.name;
        TextView title = ReadingUi.label(this, name, ReadingUi.ink(this), 15);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tag = ReadingUi.label(this, kindLabel(source.kind) + (source.localModified ? " · 已改" : ""),
                ReadingUi.muted(this), 12);
        tag.setPadding(0, 0, ReadingUi.dp(this, 8), 0);
        line.addView(tag);
        Switch toggle = new Switch(this);
        toggle.setChecked(source.enabled);
        toggle.setContentDescription("启用这个源");
        toggle.setOnCheckedChangeListener((v, checked) -> {
            source.enabled = checked;
            try {
                store.saveSource(source, false);
            } catch (RuntimeException problem) {
                source.enabled = !checked; // reflect the store, don't leave a switch that lies
                toast("启停没保存成功：" + ReadingUi.reason(problem));
                rebuild();
            }
        });
        line.addView(toggle);
        card.addView(line);

        if (source.url != null && !source.url.isBlank()) card.addView(ReadingUi.note(this, source.url));

        LinearLayout ops = new LinearLayout(this);
        ops.setGravity(Gravity.CENTER_VERTICAL);
        if ("web".equals(source.kind)) {
            ops.addView(ReadingUi.pill(this, "打开网页", ReadingUi.accent(this), () -> openWeb(source.url)));
        }
        ops.addView(ReadingUi.pill(this, "编辑", ReadingUi.accent(this), () -> editSource(source)));
        ops.addView(ReadingUi.pill(this, "删除", ReadingUi.danger(this), () -> confirmDeleteSource(source)));
        card.addView(ops);
        return card;
    }

    private String kindLabel(String kind) {
        if ("video".equals(kind)) return "视频";
        if ("manga".equals(kind)) return "漫画";
        if ("web".equals(kind)) return "网页";
        return "小说";
    }

    // ---------------------------------------------------------------- add / import

    private void addSubscription() {
        EditText input = textInput("https://…/book-sources.json");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("添加订阅")
                .setMessage("粘贴订阅 JSON 的地址，添加后会先预览、确认再导入。")
                .setView(input)
                .setPositiveButton("添加并获取", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!isHttp(url)) { toast("请填写 http 或 https 地址"); return; }
                    Subscription sub = new Subscription();
                    sub.id = "sub-" + Integer.toHexString(url.hashCode());
                    sub.name = Uri.parse(url).getHost() == null ? "自定义订阅" : Uri.parse(url).getHost();
                    sub.url = url;
                    sub.builtin = false;
                    sub.lastMessage = "";
                    sub.updatedAt = "";
                    try {
                        store.saveSubscription(sub);
                    } catch (RuntimeException problem) {
                        toast("订阅没保存成功：" + ReadingUi.reason(problem));
                        return;
                    }
                    rebuild();
                    fetchSubscription(sub);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importJson() {
        EditText input = textInput("粘贴 Legado 源 JSON 或 {\"sources\":[…]} 订阅包");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle("导入 JSON")
                .setMessage("粘贴一整段文字，先预览差异，再选择要导入的源。")
                .setView(input)
                .setPositiveButton("预览", (d, w) -> previewImport(input.getText().toString(), ""))
                .setNegativeButton("取消", null)
                .show();
    }

    private void fetchSubscription(Subscription sub) {
        if (sub.url == null || sub.url.isBlank()) { toast("这个订阅没有地址"); return; }
        toast("正在获取…");
        workers.execute(() -> {
            String json;
            try {
                json = ReadingEngine.fetchSubscription(sub.url);
            } catch (Exception failure) {
                final String why = ReadingUi.reason(failure);
                sub.lastMessage = "获取失败：" + why;
                sub.updatedAt = String.valueOf(System.currentTimeMillis());
                try { store.saveSubscription(sub); } catch (RuntimeException ignored) { }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    rebuild();
                    if (BUILTIN_ID.equals(sub.id)) offerBuiltinSnapshot(why);
                    else toast("获取失败：" + why);
                });
                return;
            }
            sub.lastMessage = "获取成功";
            sub.updatedAt = String.valueOf(System.currentTimeMillis());
            try { store.saveSubscription(sub); } catch (RuntimeException ignored) { }
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { rebuild(); previewImport(json, sub.id); } });
        });
    }

    /** A failed built-in pull may fall back to the bundled snapshot, but never silently. */
    private void offerBuiltinSnapshot(String why) {
        new AlertDialog.Builder(this)
                .setTitle("内置订阅暂时取不到")
                .setMessage(why + "\n\n可以先用随安装包内置的快照预览、确认导入；联网恢复后再「获取 / 更新」。")
                .setPositiveButton("使用内置快照", (d, w) -> loadBuiltinAsset())
                .setNegativeButton("稍后再试", null)
                .show();
    }

    private void loadBuiltinAsset() {
        workers.execute(() -> {
            String json;
            try (InputStream in = getAssets().open(BUILTIN_ASSET)) {
                byte[] buffer = new byte[8192];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
                json = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception missing) {
                runOnUiThread(() -> toast("内置快照也读不到：" + ReadingUi.reason(missing)));
                return;
            }
            final String ready = json;
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) previewImport(ready, BUILTIN_ID); });
        });
    }

    // ---------------------------------------------------------------- preview

    private void previewImport(String json, String subscriptionId) {
        if (json == null || json.trim().isEmpty()) { toast("没有可导入的内容"); return; }
        toast("正在比对差异…");
        workers.execute(() -> {
            List<ImportCandidate> candidates;
            try {
                candidates = store.preview(json, subscriptionId);
            } catch (Exception failure) {
                runOnUiThread(() -> showError("预览失败", ReadingUi.reason(failure)));
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (candidates == null || candidates.isEmpty()) { toast("这份内容里没有可识别的源"); return; }
                showDiffDialog(candidates);
            });
        });
    }

    private void showDiffDialog(List<ImportCandidate> candidates) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ReadingUi.dp(this, 16), ReadingUi.dp(this, 8), ReadingUi.dp(this, 16), ReadingUi.dp(this, 8));
        box.addView(ReadingUi.note(this, "冲突默认保留本地；需要时对每条冲突选「替换本地」或「另存副本」，其余勾选导入。"));
        List<CheckBox> boxes = new ArrayList<>();
        final java.util.Map<Integer, String> conflictChoice = new java.util.HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            ImportCandidate candidate = candidates.get(i);
            Source src = candidate.source;
            String name = src == null ? "未知源" : (src.name == null || src.name.isBlank() ? src.id : src.name);
            String kind = src == null ? "" : kindLabel(src.kind);
            String head = name + " · " + kind + "\n" + stateLabel(candidate.state)
                    + (candidate.message == null || candidate.message.isBlank() ? "" : " · " + candidate.message);
            if ("conflict".equals(candidate.state)) {
                // A conflict is not a plain checkbox: the user must pick replace vs keep-a-copy explicitly.
                final int idx = i;
                LinearLayout card = ReadingUi.card(this);
                card.addView(ReadingUi.label(this, head, ReadingUi.ink(this), 14));
                TextView status = ReadingUi.note(this, "当前：保留本地（不导入）");
                card.addView(status);
                LinearLayout ops = new LinearLayout(this);
                ops.setGravity(Gravity.CENTER_VERTICAL);
                ops.addView(ReadingUi.pill(this, "替换本地", ReadingUi.danger(this),
                        () -> { conflictChoice.put(idx, "replace"); status.setText("当前：用上游规则替换本地"); }));
                ops.addView(ReadingUi.pill(this, "另存副本", ReadingUi.accent(this),
                        () -> { conflictChoice.put(idx, "copy"); status.setText("当前：保留本地并另存一份副本"); }));
                ops.addView(ReadingUi.pill(this, "保留本地", ReadingUi.muted(this),
                        () -> { conflictChoice.remove(idx); status.setText("当前：保留本地（不导入）"); }));
                card.addView(ops);
                box.addView(card);
                boxes.add(null);
            } else {
                boolean selectable = "new".equals(candidate.state) || "update".equals(candidate.state);
                CheckBox check = new CheckBox(this);
                check.setText(head);
                check.setTextColor(ReadingUi.ink(this));
                check.setTextSize(14);
                check.setChecked(selectable);
                check.setEnabled(selectable);
                check.setMinHeight(ReadingUi.dp(this, 48));
                box.addView(check);
                boxes.add(check);
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("预览新增 / 更新")
                .setView(scroll)
                .setPositiveButton("导入选中", (d, w) -> {
                    List<ImportCandidate> plain = new ArrayList<>();
                    List<ImportCandidate> replace = new ArrayList<>();
                    List<ImportCandidate> copy = new ArrayList<>();
                    for (int i = 0; i < candidates.size(); i++) {
                        String choice = conflictChoice.get(i);
                        if ("replace".equals(choice)) replace.add(candidates.get(i));
                        else if ("copy".equals(choice)) copy.add(candidates.get(i));
                        else if (boxes.get(i) != null && boxes.get(i).isChecked()) plain.add(candidates.get(i));
                    }
                    if (plain.isEmpty() && replace.isEmpty() && copy.isEmpty()) { toast("没有选中任何要导入的项"); return; }
                    doImport(plain, replace, copy);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String stateLabel(String state) {
        if (state == null) return "未知";
        switch (state) {
            case "new": return "新增";
            case "update": return "更新";
            case "unchanged": return "无变化";
            case "conflict": return "冲突（本地已改，默认不选）";
            case "unsupported": return "不支持";
            default: return state;
        }
    }

    private void doImport(List<ImportCandidate> plain, List<ImportCandidate> replace, List<ImportCandidate> copy) {
        toast("正在导入…");
        workers.execute(() -> {
            int count = 0;
            String failure = null;
            try {
                // Plain new/update items keep local edits (replaceModified=false); replace is the
                // explicit "替换本地" choice for conflicts.
                if (!plain.isEmpty()) count += store.importSelected(plain, false);
                if (!replace.isEmpty()) count += store.importSelected(replace, true);
            } catch (Exception problem) {
                failure = ReadingUi.reason(problem);
            }
            int copies = 0;
            for (ImportCandidate candidate : copy) {
                try {
                    if (saveCopy(candidate)) copies++;
                } catch (Exception problem) {
                    if (failure == null) failure = ReadingUi.reason(problem);
                }
            }
            final int imported = count + copies;
            final int copyCount = copies;
            final String why = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (why != null && imported == 0) { showError("导入失败", why); return; }
                String note = "已导入 " + imported + " 个源";
                if (copyCount > 0) note += "（含 " + copyCount + " 份副本）";
                if (why != null) note += "；部分失败：" + why;
                toast(note);
                rebuild();
            });
        });
    }

    /** Save an upstream source as a new local entry, leaving the edited original untouched. */
    private boolean saveCopy(ImportCandidate candidate) throws Exception {
        Source copy = Source.fromJson(new org.json.JSONObject(candidate.source.toJson().toString()));
        copy.id = "copy-" + Long.toHexString(System.nanoTime());
        copy.originKey = "";
        copy.localModified = true; // a hand-kept copy, never silently overwritten by the next pull
        String base = copy.name == null || copy.name.isBlank() ? "源" : copy.name;
        copy.name = base + "（副本）";
        store.saveSource(copy, true);
        return true;
    }

    // ---------------------------------------------------------------- edit / delete

    private void editSource(Source source) {
        EditText input = textInput("");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(pretty(source.toJson()));
        input.setMinLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("编辑源 JSON")
                .setMessage("直接改 JSON。保存后视为本地修改，后续导入不会自动覆盖它。")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        Source edited = Source.fromJson(new JSONObject(input.getText().toString()));
                        edited.id = source.id; // keep a stable identity across an edit
                        store.saveSource(edited, true);
                        rebuild();
                    } catch (Exception problem) {
                        showError("JSON 没保存", ReadingUi.reason(problem));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSource(Source source) {
        String name = source.name == null || source.name.isBlank() ? source.id : source.name;
        new AlertDialog.Builder(this)
                .setTitle("删除源？")
                .setMessage("将移除「" + name + "」及其订阅关系，本地已缓存的章节不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        store.deleteSource(source.id);
                        rebuild();
                    } catch (RuntimeException problem) {
                        toast("删除失败：" + ReadingUi.reason(problem));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSubscription(Subscription sub) {
        String name = sub.name == null || sub.name.isBlank() ? sub.id : sub.name;
        new AlertDialog.Builder(this)
                .setTitle("删除订阅？")
                .setMessage("将移除订阅「" + name + "」。它导入过的源仍保留在下面的源列表里。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        store.deleteSubscription(sub.id);
                        rebuild();
                    } catch (RuntimeException problem) {
                        toast("删除失败：" + ReadingUi.reason(problem));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openWeb(String url) {
        if (url == null || !isHttp(url)) { toast("这个源没有可打开的网页地址"); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException noBrowser) {
            toast("这台设备没有能打开网页的应用");
        }
    }

    // ---------------------------------------------------------------- helpers

    private EditText textInput(String hint) {
        EditText input = new EditText(this);
        ToolUi.text(input, ReadingUi.ink(this), 16);
        input.setHintTextColor(ReadingUi.muted(this));
        input.setHint(hint);
        input.setPadding(ReadingUi.dp(this, 14), ReadingUi.dp(this, 10), ReadingUi.dp(this, 14), ReadingUi.dp(this, 10));
        input.setBackground(ToolUi.fill(ReadingUi.surface(this), ReadingUi.dp(this, 12), ReadingUi.outline(this), ReadingUi.dp(this, 1)));
        return input;
    }

    private boolean isHttp(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Indented JSON for editing; small objects, and a rejected indent just falls back to one line. */
    private String pretty(JSONObject json) {
        try {
            return json.toString(2);
        } catch (org.json.JSONException flat) {
            return json.toString();
        }
    }

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
    }

    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        workers.shutdownNow();
        super.onDestroy();
    }
}
