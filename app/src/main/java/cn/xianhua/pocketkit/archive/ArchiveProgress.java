package cn.xianhua.pocketkit.archive;

import java.io.IOException;

/** 归档任务的进度与取消通道。回调都从工作线程来，由上层自己切主线程。 */
public interface ArchiveProgress {

    /** 已取消时抛这个；上层据此区分「用户取消」和「真的出错」。 */
    final class Cancelled extends IOException {
        private static final long serialVersionUID = 1L;

        public Cancelled() {
            super("已取消");
        }
    }

    /**
     * @param label 当前条目或阶段，给用户看的
     * @param done  已完成字节数；总量未知时传 -1
     * @param total 总量；未知传 -1
     */
    void onProgress(String label, long done, long total);

    boolean isCancelled();

    default void checkCancelled() throws Cancelled {
        if (isCancelled()) {
            throw new Cancelled();
        }
    }
}
