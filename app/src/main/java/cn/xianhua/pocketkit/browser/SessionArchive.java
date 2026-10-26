package cn.xianhua.pocketkit.browser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 已停止会话的加密档案：目录先 ZIP，再整体 AES/GCM，调用方自己保管密钥。
 *
 * <p>文件布局（纯字节序列）：
 * <pre>
 * 头部：'P' 'K' 'S' 'A' | 版本 1 字节 | IV 长度 1 字节 | 12 字节随机 IV      ← 同时作为 AAD
 * 记录：4 字节大端明文长度 | 等长密文 | 16 字节 GCM tag | …                    ← 重复 0..n 次
 * </pre>
 * 头部只放魔数、版本和 IV，<b>不含任何文件名</b>：ZIP 内所有路径都随载荷一起加密，
 * 档案里看不到 Cookies、IndexedDB 之类的名字。
 *
 * <p>为什么分记录而不是“一整块 GCM”：JDK 的 GCM 解密会把整条消息缓冲在内存里，
 * 一条 64MB 的密文就要占上百 MB 堆。"流式"必须是多记录的：每条记录 ≤ {@link #RECORD_BYTES}
 * 且用 {@code 基础IV[11] || 计数器} 区分，内存占用与档案总大小脱钩。
 * 每条记录都是完整的 GCM 消息，<b>必须读到末尾 tag 校验通过</b>才算成功；
 * 若流在记录中途或 tag 之前结束，一定抛异常——"读够 zip 就收工"会立刻暴露。
 *
 * <p>密钥由调用方从 AndroidKeyStore 取出后传入，本类不缓存、不落盘、不打印；
 * 任何异常信息都不包含密钥内容。
 *
 * <p>默认不保存 WebView 的纯缓存目录与锁文件（见 {@link #SKIP_DIRECTORIES}、{@link #SKIP_FILES}），
 * 网站数据一律保留：Cookies 及其 {@code -wal}/{@code -shm}、Local Storage、IndexedDB、
 * Session Storage、Service Worker、blob_storage、Web Data 等都不做过滤。
 *
 * <p>容量：明文（ZIP 本体与解压后的文件）都受 {@link #MAX_BYTES}、条目数上限约束；
 * 打包与解包全程流式，不会把 64MB 档案整体读进内存。
 */
public final class SessionArchive {

    /** 明文总量上限：内存密封块、目录打包总量、解包解压总量都用它。 */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final byte[] MAGIC = {'P', 'K', 'S', 'A'};
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 1 + IV_BYTES;
    /** 单条记录的最大明文长度：GCM 解密的内存量由此封顶，而不是档案总量。 */
    private static final int RECORD_BYTES = 1024 * 1024;
    /** 条目上限：普通 WebView profile 远低于此，超过只可能是构造出来的档案。 */
    private static final int MAX_ENTRIES = 4096;
    /** 加密档案允许比明文多出的余量：ZIP 开销、每记录 tag/长度、档案头。 */
    private static final long MAX_ARCHIVE_BYTES = MAX_BYTES + 4L * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    /*
     * 只在 profile 根目录或 Default 的直属目录跳过引擎缓存，避免误删网站自己的同名目录。
     * 而站点数据（Local Storage、IndexedDB、Service Worker 的 CacheStorage 等）名字不在此列。
     */
    private static final Set<String> SKIP_DIRECTORIES = names(
            "cache", "code cache", "gpucache", "dawncache", "dawngraphitecache",
            "dawnwebgpucache", "graphitedawncache", "shadercache", "grshadercache",
            "component_crx_cache");

    /*
     * 锁文件：SingletonLock 在 Linux 上还是符号链接，必须在符号链接检查之前跳过，
     * 否则打包会因为一个本就该丢弃的锁而整体失败。LEVELDB 的 LOCK 由引擎自己重建。
     */
    private static final Set<String> SKIP_FILES = names(
            "singletonlock", "singletonsocket", "singletoncookie", "lock", "lockfile");

    private SessionArchive() {
    }

    /**
     * 把 {@code source} 目录加密写入 {@code encryptedFile}。
     *
     * <p>先写到同级的临时文件，成功后原子改名，所以失败不会覆盖已有旧档案。
     * 不跟随、不接受符号链接；不在返回前改动 {@code source} 里的任何内容。
     */
    public static void pack(Path source, Path encryptedFile, SecretKey key) throws Exception {
        if (source == null || encryptedFile == null) {
            throw new IllegalArgumentException("会话目录与档案路径都不能为空");
        }
        Path root = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("拒绝符号链接：" + root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("会话目录不存在或不是普通目录：" + root);
        }
        Path target = encryptedFile.toAbsolutePath().normalize();
        Path folder = target.getParent();
        if (folder == null) throw new IOException("档案路径无效：" + target);
        if (!Files.isDirectory(folder)) throw new IOException("档案所在目录不存在：" + folder);
        // 档案写进被保存的目录里会把自己的密文当成会话数据一起打进去。
        if (target.startsWith(root)) throw new IOException("档案不能写进正在保存的会话目录");
        if (Files.isSymbolicLink(target)) throw new IOException("档案路径不能是符号链接：" + target);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("档案路径已被目录占用：" + target);
        }

        Path temp = Files.createTempFile(folder, ".pksa-pack-", ".part");
        boolean moved = false;
        try {
            writeArchive(root, temp, key);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            moveReplacing(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 残留的只是密文临时文件，不影响旧档案，也不再掩盖真正的失败原因。
                }
            }
        }
    }

    /**
     * 把 {@code encryptedFile} 解开到 {@code destination}。
     *
     * <p>先在 destination 同级建一个暂存目录：先把整个载荷解密成临时 ZIP（这一步读到 EOF，
     * 每条记录的 GCM tag 都必须校验通过），再解压到暂存树，最后整目录改名成 destination。
     * 因此 destination 出现时内容已经完整认证过；任何失败都会清掉本方法创建的全部明文
     * （临时 ZIP、暂存树、暂存目录），并且完全不碰原始档案。
     * destination 已存在时直接拒绝。
     */
    public static void unpack(Path encryptedFile, Path destination, SecretKey key) throws Exception {
        if (encryptedFile == null || destination == null) {
            throw new IllegalArgumentException("档案路径与目标目录都不能为空");
        }
        Path archive = encryptedFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("档案不存在或不是普通文件：" + archive);
        }
        long size = Files.size(archive);
        if (size < HEADER_BYTES + TAG_BYTES + 4) throw new IOException("档案不完整：长度不足");
        if (size > MAX_ARCHIVE_BYTES) throw new IOException("档案超过 " + MAX_ARCHIVE_BYTES + " 字节上限");
        Path target = destination.toAbsolutePath().normalize();
        Path folder = target.getParent();
        if (folder == null) throw new IOException("目标目录路径无效：" + target);
        if (!Files.isDirectory(folder)) throw new IOException("目标所在目录不存在：" + folder);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("目标目录已存在，拒绝覆盖：" + target);
        }

        Path work = Files.createTempDirectory(folder, ".pksa-unpack-");
        try {
            Path plainZip = work.resolve("payload.zip");
            Path staging = work.resolve("content");
            decryptTo(archive, plainZip, key);
            extract(plainZip, staging);
            Files.deleteIfExists(plainZip);          // 明文 ZIP 只服务解压，尽快消失
            moveNew(staging, target);
        } finally {
            // 成功时只剩空的暂存目录；失败时这里清掉明文 ZIP 与半成品暂存树。
            deleteTree(work);
        }
    }

    /**
     * 内存密封：返回与 {@link #pack} 完全相同的容器字节（头部 + 记录流），
     * 所以 {@link #open} 能读回，也能直接写成文件交给 {@link #unpack}。
     */
    public static byte[] seal(byte[] plaintext, SecretKey key) throws Exception {
        if (plaintext == null) throw new IllegalArgumentException("明文不能为空");
        if (plaintext.length > MAX_BYTES) throw new IOException("明文超过 " + MAX_BYTES + " 字节上限");
        byte[] iv = freshIv();
        byte[] header = header(iv);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(plaintext.length + 4 * 64 + 64);
        buffer.write(header);
        Recorder records = new Recorder(buffer, key, iv, header);
        records.write(plaintext);
        records.finish();
        return buffer.toByteArray();
    }

    /** 内存解封：头部不对、密钥不对、任一字节被改或被截断都会抛异常。 */
    public static byte[] open(byte[] ciphertext, SecretKey key) throws Exception {
        if (ciphertext == null) throw new IllegalArgumentException("密文不能为空");
        // Even an empty payload must have an authenticated terminal record.
        if (ciphertext.length < HEADER_BYTES + 4 + TAG_BYTES) throw new IOException("密文不完整：长度不足");
        if (ciphertext.length > MAX_ARCHIVE_BYTES) throw new IOException("密文超过长度上限");
        byte[] header = Arrays.copyOfRange(ciphertext, 0, HEADER_BYTES);
        byte[] iv = checkedIv(header);
        ByteArrayOutputStream plain = new ByteArrayOutputStream(
                Math.min(ciphertext.length, 1024 * 1024));
        // 从头部之后开始读记录，绝不能把头部当成长度字段。
        try (InputStream records = new Replayer(
                new ByteArrayInputStream(ciphertext, HEADER_BYTES, ciphertext.length - HEADER_BYTES),
                key, iv, header)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = records.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new IOException("密文超过 " + MAX_BYTES + " 字节上限");
                plain.write(buffer, 0, read);
            }
        }
        return plain.toByteArray();
    }

    /**
     * 删除整棵树，从不跟随符号链接：链接本身被删，链接指向的内容不动。
     * 目录不存在时静默返回（清理路径可以放心重复调用）。
     */
    public static void deleteTree(Path root) throws IOException {
        if (root == null) throw new IllegalArgumentException("路径不能为空");
        Path start = root.toAbsolutePath().normalize();
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return;
        if (start.getParent() == null) throw new IOException("拒绝删除文件系统根目录：" + start);
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                // 不跟链接：符号链接在这里就是一个普通条目，删掉链接本身即可。
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException failure)
                    throws IOException {
                throw failure;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ------------------------------------------------------------------ 打包

    private static void writeArchive(Path root, Path temp, SecretKey key) throws Exception {
        byte[] iv = freshIv();
        byte[] header = header(iv);
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(temp,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), BUFFER_BYTES)) {
            file.write(header);
            Recorder records = new Recorder(file, key, iv, header);
            ZipOutputStream zip = new ZipOutputStream(records, StandardCharsets.UTF_8);
            // 只有走到最后才 finish：失败时不再写 tag，临时文件随后被删掉。
            // 这里刻意不用 zip.close()，否则它会替失败路径补完最后一条记录。
            writeTree(root, zip);
            zip.finish();        // 写完中央目录
            records.finish();    // 把最后一条不满的记录连同 tag 写出去
            file.flush();
        }
    }

    private static void writeTree(Path root, ZipOutputStream zip) throws IOException {
        final long[] total = {0};
        final int[] entries = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                Path relative = root.relativize(dir);
                boolean engineDirectory = relative.getNameCount() == 1 || (relative.getNameCount() == 2 && "Default".equals(relative.getName(0).toString()));
                if (engineDirectory && skipDirectory(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (Files.isSymbolicLink(dir)) throw new IOException("拒绝符号链接：" + label(root, dir));
                putEntry(zip, root, dir, true, entries);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                // 先按名字跳过锁文件：SingletonLock 本身就是符号链接，跳过比报错更符合语义。
                if (skipFile(name)) return FileVisitResult.CONTINUE;
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("拒绝符号链接：" + label(root, file));
                }
                if (!attrs.isRegularFile()) throw new IOException("不支持的文件类型：" + label(root, file));
                putEntry(zip, root, file, false, entries);
                try (InputStream in = Files.newInputStream(file,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total[0] += read;
                        if (total[0] > MAX_BYTES) {
                            throw new IOException("会话数据超过 " + MAX_BYTES + " 字节上限");
                        }
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException failure)
                    throws IOException {
                throw new IOException("无法读取会话数据：" + label(root, file), failure);
            }
        });
    }

    private static void putEntry(ZipOutputStream zip, Path root, Path path, boolean directory,
                                 int[] entries) throws IOException {
        if (++entries[0] > MAX_ENTRIES) throw new IOException("会话数据条目超过 " + MAX_ENTRIES + " 个上限");
        String name = label(root, path) + (directory ? "/" : "");
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
        zip.putNextEntry(entry);
        if (directory) zip.closeEntry();
    }

    // ------------------------------------------------------------------ 解包

    /** 解密到临时 ZIP：必须读到流末尾，最后一条记录的 tag 校验通过才算成功。 */
    private static void decryptTo(Path archive, Path plainZip, SecretKey key) throws IOException {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), BUFFER_BYTES)) {
            byte[] header = readHeader(file);
            byte[] iv = checkedIv(header);
            try (InputStream records = new Replayer(file, key, iv, header);
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(plainZip,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER_BYTES)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long total = 0;
                int read;
                while ((read = records.read(buffer)) != -1) {   // 读到 -1 才代表最后一条记录也验过了
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("档案明文超过 " + MAX_BYTES + " 字节上限");
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    private static void extract(Path plainZip, Path staging) throws IOException {
        Files.createDirectories(staging);
        Set<String> seen = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                Files.newInputStream(plainZip, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                BUFFER_BYTES), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("档案条目超过 " + MAX_ENTRIES + " 个上限");
                String rawName = entry.getName();
                String clean = checkEntryName(rawName);
                if (!seen.add(clean)) throw new IOException("档案包含重复条目：" + rawName);
                Path out = staging.resolve(clean).normalize();
                if (!out.startsWith(staging) || out.equals(staging)) {
                    throw new IOException("档案包含越界路径：" + rawName);
                }
                if (entry.isDirectory() || rawName.endsWith("/")) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (OutputStream file = Files.newOutputStream(out,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            if (total > MAX_BYTES) {
                                throw new IOException("档案解压后超过 " + MAX_BYTES + " 字节上限");
                            }
                            file.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * ZIP 里的路径只允许 {@code a/b/c} 这种形式：拒绝绝对路径、盘符、反斜杠和 {@code ..}，
     * 同时把 {@code .} 与空段折叠掉，避免同一目录出现多种写法。
     */
    private static String checkEntryName(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.length() > 1024) throw new IOException("档案包含非法路径");
        if (raw.indexOf('\0') >= 0) throw new IOException("档案包含非法路径：" + raw);
        if (raw.startsWith("/") || raw.indexOf('\\') >= 0) {
            throw new IOException("档案包含绝对或 Windows 风格路径：" + raw);
        }
        if (raw.length() > 1 && raw.charAt(1) == ':' && Character.isLetter(raw.charAt(0))) {
            throw new IOException("档案包含盘符路径：" + raw);
        }
        StringBuilder clean = new StringBuilder(raw.length());
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("档案包含越界路径：" + raw);
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        if (clean.length() == 0) throw new IOException("档案包含空路径");
        return clean.toString();
    }

    // ------------------------------------------------------------------ 头部与密码学

    private static byte[] freshIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * 每条记录的 IV：前 11 字节沿用基础 IV，末字节换成记录序号。
     * 序号从 0 开始单调递增，同一档案内不复用 IV；不同档案的基础 IV 由 SecureRandom 生成。
     * 同一档案的记录数最多 MAX_BYTES/RECORD_BYTES ≈ 64，8 位序号足够。
     */
    private static byte[] recordIv(byte[] baseIv, int index) {
        byte[] iv = baseIv.clone();
        iv[IV_BYTES - 1] = (byte) (baseIv[IV_BYTES - 1] ^ (byte) index);
        return iv;
    }

    private static byte[] header(byte[] iv) {
        byte[] header = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = VERSION;
        header[MAGIC.length + 1] = (byte) iv.length;
        System.arraycopy(iv, 0, header, MAGIC.length + 2, iv.length);
        return header;
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int offset = 0;
        while (offset < HEADER_BYTES) {
            int read = in.read(header, offset, HEADER_BYTES - offset);
            if (read < 0) throw new IOException("档案不完整：头部缺失");
            offset += read;
        }
        return header;
    }

    private static byte[] checkedIv(byte[] header) throws IOException {
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) throw new IOException("不是本应用的会话档案");
        }
        if (header[MAGIC.length] != VERSION) {
            throw new IOException("不支持的档案版本：" + (header[MAGIC.length] & 0xFF));
        }
        if (header[MAGIC.length + 1] != IV_BYTES) throw new IOException("档案头部异常：IV 长度不符");
        return Arrays.copyOfRange(header, MAGIC.length + 2, MAGIC.length + 2 + IV_BYTES);
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv) throws GeneralSecurityException {
        if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("需要调用方传入 AndroidKeyStore 的 AES 密钥");
        }
        if (iv == null || iv.length != IV_BYTES) throw new IllegalArgumentException("IV 必须是 12 字节");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private static void moveReplacing(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveNew(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(staging, target);
        }
    }

    // ------------------------------------------------------------------ 名字与杂项

    private static String label(Path root, Path path) {
        String relative = root.relativize(path).toString();
        return relative.replace('\\', '/');
    }

    private static boolean skipDirectory(String name) {
        return SKIP_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean skipFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SKIP_FILES.contains(lower) || lower.endsWith(".lock");
    }

    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int getInt(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
    }

    private static void readFully(InputStream in, byte[] target, int offset, int length)
            throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(target, offset + done, length - done);
            if (read < 0) throw new IOException("档案不完整：记录被截断");
            done += read;
        }
    }

    // ------------------------------------------------------------------ 记录流

    /**
     * 把写入的明文切成 ≤ {@link #RECORD_BYTES} 的块，每块单独加密成一条记录：
     * {@code 4 字节明文长度 | 密文 | 16 字节 tag}。
     *
     * <p>长度字段以明文放在记录最前面，但被 header 与记录序号隐式覆盖：
     * 它是 AAD 的一部分（连同头部一起），改长度字段会导致该记录 tag 校验失败。
     */
    private static final class Recorder extends OutputStream {
        private final OutputStream out;
        private final SecretKey key;
        private final byte[] baseIv;
        private final byte[] header;
        private byte[] plain = new byte[RECORD_BYTES];
        private int filled;
        private int records;
        private long total;

        Recorder(OutputStream out, SecretKey key, byte[] baseIv, byte[] header) {
            this.out = out;
            this.key = key;
            this.baseIv = baseIv;
            this.header = header;
        }

        @Override public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override public void write(byte[] source, int offset, int length) throws IOException {
            total += length;
            if (total > MAX_BYTES) throw new IOException("会话归档超过大小上限");
            while (length > 0) {
                if (filled == RECORD_BYTES) emit();
                int take = Math.min(length, RECORD_BYTES - filled);
                System.arraycopy(source, offset, plain, filled, take);
                filled += take;
                offset += take;
                length -= take;
                if (filled == RECORD_BYTES) emit();
            }
        }

        /** An authenticated empty terminal record prevents truncation at a complete record boundary. */
        void finish() throws IOException {
            if (filled > 0) emit();
            emit();
            out.flush();
        }
        private void emit() throws IOException {
            byte[] body;
            try {
                Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, recordIv(baseIv, records));
                byte[] aad = new byte[header.length + 4];
                System.arraycopy(header, 0, aad, 0, header.length);
                putInt(aad, header.length, filled);
                cipher.updateAAD(aad);
                body = cipher.doFinal(plain, 0, filled);
            } catch (GeneralSecurityException failure) {
                throw new IOException("无法加密会话档案（密钥不可用）", failure);
            }
            byte[] prefix = new byte[4];
            putInt(prefix, 0, filled);
            out.write(prefix);
            out.write(body);
            filled = 0;
            records++;
            if (records > MAX_BYTES / RECORD_BYTES + 1) throw new IOException("会话档案记录数异常");
        }
    }

    /**
     * 读回记录流。每条记录都必须完整读完并校验 tag；
     * 必须验证认证结束记录才返回 EOF，完整记录边界上的截断同样被拒绝。
     */
    private static final class Replayer extends InputStream {
        private final InputStream source;
        private final SecretKey key;
        private final byte[] baseIv;
        private final byte[] header;
        private byte[] plain = new byte[0];
        private int position;
        private int records;
        private boolean finished;

        Replayer(InputStream source, SecretKey key, byte[] baseIv, byte[] header) {
            this.source = source;
            this.key = key;
            this.baseIv = baseIv;
            this.header = header;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (position >= plain.length) {
                if (finished) return -1;
                if (!nextRecord()) return -1;
            }
            int read = Math.min(length, plain.length - position);
            System.arraycopy(plain, position, target, offset, read);
            position += read;
            return read;
        }

        /** @return 是否读到了一条记录 */
        private boolean nextRecord() throws IOException {
            int first = source.read();
            if (first < 0) throw new IOException("档案被截断：缺少认证结束记录");
            byte[] lengthBytes = new byte[4];
            lengthBytes[0] = (byte) first;
            readFully(source, lengthBytes, 1, 3);
            int length = getInt(lengthBytes, 0);
            if (length < 0 || length > RECORD_BYTES) throw new IOException("档案记录长度异常");
            byte[] body = new byte[length + TAG_BYTES];
            readFully(source, body, 0, body.length);
            try {
                Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, recordIv(baseIv, records));
                byte[] aad = new byte[header.length + 4];
                System.arraycopy(header, 0, aad, 0, header.length);
                System.arraycopy(lengthBytes, 0, aad, header.length, 4);
                cipher.updateAAD(aad);
                byte[] opened = cipher.doFinal(body);
                if (opened.length != length) throw new IOException("档案记录长度不一致");
                plain = opened;
            } catch (GeneralSecurityException failure) {
                throw new IOException("档案认证失败：密钥不正确或内容被篡改", failure);
            }
            position = 0;
            records++;
            if (records > MAX_BYTES / RECORD_BYTES + 1) throw new IOException("档案记录数异常");
            if (length == 0) {
                if (source.read() != -1) throw new IOException("认证结束记录后包含额外数据");
                finished = true;
                return false;
            }
            return true;
        }

        @Override public void close() throws IOException {
            source.close();
        }
    }
}
