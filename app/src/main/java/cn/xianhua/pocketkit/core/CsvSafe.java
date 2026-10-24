package cn.xianhua.pocketkit.core;

/**
 * CSV 单元格转义（纯计算，可被纯 Java 测试）。
 *
 * 关键安全点：以 = + - @ 或制表符开头的内容，会被 Excel / WPS / LibreOffice
 * 当成公式执行（CSV 公式注入 / CSV injection）。导出用户自填的分类、备注时
 * 必须中和掉，做法是前置一个单引号，让单元格成为文本。
 */
public final class CsvSafe {

    private CsvSafe() {
    }

    /** 转成一格带引号的 CSV 字段。 */
    public static String cell(String value) {
        String s = value == null ? "" : value;
        if (!s.isEmpty()) {
            char c = s.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                s = "'" + s;
            }
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** 一行若干格。 */
    public static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(cells[i]));
        }
        return sb.toString();
    }
}
