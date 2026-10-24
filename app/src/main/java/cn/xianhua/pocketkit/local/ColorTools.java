package cn.xianhua.pocketkit.local;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.ColorTool;
import cn.xianhua.pocketkit.core.PaletteTool;
import cn.xianhua.pocketkit.core.ToolException;

/** 002 颜色码转换、003 配色助手。 */
public final class ColorTools implements LocalTool {

    @Override public String[] ids() {
        return new String[]{"002", "003"};
    }

    @Override public Map<String, String> limitations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("002", "支持 HEX ↔ RGB ↔ HSL ↔ HSV ↔ CMYK 与明暗文字建议。"
                + "不做屏幕取色（那需要另一套权限与界面），也不做 ICC 色彩管理，"
                + "因此 CMYK 是按 sRGB 直接换算的近似值，仅供屏幕参考，不能当作印刷分色。");
        m.put("003", "内置传统色参考，"
                + "不是从商业应用提取的素材。配色方案由输入色按 HSL 推导；"
                + "最接近的传统色名是按加权 RGB 距离匹配的近似结果，不代表该色就是那个传统色。");
        return m;
    }

    @Override public void install(LocalToolsActivity host) {
        if ("002".equals(host.toolIdOf())) {
            installColorConvert(host);
        } else {
            installPalette(host);
        }
    }

    // ---------- 002 ----------

    private void installColorConvert(LocalToolsActivity host) {
        EditText hex = host.uiField("颜色值", "#3A6EA5 或 3A6EA5");
        host.uiNote("输入 #RRGGBB、#RGB 或 #AARRGGBB。也可以只填 RGB 三个数。");
        EditText rgb = host.uiField("或者填 R,G,B", "58,110,165");

        View swatch = new View(host);
        host.uiAdd(swatch, 64);
        TextView dark = host.uiNote("色块上应使用深色还是浅色文字，会随输入一起给出。");

        host.uiButton("转换", () -> {
            try {
                int[] v = hex.getText().toString().trim().isEmpty()
                        ? parseRgb(rgb.getText().toString())
                        : ColorTool.parseHex(hex.getText().toString());
                PaletteTool.Readings r = PaletteTool.read(v[0], v[1], v[2]);
                swatch.setBackground(new ColorDrawable(r.argb));
                dark.setText(r.preferDarkText
                        ? "这个底色偏亮，文字用深色（例如 #182A2E）可读性更好。"
                        : "这个底色偏暗，文字用浅色（例如 #FFFFFF）可读性更好。");
                int[] hsl = ColorTool.toHsl(v[0], v[1], v[2]);
                double ratio = ColorTool.contrastRatio(
                        ColorTool.relativeLuminance(v[0], v[1], v[2]),
                        r.preferDarkText ? 0.0 : 1.0);
                host.uiShow("HEX      " + r.hex
                        + "\nRGB      " + r.rgbText
                        + "\nHSL      " + r.hslText
                        + "\nHSV      " + r.hsvText
                        + "\nCMYK     " + r.cmykText
                        + "\nARGB     " + String.format(java.util.Locale.US, "#%08X", r.argb)
                        + "\n色相/饱和/明度  " + hsl[0] + "° / " + hsl[1] + "% / " + hsl[2] + "%"
                        + "\n与" + (r.preferDarkText ? "黑" : "白") + "的对比度  "
                        + String.format(java.util.Locale.US, "%.2f", ratio) + ":1"
                        + "\n\nCMYK 按 sRGB 直接换算，是屏幕近似值，不是印刷分色。");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });

        host.uiNote("常用色：");
        String[] quick = {"#3A6EA5", "#0E6E6B", "#B9660E", "#A63232", "#2E7D4F",
                "#000000", "#FFFFFF", "#808080"};
        LinearLayout row = quickRow(host);
        for (String q : quick) {
            row.addView(chip(host, q, q, () -> {
                hex.setText(q);
                rgb.setText("");
            }));
        }
        host.uiAdd(row);
    }

    private static int[] parseRgb(String text) throws ToolException {
        String[] parts = text.replace("，", ",").split(",");
        if (parts.length != 3) {
            throw new ToolException("请填写 R,G,B 三个 0–255 的整数，例如 58,110,165。");
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new ToolException("「" + parts[i].trim() + "」不是整数。三个分量都要是 0–255 的整数。");
            }
            if (out[i] < 0 || out[i] > 255) {
                throw new ToolException("分量 " + out[i] + " 超出范围，只能是 0–255。");
            }
        }
        return out;
    }

    // ---------- 003 ----------

    private void installPalette(LocalToolsActivity host) {
        host.uiNote("输入一个颜色，查看它对应的传统色与配色方案。");
        EditText hex = host.uiField("颜色值", "#0E6E6B");

        host.uiNote("传统色库：" + PaletteTool.totalCount(PaletteTool.Family.CHINESE)
                + " 条中国传统色 / " + PaletteTool.totalCount(PaletteTool.Family.JAPANESE)
                + " 条日本传统色。数据来源与许可见文件头注释。");

        host.uiButton("查看配色", () -> {
            try {
                int[] v = ColorTool.parseHex(hex.getText().toString());
                StringBuilder sb = new StringBuilder();
                sb.append("输入色 ").append(ColorTool.toHex(v[0], v[1], v[2])).append('\n');
                sb.append("最接近的中国传统色：\n");
                for (PaletteTool.Nearest n : PaletteTool.nearest(v[0], v[1], v[2],
                        PaletteTool.Family.CHINESE, 3)) {
                    sb.append("  ").append(n.color.name).append(' ').append(n.color.hex)
                            .append("（").append(n.color.extra).append("）\n");
                }
                sb.append("最接近的日本传统色：\n");
                for (PaletteTool.Nearest n : PaletteTool.nearest(v[0], v[1], v[2],
                        PaletteTool.Family.JAPANESE, 3)) {
                    sb.append("  ").append(n.color.name).append(' ').append(n.color.hex)
                            .append("  ").append(n.color.extra).append('\n');
                }
                sb.append("\n配色方案（按 HSL 推导）：\n");
                for (PaletteTool.Scheme s : PaletteTool.schemes(v[0], v[1], v[2])) {
                    sb.append("\n[").append(s.name).append("]\n");
                    for (PaletteTool.Swatch c : s.colors) {
                        sb.append("  ").append(c.hex);
                        if (!c.traditionalName.isEmpty()) {
                            sb.append("  ≈").append(c.traditionalName);
                        }
                        sb.append('\n');
                    }
                    sb.append("  ").append(s.note).append('\n');
                }
                host.uiShow(sb.toString());
                renderSchemes(host, v[0], v[1], v[2]);
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });

        host.uiNote("按传统色分组浏览：");
        String[] groups = PaletteTool.groups();
        for (String g : groups) {
            List<PaletteTool.Named> list = PaletteTool.byGroup(g);
            TextView label = host.uiNote(g + "（" + list.size() + "）");
            label.setTextSize(15);
            LinearLayout row = quickRow(host);
            for (PaletteTool.Named n : list) {
                row.addView(chip(host, n.hex, n.name, () -> hex.setText(n.hex)));
            }
            host.uiAdd(row);
        }
    }

    private LinearLayout schemeRow;

    private void renderSchemes(LocalToolsActivity host, int r, int g, int b) {
        if (schemeRow != null) {
            host.uiBody().removeView(schemeRow);
        }
        schemeRow = new LinearLayout(host);
        schemeRow.setOrientation(LinearLayout.VERTICAL);
        for (PaletteTool.Scheme s : PaletteTool.schemes(r, g, b)) {
            TextView t = new TextView(host);
            t.setText(s.name);
            t.setTextSize(14);
            schemeRow.addView(t);
            LinearLayout row = quickRow(host);
            for (PaletteTool.Swatch sw : s.colors) {
                try {
                    row.addView(chip(host, sw.hex, sw.traditionalName, () -> {
                    }));
                } catch (RuntimeException ignored) {
                    // 色值来自内部推导，解析失败只可能是数据被改坏，跳过不阻塞其它色块
                }
            }
            schemeRow.addView(row);
        }
        host.uiAdd(schemeRow);
    }

    // ---------- 小控件 ----------

    private static LinearLayout quickRow(LocalToolsActivity host) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** 一个色块按钮：上面是颜色，下面是名称。 */
    private static View chip(LocalToolsActivity host, String hex, String label, Runnable onClick) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = host.uiDp(4);
        box.setPadding(pad, pad, pad, pad);

        View color = new View(host);
        try {
            color.setBackground(new ColorDrawable(Color.parseColor(hex)));
        } catch (IllegalArgumentException e) {
            color.setBackground(new ColorDrawable(Color.GRAY));
        }
        box.addView(color, new LinearLayout.LayoutParams(host.uiDp(44), host.uiDp(30)));

        TextView t = new TextView(host);
        t.setText(label);
        t.setTextSize(11);
        t.setMaxLines(2);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        box.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = host.uiDp(6);
        box.setLayoutParams(lp);
        return box;
    }
}
