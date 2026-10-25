package cn.xianhua.pocketkit.archive;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 这一个页面到底支持什么、不支持什么，全写在这里，界面直接用，不许另写一份宣传词。
 *
 * 两类实现：
 *  - ZIP 家族走 zip4j（流式，AES 与传统 ZipCrypto 都能解，能建 AES 加密包）；
 *  - 其余格式走 libarchive（7z / RAR 含 RAR5 / tar 及各种压缩流 / cab / iso / xz…）。
 *
 * 不支持：RAR 与 7z 的「创建」、RAR5 之外的新 RAR 变体写入、加密的 7z（创建）、
 * 分卷包、非 UTF-8 文件名的自动识别（这类名字可能显示为乱码）。
 */
public final class ArchiveFormats {

    public enum Kind { ZIP, LIBARCHIVE, UNKNOWN }

    private static final Set<String> ZIP = new HashSet<>(Arrays.asList(
            "zip", "zipx", "apk", "jar", "war", "ear", "aar", "epub", "cbz", "kmz", "xpi",
            "docx", "xlsx", "pptx", "odt", "ods", "odp"));

    private static final Set<String> LIBARCHIVE = new HashSet<>(Arrays.asList(
            "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "lzma",
            "zst", "tzst", "lz4", "lzo", "cab", "iso", "cpio", "ar", "z", "tar.gz", "tar.bz2",
            "tar.xz", "tar.zst"));

    /** 能被 libarchive 试出来的容器名，用于给用户看。 */
    public static final String SUPPORTED_READ =
            "ZIP / APK / JAR / EPUB 等 ZIP 家族，7z、RAR（含 RAR5）、tar 及 tar.gz/tar.bz2/tar.xz、"
                    + "单层 gz/bz2/xz/zst/lz4、cab、iso。";

    private ArchiveFormats() {
    }

    public static Kind kind(String displayName) {
        String extension = extension(displayName);
        if (ZIP.contains(extension)) {
            return Kind.ZIP;
        }
        if (LIBARCHIVE.contains(extension)) {
            return Kind.LIBARCHIVE;
        }
        return Kind.UNKNOWN;
    }

    /** 名字上像不像压缩包；决定收到的分享是当「要解压的包」还是「要打包的文件」。 */
    public static boolean looksLikeArchive(String displayName) {
        return kind(displayName) != Kind.UNKNOWN;
    }

    /** 扩展名不认识时按文件头判断，认识就按文件头纠正。 */
    public static Kind sniff(byte[] head, String displayName) {
        Kind byName = kind(displayName);
        if (head == null || head.length < 4) {
            return byName;
        }
        if (isZip(head)) {
            return Kind.ZIP;
        }
        if (is7z(head) || isRar(head)) {
            return Kind.LIBARCHIVE;
        }
        return byName;
    }

    static boolean isZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6)
                || (head[2] == 7 && head[3] == 8));
    }

    static boolean is7z(byte[] head) {
        return head.length >= 6 && head[0] == '7' && head[1] == 'z' && head[2] == (byte) 0xBC
                && head[3] == (byte) 0xAF && head[4] == 0x27 && head[5] == 0x1C;
    }

    static boolean isRar(byte[] head) {
        return head.length >= 7 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r'
                && head[3] == '!' && head[4] == 0x1A && head[5] == 0x07;
    }

    public static String extension(String displayName) {
        if (displayName == null) {
            return "";
        }
        String lower = displayName.toLowerCase(Locale.ROOT).trim();
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    /** 界面上的能力声明；同时被 supports()/limitation() 复用，避免两处说法不一致。 */
    public static String limitation() {
        return "只做压缩与解压，不管理、不整理、不改名你已有的文件："
                + "解压只往你选的目录里新建一个独立子目录，不会覆盖或删除原件；"
                + "压缩只在选中的目录里新建一个 ZIP，默认可选 AES-256 密码。"
                + "能读：" + SUPPORTED_READ
                + "。不能读：分卷或多卷包、加密的 7z/RAR（只能提示要密码，暂不支持解）、"
                + "WIM/ACE 等未收录格式；不能创建 7z/RAR，只能创建 ZIP。"
                + "非 UTF-8 文件名的老包可能显示为乱码。"
                + "解压有条目数、单条与总量上限，压缩比异常会停止并清理本次临时文件。";
    }
}
