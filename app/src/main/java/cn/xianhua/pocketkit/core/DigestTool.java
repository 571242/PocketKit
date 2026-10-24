package cn.xianhua.pocketkit.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 / SHA-256 摘要。
 *
 * 注意措辞：这是「摘要」，不是「加密」。摘要是单向的，无法从结果还原原文，
 * 界面上必须如实这么写，避免用户把它当成加密工具使用。
 */
public final class DigestTool {

    public static final String MD5 = "MD5";
    public static final String SHA256 = "SHA-256";

    private DigestTool() {
    }

    public static String hex(String algorithm, String input) {
        if (input == null) {
            input = "";
        }
        return hex(algorithm, input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(String algorithm, byte[] data) {
        MessageDigest md = newDigest(algorithm);
        byte[] out = md.digest(data);
        return toHex(out);
    }

    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // JDK / Android 均内置 MD5 与 SHA-256，正常不会到这里。
            throw new IllegalStateException("当前系统不支持 " + algorithm + " 摘要算法", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX[(v >> 4) & 0xF]);
            sb.append(HEX[v & 0xF]);
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 分组显示成 8 位一段，便于肉眼比对。 */
    public static String group(String hex, int groupSize) {
        if (hex == null || groupSize <= 0) {
            return hex == null ? "" : hex;
        }
        StringBuilder sb = new StringBuilder(hex.length() + hex.length() / groupSize + 1);
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && i % groupSize == 0) {
                sb.append(' ');
            }
            sb.append(hex.charAt(i));
        }
        return sb.toString();
    }

    public static int bitLength(String algorithm) {
        return MD5.equals(algorithm) ? 128 : 256;
    }
}
