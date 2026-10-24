package cn.xianhua.pocketkit.proxy;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks the actual selected outbound through the core's loopback HTTP inbound. */
public final class OutboundIpDetector {
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private OutboundIpDetector() { }

    public static void refresh() {
        ProxyState state = ProxyState.get();
        if (state.getStatus() == ProxyState.Status.CONNECTING || state.getStatus() == ProxyState.Status.DISCONNECTING) return;
        if (!BUSY.compareAndSet(false, true)) return;
        long version = state.getConnectionVersion();
        boolean connected = state.getStatus() == ProxyState.Status.CONNECTED;
        state.updateOutboundIp("检测中…", "");
        EXECUTOR.execute(() -> {
            String ip = "检测超时", location = "点击重试";
            try {
                String trace = fetch("https://cloudflare.com/cdn-cgi/trace", connected);
                for (String line : trace.split("\n")) {
                    if (line.startsWith("ip=")) ip = line.substring(3).trim();
                    if (line.startsWith("loc=")) location = line.substring(4).trim();
                }
                if ("检测超时".equals(ip)) throw new IOException("Trace has no IP");
            } catch (Exception first) {
                try {
                    JSONObject json = new JSONObject(fetch("https://api.ip.sb/geoip", connected));
                    ip = json.getString("ip");
                    location = (json.optString("country", "") + " " + json.optString("city", "")).trim();
                } catch (Exception ignored) { }
            }
            String value = ip, place = location;
            MAIN.post(() -> {
                BUSY.set(false);
                if (version == state.getConnectionVersion()) state.updateOutboundIp(value, (connected ? "" : "直连 · ") + place);
                else if (state.getStatus() == ProxyState.Status.CONNECTED) refresh();
            });
        });
    }
    private static String fetch(String address, boolean connected) throws IOException {
        Proxy proxy = connected ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 2080)) : Proxy.NO_PROXY;
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection(proxy);
        conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "PocketKit/0.9.3");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("IP endpoint unavailable");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int length;
                while ((length = in.read(buffer)) != -1) {
                    if (out.size() + length > 16384) throw new IOException("IP response too large");
                    out.write(buffer, 0, length);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { conn.disconnect(); }
    }
}
