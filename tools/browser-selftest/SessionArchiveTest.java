import cn.xianhua.pocketkit.browser.SessionArchive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * SessionArchive 的纯 JVM 自检：不碰 Android、不碰设备、不引第三方测试框架。
 *
 * 覆盖：字节往返 / 错密钥 / 篡改与截断 / 目录往返 / 原始源不变 / 坏档案不留目标 /
 * zip 路径穿越与重复项 / 大小与条目上限 / 暂存残留清理 / 失败不覆盖旧档案。
 * 产物全部落在 .scratch/browser-archive-test，每次运行先清空。
 */
public final class SessionArchiveTest {
    /** 数据目录；与编译输出（同级的 classes/）分开，清空时不会删到自己的类文件。 */
    private static final Path WORK = Paths.get(".scratch/browser-archive-test/data").toAbsolutePath();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (Files.exists(WORK)) SessionArchive.deleteTree(WORK);
        Files.createDirectories(WORK);

        SecretKey key = aes(256);
        SecretKey other = aes(256);

        byteRoundTrip(key);
        wrongKey(key, other);
        tampering(key);
        directoryRoundTrip(key);
        sourceUntouched(key);
        symlinksRejected(key);
        destinationRules(key);
        failedUnpackLeavesNothing(key, other);
        failedPackKeepsOldFile(key);
        zipSlipAndDuplicates(key);
        limits(key);
        deleteTreeRules();

        System.out.println("PASS " + checks + " session archive checks");
    }

    // ------------------------------------------------------------------ 字节层

    private static void byteRoundTrip(SecretKey key) throws Exception {
        byte[] payload = new byte[5000];
        RANDOM.nextBytes(payload);
        byte[] sealed = SessionArchive.seal(payload, key);
        byte[] opened = SessionArchive.open(sealed, key);
        check("字节往返内容一致", java.util.Arrays.equals(payload, opened));
        check("密文不同于明文", !java.util.Arrays.equals(payload, sealed));
        check("档案头以 PKSA 开头", sealed[0] == 'P' && sealed[1] == 'K'
                && sealed[2] == 'S' && sealed[3] == 'A');
        check("版本号为 1", sealed[4] == 1);
        check("IV 长度字段为 12", sealed[5] == 12);
        check("密文长度包含认证结束记录", sealed.length == 5000 + 18 + 4 + 16 + 20);
        check("首条记录长度 = 明文长度（5000）",
                byteAt(sealed, 18) == 0 && byteAt(sealed, 19) == 0
                        && byteAt(sealed, 20) == (5000 >>> 8) && byteAt(sealed, 21) == (5000 & 0xFF));
        check("单条记录长度不超过 1MB（内存有界）", sealed.length < 18 + 4 + 1024 * 1024 + 16);
        check("seal 产物可以直接当档案文件解包", sealIsArchive(sealed, key));

        byte[] again = SessionArchive.seal(payload, key);
        check("随机 IV：同一明文两次密封不同", !java.util.Arrays.equals(sealed, again));
        check("同一明文两次密封都能打开",
                java.util.Arrays.equals(payload, SessionArchive.open(again, key)));

        byte[] empty = SessionArchive.seal(new byte[0], key);
        check("空明文可密封并解封", SessionArchive.open(empty, key).length == 0);

        // 多记录：超过单记录上限的明文必须跨记录往返，内容不变。
        byte[] large = new byte[(int) (5L * 1024 * 1024) + 123];
        RANDOM.nextBytes(large);
        byte[] largeSealed = SessionArchive.seal(large, key);
        check("跨记录的大块可往返", java.util.Arrays.equals(large, SessionArchive.open(largeSealed, key)));
        check("大块密文明显长于 1 条记录", largeSealed.length > 3 * 1024 * 1024);

        // 恰好整记录边界：仍须写入带认证的 0 长度结束记录。
        byte[] exact = new byte[1024 * 1024];
        RANDOM.nextBytes(exact);
        byte[] exactSealed = SessionArchive.seal(exact, key);
        check("整记录边界往返一致", java.util.Arrays.equals(exact, SessionArchive.open(exactSealed, key)));
    }

    private static void wrongKey(SecretKey key, SecretKey other) throws Exception {
        byte[] sealed = SessionArchive.seal("会话数据".getBytes(StandardCharsets.UTF_8), key);
        check("错密钥被拒绝", fails(() -> SessionArchive.open(sealed, other)));
        check("空密钥被拒绝", fails(() -> SessionArchive.open(sealed, null)));
    }

    private static void tampering(SecretKey key) throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = SessionArchive.seal(payload, key);

        byte[] body = sealed.clone();
        body[body.length - 1] ^= 0x01;
        check("密文尾部被改：认证失败", fails(() -> SessionArchive.open(body, key)));

        byte[] middle = sealed.clone();
        middle[middle.length / 2] ^= 0x40;
        check("密文中段被改：认证失败", fails(() -> SessionArchive.open(middle, key)));

        byte[] iv = sealed.clone();
        iv[8] ^= 0x01;                       // 头部里的 IV，被 AAD 覆盖
        check("IV 被改：认证失败", fails(() -> SessionArchive.open(iv, key)));

        byte[] version = sealed.clone();
        version[4] = 2;
        check("版本号被改：拒绝", fails(() -> SessionArchive.open(version, key)));

        byte[] magic = sealed.clone();
        magic[0] = 'X';
        check("魔数被改：拒绝", fails(() -> SessionArchive.open(magic, key)));

        byte[] truncated = java.util.Arrays.copyOf(sealed, sealed.length - 1);
        check("截断 1 字节：拒绝", fails(() -> SessionArchive.open(truncated, key)));

        byte[] empty = new byte[0];
        check("空密文：拒绝", fails(() -> SessionArchive.open(empty, key)));
    }

    // ------------------------------------------------------------------ 目录层

    /**
     * 一个贴近真实 WebView profile 的目录：网站数据必须原样保留，
     * 引擎缓存与锁文件必须被丢掉。
     */
    private static void directoryRoundTrip(SecretKey key) throws Exception {
        Path profile = WORK.resolve("profile");
        Path siteData = profile.resolve("Default");
        write(siteData.resolve("Cookies"), "sqlite-cookies-v1");
        write(siteData.resolve("Cookies-wal"), "wal-frames");
        write(siteData.resolve("Cookies-shm"), "shm-index");
        write(siteData.resolve("Local Storage").resolve("leveldb").resolve("000003.log"), "local-storage");
        write(siteData.resolve("Local Storage").resolve("leveldb").resolve("LOCK"), "leveldb-lock");
        write(siteData.resolve("IndexedDB").resolve("https_example.com_0.indexeddb.leveldb")
                .resolve("MANIFEST-000001"), "indexeddb-manifest");
        write(siteData.resolve("IndexedDB").resolve("https_example.com_0.indexeddb.leveldb")
                .resolve("000005.ldb"), "indexeddb-table");
        write(siteData.resolve("Session Storage").resolve("000010.log"), "session-storage");
        write(siteData.resolve("Service Worker").resolve("CacheStorage").resolve("hash").resolve("index"), "sw-cache");
        write(siteData.resolve("Web Data"), "web-data-sqlite");
        write(siteData.resolve("Preferences"), "{\"profile\":\"x\"}");
        write(siteData.resolve("blob_storage").resolve("blob-id"), "blob-bytes");
        Files.createDirectories(siteData.resolve("IndexedDB").resolve("empty-dir"));

        // 必须被丢弃：引擎缓存与锁。
        write(profile.resolve("Default").resolve("Cache").resolve("data_0"), "cache-should-skip");
        write(profile.resolve("Default").resolve("Code Cache").resolve("js").resolve("index"), "code-cache-should-skip");
        write(profile.resolve("Default").resolve("GPUCache").resolve("data_1"), "gpu-should-skip");
        write(profile.resolve("Default").resolve("DawnCache").resolve("data_2"), "dawn-should-skip");
        write(profile.resolve("SingletonLock"), "lock-should-skip");
        write(profile.resolve("SingletonCookie"), "cookie-should-skip");
        write(profile.resolve("SingletonSocket"), "socket-should-skip");
        write(siteData.resolve("Local Storage").resolve("leveldb").resolve("LOCK"), "leveldb-lock");
        write(profile.resolve("Default").resolve("Preferences.lock"), "suffix-lock-should-skip");

        Map<String, String> before = snapshot(profile);
        Path archive = WORK.resolve("profile.pksa");
        SessionArchive.pack(profile, archive, key);
        check("档案文件已生成", Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS));
        check("档案里看不到任何明文字段名", !containsBytes(Files.readAllBytes(archive),
                "Cookies".getBytes(StandardCharsets.US_ASCII),
                "IndexedDB".getBytes(StandardCharsets.US_ASCII),
                "Local Storage".getBytes(StandardCharsets.US_ASCII)));

        Path restored = WORK.resolve("restored");
        SessionArchive.unpack(archive, restored, key);
        Map<String, String> after = snapshot(restored);

        check("网站数据：Cookies 原样", "sqlite-cookies-v1".equals(read(restored.resolve("Default/Cookies"))));
        check("网站数据：Cookies-wal 原样", "wal-frames".equals(read(restored.resolve("Default/Cookies-wal"))));
        check("网站数据：Cookies-shm 原样", "shm-index".equals(read(restored.resolve("Default/Cookies-shm"))));
        check("网站数据：Local Storage 原样", "local-storage".equals(read(
                restored.resolve("Default/Local Storage/leveldb/000003.log"))));
        check("网站数据：IndexedDB 两张表原样",
                "indexeddb-manifest".equals(read(restored.resolve(
                        "Default/IndexedDB/https_example.com_0.indexeddb.leveldb/MANIFEST-000001")))
                        && "indexeddb-table".equals(read(restored.resolve(
                        "Default/IndexedDB/https_example.com_0.indexeddb.leveldb/000005.ldb"))));
        check("网站数据：Session Storage 原样", "session-storage".equals(
                read(restored.resolve("Default/Session Storage/000010.log"))));
        check("网站数据：Service Worker CacheStorage 原样", "sw-cache".equals(
                read(restored.resolve("Default/Service Worker/CacheStorage/hash/index"))));
        check("网站数据：Web Data 原样", "web-data-sqlite".equals(read(restored.resolve("Default/Web Data"))));
        check("网站数据：blob_storage 原样", "blob-bytes".equals(
                read(restored.resolve("Default/blob_storage/blob-id"))));
        check("网站数据：空目录保留", Files.isDirectory(
                restored.resolve("Default/IndexedDB/empty-dir"), LinkOption.NOFOLLOW_LINKS));

        check("缓存目录被丢弃：Cache", !Files.exists(restored.resolve("Default/Cache")));
        check("缓存目录被丢弃：Code Cache", !Files.exists(restored.resolve("Default/Code Cache")));
        check("缓存目录被丢弃：GPUCache", !Files.exists(restored.resolve("Default/GPUCache")));
        check("缓存目录被丢弃：DawnCache", !Files.exists(restored.resolve("Default/DawnCache")));
        check("锁文件被丢弃：SingletonLock", !Files.exists(restored.resolve("SingletonLock")));
        check("锁文件被丢弃：SingletonCookie", !Files.exists(restored.resolve("SingletonCookie")));
        check("锁文件被丢弃：LOCK", !Files.exists(
                restored.resolve("Default/Local Storage/leveldb/LOCK")));
        check("锁文件被丢弃：*.lock", !Files.exists(restored.resolve("Default/Preferences.lock")));

        String skipped = "cache-should-skip|code-cache-should-skip|gpu-should-skip|dawn-should-skip";
        check("被跳过的内容确实没进档案",
                !before.equals(after) && !snapshot(restored).containsValue(skipped));
        check("还原后的目录是普通目录", Files.isDirectory(restored, LinkOption.NOFOLLOW_LINKS));

        // 再解一次：destination 已存在必须拒绝，且原有内容不被改动。
        Map<String, String> keep = snapshot(restored);
        check("destination 已存在时拒绝", fails(() -> SessionArchive.unpack(archive, restored, key)));
        check("拒绝后 destination 内容不变", keep.equals(snapshot(restored)));

        // 暂存目录必须清理干净：目标父目录里不留 .pksa-* 临时物。
        check("解包后不残留暂存目录", countTempArtifacts(WORK) == 0);
    }

    private static void sourceUntouched(SecretKey key) throws Exception {
        Path profile = WORK.resolve("source-keep");
        write(profile.resolve("Default/Cookies"), "keep-me");
        write(profile.resolve("Default/Local Storage/x"), "keep-me-too");
        Map<String, String> before = snapshot(profile);
        Path archive = WORK.resolve("source-keep.pksa");
        SessionArchive.pack(profile, archive, key);
        Map<String, String> after = snapshot(profile);
        check("pack 不改动原始目录（含内容与 mtime）", before.equals(after));

        Path restored = WORK.resolve("source-keep-out");
        SessionArchive.unpack(archive, restored, key);
        check("unpack 不改动原始目录", before.equals(snapshot(profile)));
        check("原始目录仍存在且可读", "keep-me".equals(read(profile.resolve("Default/Cookies"))));

        // 档案不能写进被保存的目录里（会把自己的密文打进去）。
        check("拒绝把档案写进源目录", fails(() -> SessionArchive.pack(profile, profile.resolve("x.pksa"), key)));
        check("拒绝把档案写到源目录之内", fails(() -> SessionArchive.pack(profile,
                profile.resolve("Default").resolve("y.pksa"), key)));
    }

    /** 符号链接一律拒绝：既不能把链接本身存进去，也不能顺着它读到目录外的数据。 */
    private static void symlinksRejected(SecretKey key) throws Exception {
        Path base = WORK.resolve("symlink");
        Path outside = WORK.resolve("symlink-outside");
        write(outside.resolve("secret.txt"), "outside-secret");
        Path profile = base.resolve("profile");
        write(profile.resolve("Default/Cookies"), "cookie");
        boolean supported = true;
        try {
            Files.createSymbolicLink(profile.resolve("Default/evil-link"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(profile.resolve("Default/evil-dir"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            supported = false;   // Windows 未开开发者模式 / 无权限：跳过硬链接断言。
        }
        if (!supported) {
            System.out.println("  skip 当前主机无法创建符号链接，跳过这项");
            return;
        }

        Path archive = base.resolve("link.pksa");
        check("拒绝文件符号链接", fails(() -> SessionArchive.pack(profile, archive, key)));
        check("拒绝符号链接时不留下档案", !Files.exists(archive, LinkOption.NOFOLLOW_LINKS));
        check("拒绝符号链接时不碰链接目标", "outside-secret".equals(read(outside.resolve("secret.txt"))));

        Files.delete(profile.resolve("Default/evil-link"));
        check("拒绝目录符号链接", fails(() -> SessionArchive.pack(profile, archive, key)));
        check("拒绝目录链接时不留档案", !Files.exists(archive, LinkOption.NOFOLLOW_LINKS));

        Files.delete(profile.resolve("Default/evil-dir"));
        // Linux 上 SingletonLock 本身就是符号链接：先按名字跳过，绝不能因此整体失败。
        Files.createSymbolicLink(profile.resolve("SingletonLock"), outside.resolve("secret.txt"));
        Path clean = base.resolve("clean.pksa");
        SessionArchive.pack(profile, clean, key);
        check("作为符号链接的 SingletonLock 被跳过而不报错",
                Files.isRegularFile(clean, LinkOption.NOFOLLOW_LINKS));
        Path out = base.resolve("clean-out");
        SessionArchive.unpack(clean, out, key);
        check("跳过锁后网站数据仍完整", "cookie".equals(read(out.resolve("Default/Cookies"))));
        check("跳过锁后没有把链接写进档案", !Files.exists(out.resolve("SingletonLock"), LinkOption.NOFOLLOW_LINKS));
        check("链接目标始终没被读取或改动",
                "outside-secret".equals(read(outside.resolve("secret.txt"))));
    }

    private static void destinationRules(SecretKey key) throws Exception {
        Path profile = WORK.resolve("dest-src");
        write(profile.resolve("Default/Cookies"), "c");
        Path archive = WORK.resolve("dest.pksa");
        SessionArchive.pack(profile, archive, key);

        Path nested = WORK.resolve("dest-missing-parent").resolve("out");
        check("父目录不存在时拒绝", fails(() -> SessionArchive.unpack(archive, nested, key)));

        Path existing = WORK.resolve("dest-exists");
        Files.createDirectories(existing);
        write(existing.resolve("keep.txt"), "keep");
        check("destination 已存在（非空）拒绝", fails(() -> SessionArchive.unpack(archive, existing, key)));
        check("拒绝时不删除已有目录", "keep".equals(read(existing.resolve("keep.txt"))));

        check("档案不存在时拒绝", fails(() -> SessionArchive.unpack(
                WORK.resolve("nope.pksa"), WORK.resolve("dest-x"), key)));
        check("源不是目录时拒绝", fails(() -> SessionArchive.pack(
                profile.resolve("Default/Cookies"), WORK.resolve("bad.pksa"), key)));
    }

    private static void failedUnpackLeavesNothing(SecretKey key, SecretKey other) throws Exception {
        Path profile = WORK.resolve("bad-src");
        write(profile.resolve("Default/Cookies"), "cookie-data");
        write(profile.resolve("Default/IndexedDB/a"), "idb");
        Path archive = WORK.resolve("bad.pksa");
        SessionArchive.pack(profile, archive, key);
        byte[] intact = Files.readAllBytes(archive);

        Path wrong = WORK.resolve("bad-out-key");
        check("错密钥解包失败", fails(() -> SessionArchive.unpack(archive, wrong, other)));
        check("错密钥失败后不留目标目录", !Files.exists(wrong, LinkOption.NOFOLLOW_LINKS));
        check("错密钥失败后不留暂存目录", countTempArtifacts(WORK) == 0);

        Path tamperedFile = WORK.resolve("bad-tampered.pksa");
        byte[] broken = intact.clone();
        broken[broken.length - 40] ^= 0x7F;
        Files.write(tamperedFile, broken);
        Path tamperedOut = WORK.resolve("bad-out-tampered");
        check("篡改档案解包失败", fails(() -> SessionArchive.unpack(tamperedFile, tamperedOut, key)));
        check("篡改失败后不留目标目录", !Files.exists(tamperedOut, LinkOption.NOFOLLOW_LINKS));
        check("篡改失败后不留明文暂存", countTempArtifacts(WORK) == 0);
        check("篡改失败不改动档案本身", java.util.Arrays.equals(broken, Files.readAllBytes(tamperedFile)));

        Path truncatedFile = WORK.resolve("bad-truncated.pksa");
        Files.write(truncatedFile, java.util.Arrays.copyOf(intact, intact.length - 100));
        Path truncatedOut = WORK.resolve("bad-out-truncated");
        check("截断档案解包失败", fails(() -> SessionArchive.unpack(truncatedFile, truncatedOut, key)));
        check("截断失败后不留目标目录", !Files.exists(truncatedOut, LinkOption.NOFOLLOW_LINKS));

        Path headerFile = WORK.resolve("bad-header.pksa");
        byte[] headerBroken = intact.clone();
        headerBroken[0] = 'Q';
        Files.write(headerFile, headerBroken);
        check("魔数错误的档案被拒绝", fails(() -> SessionArchive.unpack(
                headerFile, WORK.resolve("bad-out-header"), key)));

        // 只有头部没有载荷的极短文件
        Path tiny = WORK.resolve("bad-tiny.pksa");
        Files.write(tiny, new byte[]{'P', 'K', 'S', 'A', 1, 12});
        check("极短档案被拒绝", fails(() -> SessionArchive.unpack(
                tiny, WORK.resolve("bad-out-tiny"), key)));
    }

    private static void failedPackKeepsOldFile(SecretKey key) throws Exception {
        Path profile = WORK.resolve("atomic-src");
        write(profile.resolve("Default/Cookies"), "new-data");
        Path archive = WORK.resolve("atomic.pksa");
        Files.write(archive, "OLD-ARCHIVE".getBytes(StandardCharsets.UTF_8));

        // 让打包在中途失败：单个文件超过 MAX_BYTES（稀疏文件，读起来很快）。
        Path huge = profile.resolve("Default/IndexedDB/huge.bin");
        Files.createDirectories(huge.getParent());
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(SessionArchive.MAX_BYTES + 1);
        }
        check("超过 64MB 的目录打包失败", fails(() -> SessionArchive.pack(profile, archive, key)));
        check("失败后旧档案原样保留", "OLD-ARCHIVE".equals(read(archive)));
        check("失败后不留临时档案", countTempArtifacts(WORK) == 0);

        Files.deleteIfExists(huge);
        SessionArchive.pack(profile, archive, key);
        check("再次打包成功并覆盖旧档案", Files.size(archive) > 20
                && !"OLD-ARCHIVE".equals(read(archive)));
        Path out = WORK.resolve("atomic-out");
        SessionArchive.unpack(archive, out, key);
        check("覆盖后的档案可解出内容", "new-data".equals(read(out.resolve("Default/Cookies"))));
    }

    // ------------------------------------------------------------------ 构造的坏档案

    private static void zipSlipAndDuplicates(SecretKey key) throws Exception {
        assertRejected(key, entries("a/../../escape.txt", "x"), "路径穿越 ../");
        assertRejected(key, entries("../escape.txt", "x"), "路径穿越 开头 ../");
        assertRejected(key, entries("/abs.txt", "x"), "绝对路径");
        assertRejected(key, entries("C:\\evil.txt", "x"), "盘符路径");
        assertRejected(key, entries("a\\b.txt", "x"), "反斜杠路径");
        assertRejected(key, entries("Default/../escape2.txt", "x"), "中段 ../ 穿越");
        assertRejected(key, entries("a//b.txt", "1", "a/b.txt", "2"), "重复条目（写法不同）");
        assertRejected(key, rawZip("dup.txt", "1", "dup.txt", "2"), "重复条目（完全相同）");
        assertRejected(key, entries("Default/./Cookies", "1", "Default/Cookies", "2"), "折叠后重复条目");

        // 一次成功的构造档案：确认我们自己造的包也能正常解开。
        Path good = WORK.resolve("crafted.pksa");
        Files.write(good, SessionArchive.seal(entries("Default/Cookies", "crafted"), key));
        Path out = WORK.resolve("crafted-out");
        SessionArchive.unpack(good, out, key);
        check("手工构造的正常档案可解包", "crafted".equals(read(out.resolve("Default/Cookies"))));

        // 单点段不是穿越：Default/./Cookies 折叠成 Default/Cookies 后照常写入。
        Path dotted = WORK.resolve("dotted.pksa");
        Files.write(dotted, SessionArchive.seal(entries("Default/./Cookies", "dotted"), key));
        Path dottedOut = WORK.resolve("dotted-out");
        SessionArchive.unpack(dotted, dottedOut, key);
        check("单点段被折叠而非拒绝", "dotted".equals(read(dottedOut.resolve("Default/Cookies"))));
        check("折叠后只有一个文件落盘", snapshot(dottedOut).size() == 2);   // Default/ 与 Default/Cookies
    }

    private static int crafted;

    private static void assertRejected(SecretKey key, byte[] zip, String what) throws Exception {
        // 每次用新文件：同一个路径反复截断写，容易和上一轮还没来得及关的句柄打架。
        Path archive = WORK.resolve("reject-" + (++crafted) + ".pksa");
        Files.write(archive, SessionArchive.seal(zip, key));
        Path out = WORK.resolve("reject-out-" + crafted);
        boolean rejected = fails(() -> SessionArchive.unpack(archive, out, key));
        check("拒绝" + what, rejected);
        check("拒绝" + what + " 后不留目标", !Files.exists(out, LinkOption.NOFOLLOW_LINKS));
        check("拒绝" + what + " 后不留暂存", countTempArtifacts(WORK) == 0);
        check("拒绝" + what + " 后没有越界文件", !Files.exists(WORK.resolve("escape.txt"))
                && !Files.exists(WORK.getParent().resolve("escape.txt"))
                && !Files.exists(Paths.get("/abs.txt")));
    }

    private static void limits(SecretKey key) throws Exception {
        check("seal 拒绝超过 64MB 的明文",
                fails(() -> SessionArchive.seal(new byte[(int) SessionArchive.MAX_BYTES + 1], key)));

        // 解压炸弹：64MB+1 的零字节条目，压缩后极小，但解压总量必须被拦住。
        Path bombZip = WORK.resolve("bomb.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bombZip))) {
            zip.putNextEntry(new ZipEntry("Default/big.bin"));
            byte[] chunk = new byte[64 * 1024];
            for (long written = 0; written <= SessionArchive.MAX_BYTES; written += chunk.length) {
                zip.write(chunk, 0, chunk.length);
            }
            zip.closeEntry();
        }
        Path bomb = WORK.resolve("bomb.pksa");
        Files.write(bomb, SessionArchive.seal(Files.readAllBytes(bombZip), key));
        Path bombOut = WORK.resolve("bomb-out");
        check("解压后超过 64MB 被拒绝", fails(() -> SessionArchive.unpack(bomb, bombOut, key)));
        check("炸弹失败后不留目标", !Files.exists(bombOut, LinkOption.NOFOLLOW_LINKS));
        check("炸弹失败后不留暂存", countTempArtifacts(WORK) == 0);
        Files.deleteIfExists(bombZip);

        // 条目数上限：超过 4096 个条目必须拒绝。
        Path manyZip = WORK.resolve("many.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(manyZip))) {
            for (int i = 0; i < 4100; i++) {
                zip.putNextEntry(new ZipEntry("Default/many/" + i + ".bin"));
                zip.write(0x30);
                zip.closeEntry();
            }
        }
        Path many = WORK.resolve("many.pksa");
        Files.write(many, SessionArchive.seal(Files.readAllBytes(manyZip), key));
        Path manyOut = WORK.resolve("many-out");
        check("条目数超过上限被拒绝", fails(() -> SessionArchive.unpack(many, manyOut, key)));
        check("条目超限后不留目标", !Files.exists(manyOut, LinkOption.NOFOLLOW_LINKS));
        check("条目超限后不留暂存", countTempArtifacts(WORK) == 0);
        Files.deleteIfExists(manyZip);

        // 档案长度下限：比“头部 + tag”还短的文件在解密前就该被拒。
        Path tiny = WORK.resolve("oversized.pksa");
        Files.write(tiny, new byte[20]);
        check("档案过短被拒绝", fails(() -> SessionArchive.unpack(
                tiny, WORK.resolve("oversized-out"), key)));
        check("极小档案不留暂存", countTempArtifacts(WORK) == 0);
    }

    private static void deleteTreeRules() throws Exception {
        SessionArchive.deleteTree(WORK.resolve("no-such-dir"));
        check("deleteTree 对不存在的路径静默返回", true);

        Path root = WORK.resolve("delete-tree");
        write(root.resolve("a/b.txt"), "b");
        write(root.resolve("c.txt"), "c");
        Path outside = WORK.resolve("outside.txt");
        write(outside, "outside");
        boolean linked = false;
        try {
            Files.createSymbolicLink(root.resolve("link-to-outside"), outside);
            linked = true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Windows 未开开发者模式时建不了符号链接；跳过链接断言，其余照测。
        }
        SessionArchive.deleteTree(root);
        check("deleteTree 删除整棵树", !Files.exists(root, LinkOption.NOFOLLOW_LINKS));
        check("deleteTree 不跟随符号链接", !linked
                || "outside".equals(read(outside)) && Files.exists(outside));
        Files.deleteIfExists(outside);
    }

    // ------------------------------------------------------------------ 工具

    private static SecretKey aes(int bits) {
        byte[] raw = new byte[bits / 8];
        RANDOM.nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * seal 与 pack 共用同一种容器格式：把 seal(一个 ZIP) 直接写成文件，unpack 必须能读。
     * 这条断言防的是“内存接口与文件接口各写一套格式”的漂移。
     */
    private static boolean sealIsArchive(byte[] sealedZip, SecretKey key) throws Exception {
        Path archive = WORK.resolve("seal-as-file.pksa");
        Files.write(archive, SessionArchive.seal(entries("Default/Cookies", "sealed-zip"), key));
        Path out = WORK.resolve("seal-as-file-out");
        SessionArchive.unpack(archive, out, key);
        return "sealed-zip".equals(read(out.resolve("Default/Cookies")));
    }

    private static int byteAt(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    private static void write(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 相对路径 → 内容摘要（+ 类型 + mtime），用来证明目录没被改动。 */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> map = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                String rel = root.relativize(path).toString().replace('\\', '/');
                if (rel.isEmpty()) continue;
                FileTime time = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    map.put(rel + "/", "dir@" + time.toMillis());
                } else {
                    map.put(rel, digest(path) + "@" + time.toMillis());
                }
            }
        }
        return map;
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(sha.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean containsBytes(byte[] haystack, byte[]... needles) {
        for (byte[] needle : needles) {
            outer:
            for (int i = 0; i + needle.length <= haystack.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) continue outer;
                }
                return true;
            }
        }
        return false;
    }

    /** 同级目录里不能残留本方法建的 .pksa-pack-* / .pksa-unpack-* 或明文 payload.zip。 */
    private static int countTempArtifacts(Path folder) throws IOException {
        int count = 0;
        try (Stream<Path> list = Files.list(folder)) {
            for (Path path : list.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(".pksa-pack-") || name.startsWith(".pksa-unpack-")
                        || name.equals("payload.zip")) count++;
            }
        }
        return count;
    }

    /** 直接把条目名与内容对拼成 ZIP，用来构造恶意档案。 */
    private static byte[] entries(String... nameThenContent) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenContent[i]));
                zip.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * 手工拼一个 STORED（不压缩）ZIP。{@code ZipOutputStream} 自己会拒绝同名条目，
     * 所以“完全重复的条目名”只能这样造出来——攻击者手上的包可不受这个约束。
     */
    private static byte[] rawZip(String... nameThenContent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        int count = 0;
        for (int i = 0; i < nameThenContent.length; i += 2) {
            byte[] name = nameThenContent[i].getBytes(StandardCharsets.UTF_8);
            byte[] data = nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8);
            int crc = crc32(data);
            int offset = out.size();
            le32(out, 0x04034b50);
            le16(out, 20);            // version needed
            le16(out, 0);             // flags
            le16(out, 0);             // STORED
            le16(out, 0);             // time
            le16(out, 0);             // date
            le32(out, crc);
            le32(out, data.length);
            le32(out, data.length);
            le16(out, name.length);
            le16(out, 0);             // extra length
            out.write(name);
            out.write(data);

            le32(directory, 0x02014b50);
            le16(directory, 20);      // version made by
            le16(directory, 20);      // version needed
            le16(directory, 0);
            le16(directory, 0);       // STORED
            le16(directory, 0);
            le16(directory, 0);
            le32(directory, crc);
            le32(directory, data.length);
            le32(directory, data.length);
            le16(directory, name.length);
            le16(directory, 0);       // extra
            le16(directory, 0);       // comment
            le16(directory, 0);       // disk
            le16(directory, 0);       // internal attrs
            le32(directory, 0);       // external attrs
            le32(directory, offset);
            directory.write(name);
            count++;
        }
        int directoryOffset = out.size();
        out.write(directory.toByteArray());
        le32(out, 0x06054b50);
        le16(out, 0);
        le16(out, 0);
        le16(out, count);
        le16(out, count);
        le32(out, directory.size());
        le32(out, directoryOffset);
        le16(out, 0);
        return out.toByteArray();
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private interface Block {
        void run() throws Exception;
    }

    private static boolean fails(Block block) {
        try {
            block.run();
            return false;
        } catch (AssertionError assertion) {
            throw assertion;          // 断言失败必须冒出去，不能被当成“被拒绝”
        } catch (Throwable expected) {
            // 任何受检/非受检异常都算“被拒绝”；关键是没留下明文或半成品（由调用方另行断言）。
            return true;
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("  ok " + what);
    }
}
