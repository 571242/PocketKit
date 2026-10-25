package cn.xianhua.pocketkit.archive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨工具互操作：包由「别人」造（Python zipfile / 系统 unzip），我们用真实代码去解；
 * 我们造的包再交给别人读。只测标准工具链能复现的东西，不依赖设备。
 *
 * 运行前由 interop.sh 先生成 fixture，并在我们造包之后用 python 复核。
 */
public final class ArchiveInteropTest {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "interop");
        File fixtures = new File(work, "fixtures");
        File out = new File(work, "out");
        deleteTree(out);
        if (!out.mkdirs() && !out.isDirectory()) throw new IOException("建不出 " + out);

        File pythonZip = new File(fixtures, "python-made.zip");
        if (!pythonZip.isFile()) throw new IOException("缺少 fixture：" + pythonZip);
        File unzipZip = new File(fixtures, "system-zip.zip");
        if (!unzipZip.isFile()) throw new IOException("缺少 fixture：" + unzipZip);

        pythonMadeZip(pythonZip, out);
        systemZip(unzipZip, out);
        ourOwnZipForOthers(work);

        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " / " + checks);
            for (String failure : failures) System.out.println("  - " + failure);
            System.exit(1);
        }
        System.out.println("ArchiveInteropTest OK: " + checks + " checks");
    }

    /** Python zipfile 造的包：嵌套目录、UTF-8 名、空文件、1 MiB 二进制、只读权限位。 */
    private static void pythonMadeZip(File zip, File out) throws Exception {
        MemoryOutput output = new MemoryOutput(new File(out, "from-python"));
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, zip.length())
                .extract(open(zip), zip.getName(), null, output, "py", new Silent());
        check(result.complete(), "Python 造的包解完：" + result.stopped);
        check(result.files == 5, "解出 5 个文件，实际 " + result.files);
        File base = new File(out, "from-python/py");
        check("中文内容".equals(read(new File(base, "文档/报告.txt"))), "UTF-8 名与内容都对");
        check(new File(base, "文档/empty.txt").length() == 0, "空文件还原");
        byte[] expected = bytes(1 << 20, (byte) 0x5A);
        check(Arrays.equals(expected, bytesAt(new File(base, "bin/blob.dat"))),
                "1 MiB 二进制逐字节一致");
        check("根目录文件".equals(read(new File(base, "root.txt"))), "根级文件正常");
        check("深层".equals(read(new File(base, "a/b/c/deep.txt"))), "三层嵌套还原");
    }

    /** 压缩后再解：Python 用 deflate 压缩，我们解出来要和原文一致。 */
    private static void systemZip(File zip, File out) throws Exception {
        MemoryOutput output = new MemoryOutput(new File(out, "from-system"));
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, zip.length())
                .extract(open(zip), zip.getName(), null, output, "sys", new Silent());
        File base = new File(out, "from-system/sys");
        check(result.complete(), "deflate 包解完：" + result.stopped);
        check(result.files >= 1, "至少解出 1 个文件，实际 " + result.files);
        byte[] expected = bytes(400_000, (byte) 0x7E);
        check(Arrays.equals(expected, bytesAt(new File(base, "compressible.bin"))),
                "deflate 还原后逐字节一致（说明 CRC 校验也过了）");
    }

    /** 我们造的包交给外部工具读：只检查文件落盘，真正的「别人能不能开」由 interop.sh 的 python 复核。 */
    private static void ourOwnZipForOthers(File work) throws Exception {
        File zip = new File(work, "ours.zip");
        char[] password = "interop-pass".toCharArray();
        try (OutputStream stream = new FileOutputStream(zip)) {
            ZipPacker.pack(Arrays.asList(
                    source("plain.txt", "外部工具要能读出来"),
                    source("中文名.txt", "UTF-8 文件名"),
                    source("nested/data.bin", bytes(200_000, (byte) 0x33))),
                    stream, password, ZipPacker.Encryption.AES256, new Silent());
        }
        check(zip.isFile() && zip.length() > 0, "我们造出了非空 ZIP");
        // 自己再解一遍，确认往返；交给 python 的部分在 interop.sh 里做。
        MemoryOutput output = new MemoryOutput(new File(work, "ours-out"));
        ExtractResult result = new ZipExtractor(ArchiveLimits.DEFAULTS, zip.length())
                .extract(open(zip), zip.getName(), password, output, "ours", new Silent());
        check(result.files == 3, "自造的加密包解出 3 个文件，实际 " + result.files);
        File landed = new File(work, "ours-out/ours");
        check("外部工具要能读出来".equals(read(new File(landed, "plain.txt"))),
                "自造加密包内容一致");
        check(new File(landed, "中文名.txt").isFile(), "中文名条目写出去了");
        // 打包来源只有显示名（SAF 不给真实路径），所以包内一律平铺到最后一层。
        check(Arrays.equals(bytes(200_000, (byte) 0x33), bytesAt(new File(landed, "data.bin"))),
                "包内名平铺为最后一段，内容一致");
        plainZipForOtherTools(work);
    }

    /** 未加密包交给外部工具读：条目名、内容、CRC 都必须对得上。 */
    private static void plainZipForOtherTools(File work) throws Exception {
        File zip = new File(work, "ours-plain.zip");
        try (OutputStream stream = new FileOutputStream(zip)) {
            ZipPacker.Encryption used = ZipPacker.pack(Arrays.asList(
                    source("note.txt", "别只做空壳"),
                    source("中文名.txt", "UTF-8"),
                    source("bin.dat", bytes(300_000, (byte) 0x41))),
                    stream, null, ZipPacker.Encryption.AES256, new Silent());
            check(used == ZipPacker.Encryption.NONE, "没给密码时不加密，不假装加了密");
        }
        check(zip.isFile() && zip.length() > 0, "造出未加密 ZIP");
    }

    // ---------------- 替身 ----------------

    private static final class MemoryOutput implements OutputDirectory {
        private final File base;
        private final Set<String> dirs = new LinkedHashSet<>();

        MemoryOutput(File base) {
            this.base = base;
        }

        @Override public void mkdir(String path) throws IOException {
            String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            if (normalized.isEmpty()) return;
            File dir = resolve(normalized);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("建不出目录：" + normalized);
            }
            dirs.add(normalized);
        }

        @Override public OutputStream create(String path) throws IOException {
            int slash = path.lastIndexOf('/');
            if (slash > 0) mkdir(path.substring(0, slash));
            File file = resolve(path);
            if (!file.createNewFile()) throw new IOException("拒绝覆盖：" + path);
            return new FileOutputStream(file);
        }

        @Override public void delete(String path) throws IOException {
            File file = resolve(path);
            if (file.exists() && !file.delete()) throw new IOException("删不掉：" + path);
        }

        @Override public String describe() {
            return base.getAbsolutePath();
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
        @Override public void onProgress(String label, long done, long total) {
        }

        @Override public boolean isCancelled() {
            return false;
        }
    }

    private static ArchiveSource source(final String name, final String text) {
        return source(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static ArchiveSource source(final String name, final byte[] data) {
        return new ArchiveSource() {
            @Override public String name() {
                return name;
            }

            @Override public long size() {
                return data.length;
            }

            @Override public InputStream open() {
                return new java.io.ByteArrayInputStream(data);
            }
        };
    }

    // ---------------- 小工具 ----------------

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) failures.add(label);
    }

    private static InputStream open(File file) throws IOException {
        return new java.io.BufferedInputStream(new java.io.FileInputStream(file), Pump.CHUNK);
    }

    private static byte[] bytes(int size, byte fill) {
        byte[] data = new byte[size];
        Arrays.fill(data, fill);
        return data;
    }

    private static byte[] bytesAt(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static String read(File file) throws IOException {
        return new String(bytesAt(file), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) file.deleteOnExit();
    }
}
