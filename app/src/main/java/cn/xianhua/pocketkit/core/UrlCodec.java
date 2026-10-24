package cn.xianhua.pocketkit.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * URL 百分号编解码。
 * 两种模式在「空格」上处理不同，本工具明确区分，不混用：
 *   表单模式（application/x-www-form-urlencoded）：空格 -&gt; +
 *   组件模式（RFC 3986，用于路径/查询值）：空格 -&gt; %20
 */
public final class UrlCodec {

    public enum Space {
        /** 空格写成 %20（RFC 3986 组件）。 */
        PERCENT,
        /** 空格写成 +（表单编码）。 */
        PLUS
    }

    private UrlCodec() {
    }

    private static boolean unreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    public static String encode(String s, Space space) {
        if (s == null) {
            return "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == ' ' && space == Space.PLUS) {
                sb.append('+');
            } else if (unreserved(c)) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(hexDigit((c >> 4) & 0xF));
                sb.append(hexDigit(c & 0xF));
            }
        }
        return sb.toString();
    }

    public static String decode(String s, Space space) throws ToolException {
        if (s == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= n) {
                    throw new ToolException("第 " + (i + 1) + " 个字符是 %，但它后面不足两位十六进制。"
                            + "百分号编码必须是 % 加两位十六进制，例如 %20 表示空格。");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new ToolException("第 " + (i + 1) + " 个字符开始的「%"
                            + s.charAt(i + 1) + s.charAt(i + 2) + "」不是合法的百分号编码，"
                            + "两位都必须是 0–9 或 A–F。");
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else if (c == '+' && space == Space.PLUS) {
                out.write(' ');
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 统计原文里出现过哪些转义字符，供界面提示用。 */
    public static int countPercent(String s) {
        if (s == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '%') {
                count++;
            }
        }
        return count;
    }

    public static int countPlus(String s) {
        if (s == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '+') {
                count++;
            }
        }
        return count;
    }

    private static char hexDigit(int v) {
        return (char) (v < 10 ? ('0' + v) : ('A' + v - 10));
    }
}
