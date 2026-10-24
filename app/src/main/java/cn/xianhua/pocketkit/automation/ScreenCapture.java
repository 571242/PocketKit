package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Looper;
import android.os.Handler;
import android.view.Choreographer;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Screenshot bridge for AccessibilityService API 30+ and MediaProjection API 26-29. */
public final class ScreenCapture {
    private static final long MIN_REQUEST_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(350);
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock();
    private static final ExecutorService CALLBACKS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "PocketKitScreenshotCallback");
        thread.setDaemon(true);
        return thread;
    });
    private static long nextCaptureNanos;

    private ScreenCapture() { }

    public static Bitmap capture(AccessibilityService service, long timeoutMs) {
        if (service == null) throw new IllegalArgumentException("截图需要已连接的无障碍服务。");
        if (timeoutMs <= 0) throw new IllegalArgumentException("截图超时必须大于 0 毫秒。");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("截图必须从后台线程调用。");
        long deadline = deadline(timeoutMs);
        boolean locked = false;
        try {
            long lockNanos = remainingNanos(deadline);
            try { locked = CAPTURE_LOCK.tryLock(lockNanos, TimeUnit.NANOSECONDS); }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("截图已取消。", ex);
            }
            if (!locked) throw new IllegalStateException("截图请求等待超时，请重试。");
            waitForRateLimit(deadline);
            hideControls(deadline);
            long remainingMs = remainingMillis(deadline);
            Bitmap bitmap = android.os.Build.VERSION.SDK_INT >= 30
                    ? captureWithAccessibility(service, remainingMs)
                    : ProjectionCaptureService.awaitFrame(remainingMs);
            if (looksSecureBlack(bitmap)) {
                bitmap.recycle();
                throw new IllegalStateException("截图可能来自 FLAG_SECURE 安全页面，已拒绝继续识别。");
            }
            return bitmap;
        } finally {
            if (locked) {
                try { restoreControls(); }
                finally {
                    nextCaptureNanos = System.nanoTime() + MIN_REQUEST_INTERVAL_NANOS;
                    CAPTURE_LOCK.unlock();
                }
            }
        }
    }

    private static void waitForRateLimit(long deadline) {
        while (true) {
            long left = nextCaptureNanos - System.nanoTime();
            if (left <= 0) return;
            if (left > remainingNanos(deadline)) throw new IllegalStateException("截图请求过于频繁，请稍后重试。");
            sleepNanos(left, deadline);
        }
    }

    private static void sleepUntil(long deadline, long maxMillis) {
        long nanos = Math.min(TimeUnit.MILLISECONDS.toNanos(maxMillis), remainingNanos(deadline));
        if (nanos <= 0) throw new IllegalStateException("截图等待超时，请重试。");
        sleepNanos(nanos, deadline);
    }

    private static void sleepNanos(long nanos, long deadline) {
        try { TimeUnit.NANOSECONDS.sleep(Math.min(nanos, remainingNanos(deadline))); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("截图已取消。", ex);
        }
        if (System.nanoTime() >= deadline) throw new IllegalStateException("截图超时，请重试。");
    }

    private static long deadline(long timeoutMs) {
        long now = System.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return now + (duration < 0 || Long.MAX_VALUE - now < duration ? Long.MAX_VALUE - now : duration);
    }
    private static long remainingNanos(long deadline) {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new IllegalStateException("截图超时，请重试。");
        return value;
    }
    private static long remainingMillis(long deadline) {
        long nanos = remainingNanos(deadline);
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    @android.annotation.TargetApi(30)
    private static Bitmap captureWithAccessibility(AccessibilityService service, long timeoutMs) {
        AccessibilityServiceInfo info = service.getServiceInfo();
        if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0) {
            throw new IllegalStateException("无障碍服务当前没有屏幕捕获能力，请在系统无障碍设置中重新启用该服务后重试。");
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, CALLBACKS, new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                Bitmap copy = null;
                HardwareBuffer buffer = null;
                try {
                    buffer = screenshot.getHardwareBuffer();
                    if (buffer == null) throw new IllegalStateException("系统未返回截图缓冲区。");
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("系统截图缓冲区无法转换。");
                    try { copy = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                    finally { hardware.recycle(); }
                    if (copy == null) throw new IllegalStateException("系统截图无法转换为软件 Bitmap。");
                    if (!settled.compareAndSet(false, true)) { copy.recycle(); return; }
                    result.set(copy);
                } catch (RuntimeException ex) {
                    if (settled.compareAndSet(false, true)) error.set("无障碍截图失败：" + message(ex));
                    if (copy != null && !copy.isRecycled()) copy.recycle();
                } finally {
                    if (buffer != null) buffer.close();
                    latch.countDown();
                }
            }
            @Override public void onFailure(int errorCode) {
                if (settled.compareAndSet(false, true)) error.set("系统拒绝屏幕捕获（错误码 " + errorCode + "），可能是安全窗口、服务授权失效或截图频率限制，请返回目标页面后重试。");
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                settled.set(true);
                throw new IllegalStateException("无障碍截图超时，请重试。");
            }
        } catch (InterruptedException ex) {
            settled.set(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("无障碍截图已取消。", ex);
        }
        Bitmap bitmap = result.get();
        if (bitmap == null) throw new IllegalStateException(error.get() == null ? "无障碍截图失败，请重试。" : error.get());
        return bitmap;
    }

    private static boolean looksSecureBlack(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) return true;
        int samples = 0;
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 12)) {
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 12)) {
                if ((bitmap.getPixel(x, y) & 0x00ffffff) != 0) return false;
                samples++;
            }
        }
        return samples > 0;
    }

    private static void hideControls(long deadline) {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        main.post(() -> {
            try {
                setCaptureHidden(true);
                // Two UI frames let the visibility traversal reach WindowManager before taking pixels.
                Choreographer.getInstance().postFrameCallback(first ->
                        Choreographer.getInstance().postFrameCallback(second -> applied.countDown()));
            } catch (RuntimeException ex) { failure.set(ex); applied.countDown(); }
        });
        try {
            if (!applied.await(remainingNanos(deadline), TimeUnit.NANOSECONDS))
                throw new IllegalStateException("隐藏悬浮控件超时，请重试。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("截图已取消。", ex);
        }
        if (failure.get() != null) throw new IllegalStateException("无法隐藏悬浮控件。", failure.get());
    }

    private static void restoreControls() {
        new Handler(Looper.getMainLooper()).post(() -> setCaptureHidden(false));
    }

    private static void setCaptureHidden(boolean hidden) {
        AutomationOverlayService.setCaptureHidden(hidden);
        AutomationPickerService.setCaptureHidden(hidden);
        AutomationRecorderService.setCaptureHidden(hidden);
    }

    private static String message(Throwable ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
