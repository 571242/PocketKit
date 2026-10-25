package cn.xianhua.pocketkit.lanshare;

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
 * 局域网互传的前台服务。
 *
 * 为什么要有它：用户明确要求「离开 Activity 不中断」。Activity 一离开就被系统回收，
 * 只有前台服务能把监听套接字留在进程里。
 *
 * 刻意不做的事：
 *  - 不自启动：没有 BOOT_COMPLETED，没有 START_STICKY，落盘配置里也没有"自动开"；
 *  - 不隐藏：通知常驻，带一键停止；
 *  - 不无限期空转：闲置超过 {@link LanSession#IDLE_TIMEOUT_MS} 自动停止并释放端口。
 */
public class LanShareService extends Service {

    /** 启动会话；已经运行时什么也不做。地址与页面由界面传进来，服务不去读界面状态。 */
    public static final String ACTION_START = "cn.xianhua.pocketkit.lanshare.START";
    public static final String ACTION_STOP = "cn.xianhua.pocketkit.lanshare.STOP";

    private static final String CHANNEL = "lan-share";
    private static final int NOTIFICATION_ID = 8121;
    /** 闲置检查间隔。比超时短很多，停止延迟最多一分钟。 */
    private static final long WATCH_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean watching;
    private LanSession.Observer observer;

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!watching) {
                return;
            }
            if (!LanSession.isRunning()) {
                stopSelf();
                return;
            }
            // 判定逻辑在 LanServer.idleExpired 里，可被 JVM 测试直接断言（不用等十分钟）。
            boolean transferring = !LanSession.transfers().isEmpty();
            if (LanServer.idleExpired(LanSession.lastActivity(), System.currentTimeMillis(),
                    transferring, LanSession.IDLE_TIMEOUT_MS)) {
                // 闲置超时：连通知一起收掉，端口立刻释放。
                LanSession.stop();
                stopSelf();
                return;
            }
            updateNotification();
            handler.postDelayed(this, WATCH_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        observer = new LanSession.Observer() {
            @Override public void onSessionChanged(LanSession.State state) {
                if (state == LanSession.State.RUNNING) {
                    updateNotification();
                } else if (state != LanSession.State.STARTING) {
                    stopSelf();
                }
            }

            @Override public void onServerEvent(LanServer.Event event) {
                updateNotification();
            }
        };
        LanSession.addObserver(observer);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先无条件进前台：用 startForegroundService() 拉起的服务若在 5 秒内
        // 没有调用 startForeground()，Android 8+ 会直接抛 RemoteServiceException 把进程打死。
        // 下面每一条提前返回的路径（没有会话、收到停止）都要先满足这个前提。
        startInForeground();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            LanSession.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!LanSession.isRunning()) {
            // 没有会话却收到启动（例如进程被杀后系统重投递）：不自己起，安静退出。
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!watching) {
            watching = true;
            handler.postDelayed(watch, WATCH_MS);
        }
        updateNotification();
        // 不用 START_STICKY：被系统杀掉就结束，重新开始必须由用户再按一次。
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        // minSdk 26：通知渠道一定存在，不需要旧版分支。
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "局域网互传", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("共享运行时常驻；点通知里的按钮即可停止");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, cn.xianhua.pocketkit.LanShareActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // FLAG_IMMUTABLE 必需（API 31+ 强制），而且这里两个 Intent 都是固定的，不需要可变。
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, LanShareService.class).setAction(ACTION_STOP), flags);

        // 通知里不写地址：地址带会话令牌，锁屏预览会把令牌漏给别人。
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle("局域网互传进行中")
                .setContentText("点开查看地址二维码；离开页面也会继续，直到停止或闲置超时")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止共享", stop).build());
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        LanSession.stop();
        stopSelf();
    }

    @Override public void onDestroy() {
        watching = false;
        handler.removeCallbacks(watch);
        if (observer != null) {
            LanSession.removeObserver(observer);
        }
        // 服务没了就不该再占着端口；这是用户能感知的边界，宁可保守。
        LanSession.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** 启动服务。只在会话已经在跑时调用，否则前台服务会立刻自己退出。 */
    public static void launch(Context context) {
        Intent intent = new Intent(context, LanShareService.class).setAction(ACTION_START);
        // minSdk 26：一律走前台服务入口，服务必须在 5 秒内 startForeground（见 onStartCommand）。
        context.startForegroundService(intent);
    }

    /** 停止服务与端口。通知上的「停止共享」和界面上的停止按钮共用这一条路径。 */
    public static void halt(Context context) {
        LanSession.stop();
        context.stopService(new Intent(context, LanShareService.class));
    }
}
