package cn.xianhua.pocketkit.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared connection state. Listener callbacks always run on Android's main thread. */
public final class ProxyState {
    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
    public enum Mode {
        RULE("规则模式", "Rule"), GLOBAL("全局模式", "Global"), DIRECT("直连模式", "Direct");
        public final String label, coreValue;
        Mode(String label, String value) { this.label = label; coreValue = value; }
        public static Mode fromCore(String value) {
            for (Mode mode : values()) if (mode.coreValue.equalsIgnoreCase(value)) return mode;
            return RULE;
        }
    }
    public interface StateListener {
        void onStatusChanged(Status status);
        void onTrafficUpdated(long uploadSpeedBps, long downloadSpeedBps, long totalUpload, long totalDownload);
        void onOutboundIpUpdated(String ip, String location);
        void onModeChanged(Mode mode);
        void onSelectedProxyChanged(String proxyName);
    }
    private static final ProxyState INSTANCE = new ProxyState();
    private static final String PREF_NAME = "proxy_settings";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong connectionVersion = new AtomicLong();
    private boolean initialized;
    private volatile Status status = Status.DISCONNECTED;
    private volatile Mode mode = Mode.RULE;
    private volatile String selectedProxy = "未选择节点", outboundIp = "未检测", outboundLocation = "", lastError = "";
    private volatile long uploadSpeedBps, downloadSpeedBps, totalUpload, totalDownload;
    private ProxyState() { }
    public static ProxyState get() { return INSTANCE; }
    public synchronized void init(Context context) {
        if (initialized) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try { mode = Mode.valueOf(sp.getString("outbound_mode", Mode.RULE.name())); } catch (IllegalArgumentException ignored) { mode = Mode.RULE; }
        selectedProxy = sp.getString("last_selected_proxy", "未选择节点");
        initialized = true;
    }
    public void save(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString("outbound_mode", mode.name()).putString("last_selected_proxy", selectedProxy).apply();
    }
    private void emit(Consumer<StateListener> callback) {
        main.post(() -> { for (StateListener listener : listeners) try { callback.accept(listener); } catch (RuntimeException ignored) { } });
    }
    public void addListener(StateListener listener) {
        if (listener == null || !listeners.addIfAbsent(listener)) return;
        main.post(() -> {
            if (!listeners.contains(listener)) return;
            listener.onStatusChanged(status);
            listener.onTrafficUpdated(uploadSpeedBps, downloadSpeedBps, totalUpload, totalDownload);
            listener.onOutboundIpUpdated(outboundIp, outboundLocation);
            listener.onModeChanged(mode);
            listener.onSelectedProxyChanged(selectedProxy);
        });
    }
    public void removeListener(StateListener listener) { listeners.remove(listener); }

    /**
     * Re-publishes the current status without changing it.
     *
     * <p>{@link #setStatus} returns early when the value is unchanged, which is correct but means a
     * caller that aborted a connection attempt (a cancelled VPN permission, for instance) could not
     * resynchronise a UI that had already flipped its switch optimistically.
     */
    public void refreshStatus() { emit(l -> l.onStatusChanged(status)); }
    public Status getStatus() { return status; }
    public Mode getMode() { return mode; }
    public String getSelectedProxy() { return selectedProxy; }
    public String getOutboundIp() { return outboundIp; }
    public String getOutboundLocation() { return outboundLocation; }
    public String getLastError() { return lastError; }
    public void setError(String error) { lastError = error == null ? "" : error; }
    public long getConnectionVersion() { return connectionVersion.get(); }
    public long getUploadSpeedBps() { return uploadSpeedBps; }
    public long getDownloadSpeedBps() { return downloadSpeedBps; }
    public long getTotalUpload() { return totalUpload; }
    public long getTotalDownload() { return totalDownload; }
    public synchronized void setStatus(Status value) {
        if (status == value) return;
        status = value;
        connectionVersion.incrementAndGet();
        if (value == Status.DISCONNECTED) {
            uploadSpeedBps = downloadSpeedBps = 0;
            updateOutboundIp("未连接", "");
            emit(l -> l.onTrafficUpdated(0, 0, totalUpload, totalDownload));
        }
        emit(l -> l.onStatusChanged(value));
    }
    public void setMode(Mode value, Context context) {
        if (mode == value) return;
        mode = value;
        connectionVersion.incrementAndGet();
        if (context != null) save(context);
        emit(l -> l.onModeChanged(value));
        if (context != null) PocketVpnService.reload(context);
    }
    public void setSelectedProxy(String value, Context context) {
        if (value == null || value.equals(selectedProxy)) return;
        selectedProxy = value;
        connectionVersion.incrementAndGet();
        if (context != null) save(context);
        emit(l -> l.onSelectedProxyChanged(value));
        if (context != null) PocketVpnService.reload(context);
    }
    /** Restore a rejected selection without scheduling another reload. */
    void restoreSelection(Mode value, String proxy, Context context) {
        mode = value;
        selectedProxy = proxy;
        connectionVersion.incrementAndGet();
        save(context);
        emit(l -> l.onModeChanged(value));
        emit(l -> l.onSelectedProxyChanged(proxy));
    }
    public synchronized void updateTraffic(long up, long down, long deltaUp, long deltaDown) {
        uploadSpeedBps = Math.max(0, up); downloadSpeedBps = Math.max(0, down);
        totalUpload += Math.max(0, deltaUp); totalDownload += Math.max(0, deltaDown);
        long sent = totalUpload, received = totalDownload;
        emit(l -> l.onTrafficUpdated(Math.max(0, up), Math.max(0, down), sent, received));
    }
    public void updateOutboundIp(String ip, String location) {
        outboundIp = ip == null || ip.isEmpty() ? "未知" : ip;
        outboundLocation = location == null ? "" : location;
        String value = outboundIp, place = outboundLocation;
        emit(l -> l.onOutboundIpUpdated(value, place));
    }
    public static String formatSpeed(long bytes) {
        if (bytes <= 0) return "0 B/s";
        if (bytes < 1024) return bytes + " B/s";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB/s", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB/s", bytes / (1024.0 * 1024.0));
    }
    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
