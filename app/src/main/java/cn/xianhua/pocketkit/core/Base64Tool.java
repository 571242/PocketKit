package cn.xianhua.pocketkit.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64（UTF-8 文本 &lt;-&gt; Base64）编解码。
 * 严格解码：失败时给出具体位置和原因，不返回猜出来的结果。
 */
public final class Base64Tool {

    public static final class Decoded {
        /** 按 UTF-8 解释出来的文本；原文不是 UTF-8 文本时含替换符 U+FFFD。 */
        public final String text;
        /** 解码出的原始字节数。 */
        public final int byteLength;
        /** 原文不是合法的 UTF-8 文本。 */
        public final boolean notUtf8Text;
        /** 输入使用了 URL 安全字母表（- 和 _）。 */
        public final boolean urlSafeAlphabet;
        /** 输入含空白并被自动忽略。 */
        public final boolean whitespaceIgnored;

        Decoded(String text, int byteLength, boolean notUtf8Text,
                boolean urlSafeAlphabet, boolean whitespaceIgnored) {
            this.text = text;
            this.byteLength = byteLength;
            this.notUtf8Text = notUtf8Text;
            this.urlSafeAlphabet = urlSafeAlphabet;
            this.whitespaceIgnored = whitespaceIgnored;
        }
    }

    private Base64Tool() {
    }

    public static String encode(String plain) {
        if (plain == null) {
            plain = "";
        }
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static Decoded decode(String raw) throws ToolException {
        if (raw == null) {
            raw = "";
        }
        String stripped = raw.replaceAll("[\\s\\u3000\\uFEFF]+", "");
        if (stripped.isEmpty()) {
            throw new ToolException("没有可解码的内容。把 Base64 文本粘贴到输入框，或先点「示例」。");
        }
        boolean whitespaceIgnored = !stripped.equals(raw);
        boolean urlSafe = stripped.indexOf('-') >= 0 || stripped.indexOf('_') >= 0;

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripped);
        } catch (IllegalArgumentException standardFailed) {
            try {
                bytes = Base64.getUrlDecoder().decode(stripped);
            } catch (IllegalArgumentException urlFailed) {
                throw new ToolException(explain(stripped));
            }
        }

        String text;
        boolean notUtf8 = false;
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            text = dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, StandardCharsets.UTF_8);
            notUtf8 = true;
        }
        return new Decoded(text, bytes.length, notUtf8, urlSafe, whitespaceIgnored);
    }

    private static String explain(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!ok) {
                return "第 " + (i + 1) + " 个字符「" + c + "」不是 Base64 字符。"
                        + "Base64 只允许 A–Z、a–z、0–9、+ / 和结尾的 =（URL 安全变体用 - _）。";
            }
        }
        if (s.length() % 4 != 0) {
            return "长度不合法：去掉空白后共 " + s.length() + " 个字符，不是 4 的倍数。"
                    + "Base64 每 3 字节编成 4 个字符，末尾用 = 补齐。";
        }
        return "不是合法的 Base64：长度 " + s.length() + " 正确但内容无法解出，请核对是否漏掉或改动了字符。";
    }
}
