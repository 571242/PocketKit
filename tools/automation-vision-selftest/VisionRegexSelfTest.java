import cn.xianhua.pocketkit.automation.VisionRegex;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class VisionRegexSelfTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        long budget = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        check(VisionRegex.find(Pattern.compile("buy\\s+now", Pattern.CASE_INSENSITIVE), "BUY now", budget), "normal match");
        check(!VisionRegex.find(Pattern.compile("ticket"), "ordinary text", budget), "not found");
        char[] chars = new char[4096]; java.util.Arrays.fill(chars, 'a');
        AtomicReference<String> result = new AtomicReference<>();
        Thread timed = new Thread(() -> {
            try { VisionRegex.find(Pattern.compile("(a+)+b"), new String(chars), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)); result.set("unexpected"); }
            catch (IllegalStateException expected) { result.set("bounded"); }
        });
        timed.start(); timed.join(1500);
        check(!timed.isAlive(), "backtracking worker really exited");
        check("bounded".equals(result.get()), "deadline or stack guard was enforced");
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        Thread stop = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { VisionRegex.find(Pattern.compile("a+"), "aaa", System.nanoTime() + TimeUnit.SECONDS.toNanos(1)); }
            catch (IllegalStateException expected) { cancelled.set(Thread.currentThread().isInterrupted()); }
        });
        stop.start(); stop.join(500);
        check(Boolean.TRUE.equals(cancelled.get()) && !stop.isAlive(), "interrupt preserves cancellation and terminates");
        System.out.println("VisionRegexSelfTest PASS checks=" + checks + "; no regex worker left running");
    }
}
