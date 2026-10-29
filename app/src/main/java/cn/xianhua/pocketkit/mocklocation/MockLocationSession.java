package cn.xianhua.pocketkit.mocklocation;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 一次「修改定位 / 模拟足迹」会话的全部状态。
 *
 * 为什么是静态单例：前台服务负责 1Hz 推送，界面（Activity）随时可能被回收重建，
 * 两边必须看到同一份进度。与 LanSession 的做法保持一致。
 *
 * 关键事实（决定这里怎么写）：
 *  - 写测试 Provider 只需要「开发者选项 → 选择模拟位置信息应用」这一个 app op，
 *    不需要 ACCESS_FINE_LOCATION，也不需要 location 类型的前台服务；
 *  - 测试 Provider 是系统级的：它替换掉同名真实 Provider 对**所有**应用生效，
 *    进程崩溃/被杀后不会自动注销，所以每次服务启动前都要先防御性清理一次；
 *  - 只模拟 gps / network，Android 12 起再加 fused（31 之前 fused 不可见且无用）；
 *    绝不碰 passive——它在旧版本会直接抛异常，而且会破坏全机的被动定位分发。
 */
public final class MockLocationSession {

    public enum State { IDLE, RUNNING, PAUSED, ERROR }

    /** 一次状态快照，给界面轮询用。 */
    public static final class Status {
        public final State state;
        public final String message;
        /** 当前注入点（WGS-84）。没有会话时为 0。 */
        public final double lat;
        public final double lng;
        public final double travelled;
        public final double total;
        public final boolean arrived;
        public final double speed;
        public final double multiplier;

        Status(State state, String message, double lat, double lng, double travelled,
               double total, boolean arrived, double speed, double multiplier) {
            this.state = state;
            this.message = message;
            this.lat = lat;
            this.lng = lng;
            this.travelled = travelled;
            this.total = total;
            this.arrived = arrived;
            this.speed = speed;
            this.multiplier = multiplier;
        }

        public double progress() {
            return total <= 0 ? (state == State.IDLE ? 0 : 1) : Math.max(0, Math.min(1, travelled / total));
        }

        public String stateName() {
            switch (state) {
                case RUNNING: return "running";
                case PAUSED: return "paused";
                case ERROR: return "error";
                default: return "idle";
            }
        }
    }

    /** 状态变化回调，统一在主线程。 */
    public interface Observer {
        void onStatus(Status status);
    }

    /** 每个 tick 的间隔。1Hz 是业界惯例，也足够让地图看起来在动。 */
    public static final long TICK_MS = 1000L;

    private static final Object LOCK = new Object();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Random random = new Random();

    private static State state = State.IDLE;
    private static String message = "";
    private static RouteTrack track;
    /** 这条路线是不是多点路线；单点会话即使两点重合也不会被误判成足迹。 */
    private static boolean multiPoint;
    private static double speedMps = 1.4;
    private static float jitterMeters = 2f;
    private static double multiplier = 1;
    private static long lastTickAt;
    private static double lastLat;
    private static double lastLng;
    private static double lastBearing;
    private static boolean armed;
    private static final List<Observer> observers = new ArrayList<>();

    private MockLocationSession() {
    }

    public static State state() {
        synchronized (LOCK) {
            return state;
        }
    }

    public static boolean isActive() {
        State current = state();
        return current == State.RUNNING || current == State.PAUSED;
    }

    public static boolean isArmed() {
        synchronized (LOCK) {
            return armed;
        }
    }

    /** 当前会话是否为多点路线，供通知点击后回到正确的页面。 */
    public static boolean isMultiPoint() {
        synchronized (LOCK) {
            return multiPoint;
        }
    }

    public static void addObserver(Observer observer) {
        synchronized (LOCK) {
            if (!observers.contains(observer)) {
                observers.add(observer);
            }
        }
        observer.onStatus(status());
    }

    public static void removeObserver(Observer observer) {
        synchronized (LOCK) {
            observers.remove(observer);
        }
    }

    public static Status status() {
        synchronized (LOCK) {
            double total = track == null ? 0 : track.totalMeters();
            double travelled = track == null ? 0 : track.travelledMeters();
            return new Status(state, message, lastLat, lastLng, travelled, total,
                    track != null && track.sample().arrived, speedMps * multiplier, multiplier);
        }
    }

    /**
     * 设置路线参数。坐标是 WGS-84，调用前已由界面从 GCJ-02 转好。
     *
     * @param points       至少一个点；一个点就是原地不动
     * @param speedMps     移动速度，米/秒
     * @param jitterMeters 抖动半径，0 表示不抖
     */
    public static void configure(List<double[]> points, double speedMps, double jitterMeters) {
        RouteTrack next = new RouteTrack(points);
        synchronized (LOCK) {
            track = next;
            multiPoint = points.size() > 1;
            MockLocationSession.speedMps = Math.max(0, Math.min(80, speedMps));
            MockLocationSession.jitterMeters = (float) Math.max(0, Math.min(50, jitterMeters));
            multiplier = 1;
            // 正在跑的时候换路线：不能先降到 IDLE，否则服务的定时器会以为会话结束而自杀。
            // 保持 RUNNING/PAUSED，让下一个 tick 直接走新路线；arm() 会重置计时。
            if (state != State.RUNNING && state != State.PAUSED) {
                state = State.IDLE;
            } else if (state == State.PAUSED) {
                // 点「重新启动」时人是想继续走的，不应该还停在暂停里。
                state = State.RUNNING;
            }
            message = "";
            lastLat = points.get(0)[0];
            lastLng = points.get(0)[1];
            lastBearing = 0;
            lastTickAt = SystemClock.elapsedRealtime();
        }
    }

    /** 是否已经拿到「模拟位置信息应用」授权。不需要任何运行时权限。 */
    public static boolean permitted(Context context) {
        android.app.AppOpsManager ops = (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) {
            return false;
        }
        try {
            return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(), context.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean locationEnabled(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 注册测试 Provider。由前台服务调用（服务已经进前台，进程不会被随手回收）。
     *
     * @return 成功返回 true；失败时 state 已置为 ERROR 且 message 里写明原因
     */
    public static boolean arm(Context context) {
        removeProviders(context);
        synchronized (LOCK) {
            armed = false;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            fail("系统没有提供定位服务。");
            return false;
        }
        if (!permitted(context)) {
            fail("还没有把「拾用」选为模拟位置信息应用。请到 开发者选项 → 选择模拟位置信息应用 里选上它。");
            return false;
        }
        try {
            // 逐个注册：fused 在个别 ROM 上不允许被替换，不能因为它一个失败就把
            // gps/network 也一起放弃——只要还有一个能写，模拟就是有效的。
            int registered = 0;
            String firstFailure = null;
            for (String name : providers()) {
                try {
                    addProvider(manager, name);
                    try {
                        manager.setTestProviderEnabled(name, true);
                    } catch (Throwable notEnabled) {
                        // addTestProvider 已经成功、enable 失败：留着会是一个「存在但未启用」的
                        // 半成品 Provider，先撤掉再记失败，避免下一轮状态不干净。
                        try {
                            manager.removeTestProvider(name);
                        } catch (Throwable ignored) {
                        }
                        throw notEnabled;
                    }
                    registered++;
                } catch (SecurityException denied) {
                    throw denied;
                } catch (Throwable error) {
                    if (firstFailure == null) {
                        firstFailure = name + "：" + describe(error);
                    }
                }
            }
            if (registered == 0) {
                removeProviders(context);
                fail("注册模拟位置失败：" + (firstFailure == null ? "系统拒绝了全部定位 Provider" : firstFailure));
                return false;
            }
        } catch (SecurityException denied) {
            removeProviders(context);
            fail("模拟位置权限被拒绝：请确认「开发者选项 → 选择模拟位置信息应用」里选的是「拾用」。");
            return false;
        } catch (Throwable error) {
            removeProviders(context);
            fail("注册模拟位置失败：" + describe(error));
            return false;
        }
        synchronized (LOCK) {
            armed = true;
            lastTickAt = SystemClock.elapsedRealtime();
            state = State.RUNNING;
            message = "";
        }
        notifyStatus();
        return true;
    }

    /** 注销测试 Provider。任何退出路径都要走到这里，否则全机定位会停在最后一个假点上。 */
    public static void disarm(Context context) {
        removeProviders(context);
        synchronized (LOCK) {
            armed = false;
        }
    }

    /**
     * 清理上一次进程留下的测试 Provider。
     *
     * 测试 Provider 在进程被杀后不会被系统回收，而静态状态已经清零，所以只能靠每次
     * 打开页面时盲扫一遍。有活会话时跳过——那是当前正在用的，不能拆。
     */
    public static void sweepStale(Context context) {
        synchronized (LOCK) {
            if (armed || state == State.RUNNING || state == State.PAUSED) {
                return;
            }
        }
        removeProviders(context);
    }

    /** 由服务里的 1Hz 定时器调用。暂停时也照常推送同一个点：位置停住，但时间戳保持新鲜。 */
    public static void tick(Context context) {
        Status snapshot;
        synchronized (LOCK) {
            if ((state != State.RUNNING && state != State.PAUSED) || track == null || !armed) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            double deltaSeconds = Math.max(0, Math.min(5, (now - lastTickAt) / 1000.0));
            lastTickAt = now;
            if (state == State.RUNNING) {
                RouteTrack.Sample sample = track.advance(speedMps * multiplier * deltaSeconds);
                lastLat = sample.lat;
                lastLng = sample.lng;
                lastBearing = sample.bearing;
            }
            snapshot = status();
        }
        if (!push(context, snapshot)) {
            return;
        }
        notifyStatus();
    }

    public static void setPaused(boolean paused) {
        synchronized (LOCK) {
            if (state != State.RUNNING && state != State.PAUSED) {
                return;
            }
            state = paused ? State.PAUSED : State.RUNNING;
            lastTickAt = SystemClock.elapsedRealtime();
            message = paused ? "已暂停，位置停在当前点" : "";
        }
        notifyStatus();
    }

    public static void togglePaused() {
        setPaused(state() != State.PAUSED);
    }

    public static void setMultiplier(double value) {
        synchronized (LOCK) {
            multiplier = Math.max(0.25, Math.min(4, value));
            lastTickAt = SystemClock.elapsedRealtime();
        }
        notifyStatus();
    }

    /** 用户主动停止：只改状态，Provider 由服务在销毁时注销。 */
    public static void stop() {
        synchronized (LOCK) {
            state = State.IDLE;
            message = "";
            track = null;
            multiPoint = false;
            multiplier = 1;
            armed = false;
        }
        notifyStatus();
    }

    /**
     * 服务销毁时调用。正在运行/暂停的会话回到 IDLE；ERROR 保留原因，方便用户
     * 回到界面时还能看到为什么失败 —— 否则错误一闪而过，等于什么都没说。
     */
    public static void detach() {
        synchronized (LOCK) {
            armed = false;
            if (state == State.RUNNING || state == State.PAUSED) {
                state = State.IDLE;
                message = "";
                track = null;
                multiPoint = false;
                multiplier = 1;
            }
        }
        notifyStatus();
    }

    static void fail(String reason) {
        synchronized (LOCK) {
            state = State.ERROR;
            message = reason;
        }
        notifyStatus();
    }

    private static String describe(Throwable error) {
        String text = error == null ? "" : error.getMessage();
        if (text == null || text.trim().isEmpty()) {
            text = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return text;
    }

    private static boolean push(Context context, Status snapshot) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            fail("系统没有提供定位服务。");
            return false;
        }
        // 暂停时不加抖动：用户点「暂停」期望位置真的钉住，而不是在原地小幅游走。
        double[] point = snapshot.state == State.PAUSED
                ? new double[]{snapshot.lat, snapshot.lng}
                : jitter(snapshot.lat, snapshot.lng);
        try {
            int pushed = 0;
            String firstFailure = null;
            for (String name : providers()) {
                try {
                    Location location = build(manager, name, point[0], point[1], snapshot);
                    manager.setTestProviderLocation(name, location);
                    pushed++;
                } catch (SecurityException denied) {
                    throw denied;
                } catch (Throwable error) {
                    if (firstFailure == null) {
                        firstFailure = name + "：" + describe(error);
                    }
                }
            }
            if (pushed == 0) {
                fail("推送模拟位置失败：" + (firstFailure == null ? "没有可用的定位 Provider" : firstFailure));
                return false;
            }
            return true;
        } catch (SecurityException denied) {
            synchronized (LOCK) {
                armed = false;
            }
            fail("模拟位置权限被收回（可能是在开发者选项里换了应用）。请重新选择「拾用」后再启动；若定位卡住，重启手机即可恢复。");
            return false;
        } catch (Throwable error) {
            fail("推送模拟位置失败：" + describe(error));
            return false;
        }
    }

    private static Location build(LocationManager manager, String provider, double lat, double lng, Status snapshot) {
        Location location = new Location(provider);
        location.setLatitude(lat);
        location.setLongitude(lng);
        // 精度取 4~9 米：太小不像真实 GNSS，太大又会被地图画成大圈。
        location.setAccuracy(6f + random.nextFloat() * 3f);
        location.setAltitude(30 + random.nextDouble() * 20);
        location.setSpeed((float) (snapshot.arrived || snapshot.state == State.PAUSED ? 0 : snapshot.speed));
        location.setBearing((float) lastBearing);
        location.setTime(System.currentTimeMillis());
        // 必须是「现在」，不能是未来；每次推送都要重新取。
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setVerticalAccuracyMeters(10f);
        location.setSpeedAccuracyMetersPerSecond(0.2f);
        location.setBearingAccuracyDegrees(2f);
        if (Build.VERSION.SDK_INT >= 31) {
            // 系统在投递时本来就会盖成 mock；这里显式标记只是让意图清楚。
            // 31 以下没有这个公开方法（isMock/setMock 都是 S 才开放的），不能无条件调用。
            location.setMock(true);
        }
        return location;
    }

    private static double[] jitter(double lat, double lng) {
        float radius = jitterMeters;
        if (radius <= 0) {
            return new double[]{lat, lng};
        }
        double distance = Math.sqrt(random.nextDouble()) * radius;
        double angle = random.nextDouble() * Math.PI * 2;
        return CoordTransform.offsetMeters(lat, lng, distance * Math.cos(angle), distance * Math.sin(angle));
    }

    private static String[] providers() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER};
        }
        return new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
    }

    // ProviderProperties 的常量是编译期 inlined int（数值与旧 Criteria 的 1/2 完全一致），
    // 所以旧重载里直接用它既安全又只需一套词表；lint 的 InlinedApi 属于误报。
    @android.annotation.SuppressLint("InlinedApi")
    private static void addProvider(LocationManager manager, String name) {
        if (Build.VERSION.SDK_INT >= 31) {
            ProviderProperties properties = new ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build();
            manager.addTestProvider(name, properties);
        } else {
            // 旧重载参数多但语义直白：不依赖网络/卫星/基站，不收钱，支持高度/速度/方向。
            manager.addTestProvider(name, false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
        }
    }

    private static void removeProviders(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        // 不区分版本，三个名字都试：S+ 上删除不存在的 Provider 是空操作，旧版本抛的异常吞掉。
        String[] names = {"gps", "network", "fused"};
        for (String name : names) {
            try {
                manager.removeTestProvider(name);
            } catch (Throwable ignored) {
                // 没注册过、或权限已经被收回——都无需让用户看到。
            }
        }
    }

    private static void notifyStatus() {
        final Status snapshot = status();
        main.post(() -> {
            List<Observer> copy;
            synchronized (LOCK) {
                copy = new ArrayList<>(observers);
            }
            for (Observer observer : copy) {
                observer.onStatus(snapshot);
            }
        });
    }

    /** 给界面用的一行摘要。 */
    public static String summary(Status status) {
        if (status.state == State.ERROR) {
            return status.message;
        }
        if (status.state == State.IDLE) {
            return "未在模拟";
        }
        String where = String.format(Locale.US, "%.5f, %.5f", status.lat, status.lng);
        if (status.total <= 1) {
            return "已定位到 " + where + (status.state == State.PAUSED ? "（暂停）" : "");
        }
        return String.format(Locale.US, "%s · %.0f/%.0f 米 · %s", where, status.travelled, status.total,
                status.state == State.PAUSED ? "已暂停" : (status.arrived ? "已到终点" : "行进中"));
    }
}
