package cn.xianhua.pocketkit.core;

import java.math.BigInteger;

/**
 * 进制转换：2 / 8 / 10 / 16（以及 2–36 的自由基数）。
 * 使用 BigInteger，因此超出 long 的整数同样正确。
 */
public final class RadixTool {

    public static final int MIN_RADIX = 2;
    public static final int MAX_RADIX = 36;

    /** 一个输入文本在全部常用基数下的读数。 */
    public static final class Readings {
        public final int sourceRadix;
        /** 规范化后的十进制值（可能为负）。 */
        public final BigInteger value;
        /** 以下四个读数都带符号（负数为 "-" 前缀），可直接反解析回 value。 */
        public final String bin;
        public final String oct;
        public final String dec;
        public final String hex;
        /**
         * 十六进制按字节分组，例如 "7F FF"；负数保留前导 "-"，符号不参与分组。
         * 这是展示用的第三个视图，与 hex 同值不同排版。
         */
        public final String hexGrouped;
        /** 补码视图（按 64 位），仅当 |value| 能用 64 位补码表示时非 null。 */
        public final String twosComplement64;
        public final boolean negative;

        Readings(int sourceRadix, BigInteger value) {
            this.sourceRadix = sourceRadix;
            this.value = value;
            this.negative = value.signum() < 0;
            // BigInteger.toString(radix) 对负数直接输出 "-" 前缀，无需自己拼符号。
            this.bin = value.toString(2);
            this.oct = value.toString(8);
            this.dec = value.toString(10);
            this.hex = value.toString(16);
            this.hexGrouped = value.signum() < 0
                    ? "-" + groupHex(value.negate().toString(16))
                    : groupHex(this.hex);
            // 补码需要位宽：统一按 64 位给；超出 64 位可表示范围时不给（不猜位宽）。
            this.twosComplement64 = twosComplement(value);
        }

        /** 64 位补码视图；超出 long 范围（例如 2^64-1）时返回 null，不猜位宽。 */
        private static String twosComplement(BigInteger v) {
            if (v.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                return null;
            }
            long bits = v.longValue();
            StringBuilder sb = new StringBuilder(64);
            for (int i = 63; i >= 0; i--) {
                sb.append(((bits >>> i) & 1L) == 1L ? '1' : '0');
            }
            return sb.toString();
        }

        public String prefixed(int radix, String digits) {
            boolean neg = digits.startsWith("-");
            String body = neg ? digits.substring(1) : digits;
            switch (radix) {
                case 2:
                    return (neg ? "-" : "") + "0b" + body;
                case 8:
                    return (neg ? "-" : "") + "0o" + body;
                case 16:
                    return (neg ? "-" : "") + "0x" + body;
                default:
                    return digits;
            }
        }
    }

    private RadixTool() {
    }

    public static Readings convert(String input, int radix) throws ToolException {
        if (input == null) {
            input = "";
        }
        String s = input.replaceAll("[\\s\\u3000_]", "");
        if (s.isEmpty()) {
            throw new ToolException("没有可转换的内容。输入一个整数，例如十进制 255，或十六进制 FF。");
        }
        if (radix < MIN_RADIX || radix > MAX_RADIX) {
            throw new ToolException("进制 " + radix + " 超出范围，只支持 2 到 36。");
        }
        boolean negative = false;
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        // 允许带常见前缀，避免用户粘贴 0x 前缀时被当成非法字符。
        String lower = s.toLowerCase();
        if (radix == 16 && lower.startsWith("0x")) {
            s = s.substring(2);
        } else if (radix == 2 && lower.startsWith("0b")) {
            s = s.substring(2);
        } else if (radix == 8 && lower.startsWith("0o")) {
            s = s.substring(2);
        }
        if (s.isEmpty()) {
            throw new ToolException("只写了符号或前缀，缺少数字。");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = digitValue(c);
            if (digit < 0 || digit >= radix) {
                throw new ToolException(badDigitMessage(c, i, radix));
            }
        }
        BigInteger v = new BigInteger(s, radix);
        if (negative) {
            v = v.negate();
        }
        return new Readings(radix, v);
    }

    private static String badDigitMessage(char c, int index, int radix) {
        String allowed;
        if (radix <= 10) {
            allowed = "允许 0–" + (radix - 1) + "。";
        } else {
            allowed = "允许 0–9 和 A–" + Character.toUpperCase(Character.forDigit(radix - 1, 36)) + "。";
        }
        return "第 " + (index + 1) + " 个字符「" + c + "」不是 " + radix + " 进制的数字，" + allowed;
    }

    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** 每两位一组，让十六进制也能一眼分段。 */
    public static String groupHex(String hex) {
        StringBuilder sb = new StringBuilder(hex.length() + hex.length() / 2);
        int lead = hex.length() % 2;
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && (i - lead) % 2 == 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(hex.charAt(i)));
        }
        return sb.toString();
    }

    /** 每个基数最多能表示多少位才不至于让界面溢出（仅用于提示，不限制输入）。 */
    public static int digitsFor(BigInteger value, int radix) {
        return value.abs().toString(radix).length();
    }
}
