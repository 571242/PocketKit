package cn.xianhua.pocketkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Purpose-based navigation, independent of the implementation Activity. */
public final class ToolCatalog {
    public static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "图片编辑", "文档扫描", "影音变声", "文字处理", "计算换算", "时间效率",
            "生活查询", "文件管理", "网络工具", "设备测量", "自动化"));
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();
    static {
        group("图片编辑", "002 003 004 005 007 008 010 011 012 013 014 015 018 019 022 023 024 025 026 027 029 030 032 033");
        group("文档扫描", "020 031 034 A01 A02 A03 A04");
        group("影音变声", "048 080 A08 V01 V02 V03");
        group("文字处理", "035 036 038 039 040 042 043 044 046 094 096 104");
        group("计算换算", "037 050 051 052 053 059 060 063 066 B02");
        group("时间效率", "057 062 064 065 087");
        group("生活查询", "054 056 061 095 097 105 106 111 113 B05");
        group("文件管理", "074 075 076 078 079 M01 M02 M03 M04 M05 M06");
        group("网络工具", "081 083 084 086 088 089");
        group("设备测量", "067 068 069 070 072 073 077");
        group("自动化", "071 C01 C02");
    }
    private ToolCatalog() { }
    private static void group(String category, String ids) {
        for (String id : ids.split(" ")) {
            if (GROUPS.put(id, category) != null) throw new IllegalStateException("Repeated tool " + id);
        }
    }
    public static String category(String id, String fallback) { return GROUPS.getOrDefault(id, fallback); }
    public static String canonicalId(String id) { return "048".equals(id) || "V02".equals(id) ? "V01" : id; }
    public static boolean visible(String id) { return canonicalId(id).equals(id); }
    public static int order(String category) {
        int position = CATEGORIES.indexOf(category);
        return position < 0 ? CATEGORIES.size() : position;
    }
    public static String name(String id, String fallback) {
        switch (id) {
            case "V01": return "声音工作台";
            case "A01": return "PDF 页面编辑";
            case "A02": return "图片与 PDF 互转";
            case "A03": return "文档扫描";
            case "A04": return "隐私打码";
            case "B05": return "记账与报销";
            case "M01": return "双窗口管理器";
            case "M02": return "压缩包浏览";
            case "077": return "设备信息";
            case "081": return "局域网文件共享";
            case "111": return "QQ 会话跳转";
            default: return fallback;
        }
    }
    public static String keywords(String id) {
        return "V01".equals(id) ? "文字转语音 朗读 TTS 录音变声 音频变声转换 048 V02" : "";
    }
}
