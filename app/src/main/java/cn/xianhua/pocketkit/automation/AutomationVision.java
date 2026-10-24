package cn.xianhua.pocketkit.automation;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Local OCR, accessibility-node text and template matching facade. */
public final class AutomationVision {
    private static final int MAX_NODES = 1000;
    private static final int MAX_TEXT = 4096;
    private static final ExecutorService ML_CALLBACKS = Executors.newCachedThreadPool(r -> daemon(r, "PocketKitMlKitCallback"));

    private AutomationVision() { }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }

    public static cn.xianhua.pocketkit.core.automation.Match findText(
            AccessibilityService service, String query, String source, String matchMode,
            Rect region, long timeoutMs) {
        requireBackground();
        if (service == null) throw new IllegalArgumentException("文字识别需要无障碍服务。");
        validateQuery(query);
        requireTimeout(timeoutMs);
        String actualSource = source == null || source.trim().isEmpty() ? "ocr" : source.trim().toLowerCase(Locale.ROOT);
        String actualMode = matchMode == null || matchMode.trim().isEmpty() ? "contains" : matchMode.trim().toLowerCase(Locale.ROOT);
        if (!actualSource.equals("ocr") && !actualSource.equals("nodes")) throw new IllegalArgumentException("文字来源只能是 ocr 或 nodes。");
        validateMode(actualMode);
        long deadline = deadline(timeoutMs);
        return actualSource.equals("nodes") ? findInNodes(service, query, actualMode, region, deadline)
                : findInOcr(service, query, actualMode, region, deadline);
    }

    public static String readText(AccessibilityService service, Rect region, long timeoutMs) {
        requireBackground();
        if (service == null) throw new IllegalArgumentException("文字识别需要无障碍服务。");
        requireTimeout(timeoutMs);
        return ocrText(service, region, deadline(timeoutMs));
    }

    public static cn.xianhua.pocketkit.core.automation.Match findImage(
            AccessibilityService service, Bitmap template, Rect region, double threshold, long timeoutMs) {
        requireBackground();
        if (service == null) throw new IllegalArgumentException("图片匹配需要无障碍服务。");
        if (template == null || template.isRecycled()) throw new IllegalArgumentException("图片模板无效。");
        requireTimeout(timeoutMs);
        long deadline = deadline(timeoutMs);
        Bitmap frame = ScreenCapture.capture(service, remainingMillis(deadline));
        try { return cn.xianhua.pocketkit.core.automation.TemplateMatcher.find(frame, template, region, threshold, remainingMillis(deadline)); }
        finally { if (!frame.isRecycled()) frame.recycle(); }
    }

    private static String ocrText(AccessibilityService service, Rect region, long deadline) {
        Bitmap frame = ScreenCapture.capture(service, remainingMillis(deadline));
        Bitmap image = null;
        try {
            Rect actual = clipRegion(region, frame.getWidth(), frame.getHeight());
            image = crop(frame, actual);
            Text result = recognize(image, frame, service, deadline);
            return joinLines(result);
        } catch (RuntimeException ex) {
            if (image == null && !frame.isRecycled()) frame.recycle();
            throw ex;
        }
    }

    private static cn.xianhua.pocketkit.core.automation.Match findInOcr(
            AccessibilityService service, String query, String mode, Rect region, long deadline) {
        Bitmap frame = ScreenCapture.capture(service, remainingMillis(deadline));
        Bitmap image = null;
        try {
            Rect actual = clipRegion(region, frame.getWidth(), frame.getHeight());
            image = crop(frame, actual);
            int imageWidth = image.getWidth(), imageHeight = image.getHeight();
            Text result = recognize(image, frame, service, deadline);
            Pattern pattern = "regex".equals(mode) ? compileRegex(query) : null;
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    checkBudget(deadline);
                    String text = line.getText() == null ? "" : line.getText();
                    if (matches(text, query, mode, pattern, deadline)) {
                        Rect box = line.getBoundingBox();
                        if (box == null) box = new Rect(0, 0, imageWidth, imageHeight);
                        return new cn.xianhua.pocketkit.core.automation.Match(true,
                                actual.left + box.left, actual.top + box.top,
                                actual.left + box.right, actual.top + box.bottom, text, 1d);
                    }
                }
            }
            return cn.xianhua.pocketkit.core.automation.Match.notFound();
        } catch (RuntimeException ex) {
            if (image == null && !frame.isRecycled()) frame.recycle();
            throw ex;
        }
    }

    /** Task owns both image and recognizer; timeout never recycles an in-flight ML Kit input. */
    private static Text recognize(Bitmap image, Bitmap frame, AccessibilityService service, long deadline) {
        MlTaskState state = new MlTaskState(image, frame);
        boolean completionAttached = false;
        try {
            cn.xianhua.pocketkit.MlKitBootstrap.ensureInitialized(service.getApplicationContext());
            state.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            Task<Text> task = state.recognizer.process(InputImage.fromBitmap(image, 0));
            task.addOnCompleteListener(ML_CALLBACKS, completed -> {
                if (completed.isSuccessful()) state.result.set(completed.getResult());
                else state.error.set(completed.getException());
                state.finish();
            });
            completionAttached = true;
            long remaining = remainingMillis(deadline);
            if (!state.done.await(remaining, TimeUnit.MILLISECONDS)) {
                state.timedOut.set(true);
                throw new IllegalStateException("OCR 超时；后台任务完成后才会释放识别资源。");
            }
            if (state.error.get() != null) throw new IllegalStateException("OCR 失败：" + message(state.error.get()), state.error.get());
            if (state.result.get() == null) throw new IllegalStateException("OCR 未返回结果。");
            return state.result.get();
        } catch (InterruptedException ex) {
            state.timedOut.set(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR 已取消；后台任务完成后才会释放识别资源。", ex);
        } catch (RuntimeException ex) {
            // A synchronous process/attach failure has no completion callback to own cleanup.
            if (!completionAttached) state.finish();
            throw ex;
        }
    }

    private static final class MlTaskState {
        final Bitmap image, frame;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicReference<Text> result = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        TextRecognizer recognizer;
        MlTaskState(Bitmap image, Bitmap frame) { this.image = image; this.frame = frame; }
        void finish() {
            if (!finished.compareAndSet(false, true)) return;
            if (recognizer != null) recognizer.close();
            if (image != frame && !image.isRecycled()) image.recycle();
            if (!frame.isRecycled()) frame.recycle();
            done.countDown();
        }
    }

    private static cn.xianhua.pocketkit.core.automation.Match findInNodes(
            AccessibilityService service, String query, String mode, Rect region, long deadline) {
        Pattern pattern = "regex".equals(mode) ? compileRegex(query) : null;
        Rect requested = region == null ? null : new Rect(region);
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return cn.xianhua.pocketkit.core.automation.Match.notFound();
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        int seen = 0;
        try {
            while (!nodes.isEmpty() && seen++ < MAX_NODES) {
                if ((seen & 31) == 0) checkBudget(deadline);
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    if (node.isVisibleToUser() && !node.isPassword()) {
                        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                        if (requested == null || Rect.intersects(requested, bounds)) {
                            CharSequence value = node.getText();
                            if (value == null || value.length() == 0) value = node.getContentDescription();
                            String text = value == null ? "" : value.toString();
                            if (matches(text, query, mode, pattern, deadline)) {
                                return new cn.xianhua.pocketkit.core.automation.Match(true,
                                        bounds.left, bounds.top, bounds.right, bounds.bottom, text, 1d);
                            }
                        }
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                } finally { node.recycle(); }
            }
            if (seen >= MAX_NODES) throw new IllegalStateException("无障碍节点数量超过限制，已停止识别。");
            return cn.xianhua.pocketkit.core.automation.Match.notFound();
        } finally { while (!nodes.isEmpty()) nodes.removeFirst().recycle(); }
    }

    private static boolean matches(String text, String query, String mode, Pattern pattern, long deadline) {
        if (text == null || text.length() > MAX_TEXT) return false;
        if ("exact".equals(mode)) return text.equals(query);
        if (!"regex".equals(mode)) return text.contains(query);
        return VisionRegex.find(pattern, text, deadline);
    }

    private static Pattern compileRegex(String query) {
        try { return Pattern.compile(query); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("正则表达式无效。", ex); }
    }

    private static String joinLines(Text result) {
        StringBuilder output = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
            String text = line.getText();
            if (text != null && !text.trim().isEmpty()) { if (output.length() > 0) output.append('\n'); output.append(text.trim()); }
        }
        return output.toString();
    }

    private static Rect clipRegion(Rect region, int width, int height) {
        Rect actual = region == null ? new Rect(0, 0, width, height) : new Rect(region);
        if (!actual.intersect(0, 0, width, height) || actual.isEmpty()) throw new IllegalArgumentException("ROI 不在截图范围内。");
        return actual;
    }
    private static Bitmap crop(Bitmap frame, Rect region) {
        if (region.left == 0 && region.top == 0 && region.width() == frame.getWidth() && region.height() == frame.getHeight()) return frame;
        return Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height());
    }
    private static void validateQuery(String query) { if (query == null || query.isEmpty() || query.length() > 256) throw new IllegalArgumentException("识别查询必须为 1 到 256 个字符。"); }
    private static void validateMode(String mode) { if (!"contains".equals(mode) && !"exact".equals(mode) && !"regex".equals(mode)) throw new IllegalArgumentException("匹配模式只能是 contains、exact 或 regex。"); }
    private static void requireTimeout(long timeoutMs) { if (timeoutMs <= 0) throw new IllegalArgumentException("识别超时必须大于 0 毫秒。"); }
    private static void requireBackground() { if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("视觉识别必须从后台线程调用。"); }
    private static long deadline(long timeoutMs) { long now = System.nanoTime(), duration = TimeUnit.MILLISECONDS.toNanos(timeoutMs); return duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration; }
    private static long remainingMillis(long deadline) { long left = deadline - System.nanoTime(); if (left <= 0) throw new IllegalStateException("视觉任务超时。"); return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)); }
    private static void checkBudget(long deadline) { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("文字识别已取消。"); if (deadline - System.nanoTime() <= 0) throw new IllegalStateException("文字识别超时。"); }
    private static String message(Throwable ex) { return ex == null || ex.getMessage() == null ? (ex == null ? "unknown" : ex.getClass().getSimpleName()) : ex.getMessage(); }
}
