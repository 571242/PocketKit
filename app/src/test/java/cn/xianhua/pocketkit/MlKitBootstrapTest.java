package cn.xianhua.pocketkit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Narrow contract test for lazy, repeat-safe, concurrent ML Kit initialization. */
public final class MlKitBootstrapTest {
    public static void main(String[] args) throws Exception {
        MlKitBootstrap.Once failed = new MlKitBootstrap.Once();
        AtomicInteger retries = new AtomicInteger();
        try {
            failed.ensure(null, context -> {
                retries.incrementAndGet();
                throw new IllegalStateException("expected test failure");
            });
            throw new AssertionError("initializer failure was swallowed");
        } catch (IllegalStateException expected) {
            failed.ensure(null, context -> retries.incrementAndGet());
        }
        if (retries.get() != 2) {
            throw new AssertionError("expected failed initialization to be retried");
        }

        MlKitBootstrap.Once once = new MlKitBootstrap.Once();
        AtomicInteger calls = new AtomicInteger();
        int workers = 24;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    once.ensure(null, context -> {
                        calls.incrementAndGet();
                        Thread.yield();
                    });
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        once.ensure(null, context -> calls.incrementAndGet());
        if (calls.get() != 1) {
            throw new AssertionError("expected one initializer call, got " + calls.get());
        }
        System.out.println("MlKitBootstrapTest PASS: first, repeated, and 24 concurrent calls initialized once");
    }
}
