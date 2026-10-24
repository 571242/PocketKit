package cn.xianhua.pocketkit.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import cn.xianhua.pocketkit.proxy.PocketVpnService;
import cn.xianhua.pocketkit.proxy.ProxyProfileManager;
import cn.xianhua.pocketkit.proxy.ProxyRules;
import cn.xianhua.pocketkit.proxy.ProxyRulesStore;
import cn.xianhua.pocketkit.proxy.ProxyState;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Syncs the cloud proxy rules (and the hub's own subscription) from the author's host.
 *
 * <p>APK updates no longer travel through this host — see {@link RepositoryUpdater} and
 * {@link UpdateSheet}; the small free host only carries the rule bundle now.
 */
public final class UpdateManager {
    public static final String DEFAULT_HUB_URL = "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/api/check-update";
    private static final String PREF_UPDATE = "pocketkit_cloud_update";
    private static final String KEY_CONFIG_VERSION = "local_proxy_config_version";
    private static final UpdateManager INSTANCE = new UpdateManager();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final AtomicBoolean checking = new AtomicBoolean();
    public interface UpdateCallback {
        void onCheckFinished(boolean hasConfigUpdate, String message);
    }
    private UpdateManager() { }
    public static UpdateManager get() { return INSTANCE; }

    /**
     * Manual/timed rule and subscription sync. Never looks for an APK: the release channel is the
     * user's chosen repository, so this host only answers for proxy rules and config.
     */
    public void check(Context context, boolean manual, UpdateCallback callback) {
        if (!checking.compareAndSet(false, true)) {
            report(context, manual, callback, false, "正在检查更新，请稍候");
            return;
        }
        worker.execute(() -> {
            try {
                JSONObject payload = new JSONObject(ProxyProfileManager.fetchHttpContent(DEFAULT_HUB_URL));
                boolean configSynced = syncNodes(context, payload);
                boolean rulesSynced = syncRules(context, payload);
                // One reload for both: nodes and rules share the same live tunnel.
                if (configSynced || rulesSynced) {
                    main.post(() -> {
                        if (configSynced || ProxyState.get().getMode() == ProxyState.Mode.RULE) {
                            PocketVpnService.reload(context.getApplicationContext());
                        }
                    });
                }
                String result = "分流规则已是最新";
                if (configSynced) result = "订阅已同步";
                else if (hasPendingNodeSync(context, payload)) result = "订阅同步未成功，已保留原配置";
                if (rulesSynced) result += "，分流规则已更新";
                else if (!new ProxyRulesStore(context).getLastError().isEmpty()) {
                    result += "；规则同步未成功，继续使用原规则";
                }
                String message = result;
                main.post(() -> {
                    if (!alive(context)) return;
                    if (manual) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    if (callback != null) callback.onCheckFinished(configSynced || rulesSynced, message);
                });
            } catch (Exception error) {
                // URLs and native/parser messages can contain subscription credentials.
                report(context, manual, callback, false, "检查更新失败，请检查网络后重试");
            } finally { checking.set(false); }
        });
    }

    /**
     * Silent cloud-rule check, used by the 15-minute VPN timer. Never shows a dialog or a toast and
     * never touches the node selection: a failure only leaves a status line behind.
     */
    public void checkRules(Context context) {
        if (!checking.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                JSONObject payload = new JSONObject(ProxyProfileManager.fetchHttpContent(DEFAULT_HUB_URL));
                boolean synced = syncRules(context, payload);
                if (synced) main.post(() -> {
                    if (ProxyState.get().getMode() == ProxyState.Mode.RULE) {
                        PocketVpnService.reload(context.getApplicationContext());
                    }
                });
            } catch (Exception ignored) {
                new ProxyRulesStore(context).recordFailure("云端暂不可达，继续使用原规则");
            } finally { checking.set(false); }
        });
    }

    /** Existing behaviour: the Hub subscription only replaces its own nodes in the pool. */
    private boolean syncNodes(Context context, JSONObject payload) {
        String configVersion = payload.optString("configVersion", "");
        String configUrl = payload.optString("proxyConfigUrl", "");
        if (configVersion.isEmpty() || !isVersionPending(context, configVersion)) return false;
        if (configUrl.isEmpty()) return false;
        try {
            if (!isHttps(configUrl)) throw new IllegalArgumentException("Unencrypted config URL");
            ProxyProfileManager profiles = new ProxyProfileManager(context);
            if (profiles.isHubSyncDisabled() || !profiles.importRemoteConfig(configUrl)) return false;
            ProxyState state = ProxyState.get();
            state.init(context);
            boolean validSelection = false;
            for (ProxyProfileManager.Node node : profiles.getNodes()) {
                if (node.usable() && node.name.equals(state.getSelectedProxy())) { validSelection = true; break; }
            }
            if (!validSelection) for (ProxyProfileManager.Node node : profiles.getNodes()) {
                if (node.usable()) { state.setSelectedProxy(node.name, context); break; }
            }
            context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CONFIG_VERSION, configVersion).apply();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private boolean hasPendingNodeSync(Context context, JSONObject payload) {
        String configVersion = payload.optString("configVersion", "");
        return !new ProxyProfileManager(context).isHubSyncDisabled()
                && !configVersion.isEmpty() && !payload.optString("proxyConfigUrl", "").isEmpty()
                && isVersionPending(context, configVersion);
    }

    private static boolean isVersionPending(Context context, String configVersion) {
        String local = context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG_VERSION, "");
        return !configVersion.equals(local);
    }

    /**
     * Downloads, verifies and activates the cloud rule bundle.
     *
     * <p>Contract: rulesVersion is a 64-character lowercase SHA-256 and rulesSha256 repeats it;
     * rulesUrl must be an HTTPS URL on the hub's own origin; rulesBytes bounds the payload. A local
     * file is only trusted when it still hashes to the recorded value, so an equal version never
     * hides a corrupted bundle. Anything unexpected keeps the last known good rules.
     *
     * @return true only when a new bundle was committed
     */
    private boolean syncRules(Context context, JSONObject payload) {
        ProxyRulesStore store = new ProxyRulesStore(context);
        store.recordUpstreams(payload);
        String url = payload.optString("rulesUrl", "").trim();
        if (url.isEmpty()) return false; // hub does not publish rules yet
        if (payload.optLong("rulesMinAppCode", 0) > cn.xianhua.pocketkit.BuildConfig.VERSION_CODE) {
            store.recordFailure("新规则需要升级应用，继续使用原规则");
            return false;
        }
        String version = payload.optString("rulesVersion", "").trim();
        String announced = payload.optString("rulesSha256", "").trim();
        String sha = announced.isEmpty() ? version : announced;
        if (!ProxyRules.versionsAgree(version, announced)) {
            store.recordFailure("规则版本信息不一致，继续使用上一版");
            return false;
        }
        // Equal version is not proof: the on-disk bundle must still hash to it.
        if (ProxyRules.verifyFile(store.file(), sha) != null) {
            store.recordFailure("");
            return false;
        }
        try {
            ProxyRulesStore.Pending pending = store.download(DEFAULT_HUB_URL, url, sha,
                    payload.optLong("rulesBytes", 0), payload.optInt("rulesSchemaVersion", 0));
            if (!PocketVpnService.candidateAccepted(context, pending.rules)) {
                store.recordFailure("新规则未通过内核校验，继续使用上一版");
                return false;
            }
            if (!store.commit(pending)) {
                store.recordFailure("规则保存失败，继续使用上一版");
                return false;
            }
            return true;
        } catch (IOException error) {
            String reason = error.getMessage();
            store.recordFailure(reason == null || reason.isEmpty() ? "规则下载失败，继续使用上一版" : reason);
            return false;
        }
    }

    private static boolean alive(Context context) {
        return !(context instanceof Activity) || (!((Activity) context).isFinishing() && !((Activity) context).isDestroyed());
    }
    private static boolean isHttps(String value) {
        try { URL url = new URL(value); return "https".equals(url.getProtocol()) && !url.getHost().isEmpty() && url.getUserInfo() == null; }
        catch (Exception ignored) { return false; }
    }
    private void report(Context context, boolean manual, UpdateCallback callback, boolean config, String message) {
        main.post(() -> {
            if (!alive(context)) return;
            if (manual) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            if (callback != null) callback.onCheckFinished(config, message);
        });
    }
}
