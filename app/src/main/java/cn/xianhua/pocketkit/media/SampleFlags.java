package cn.xianhua.pocketkit.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;

/**
 * Converts {@link MediaExtractor} sample flags into {@link MediaCodec} buffer flags.
 *
 * <p>The two flag sets look similar and are <b>not</b> interchangeable, which is why this class
 * exists rather than passing the value straight through:
 *
 * <pre>
 *   MediaExtractor.SAMPLE_FLAG_SYNC          = 1   MediaCodec.BUFFER_FLAG_KEY_FRAME       = 1
 *   MediaExtractor.SAMPLE_FLAG_ENCRYPTED     = 2   MediaCodec.BUFFER_FLAG_CODEC_CONFIG    = 2   ← collision
 *   MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME = 4   MediaCodec.BUFFER_FLAG_END_OF_STREAM   = 4   ← collision
 *                                                  MediaCodec.BUFFER_FLAG_PARTIAL_FRAME   = 8
 * </pre>
 *
 * <p>Only {@code SYNC} happens to line up. Passing the raw value through would label an encrypted
 * sample as codec configuration and a partial frame as end-of-stream, which corrupts the output
 * stream instead of failing. So:
 *
 * <ul>
 *   <li>SYNC becomes {@code BUFFER_FLAG_KEY_FRAME} (a sync sample is a keyframe; the numbers agree
 *       but the mapping is written out so the intent is explicit).</li>
 *   <li>PARTIAL_FRAME becomes {@code BUFFER_FLAG_PARTIAL_FRAME} when the sample is fed to a decoder,
 *       and is <b>refused</b> when the sample is remuxed: a muxer cannot reassemble a frame that was
 *       split across buffers, and writing it would produce a broken file.</li>
 *   <li>ENCRYPTED is <b>refused</b> in both directions: an encrypted sample needs a
 *       {@code MediaCrypto} to be decoded and cannot be copied into a new container, so the honest
 *       outcome is a clear error rather than garbage output.</li>
 * </ul>
 */
public final class SampleFlags {

    /** Where the converted flags will be used, which decides what is representable. */
    public enum Target {
        /** Straight into a muxer via {@code writeSampleData}; no decoder is involved. */
        MUXER,
        /** Into a decoder via {@code queueInputBuffer}. */
        DECODER_INPUT
    }

    /**
     * Raised when a sample cannot be represented for the requested target. The message is written to
     * be shown to a user, and it does not wrap a platform error because there is no single cause.
     */
    public static final class Unsupported extends Exception {
        public Unsupported(String message) {
            super(message);
        }
    }

    private SampleFlags() {
    }

    /**
     * Maps extractor flags for the given target.
     *
     * @return the codec flag set to use; 0 when the sample needs no flag
     * @throws Unsupported when the sample type cannot be handled for that target
     */
    public static int convert(int extractorFlags, Target target) throws Unsupported {
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new Unsupported(target == Target.MUXER
                    ? "这段视频包含加密采样（DRM），无法直接复制到新文件。"
                    + "请先在有权限的播放器里解除加密，或改用未加密的源文件。"
                    : "这段视频包含加密采样（DRM），本机没有对应的解密能力，无法处理。");
        }
        boolean partial = (extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0;
        if (partial && target == Target.MUXER) {
            throw new Unsupported("这段视频使用了分段采样（一个画面分成多个缓冲），"
                    + "直接复制会得到损坏的文件，因此本工具拒绝重封装这段视频。"
                    + "如果只是要裁剪时间，可以先用支持该格式的工具转码一次再处理。");
        }

        int codecFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if (partial) {
            codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        return codecFlags;
    }

    /**
     * Convenience for the decoder path: a sample with no flag for the decoder yields 0, which is what
     * {@code queueInputBuffer} expects for an ordinary frame.
     */
    public static int forDecoderInput(int extractorFlags) throws Unsupported {
        return convert(extractorFlags, Target.DECODER_INPUT);
    }

    public static int forMuxer(int extractorFlags) throws Unsupported {
        return convert(extractorFlags, Target.MUXER);
    }
}
