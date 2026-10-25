package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 流式搬运：固定 64 KiB 一块，边搬边计数、边报进度、边看有没有取消。
 *
 * 「不一次读全包」是硬要求：条目可能是几十 GB，也可能是压缩炸弹，任何一处 buffer 分配都不能跟着条目大小走。
 */
public final class Pump {

    /** 64 KiB 是 SAF 与本地文件都够快的块大小；和 libarchive 读取循环保持一致。 */
    public static final int CHUNK = 64 * 1024;

    private Pump() {
    }

    /**
     * @param hint 该条目预计长度，只用于进度显示，可为 -1
     * @return 本次写出的字节数
     */
    public static long copy(InputStream in, OutputStream out, Budget budget,
                            ArchiveProgress progress, String label, long hint) throws IOException {
        byte[] chunk = new byte[CHUNK];
        long written = 0;
        while (true) {
            progress.checkCancelled();
            int read = in.read(chunk, 0, chunk.length);
            if (read < 0) {
                return written;
            }
            if (read == 0) {
                continue;
            }
            budget.add(read);
            out.write(chunk, 0, read);
            written += read;
            progress.onProgress(label, budget.entry(), hint);
        }
    }
}
