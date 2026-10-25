package cn.xianhua.pocketkit.filetools;

/**
 * 协作式取消：每个可能跑超过 50 ms 的循环都调用 {@link #check()}。
 *
 * <p>同时看中断标志——{@code BaseToolActivity.onDestroy()} 会 {@code shutdownNow()} 线程池，
 * 只靠一个布尔开关会让"页面已经关掉但还在读文件"继续跑下去。
 */
public final class Cancel {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    public void check() throws Stop {
        if (isCancelled()) {
            throw new Stop();
        }
    }

    /** 继承 IOException，让取消能穿过只声明 IOException 的流辅助方法。 */
    public static final class Stop extends java.io.IOException {
        private static final long serialVersionUID = 1L;

        public Stop() {
            super("已取消");
        }
    }
}
