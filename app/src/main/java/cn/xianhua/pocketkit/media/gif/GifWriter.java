package cn.xianhua.pocketkit.media.gif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * GIF89a animation writer over plain ARGB pixels.
 *
 * <p>Built on the vendored {@link NeuQuant} colour quantiser and {@link LZWEncoder} from
 * animated-gif-lib 1.4. The frame plumbing is rewritten because the upstream classes depend on
 * {@code java.awt.image.BufferedImage}, which does not exist on Android.
 *
 * <p>Per-frame handling that a naive implementation gets wrong and this one does explicitly:
 * <ul>
 *   <li><b>Local palette per frame.</b> Each frame is quantised independently and written with a
 *       local colour table. One global table would make a scene change or a rainbow flicker, since
 *       256 colours shared across every frame cannot represent each frame's palette.</li>
 *   <li><b>Transparency.</b> Pixels with alpha &lt; 128 map to a reserved transparent index and are
 *       excluded from the quantiser's statistics, so a transparent region neither turns black nor
 *       skews the palette.</li>
 *   <li><b>Disposal method 2</b> ("restore to background"): the caller passes fully composited
 *       frames, so each must replace the canvas rather than accumulate on the previous one.</li>
 *   <li><b>Delays</b> in hundredths of a second, with GIF's floor of 2 applied because most viewers
 *       treat 0 or 1 as "as fast as possible", which makes an export look broken.</li>
 * </ul>
 */
public final class GifWriter implements AutoCloseable {

    /** Viewers treat a delay below this as "as fast as possible"; 20 ms is the sane floor. */
    public static final int MIN_DELAY_HUNDREDTHS = 2;

    private static final int DISPOSAL_RESTORE_BACKGROUND = 2;
    /** Reserved index for transparent pixels; the palette is capped at 255 colours to leave it free. */
    private static final int TRANSPARENT_INDEX = 255;

    private final OutputStream out;
    private final int width;
    private final int height;
    private int frames;
    private boolean finished;

    /**
     * @param loopCount 0 loops forever, which is what a user exporting an animation expects
     */
    public GifWriter(OutputStream out, int width, int height, int loopCount) throws IOException {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("GIF 尺寸至少要 1×1，当前为 " + width + "×" + height);
        }
        this.out = out;
        this.width = width;
        this.height = height;
        writeHeader(Math.max(0, loopCount));
    }

    private void writeHeader(int loopCount) throws IOException {
        writeAscii("GIF89a");
        writeShort(width);
        writeShort(height);
        // No global colour table: each frame carries its own (see the class comment).
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        // The NETSCAPE2.0 application extension is what actually makes viewers loop.
        out.write(0x21);
        out.write(0xFF);
        out.write(0x0B);
        writeAscii("NETSCAPE2.0");
        out.write(0x03);
        out.write(0x01);
        writeShort(loopCount);
        out.write(0x00);
    }

    /**
     * Appends one frame from ARGB pixels, row-major, {@code width * height} long.
     *
     * @param argb              the composited frame
     * @param delayMs           how long this frame shows
     * @param allowTransparency when false, alpha is ignored and every pixel is written opaque
     */
    public void addFrame(int[] argb, int delayMs, boolean allowTransparency) throws IOException {
        if (finished) {
            throw new IOException("GIF 已经收尾，不能再添加帧");
        }
        if (argb.length < width * height) {
            throw new IOException("帧数据不足：需要 " + (width * height) + " 个像素，实际 " + argb.length);
        }

        int pixelCount = width * height;
        byte[] rgb = new byte[pixelCount * 3];
        byte[] indexed = new byte[pixelCount];
        boolean hasTransparency = false;
        for (int i = 0; i < pixelCount; i++) {
            int c = argb[i];
            if (allowTransparency && ((c >>> 24) < 128)) {
                hasTransparency = true;
                indexed[i] = (byte) TRANSPARENT_INDEX;
                // Kept out of the quantiser's sample: a fixed value here would drag the palette,
                // and transparent pixels have no colour to preserve anyway.
                rgb[i * 3] = 0;
                rgb[i * 3 + 1] = 0;
                rgb[i * 3 + 2] = 0;
                continue;
            }
            rgb[i * 3] = (byte) ((c >> 16) & 0xFF);
            rgb[i * 3 + 1] = (byte) ((c >> 8) & 0xFF);
            rgb[i * 3 + 2] = (byte) (c & 0xFF);
        }

        NeuQuant quantiser = new NeuQuant(rgb, rgb.length, 10);
        // NeuQuant's colour table comes back in the same triple order as the input it was fed, and
        // map() must be called with that same order. Upstream AnimatedGifEncoder feeds BGR (its
        // source is a TYPE_3BYTE_BGR BufferedImage) and then swaps R/B to build the GIF table. We
        // feed RGB, so the palette is already in GIF order and no swap is applied — doing both the
        // swap and a b,g,r call would exchange red and blue twice over and then index the wrong
        // palette entries, which is exactly what made every frame come back grey.
        byte[] palette = quantiser.process();
        for (int i = 0; i < pixelCount; i++) {
            if (hasTransparency && (indexed[i] & 0xFF) == TRANSPARENT_INDEX) {
                continue;
            }
            int r = rgb[i * 3] & 0xFF;
            int g = rgb[i * 3 + 1] & 0xFF;
            int b = rgb[i * 3 + 2] & 0xFF;
            indexed[i] = (byte) quantiser.map(r, g, b);
        }

        writeGraphicControlExtension(delayMs, hasTransparency ? TRANSPARENT_INDEX : -1);
        writeImageDescriptor();
        writeLocalColorTable(palette);
        // LZWEncoder.encode() writes the minimum-code-size byte, the sub-block framing and the
        // terminating zero itself. Writing any of those here as well would emit a duplicated length
        // byte and corrupt the stream for every decoder.
        new LZWEncoder(width, height, indexed, 8).encode(out);
        frames++;
    }

    private void writeGraphicControlExtension(int delayMs, int reservedIndex) throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(0x04);
        int transparentFlag = reservedIndex >= 0 ? 1 : 0;
        out.write((DISPOSAL_RESTORE_BACKGROUND << 2) | transparentFlag);
        int hundredths = Math.max(MIN_DELAY_HUNDREDTHS, Math.round(delayMs / 10f));
        writeShort(Math.min(hundredths, 0xFFFF));
        out.write(reservedIndex >= 0 ? reservedIndex : 0);
        out.write(0x00);
    }

    private void writeImageDescriptor() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        // Local colour table present (0x80), not interlaced, size field = 8 bits per pixel.
        out.write(0x80 | (8 - 1));
    }

    private void writeLocalColorTable(byte[] palette) throws IOException {
        int entries = palette.length / 3;
        for (int i = 0; i < 256; i++) {
            if (i < entries) {
                out.write(palette[i * 3] & 0xFF);
                out.write(palette[i * 3 + 1] & 0xFF);
                out.write(palette[i * 3 + 2] & 0xFF);
            } else {
                out.write(0);
                out.write(0);
                out.write(0);
            }
        }
    }

    /** Writes the trailer; without it many decoders treat the file as truncated. */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        out.write(0x3B);
        out.flush();
        finished = true;
    }

    public int frameCount() {
        return frames;
    }

    @Override public void close() throws IOException {
        finish();
    }

    private void writeAscii(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write(s.charAt(i) & 0xFF);
        }
    }

    private void writeShort(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
