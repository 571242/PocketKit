package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 把 {@link ArchiveReader} 包成 InputStream，好让两条解压路径共用同一套搬运/计数/取消逻辑。
 *
 * 只按 64 KiB 读：绝不能按条目声明的大小去分配 buffer，那是压缩炸弹的入口。
 */
final class EntryInputStream extends InputStream {

    private final ArchiveReader reader;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(Pump.CHUNK);
    private byte[] chunk;
    private int position;
    private int limit;
    private boolean ended;

    EntryInputStream(ArchiveReader reader) {
        this.reader = reader;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (position >= limit) {
            if (ended) {
                return -1;
            }
            int read = reader.readData(buffer);
            if (read <= 0) {
                ended = true;
                return -1;
            }
            // JNI 读完后 position 指向已读末尾；把数据原样拷出来给调用方。
            buffer.position(0);
            buffer.limit(read);
            if (chunk == null) {
                chunk = new byte[Pump.CHUNK];
            }
            buffer.get(chunk, 0, read);
            position = 0;
            limit = read;
        }
        int copy = Math.min(length, limit - position);
        System.arraycopy(chunk, position, target, offset, copy);
        position += copy;
        return copy;
    }

    /** 条目切换时重置，让下一个条目重新读。 */
    void resetEntry() {
        position = 0;
        limit = 0;
        ended = false;
    }

    @Override public void close() {
        // 条目流不拥有 reader，关由调用方负责。
    }
}
