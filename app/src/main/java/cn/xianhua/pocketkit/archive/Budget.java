package cn.xianhua.pocketkit.archive;

import java.io.IOException;

/**
 * 边写边数的字节预算。压缩包头部写的条目大小和总量都不可信，只有真的写出去了多少才算数。
 *
 * 一个 Budget 对应一次解压任务：跨条目累计 total，条目切换时 beginEntry。
 * 越界抛 {@link Exceeded}，调用方必须把它当致命错误处理（停下 + 清理），不能当成「单条失败」。
 */
public final class Budget {

    /** 超上限。故意单独一类，避免被「这个条目坏了，继续下一个」的兜底逻辑吞掉。 */
    public static final class Exceeded extends IOException {
        private static final long serialVersionUID = 1L;

        public Exceeded(String message) {
            super(message);
        }
    }

    private final ArchiveLimits limits;
    private final long archiveBytes;
    private long total;
    private long entry;
    private String path = "";

    public Budget(ArchiveLimits limits, long archiveBytes) {
        this.limits = limits;
        this.archiveBytes = archiveBytes;
    }

    /** 压缩任务用：不限量，只计数。 */
    public static Budget unbounded() {
        return new Budget(null, -1);
    }

    public void beginEntry(String entryPath) {
        this.path = entryPath == null ? "" : entryPath;
        this.entry = 0;
    }

    /** 每写出一块就调用一次。 */
    public void add(long bytes) throws Exceeded {
        if (bytes <= 0) {
            return;
        }
        entry += bytes;
        total += bytes;
        if (limits == null) {
            return;
        }
        try {
            limits.checkEntrySize(path, entry);
            limits.checkTotal(total, archiveBytes);
        } catch (IOException ex) {
            throw new Exceeded(ex.getMessage());
        }
    }

    public long entry() {
        return entry;
    }

    public long total() {
        return total;
    }
}
