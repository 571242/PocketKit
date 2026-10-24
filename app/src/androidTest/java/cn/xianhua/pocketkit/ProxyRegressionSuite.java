package cn.xianhua.pocketkit;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import cn.xianhua.pocketkit.proxy.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real native-core/TUN smoke checks, not source-text assertions. */
final class ProxyRegressionSuite {
    private final Instrumentation runner;
    private final List<String> passed = new ArrayList<>();
    ProxyRegressionSuite(Instrumentation runner) { this.runner = runner; }
    void run(Bundle args) {
        Context context = runner.getTargetContext();
        Bundle result = new Bundle();
        try {
            ProxyState state = ProxyState.get();
            state.init(context);
            subscriptionDeletion(context);
            runner.runOnMainSync(() -> state.setMode(ProxyState.Mode.DIRECT, context));
            runner.runOnMainSync(() -> { PocketVpnService.start(context); PocketVpnService.stop(context); });
            await(ProxyState.Status.DISCONNECTED);
            SystemClock.sleep(500);
            require(state.getStatus() == ProxyState.Status.DISCONNECTED && !mixedPortOpen(), "cancelled startup never resurrects the tunnel");
            runner.runOnMainSync(() -> PocketVpnService.start(context));
            await(ProxyState.Status.CONNECTED);
            require(android.net.VpnService.prepare(context) == null, "VPN permission retained");
            String direct = trace();
            require(direct.contains("ip="), "real DIRECT TUN resolves DNS and loads HTTPS");
            stop(context);
            require(trace().contains("ip="), "stopping VPN restores ordinary HTTPS routing");
            String subscription = args.getString("subscription", "");
            if (!subscription.isEmpty()) {
                ProxyProfileManager profiles = new ProxyProfileManager(context);
                require(profiles.importRemoteConfig(subscription), "remote subscription imported with complete credentials");
                List<ProxyProfileManager.Node> nodes = profiles.getNodes();
                require(!nodes.isEmpty(), "subscription contains usable nodes");
                for (int i = 0; i < Math.min(2, nodes.size()); i++) {
                    ProxyProfileManager.Node node = nodes.get(i);
                    runner.runOnMainSync(() -> {
                        state.setSelectedProxy(node.name, context);
                        state.setMode(ProxyState.Mode.GLOBAL, context);
                        PocketVpnService.start(context);
                    });
                    await(ProxyState.Status.CONNECTED);
                    String proxied = trace();
                    require(proxied.contains("ip="), "node " + (i + 1) + " real TUN HTTPS passed");
                    String expected = args.getString("expected" + i, "");
                    if (!expected.isEmpty()) require(proxied.contains("ip=" + expected + "\n"), "node " + (i + 1) + " exit matches expected server");
                    SystemClock.sleep(1500);
                    require(state.getTotalUpload() + state.getTotalDownload() > 0, "native traffic counters report transferred bytes");
                    stop(context);
                }
            }
            result.putString("stream", "PASS " + passed.size() + " VPN runtime checks\n" + String.join("\n", passed) + "\n");
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            runner.runOnMainSync(() -> PocketVpnService.stop(context));
            result.putString("stream", "FAIL after " + passed.size() + " VPN checks: " + error.getClass().getSimpleName() + (error instanceof AssertionError ? ": " + error.getMessage() : "") + "\n" + String.join("\n", passed) + "\n" + ProxyState.get().getLastError() + "\n");
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void stop(Context context) {
        runner.runOnMainSync(() -> PocketVpnService.stop(context));
        await(ProxyState.Status.DISCONNECTED);
        require(!mixedPortOpen(), "disconnect closes the native mixed listener");
        SystemClock.sleep(500);
        require(ProxyState.get().getStatus() == ProxyState.Status.DISCONNECTED, "disconnect stays stopped");
    }
    private static boolean mixedPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 2080), 250);
            return true;
        } catch (IOException closed) { return false; }
    }
    private void subscriptionDeletion(Context context) {
        Context isolated = new android.content.ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return context.getSharedPreferences("quickpatch095_test_" + name, mode);
            }
        };
        android.content.SharedPreferences database = isolated.getSharedPreferences("proxy_multi_subscriptions_db", Context.MODE_PRIVATE);
        database.edit().clear().commit();
        try {
            ProxyProfileManager manager = new ProxyProfileManager(isolated);
            manager.addSubscription(ProxyProfileManager.HUB_SUBSCRIPTION_NAME, "https://example.invalid/sub");
            String id = manager.getSubscriptions().get(0).id;
            manager.removeSubscription(id);
            require(manager.getSubscriptions().isEmpty() && manager.getNodes().isEmpty(), "deleted subscription leaves no nodes");
            require(manager.isHubSyncDisabled(), "explicit Hub removal disables automatic resurrection");
            require(!manager.importRemoteConfig("https://example.invalid/sub") && manager.getLastError().contains("已取消"), "Hub sync aborts before fetching a removed subscription");
            manager.addSubscription(ProxyProfileManager.HUB_SUBSCRIPTION_NAME, "https://example.invalid/sub");
            require(!manager.isHubSyncDisabled(), "explicitly adding Hub restores its updates");
        } finally { database.edit().clear().commit(); }
    }
    private void await(ProxyState.Status expected) {
        long until = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < until) {
            if (ProxyState.get().getStatus() == expected) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("VPN did not reach " + expected);
    }
    private static String trace() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://cloudflare.com/cdn-cgi/trace").openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(20000); conn.setReadTimeout(20000);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTPS probe failed");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1024]; int n;
                while ((n = in.read(b)) != -1) { if (out.size() + n > 16384) throw new IOException("Probe too large"); out.write(b, 0, n); }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { conn.disconnect(); }
    }
    private void require(boolean valid, String label) { if (!valid) throw new AssertionError(label); passed.add(label); }
}
