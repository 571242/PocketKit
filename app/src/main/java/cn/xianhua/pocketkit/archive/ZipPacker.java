package cn.xianhua.pocketkit.archive;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 建 ZIP。只建 ZIP：7z / RAR 的写入这个库不做，别假装支持。
 *
 * 输出流由调用方给（SAF 新建的文档），文件名冲突在包内自己解决，从不碰目标目录里的其他文件。
 */
public final class ZipPacker {

    /** 加密方式：不加密，或 AES-256（Windows 7+ / 7-Zip / Keka 都认；XP 时代的工具不认）。 */
    public enum Encryption { NONE, AES256 }

    private ZipPacker() {
    }

    /**
     * @param password 仅 AES256 时使用；为空时降级为不加密，由返回的枚举说明实际写出的方式
     * @return 真正写进包里的加密方式
     */
    public static Encryption pack(List<ArchiveSource> sources, OutputStream out, char[] password,
                                  Encryption requested, ArchiveProgress progress) throws IOException {
        Encryption used = requested == Encryption.AES256 && (password == null || password.length == 0)
                ? Encryption.NONE : requested;
        long total = 0;
        for (ArchiveSource source : sources) {
            if (source.size() > 0) {
                total += source.size();
            }
        }
        Set<String> usedNames = new LinkedHashSet<>();
        Budget budget = Budget.unbounded();
        long done = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out, used == Encryption.AES256 ? password : null)) {
            int index = 0;
            for (ArchiveSource source : sources) {
                progress.checkCancelled();
                ZipParameters parameters = new ZipParameters();
                parameters.setCompressionMethod(CompressionMethod.DEFLATE);
                parameters.setCompressionLevel(CompressionLevel.NORMAL);
                parameters.setFileNameInZip(uniqueInZip(
                        EntryNames.forZipEntry(source.name(), "item-" + (++index)), usedNames));
                if (used == Encryption.AES256) {
                    parameters.setEncryptFiles(true);
                    parameters.setEncryptionMethod(EncryptionMethod.AES);
                    parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                    parameters.setAesVersion(AesVersion.TWO);
                }
                zip.putNextEntry(parameters);
                budget.beginEntry(parameters.getFileNameInZip());
                try (InputStream in = source.open()) {
                    done += Pump.copy(in, zip, budget, progress, source.name(), source.size());
                }
                zip.closeEntry();
                progress.onProgress(source.name(), done, total);
            }
        }
        return used;
    }

    /** 包内重名（用户可能选了同名文件）：加 (1)(2)，绝不互相覆盖。 */
    private static String uniqueInZip(String name, Set<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; !used.add(candidate); i++) {
            candidate = base + " (" + i + ")" + extension;
        }
        return candidate;
    }

    /** 默认包名：<第一个来源的基础名>.zip。 */
    public static String defaultName(String firstSourceName) {
        String base = "打包";
        if (firstSourceName != null && !firstSourceName.trim().isEmpty()) {
            String name = firstSourceName.trim();
            int dot = name.lastIndexOf('.');
            base = dot > 0 ? name.substring(0, dot) : name;
            if (base.length() > 40) {
                base = base.substring(0, 40);
            }
        }
        return EntryNames.cleanSegment(base + ".zip");
    }

    /** 让调用方把包名里的中文长度也算一次，避免超长名字在 SAF 上被截断。 */
    static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
