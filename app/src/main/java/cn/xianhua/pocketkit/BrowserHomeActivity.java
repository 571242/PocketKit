package cn.xianhua.pocketkit;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import cn.xianhua.pocketkit.browser.BrowserVault;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import org.json.JSONObject;

/** No WebView lives here: files are archived only after the isolated browser process releases its lease. */
public final class BrowserHomeActivity extends BaseToolActivity {
    private BrowserVault vault;
    private boolean busy;
    private TextView message;

    public static boolean supports(String id) { return "N02".equals(id) && Build.VERSION.SDK_INT >= 28; }
    public static String limitation(String id) {
        return Build.VERSION.SDK_INT < 28 ? "需要 Android 9 及以上" : "";
    }
    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra("title", "无痕浏览").putExtra("tool_id", "N02");
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setSaveFromParentEnabled(false);
        try { vault = new BrowserVault(this); } catch (Exception ex) { fail(ex); }
    }
    @Override protected void onResume() { super.onResume(); if (vault != null && !busy) refresh(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }

    private void refresh() {
        if (busy) return;
        busy = true;
        body.removeAllViews();
        message = note("正在检查会话…");
        final boolean returning = getIntent().getBooleanExtra("returning", false);
        getIntent().removeExtra("returning");
        workers.execute(() -> {
            String notice = "";
            boolean active = false;
            Exception failure = null;
            List<JSONObject> entries = java.util.Collections.emptyList();
            try {
                BrowserVault.Lease lease = vault.tryLease();
                if (lease == null && returning) {
                    long until = android.os.SystemClock.elapsedRealtime() + 10000;
                    while (lease == null && android.os.SystemClock.elapsedRealtime() < until) {
                        Thread.sleep(100);
                        lease = vault.tryLease();
                    }
                }
                if (lease == null) active = true;
                else try (BrowserVault.Lease held = lease) { notice = vault.settle(); }
                entries = vault.list();
            } catch (Exception ex) { failure = ex; }
            final String text = notice;
            final boolean running = active;
            final Exception error = failure;
            final List<JSONObject> rows = entries;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                busy = false;
                render(text, running, error, rows);
            });
        });
    }

    private void render(String notice, boolean running, Exception failure, List<JSONObject> entries) {
        body.removeAllViews();
        note("退出即清除；要下次继续就点「冻结并退出」。");
        message = note(notice.isEmpty() ? "无痕浏览 · 退出即清除" : notice);
        if (Build.VERSION.SDK_INT < 28) { message.setText(limitation("N02")); return; }
        if (failure != null) {
            message.setText("处理未完成：" + failure.getMessage() + "；可重试或清除暂存。");
            button("重试处理", this::refresh);
            button("清除暂存会话", () -> new AlertDialog.Builder(this).setTitle("放弃未完成的冻结？")
                    .setMessage("将删除当前未完成的暂存会话，无法撤销。")
                    .setPositiveButton("清除", (d, w) -> discard()).setNegativeButton("取消", null).show());
        } else if (running) {
            message.setText("当前无痕窗口正在运行，请先返回处理。");
            button("返回当前窗口", () -> {
                try { launch(vault.currentToken()); } catch (Exception ex) { message.setText(ex.getMessage()); }
            });
            button("重新检查", this::refresh);
        } else button("开始新无痕会话", () -> open(null));
        note("长期冻结窗口 · 点击解冻，长按删除");
        if (entries.isEmpty()) note("暂无冻结窗口；点「冻结」可保存登录状态与浏览进度。");
        for (JSONObject entry : entries) {
            String id = entry.optString("frozenId");
            String label = entry.optString("title", "未命名窗口");
            if (entry.optLong("savedAt") > 0) label += "\n" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.optLong("savedAt")));
            android.widget.Button row = button(label, () -> {
                if (running || failure != null) { message.setText("请先处理当前会话，再解冻其他窗口"); return; }
                if (entry.optBoolean("broken")) { message.setText("文件损坏或密钥不可用，无法恢复。"); return; }
                open(id);
            });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("删除冻结窗口？")
                        .setMessage("将删除本机保存的登录状态，无法撤销。")
                        .setPositiveButton("删除", (d, w) -> {
                            if (busy) return;
                            busy = true;
                            workers.execute(() -> {
                                Exception error = null;
                                try { vault.delete(id); } catch (Exception ex) { error = ex; }
                                final Exception result = error;
                                runOnUiThread(() -> { busy = false; if (result != null) message.setText("删除失败：" + result.getMessage()); else refresh(); });
                            });
                        }).setNegativeButton("取消", null).show();
                return true;
            });
        }
        button("隐私与冻结边界", () -> new AlertDialog.Builder(this).setTitle("无痕浏览")
                .setMessage("浏览记录只留在本机，退出即清除；冻结会把登录状态加密存在手机上，下次点开继续。")
                .setPositiveButton("知道了", null).show());
    }

    private void open(String id) {
        if (busy) return;
        busy = true;
        message.setText(id == null ? "正在创建独立会话…" : "正在解密窗口…");
        workers.execute(() -> {
            try {
                final String token;
                try (BrowserVault.Lease lease = vault.tryLease()) {
                    if (lease == null) throw new IllegalStateException("当前窗口仍在使用，不能覆盖它的网页数据");
                    token = vault.prepare(id).getString("token");
                }
                // Release the coordinator lease before the child process tries to acquire it.
                runOnUiThread(() -> {
                    busy = false;
                    if (isDestroyed() || isFinishing()) return;
                    launch(token);
                });
            } catch (Exception ex) { runOnUiThread(() -> { busy = false; message.setText("打开失败：" + ex.getMessage() + "\n原冻结副本保留。可点重新检查后重试。"); button("重新检查", this::refresh); }); }
        });
    }
    private void launch(String token) {
        startActivity(new Intent(this, PrivateBrowserActivity.class).putExtra("session_token", token));
    }
    private void discard() {
        if (busy) return;
        busy = true;
        workers.execute(() -> {
            try (BrowserVault.Lease lease = vault.tryLease()) {
                if (lease == null) throw new IllegalStateException("网页仍在运行，未清除");
                vault.discardPending();
                runOnUiThread(() -> { busy = false; refresh(); });
            } catch (Exception ex) { runOnUiThread(() -> { busy = false; message.setText(ex.getMessage()); }); }
        });
    }
}
