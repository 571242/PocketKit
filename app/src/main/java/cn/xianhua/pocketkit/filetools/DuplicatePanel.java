package cn.xianhua.pocketkit.filetools;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.ManagerStore;
import cn.xianhua.pocketkit.core.SizeFmt;

/**
 * F01 重复文件查找。
 *
 * <p>流程：选定授权位置 → 递归列出文件 → 先按大小分桶 → 同尺寸的再流式算 SHA-256 分组。
 * 三条不做假的地方：
 * <ul>
 *   <li>读不出来的项单独列出并写明原因，不会因为它读不到就说"没有重复"。</li>
 *   <li>默认一个都不勾；每组无论怎么勾都至少留一份（{@link DuplicateScan#selectForDeletion}）。</li>
 *   <li>删之前重读一次大小与修改时间，变了就不删，并说明是哪一项变了。</li>
 * </ul>
 */
final class DuplicatePanel {

    /** 一次最多铺 400 个勾选框：再多界面会卡，剩下的在结果文字里明确标注。 */
    private static final int ROW_LIMIT = 400;

    private final Host host;
    private final FileSession session;
    private final State state;

    private TextView status;
    private LinearLayout holder;
    private TextView selectionLabel;

    static final class State {
        final List<Uri> roots = new ArrayList<>();
        final Map<String, ManagerStore.Node> nodes = new LinkedHashMap<>();
        final java.util.Set<String> checked = new java.util.HashSet<>();
        DuplicateScan.Result result;
        String summary = "尚未扫描";
        String detail = "";
        Cancel cancel;

        boolean busy() {
            return cancel != null;
        }
    }

    DuplicatePanel(Host host) {
        this.host = host;
        this.session = new FileSession(host.activity());
        Object retained = host.retained();
        this.state = retained instanceof State ? (State) retained : new State();
    }

    void build() {
        host.retain(state);
        if (state.busy()) {
            // 旧页面的工作线程已被宿主 shutdownNow，那次操作不会再有结果。
            state.cancel.cancel();
            state.cancel = null;
            state.result = null;
            state.summary = "上次的后台操作因页面重建而中断，请重新开始。";
        }
        host.note("只处理你选中的位置：一个目录，或手动挑的几个文件。");
        host.note("先按大小筛，再对同大小的算 SHA-256；只勾要删的，每组会自动留一份。");
        status = host.note(state.summary);
        host.button("选择目录", this::pickTree);
        host.button("选择文件（可多选）", this::pickFiles);
        host.button("开始扫描", this::scan);
        host.button("取消扫描", () -> {
            if (state.cancel != null) {
                state.cancel.cancel();
                host.show("已请求取消，正在停止…");
            } else {
                host.show("当前没有正在进行的扫描。");
            }
        });
        host.button("删除已勾选的项", this::confirmDelete);
        status.setText(state.summary);
        if (!state.detail.isEmpty()) {
            host.show(state.detail);
        }
        render();
    }

    // ------------------------------------------------------------ 输入

    private void pickTree() {
        host.pickTree(uri -> {
            if (uri == null) {
                host.show("没有选择目录。");
                return;
            }
            reset(java.util.Collections.singletonList(uri), "已选目录，点「开始扫描」。");
            host.show("已选目录：" + uri);
        });
    }

    private void pickFiles() {
        host.pickDocuments("*/*", uris -> {
            if (uris == null || uris.isEmpty()) {
                host.show("没有选择文件。");
                return;
            }
            reset(uris, "已选 " + uris.size() + " 个文件，点「开始扫描」。");
            host.show("已选文件：\n" + join(uris));
        });
    }

    /**
     * 由 Activity 把 Intent / ClipData 送进来的入口。
     *
     * <p>直接开始扫描：调用方（管理器、分享面板）已经把位置交过来了，再让用户在页面上重复一次
     * "确认并开始"就是把人的操作丢回来重做一遍。
     */
    void accept(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        reset(uris, "已接收 " + uris.size() + " 个位置，正在扫描…");
        scan();
    }

    private void reset(List<Uri> uris, String summary) {
        // 换了位置就先把正在跑的那次停掉：旧结果属于旧位置，留着只会让人对着错误的列表下手。
        if (state.cancel != null) {
            state.cancel.cancel();
            state.cancel = null;
            host.setRunning(false);
        }
        state.roots.clear();
        state.roots.addAll(uris);
        state.checked.clear();
        state.nodes.clear();
        state.result = null;
        state.detail = "";
        state.summary = summary;
        if (status != null) {
            status.setText(summary);
        }
        render();
    }

    // ------------------------------------------------------------ 扫描

    private void scan() {
        if (state.roots.isEmpty()) {
            host.show("请先选择目录或文件。");
            return;
        }
        if (state.busy()) {
            host.show("上一次操作还在进行。");
            return;
        }
        Cancel cancel = new Cancel();
        state.cancel = cancel;
        state.checked.clear();
        state.nodes.clear();
        state.result = null;
        state.detail = "";
        state.summary = "正在列出文件…";
        status.setText(state.summary);
        host.setRunning(true);
        host.onCancel(cancel::cancel);
        List<Uri> roots = new ArrayList<>(state.roots);
        host.background(() -> {
            List<ManagerStore.Node> files;
            try {
                files = session.files(roots, cancel, (count, label) -> {
                    if (count % 25 == 0) {
                        host.post(() -> {
                            if (host.alive()) {
                                status.setText("已列出 " + count + " 个文件…");
                            }
                        });
                    }
                });
            } catch (Exception error) {
                finish(cancel, () -> host.fail(error));
                return;
            }
            Map<String, ManagerStore.Node> byId = new LinkedHashMap<>();
            List<DuplicateScan.Item> items = new ArrayList<>();
            for (ManagerStore.Node node : files) {
                String id = node.uri.toString();
                if (byId.put(id, node) != null) {
                    continue; // 同一文件被选两次，只算一次
                }
                items.add(new DuplicateScan.Item(id, FileSession.label(node), node.size, node.modified));
            }
            List<String> skipped = new ArrayList<>(session.skipped());
            host.post(() -> {
                if (host.alive()) {
                    status.setText("已列出 " + files.size() + " 个文件，正在计算摘要…");
                }
            });
            DuplicateScan.Result result = DuplicateScan.scan(items, item -> {
                ManagerStore.Node node = byId.get(item.id);
                if (node == null) {
                    throw new java.io.IOException("文件已不在列表中");
                }
                return session.sha256(node, cancel);
            }, cancel, (done, total, label) -> {
                if (done % 20 == 0 || done == total) {
                    host.post(() -> {
                        if (host.alive()) {
                            status.setText("摘要 " + done + "/" + total + "：" + label);
                        }
                    });
                }
            });
            finish(cancel, () -> {
                if (!host.alive()) {
                    return;
                }
                state.nodes.clear();
                state.nodes.putAll(byId);
                state.result = result;
                state.checked.clear(); // 默认一个都不勾
                state.summary = summaryOf(result);
                state.detail = detailOf(result, skipped);
                status.setText(state.summary);
                render();
                host.show(state.detail);
            });
        });
    }

    /**
     * 统一收尾：清标记、停进度，最后才碰界面。
     *
     * <p>三条顺序上的讲究：
     * <ul>
     *   <li>令牌已经不是当前那个 → 这次结果过期（重建或重扫了），整份丢弃，不覆盖新状态。</li>
     *   <li>页面已销毁 → 不碰任何 View，只留一句重建后能读到的话，不留"正在扫描…"这种假状态。</li>
     *   <li>否则先停进度再更新界面。</li>
     * </ul>
     */
    private void finish(Cancel cancel, Runnable ui) {
        host.post(() -> {
            if (state.cancel != cancel) {
                return;
            }
            state.cancel = null;
            if (!host.alive()) {
                state.summary = "上次的操作已结束，但页面已重建：请重新扫描确认结果。";
                state.detail = "";
                return;
            }
            host.setRunning(false);
            ui.run();
        });
    }

    private static String summaryOf(DuplicateScan.Result result) {
        StringBuilder text = new StringBuilder();
        if (result.cancelled) {
            text.append("已取消：只算了一部分摘要，结果不完整。\n");
        }
        text.append("重复组 ").append(result.groups.size()).append(" 组，涉及 ")
                .append(result.duplicateFiles()).append(" 个文件，可省 ")
                .append(SizeFmt.bytes(result.reclaimable())).append("。");
        return text.toString();
    }

    private static String detailOf(DuplicateScan.Result result, List<String> skipped) {
        StringBuilder text = new StringBuilder(summaryOf(result));
        boolean complete = (skipped == null || skipped.isEmpty()) && result.unreadable.isEmpty();
        if (result.groups.isEmpty() && complete) {
            text.append("\n已比较的 ").append(result.uniqueSize).append(" 个非空文件没有发现重复内容。");
        }
        if (skipped != null && !skipped.isEmpty()) {
            // 没读到的目录必须说出来：否则"没有重复"是基于一份不完整的清单得出的结论。
            text.append("\n\n跳过了 ").append(skipped.size()).append(" 个位置，这些地方没有参与比较：");
            int shown = 0;
            for (String note : skipped) {
                if (shown++ >= 10) {
                    text.append("\n…还有 ").append(skipped.size() - 10).append(" 个");
                    break;
                }
                text.append('\n').append(note);
            }
        }
        if (result.emptySkipped > 0) {
            text.append("\n0 字节文件 ").append(result.emptySkipped).append(" 个不参与比较（删空文件不是清理）。");
        }
        if (!result.unreadable.isEmpty()) {
            text.append("\n\n读取失败 ").append(result.unreadable.size()).append(" 项。")
                    .append("这些文件没有参与比较，所以不能据此说它们不重复：");
            int shown = 0;
            for (DuplicateScan.Item item : result.unreadable) {
                if (shown++ >= 20) {
                    text.append("\n…还有 ").append(result.unreadable.size() - 20).append(" 项");
                    break;
                }
                text.append('\n').append(item.label).append(" — ").append(item.error);
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------ 勾选与渲染

    private void render() {
        if (holder != null) {
            // 重扫时先摘掉旧的勾选区，否则会一层层往上叠。
            host.body().removeView(holder);
            holder.removeAllViews();
            holder = null;
        }
        DuplicateScan.Result result = state.result;
        if (result == null || result.groups.isEmpty()) {
            updateSelectionLabel();
            return;
        }
        LinearLayout list = new LinearLayout(host.activity());
        list.setOrientation(LinearLayout.VERTICAL);
        holder = list;
        int rows = 0;
        int groupIndex = 0;
        for (List<DuplicateScan.Item> group : result.groups) {
            groupIndex++;
            long size = group.get(0).size;
            TextView header = new TextView(host.activity());
            header.setText("第 " + groupIndex + " 组 · " + group.size() + " 份 · 每份 " + SizeFmt.bytes(size)
                    + " · 删到只剩一份可省 " + SizeFmt.bytes(size * (group.size() - 1)));
            header.setTextColor(host.accent());
            header.setTextSize(14);
            header.setPadding(0, host.dp(12), 0, host.dp(4));
            list.addView(header);
            for (DuplicateScan.Item item : group) {
                if (rows >= ROW_LIMIT) {
                    TextView more = new TextView(host.activity());
                    more.setText("…还有 " + (result.duplicateFiles() - rows) + " 项没有铺成勾选框。"
                            + "未铺出的项不会被删除，请用更小的目录分批处理。");
                    more.setTextColor(host.muted());
                    more.setTextSize(13);
                    list.addView(more);
                    break;
                }
                CheckBox box = new CheckBox(host.activity());
                box.setText(item.label + "  " + SizeFmt.bytes(item.size));
                box.setTextColor(host.ink());
                box.setTextSize(14);
                box.setChecked(state.checked.contains(item.id));
                box.setOnCheckedChangeListener((view, checked) -> {
                    if (checked) {
                        state.checked.add(item.id);
                    } else {
                        state.checked.remove(item.id);
                    }
                    updateSelectionLabel();
                });
                list.addView(box, new LinearLayout.LayoutParams(-1, -2));
                rows++;
            }
            if (rows >= ROW_LIMIT) {
                break;
            }
        }
        host.body().addView(list);
        updateSelectionLabel();
    }

    private void updateSelectionLabel() {
        int selected = 0;
        if (state.result != null) {
            for (List<DuplicateScan.Item> group : state.result.groups) {
                selected += DuplicateScan.selectForDeletion(group, state.checked).size();
            }
        }
        String text = "待删除：" + selected + " 项（每组至少留一份）。";
        if (selectionLabel == null) {
            // 还没扫出结果时不铺这一行：空列表上写"待删除 0 项"只会让人以为出了问题。
            if (state.result == null) {
                return;
            }
            selectionLabel = host.note(text);
        } else {
            selectionLabel.setText(text);
        }
    }

    // ------------------------------------------------------------ 删除

    private void confirmDelete() {
        if (!host.alive()) {
            return;
        }
        DuplicateScan.Result result = state.result;
        if (result == null || result.groups.isEmpty()) {
            host.show("还没有扫描结果。");
            return;
        }
        List<DuplicateScan.Item> doomed = new ArrayList<>();
        for (List<DuplicateScan.Item> group : result.groups) {
            doomed.addAll(DuplicateScan.selectForDeletion(group, state.checked));
        }
        if (doomed.isEmpty()) {
            host.show("没有勾选任何项。默认不删：把要删的勾上，每组会自动留一份。");
            return;
        }
        StringBuilder body = new StringBuilder("将删除 ").append(doomed.size())
                .append(" 项，每组保留一份。删除后无法恢复。\n");
        for (int i = 0; i < doomed.size() && i < 12; i++) {
            body.append('\n').append(doomed.get(i).label);
        }
        if (doomed.size() > 12) {
            body.append("\n…还有 ").append(doomed.size() - 12).append(" 项");
        }
        new AlertDialog.Builder(host.activity())
                .setTitle("确认删除 " + doomed.size() + " 项？")
                .setMessage(body.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> delete(doomed))
                .show();
    }

    /** 元数据完全拿不到时，靠重算摘要来核验内容没变；这不算"删前没核验"。 */
    private void delete(List<DuplicateScan.Item> targets) {
        if (state.busy()) {
            host.show("上一次操作还在进行。");
            return;
        }
        Cancel cancel = new Cancel();
        state.cancel = cancel;
        host.setRunning(true);
        host.onCancel(cancel::cancel);
        status.setText("正在删除…");
        List<DuplicateScan.Item> list = new ArrayList<>(targets);
        Map<String, ManagerStore.Node> nodes = new LinkedHashMap<>(state.nodes);
        java.util.Set<String> deleting = new java.util.HashSet<>();
        for (DuplicateScan.Item item : list) deleting.add(item.id);
        Map<String, String> keepers = new LinkedHashMap<>();
        for (List<DuplicateScan.Item> group : state.result.groups) {
            String kept = null;
            for (DuplicateScan.Item item : group) if (!deleting.contains(item.id)) { kept = item.id; break; }
            if (kept != null) for (DuplicateScan.Item item : group) keepers.put(item.id, kept);
        }
        host.background(() -> {
            int deleted = 0;
            List<String> failures = new ArrayList<>();
            for (DuplicateScan.Item item : list) {
                if (cancel.isCancelled()) {
                    failures.add(item.label + " — 已取消，未处理");
                    continue;
                }
                ManagerStore.Node node = nodes.get(item.id);
                if (node == null) {
                    failures.add(item.label + " — 列表已过期，请重新扫描");
                    continue;
                }
                try {
                    // 删前核验：扫描后文件被改过就不删，绝不按旧结论下手。
                    ManagerStore.Node fresh = session.resolve(node.uri);
                    String changed = DuplicateScan.verifyUnchanged(item, fresh.size, fresh.modified);
                    if (changed != null && !DuplicateScan.metadataUnknown(item, fresh.size, fresh.modified)) {
                        failures.add(item.label + " — " + changed + "，已跳过");
                        continue;
                    }
                    ManagerStore.Node kept = nodes.get(keepers.get(item.id));
                    if (kept == null) throw new java.io.IOException("没有可核验的保留副本，已跳过");
                    ManagerStore.Node keptFresh = session.resolve(kept.uri);
                    session.requireDistinctCopies(fresh, keptFresh);
                    String currentHash = session.sha256(fresh, cancel);
                    String keptHash = session.sha256(keptFresh, cancel);
                    if (!DuplicateScan.contentStillDuplicate(item, currentHash, keptHash))
                        throw new java.io.IOException("所选文件或保留副本已变化，已跳过");
                    cancel.check();
                    session.delete(fresh);
                    deleted++;
                } catch (Exception error) {
                    failures.add(item.label + " — " + DuplicateScan.message(error));
                }
            }
            int ok = deleted;
            finish(cancel, () -> {
                StringBuilder text = new StringBuilder("已删除 ").append(ok).append(" 项，未删除 ")
                        .append(failures.size()).append(" 项。");
                for (String failure : failures) {
                    text.append('\n').append(failure);
                }
                text.append("\n请重新扫描以刷新结果。");
                state.result = null;
                state.checked.clear();
                state.nodes.clear();
                state.summary = "已删除 " + ok + " 项，请重新扫描。";
                state.detail = text.toString();
                status.setText(state.summary);
                render();
                host.show(state.detail);
            });
        });
    }

    private static String join(List<Uri> uris) {
        StringBuilder text = new StringBuilder();
        for (Uri uri : uris) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(uri);
        }
        return text.toString();
    }
}
