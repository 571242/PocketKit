package cn.xianhua.pocketkit.mocklocation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 模拟定位的前台服务。
 *
 * 为什么要它：用户切到别的应用看效果时，Activity 可能被系统回收，但测试 Provider 必须
 * 每秒钟有新点，否则定位会「冻」在最后一个坐标上。前台服务 + 常驻通知是唯一正当做法。
 *
 * 刻意不做的事：
 *  - 不自启动、不 START_STICKY：被杀就结束，重新开始必须用户再按一次；
 *  - 退出时一定注销测试 Provider（见 onDestroy）；这是用户能感知的边界——
 *    残留的测试 Provider 会让整台手机的定位停在假点上；
 *  - 通知里始终有「停止」，并显示当前状态。
 */
public class MockLocationService extends Service {

    public static final String ACTION_START = "cn.xianhua.pocketkit.mocklocation.START";
    public static final String ACTION_STOP = "cn.xianhua.pocketkit.mocklocation.STOP";
    public static final String ACTION_TOGGLE = "cn.xianhua.pocketkit.mocklocation.TOGGLE";
    public static final String ACTION_FAST = "cn.xianhua.pocketkit.mocklocation.FAST";

    private static final String CHANNEL = "mock-location";
    private static final int NOTIFICATION_ID = 8123;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;
    private MockLocationSession.Observer observer;
    /** 上一次写进通知栏的文字，用来避免每秒重建同一条通知。 */
    private String lastNotificationKey;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) {
                return;
            }
            if (!MockLocationSession.isActive()) {
                stopSelf();
                return;
            }
            MockLocationSession.tick(getApplicationContext());
            if (MockLocationSession.state() == MockLocationSession.State.RUNNING
                    || MockLocationSession.state() == MockLocationSession.State.PAUSED) {
                handler.postDelayed(this, MockLocationSession.TICK_MS);
            } else {
                stopSelf();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        observer = status -> updateNotification();
        MockLocationSession.addObserver(observer);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 用 startForegroundService() 拉起的服务必须在 5 秒内 startForeground()，
        // 否则 Android 8+ 会抛 RemoteServiceException 直接把进程打死。先满足这个前提。
        startInForeground();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            MockLocationSession.togglePaused();
            if (MockLocationSession.isActive()) {
                beginTicking();
            }
            updateNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_FAST.equals(action)) {
            MockLocationSession.setMultiplier(MockLocationSession.status().multiplier >= 2 ? 1 : 2);
            if (MockLocationSession.isActive()) {
                beginTicking();
            }
            updateNotification();
            return START_NOT_STICKY;
        }

        if (MockLocationSession.isActive() && MockLocationSession.isArmed()) {
            // 已经在跑（用户第二次点启动，或系统重投递）：只把定时器接上，别再拆了重装
            // Provider，否则正在生效的位置会短暂断档。
            beginTicking();
            updateNotification();
            return START_NOT_STICKY;
        }
        // 首次启动：注册测试 Provider。放在服务里而不是界面里，是因为 Provider 的
        // 生命周期必须和这个前台服务一致——服务被回收时 onDestroy 才能保证注销。
        if (!MockLocationSession.arm(getApplicationContext())) {
            // arm() 已经把失败原因写进会话状态；通知里能看到，界面也会收到推送。
            updateNotification();
            stopSelf();
            return START_NOT_STICKY;
        }
        // 立刻推一个点：用户按下启动后应当马上生效，而不是等第一个 1 秒定时器。
        MockLocationSession.tick(getApplicationContext());
        beginTicking();
        updateNotification();
        return START_NOT_STICKY;
    }

    private void beginTicking() {
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, MockLocationSession.TICK_MS);
        }
    }

    private void shutdown() {
        ticking = false;
        handler.removeCallbacks(tick);
        MockLocationSession.stop();
        MockLocationSession.disarm(getApplicationContext());
        stopSelf();
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // 这里刻意用 specialUse 而不是 location：模拟位置不需要 ACCESS_FINE_LOCATION，
            // 用 location 类型反而会强制我们先申请真实定位权限，属于无谓的越权。
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "模拟定位", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("模拟定位运行时常驻；通知里可以暂停、加速或停止");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        MockLocationSession.Status status = MockLocationSession.status();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        // 点通知回到用户当时用的那一页：单点会话回「修改定位」，多点会话回「模拟足迹」。
        boolean fixedMode = !MockLocationSession.isMultiPoint();
        Intent open = new Intent(this, cn.xianhua.pocketkit.MockLocationActivity.class);
        open.putExtra("tool_id", fixedMode ? "L01" : "L02");
        open.putExtra("mode", fixedMode ? "fixed" : "footprint");
        // CLEAR_TOP 让「再点一次通知」回到同一个页面实例，而不是越堆越多。
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);
        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, MockLocationService.class).setAction(ACTION_TOGGLE), flags);
        PendingIntent fast = PendingIntent.getService(this, 3,
                new Intent(this, MockLocationService.class).setAction(ACTION_FAST), flags);
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, MockLocationService.class).setAction(ACTION_STOP), flags);

        boolean paused = status.state == MockLocationSession.State.PAUSED;
        String text = status.state == MockLocationSession.State.ERROR
                ? status.message
                : MockLocationSession.summary(status);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle(paused ? "模拟定位（已暂停）" : "模拟定位进行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET);
        // 单点会话没有「走」这回事，暂停和倍速对它没意义，通知里只留停止。
        if (MockLocationSession.isMultiPoint()) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                            paused ? "继续" : "暂停", toggle).build())
                    .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_ff,
                            status.multiplier >= 2 ? "恢复 1x" : "2 倍速", fast).build());
        }
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "停止", stop).build());
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        MockLocationSession.State state = MockLocationSession.state();
        // 结束时服务马上就要销毁、通知也会被撤掉，没必要再发一条。
        if (manager == null || (state != MockLocationSession.State.RUNNING
                && state != MockLocationSession.State.PAUSED
                && state != MockLocationSession.State.ERROR)) {
            return;
        }
        // 每秒都 notify 会让通知栏一直在闪；只有文字变了才重发。
        MockLocationSession.Status status = MockLocationSession.status();
        String key = status.stateName() + "|" + status.message + "|" + Math.round(status.travelled / 10) + "|" + status.multiplier;
        if (key.equals(lastNotificationKey)) {
            return;
        }
        lastNotificationKey = key;
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void onDestroy() {
        ticking = false;
        handler.removeCallbacks(tick);
        if (observer != null) {
            MockLocationSession.removeObserver(observer);
        }
        // 顺序很重要：先注销测试 Provider，再清会话状态。
        // 任何退出路径（用户停止、系统回收、异常）都必须走到这里。
        MockLocationSession.disarm(getApplicationContext());
        MockLocationSession.detach();
        // 通知是「服务在跑」的信号；服务没了还留着一条常驻通知只会让人误会。
        try {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** 启动服务。调用前必须已经 MockLocationSession.configure(...) 且 arm() 成功。 */
    public static void launch(Context context) {
        Intent intent = new Intent(context, MockLocationService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    /** 停止服务与测试 Provider。通知上的「停止」和界面上的停止按钮共用这一条路径。 */
    public static void halt(Context context) {
        MockLocationSession.stop();
        MockLocationSession.disarm(context);
        context.stopService(new Intent(context, MockLocationService.class));
    }
}
