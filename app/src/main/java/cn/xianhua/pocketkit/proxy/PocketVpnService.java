package cn.xianhua.pocketkit.proxy;

import android.app.*;
import android.app.Notification;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.widget.Toast;
import cn.xianhua.pocketkit.R;
import cn.xianhua.pocketkit.update.UpdateManager;
import io.nekohasekai.libbox.*;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the TUN and a real sing-box core. A failed core must never leave a black-hole VPN. */
public final class PocketVpnService extends VpnService {
    private static final String CHANNEL_ID = "pocketkit_proxy_channel";
    private static final int NOTIFICATION_ID = 2026;
    public static final String ACTION_START = "cn.xianhua.pocketkit.proxy.START";
    public static final String ACTION_STOP = "cn.xianhua.pocketkit.proxy.STOP";
    private static final String ACTION_RELOAD = "cn.xianhua.pocketkit.proxy.RELOAD";
    /** Silent cloud-rule check while the tunnel stays up; the timer dies with the service. */
    private static final long RULES_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);
    private static boolean coreInitialized;
    private static final Object CORE_LOCK = new Object();
    private static final AtomicInteger SERVICE_EPOCH = new AtomicInteger();
    private static PocketVpnService coreOwner;
    private int serviceEpoch;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private ParcelFileDescriptor vpnInterface;
    private ProxyPlatform platform;
    private CommandServer core;
    private CommandClient stats;
    private volatile boolean running;
    private long lastUpload, lastDownload;
    private ProxyState.Mode activeMode;
    private String activeProxy;
    private volatile boolean destroyed, stopping;
    /** Newest startId seen, so a delayed shutdown cannot stop a start that arrived later. */
    private volatile int lastStartId;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, PocketVpnService.class).setAction(ACTION_START));
    }
    public static void stop(Context context) {
        context.startService(new Intent(context, PocketVpnService.class).setAction(ACTION_STOP));
    }
    public static void reload(Context context) {
        ProxyState.Status status = ProxyState.get().getStatus();
        if (status == ProxyState.Status.CONNECTED || status == ProxyState.Status.CONNECTING) {
            context.startService(new Intent(context, PocketVpnService.class).setAction(ACTION_RELOAD));
        }
    }

    /** Validate every candidate in RULE mode, even before the first tunnel or without a node. */
    public static boolean candidateAccepted(Context context, ProxyRules rules) {
        if (rules == null) return false;
        synchronized (CORE_LOCK) {
            try {
                initializeCore(context.getApplicationContext());
                // The placeholder checks rule/DNS syntax without opening a connection.
                String config = ProxyConfig.buildRuntimeConfig("{\"type\":\"direct\"}",
                        "Rule", null, rules);
                Libbox.checkConfig(config);
                return true;
            } catch (Exception | LinkageError error) {
                return false;
            }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        serviceEpoch = SERVICE_EPOCH.incrementAndGet();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "规则代理常驻通知", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        platform = new ProxyPlatform(this);
        ProxyState.get().init(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action) || action == null) { requestStop(); return START_NOT_STICKY; }
        if (destroyed) return START_NOT_STICKY;
        if (stopping) {
            // A shutdown is already in flight. Dropping this START silently left the toggle on
            // while nothing was running, so the request is re-queued after the stop settles.
            if (ACTION_START.equals(action)) {
                main.postDelayed(() -> {
                    if (!destroyed && !stopping && !running
                            && ProxyState.get().getStatus() != ProxyState.Status.CONNECTING) {
                        startCore(serviceEpoch, lastStartId);
                    }
                }, 400);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) && !ACTION_RELOAD.equals(action)) return START_NOT_STICKY;
        if (ACTION_START.equals(action) && (running || ProxyState.get().getStatus() == ProxyState.Status.CONNECTING)) return START_NOT_STICKY;
        try {
            Notification notification = notification("正在启动真实代理内核…");
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (RuntimeException error) {
            fail("无法启动前台 VPN 服务，请重新授权后重试", startId);
            return START_NOT_STICKY;
        }
        int token = generation.incrementAndGet();
        ProxyState.get().setStatus(ProxyState.Status.CONNECTING);
        worker.execute(() -> startCore(token, startId));
        return START_NOT_STICKY;
    }

    private void startCore(int token, int startId) {
        synchronized (CORE_LOCK) {
            if (token != generation.get()) return;
            if (coreOwner != null && coreOwner != this) coreOwner.closeCore();
            startCoreLocked(token, startId);
        }
    }

    private void startCoreLocked(int token, int startId) {
        if (token != generation.get()) return;
        ProxyState.Mode requestedMode = ProxyState.get().getMode();
        String requestedProxy = ProxyState.get().getSelectedProxy();
        String config;
        try {
            ProxyProfileManager profiles = new ProxyProfileManager(this);
            config = profiles.buildRuntimeConfig(requestedProxy, requestedMode, UUID.randomUUID().toString(),
                    new ProxyRulesStore(this).load());
            initializeCore(this);
            Libbox.checkConfig(config);
        } catch (Exception | LinkageError error) {
            if (token != generation.get()) return;
            if (running) {
                main.post(() -> {
                    if (token != generation.get()) return;
                    ProxyState.get().restoreSelection(activeMode, activeProxy, this);
                    ProxyState.get().setError("新配置未通过校验，继续使用原连接");
                    ProxyState.get().setStatus(ProxyState.Status.CONNECTED);
                    Toast.makeText(this, "新配置未通过校验，已保留原连接", Toast.LENGTH_LONG).show();
                    updateNotification();
                });
            } else {
                fail("请选择有效节点并更新订阅后重试（" + error.getClass().getSimpleName() + "）", startId);
            }
            return;
        }
        try {
            if (token != generation.get()) return;
            closeCore();
            if (token != generation.get()) return;
            core = new CommandServer(new CommandServerHandler() {
                public int connectSSHAgent() { return -1; }
                public SystemProxyStatus getSystemProxyStatus() { return new SystemProxyStatus(); }
                public void serviceReload() { main.post(() -> reload(PocketVpnService.this)); }
                public void serviceStop() { main.post(() -> stop(PocketVpnService.this)); }
                public void setSystemProxyEnabled(boolean enabled) { throw new UnsupportedOperationException(); }
                public void triggerNativeCrash() { throw new UnsupportedOperationException(); }
                public void writeDebugMessage(String message) { }
            }, platform);
            coreOwner = this;
            core.start();
            core.startOrReloadService(config, new OverrideOptions());
            if (token != generation.get()) { closeCore(); return; }
            activeMode = requestedMode;
            activeProxy = requestedProxy;
            running = true;
            main.post(() -> {
                if (token != generation.get()) return;
                ProxyState.get().setError("");
                ProxyState.get().setStatus(ProxyState.Status.CONNECTED);
                updateNotification();
                OutboundIpDetector.refresh();
                scheduleRulesCheck();
            });
            startStatistics();
        } catch (Exception | LinkageError error) {
            closeCore();
            if (token == generation.get()) {
                // Native errors can include complete outbound configs; never send credentials to logcat.
                fail("代理内核启动失败：请更新订阅、选择有效节点后重试（" + error.getClass().getSimpleName() + "）", startId);
            }
        }
    }

    // Both callers hold CORE_LOCK. Setup alone never starts a tunnel or a listener.
    private static void initializeCore(Context context) throws Exception {
        if (coreInitialized) return;
        go.Seq.setContext(context.getApplicationContext());
        File dir = new File(context.getFilesDir(), "sing-box");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create core directory");
        SetupOptions options = new SetupOptions();
        options.setBasePath(dir.getAbsolutePath());
        options.setWorkingPath(dir.getAbsolutePath());
        options.setTempPath(context.getCacheDir().getAbsolutePath());
        options.setFixAndroidStack(true);
        options.setCommandServerSecret(UUID.randomUUID().toString());
        options.setLogMaxLines(50);
        Libbox.setup(options);
        coreInitialized = true;
    }

    int openTun(TunOptions options) throws Exception {
        if (stopping || destroyed) throw new java.io.IOException("VPN start cancelled");
        if (prepare(this) != null) throw new SecurityException("VPN permission revoked");
        Builder builder = new Builder().setSession("拾用 · 规则代理").setMtu(options.getMTU());
        RoutePrefixIterator addresses = options.getInet4Address();
        while (addresses.hasNext()) { RoutePrefix address = addresses.next(); builder.addAddress(address.address(), address.prefix()); }
        addresses = options.getInet6Address();
        while (addresses.hasNext()) { RoutePrefix address = addresses.next(); builder.addAddress(address.address(), address.prefix()); }
        builder.addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("172.19.0.2");
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        // Do not exclude PocketKit: protect(fd) keeps core sockets out of the TUN, while app probes use it.
        closeTun();
        vpnInterface = builder.establish();
        if (vpnInterface == null) throw new java.io.IOException("VPN interface unavailable");
        if (stopping || destroyed) { closeTun(); throw new java.io.IOException("VPN start cancelled"); }
        return vpnInterface.getFd();
    }

    /**
     * Arms the silent cloud-rule check. Posting the same Runnable re-schedules instead of stacking,
     * and it is only armed from a successful start so the period is counted from connect.
     */
    private void scheduleRulesCheck() {
        main.removeCallbacks(rulesCheck);
        main.postDelayed(rulesCheck, RULES_CHECK_INTERVAL_MS);
    }

    private final Runnable rulesCheck = new Runnable() {
        @Override public void run() {
            if (!running || destroyed) return;
            scheduleRulesCheck();
            UpdateManager.get().checkRules(getApplicationContext());
        }
    };

    private void startStatistics() {
        lastUpload = lastDownload = 0;
        CommandClientOptions options = new CommandClientOptions();
        options.addCommand(Libbox.CommandStatus);
        options.setStatusInterval(TimeUnit.SECONDS.toNanos(1));
        stats = new CommandClient(new CommandClientHandler() {
            public void writeStatus(StatusMessage status) {
                if (!running || !status.getTrafficAvailable()) return;
                long up = status.getUplinkTotal(), down = status.getDownlinkTotal();
                long deltaUp = Math.max(0, up - lastUpload), deltaDown = Math.max(0, down - lastDownload);
                lastUpload = up; lastDownload = down;
                ProxyState.get().updateTraffic(status.getUplink(), status.getDownlink(), deltaUp, deltaDown);
                main.post(() -> updateNotification());
            }
            public void connected() { }
            public void disconnected(String message) { }
            public void clearLogs() { }
            public void setDefaultLogLevel(int level) { }
            public void initializeClashMode(StringIterator modes, String mode) { }
            public void updateClashMode(String mode) { }
            public void writeConnectionEvents(ConnectionEvents events) { }
            public void writeGroups(OutboundGroupIterator groups) { }
            public void writeLogs(LogIterator logs) { }
            public void writeOutbounds(OutboundGroupItemIterator outbounds) { }
        }, options);
        try { stats.connect(); }
        catch (Exception error) { ProxyState.get().updateTraffic(0, 0, 0, 0); }
    }

    private void closeTun() {
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (java.io.IOException ignored) { }
            vpnInterface = null;
        }
    }
    private void closeCore() {
        running = false;
        main.removeCallbacks(rulesCheck);
        if (stats != null) { try { stats.disconnect(); } catch (Exception ignored) { } stats = null; }
        // libbox owns a duplicated descriptor; both native and Java TUN handles must close.
        closeTun();
        if (core != null) {
            try { core.closeService(); } catch (Exception ignored) { }
            try { core.close(); } catch (Exception | LinkageError ignored) { }
            core = null;
        }
        platform.close();
        if (coreOwner == this) coreOwner = null;
    }
    private void fail(String message, int startId) {
        main.post(() -> {
            if (stopping || destroyed || serviceEpoch != SERVICE_EPOCH.get()) return;
            ProxyState.get().setError(message);
            ProxyState.get().setStatus(ProxyState.Status.DISCONNECTED);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        });
    }
    /** VpnService may remain system-bound: stopSelf alone does not close its TUN. */
    private void requestStop() {
        if (stopping || destroyed) return;
        stopping = true;
        generation.incrementAndGet();
        running = false;
        main.removeCallbacks(rulesCheck);
        ProxyState.get().setStatus(ProxyState.Status.DISCONNECTING);
        worker.execute(() -> {
            synchronized (CORE_LOCK) { closeCore(); }
            main.post(() -> {
                if (destroyed) return;
                if (serviceEpoch == SERVICE_EPOCH.get()) ProxyState.get().setStatus(ProxyState.Status.DISCONNECTED);
                stopping = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                // Without startId this could tear down a newer ACTION_START that arrived while the
                // shutdown was in flight.
                stopSelf(lastStartId);
            });
        });
    }
    @Override public void onRevoke() { requestStop(); }
    @Override public void onDestroy() {
        generation.incrementAndGet();
        destroyed = true;
        stopping = true;
        main.removeCallbacksAndMessages(null);
        if (ProxyState.get().getStatus() != ProxyState.Status.DISCONNECTED)
            ProxyState.get().setStatus(ProxyState.Status.DISCONNECTING);
        worker.execute(() -> {
            synchronized (CORE_LOCK) { closeCore(); }
            main.post(() -> {
                if (serviceEpoch == SERVICE_EPOCH.get()) ProxyState.get().setStatus(ProxyState.Status.DISCONNECTED);
            });
        });
        worker.shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ProxyActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PocketVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID).setContentTitle("拾用 · 规则代理")
                .setContentText(text).setSmallIcon(R.drawable.ic_stat_pocketkit)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开连接", stop).setOnlyAlertOnce(true).setOngoing(true).build();
    }
    private void updateNotification() {
        if (!running || destroyed) return;
        ProxyState state = ProxyState.get();
        String text = state.getSelectedProxy() + " · " + state.getMode().label + " ↑ " + ProxyState.formatSpeed(state.getUploadSpeedBps()) + " ↓ " + ProxyState.formatSpeed(state.getDownloadSpeedBps());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }
}
