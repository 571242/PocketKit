package cn.xianhua.pocketkit.device;

import java.util.concurrent.atomic.AtomicBoolean;

/** One live scan or delete operation; starting another invalidates the old result. */
final class ScanSession {
    private Token current;

    synchronized Token start() {
        cancel();
        current = new Token();
        return current;
    }

    synchronized void cancel() {
        if (current != null) current.cancelled.set(true);
        current = null;
    }

    synchronized boolean isCurrent(Token token) {
        return token != null && current == token && !token.isCancelled();
    }

    static final class Token {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        boolean isCancelled() {
            return cancelled.get() || Thread.currentThread().isInterrupted();
        }
    }
}
