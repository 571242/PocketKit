package cn.xianhua.pocketkit.device;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * 文件清理的判定规则。纯逻辑，可脱机测试。
 *
 * 这些规则只负责"哪些条目符合条件"，不负责删除。
 * 删除必须由用户在界面上明确确认，见 StorageCleanTool。
 */
public final class FileRules {

    private FileRules() {
    }

    /** 默认"大文件"门槛：50 MB。 */
    public static final long BIG_FILE_BYTES = 50L * 1024 * 1024;

    /** 快速安装包校验：扩展名加 APK ZIP 中存在 AndroidManifest.xml。 */
    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    public static boolean isApk(File file) {
        if (file == null || !isApk(file.getName()) || !file.isFile()) return false;
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("AndroidManifest.xml") != null;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * 0 字节文件。
     *
     * 注意用 size == 0 而不是 size <= 0：拿不到大小时应传 -1，代表"未知"，
     * 未知不能被当成空文件删掉。
     */
    public static boolean isEmptyFile(long size) {
        return size == 0;
    }

    /** 超过门槛的大文件。size 为 -1（未知）时不算大文件。 */
    public static boolean isBigFile(long size, long threshold) {
        return size > 0 && size >= threshold;
    }

    /**
     * 空目录判定。
     *
     * 只有"子项数量为 0"才算空；子项数量未知时不算，
     * 因为把"没读到"当成"里面没东西"会导致误删整个目录树。
     */
    public static boolean isEmptyDir(long childCount) {
        return childCount == 0;
    }

    public static String reasonFor(String id) {
        switch (id) {
            case "074": return "安装包（.apk）";
            case "076": return "大文件（≥ " + (BIG_FILE_BYTES / 1024 / 1024) + " MB）";
            case "078": return "空文件夹（内部没有任何子项）";
            case "079": return "0 字节文件";
            default: return "未知识别类型";
        }
    }

    /**
     * 生成一个不冲突的目标文件名：已存在时加 (1)(2)…，**绝不覆盖**已有文件。
     *
     * 为什么抽成纯逻辑：导入文件时"覆盖同名"会毁掉对方刚通过 FTP 上传的内容，
     * 这个行为必须有测试盯着，而测试不该依赖 Android。
     */
    public static java.io.File uniqueTarget(java.io.File dir, String name) {
        java.io.File candidate = new java.io.File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            candidate = new java.io.File(dir, base + "(" + i + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new java.io.File(dir, base + "_" + System.currentTimeMillis() + extension);
    }
}