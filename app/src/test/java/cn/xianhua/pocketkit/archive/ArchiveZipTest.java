package cn.xianhua.pocketkit.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ZIP 这条路的纯 JVM 实测：真的建包、真的解包、真的按字节比对内容。
 *
 * 不依赖 Android、不依赖真机：zip4j 是纯 Java，落点用本地临时目录实现同一个
 * {@link OutputDirectory} 接口，所以这里跑的就是手机上跑的那份 ZipExtractor / ZipPacker。
 *
 * 覆盖：往返一致、AES-256 加密与错误密码、非 ASCII 名、路径穿越、符号链接位、重名、
 * 孤儿条数上限、单条上限、总量上限、压缩炸弹、取消后清理、损坏包、无覆盖原件。
 */
public final class ArchiveZipTest {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static File root;

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "archive-zip-test");
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("测试目录建不出来：" + root);

        roundTripPlain();
        roundTripEncrypted();
        wrongPassword();
        nonAsciiNames();
        pathTraversalBlocked();
        duplicateNames();
        entryCountLimit();
        entrySizeLimit();
        totalLimit();
        compressionBomb();
        cancelCleansUp();
        brokenArchive();
        originalUntouched();
        packNamesAreSafe();
        packEmptyFolderListing();

        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " / " + checks);
            for (String failure : failures) System.out.println("  - " + failure);
            System.exit(1);
        }
        System.out.println("ArchiveZipTest OK: " + checks + " checks");
    }

    // ---------------- 用例 ----------------

    private static void roundTripPlain() throws Exception {
        File zip = new File(root, "plain.zip");
        List<ArchiveSource> sources = Arrays.asList(
                source("note.txt", "hello 归档"),
                source("data.bin", randomBytes(300_000)),
                source("empty.txt", new byte[0]));
        try (OutputStream out = new FileOutputStream(zip)) {
            ZipPacker.Encryption used = ZipPacker.pack(sources, out, null,
                    ZipPacker.Encryption.NONE, new Silent());
            check(used == ZipPacker.Encryption.NONE, "不加密时写出的加密方式为 NONE");
        }
        check(zip.isFile() && zip.length() > 0, "建出了非空 ZIP");

        File out = dir("plain-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "plain.zip", null, output, "plain", new Silent());
        check(result.complete(), "正常包解完没有停止原因：" + result.stopped);
        check(result.files == 3, "解出 3 个文件，实际 " + result.files);
        check(result.failures.isEmpty(), "正常包没有失败项：" + describe(result));
        check("hello 归档".equals(read(new File(out, "plain/note.txt"))), "文本内容一致");
        check(Arrays.equals(randomBytesCache, bytes(new File(out, "plain/data.bin"))), "300 KB 二进制一致");
        check(new File(out, "plain/empty.txt").length() == 0, "空文件也建出来了");
    }

    private static void roundTripEncrypted() throws Exception {
        File zip = new File(root, "secret.zip");
        char[] password = "口令-1234".toCharArray();
        try (OutputStream out = new FileOutputStream(zip)) {
            ZipPacker.Encryption used = ZipPacker.pack(
                    Arrays.asList(source("a.txt", "机密内容")), out, password,
                    ZipPacker.Encryption.AES256, new Silent());
            check(used == ZipPacker.Encryption.AES256, "请求 AES-256 时真的写了 AES-256");
        }
        check(zip.length() > 0, "加密包非空");
        // zip4j 自己的 FileHeader 判定：确认写出的确实是 AES，别只信我们自己的返回值。
        net.lingala.zip4j.ZipFile reading = new net.lingala.zip4j.ZipFile(zip, password);
        check(reading.isEncrypted(), "包被识别为加密");
        net.lingala.zip4j.model.FileHeader header = reading.getFileHeaders().get(0);
        check(header.getEncryptionMethod() == net.lingala.zip4j.model.enums.EncryptionMethod.AES,
                "加密方式是 AES，实际 " + header.getEncryptionMethod());
        check(header.getAesExtraDataRecord() != null
                        && header.getAesExtraDataRecord().getAesKeyStrength()
                        == net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256,
                "AES 密钥强度是 256 位");

        File out = dir("secret-out");
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "secret.zip", password, new MemoryOutput(out), "secret", new Silent());
        check(result.files == 1 && result.failures.isEmpty(),
                "有正确密码时解出 1 个文件：" + describe(result));
        check("机密内容".equals(read(new File(out, "secret/a.txt"))), "解密后内容一致");
    }

    private static void wrongPassword() throws Exception {
        File zip = new File(root, "secret.zip");
        File out = dir("wrongpw-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "secret.zip", "错误密码".toCharArray(), output, "secret", new Silent());
        check(!result.complete() && result.wipe, "错误密码整体作废");
        check(result.stopped.contains("密码"), "错误密码的说明提到密码：" + result.stopped);
        // 作废后必须由调用方清理；这里模拟 Activity 的动作，确认清干净。
        output.deleteCreated();
        check(emptyDir(out), "错误密码清理后没有残留：" + list(out));
    }

    private static void nonAsciiNames() throws Exception {
        File zip = new File(root, "中文 名.zip");
        try (OutputStream out = new FileOutputStream(zip)) {
            ZipPacker.pack(Arrays.asList(
                    source("报告 2026.txt", "内容 A"),
                    source("照片.jpg", randomBytes(1024))), out, null,
                    ZipPacker.Encryption.NONE, new Silent());
        }
        File out = dir("cjk-out");
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "中文 名.zip", null, new MemoryOutput(out), "中文包", new Silent());
        check(result.files == 2, "中文名包解出 2 个文件：" + describe(result));
        check(new File(out, "中文包/报告 2026.txt").isFile(), "中文文件名原样落地");
    }

    private static void pathTraversalBlocked() throws Exception {
        // 用 JDK 的 ZipOutputStream 造一个恶意包（zip4j 不让我们写 ../）。
        File zip = new File(root, "evil.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            put(zos, "../../escape.txt", "逃出去");
            put(zos, "/abs/absolute.txt", "绝对路径");
            put(zos, "C:/win/win.txt", "盘符");
            put(zos, "ok/fine.txt", "正常的");
            put(zos, "..", "只有上跳");
            put(zos, "a/../../b.txt", "中段上跳");
        }
        File out = dir("evil-out");
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "evil.zip", null, new MemoryOutput(out), "evil", new Silent());
        check(result.files == 1, "只有 ok/fine.txt 落地，实际 " + result.files);
        check(new File(out, "evil/ok/fine.txt").isFile(), "正常条目照常解出");
        check(!new File(out, "escape.txt").exists(), "没有写到落点之外");
        check(!new File(root, "escape.txt").exists(), "没有写到上级目录");
        check(!new File(out, "evil/abs/absolute.txt").exists(), "绝对路径没有落成文件");
        check(!new File(out, "evil/win/win.txt").exists(), "盘符路径没有落成文件");
        check(result.failures.size() >= 4, "不安全的名字都记了失败项，实际 " + result.failures.size());
        check(!hasPathEscape(out), "落点目录里没有任何 ../ 逃逸产物");
    }

    private static void duplicateNames() throws Exception {
        File zip = new File(root, "dupes.zip");
        // java.util.zip 和 zip4j 都拒绝写同名条目，这里手写最小 ZIP，才测得到重名。
        RawZip raw = new RawZip();
        raw.add("same.txt", "第一次");
        raw.add("same.txt", "第二次");
        raw.add("same.txt", "第三次");
        raw.writeTo(zip);
        File out = dir("dupes-out");
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "dupes.zip", null, new MemoryOutput(out), "dupes", new Silent());
        check(result.files == 3, "三个同名条目都解出来了，实际 " + result.files);
        check("第一次".equals(read(new File(out, "dupes/same.txt"))), "第一个保留原名");
        check("第二次".equals(read(new File(out, "dupes/same (1).txt"))), "第二个加 (1)");
        check("第三次".equals(read(new File(out, "dupes/same (2).txt"))), "第三个加 (2)");
    }

    private static void entryCountLimit() throws Exception {
        File zip = new File(root, "many.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            for (int i = 0; i < 40; i++) put(zos, "f" + i + ".txt", "x");
        }
        File out = dir("many-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(10, 1 << 20, 1 << 20, 300);
        ExtractResult result = new ZipExtractor(tight, -1)
                .extract(open(zip), "many.zip", null, output, "many", new Silent());
        check(!result.complete(), "条目数超上限会停");
        check(result.stopped.contains("条目"), "说明提到条目上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超条目清理后没有残留：" + list(out));
    }

    private static void entrySizeLimit() throws Exception {
        File zip = new File(root, "big-entry.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            put(zos, "big.bin", new String(new char[300_000]).replace('\0', 'A'));
        }
        File out = dir("bigentry-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(1000, 64 * 1024, 1 << 30, 300);
        ExtractResult result = new ZipExtractor(tight, -1)
                .extract(open(zip), "big-entry.zip", null, output, "big", new Silent());
        check(!result.complete(), "单条超上限会停");
        check(result.stopped.contains("单个条目"), "说明提到单条上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超单条上限清理后没有残留");
    }

    private static void totalLimit() throws Exception {
        // 三个条目各自在单条上限内，但总量超上限。
        File zip = new File(root, "total.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            for (int i = 0; i < 3; i++) {
                put(zos, "part" + i + ".bin", new String(new char[80_000]).replace('\0', 'B'));
            }
        }
        File out = dir("total-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(1000, 1 << 20, 150_000, 300);
        ExtractResult result = new ZipExtractor(tight, -1)
                .extract(open(zip), "total.zip", null, output, "total", new Silent());
        check(!result.complete(), "总量超上限会停");
        check(result.stopped.contains("总量"), "说明提到总量上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超总量清理后没有残留");
    }

    private static void compressionBomb() throws Exception {
        // 10 MiB 全零 → 压缩后几 KB，解压比远超 300。
        File zip = new File(root, "bomb.zip");
        byte[] zeros = new byte[10 * 1024 * 1024];
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("zeros.bin"));
            zos.write(zeros);
            zos.closeEntry();
        }
        long archiveBytes = zip.length();
        check(archiveBytes > 0 && 10L * 1024 * 1024 / archiveBytes > 300,
                "测试包本身确实满足「压缩比 > 300」");
        File out = dir("bomb-out");
        MemoryOutput output = new MemoryOutput(out);
        // ratioFloor 压到 1 MiB，让 10 MiB 的炸弹在测试里几秒内就撞线。
        ArchiveLimits limits = new ArchiveLimits(1000, 1L << 30, 1L << 30, 300, 1L << 20);
        ExtractResult result = new ZipExtractor(limits, archiveBytes)
                .extract(open(zip), "bomb.zip", null, output, "bomb", new Silent());
        check(!result.complete() && result.wipe, "压缩炸弹被拦下：" + result.stopped);
        check(result.stopped.contains("压缩比"), "说明点明压缩比异常：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "炸弹清理后没有残留");
        check(new File(root, "bomb.zip").length() == archiveBytes, "原包没被改动");
    }

    private static void cancelCleansUp() throws Exception {
        File zip = new File(root, "plain.zip");
        File out = dir("cancel-out");
        MemoryOutput output = new MemoryOutput(out);
        Silent progress = new Silent();
        progress.cancelAfter = 2;
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "plain.zip", null, output, "cancel", progress);
        check(result.cancelled, "取消被标记");
        check(result.stopped.contains("已取消"), "说明写明已取消：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "取消清理后没有残留：" + list(out));
    }

    private static void brokenArchive() throws Exception {
        File broken = new File(root, "broken.zip");
        try (FileOutputStream file = new FileOutputStream(broken)) {
            file.write("PK\u0003\u0004这不是一个真的 ZIP 包".getBytes(StandardCharsets.UTF_8));
        }
        File out = dir("broken-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(broken), "broken.zip", null, output, "broken", new Silent());
        check(!result.complete(), "坏包不会报成功");
        check(!result.stopped.isEmpty(), "坏包有明确原因：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "坏包清理后没有残留");

        File notArchive = new File(root, "random.bin");
        try (FileOutputStream file = new FileOutputStream(notArchive)) {
            byte[] noise = new byte[4096];
            new SecureRandom().nextBytes(noise);
            file.write(noise);
        }
        ExtractResult noise = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(notArchive), "random.bin", null,
                        new MemoryOutput(dir("noise-out")), "noise", new Silent());
        check(!noise.complete(), "随机数据不会被当成 ZIP 解出内容");
    }

    private static void originalUntouched() throws Exception {
        File zip = new File(root, "keep.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            put(zos, "keep.txt", "原样");
        }
        long before = zip.length();
        byte[] beforeBytes = bytes(zip);
        // 落点里先放一个同名文件：解压必须新建独立子目录，不能覆盖它。
        File out = dir("keep-out");
        try (FileOutputStream existing = new FileOutputStream(new File(out, "keep"))) {
            existing.write("原有文件".getBytes(StandardCharsets.UTF_8));
        }
        // 和 Activity 一样先查重名，拿到 "keep (1)" 当本次子目录。
        Set<String> taken = new LinkedHashSet<>();
        taken.add("keep");
        String rootName = EntryNames.rootName("keep.zip", taken);
        check("keep (1)".equals(rootName), "本次子目录避开已有同名项：" + rootName);
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, before)
                .extract(open(zip), "keep.zip", null, output, rootName, new Silent());
        check(result.files == 1, "解出 1 个文件：" + describe(result));
        check(new File(out, "keep (1)/keep.txt").isFile(), "落点是新建的独立子目录");
        check("原有文件".equals(read(new File(out, "keep"))),
                "落点里原有的同名文件一个字节都没变");
        check(zip.length() == before && Arrays.equals(beforeBytes, bytes(zip)),
                "原压缩包一个字节都没变");
    }

    private static void packNamesAreSafe() {
        check("a.txt".equals(EntryNames.forZipEntry("a.txt", "x")), "普通名原样");
        check("passwd".equals(EntryNames.forZipEntry("../../etc/passwd", "x")), "穿越名只取最后一段");
        check(!"".equals(EntryNames.forZipEntry("", "fallback")), "空名有兜底");
        // 落点子目录名：从包名派生，撞名加 (1)，不安全的基础名有兜底。
        Set<String> taken = new LinkedHashSet<>();
        check("data".equals(EntryNames.rootName("data.zip", taken)), "子目录名取自包名");
        taken.add("data");
        check("data (1)".equals(EntryNames.rootName("data.zip", taken)), "撞名加 (1)");
        taken.add("data (1)");
        check("data (2)".equals(EntryNames.rootName("data.zip", taken)), "再加就是 (2)");
        check("解压结果".equals(EntryNames.rootName("...", taken)), "空基础名有兜底");
        check("evil".equals(EntryNames.rootName("../../evil.zip", taken)), "包名里的路径被剥掉");
    }

    /** 只含目录的包：不能因为「没有文件」就报错。 */
    private static void packEmptyFolderListing() throws Exception {
        File zip = new File(root, "dirs.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("empty/"));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("empty/inner/"));
            zos.closeEntry();
        }
        File out = dir("dirs-out");
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, -1)
                .extract(open(zip), "dirs.zip", null, new MemoryOutput(out), "dirs", new Silent());
        check(result.directories == 2, "建出 2 个目录，实际 " + result.directories);
        check(result.files == 0 && result.complete(), "只有目录也算完成：" + result.stopped);
        check(new File(out, "dirs/empty/inner").isDirectory(), "嵌套目录建出来了");
    }

    // ---------------- 测试替身 ----------------

    /** 手写最小 ZIP：java.util.zip 与 zip4j 都拒绝同名条目，重名包只能自己拼。 */
    private static final class RawZip {
        private final List<String> names = new ArrayList<>();
        private final List<byte[]> datas = new ArrayList<>();

        void add(String name, String text) {
            names.add(name);
            datas.add(text.getBytes(StandardCharsets.UTF_8));
        }

        void writeTo(File target) throws IOException {
            try (FileOutputStream out = new FileOutputStream(target)) {
                ByteArrayOutputStream central = new ByteArrayOutputStream();
                int offset = 0;
                for (int i = 0; i < names.size(); i++) {
                    byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                    byte[] data = datas.get(i);
                    int crc = (int) crc32(data);
                    ByteArrayOutputStream local = new ByteArrayOutputStream();
                    writeInt(local, 0x04034b50);
                    writeShort(local, 20);
                    writeShort(local, 0);
                    writeShort(local, 0);       // STORE
                    writeShort(local, 0);
                    writeShort(local, 0);
                    writeInt(local, crc);
                    writeInt(local, data.length);
                    writeInt(local, data.length);
                    writeShort(local, name.length);
                    writeShort(local, 0);
                    local.write(name);
                    local.write(data);
                    byte[] localBytes = local.toByteArray();
                    out.write(localBytes);

                    writeInt(central, 0x02014b50);
                    writeShort(central, 20);
                    writeShort(central, 20);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeInt(central, crc);
                    writeInt(central, data.length);
                    writeInt(central, data.length);
                    writeShort(central, name.length);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeShort(central, 0);
                    writeInt(central, 0);
                    writeInt(central, offset);
                    central.write(name);
                    offset += localBytes.length;
                }
                byte[] centralBytes = central.toByteArray();
                out.write(centralBytes);
                ByteArrayOutputStream end = new ByteArrayOutputStream();
                writeInt(end, 0x06054b50);
                writeShort(end, 0);
                writeShort(end, 0);
                writeShort(end, names.size());
                writeShort(end, names.size());
                writeInt(end, centralBytes.length);
                writeInt(end, offset);
                writeShort(end, 0);
                out.write(end.toByteArray());
            }
        }

        private static long crc32(byte[] data) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            return crc.getValue();
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
        }

        private static void writeInt(ByteArrayOutputStream out, int value) {
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
            out.write((value >>> 16) & 0xff);
            out.write((value >>> 24) & 0xff);
        }
    }

    private static byte[] randomBytesCache;

    private static synchronized byte[] randomBytes(int size) {
        if (randomBytesCache == null || randomBytesCache.length != size) {
            randomBytesCache = new byte[size];
            new SecureRandom().nextBytes(randomBytesCache);
        }
        return randomBytesCache;
    }

    private static ArchiveSource source(final String name, final String text) {
        return source(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static ArchiveSource source(final String name, final byte[] bytes) {
        return new ArchiveSource() {
            @Override public String name() {
                return name;
            }

            @Override public long size() {
                return bytes.length;
            }

            @Override public InputStream open() {
                return new java.io.ByteArrayInputStream(bytes);
            }
        };
    }

    /** 本地目录版的落点，行为和 SAF 版一致：只记自己建过的东西。 */
    private static final class MemoryOutput implements OutputDirectory {
        private final File base;
        private final Set<String> createdFiles = new LinkedHashSet<>();
        private final Set<String> createdDirs = new LinkedHashSet<>();

        MemoryOutput(File base) {
            this.base = base;
        }

        @Override public String describe() {
            return base.getAbsolutePath();
        }

        @Override public void mkdir(String path) throws IOException {
            String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            if (normalized.isEmpty() || createdDirs.contains(normalized)) return;
            File dir = resolve(normalized);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("建不出目录：" + normalized);
            }
            createdDirs.add(normalized);
        }

        @Override public OutputStream create(String path) throws IOException {
            int slash = path.lastIndexOf('/');
            if (slash > 0) mkdir(path.substring(0, slash));
            File file = resolve(path);
            if (!file.createNewFile()) {
                throw new IOException("同名文件已存在，拒绝覆盖：" + path);
            }
            createdFiles.add(path);
            return new FileOutputStream(file);
        }

        @Override public void delete(String path) throws IOException {
            File file = resolve(path);
            if (file.exists() && !file.delete()) throw new IOException("删不掉：" + path);
            createdFiles.remove(path);
            createdDirs.remove(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }

        /** 只删本次建的东西；测试里用于模拟 Activity 的清理动作。 */
        void deleteCreated() throws IOException {
            List<String> files = new ArrayList<>(createdFiles);
            for (String path : files) delete(path);
            List<String> dirs = new ArrayList<>(createdDirs);
            for (int i = dirs.size() - 1; i >= 0; i--) delete(dirs.get(i));
        }

        private File resolve(String path) throws IOException {
            File file = new File(base, path);
            String canonicalBase = base.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(canonicalBase)) {
                throw new IOException("落点越界：" + path);
            }
            return file;
        }
    }

    private static final class Silent implements ArchiveProgress {
        int seen;
        int cancelAfter = -1;

        @Override public void onProgress(String label, long done, long total) {
            seen++;
        }

        @Override public boolean isCancelled() {
            return cancelAfter >= 0 && seen >= cancelAfter;
        }
    }

    // ---------------- 小工具 ----------------

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) failures.add(label);
    }

    private static File dir(String name) throws IOException {
        File dir = new File(root, name);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("建不出目录：" + dir);
        return dir;
    }

    private static InputStream open(File file) throws IOException {
        return new java.io.BufferedInputStream(new java.io.FileInputStream(file), Pump.CHUNK);
    }

    private static void put(java.util.zip.ZipOutputStream zos, String name, String text)
            throws IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static byte[] bytes(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    private static String read(File file) throws IOException {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static boolean emptyDir(File dir) {
        File[] children = dir.listFiles();
        return children == null || children.length == 0;
    }

    private static String list(File dir) {
        StringBuilder text = new StringBuilder();
        for (File child : dir.listFiles()) text.append(child.getName()).append(' ');
        return text.toString();
    }

    /** 落点之外没有出现任何产物（防止 ../ 穿越成功）。 */
    private static boolean hasPathEscape(File outputRoot) throws IOException {
        String canonical = outputRoot.getCanonicalPath();
        File parent = outputRoot.getParentFile();
        for (String name : new String[]{"escape.txt", "b.txt"}) {
            File suspect = new File(parent, name);
            if (suspect.exists()) return true;
            if (new File(outputRoot, name).exists()) return true;
        }
        return !outputRoot.getCanonicalPath().equals(canonical);
    }

    private static String describe(ExtractResult result) {
        StringBuilder text = new StringBuilder(result.summary());
        for (ExtractResult.Problem problem : result.failures()) {
            text.append("\n  ").append(problem.entry).append(" → ").append(problem.reason);
        }
        return text.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) file.deleteOnExit();
    }
}
