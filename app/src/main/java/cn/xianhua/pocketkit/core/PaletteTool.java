package cn.xianhua.pocketkit.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配色助手与颜色码转换的纯计算（002 / 003）。
 *
 * 传统色数据见 PaletteData，来自两份 MIT 许可的公开数据集，
 * 不是从任何商业 APK 提取的素材。
 *
 * 不依赖 Android：颜色数学全部在 ColorTool 里，本类只做查询、匹配与配色推导。
 */
public final class PaletteTool {

    /** 一个传统色。 */
    public static final class Named {
        public final String name;
        public final String hex;
        /** 分组（中文色）或罗马字读音（日本色）。 */
        public final String extra;
        public final int r;
        public final int g;
        public final int b;

        Named(String name, String hex, String extra) {
            this.name = name;
            this.hex = hex.toUpperCase();
            this.extra = extra == null ? "" : extra;
            try {
                int[] rgb = ColorTool.parseHex(this.hex);
                this.r = rgb[0];
                this.g = rgb[1];
                this.b = rgb[2];
            } catch (ToolException e) {
                throw new IllegalStateException("内置色值表里有非法颜色：" + hex, e);
            }
        }

        public int argb() {
            return ColorTool.argb(r, g, b);
        }

        @Override
        public String toString() {
            return name + " " + hex;
        }
    }

    public enum Family {
        CHINESE("中国传统色"),
        JAPANESE("日本传统色");

        public final String label;

        Family(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Named> CHINESE = new ArrayList<>();
    private static final List<Named> JAPANESE = new ArrayList<>();

    static {
        for (String[] row : PaletteData.CHINESE) {
            CHINESE.add(new Named(row[0], row[1], row[2]));
        }
        for (String[] row : PaletteData.JAPANESE) {
            JAPANESE.add(new Named(row[0], row[1], row[2]));
        }
    }

    private PaletteTool() {
    }

    public static List<Named> all(Family f) {
        return new ArrayList<>(f == Family.JAPANESE ? JAPANESE : CHINESE);
    }

    public static String[] groups() {
        return PaletteData.CHINESE_GROUPS.clone();
    }

    public static List<Named> byGroup(String group) {
        List<Named> out = new ArrayList<>();
        for (Named n : CHINESE) {
            if (n.extra.equals(group)) {
                out.add(n);
            }
        }
        return out;
    }

    public static int totalCount(Family f) {
        return f == Family.JAPANESE ? JAPANESE.size() : CHINESE.size();
    }

    /** 按名称查色（精确或包含）。返回全部命中。 */
    public static List<Named> search(String keyword) {
        List<Named> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String k = keyword.trim();
        for (Named n : CHINESE) {
            if (n.name.contains(k) || n.hex.equalsIgnoreCase(k)) {
                out.add(n);
            }
        }
        for (Named n : JAPANESE) {
            if (n.name.contains(k) || n.extra.equalsIgnoreCase(k) || n.hex.equalsIgnoreCase(k)) {
                out.add(n);
            }
        }
        return out;
    }

    // ---------- 最近色名 ----------

    public static final class Nearest {
        public final Named color;
        /** 色差，越小越接近。0 表示完全一致。 */
        public final double distance;
        /** 相对该传统色的取整差距，用于排序展示。 */
        public final int rank;

        Nearest(Named color, double distance, int rank) {
            this.color = color;
            this.distance = distance;
            this.rank = rank;
        }
    }

    /**
     * 找最接近的传统色。用 CIEDE2000 会更好，但那需要完整的 Lab 管线；
     * 这里用「加权 RGB 距离」，对传统色的命名匹配已经足够，且结果可复现。
     * 距离值本身没有物理含义，只用于排序，界面不展示它。
     */
    public static List<Nearest> nearest(int r, int g, int b, Family family, int limit) {
        List<Named> pool = family == Family.JAPANESE ? JAPANESE : CHINESE;
        List<Nearest> all = new ArrayList<>(pool.size());
        for (Named n : pool) {
            all.add(new Nearest(n, weightedDistance(r, g, b, n.r, n.g, n.b), 0));
        }
        all.sort((a, b2) -> {
            int c = Double.compare(a.distance, b2.distance);
            return c != 0 ? c : a.color.name.compareTo(b2.color.name);
        });
        List<Nearest> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            Nearest n = all.get(i);
            out.add(new Nearest(n.color, n.distance, i + 1));
        }
        return out;
    }

    /** 加权 RGB 距离：人眼对绿色更敏感，因此权重不同。 */
    static double weightedDistance(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rm = (r1 + r2) / 2.0;
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return Math.sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg
                + (2 + (255 - rm) / 256) * db * db);
    }

    // ---------- 配色推导（003 的核心价值） ----------

    /** 一种配色方案。 */
    public static final class Scheme {
        public final String name;
        public final List<Swatch> colors;
        public final String note;

        Scheme(String name, List<Swatch> colors, String note) {
            this.name = name;
            this.colors = colors;
            this.note = note;
        }
    }

    /** 一个色块：十六进制值 + 最接近的传统色名（可能为空）。 */
    public static final class Swatch {
        public final String hex;
        public final String traditionalName;

        Swatch(String hex, String traditionalName) {
            this.hex = hex.toUpperCase();
            this.traditionalName = traditionalName == null ? "" : traditionalName;
        }

        public int argb() throws ToolException {
            int[] rgb = ColorTool.parseHex(hex);
            return ColorTool.argb(rgb[0], rgb[1], rgb[2]);
        }

        @Override
        public String toString() {
            return traditionalName.isEmpty() ? hex : hex + " " + traditionalName;
        }
    }

    /**
     * 生成配色方案。全部从输入色出发按 HSL 推导，不联网、不随机。
     * 返回的色值会反向匹配到最接近的传统色名，方便用户理解。
     */
    public static List<Scheme> schemes(int r, int g, int b) {
        int[] hsl = ColorTool.toHsl(r, g, b);
        int h = hsl[0];
        int s = hsl[1];
        int l = hsl[2];
        List<Scheme> out = new ArrayList<>();

        out.add(new Scheme("互补色",
                namedList(new int[][]{
                        {h, s, l},
                        {(h + 180) % 360, s, l}
                }),
                "色轮上相差 180°，对比最强，适合强调与主色搭配。"));

        out.add(new Scheme("邻近色",
                namedList(new int[][]{
                        {(h + 330) % 360, s, l},
                        {h, s, l},
                        {(h + 30) % 360, s, l}
                }),
                "色轮上相邻 30°，过渡柔和，适合做渐变与背景。"));

        out.add(new Scheme("三角配色",
                namedList(new int[][]{
                        {h, s, l},
                        {(h + 120) % 360, s, l},
                        {(h + 240) % 360, s, l}
                }),
                "色轮上三等分，活泼而平衡。"));

        out.add(new Scheme("分裂互补",
                namedList(new int[][]{
                        {h, s, l},
                        {(h + 150) % 360, s, l},
                        {(h + 210) % 360, s, l}
                }),
                "互补色的两个邻色，比纯互补更容易协调。"));

        out.add(new Scheme("明度阶梯",
                namedList(new int[][]{
                        {h, s, clamp(l - 25, 0, 100)},
                        {h, s, clamp(l - 12, 0, 100)},
                        {h, s, l},
                        {h, s, clamp(l + 12, 0, 100)},
                        {h, s, clamp(l + 25, 0, 100)}
                }),
                "同一色相拉出深浅，适合做层次与状态。"));

        out.add(new Scheme("同色系弱饱和",
                namedList(new int[][]{
                        {h, clamp((int) Math.round(s * 0.35), 0, 100), clamp(l, 0, 100)},
                        {h, clamp((int) Math.round(s * 0.65), 0, 100), clamp(l + 8, 0, 100)},
                        {h, s, l}
                }),
                "降低饱和度做成组配色，用于大面积背景不刺眼。"));

        return out;
    }

    /** 按 HSL 生成色块，并给出最接近的传统色名作为参考标签。 */
    private static List<Swatch> namedList(int[][] hslList) {
        List<Swatch> out = new ArrayList<>();
        for (int[] hsl : hslList) {
            int[] rgb = hslToRgb(hsl[0], hsl[1], hsl[2]);
            String hex = ColorTool.toHex(rgb[0], rgb[1], rgb[2]);
            List<Nearest> near = nearest(rgb[0], rgb[1], rgb[2], Family.CHINESE, 1);
            out.add(new Swatch(hex, near.isEmpty() ? "" : near.get(0).color.name));
        }
        return out;
    }

    public static int[] hslToRgb(int h, int s, int l) {
        double hh = ((h % 360) + 360) % 360 / 360.0;
        double ss = clamp(s, 0, 100) / 100.0;
        double ll = clamp(l, 0, 100) / 100.0;
        double q = ll < 0.5 ? ll * (1 + ss) : ll + ss - ll * ss;
        double p = 2 * ll - q;
        return new int[]{
                (int) Math.round(hue2rgb(p, q, hh + 1.0 / 3.0) * 255),
                (int) Math.round(hue2rgb(p, q, hh) * 255),
                (int) Math.round(hue2rgb(p, q, hh - 1.0 / 3.0) * 255)
        };
    }

    private static double hue2rgb(double p, double q, double t) {
        double tt = t;
        if (tt < 0) {
            tt += 1;
        }
        if (tt > 1) {
            tt -= 1;
        }
        if (tt < 1.0 / 6) {
            return p + (q - p) * 6 * tt;
        }
        if (tt < 1.0 / 2) {
            return q;
        }
        if (tt < 2.0 / 3) {
            return p + (q - p) * (2.0 / 3 - tt) * 6;
        }
        return p;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 002 一站式：把输入颜色在 HEX / RGB / HSL / HSV / CMYK 之间转换。 */
    public static final class Readings {
        public final String hex;
        public final String rgbText;
        public final String hslText;
        public final String hsvText;
        public final String cmykText;
        public final int argb;
        public final boolean preferDarkText;

        Readings(String hex, String rgbText, String hslText, String hsvText,
                 String cmykText, int argb, boolean preferDarkText) {
            this.hex = hex;
            this.rgbText = rgbText;
            this.hslText = hslText;
            this.hsvText = hsvText;
            this.cmykText = cmykText;
            this.argb = argb;
            this.preferDarkText = preferDarkText;
        }
    }

    public static Readings read(String hexInput) throws ToolException {
        int[] rgb = ColorTool.parseHex(hexInput);
        return read(rgb[0], rgb[1], rgb[2]);
    }

    public static Readings read(int r, int g, int b) {
        int[] hsv = rgbToHsv(r, g, b);
        int[] hsl = ColorTool.toHsl(r, g, b);
        int[] cmyk = rgbToCmyk(r, g, b);
        return new Readings(
                ColorTool.toHex(r, g, b),
                ColorTool.toRgbText(r, g, b),
                ColorTool.toHslText(r, g, b),
                "hsv(" + hsv[0] + ", " + hsv[1] + "%, " + hsv[2] + "%)",
                "cmyk(" + cmyk[0] + "%, " + cmyk[1] + "%, " + cmyk[2] + "%, " + cmyk[3] + "%)",
                ColorTool.argb(r, g, b),
                ColorTool.preferDarkText(r, g, b));
    }

    public static int[] rgbToHsv(int r, int g, int b) {
        double rr = ColorTool.clamp255(r) / 255.0;
        double gg = ColorTool.clamp255(g) / 255.0;
        double bb = ColorTool.clamp255(b) / 255.0;
        double max = Math.max(rr, Math.max(gg, bb));
        double min = Math.min(rr, Math.min(gg, bb));
        double d = max - min;
        double h;
        if (d == 0) {
            h = 0;
        } else if (max == rr) {
            h = 60 * (((gg - bb) / d) % 6);
        } else if (max == gg) {
            h = 60 * ((bb - rr) / d + 2);
        } else {
            h = 60 * ((rr - gg) / d + 4);
        }
        if (h < 0) {
            h += 360;
        }
        double s = max == 0 ? 0 : d / max;
        return new int[]{
                (int) Math.round(h),
                (int) Math.round(s * 100),
                (int) Math.round(max * 100)
        };
    }

    public static int[] rgbToCmyk(int r, int g, int b) {
        double rr = ColorTool.clamp255(r) / 255.0;
        double gg = ColorTool.clamp255(g) / 255.0;
        double bb = ColorTool.clamp255(b) / 255.0;
        double k = 1 - Math.max(rr, Math.max(gg, bb));
        if (k >= 1.0) {
            // 纯黑：CMYK 里 C/M/Y 都是 0，K 是 100%
            return new int[]{0, 0, 0, 100};
        }
        double c = (1 - rr - k) / (1 - k);
        double m = (1 - gg - k) / (1 - k);
        double y = (1 - bb - k) / (1 - k);
        return new int[]{
                (int) Math.round(c * 100),
                (int) Math.round(m * 100),
                (int) Math.round(y * 100),
                (int) Math.round(k * 100)
        };
    }

    /** 008/003 相关：在两个颜色之间插值出 N 个过渡色，用于渐变预览。 */
    public static List<String> gradient(String fromHex, String toHex, int steps) throws ToolException {
        int[] a = ColorTool.parseHex(fromHex);
        int[] b = ColorTool.parseHex(toHex);
        int n = Math.max(2, Math.min(64, steps));
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            out.add(ColorTool.toHex(
                    (int) Math.round(a[0] + (b[0] - a[0]) * t),
                    (int) Math.round(a[1] + (b[1] - a[1]) * t),
                    (int) Math.round(a[2] + (b[2] - a[2]) * t)));
        }
        return new ArrayList<>(out);
    }
}
