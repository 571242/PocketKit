package cn.xianhua.pocketkit.proxy;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import cn.xianhua.pocketkit.BaseToolActivity;
import cn.xianhua.pocketkit.ToolUi;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-featured Proxy Center with multi-subscription management and unified node pool.
 */
public final class ProxyActivity extends BaseToolActivity implements ProxyState.StateListener {
    public static boolean supports(String id) { return "P01".equals(id); }
    public static String limitation(String id) { return ""; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProxyProfileManager profileManager;

    private View statusBadge;
    private TextView tvStatusTitle, tvStatusSubtitle;
    private TextView tvOutboundIp, tvTrafficSpeed, tvTrafficTotal;
    private Button btnMainToggle;
    private LinearLayout modeContainer;
    private LinearLayout subsContainer;
    private LinearLayout nodesContainer;
    private TextView tvNodePoolHeader;
    private TextView btnTestDelay;
    private TextView tvRulesStatus;

    private TextView text(String value, int color, float size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        ToolUi.text(v, color, size);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private int tint(int color) {
        float weight = 0.12f;
        return Color.rgb(
                Math.round(Color.red(surface) * (1 - weight) + Color.red(color) * weight),
                Math.round(Color.green(surface) * (1 - weight) + Color.green(color) * weight),
                Math.round(Color.blue(surface) * (1 - weight) + Color.blue(color) * weight)
        );
    }

    @Override
    protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "规则代理");
        super.onCreate(state);

        profileManager = new ProxyProfileManager(this);
        ProxyState.get().init(this);

        initUi();
    }

    private void initUi() {
        body.removeAllViews();
        body.setPadding(0, dp(12), 0, dp(32));

        // 1. Hero Status Card
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        heroCard.setBackground(ToolUi.fill(surface, dp(22), Color.TRANSPARENT, 0));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        statusBadge = new View(this);
        updateDot(ProxyState.get().getStatus());
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        badgeLp.setMargins(0, 0, dp(10), 0);
        topRow.addView(statusBadge, badgeLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        tvStatusTitle = text("规则代理", ink, 18, true);
        titleCol.addView(tvStatusTitle);
        tvStatusSubtitle = text("未连接 · 点击启动", muted, 13, false);
        titleCol.addView(tvStatusSubtitle);
        topRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        btnMainToggle = new Button(this);
        btnMainToggle.setText("启动");
        ToolUi.text(btnMainToggle, Color.WHITE, 14);
        btnMainToggle.setBackground(ToolUi.ripple(this, accent, dp(16), Color.TRANSPARENT, 0));
        btnMainToggle.setPadding(dp(16), dp(8), dp(16), dp(8));
        btnMainToggle.setOnClickListener(v -> toggleConnection());
        topRow.addView(btnMainToggle, new LinearLayout.LayoutParams(-2, dp(44)));
        heroCard.addView(topRow);

        View div = new View(this);
        div.setBackgroundColor(Color.argb(18, 128, 128, 128));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(1));
        divLp.setMargins(0, dp(14), 0, dp(14));
        heroCard.addView(div, divLp);

        // Stats row
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);

        tvOutboundIp = text("出口 IP: " + ProxyState.get().getOutboundIp() + " (点击刷新)", muted, 12, false);
        tvOutboundIp.setOnClickListener(v -> OutboundIpDetector.refresh());
        statsRow.addView(tvOutboundIp, new LinearLayout.LayoutParams(0, -2, 1));

        tvTrafficSpeed = text("↑ 0 B/s  ↓ 0 B/s", accent, 12, true);
        statsRow.addView(tvTrafficSpeed, new LinearLayout.LayoutParams(-2, -2));
        heroCard.addView(statsRow);

        tvTrafficTotal = text("本次运行累计: " + ProxyState.formatBytes(ProxyState.get().getTotalUpload() + ProxyState.get().getTotalDownload()), muted, 12, false);
        tvTrafficTotal.setPadding(0, dp(4), 0, 0);
        heroCard.addView(tvTrafficTotal);

        body.addView(heroCard);

        // 2. Mode Selector
        sectionTitle("分流模式");
        modeContainer = new LinearLayout(this);
        modeContainer.setOrientation(LinearLayout.HORIZONTAL);
        modeContainer.setGravity(Gravity.CENTER_VERTICAL);
        renderModeButtons();
        body.addView(modeContainer);
        TextView ruleHint = text("规则模式：私网直连；精确域名优先于后缀，同级明确代理优先；"
                + "国内域名和 CN 网段直连，其余走代理，DNS 同步分流。全局与直连模式不受云规则影响。", muted, 12, false);
        ruleHint.setPadding(0, dp(8), 0, 0);
        body.addView(ruleHint);
        tvRulesStatus = text("", muted, 11, false);
        tvRulesStatus.setPadding(0, dp(4), 0, 0);
        body.addView(tvRulesStatus);
        renderRulesStatus();

        // 3. Multi-Subscription Section
        LinearLayout subHeaderRow = new LinearLayout(this);
        subHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        subHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        subHeaderRow.setPadding(0, dp(20), 0, dp(8));

        TextView tvSubTitle = text("订阅地址管理", ink, 16, true);
        subHeaderRow.addView(tvSubTitle, new LinearLayout.LayoutParams(0, -2, 1));

        TextView btnAddSub = text("+ 添加订阅", accent, 13, true);
        btnAddSub.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnAddSub.setBackground(ToolUi.ripple(this, Color.argb(18, 34, 100, 220), dp(10), Color.TRANSPARENT, 0));
        btnAddSub.setOnClickListener(v -> showAddSubscriptionDialog());
        subHeaderRow.addView(btnAddSub);

        TextView btnSyncAll = text("全部更新", accent, 13, true);
        btnSyncAll.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnSyncAll.setBackground(ToolUi.ripple(this, Color.argb(18, 34, 100, 220), dp(10), Color.TRANSPARENT, 0));
        btnSyncAll.setOnClickListener(v -> syncAllSubscriptions());
        subHeaderRow.addView(btnSyncAll);
        body.addView(subHeaderRow);

        subsContainer = new LinearLayout(this);
        subsContainer.setOrientation(LinearLayout.VERTICAL);
        renderSubscriptions();
        body.addView(subsContainer);

        // 4. Unified Node Pool Section
        LinearLayout nodeHeaderRow = new LinearLayout(this);
        nodeHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        nodeHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        nodeHeaderRow.setPadding(0, dp(20), 0, dp(8));

        tvNodePoolHeader = text("全部节点池", ink, 16, true);
        nodeHeaderRow.addView(tvNodePoolHeader, new LinearLayout.LayoutParams(0, -2, 1));

        btnTestDelay = text("TCP 连通测试", accent, 13, true);
        btnTestDelay.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnTestDelay.setBackground(ToolUi.ripple(this, Color.argb(20, 34, 100, 220), dp(12), Color.TRANSPARENT, 0));
        btnTestDelay.setOnClickListener(v -> runBatchPing());
        nodeHeaderRow.addView(btnTestDelay);
        body.addView(nodeHeaderRow);

        nodesContainer = new LinearLayout(this);
        nodesContainer.setOrientation(LinearLayout.VERTICAL);
        renderNodes();
        body.addView(nodesContainer);
    }

    /** One line, refreshed with every subscription refresh: no URL, no credentials. */
    private void renderRulesStatus() {
        if (tvRulesStatus == null) return;
        tvRulesStatus.setText(new ProxyRulesStore(this).statusLine());
    }

    private void sectionTitle(String title) {
        TextView tv = text(title, ink, 16, true);
        tv.setPadding(0, dp(20), 0, dp(8));
        body.addView(tv);
    }

    private void renderModeButtons() {
        modeContainer.removeAllViews();
        ProxyState.Mode current = ProxyState.get().getMode();
        for (ProxyState.Mode m : ProxyState.Mode.values()) {
            boolean active = m == current;
            TextView btn = new TextView(this);
            btn.setText(m.label);
            btn.setTextSize(13);
            btn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(active ? Color.WHITE : muted);
            btn.setBackground(ToolUi.fill(active ? accent : surface, dp(14), Color.TRANSPARENT, 0));
            btn.setPadding(dp(16), dp(10), dp(16), dp(10));
            btn.setOnClickListener(v -> {
                ProxyState.get().setMode(m, this);
                renderModeButtons();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(0, 0, dp(8), 0);
            modeContainer.addView(btn, lp);
        }
    }

    private void renderSubscriptions() {
        subsContainer.removeAllViews();
        List<ProxyProfileManager.Subscription> subs = profileManager.getSubscriptions();

        if (subs.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setPadding(dp(16), dp(20), dp(16), dp(20));
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setBackground(ToolUi.fill(surface, dp(16), Color.TRANSPARENT, 0));

            TextView tip = text("暂无订阅地址，请点击右上角「+ 添加订阅」", muted, 13, false);
            emptyCard.addView(tip);
            subsContainer.addView(emptyCard);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (ProxyProfileManager.Subscription s : subs) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            item.setBackground(ToolUi.fill(surface, dp(16), Color.TRANSPARENT, 0));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = text(s.name + " (" + s.nodeCount + " 个节点)", ink, 14, true);
            infoCol.addView(nameTv);

            String timeStr = s.lastUpdated > 0 ? "更新于: " + sdf.format(new Date(s.lastUpdated)) : "尚未拉取";
            TextView urlTv = text(timeStr + " · " + s.url, muted, 11, false);
            urlTv.setSingleLine(true);
            infoCol.addView(urlTv);

            item.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));

            // Refresh Single Sub Button
            TextView btnRefresh = text("刷新", accent, 12, true);
            btnRefresh.setPadding(dp(10), dp(6), dp(10), dp(6));
            btnRefresh.setOnClickListener(v -> syncSingleSubscription(s));
            item.addView(btnRefresh);

            // Delete Sub Button
            TextView btnDel = text("删除", Color.parseColor("#EF4444"), 12, false);
            btnDel.setPadding(dp(10), dp(6), dp(10), dp(6));
            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除订阅")
                        .setMessage("确定要删除订阅「" + s.name + "」及其全部节点吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            boolean removedSelection = false;
                            for (ProxyProfileManager.Node node : profileManager.getNodes()) {
                                if (s.id.equals(node.subId) && node.name.equals(ProxyState.get().getSelectedProxy())) removedSelection = true;
                            }
                            profileManager.removeSubscription(s.id);
                            reconcileSelection(removedSelection);
                            renderSubscriptions();
                            renderNodes();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            item.addView(btnDel);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(8));
            subsContainer.addView(item, lp);
        }
    }

    private void renderNodes() {
        nodesContainer.removeAllViews();
        List<ProxyProfileManager.Node> nodes = profileManager.getNodes();
        String selected = ProxyState.get().getSelectedProxy();

        tvNodePoolHeader.setText("全部节点池 (" + nodes.size() + ")");

        if (nodes.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setPadding(dp(16), dp(24), dp(16), dp(24));
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setBackground(ToolUi.fill(surface, dp(16), Color.TRANSPARENT, 0));

            TextView tip = text("节点池为空。添加并更新订阅后，所有节点会汇总在此处。", muted, 13, false);
            emptyCard.addView(tip);
            nodesContainer.addView(emptyCard);
            return;
        }

        for (ProxyProfileManager.Node node : nodes) {
            boolean isSelected = node.name.equals(selected);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), dp(13), dp(16), dp(13));
            item.setBackground(ToolUi.ripple(this, isSelected ? tint(accent) : surface, dp(16), isSelected ? accent : Color.TRANSPARENT, isSelected ? dp(1) : 0));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = text(node.name, isSelected ? accent : ink, 14, isSelected);
            infoCol.addView(nameTv);

            String origin = (node.subName != null && !node.subName.isEmpty()) ? "[" + node.subName + "] " : "";
            TextView typeTv = text(node.usable()
                    ? origin + node.type.toUpperCase() + (node.server.isEmpty() ? "" : " · " + node.server)
                    : "不可用 · " + (node.rejectReason == null ? "请重新同步订阅" : node.rejectReason), muted, 11, false);
            infoCol.addView(typeTv);

            item.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));

            // Delay Text
            TextView delayTv = new TextView(this);
            delayTv.setTextSize(12);
            if (node.delayMs > 0) {
                delayTv.setText(node.delayMs + " ms");
                if (node.delayMs < 150) {
                    delayTv.setTextColor(Color.parseColor("#10B981"));
                } else if (node.delayMs < 300) {
                    delayTv.setTextColor(Color.parseColor("#F59E0B"));
                } else {
                    delayTv.setTextColor(Color.parseColor("#EF4444"));
                }
            } else if (node.delayMs == -2) {
                delayTv.setText("连接失败");
                delayTv.setTextColor(Color.parseColor("#EF4444"));
            } else if (node.delayMs == 0) {
                delayTv.setText("测试中...");
                delayTv.setTextColor(accent);
            } else {
                delayTv.setText("未测试");
                delayTv.setTextColor(muted);
            }
            item.addView(delayTv);

            item.setOnClickListener(v -> {
                if (!node.usable()) {
                    Toast.makeText(this, node.rejectReason == null ? "请重新同步订阅" : node.rejectReason, Toast.LENGTH_LONG).show();
                    return;
                }
                ProxyState.get().setSelectedProxy(node.name, this);
                renderNodes();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dp(8));
            nodesContainer.addView(item, lp);
        }
    }

    private void showAddSubscriptionDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(14), dp(20), dp(10));

        EditText etName = new EditText(this);
        etName.setHint("订阅名称 (例如: 主力机场)");
        etName.setTextColor(ink);
        etName.setTextSize(14);
        layout.addView(etName);

        EditText etUrl = new EditText(this);
        etUrl.setHint("订阅地址 URL (http/https)");
        etUrl.setTextColor(ink);
        etUrl.setTextSize(14);
        etUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, -2);
        urlLp.setMargins(0, dp(10), 0, 0);
        layout.addView(etUrl, urlLp);

        new AlertDialog.Builder(this)
                .setTitle("添加新订阅")
                .setView(layout)
                .setPositiveButton("添加并拉取", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "订阅链接不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    profileManager.addSubscription(name, url);
                    renderSubscriptions();
                    // Auto-fetch this new sub
                    List<ProxyProfileManager.Subscription> list = profileManager.getSubscriptions();
                    if (!list.isEmpty()) {
                        syncSingleSubscription(list.get(list.size() - 1));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncSingleSubscription(ProxyProfileManager.Subscription sub) {
        Toast.makeText(this, "正在拉取「" + sub.name + "」...", Toast.LENGTH_SHORT).show();
        workers.execute(() -> {
            boolean ok = profileManager.fetchAndParseSubscription(sub);
            handler.post(() -> {
                if (ok) {
                    Toast.makeText(this, "「" + sub.name + "」拉取成功 (" + sub.nodeCount + " 个节点)", Toast.LENGTH_SHORT).show();
                    if ("未选择节点".equals(ProxyState.get().getSelectedProxy())) {
                        for (ProxyProfileManager.Node node : profileManager.getNodes()) {
                            if (node.usable()) { ProxyState.get().setSelectedProxy(node.name, this); break; }
                        }
                    }
                    PocketVpnService.reload(this);
                } else {
                    String error = profileManager.getLastError();
                    Toast.makeText(this, error == null ? "订阅更新失败，已保留原节点" : error, Toast.LENGTH_LONG).show();
                }
                renderSubscriptions();
                renderNodes();
                renderRulesStatus();
            });
        });
    }

    private void syncAllSubscriptions() {
        List<ProxyProfileManager.Subscription> subs = profileManager.getSubscriptions();
        if (subs.isEmpty()) {
            Toast.makeText(this, "暂无订阅，请先点击「+ 添加订阅」", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在全量拉取 " + subs.size() + " 个订阅...", Toast.LENGTH_SHORT).show();
        workers.execute(() -> {
            int successCount = 0;
            for (ProxyProfileManager.Subscription s : subs) {
                if (profileManager.fetchAndParseSubscription(s)) {
                    successCount++;
                }
            }
            int finalSuccess = successCount;
            handler.post(() -> {
                Toast.makeText(this, "全量更新完成: 成功 " + finalSuccess + " / " + subs.size(), Toast.LENGTH_SHORT).show();
                renderSubscriptions();
                renderNodes();
                renderRulesStatus();
                if (finalSuccess > 0) PocketVpnService.reload(this);
            });
        });
    }

    private void runBatchPing() {
        List<ProxyProfileManager.Node> nodes = profileManager.getNodes();
        if (nodes.isEmpty()) {
            Toast.makeText(this, "暂无节点可测速", Toast.LENGTH_SHORT).show();
            return;
        }

        btnTestDelay.setEnabled(false);
        btnTestDelay.setText("测速中...");
        for (ProxyProfileManager.Node n : nodes) {
            n.delayMs = 0;
        }
        renderNodes();

        workers.execute(() -> {
            for (ProxyProfileManager.Node n : nodes) {
                long start = System.currentTimeMillis();
                try {
                    if (n.server == null || n.server.isEmpty() || n.port < 1 || n.port > 65535) {
                        n.delayMs = -2;
                    } else try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(n.server, n.port), 1200);
                        n.delayMs = (int) Math.max(1, System.currentTimeMillis() - start);
                    }
                } catch (Exception e) {
                    n.delayMs = -2;
                }
                profileManager.updateNodeDelay(n.name, n.delayMs);
            }
            handler.post(() -> {
                btnTestDelay.setEnabled(true);
                btnTestDelay.setText("TCP 连通测试");
                renderNodes();
            });
        });
    }

    private void reconcileSelection(boolean removedSelection) {
        ProxyState state = ProxyState.get();
        List<ProxyProfileManager.Node> remaining = profileManager.getNodes();
        boolean present = false;
        for (ProxyProfileManager.Node node : remaining) if (node.name.equals(state.getSelectedProxy())) present = true;
        if (present && !removedSelection) return;
        if (state.getMode() != ProxyState.Mode.DIRECT
                && (state.getStatus() == ProxyState.Status.CONNECTED || state.getStatus() == ProxyState.Status.CONNECTING))
            PocketVpnService.stop(this);
        String next = remaining.isEmpty() ? "未选择节点" : remaining.get(0).name;
        if (!next.equals(state.getSelectedProxy())) state.restoreSelection(state.getMode(), next, this);
    }

    private void toggleConnection() {
        ProxyState.Status status = ProxyState.get().getStatus();
        if (status == ProxyState.Status.DISCONNECTING) return;
        if (status == ProxyState.Status.CONNECTED || status == ProxyState.Status.CONNECTING) {
            PocketVpnService.stop(this);
        } else {
            Intent vpnPrepareIntent = VpnService.prepare(this);
            if (vpnPrepareIntent != null) {
                startActivityForResult(vpnPrepareIntent, 1024);
            } else {
                PocketVpnService.start(this);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1024 && resultCode == RESULT_OK) {
            PocketVpnService.start(this);
        }
    }

    private void updateDot(ProxyState.Status status) {
        int color;
        switch (status) {
            case CONNECTED:
                color = Color.parseColor("#10B981");
                break;
            case CONNECTING:
            case DISCONNECTING:
                color = Color.parseColor("#F59E0B");
                break;
            case DISCONNECTED:
            default:
                color = Color.parseColor("#9CA3AF");
                break;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        statusBadge.setBackground(shape);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reconcileSelection(false);
        ProxyState.get().addListener(this);
        if (ProxyState.get().getStatus() == ProxyState.Status.CONNECTED) OutboundIpDetector.refresh();
        renderRulesStatus();
    }

    @Override
    protected void onStop() {
        ProxyState.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onStatusChanged(ProxyState.Status status) {
        handler.post(() -> {
            updateDot(status);
            btnMainToggle.setEnabled(status != ProxyState.Status.DISCONNECTING);
            switch (status) {
                case CONNECTED:
                    tvStatusSubtitle.setText("已连接 · " + ProxyState.get().getSelectedProxy());
                    btnMainToggle.setText("断开");
                    btnMainToggle.setBackground(ToolUi.ripple(this, Color.parseColor("#EF4444"), dp(16), Color.TRANSPARENT, 0));
                    break;
                case CONNECTING:
                    tvStatusSubtitle.setText("正在建立安全隧道...");
                    btnMainToggle.setText("取消连接");
                    break;
                case DISCONNECTING:
                    tvStatusSubtitle.setText("正在断开...");
                    btnMainToggle.setText("断开中");
                    break;
                case DISCONNECTED:
                default:
                    tvStatusSubtitle.setText(ProxyState.get().getLastError().isEmpty() ? "未连接 · 点击启动" : ProxyState.get().getLastError());
                    btnMainToggle.setText("启动");
                    btnMainToggle.setBackground(ToolUi.ripple(this, accent, dp(16), Color.TRANSPARENT, 0));
                    break;
            }
        });
    }

    @Override
    public void onTrafficUpdated(long uploadSpeedBps, long downloadSpeedBps, long totalUpload, long totalDownload) {
        handler.post(() -> {
            tvTrafficSpeed.setText("↑ " + ProxyState.formatSpeed(uploadSpeedBps) + "  ↓ " + ProxyState.formatSpeed(downloadSpeedBps));
            tvTrafficTotal.setText("本次运行累计: " + ProxyState.formatBytes(totalUpload + totalDownload));
        });
    }

    @Override
    public void onOutboundIpUpdated(String ip, String location) {
        handler.post(() -> {
            if (location.isEmpty()) {
                tvOutboundIp.setText("出口 IP: " + ip);
            } else {
                tvOutboundIp.setText("出口 IP: " + ip + " (" + location + ")");
            }
        });
    }

    @Override
    public void onModeChanged(ProxyState.Mode mode) {
        handler.post(this::renderModeButtons);
    }

    @Override
    public void onSelectedProxyChanged(String proxyName) {
        handler.post(this::renderNodes);
    }
}
