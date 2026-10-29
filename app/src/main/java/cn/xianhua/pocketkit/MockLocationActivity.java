package cn.xianhua.pocketkit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import cn.xianhua.pocketkit.mocklocation.MockLocationBridge;

/**
 * L01 修改定位 / L02 模拟足迹。
 *
 * 为什么用 WebView 而不是原生表单：选点天然是地图交互，用原生控件自己画一遍等于重写
 * 一个地图。这里复用系统 WebView + 高德栅格瓦片（不引入任何第三方地图 SDK），
 * 原生只负责「注入坐标」这件事，边界清楚。
 *
 * 两个工具共用这一页：固定模式只允许一个点，足迹模式按顺序连成路线。
 */
public final class MockLocationActivity extends Activity {

    public static boolean supports(String id) {
        return "L01".equals(id) || "L02".equals(id);
    }

    public static String limitation(String id) {
        if (!"L01".equals(id) && !"L02".equals(id)) {
            return "";
        }
        return "需要先在系统「开发者选项 → 选择模拟位置信息应用」里选上「拾用」；"
                + "模拟位置对所有应用生效并被标记为 mock，银行、打车、考勤类应用可能直接拒绝。"
                + "本工具尚未真机验收。";
    }

    private int ink;
    private int base;
    private WebView web;
    private MockLocationBridge bridge;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E7EEFC" : "#172D4D");
        int accent = Color.parseColor(dark ? "#9BBCFF" : "#2264DC");
        base = Color.parseColor(dark ? "#101B2B" : "#F3F6FB");

        String toolId = getIntent().getStringExtra("tool_id");
        if (toolId == null) {
            toolId = "L02";
        }
        String mode = getIntent().getStringExtra("mode");
        if (mode == null) {
            mode = "L01".equals(toolId) ? "fixed" : "footprint";
        }
        String title = getIntent().getStringExtra("title");
        if (title == null || title.trim().isEmpty()) {
            title = "footprint".equals(mode) ? "模拟足迹" : "修改定位";
        }
        // 上一次会话如果被系统强杀，测试 Provider 会留在系统里，全机定位就停在假点。
        // 每次打开页面先扫一遍（有活会话时它自己会跳过）。
        cn.xianhua.pocketkit.mocklocation.MockLocationSession.sweepStale(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(base);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12) + left, top, dp(12) + right, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("返回");
        ToolUi.text(back, accent, 15);
        ToolUi.touch(back, dp(48));
        back.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(72), dp(52)));
        TextView heading = new TextView(this);
        heading.setText(title);
        ToolUi.text(heading, ink, 21);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        boolean footprint = "footprint".equals(mode);
        Button switchMode = new Button(this);
        switchMode.setText(footprint ? "修改定位" : "模拟足迹");
        ToolUi.text(switchMode, accent, 14);
        ToolUi.touch(switchMode, dp(48));
        switchMode.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(12), Color.TRANSPARENT, 0));
        switchMode.setOnClickListener(v -> {
            Intent next = new Intent(this, MockLocationActivity.class);
            next.putExtra("tool_id", footprint ? "L01" : "L02");
            startActivity(next);
            finish();
        });
        header.addView(switchMode, new LinearLayout.LayoutParams(dp(100), dp(52)));
        root.addView(header);

        web = new WebView(this);
        web.setBackgroundColor(base);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        // 页面内容全部来自本地 assets，只通过网络加载瓦片图片和地点搜索。
        settings.setBlockNetworkLoads(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 页面自己会跟随系统深浅色（见 mock_location.html 的 dark 类），这里只处理
        // Android 13+ 的「按系统主题自动变暗」，避免系统再对已经深色的页面二次反色。
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                // 页面只加载本地 assets；任何外部跳转都不该把这个 WebView 带走。
                android.net.Uri uri = request.getUrl();
                return uri == null || !"file".equals(uri.getScheme());
            }
        });
        bridge = new MockLocationBridge(this, web);
        web.addJavascriptInterface(bridge, "ToolboxMock");
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
        root.requestApplyInsets();
        web.loadUrl("file:///android_asset/mock_location.html?mode=" + mode + (dark ? "&dark=1" : ""));

        // 前台服务的通知在 Android 13+ 需要用户授权；不给也能跑，但通知栏里的
        // 「暂停 / 停止」就没了。只在第一次进页面时问一次，避免每次进来都弹。
        android.content.SharedPreferences prefs = getSharedPreferences("mock_location", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33
                && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 81);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        // 用户可能刚从开发者选项回来：让页面重新读一次权限状态。
        if (web != null) {
            web.evaluateJavascript("window.MockUI&&MockUI.refreshPermission()", null);
        }
    }

    @Override protected void onDestroy() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        if (web != null) {
            web.removeJavascriptInterface("ToolboxMock");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
