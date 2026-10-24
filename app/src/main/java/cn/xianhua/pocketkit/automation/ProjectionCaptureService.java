package cn.xianhua.pocketkit.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** One user-authorized, continuously readable MediaProjection session for API 26-29 fallback. */
public final class ProjectionCaptureService extends Service {
    private static final String CHANNEL = "automation_capture";
    private static final int NOTIFICATION_ID = 807;
    private static final String EXTRA_CODE = "result_code";
    private static final String EXTRA_DATA = "result_data";
    private static final String STOP = "cn.xianhua.pocketkit.automation.capture.STOP";
    private static final Object LOCK = new Object();
    private static ProjectionCaptureService instance;
    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread readerThread;
    private static Handler readerHandler;
    private static Bitmap latest;
    private static String lastError;
    private static boolean starting;
    private static boolean waitingForFrame;
    private static long frameSequence;
    private static long requestedAfterSequence;
    private int width;
    private int height;

    public static void start(Context context, int resultCode, Intent resultData) {
        if (context == null || resultData == null) throw new IllegalArgumentException("屏幕授权数据无效。");
        synchronized (LOCK) {
            if (isReadyLocked() || starting) return;
            starting = true;
        }
        Intent intent = new Intent(context.getApplicationContext(), ProjectionCaptureService.class)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, resultData);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ex) {
            synchronized (LOCK) { starting = false; lastError = "屏幕投影前台服务启动失败：" + message(ex); }
            throw ex;
        }
    }

    public static boolean isReady() {
        synchronized (LOCK) { return isReadyLocked(); }
    }

    public static void stop(Context context) {
        if (context == null) throw new IllegalArgumentException("停止屏幕投影需要 Context。");
        context.getApplicationContext().stopService(new Intent(context, ProjectionCaptureService.class));
    }

    /** Returns a caller-owned software bitmap, waiting for a reader frame when necessary. */
    public static Bitmap awaitFrame(long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("截图超时必须大于 0 毫秒。");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (LOCK) {
            if (!isReadyLocked()) throw new IllegalStateException(lastError == null ? "屏幕投影尚未授权。" : lastError);
            if (waitingForFrame) throw new IllegalStateException("屏幕投影已有截图请求正在等待。");
            requestedAfterSequence = frameSequence;
            waitingForFrame = true;
            while (isReadyLocked() && frameSequence <= requestedAfterSequence) {
                long leftNanos = deadline - System.nanoTime();
                if (leftNanos <= 0) { waitingForFrame = false; throw new IllegalStateException("屏幕投影等待新帧超时。"); }
                long leftMillis = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(leftNanos));
                try { LOCK.wait(leftMillis); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    waitingForFrame = false;
                    throw new IllegalStateException("屏幕投影等待被取消。", ex);
                }
            }
            waitingForFrame = false;
            if (latest == null || frameSequence <= requestedAfterSequence) throw new IllegalStateException(lastError == null ? "屏幕投影未返回新帧。" : lastError);
            Bitmap copy = latest.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("屏幕投影帧复制失败。");
            return copy;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null || !intent.hasExtra(EXTRA_DATA)) {
            failAndStop("屏幕投影授权数据缺失。");
            return START_NOT_STICKY;
        }
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, n);
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection obtained = manager.getMediaProjection(intent.getIntExtra(EXTRA_CODE, -1),
                    intent.getParcelableExtra(EXTRA_DATA));
            if (obtained == null) throw new IllegalStateException("屏幕授权已失效，请重新授权。");
            synchronized (LOCK) {
                if (isReadyLocked()) { starting = false; obtained.stop(); return START_NOT_STICKY; }
                projection = obtained;
                lastError = null;
                DisplayMetrics metrics = new DisplayMetrics();
                ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels;
                height = metrics.heightPixels;
                if (width <= 0 || height <= 0) throw new IllegalStateException("无法读取屏幕尺寸。");
                readerThread = new HandlerThread("PocketKitProjectionReader");
                readerThread.start();
                readerHandler = new Handler(readerThread.getLooper());
                reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(this::onImageAvailable, readerHandler);
                projection.registerCallback(callback, readerHandler);
                display = projection.createVirtualDisplay("PocketKitAutomationCapture", width, height,
                        metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, readerHandler);
                if (display == null) throw new IllegalStateException("无法创建屏幕投影显示。");
                starting = false;
                LOCK.notifyAll();
            }
        } catch (RuntimeException ex) {
            synchronized (LOCK) { starting = false; lastError = "屏幕投影启动失败：" + message(ex); cleanupLocked(); }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void onImageAvailable(ImageReader source) {
        Image image = null;
        Bitmap bitmap = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            if (image.getWidth() != width || image.getHeight() != height) {
                synchronized (LOCK) { lastError = "屏幕方向或尺寸已变化，请重新授权屏幕捕获。"; cleanupLocked(); }
                stopSelf();
                return;
            }
            synchronized (LOCK) { if (!isReadyLocked() || !waitingForFrame) return; }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0 || planes[0].getPixelStride() != 4) throw new IllegalStateException("屏幕帧像素格式不支持。");
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowPadding = plane.getRowStride() - plane.getPixelStride() * width;
            int paddedWidth = width + Math.max(0, rowPadding / plane.getPixelStride());
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (cropped != padded) padded.recycle();
            bitmap = cropped;
            synchronized (LOCK) {
                if (!isReadyLocked() || !waitingForFrame) { bitmap.recycle(); bitmap = null; return; }
                Bitmap old = latest;
                latest = bitmap;
                frameSequence++;
                waitingForFrame = false;
                bitmap = null;
                if (old != null) old.recycle();
                LOCK.notifyAll();
            }
        } catch (RuntimeException ex) {
            synchronized (LOCK) { lastError = "读取屏幕帧失败：" + message(ex); }
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (image != null) image.close();
        }
    }

    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() {
            synchronized (LOCK) {
                lastError = "屏幕投影授权已撤回，请重新授权。";
                cleanupLocked();
                try { cn.xianhua.pocketkit.automation.AutomationRuntime.stop("屏幕投影授权已撤回"); } catch (RuntimeException ignored) { }
            }
            stopSelf();
        }
    };

    private void failAndStop(String error) {
        synchronized (LOCK) { starting = false; lastError = error; cleanupLocked(); }
        stopSelf();
    }

    private static boolean isReadyLocked() { return projection != null && display != null && reader != null; }

    private static void cleanupLocked() {
        starting = false;
        waitingForFrame = false;
        VirtualDisplay oldDisplay = display; display = null;
        ImageReader oldReader = reader; reader = null;
        MediaProjection oldProjection = projection; projection = null;
        HandlerThread oldThread = readerThread; readerThread = null; readerHandler = null;
        Bitmap oldLatest = latest; latest = null;
        frameSequence = 0;
        requestedAfterSequence = 0;
        if (oldDisplay != null) oldDisplay.release();
        if (oldReader != null) oldReader.close();
        if (oldProjection != null) {
            try { if (instance != null) oldProjection.unregisterCallback(instance.callback); } catch (RuntimeException ignored) { }
            oldProjection.stop();
        }
        if (oldLatest != null) oldLatest.recycle();
        if (oldThread != null) oldThread.quitSafely();
        LOCK.notifyAll();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "自动化屏幕捕获", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, ProjectionCaptureService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("自动化屏幕捕获运行中")
                .setContentText("仅在用户授权的本地投影会话中读取屏幕")
                .addAction(new Notification.Action.Builder(null, "停止捕获", stop).build())
                .setOngoing(true).build();
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        synchronized (LOCK) { lastError = "屏幕方向或配置已变化，请重新授权屏幕捕获。"; cleanupLocked(); }
        try { cn.xianhua.pocketkit.automation.AutomationRuntime.stop("屏幕方向变化，屏幕投影已停止"); } catch (RuntimeException ignored) { }
        stopSelf();
    }

    @Override public void onDestroy() {
        synchronized (LOCK) { cleanupLocked(); }
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
