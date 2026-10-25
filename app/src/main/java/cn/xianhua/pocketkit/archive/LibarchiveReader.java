package cn.xianhua.pocketkit.archive;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/**
 * libarchive 的 Java 侧适配：7z / RAR（含 RAR5）/ tar 家族 / cab / iso。
 *
 * 关键点（都对着 1.1.6 的 JNI 源码核过）：
 *  - {@code readData(ByteBuffer)} 把 buffer 的 position..limit 当可用窗口，读完把 position
 *    前进到已读长度；所以复用同一个 direct buffer 时每次都先 clear()，返回 0 表示条目读完。
 *  - {@code readDataIntoFd} 直接往 fd 里倒、中间没有任何总量控制，压缩炸弹能一路写满存储，
 *    所以这里不用它，一律自己按 64 KiB 读、自己计数。
 *  - handle 不是线程安全的：打开、遍历、读取、关闭必须在同一个线程，上层用单线程 executor 保证。
 */
public final class LibarchiveReader implements ArchiveReader {

    private final long handle;
    private final ParcelFileDescriptor pinned;
    private final File staged;
    private boolean closed;

    private LibarchiveReader(long handle, ParcelFileDescriptor pinned, File staged) {
        this.handle = handle;
        this.pinned = pinned;
        this.staged = staged;
    }

    /**
     * @param pinned     已打开的可读文档；reader 负责关闭它
     * @param staged     为不支持 seek 的来源落下的缓存文件，关闭时删除；可为 null
     * @param passphrase 为空表示没有密码
     */
    public static LibarchiveReader open(ParcelFileDescriptor pinned, File staged, byte[] passphrase)
            throws IOException {
        long handle = Archive.readNew();
        try {
            Archive.setCharset(handle, StandardCharsets.UTF_8.name().getBytes(StandardCharsets.UTF_8));
            Archive.readSupportFilterAll(handle);
            Archive.readSupportFormatAll(handle);
            if (passphrase != null && passphrase.length > 0) {
                Archive.readAddPassphrase(handle, passphrase);
            }
            Archive.readOpenFd(handle, pinned.getFd(), Pump.CHUNK);
        } catch (ArchiveException | RuntimeException ex) {
            freeQuietly(handle);
            closeQuietly(pinned);
            deleteQuietly(staged);
            throw new IOException(describe(ex), ex);
        }
        return new LibarchiveReader(handle, pinned, staged);
    }

    @Override public String formatName() {
        try {
            byte[] name = Archive.formatName(handle);
            return name == null ? "" : new String(name, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    @Override public ArchiveReader.Entry next() throws IOException {
        long entry = Archive.readNextHeader(handle);
        return entry == 0 ? null : new NativeEntry(entry);
    }

    @Override public int readData(ByteBuffer buffer) throws IOException {
        buffer.clear();
        Archive.readData(handle, buffer);
        return buffer.position();
    }

    @Override public void skipEntryData() throws IOException {
        Archive.readDataSkip(handle);
    }

    @Override public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Archive.readClose(handle);
        } catch (ArchiveException | RuntimeException ignored) {
            // 关闭失败不影响已经写出去的结果。
        }
        freeQuietly(handle);
        closeQuietly(pinned);
        deleteQuietly(staged);
    }

    /** 把不适合 seek 的来源（云盘给的流）先流式复制到缓存，再交给 libarchive。 */
    public static File stage(InputStream in, File cacheDir, ArchiveProgress progress, String label)
            throws IOException {
        File target = new File(cacheDir, "stage-" + System.currentTimeMillis() + ".arc");
        byte[] chunk = new byte[Pump.CHUNK];
        long written = 0;
        try (FileOutputStream out = new FileOutputStream(target)) {
            while (true) {
                progress.checkCancelled();
                int read = in.read(chunk, 0, chunk.length);
                if (read < 0) {
                    break;
                }
                if (written > ArchiveLimits.MAX_TOTAL_BYTES - read) throw new IOException("源压缩包超过 4 GiB 缓存上限");
                out.write(chunk, 0, read);
                written += read;
                progress.onProgress(label, written, -1);
            }
            out.getFD().sync();
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(target);
            throw ex;
        }
        return target;
    }

    private static void freeQuietly(long handle) {
        try {
            Archive.free(handle);
        } catch (ArchiveException | RuntimeException ignored) {
            // 释放失败没有可补救的动作。
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // fd 关闭失败会在进程退出时由系统回收。
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = ex.getClass().getSimpleName();
        }
        if (message.contains("Unrecognized archive format")) {
            return "认不出这个包：不是支持的格式，或者文件已经损坏";
        }
        if (message.toLowerCase(java.util.Locale.ROOT).contains("passphrase")) {
            return "这个包需要密码，或者密码不对；加密的 7z / RAR 目前不能解";
        }
        return message;
    }

    /** 条目视图：只暴露解压需要的判断，路径名原样给出，清洗交给 {@link EntryNames}。 */
    private static final class NativeEntry implements ArchiveReader.Entry {
        private final String pathname;
        private final int filetype;
        private final long size;
        private final boolean symlink;
        private final boolean hardlink;
        private final boolean encrypted;

        NativeEntry(long pointer) {
            String utf8 = ArchiveEntry.pathnameUtf8(pointer);
            if (utf8 == null) {
                byte[] bytes = ArchiveEntry.pathname(pointer);
                utf8 = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            }
            this.pathname = utf8;
            this.filetype = ArchiveEntry.filetype(pointer);
            this.size = ArchiveEntry.size(pointer);
            byte[] link = ArchiveEntry.symlink(pointer);
            this.symlink = link != null && link.length > 0;
            byte[] hard = ArchiveEntry.hardlink(pointer);
            this.hardlink = hard != null && hard.length > 0;
            this.encrypted = ArchiveEntry.isDataEncrypted(pointer);
        }

        @Override public String pathname() {
            return pathname;
        }

        @Override public boolean isDirectory() {
            return (filetype & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFDIR;
        }

        @Override public boolean isSymlink() {
            return symlink || (filetype & ArchiveEntry.AE_IFMT) == ArchiveEntry.AE_IFLNK;
        }

        @Override public boolean isHardlink() {
            return hardlink;
        }

        @Override public long size() {
            return size;
        }

        @Override public boolean isEncrypted() {
            return encrypted;
        }
    }
}
