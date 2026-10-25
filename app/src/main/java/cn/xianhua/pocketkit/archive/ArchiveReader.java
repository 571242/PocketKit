package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * libarchive 读取器的最小抽象。真实实现是 {@link LibarchiveReader}（JNI），
 * 测试实现放在测试目录里，这样「读一块 → 计数 → 写盘 → 取消/超限」这段逻辑能脱离 JNI 被验证。
 *
 * 为什么必须自己按块读：库自带的 readDataIntoFd 直接往 fd 里倒，中间没有总量控制，
 * 遇到压缩炸弹会把手机写满才报错。
 */
public interface ArchiveReader {

    /** 容器名，如 "7-Zip"、"RAR 5"、"POSIX tar"。 */
    String formatName();

    /** 下一个条目；返回 null 表示包读完。 */
    Entry next() throws IOException;

    /**
     * 读当前条目的一块数据。实现必须按 JNI 契约工作：调用前 buffer 的
     * position..limit 是可用窗口，调用后 position 前进到已读长度。
     *
     * @return 本块字节数，0 表示当前条目结束
     */
    int readData(ByteBuffer buffer) throws IOException;

    /** 丢弃当前条目剩余数据，跳到下一个包头。 */
    void skipEntryData() throws IOException;

    void close();

    interface Entry {
        /** 原始路径，未经清洗；可能含 ../ 或绝对路径。 */
        String pathname();

        boolean isDirectory();

        boolean isSymlink();

        boolean isHardlink();

        /** 条目声明的大小，只用于进度显示；不可信。 */
        long size();

        boolean isEncrypted();
    }
}
