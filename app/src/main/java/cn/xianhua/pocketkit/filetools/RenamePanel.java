package cn.xianhua.pocketkit.filetools;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.ManagerStore;

/**
 * F02 批量改名。
 *
 * <p>一次给整批文件换名字：查找替换、加前缀、加后缀、加序号，扩展名默认不动。
 * 三条不做假的地方：
 * <ul>
 *   <li>执行前必须先「预览」，预览里就是最终会写下去的名字；非法名与重名整批拦下。</li>
 *   <li>改完用提供器**实际返回的 URI** 接着操作，不假设旧 URI 还有效。</li>
 *   <li>失败按项显示，不冒充"整批成功"；取消后已完成的保持完成，没做的标"未执行"。</li>
 * </ul>
 */
final class RenamePanel {

    private static final int PREVIEW_LIMIT = 200;

    private final Host host;
    private final FileSession session;
    private final State state;

    private TextView status;
    private EditText find, replace, prefix, suffix, start, digits;
    private CheckBox numberFirst, keepExtension;

    static final class State {
        final List<ManagerStore.Node> nodes = new ArrayList<>();
        RenameRules.Plan plan;
        String summary = "尚未选择文件";
        String detail = "";
        Cancel cancel;
        String find = "", replace = "", prefix = "", suffix = "", start = "1", digits = "0";
        boolean numberFirst, keepExtension = true;

        boolean busy() {
            return cancel != null;
        }
    }

    RenamePanel(Host host) {
        this.host = host;
        this.session = new FileSession(host.activity());
        Object retained = host.retained();
        this.state = retained instanceof State ? (State) retained : new State();
    }

    void build() {
        host.retain(state);
        if (state.busy()) {
            state.cancel.cancel();
            state.cancel = null;
            state.summary = "上次的后台操作因页面重建而中断，请重新开始。";
            state.plan = null;
        }
        host.note("选中文件 → 填规则 → 预览 → 执行。扩展名默认保留。");
        host.note("同目录重名或名字非法会整批拦下；失败按项显示，不冒充整批成功。");
        status = host.note(state.summary);

        host.button("选择文件（可多选）", () -> host.pickDocuments("*/*", uris -> {
            if (uris == null || uris.isEmpty()) {
                host.show("没有选择文件。");
                return;
            }
            accept(uris);
        }));

        find = host.field("查找（留空表示不替换）", "例如 副本");
        replace = host.field("替换为（留空表示删掉）", "");
        prefix = host.field("前缀", "");
        suffix = host.field("后缀（放在扩展名之前）", "");
        start = host.field("序号起始（留空表示不加序号）", "1");
        digits = host.field("序号补零位数（0 表示不补）", "0");
        numberFirst = host.checkbox("序号放在名字前面", state.numberFirst);
        keepExtension = host.checkbox("保留扩展名", state.keepExtension);

        // 旋转后把用户填过的规则原样放回去，不让人重填一遍。
        find.setText(state.find);
        replace.setText(state.replace);
        prefix.setText(state.prefix);
        suffix.setText(state.suffix);
        start.setText(state.start);
        digits.setText(state.digits);
        // 边输边记：只靠"预览时捕获"的话，填完就旋转会把输入丢掉。
        for (EditText input : new EditText[]{find, replace, prefix, suffix, start, digits}) {
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override public void afterTextChanged(android.text.Editable s) {
                    capture();
                }
            });
        }
        numberFirst.setOnCheckedChangeListener((view, checked) -> state.numberFirst = checked);
        keepExtension.setOnCheckedChangeListener((view, checked) -> state.keepExtension = checked);

        host.button("预览", this::preview);
        host.button("执行改名", this::confirmRun);
        host.button("取消", () -> {
            if (state.cancel != null) {
                state.cancel.cancel();
                host.show("已请求取消，已完成的项会保留。");
            } else {
                host.show("当前没有正在进行的操作。");
            }
        });

        status.setText(state.summary);
        if (!state.detail.isEmpty()) {
            host.show(state.detail);
        }
    }

    /** 控件里的规则收进 state：旋转后要能原样恢复。 */
    private void capture() {
        state.find = find == null ? state.find : find.getText().toString();
        state.replace = replace == null ? state.replace : replace.getText().toString();
        state.prefix = prefix == null ? state.prefix : prefix.getText().toString();
        state.suffix = suffix == null ? state.suffix : suffix.getText().toString();
        state.start = start == null ? state.start : start.getText().toString();
        state.digits = digits == null ? state.digits : digits.getText().toString();
        state.numberFirst = numberFirst != null && numberFirst.isChecked();
        state.keepExtension = keepExtension == null || keepExtension.isChecked();
    }

    /** 由 Activity 把 Intent / ClipData 送进来的位置。 */
    void accept(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        if (state.busy()) {
            host.show("上一次操作还在进行。");
            return;
        }
        Cancel cancel = new Cancel();
        state.cancel = cancel;
        state.nodes.clear();
        state.plan = null;
        state.detail = "";
        state.summary = "正在列出 " + uris.size() + " 个位置…";
        if (status != null) {
            status.setText(state.summary);
        }
        host.setRunning(true);
        host.onCancel(cancel::cancel);
        List<Uri> roots = new ArrayList<>(uris);
        host.background(() -> {
            List<ManagerStore.Node> files;
            try {
                files = session.files(roots, cancel, null);
            } catch (Exception error) {
                finish(cancel, () -> host.fail(error));
                return;
            }
            List<String> skipped = new ArrayList<>(session.skipped());
            finish(cancel, () -> {
                state.nodes.clear();
                state.nodes.addAll(files);
                StringBuilder text = new StringBuilder(files.isEmpty()
                        ? "选中的位置里没有文件"
                        : "已选 " + files.size() + " 个文件，填规则后点「预览」。");
                if (!skipped.isEmpty()) {
                    text.append("\n跳过了 ").append(skipped.size()).append(" 个位置，它们不会被改名：");
                    for (int i = 0; i < skipped.size() && i < 10; i++) {
                        text.append('\n').append(skipped.get(i));
                    }
                    if (skipped.size() > 10) {
                        text.append("\n…还有 ").append(skipped.size() - 10).append(" 个");
                    }
                }
                state.summary = text.toString();
                status.setText(state.summary);
                host.show(state.summary);
            });
        });
    }

    /** 收尾：令牌过期就整份丢弃，页面已销毁就不碰 View。 */
    private void finish(Cancel cancel, Runnable ui) {
        host.post(() -> {
            if (state.cancel != cancel) {
                return;
            }
            state.cancel = null;
            if (!host.alive()) {
                state.summary = "上次的操作已结束，但页面已重建：请重新预览确认结果。";
                state.detail = "";
                state.plan = null;
                return;
            }
            host.setRunning(false);
            ui.run();
        });
    }

    // ------------------------------------------------------------ 预览

    private RenameRules.Rule ruleFromUi() {
        capture();
        RenameRules.Rule rule = new RenameRules.Rule();
        rule.find = state.find;
        rule.replace = state.replace;
        rule.prefix = state.prefix;
        rule.suffix = state.suffix;
        rule.numberFirst = state.numberFirst;
        rule.keepExtension = state.keepExtension;
        String startText = state.start.trim();
        rule.numbered = !startText.isEmpty();
        if (rule.numbered) {
            try {
                rule.start = Integer.parseInt(startText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("序号起始必须是整数");
            }
            if (rule.start < 0) {
                throw new IllegalArgumentException("序号起始不能是负数");
            }
        }
        String digitsText = state.digits.trim();
        if (!digitsText.isEmpty()) {
            try {
                rule.digits = Math.max(0, Math.min(9, Integer.parseInt(digitsText)));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("补零位数必须是整数");
            }
        }
        return rule;
    }

    private void preview() {
        if (state.nodes.isEmpty()) {
            host.show("请先选择文件。");
            return;
        }
        if (state.busy()) {
            host.show("上一次操作还在进行。");
            return;
        }
        RenameRules.Rule rule;
        try {
            rule = ruleFromUi();
        } catch (IllegalArgumentException error) {
            host.fail(error);
            return;
        }
        if (rule.isEmpty()) {
            host.show("请至少填一项规则（查找/替换、前缀、后缀、序号）。");
            return;
        }
        List<RenameRules.Entry> entries = new ArrayList<>();
        for (ManagerStore.Node node : state.nodes) {
            entries.add(new RenameRules.Entry(node.uri.toString(), node.name, FileSession.directoryKey(node)));
        }
        // 查重名要列目录，是 I/O：放后台，绝不在主线程上问提供器。
        Cancel cancel = new Cancel();
        state.cancel = cancel;
        host.setRunning(true);
        host.onCancel(cancel::cancel);
        List<ManagerStore.Node> nodes = new ArrayList<>(state.nodes);
        host.background(() -> {
            RenameRules.Plan plan = RenameRules.plan(entries, rule, directory -> {
                for (ManagerStore.Node node : nodes) {
                    if (FileSession.directoryKey(node).equals(directory)) {
                        return session.siblingNames(node);
                    }
                }
                throw new java.io.IOException("该目录已不在已选列表里");
            });
            finish(cancel, () -> {
                state.plan = plan;
                state.summary = plan.executable()
                        ? "预览：" + plan.changeCount() + " 项会被改名。"
                        : "预览发现 " + plan.blockers.size() + " 处问题，已阻止执行。";
                state.detail = renderPreview(plan);
                status.setText(state.summary);
                host.show(state.detail);
            });
        });
    }

    private static String renderPreview(RenameRules.Plan plan) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (RenameRules.Entry entry : plan.entries) {
            if (shown++ >= PREVIEW_LIMIT) {
                text.append("…还有 ").append(plan.entries.size() - PREVIEW_LIMIT).append(" 项未列出\n");
                break;
            }
            text.append(entry.name).append("  →  ").append(entry.newName == null ? "（无新名）" : entry.newName);
            if (entry.blocked != null) {
                text.append("   [阻止：").append(entry.blocked).append(']');
            }
            text.append('\n');
        }
        if (!plan.blockers.isEmpty()) {
            text.append("\n阻止原因（改规则后重新预览）：\n");
            for (String blocker : plan.blockers) {
                text.append("- ").append(blocker).append('\n');
            }
        }
        for (String warning : plan.warnings) {
            text.append("\n注意：").append(warning).append('\n');
        }
        return text.toString().trim();
    }

    // ------------------------------------------------------------ 执行

    private void confirmRun() {
        RenameRules.Plan plan = state.plan;
        if (plan == null) {
            host.show("请先点「预览」，确认最终名字再执行。");
            return;
        }
        if (!plan.executable()) {
            host.show("预览里有非法名或重名，已阻止执行。改规则后重新预览。");
            return;
        }
        if (plan.changeCount() == 0) {
            host.show("没有名字会变，无需执行。");
            return;
        }
        if (state.busy()) {
            host.show("上一次操作还在进行。");
            return;
        }
        new AlertDialog.Builder(host.activity())
                .setTitle("改名 " + plan.changeCount() + " 项？")
                .setMessage("逐项执行：成功的立即生效，失败的单独列出。这不是一次原子操作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("执行", (dialog, which) -> run(plan))
                .show();
    }

    private void run(RenameRules.Plan plan) {
        Cancel cancel = new Cancel();
        state.cancel = cancel;
        host.setRunning(true);
        host.onCancel(cancel::cancel);
        status.setText("正在改名…");
        final Map<String, ManagerStore.Node> byId = new LinkedHashMap<>();
        for (ManagerStore.Node node : state.nodes) {
            byId.put(node.uri.toString(), node);
        }
        host.background(() -> {
            RenameRules.run(plan, entry -> {
                ManagerStore.Node node = byId.get(entry.id);
                if (node == null) {
                    throw new java.io.IOException("文件已不在列表中，请重新选择");
                }
                return session.rename(node, entry.newName);
            }, cancel, (done, total, entry) -> {
                if (done % 10 == 0 || done == total) {
                    host.post(() -> {
                        if (host.alive()) {
                            status.setText("改名 " + done + "/" + total);
                        }
                    });
                }
            });
            finish(cancel, () -> adopt(plan, byId));
        });
    }

    /**
     * 用提供器实际返回的 URI 重建列表。
     *
     * <p>不假设旧 URI 在改名后仍然有效——部分提供器一改完名就换 document id。
     */
    private void adopt(RenameRules.Plan plan, Map<String, ManagerStore.Node> byId) {
        state.nodes.clear();
        for (RenameRules.Entry entry : plan.entries) {
            ManagerStore.Node node = byId.get(entry.id);
            if (entry.newId != null) {
                try {
                    node = session.resolve(Uri.parse(entry.newId));
                } catch (Exception ignored) {
                    // 解不出来就保留旧节点，这一项在结果里已经标了失败。
                }
            }
            if (node != null) {
                state.nodes.add(node);
            }
        }
        state.plan = null;
        StringBuilder text = new StringBuilder(RenameRules.describe(plan));
        for (RenameRules.Entry entry : plan.entries) {
            if (entry.failure != null) {
                text.append('\n').append(entry.name).append(" — ").append(entry.failure);
            } else if (entry.renamed()) {
                text.append('\n').append(entry.name).append("  →  ").append(entry.newName);
            }
        }
        text.append("\n\n失败的项保持原名，可改规则后重新预览。");
        state.summary = RenameRules.describe(plan);
        state.detail = text.toString();
        status.setText(state.summary);
        host.show(state.detail);
    }
}
