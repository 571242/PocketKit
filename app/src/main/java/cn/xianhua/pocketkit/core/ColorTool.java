package cn.xianhua.pocketkit.core;

import java.util.Locale;

/**
 * 取色相关的纯计算：RGB &lt;-&gt; HEX，以及 HSL 与亮度。
 * 坐标映射由界面层完成，本类只负责颜色数学，因此可被纯 Java 测试。
 */
public final class ColorTool {

    /** sRGB 相对亮度（WCAG 定义），0–1。 */
    public static double relativeLuminance(int r, int g, int b) {
        double[] c = new double[3];
        c[0] = channel(r);
        c[1] = channel(g);
        c[2] = channel(b);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double channel(int v) {
        double s = clamp255(v) / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG 对比度，1–21。 */
    public static double contrastRatio(double lumA, double lumB) {
        double hi = Math.max(lumA, lumB);
        double lo = Math.min(lumA, lumB);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 在这个底色上，正文该用深色还是浅色。 */
    public static boolean preferDarkText(int r, int g, int b) {
        double lum = relativeLuminance(r, g, b);
        double withWhite = contrastRatio(lum, 1.0);
        double withBlack = contrastRatio(lum, 0.0);
        return withBlack >= withWhite;
    }

    public static String toHex(int r, int g, int b) {
        return String.format(Locale.US, "#%02X%02X%02X", clamp255(r), clamp255(g), clamp255(b));
    }

    public static String toRgbText(int r, int g, int b) {
        return "rgb(" + clamp255(r) + ", " + clamp255(g) + ", " + clamp255(b) + ")";
    }

    /** 0–360, 0–100, 0–100。 */
    public static int[] toHsl(int r, int g, int b) {
        double rr = clamp255(r) / 255.0;
        double gg = clamp255(g) / 255.0;
        double bb = clamp255(b) / 255.0;
        double max = Math.max(rr, Math.max(gg, bb));
        double min = Math.min(rr, Math.min(gg, bb));
        double l = (max + min) / 2.0;
        double h;
        double s;
        if (max == min) {
            h = 0;
            s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);
            if (max == rr) {
                h = (gg - bb) / d + (gg < bb ? 6.0 : 0.0);
            } else if (max == gg) {
                h = (bb - rr) / d + 2.0;
            } else {
                h = (rr - gg) / d + 4.0;
            }
            h *= 60.0;
        }
        return new int[]{
                (int) Math.round(h),
                (int) Math.round(s * 100.0),
                (int) Math.round(l * 100.0)
        };
    }

    public static String toHslText(int r, int g, int b) {
        int[] hsl = toHsl(r, g, b);
        return "hsl(" + hsl[0] + ", " + hsl[1] + "%, " + hsl[2] + "%)";
    }

    /** ARGB 打包，供界面直接画色块。 */
    public static int argb(int r, int g, int b) {
        return 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    public static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 解析 #RGB / #RRGGBB / #AARRGGBB，供用户手输颜色后回读。 */
    public static int[] parseHex(String text) throws ToolException {
        if (text == null) {
            throw new ToolException("请先输入颜色值，例如 #3A6EA5。");
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                sb.append(s.charAt(i)).append(s.charAt(i));
            }
            s = sb.toString();
        }
        if (s.length() == 8) {
            // 形如 AARRGGBB，丢掉 alpha 后取 RGB。
            s = s.substring(2);
        }
        if (s.length() != 6) {
            throw new ToolException("「" + text.trim() + "」不是有效的十六进制颜色。"
                    + "可用 #RGB 或 #RRGGBB，例如 #3A6EA5。");
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                throw new ToolException("「" + text.trim() + "」含有非十六进制字符。"
                        + "只允许 0–9 和 A–F。");
            }
        }
        return new int[]{
                Integer.parseInt(s.substring(0, 2), 16),
                Integer.parseInt(s.substring(2, 4), 16),
                Integer.parseInt(s.substring(4, 6), 16)
        };
    }
}
