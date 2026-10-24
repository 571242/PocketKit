package cn.xianhua.pocketkit;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

public final class MainActivity extends BaseToolActivity {
    private List<ToolRegistry.Entry> tools = new ArrayList<>();
    private final List<String> recent = new ArrayList<>();
    private final Map<String, LinearLayout> navigation = new LinkedHashMap<>();
    private Set<String> pinned;
    private SharedPreferences prefs;
    private EditText search;
    private LinearLayout results, filters;
    private cn.xianhua.pocketkit.proxy.ProxyQuickPanel proxyPanel;
    private TextView pageTitle, pageHint, resultCount, updateBtn;
    private String scope = "常用", category = "全部分类";
    private int columns;
    private boolean dark;

    @Override protected void onCreate(Bundle state) {
        try {
            getIntent().putExtra("title", "拾用"); super.onCreate(state); createHome(state);
            if (state == null) cn.xianhua.pocketkit.update.UpdateManager.get().check(this, false, null);
        } catch (RuntimeException | LinkageError failure) {
            results = null; StartupDiagnostics.show(this, failure);
        }
    }

    private void createHome(Bundle state) {
        dark = Color.luminance(surface) < .3f;
        prefs = getSharedPreferences("ui_preferences", MODE_PRIVATE);
        pinned = new HashSet<>();
        for (String id : prefs.getStringSet("pinned", Collections.emptySet())) pinned.add(ToolCatalog.canonicalId(id));
        for (String id : prefs.getString("recent", "").split(",")) {
            String canonical = ToolCatalog.canonicalId(id);
            if (!canonical.isEmpty() && !recent.contains(canonical)) recent.add(canonical);
        }
        if (state != null) {
            scope = state.getString("scope", "常用");
            category = state.getString("category", "全部分类");
        }
        if (!"全部".equals(scope)) scope = "常用";
        columns = getResources().getConfiguration().screenWidthDp < 360 || getResources().getConfiguration().fontScale > 1.25f ? 1 : 2;
        root.getChildAt(0).setVisibility(View.GONE);
        root.setFocusableInTouchMode(true); root.requestFocus();
        body.setPadding(0, dp(16), 0, dp(20));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingCopy = new LinearLayout(this); headingCopy.setOrientation(LinearLayout.VERTICAL);
        pageTitle = text("拾用", ink, 30, true); headingCopy.addView(pageTitle);
        pageHint = text("你的随身工具箱", muted, 14, false); pageHint.setPadding(0, dp(4), 0, 0); headingCopy.addView(pageHint);
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, -2, 1));
        // One small entry, only on the full tool list; the home page keeps a clean header.
        updateBtn = text("⋯", accent, 20, true);
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(0, 0, 0, 0);
        updateBtn.setMinWidth(dp(44)); updateBtn.setMinHeight(dp(44));
        updateBtn.setVisibility(View.GONE);
        updateBtn.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));
        updateBtn.setContentDescription("关于、免责声明与更新");
        updateBtn.setOnClickListener(v -> new cn.xianhua.pocketkit.update.UpdateSheet(this, ink, muted, surface, accent).show());
        heading.addView(updateBtn);
        body.addView(heading);

        LinearLayout searchBox = new LinearLayout(this); searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(14), 0, dp(12), 0); searchBox.setBackground(ToolUi.fill(surface, dp(18), Color.TRANSPARENT, 0));
        searchBox.addView(new ToolIconView(this, "search", muted), new LinearLayout.LayoutParams(dp(22), dp(22)));
        search = new EditText(this); search.setId(R.id.home_search); search.setSingleLine(true); search.setHint("搜索工具、用途或编号");
        search.setTextColor(ink); search.setHintTextColor(muted); search.setTextSize(15); search.setPadding(dp(10), dp(12), 0, dp(12));
        search.setBackgroundColor(Color.TRANSPARENT); search.setContentDescription("搜索全部工具");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(); return true; });
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView clear = text("清除", accent, 13, true); clear.setGravity(Gravity.CENTER); clear.setMinHeight(dp(48));
        clear.setContentDescription("清除搜索"); clear.setOnClickListener(v -> search.setText("")); clear.setVisibility(View.GONE);
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(48), -1));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2); searchLp.setMargins(0, dp(20), 0, dp(16)); body.addView(searchBox, searchLp);
        if (state != null) search.setText(state.getString("query", ""));
        clear.setVisibility(search.length() == 0 ? View.GONE : View.VISIBLE);

        cn.xianhua.pocketkit.proxy.ProxyState.get().init(this);
        proxyPanel = new cn.xianhua.pocketkit.proxy.ProxyQuickPanel(this, ink, muted, surface, accent);
        LinearLayout.LayoutParams proxyLp = new LinearLayout.LayoutParams(-1, -2);
        proxyLp.setMargins(0, dp(14), 0, 0);
        body.addView(proxyPanel, proxyLp);

        filters = new LinearLayout(this); filters.setOrientation(LinearLayout.VERTICAL); body.addView(filters);
        resultCount = text("", muted, 13, false); resultCount.setPadding(dp(2), dp(18), 0, dp(4)); body.addView(resultCount);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); body.addView(results);
        buildNavigation();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE); render(); }
            public void afterTextChanged(Editable s) { }
        });
        reload();
    }

    private void buildNavigation() {
        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6)); nav.setBackground(ToolUi.fill(surface, dp(22), Color.TRANSPARENT, 0));
        String[] labels = {"常用", "全部", "管理器", "连点器"}; String[] icons = {"star", "grid", "文件管理", "自动化"};
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i]; LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.addView(new ToolIconView(this, icons[i], accent), new LinearLayout.LayoutParams(-1, dp(23)));
            TextView caption = text(label, muted, 12, false); caption.setGravity(Gravity.CENTER); caption.setIncludeFontPadding(false);
            caption.setPadding(0, dp(4), 0, 0); item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            item.setFocusable(true); item.setContentDescription(label); item.setOnClickListener(v -> {
                hideKeyboard();
                if ("管理器".equals(label)) { openId("M01"); return; }
                if ("连点器".equals(label)) { startActivity(new Intent(this, AutomationToolsActivity.class).putExtra("tool_id", "C01").putExtra("title", "连点器")); return; }
                scope = label; render(); ((ScrollView)body.getParent()).smoothScrollTo(0, 0);
            });
            navigation.put(label, item); nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(6), 0, dp(8)); root.addView(nav, lp);
    }

    private void reload() {
        try { tools = ToolRegistry.load(this); render(); }
        catch (Exception e) { fail(e); if (resultCount != null) resultCount.setText("工具目录暂时无法读取"); }
    }
    private void render() {
        if (results == null) return;
        results.removeAllViews();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean searching = !query.isEmpty();
        pageTitle.setText("全部".equals(scope) ? "工具库" : "拾用");
        pageHint.setText("全部".equals(scope) ? "按用途整理，想用的更好找" : "你的随身工具箱");

        if (proxyPanel != null) proxyPanel.setVisibility(!searching && "常用".equals(scope) ? View.VISIBLE : View.GONE);
        if (updateBtn != null) updateBtn.setVisibility("全部".equals(scope) && !searching ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, LinearLayout> n : navigation.entrySet()) {
            boolean active = scope.equals(n.getKey()); LinearLayout item = n.getValue();
            item.setSelected(active); item.setBackground(ToolUi.ripple(this, active ? tint(accent) : Color.TRANSPARENT, dp(16), Color.TRANSPARENT, 0));
            TextView label = (TextView)item.getChildAt(1); label.setTextColor(active ? accent : muted); label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        renderFilters(searching);
        List<ToolRegistry.Entry> filtered = new ArrayList<>();
        for (ToolRegistry.Entry e : tools) {
            if (!e.ready || !ToolCatalog.visible(e.id)) continue;
            if (!searching && "常用".equals(scope) && "C01".equals(e.id)) continue; // Also available in the dedicated navigation entry.
            if (searching) {
                if (!(e.name + " " + e.id + " " + e.category + " " + ToolDescriptions.get(e) + " " + ToolCatalog.keywords(e.id)).toLowerCase(Locale.ROOT).contains(query)) continue;
            } else if ("常用".equals(scope)) {
                if (!pinned.contains(e.id) && !recent.contains(e.id)) continue;
            } else if (!"全部分类".equals(category) && !category.equals(e.category)) continue;
            filtered.add(e);
        }
        boolean common = "常用".equals(scope) && !searching;
        if (common) filtered.sort(Comparator.comparingInt((ToolRegistry.Entry e) -> pinned.contains(e.id) ? 0 : 1)
                .thenComparingInt(e -> pinned.contains(e.id) ? 0 : recent.indexOf(e.id)));
        else filtered.sort(Comparator.comparingInt((ToolRegistry.Entry e) -> ToolCatalog.order(e.category))
                .thenComparingInt(e -> pinned.contains(e.id) ? 0 : 1));
        resultCount.setText(searching ? "找到 " + filtered.size() + " 个工具 · 搜索全部分类" : common ? "常用工具 · 长按卡片可置顶" : category + " · " + filtered.size() + " 个工具");
        if (filtered.isEmpty()) {
            if (common) {
                section("从这里开始", "用过的工具会自动出现在这里");
                List<ToolRegistry.Entry> recommended = new ArrayList<>();
                for (String id : new String[]{"034", "011", "051", "064"}) for (ToolRegistry.Entry e : tools) if (id.equals(e.id) && e.ready) recommended.add(e);
                addGrid(recommended); TextView browse = actionText("查看全部工具", () -> { scope = "全部"; render(); }); results.addView(browse);
            } else {
                section("没有找到匹配工具", "试试名称、用途或编号，例如：图片、录音、PDF");
                results.addView(actionText("清除搜索与分类", () -> { category = "全部分类"; search.setText(""); render(); }));
            }
            return;
        }
        String current = ""; List<ToolRegistry.Entry> group = new ArrayList<>();
        for (ToolRegistry.Entry e : filtered) {
            String next = common ? pinned.contains(e.id) ? "置顶工具" : "最近使用" : e.category;
            if (!next.equals(current)) {
                if (!group.isEmpty()) addGrid(group);
                group = new ArrayList<>(); current = next; section(current, "");
            }
            group.add(e);
        }
        if (!group.isEmpty()) addGrid(group);
    }
    private void renderFilters(boolean searching) {
        filters.removeAllViews(); filters.setVisibility("全部".equals(scope) && !searching ? View.VISIBLE : View.GONE);
        if (filters.getVisibility() != View.VISIBLE) return;
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this); chips.setGravity(Gravity.CENTER_VERTICAL);
        List<String> labels = new ArrayList<>(); labels.add("全部分类"); labels.addAll(ToolCatalog.CATEGORIES);
        for (String label : labels) {
            TextView chip = text(label, category.equals(label) ? surface : muted, 14, category.equals(label)); chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(10), dp(15), dp(10)); chip.setMinHeight(dp(48)); chip.setFocusable(true); chip.setSelected(category.equals(label));
            chip.setBackground(ToolUi.ripple(this, category.equals(label) ? accent : surface, dp(16), Color.TRANSPARENT, 0));
            chip.setOnClickListener(v -> { category = label; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, dp(8), 0); chips.addView(chip, lp);
            if (category.equals(label)) chip.post(() -> scroll.scrollTo(Math.max(0, chip.getLeft() - dp(12)), 0));
        }
        scroll.addView(chips); filters.addView(scroll);
        TextView choose = actionText("选择分类  ▾", () -> {
            String[] choices = labels.toArray(new String[0]);
            new android.app.AlertDialog.Builder(this).setTitle("按用途找工具").setSingleChoiceItems(choices, labels.indexOf(category), (dialog, which) -> {
                category = choices[which]; dialog.dismiss(); render();
            }).setNegativeButton("取消", null).show();
        });
        choose.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); filters.addView(choose);
    }
    private void section(String title, String subtitle) {
        TextView heading = text(title, ink, 18, true); heading.setPadding(dp(2), dp(16), 0, dp(10)); results.addView(heading);
        if (!subtitle.isEmpty()) { TextView hint = text(subtitle, muted, 14, false); hint.setPadding(dp(2), 0, 0, dp(14)); results.addView(hint); }
    }
    private void addGrid(List<ToolRegistry.Entry> entries) {
        for (int start = 0; start < entries.size(); start += columns) {
            LinearLayout row = new LinearLayout(this); row.setBaselineAligned(false);
            for (int column = 0; column < columns; column++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(column == 0 ? 0 : dp(5), 0, column == columns - 1 ? 0 : dp(5), dp(10));
                row.addView(start + column < entries.size() ? toolCard(entries.get(start + column)) : new View(this), lp);
            }
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private View toolCard(ToolRegistry.Entry e) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(15), dp(15), dp(13), dp(15));
        card.setBackground(ToolUi.ripple(this, surface, dp(20), Color.TRANSPARENT, 0)); card.setMinimumHeight(dp(146)); card.setFocusable(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        int color = categoryColor(e.category); ToolIconView icon = new ToolIconView(this, e.category, color); icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        icon.setBackground(ToolUi.fill(tint(color), dp(12), Color.TRANSPARENT, 0)); top.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView star = text(pinned.contains(e.id) ? "★" : "", accent, 14, false); star.setGravity(Gravity.END); top.addView(star, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(top);
        TextView name = text(e.name, ink, 16, true); name.setPadding(0, dp(12), 0, dp(5)); card.addView(name);
        TextView detail = text(ToolDescriptions.get(e), muted, 12, false); detail.setMaxLines(2); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.setLineSpacing(dp(2), 1); card.addView(detail);
        card.setContentDescription(e.name + "，" + ToolDescriptions.get(e) + (pinned.contains(e.id) ? "，已置顶" : "") + "，长按切换置顶");
        card.setOnClickListener(v -> open(e)); card.setOnLongClickListener(v -> {
            boolean added = pinned.add(e.id); if (!added) pinned.remove(e.id);
            prefs.edit().putStringSet("pinned", new HashSet<>(pinned)).apply();
            Toast.makeText(this, added ? "已置顶到常用" : "已取消置顶", Toast.LENGTH_SHORT).show(); render(); return true;
        });
        return card;
    }
    private int categoryColor(String group) {
        int[] light = {0xFF7353B7,0xFF3269B5,0xFF087F92,0xFF8A5797,0xFF346DA8,0xFF997021,0xFFB05351,0xFF937326,0xFF167E72,0xFF4C7190,0xFF885CC0};
        int[] night = {0xFFC5ADF4,0xFF9BBCFF,0xFF7CD6DF,0xFFDEA9E7,0xFF9ACBFF,0xFFE3C57E,0xFFFFB2AD,0xFFE5C884,0xFF87D9CA,0xFFAACDEB,0xFFC7ABF2};
        int index = ToolCatalog.order(group); return (dark ? night : light)[Math.min(index, light.length - 1)];
    }
    private int tint(int color) {
        float weight = dark ? .13f : .09f;
        return Color.rgb(Math.round(Color.red(surface)*(1-weight)+Color.red(color)*weight), Math.round(Color.green(surface)*(1-weight)+Color.green(color)*weight), Math.round(Color.blue(surface)*(1-weight)+Color.blue(color)*weight));
    }
    private TextView text(String value, int color, float size, boolean bold) {
        TextView v = new TextView(this); v.setText(value); ToolUi.text(v, color, size);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v;
    }
    private TextView actionText(String label, Runnable action) {
        TextView v = text(label, accent, 14, true); v.setPadding(dp(4), dp(10), dp(4), dp(10)); v.setMinHeight(dp(48)); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true); v.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0)); v.setOnClickListener(view -> action.run()); return v;
    }
    private void hideKeyboard() {
        search.clearFocus(); InputMethodManager keyboard = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
    private void openId(String id) {
        for (ToolRegistry.Entry e : tools) if (id.equals(e.id) && e.ready) { open(e); return; }
        Toast.makeText(this, "工具暂不可用，请重新打开应用后重试", Toast.LENGTH_SHORT).show();
    }
    private void open(ToolRegistry.Entry e) {
        try {
            hideKeyboard(); startActivity(new Intent(this, e.activityClass()).putExtra("tool_id", e.id).putExtra("title", e.name));
            recent.remove(e.id); recent.add(0, e.id); if (recent.size() > 20) recent.remove(recent.size() - 1);
            prefs.edit().putString("recent", String.join(",", recent)).apply();
        } catch (Exception ex) { fail(ex); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("scope", scope); out.putString("category", category); out.putString("query", search == null ? "" : search.getText().toString()); super.onSaveInstanceState(out);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == cn.xianhua.pocketkit.proxy.ProxyQuickPanel.REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) cn.xianhua.pocketkit.proxy.PocketVpnService.start(this);
            else {
                // Status is already DISCONNECTED here, so setStatus() short-circuits without
                // notifying anyone and the switch stayed visually ON. Republishing the same state
                // re-syncs every listener instead.
                cn.xianhua.pocketkit.proxy.ProxyState state = cn.xianhua.pocketkit.proxy.ProxyState.get();
                state.setError("已取消 VPN 授权，未连接");
                state.refreshStatus();
                if (results != null) reload();
            }
        }
    }
    @Override protected void onResume() { super.onResume(); if (results != null) reload(); }
}
