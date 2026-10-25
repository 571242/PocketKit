package cn.xianhua.pocketkit.archive;

import java.io.IOException;

/**
 * 解压边界。压缩包里的数字全是对方给的，不能当成承诺：条目数、单条目大小、总量和压缩比
 * 都必须在写盘过程中边写边数，超了就停。
 *
 * 这些数字是经验值，不是物理极限：改大只影响「能解多大的包」，不会放宽安全判断逻辑。
 */
public final class ArchiveLimits {

    /** 条目数上限：挡住「几百万个空目录」把 SAF 和 UI 拖死。 */
    public static final int MAX_ENTRIES = 20_000;
    /** 单条目上限：手机存储与 SAF 实际写不动的量级。 */
    public static final long MAX_ENTRY_BYTES = 2L << 30;
    /** 总解压量上限。 */
    public static final long MAX_TOTAL_BYTES = 4L << 30;
    /** 压缩比上限与生效下限：小包本来就容易有高压缩比，只在超过 64 MiB 之后按比例判定。 */
    public static final int MAX_RATIO = 300;
    public static final long RATIO_FLOOR_BYTES = 64L << 20;

    public static final ArchiveLimits DEFAULTS =
            new ArchiveLimits(MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES, MAX_RATIO, RATIO_FLOOR_BYTES);

    private final int entries;
    private final long entryBytes;
    private final long totalBytes;
    private final int ratio;
    private final long ratioFloor;

    /** 测试用：注入很小的上限，才能在不写几百 MB 的前提下验证边界真的生效。 */
    ArchiveLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRatio) {
        this(maxEntries, maxEntryBytes, maxTotalBytes, maxRatio, RATIO_FLOOR_BYTES);
    }

    ArchiveLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRatio,
                  long ratioFloorBytes) {
        this.entries = maxEntries;
        this.entryBytes = maxEntryBytes;
        this.totalBytes = maxTotalBytes;
        this.ratio = maxRatio;
        this.ratioFloor = ratioFloorBytes;
    }

    public int maxEntries() {
        return entries;
    }

    /** 条目数超上限：和字节上限一样是致命错误，不能当作「这个条目跳过」。 */
    public void checkEntryCount(int count) throws Budget.Exceeded {
        if (count > entries) {
            throw new Budget.Exceeded("条目太多（" + count + " 个，上限 " + entries + " 个），已停止。"
                    + "这不是完整解压；换个包，或者自己挑需要的文件。");
        }
    }

    public void checkEntrySize(String path, long bytes) throws IOException {
        if (bytes > entryBytes) {
            throw new IOException("单个条目超过上限（" + Size.text(entryBytes) + "）：" + path);
        }
    }

    /** 边写边校验总量；archiveBytes <= 0 时跳过压缩比判断。 */
    public void checkTotal(long total, long archiveBytes) throws IOException {
        if (total > totalBytes) {
            throw new IOException("解压总量超过上限（" + Size.text(totalBytes) + "），已停止并清理本次临时文件。");
        }
        if (archiveBytes > 0 && total > ratioFloor && total / archiveBytes > ratio) {
            throw new IOException("压缩比异常（已解 " + Size.text(total) + " / 包 " + Size.text(archiveBytes)
                    + "），疑似压缩炸弹，已停止并清理本次临时文件。");
        }
    }

    /** 只在这里用的字节格式化，避免 archive 包反向依赖 UI 层。 */
    static final class Size {
        private Size() {
        }

        static String text(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            double value = bytes / 1024.0;
            if (value < 1024) {
                return String.format(java.util.Locale.US, "%.1f KB", value);
            }
            value /= 1024.0;
            if (value < 1024) {
                return String.format(java.util.Locale.US, "%.1f MB", value);
            }
            return String.format(java.util.Locale.US, "%.2f GB", value / 1024.0);
        }
    }
}
