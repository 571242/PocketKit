package cn.xianhua.pocketkit.reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict text decoding. A wrong guess is reported to the reader instead of being shown as garbage,
 * and Chinese legacy files still open: UTF-8 first, then GB18030, never a lossy replacement decoder.
 */
public final class ReaderText {
    public record Decoded(String text, String charset) { }

    private ReaderText() { }

    public static Decoded decode(byte[] bytes) throws IOException {
        int bom = bomLength(bytes);
        if (bom == 2) return new Decoded(strict(bytes, 2, StandardCharsets.UTF_16LE), "UTF-16LE");
        if (bom == 3) return new Decoded(strict(bytes, 2, StandardCharsets.UTF_16BE), "UTF-16BE");
        if (bom == 1) return new Decoded(strict(bytes, 3, StandardCharsets.UTF_8), "UTF-8");
        try {
            return new Decoded(strict(bytes, 0, StandardCharsets.UTF_8), "UTF-8");
        } catch (IOException notUtf8) {
            try {
                return new Decoded(strict(bytes, 0, Charset.forName("GB18030")), "GB18030");
            } catch (IOException notGb18030) {
                throw new IOException("这段文字既不是有效 UTF-8，也不是 GB18030，为避免显示乱码没有打开。");
            }
        }
    }

    /** 1 = UTF-8 BOM, 2 = UTF-16LE, 3 = UTF-16BE, 0 = none. */
    static int bomLength(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) return 1;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) return 2;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) return 3;
        return 0;
    }

    private static String strict(byte[] bytes, int offset, Charset charset) throws IOException {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("不是有效的 " + charset.name(), ex);
        }
    }
}
