package cn.xianhua.pocketkit.proxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import cn.xianhua.pocketkit.ToolUi;

/**
 * Compact quick-control card embedded in MainActivity home page.
 */
public final class ProxyQuickPanel extends LinearLayout implements ProxyState.StateListener {
    public static final int REQ_VPN_PERMISSION = 2048;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int ink, muted, surface, accent;

    private View statusDot;
    private TextView statusText;
    private TextView ipText;
    private Switch toggleSwitch;
    private TextView btnRule, btnGlobal, btnDirect;
    private TextView speedText;
    private TextView totalText;

    private boolean isUpdatingUi = false;

    public ProxyQuickPanel(Activity activity, int ink, int muted, int surface, int accent) {
        super(activity);
        this.activity = activity;
        this.ink = ink;
        this.muted = muted;
        this.surface = surface;
        this.accent = accent;
        initView();
    }

    private void initView() {
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(14));
        setBackground(ToolUi.fill(surface, dp(20), Color.TRANSPARENT, 0));
        setFocusable(true);

        // Header Row: Status Indicator + IP + Switch
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new View(activity);
        updateDot(ProxyState.get().getStatus());
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMargins(0, 0, dp(8), 0);
        headerRow.addView(statusDot, dotLp);

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(VERTICAL);
        statusText = new TextView(activity);
        statusText.setTextColor(ink);
        statusText.setTextSize(15);
        statusText.setTypeface(null, Typeface.BOLD);
        statusText.setText("规则代理 · 未连接");
        titleCol.addView(statusText);

        ipText = new TextView(activity);
        ipText.setTextColor(muted);
        ipText.setTextSize(12);
        ipText.setText("出口 IP: 未检测 (点击检测)");
        ipText.setOnClickListener(v -> OutboundIpDetector.refresh());
        titleCol.addView(ipText);
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        toggleSwitch = new Switch(activity);
        toggleSwitch.setChecked(ProxyState.get().getStatus() == ProxyState.Status.CONNECTED);
        toggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingUi) return;
            handleToggle(isChecked);
        });
        headerRow.addView(toggleSwitch, new LinearLayout.LayoutParams(-2, -2));
        addView(headerRow);

        // Divider
        View div = new View(activity);
        div.setBackgroundColor(Color.argb(20, 128, 128, 128));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(1));
        divLp.setMargins(0, dp(10), 0, dp(10));
        addView(div, divLp);

        // Mode Row + Traffic Info
        LinearLayout middleRow = new LinearLayout(activity);
        middleRow.setOrientation(HORIZONTAL);
        middleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout modePill = new LinearLayout(activity);
        modePill.setOrientation(HORIZONTAL);
        modePill.setBackground(ToolUi.fill(Color.argb(24, 128, 128, 128), dp(12), Color.TRANSPARENT, 0));
        modePill.setPadding(dp(2), dp(2), dp(2), dp(2));

        btnRule = createModeButton("规则", ProxyState.Mode.RULE);
        btnGlobal = createModeButton("全局", ProxyState.Mode.GLOBAL);
        btnDirect = createModeButton("直连", ProxyState.Mode.DIRECT);

        modePill.addView(btnRule);
        modePill.addView(btnGlobal);
        modePill.addView(btnDirect);
        middleRow.addView(modePill);

        View spacer = new View(activity);
        middleRow.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));

        // Speed & Data
        LinearLayout statsCol = new LinearLayout(activity);
        statsCol.setOrientation(VERTICAL);
        statsCol.setGravity(Gravity.END);

        speedText = new TextView(activity);
        speedText.setTextColor(accent);
        speedText.setTextSize(12);
        speedText.setTypeface(null, Typeface.BOLD);
        speedText.setText("↑ 0 B/s  ↓ 0 B/s");
        statsCol.addView(speedText);

        totalText = new TextView(activity);
        totalText.setTextColor(muted);
        totalText.setTextSize(11);
        totalText.setText("本次运行: 0 MB · 点击管理 >");
        statsCol.addView(totalText);

        middleRow.addView(statsCol);
        addView(middleRow);

        // Open detail activity on click
        setOnClickListener(v -> {
            Intent intent = new Intent(activity, ProxyActivity.class);
            activity.startActivity(intent);
        });

        updateModeButtons(ProxyState.get().getMode());
    }

    private TextView createModeButton(String text, ProxyState.Mode targetMode) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> {
            ProxyState.get().setMode(targetMode, activity);
        });
        return tv;
    }

    private void updateModeButtons(ProxyState.Mode current) {
        btnRule.setBackground(current == ProxyState.Mode.RULE ? ToolUi.fill(accent, dp(10), Color.TRANSPARENT, 0) : null);
        btnRule.setTextColor(current == ProxyState.Mode.RULE ? Color.WHITE : muted);

        btnGlobal.setBackground(current == ProxyState.Mode.GLOBAL ? ToolUi.fill(accent, dp(10), Color.TRANSPARENT, 0) : null);
        btnGlobal.setTextColor(current == ProxyState.Mode.GLOBAL ? Color.WHITE : muted);

        btnDirect.setBackground(current == ProxyState.Mode.DIRECT ? ToolUi.fill(accent, dp(10), Color.TRANSPARENT, 0) : null);
        btnDirect.setTextColor(current == ProxyState.Mode.DIRECT ? Color.WHITE : muted);
    }

    private void updateDot(ProxyState.Status status) {
        int color;
        switch (status) {
            case CONNECTED:
                color = Color.parseColor("#10B981"); // emerald green
                break;
            case CONNECTING:
            case DISCONNECTING:
                color = Color.parseColor("#F59E0B"); // amber
                break;
            case DISCONNECTED:
            default:
                color = Color.parseColor("#9CA3AF"); // neutral gray
                break;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        statusDot.setBackground(shape);
    }

    private void handleToggle(boolean isChecked) {
        if (isChecked) {
            Intent vpnPrepareIntent = VpnService.prepare(activity);
            if (vpnPrepareIntent != null) {
                activity.startActivityForResult(vpnPrepareIntent, REQ_VPN_PERMISSION);
            } else {
                PocketVpnService.start(activity);
            }
        } else {
            PocketVpnService.stop(activity);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ProxyState.get().addListener(this);
        if (ProxyState.get().getStatus() == ProxyState.Status.CONNECTED
                && ("未检测".equals(ProxyState.get().getOutboundIp()) || "未连接".equals(ProxyState.get().getOutboundIp()))) {
            OutboundIpDetector.refresh();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ProxyState.get().removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onStatusChanged(ProxyState.Status status) {
        handler.post(() -> {
            isUpdatingUi = true;
            toggleSwitch.setEnabled(status != ProxyState.Status.DISCONNECTING);
            updateDot(status);
            switch (status) {
                case CONNECTED:
                    statusText.setText("规则代理 · 已开启 (" + ProxyState.get().getSelectedProxy() + ")");
                    toggleSwitch.setChecked(true);
                    break;
                case CONNECTING:
                    statusText.setText("规则代理 · 正在连接...");
                    toggleSwitch.setChecked(true);
                    break;
                case DISCONNECTING:
                    statusText.setText("规则代理 · 正在断开...");
                    toggleSwitch.setChecked(false);
                    break;
                case DISCONNECTED:
                default:
                    statusText.setText(ProxyState.get().getLastError().isEmpty() ? "规则代理 · 未连接" : ProxyState.get().getLastError());
                    toggleSwitch.setChecked(false);
                    break;
            }
            isUpdatingUi = false;
        });
    }

    @Override
    public void onTrafficUpdated(long uploadSpeedBps, long downloadSpeedBps, long totalUpload, long totalDownload) {
        handler.post(() -> {
            speedText.setText("↑ " + ProxyState.formatSpeed(uploadSpeedBps) + "  ↓ " + ProxyState.formatSpeed(downloadSpeedBps));
            totalText.setText("本次运行: " + ProxyState.formatBytes(totalUpload + totalDownload) + " · 节点管理 >");
        });
    }

    @Override
    public void onOutboundIpUpdated(String ip, String location) {
        handler.post(() -> {
            if (location.isEmpty()) {
                ipText.setText("出口 IP: " + ip);
            } else {
                ipText.setText("出口 IP: " + ip + " (" + location + ")");
            }
        });
    }

    @Override
    public void onModeChanged(ProxyState.Mode mode) {
        handler.post(() -> updateModeButtons(mode));
    }

    @Override
    public void onSelectedProxyChanged(String proxyName) {
        handler.post(() -> {
            if (ProxyState.get().getStatus() == ProxyState.Status.CONNECTED) {
                statusText.setText("规则代理 · 已开启 (" + proxyName + ")");
            }
        });
    }

    private int dp(float value) {
        return ToolUi.dp(activity, value);
    }
}
