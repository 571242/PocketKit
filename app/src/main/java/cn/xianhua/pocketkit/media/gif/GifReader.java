package cn.xianhua.pocketkit.media.gif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * GIF89a reader that produces fully composited ARGB frames.
 *
 * <p>Written from scratch (no AWT) because the upstream {@code GifDecoder} is built on
 * {@code BufferedImage}. It implements the parts of the format that a "just grab the first frame"
 * shortcut silently drops:
 *
 * <ul>
 *   <li><b>Disposal methods.</b> 0/1 leave the frame in place, 2 clears the frame's rectangle to the
 *       background, 3 restores the rectangle to what was there before the frame. A reader that
 *       ignores disposal produces smeared frames on any animation that moves an object.</li>
 *   <li><b>Transparency index.</b> Transparent pixels keep the previous canvas value, which is how
 *       GIF encodes "only this part changed".</li>
 *   <li><b>Local vs global colour tables.</b> A frame with its own table overrides the global one.</li>
 *   <li><b>Interlace.</b> The four-pass row ordering is undone, otherwise interlaced GIFs come out
 *       as diagonal stripes.</li>
 *   <li><b>Per-frame delays and the loop count</b> from the NETSCAPE2.0 extension.</li>
 * </ul>
 */
public final class GifReader {

    /** One decoded frame: composited ARGB pixels plus its delay. */
    public static final class Frame {
        public final int[] argb;
        public final int delayMs;

        Frame(int[] argb, int delayMs) {
            this.argb = argb;
            this.delayMs = delayMs;
        }
    }

    public static final class Result {
        public final java.util.List<Frame> frames;
        public final int width;
        public final int height;
        /** 0 means loop forever. */
        public final int loopCount;
        /** How many frames used a local colour table; useful for honest reporting. */
        public final int localPaletteFrames;
        /** True when any frame declared a transparency index. */
        public final boolean usesTransparency;
        /** Disposal methods actually encountered, as a bitmask over values 0..3. */
        public final int disposalMethodsUsed;

        Result(java.util.List<Frame> frames, int width, int height, int loopCount,
               int localPaletteFrames, boolean usesTransparency, int disposalMethodsUsed) {
            this.frames = frames;
            this.width = width;
            this.height = height;
            this.loopCount = loopCount;
            this.localPaletteFrames = localPaletteFrames;
            this.usesTransparency = usesTransparency;
            this.disposalMethodsUsed = disposalMethodsUsed;
        }
    }

    private final byte[] data;
    private int pos;

    public GifReader(byte[] data) {
        this.data = data;
    }

    public Result read() throws IOException {
        String signature = readAscii(6);
        if (!signature.startsWith("GIF")) {
            throw new IOException("这不是 GIF 文件（文件头是「" + printable(signature) + "」）");
        }
        int width = readShort();
        int height = readShort();
        if (width < 1 || height < 1 || width > 65535 || height > 65535) {
            throw new IOException("GIF 尺寸无效：" + width + "×" + height);
        }
        int packed = readByte();
        int backgroundIndex = readByte();
        readByte();
        int[] globalPalette = null;
        if ((packed & 0x80) != 0) {
            globalPalette = readPalette(2 << (packed & 0x07));
        }

        java.util.List<Frame> frames = new java.util.ArrayList<>();
        int[] canvas = new int[width * height];
        int[] restore = new int[width * height];
        int loopCount = 0;
        int localPaletteFrames = 0;
        boolean usesTransparency = false;
        int disposalMask = 0;

        int delayMs = 0;
        int transparentIndex = -1;
        int disposal = 0;

        while (pos < data.length) {
            int block = readByte();
            if (block == 0x3B) {
                break;
            }
            if (block == 0x21) {
                int label = readByte();
                if (label == 0xF9) {
                    int size = readByte();
                    if (size < 4) {
                        // Malformed extension: consume its sub-blocks so the stream stays aligned.
                        if (size > 0) {
                            pos += size;
                        }
                        skipSubBlocks();
                        continue;
                    }
                    int flags = readByte();
                    disposal = (flags >> 2) & 0x07;
                    boolean hasTransparency = (flags & 0x01) != 0;
                    delayMs = readShort() * 10;
                    int index = readByte();
                    transparentIndex = hasTransparency ? index : -1;
                    usesTransparency |= hasTransparency;
                    disposalMask |= 1 << Math.min(disposal, 3);
                    // Graphic control is a fixed 4-byte block; the four bytes above plus this
                    // terminator are the whole extension. Calling skipSubBlocks() here would consume
                    // the *next* block as if it were ours and desync the whole stream.
                    readByte();
                } else if (label == 0xFF) {
                    int size = readByte();
                    String app = size >= 11 ? readAscii(11) : "";
                    // The loop count is a sub-block of this extension, so it must be read here rather
                    // than by scanning the raw bytes afterwards.
                    while (true) {
                        int subSize = readByte();
                        if (subSize == 0) {
                            break;
                        }
                        if (app.startsWith("NETSCAPE2.0") && subSize >= 3) {
                            int marker = readByte();
                            if (marker == 0x01) {
                                loopCount = readShort();
                                for (int skip = 3; skip < subSize; skip++) {
                                    readByte();
                                }
                                continue;
                            }
                            for (int skip = 1; skip < subSize; skip++) {
                                readByte();
                            }
                        } else {
                            for (int skip = 0; skip < subSize; skip++) {
                                readByte();
                            }
                        }
                    }
                } else {
                    skipSubBlocks();
                }
                continue;
            }
            if (block != 0x2C) {
                // Unknown block: stop rather than guess, and report what was read so far.
                break;
            }

            int left = readShort();
            int top = readShort();
            int frameW = readShort();
            int frameH = readShort();
            int framePacked = readByte();
            int[] palette = globalPalette;
            if ((framePacked & 0x80) != 0) {
                palette = readPalette(2 << (framePacked & 0x07));
                localPaletteFrames++;
            }
            boolean interlaced = (framePacked & 0x40) != 0;
            if (palette == null) {
                throw new IOException("这一帧既没有局部调色板也没有全局调色板，无法还原颜色");
            }

            int minCodeSize = readByte();
            byte[] indices = readLzwData(minCodeSize, frameW, frameH, interlaced);

            // Save what is under this frame's rectangle before drawing, for disposal 3.
            if (disposal == 3) {
                System.arraycopy(canvas, 0, restore, 0, canvas.length);
            }

            for (int y = 0; y < frameH; y++) {
                int canvasY = top + y;
                if (canvasY < 0 || canvasY >= height) {
                    continue;
                }
                for (int x = 0; x < frameW; x++) {
                    int canvasX = left + x;
                    if (canvasX < 0 || canvasX >= width) {
                        continue;
                    }
                    int index = indices[y * frameW + x] & 0xFF;
                    if (index == transparentIndex) {
                        // Transparent means "leave whatever was here", not "black".
                        continue;
                    }
                    int colorOffset = index * 3;
                    if (colorOffset + 2 >= palette.length) {
                        continue;
                    }
                    int r = palette[colorOffset];
                    int g = palette[colorOffset + 1];
                    int b = palette[colorOffset + 2];
                    canvas[canvasY * width + canvasX] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            frames.add(new Frame(canvas.clone(), delayMs));
            if (disposal == 2) {
                clearRect(canvas, width, height, left, top, frameW, frameH);
            } else if (disposal == 3) {
                System.arraycopy(restore, 0, canvas, 0, canvas.length);
            }
            // Disposal 0/1 both mean "leave the frame in place"; 1 is the same as 0 in practice.
            disposal = 0;
            transparentIndex = -1;
        }

        if (frames.isEmpty()) {
            throw new IOException("这个 GIF 里没有可解码的帧");
        }
        return new Result(frames, width, height, loopCount, localPaletteFrames,
                usesTransparency, disposalMask);
    }

    private void clearRect(int[] canvas, int width, int height, int left, int top, int w, int h) {
        for (int y = top; y < top + h && y < height; y++) {
            if (y < 0) {
                continue;
            }
            for (int x = left; x < left + w && x < width; x++) {
                if (x < 0) {
                    continue;
                }
                // "Restore to background" clears to transparent, not to the background colour index:
                // painting the background colour would leave a visible box in most animations.
                canvas[y * width + x] = 0x00000000;
            }
        }
    }

    // ------------------------------------------------------------ LZW

    /**
     * Decodes the image data sub-blocks. The variable-length code width grows as the dictionary
     * fills, which is the part a hand-rolled decoder usually gets wrong after ~250 codes.
     */
    private byte[] readLzwData(int minCodeSize, int frameW, int frameH, boolean interlaced)
            throws IOException {
        int pixelCount = frameW * frameH;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (true) {
            int size = readByte();
            if (size == 0) {
                break;
            }
            for (int i = 0; i < size; i++) {
                raw.write(readByte());
            }
        }
        byte[] compressed = raw.toByteArray();
        byte[] linear = lzwDecode(compressed, minCodeSize, pixelCount);
        // Interlacing needs the real width and height; deriving them from the pixel count alone is
        // ambiguous (many factor pairs fit), which would silently unscramble rows wrongly.
        return interlaced ? deinterlace(linear, frameW, frameH) : linear;
    }

    /**
     * GIF's LZW variant: clear code, end code, and a dictionary that resets on the clear code.
     * Started at {@code minCodeSize + 1} bits and widened at each power-of-two boundary.
     */
    static byte[] lzwDecode(byte[] data, int minCodeSize, int pixelCount) throws IOException {
        int clearCode = 1 << minCodeSize;
        int endCode = clearCode + 1;
        int codeSize = minCodeSize + 1;
        int nextCode = endCode + 1;

        byte[][] dictionary = new byte[4096][];
        for (int i = 0; i < clearCode; i++) {
            dictionary[i] = new byte[]{(byte) i};
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pixelCount);
        int bitBuffer = 0;
        int bitCount = 0;
        int dataPos = 0;
        byte[] previous = null;

        while (true) {
            while (bitCount < codeSize) {
                if (dataPos >= data.length) {
                    // Truncated stream: return what was decoded rather than dropping the frame.
                    byte[] partial = out.toByteArray();
                    return padTo(partial, pixelCount);
                }
                bitBuffer |= (data[dataPos++] & 0xFF) << bitCount;
                bitCount += 8;
            }
            int code = bitBuffer & ((1 << codeSize) - 1);
            bitBuffer >>= codeSize;
            bitCount -= codeSize;

            if (code == endCode) {
                break;
            }
            if (code == clearCode) {
                codeSize = minCodeSize + 1;
                nextCode = endCode + 1;
                previous = null;
                continue;
            }

            byte[] entry;
            if (code < nextCode && dictionary[code] != null) {
                entry = dictionary[code];
            } else if (code == nextCode && previous != null) {
                // The KwKwK case: the code refers to the entry currently being built.
                entry = concat(previous, previous[0]);
            } else {
                throw new IOException("GIF 数据流损坏：出现了字典之外的编码 " + code + "（字典长度 " + nextCode + "）");
            }

            out.write(entry, 0, entry.length);
            if (previous != null && nextCode < 4096) {
                dictionary[nextCode] = concat(previous, entry[0]);
                nextCode++;
                // Widen only after the dictionary has actually outgrown the current width.
                if (nextCode == (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            }
            previous = entry;
            if (out.size() >= pixelCount) {
                break;
            }
        }
        return padTo(out.toByteArray(), pixelCount);
    }

    private static byte[] padTo(byte[] data, int length) {
        if (data.length == length) {
            return data;
        }
        byte[] out = new byte[length];
        System.arraycopy(data, 0, out, 0, Math.min(data.length, length));
        return out;
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] out = new byte[a.length + 1];
        System.arraycopy(a, 0, out, 0, a.length);
        out[a.length] = b;
        return out;
    }

    /**
     * Undoes GIF interlacing: the rows are stored in four passes (every 8th row starting at 0, then
     * at 4, then every 4th starting at 2, then every 2nd starting at 1). Skipping this yields
     * diagonal stripes, so the pass order is hardcoded here exactly as the specification defines it.
     */
    static byte[] deinterlace(byte[] interlaced, int width, int height) {
        byte[] out = new byte[width * height];
        int[] starts = {0, 4, 2, 1};
        int[] steps = {8, 8, 4, 2};
        int srcRow = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int y = starts[pass]; y < height; y += steps[pass]) {
                int src = srcRow * width;
                int dst = y * width;
                if (src + width <= interlaced.length && dst + width <= out.length) {
                    System.arraycopy(interlaced, src, out, dst, width);
                }
                srcRow++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ primitives

    private int[] readPalette(int entries) throws IOException {
        int[] palette = new int[entries * 3];
        for (int i = 0; i < entries * 3; i++) {
            palette[i] = readByte();
        }
        return palette;
    }

    private void skipSubBlocks() throws IOException {
        while (true) {
            int size = readByte();
            if (size == 0) {
                return;
            }
            pos += size;
            if (pos > data.length) {
                throw new IOException("GIF 数据在块中途结束，文件可能不完整");
            }
        }
    }

    private int readByte() throws IOException {
        if (pos >= data.length) {
            throw new IOException("GIF 数据意外结束，文件可能不完整");
        }
        return data[pos++] & 0xFF;
    }

    private int readShort() throws IOException {
        int low = readByte();
        int high = readByte();
        return low | (high << 8);
    }

    private String readAscii(int count) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append((char) readByte());
        }
        return sb.toString();
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString();
    }
}
