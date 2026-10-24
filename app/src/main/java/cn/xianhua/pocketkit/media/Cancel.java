package cn.xianhua.pocketkit.media;

/**
 * Cooperative cancellation for long media jobs.
 *
 * <p>Every loop that can run longer than roughly 50 ms calls {@link #check()}, so a cancel
 * request lands within one step instead of after the whole file is done.
 */
public final class Cancel {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Aborts the job. Call inside loops; the message is what the user reads. */
    public void check() throws Stop {
        if (cancelled) {
            throw new Stop();
        }
    }

    /**
     * Nothing has been written to the target yet when this is thrown.
     *
     * <p>Extends {@link java.io.IOException} so a cancel can travel out through stream helpers that
     * already declare IOException, instead of every copy loop needing a second catch clause.
     */
    public static final class Stop extends java.io.IOException {
        public Stop() {
            super("已取消，未写出文件");
        }
    }
}
