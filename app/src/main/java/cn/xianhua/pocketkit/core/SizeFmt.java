package cn.xianhua.pocketkit.core;

import java.util.Locale;

/**
 * 字节数格式化与图片压缩参数预估。
 *
 * 预估只给「大致范围」，界面上必须标注是估计值，不能当作承诺。
 */
public final class SizeFmt {

    private SizeFmt() {
    }

    /** 1536 B -> "1.5 KB"。使用 1024 进制，与系统文件管理器的显示习惯一致。 */
    public static String bytes(long b) {
        if (b < 0) {
            return "—";
        }
        if (b < 1024) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** 预计压缩后的体积：按目标像素比例估算，JPEG 质量只作线性近似。 */
    public static long estimateCompressed(long originalBytes, int originalPixels, int targetPixels, int quality) {
        if (originalBytes <= 0 || originalPixels <= 0 || targetPixels <= 0) {
            return -1;
        }
        double pixelRatio = targetPixels / (double) originalPixels;
        double qualityRatio = quality / 90.0;
        double factor = pixelRatio * qualityRatio;
        if (factor > 1.0) {
            factor = 1.0;
        }
        return (long) (originalBytes * factor);
    }

    /** 保持长宽比，把长边缩到 maxLongEdge。 */
    public static int[] fit(int width, int height, int maxLongEdge) {
        if (width <= 0 || height <= 0) {
            return new int[]{0, 0};
        }
        int longEdge = Math.max(width, height);
        if (maxLongEdge <= 0 || longEdge <= maxLongEdge) {
            return new int[]{width, height};
        }
        double scale = maxLongEdge / (double) longEdge;
        int w = (int) Math.round(width * scale);
        int h = (int) Math.round(height * scale);
        return new int[]{Math.max(1, w), Math.max(1, h)};
    }

    /** 多个预设长边，供界面按钮使用。 */
    public static final int[] PRESET_LONG_EDGES = {640, 1080, 1600, 2048};
}
