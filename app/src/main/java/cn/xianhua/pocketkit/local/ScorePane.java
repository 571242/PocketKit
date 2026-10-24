package cn.xianhua.pocketkit.local;

import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.ToolException;

/**
 * 记分牌（061）。
 *
 * 存在本机，支持多队伍加减分与撤销上一次操作。
 * 不做多人同步、不上传分数、不联网。
 */
final class ScorePane {

    private final LocalToolsActivity host;
    private final LocalStore store;
    /** 每行：名称、分数、历史（用 ; 分隔的差值），用于撤销。 */
    private final List<String[]> rows = new ArrayList<>();

    ScorePane(LocalToolsActivity host) {
        this.host = host;
        this.store = new LocalStore(host);
    }

    void install() {
        rows.clear();
        rows.addAll(store.scoreboard());
        if (rows.isEmpty()) {
            rows.add(new String[]{"A 队", "0", ""});
            rows.add(new String[]{"B 队", "0", ""});
        }

        EditText name = host.uiField("队伍名", "C 队");
        host.uiButton("添加队伍", () -> {
            try {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    throw new ToolException("请填写队伍名。");
                }
                for (String[] r : rows) {
                    if (r[0].equals(n)) {
                        throw new ToolException("已经有一个叫「" + n + "」的队伍了。");
                    }
                }
                rows.add(new String[]{n, "0", ""});
                name.setText("");
                persist();
                render();
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });

        host.uiButton("刷新", this::render);
        // 动态区域用独立容器：直接往 body 追加会无限增长，清空 body 会删掉输入框
        panel = host.uiContainer();
        render();
    }

    private android.widget.LinearLayout panel;

    private void render() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            sb.append(r[0]).append("    ").append(r[1]).append('\n');
        }
        sb.append("\n共 ").append(rows.size()).append(" 支队伍。数据存在本机。");
        host.uiShow(sb.toString());

        // 只重建自己的动态容器，保留上面的输入框；否则每刷新一次按钮就多一批
        panel.removeAllViews();
        if (rows.isEmpty()) {
            return;
        }
        // 每支队伍一组加减按钮
        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            String[] r = rows.get(i);
            panel.addView(label(r[0] + "：当前 " + r[1]));
            panel.addView(action(r[0] + "  +1", () -> change(idx, 1)));
            panel.addView(action(r[0] + "  +2", () -> change(idx, 2)));
            panel.addView(action(r[0] + "  −1", () -> change(idx, -1)));
            panel.addView(action(r[0] + "  撤销上一次", () -> undo(idx)));
            panel.addView(action(r[0] + "  归零", () -> {
                rows.get(idx)[1] = "0";
                rows.get(idx)[2] = "";
                persist();
                render();
            }));
        }
    }

    private TextView label(String text) {
        TextView t = new TextView(host);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(host.uiMuted());
        t.setPadding(0, host.uiDp(8), 0, 0);
        return t;
    }

    private android.widget.Button action(String text, Runnable r) {
        android.widget.Button b = new android.widget.Button(host);
        b.setText(text);
        b.setTextColor(host.uiAccent());
        b.setMinHeight(host.uiDp(44));
        b.setOnClickListener(v -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                host.uiFail(e);
            }
        });
        return b;
    }

    private void change(int idx, int delta) {
        String[] r = rows.get(idx);
        int v;
        try {
            v = Integer.parseInt(r[1]);
        } catch (NumberFormatException e) {
            host.uiFail(new ToolException("「" + r[0] + "」的分数被改坏了，已重置为 0。"));
            v = 0;
        }
        r[1] = String.valueOf(v + delta);
        String hist = r[2] == null ? "" : r[2];
        r[2] = hist.isEmpty() ? String.valueOf(delta) : hist + ";" + delta;
        persist();
        render();
    }

    private void undo(int idx) {
        String[] r = rows.get(idx);
        String hist = r[2] == null ? "" : r[2];
        if (hist.isEmpty()) {
            host.uiShow("「" + r[0] + "」没有可撤销的操作。");
            return;
        }
        int cut = hist.lastIndexOf(';');
        String last = cut < 0 ? hist : hist.substring(cut + 1);
        r[2] = cut < 0 ? "" : hist.substring(0, cut);
        try {
            r[1] = String.valueOf(Integer.parseInt(r[1]) - Integer.parseInt(last));
        } catch (NumberFormatException e) {
            host.uiFail(new ToolException("分数记录异常，无法撤销。"));
            return;
        }
        persist();
        render();
    }

    private void persist() {
        try {
            store.saveScoreboard(rows);
        } catch (ToolException e) {
            host.uiFail(e);
        }
    }
}
