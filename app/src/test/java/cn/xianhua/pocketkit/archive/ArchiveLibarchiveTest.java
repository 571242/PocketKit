package cn.xianhua.pocketkit.archive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * libarchive 那条路（7z / RAR / tar）的纯 JVM 实测。
 *
 * 真机才有 JNI，所以这里用一个按 7z / RAR5 条目结构喂数据的 {@link FakeReader} 驱动
 * {@link LibarchiveExtractor}：测的是「条目遍历 + 清洗 + 计数 + 分段读取 + 取消 + 失败分类」，
 * 也就是这条路上唯一由我们写、也唯一会出错的部分。JNI 本身未在真机验证，见文档。
 *
 * 另外补 {@link EntryNames} 的全量边界和 {@link ArchiveFormats} 的识别表。
 */
public final class ArchiveLibarchiveTest {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static File root;

    public static void main(String[] args) throws Exception {
        root = new File(args.length > 0 ? args[0] : "archive-lib-test");
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("测试目录建不出来：" + root);

        manifestRoundTrip();
        directoryTree();
        symlinkAndHardlinkSkipped();
        traversalBlocked();
        duplicatesRenamed();
        entryCountLimit();
        entrySizeLimit();
        totalLimit();
        cancelMidEntry();
        encryptedEntrySkipped();
        readFailureIsPerEntry();
        closeAlwaysCalled();
        namesEdgeCases();
        formatTable();

        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " / " + checks);
            for (String failure : failures) System.out.println("  - " + failure);
            System.exit(1);
        }
        System.out.println("ArchiveLibarchiveTest OK: " + checks + " checks");
    }

    // ---------------- 解压路径 ----------------

    private static void manifestRoundTrip() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        reader.file("docs/readme.txt", "hello 7z");
        reader.file("docs/big.bin", bytes(700_000, (byte) 'x'));
        reader.file("empty.txt", new byte[0]);
        File out = dir("seven-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "seven", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.complete(), "7z 正常包跑完：" + result.stopped);
        check(result.files == 3, "解出 3 个文件，实际 " + result.files);
        check("7-Zip".equals(result.formatName), "格式名来自 reader：" + result.formatName);
        check("hello 7z".equals(read(new File(out, "seven/docs/readme.txt"))), "文本内容一致");
        check(new File(out, "seven/docs/big.bin").length() == 700_000, "700 KB 文件大小一致");
        check(Arrays.equals(bytes(700_000, (byte) 'x'), bytesAt(new File(out, "seven/docs/big.bin"))),
                "大文件逐字节一致（跨多个 64 KiB 块）");
        check(new File(out, "seven/empty.txt").length() == 0, "空文件也建出来了");
        check(reader.closed, "reader 被关闭");
    }

    private static void directoryTree() throws Exception {
        FakeReader reader = new FakeReader("RAR 5");
        reader.directory("a/");
        reader.directory("a/b/");
        reader.file("a/b/c.txt", "c");
        File out = dir("tree-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "tree", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.directories == 2, "建出 2 个目录，实际 " + result.directories);
        check(result.files == 1, "解出 1 个文件");
        check(new File(out, "tree/a/b/c.txt").isFile(), "嵌套路径落地正确");
    }

    private static void symlinkAndHardlinkSkipped() throws Exception {
        FakeReader reader = new FakeReader("POSIX tar");
        reader.symlink("link-to-etc", "/etc/passwd");
        reader.hardlink("hard", "link-to-etc");
        reader.file("real.txt", "真文件");
        File out = dir("link-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "link", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.skipped == 2, "两个链接都被跳过，实际 " + result.skipped);
        check(result.files == 1, "真文件照常解出");
        check(!new File(out, "link/link-to-etc").exists(), "符号链接没有落成文件");
        check(!new File(out, "link/hard").exists(), "硬链接没有落成文件");
        check(result.failures.size() == 2, "两个链接都记了跳过原因");
    }

    private static void traversalBlocked() throws Exception {
        FakeReader reader = new FakeReader("RAR 5");
        reader.file("../../escape.txt", "逃出去");
        reader.file("/abs/abs.txt", "绝对路径");
        reader.file("C:/win.txt", "盘符");
        reader.file("ok.txt", "正常");
        File out = dir("escape-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "esc", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.files == 1, "只有 ok.txt 落地，实际 " + result.files);
        check(!new File(out.getParentFile(), "escape.txt").exists(), "没有写到落点之外");
        check(!new File(out, "esc/abs/abs.txt").exists(), "绝对路径没落成文件");
        check(!new File(out, "esc/win.txt").exists(), "盘符路径没落成文件");
    }

    private static void duplicatesRenamed() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        reader.file("same.txt", "一");
        reader.file("same.txt", "二");
        File out = dir("dup-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "dup", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.files == 2, "两个同名条目都写出来了");
        check("一".equals(read(new File(out, "dup/same.txt"))), "第一个保留原名");
        check("二".equals(read(new File(out, "dup/same (1).txt"))), "第二个加 (1)");
    }

    private static void entryCountLimit() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        for (int i = 0; i < 30; i++) reader.file("f" + i + ".txt", "x");
        File out = dir("count-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(5, 1 << 20, 1 << 20, 300);
        ExtractResult result = run(reader, "count", output, tight, new Silent());
        check(!result.complete() && result.wipe, "条目数超上限整体作废");
        check(result.stopped.contains("条目"), "说明提到条目上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超条目清理后没有残留");
        check(reader.closed, "失败路径也关闭了 reader");
    }

    private static void entrySizeLimit() throws Exception {
        FakeReader reader = new FakeReader("RAR 5");
        reader.file("big.bin", bytes(200_000, (byte) 'A'));
        File out = dir("big-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(100, 64 * 1024, 1 << 30, 300);
        ExtractResult result = run(reader, "big", output, tight, new Silent());
        check(!result.complete(), "单条超上限会停");
        check(result.stopped.contains("单个条目"), "说明提到单条上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超单条清理后没有残留");
    }

    private static void totalLimit() throws Exception {
        FakeReader reader = new FakeReader("tar");
        for (int i = 0; i < 4; i++) reader.file("p" + i + ".bin", bytes(50_000, (byte) 'B'));
        File out = dir("total-out");
        MemoryOutput output = new MemoryOutput(out);
        ArchiveLimits tight = new ArchiveLimits(100, 1 << 20, 120_000, 300);
        ExtractResult result = run(reader, "total", output, tight, new Silent());
        check(!result.complete(), "总量超上限会停");
        check(result.stopped.contains("总量"), "说明提到总量上限：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "超总量清理后没有残留");
    }

    private static void cancelMidEntry() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        reader.file("a.bin", bytes(400_000, (byte) 'C'));
        reader.file("b.bin", bytes(400_000, (byte) 'D'));
        File out = dir("cancel-out");
        MemoryOutput output = new MemoryOutput(out);
        Silent progress = new Silent();
        progress.cancelAfter = 3;
        ExtractResult result = run(reader, "cancel", output, ArchiveLimits.DEFAULTS, progress);
        check(result.cancelled, "取消被标记");
        check(result.stopped.contains("已取消"), "说明写明已取消：" + result.stopped);
        output.deleteCreated();
        check(emptyDir(out), "取消清理后没有残留：" + list(out));
    }

    private static void encryptedEntrySkipped() throws Exception {
        FakeReader reader = new FakeReader("RAR 5");
        reader.encryptedFile("secret.txt", "加密内容");
        reader.file("open.txt", "明文");
        File out = dir("enc-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "enc", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.files == 1, "明文条目解出，实际 " + result.files);
        check(result.failures.size() == 1, "加密条目记了失败");
        check(result.failures.get(0).reason.contains("密码"), "失败原因提到密码");
        check(result.complete(), "加密条目不算整体失败：" + result.stopped);
        check(!new File(out, "enc/secret.txt").exists(), "加密条目没有留下空文件");
    }

    private static void readFailureIsPerEntry() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        reader.file("good1.txt", "一");
        reader.brokenFile("broken.bin", bytes(200_000, (byte) 'E'));
        reader.file("good2.txt", "二");
        File out = dir("partial-out");
        MemoryOutput output = new MemoryOutput(out);
        ExtractResult result = run(reader, "partial", output, ArchiveLimits.DEFAULTS, new Silent());
        check(result.files == 2, "两个好条目都解出来了，实际 " + result.files);
        check(result.failures.size() == 1, "坏条目记了失败");
        check(result.complete(), "局部失败不等于整体失败：" + result.stopped);
        check(!new File(out, "partial/broken.bin").exists(), "坏条目没有留下半成品");
        check(result.summary().contains("失败 1 项"), "摘要里点出失败数：" + result.summary());
    }

    private static void closeAlwaysCalled() throws Exception {
        FakeReader reader = new FakeReader("7-Zip");
        // 空包：next() 直接返回 null。
        File out = dir("empty-out");
        ExtractResult result = run(reader, "empty", new MemoryOutput(out),
                ArchiveLimits.DEFAULTS, new Silent());
        check(reader.closed, "空包也关闭了 reader");
        check(result.files == 0, "空包没有文件");
        check(result.directories == 0, "空包没有目录");
    }

    // ---------------- 名字与格式表 ----------------

    private static void namesEdgeCases() {
        // 规范：返回 null 表示整条不安全；目录保留末尾 "/"。
        check(EntryNames.sanitize("a/b/c.txt") != null, "正常相对路径可用");
        check("a/b/c.txt".equals(EntryNames.sanitize("a/b/c.txt")), "正常路径原样");
        check(EntryNames.sanitize("../x") == null, "前导 .. 拒绝");
        check(EntryNames.sanitize("a/../../x") == null, "中段 .. 拒绝");
        check(EntryNames.sanitize("/etc/passwd") == null, "绝对路径拒绝");
        check(EntryNames.sanitize("C:/x") == null, "盘符拒绝");
        check(EntryNames.sanitize("c:alt.txt") == null, "盘符 + 备用数据流拒绝");
        check(EntryNames.sanitize("") == null, "空名拒绝");
        check(EntryNames.sanitize("./a/b") != null, "点号段被忽略而不是拒绝");
        check("a/b".equals(EntryNames.sanitize("./a/b")), "忽略 . 段");
        check(EntryNames.sanitize("a\\b\\c") != null, "反斜杠当分隔符处理");
        check("a/b".equals(EntryNames.sanitize("a\\b")), "反斜杠归一成 /");
        check("dir/".equals(EntryNames.sanitize("dir/", true)), "目录保留结尾斜杠");
        check(EntryNames.sanitize("..") == null, "单独 .. 拒绝");
        check(EntryNames.sanitize("a/..") == null, "结尾 .. 拒绝");
        // 控制字符与 Windows 保留名
        String dirty = "bad\u0001name\u007f.txt";
        String cleaned = EntryNames.sanitize(dirty);
        check(cleaned != null && !cleaned.contains("\u0001") && !cleaned.contains("\u007f"),
                "控制字符被剥掉：" + cleaned);
        check("_CON".equals(EntryNames.sanitize("CON")), "Windows 保留名加前缀");
        check("_nul.txt".equals(EntryNames.sanitize("nul.txt")), "带扩展名的保留名也加前缀");
        // 超长
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 400; i++) longName.append('n');
        String truncated = EntryNames.sanitize(longName + ".txt");
        check(truncated != null && truncated.length() <= EntryNames.MAX_SEGMENT,
                "超长单段被截断到 " + EntryNames.MAX_SEGMENT);
        check(truncated.endsWith(".txt"), "截断保留扩展名");
        // 深层路径总长
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append("segment").append(i).append('/');
        check(EntryNames.sanitize(deep.toString()) == null, "超深路径被拒绝");
        // 重名
        Set<String> taken = new LinkedHashSet<>();
        check("a.txt".equals(EntryNames.unique("a.txt", taken)), "没重名原样返回");
        taken.add("a.txt");
        check("a (1).txt".equals(EntryNames.unique("a.txt", taken)), "重名加 (1)");
        taken.add("a (1).txt");
        check("a (2).txt".equals(EntryNames.unique("a.txt", taken)), "再重加 (2)");
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add("d/");
        check("d (1)/".equals(EntryNames.unique("d/", dirs)), "目录重名保留结尾斜杠");
    }

    private static void formatTable() {
        check(ArchiveFormats.kind("a.zip") == ArchiveFormats.Kind.ZIP, "zip → ZIP");
        check(ArchiveFormats.kind("a.APK") == ArchiveFormats.Kind.ZIP, "大小写不敏感");
        check(ArchiveFormats.kind("a.docx") == ArchiveFormats.Kind.ZIP, "docx 也是 ZIP 家族");
        check(ArchiveFormats.kind("a.7z") == ArchiveFormats.Kind.LIBARCHIVE, "7z 走 libarchive");
        check(ArchiveFormats.kind("a.rar") == ArchiveFormats.Kind.LIBARCHIVE, "rar 走 libarchive");
        check(ArchiveFormats.kind("a.tar.gz") == ArchiveFormats.Kind.LIBARCHIVE, "tar.gz 走 libarchive");
        check(ArchiveFormats.kind("a.txt") == ArchiveFormats.Kind.UNKNOWN, "未知扩展名");
        check(!ArchiveFormats.looksLikeArchive("a.png"), "png 不是压缩包");
        check(ArchiveFormats.looksLikeArchive("a.7z"), "7z 算压缩包");

        byte[] zipHead = {'P', 'K', 3, 4, 0, 0, 0, 0};
        byte[] sevenHead = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 0};
        byte[] rarHead = {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00};
        check(ArchiveFormats.sniff(zipHead, "noext") == ArchiveFormats.Kind.ZIP,
                "无扩展名靠文件头认出 ZIP");
        check(ArchiveFormats.sniff(sevenHead, "noext") == ArchiveFormats.Kind.LIBARCHIVE,
                "无扩展名靠文件头认出 7z");
        check(ArchiveFormats.sniff(rarHead, "noext") == ArchiveFormats.Kind.LIBARCHIVE,
                "无扩展名靠文件头认出 RAR");
        check(ArchiveFormats.sniff(zipHead, "a.7z") == ArchiveFormats.Kind.ZIP,
                "文件头优先于错误的扩展名");
        check(ArchiveFormats.sniff(new byte[0], "a.zip") == ArchiveFormats.Kind.ZIP,
                "头读不到时退回扩展名");
        check(ArchiveFormats.isZip(zipHead) && !ArchiveFormats.isZip(sevenHead), "isZip 判定正确");

        String text = ArchiveFormats.limitation();
        check(text.contains("RAR"), "能力声明提到 RAR");
        check(text.contains("不能创建 7z/RAR"), "能力声明明确不能建 7z/RAR");
        check(text.contains("压缩比"), "能力声明提到压缩比限制");
        check(text.contains("分卷"), "能力声明提到不支持分卷");
    }

    // ---------------- 驱动 ----------------

    private static ExtractResult run(FakeReader reader, String rootName, MemoryOutput output,
                                     ArchiveLimits limits, Silent progress) throws IOException {
        return new LibarchiveExtractor(limits, -1).extract(reader, rootName, output, progress);
    }

    /** 按 list 顺序吐出条目和分块数据；分块故意大于/小于 64 KiB，验证跨块读取。 */
    private static final class FakeReader implements ArchiveReader {
        private final String format;
        private final List<Object[]> entries = new ArrayList<>();
        private int index = -1;
        private byte[] current;
        private int offset;
        boolean closed;

        FakeReader(String format) {
            this.format = format;
        }

        void file(String path, String text) {
            add(path, text.getBytes(StandardCharsets.UTF_8), false, false, false, false);
        }

        void file(String path, byte[] data) {
            add(path, data, false, false, false, false);
        }

        void directory(String path) {
            add(path, new byte[0], true, false, false, false);
        }

        void symlink(String path, String target) {
            add(path, target.getBytes(StandardCharsets.UTF_8), false, true, false, false);
        }

        void hardlink(String path, String target) {
            add(path, target.getBytes(StandardCharsets.UTF_8), false, false, true, false);
        }

        void encryptedFile(String path, String text) {
            add(path, text.getBytes(StandardCharsets.UTF_8), false, false, false, true);
        }

        void brokenFile(String path, byte[] data) {
            add(path, data, false, false, false, false);
            brokenIndex = entries.size() - 1;
        }

        private int brokenIndex = -1;

        private void add(String path, byte[] data, boolean dir, boolean sym, boolean hard,
                         boolean encrypted) {
            entries.add(new Object[]{path, data, dir, sym, hard, encrypted});
        }

        @Override public String formatName() {
            return format;
        }

        @Override public ArchiveReader.Entry next() {
            index++;
            if (index >= entries.size()) return null;
            Object[] row = entries.get(index);
            current = (byte[]) row[1];
            offset = 0;
            return new FakeEntry((String) row[0], (byte[]) row[1], (Boolean) row[2],
                    (Boolean) row[3], (Boolean) row[4], (Boolean) row[5]);
        }

        @Override public int readData(ByteBuffer buffer) throws IOException {
            if (index == brokenIndex && offset > 0) {
                throw new IOException("模拟条目数据损坏");
            }
            int capacity = Math.min(buffer.remaining() > 0 ? buffer.remaining() : Pump.CHUNK,
                    Pump.CHUNK);
            int remain = current.length - offset;
            if (remain <= 0) return 0;
            int read = Math.min(capacity, remain);
            buffer.clear();
            buffer.put(current, offset, read);
            offset += read;
            // 模拟 JNI 契约：读完后 position 指向末尾。
            buffer.position(read);
            return read;
        }

        @Override public void skipEntryData() {
            offset = current.length;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static final class FakeEntry implements ArchiveReader.Entry {
        private final String path;
        private final long size;
        private final boolean dir, sym, hard, encrypted;

        FakeEntry(String path, byte[] data, boolean dir, boolean sym, boolean hard,
                  boolean encrypted) {
            this.path = path;
            this.size = data.length;
            this.dir = dir;
            this.sym = sym;
            this.hard = hard;
            this.encrypted = encrypted;
        }

        @Override public String pathname() {
            return path;
        }

        @Override public boolean isDirectory() {
            return dir;
        }

        @Override public boolean isSymlink() {
            return sym;
        }

        @Override public boolean isHardlink() {
            return hard;
        }

        @Override public long size() {
            return size;
        }

        @Override public boolean isEncrypted() {
            return encrypted;
        }
    }

    private static final class MemoryOutput implements OutputDirectory {
        private final File base;
        private final Set<String> createdFiles = new LinkedHashSet<>();
        private final Set<String> createdDirs = new LinkedHashSet<>();

        MemoryOutput(File base) {
            this.base = base;
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
            if (!file.createNewFile()) throw new IOException("拒绝覆盖同名：" + path);
            createdFiles.add(path);
            return new FileOutputStream(file);
        }

        @Override public void delete(String path) throws IOException {
            File file = resolve(path);
            if (file.exists() && !file.delete()) throw new IOException("删不掉：" + path);
            createdFiles.remove(path);
            createdDirs.remove(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }

        @Override public String describe() {
            return base.getAbsolutePath();
        }

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

    private static byte[] bytes(int size, byte fill) {
        byte[] data = new byte[size];
        Arrays.fill(data, fill);
        return data;
    }

    private static byte[] bytesAt(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    private static String read(File file) throws IOException {
        return new String(bytesAt(file), StandardCharsets.UTF_8);
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

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) file.deleteOnExit();
    }
}
