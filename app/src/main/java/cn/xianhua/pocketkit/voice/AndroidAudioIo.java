package cn.xianhua.pocketkit.voice;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Android codec/SAF bridge. It never writes to an input Uri. */
public final class AndroidAudioIo {
    public static final int MAX_INPUT_SECONDS = 120;
    private static final int MAX_SAMPLE_RATE = 48000;
    private static final long MAX_DURATION_US = MAX_INPUT_SECONDS * 1_000_000L;

    private AndroidAudioIo() {}

    public static final class AudioData {
        public final float[] samples;
        public final int sampleRate;

        public AudioData(float[] samples, int sampleRate) {
            if (samples == null || samples.length == 0) throw new IllegalArgumentException("音频为空");
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public double seconds() { return samples.length / (double)sampleRate; }
    }

    public static AudioData decode(Context context, Uri uri, VoiceProcessor.Cancellation cancel) throws Exception {
        if (uri == null) throw new IllegalArgumentException("请先选择音频文件");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    inputFormat = candidate;
                    break;
                }
            }
            if (inputFormat == null) throw new IllegalArgumentException("文件中没有可解码的音轨");
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    && inputFormat.getLong(MediaFormat.KEY_DURATION) > MAX_DURATION_US) {
                throw new IllegalArgumentException("音频超过 120 秒，已拒绝处理（不会静默截断）");
            }
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalArgumentException("音轨格式不明");
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            PcmCollector pcm = new PcmCollector();
            boolean inputEnded = false;
            boolean outputEnded = false;
            int idle = 0;
            while (!outputEnded) {
                cancel.throwIfCancelled();
                boolean progressed = false;
                if (!inputEnded) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("解码器未提供输入缓冲区");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            long timeUs = extractor.getSampleTime();
                            if (timeUs > MAX_DURATION_US) {
                                throw new IllegalArgumentException("音频超过 120 秒，已拒绝处理（不会静默截断）");
                            }
                            decoder.queueInputBuffer(index, 0, size, Math.max(0, timeUs), 0);
                            extractor.advance();
                        }
                        progressed = true;
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pcm.setFormat(decoder.getOutputFormat());
                    progressed = true;
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = decoder.getOutputBuffer(outputIndex);
                        if (buffer == null) throw new IllegalStateException("解码器未提供输出缓冲区");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        pcm.append(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), cancel);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                    progressed = true;
                }
                idle = progressed ? 0 : idle + 1;
                if (idle > 500) throw new IllegalStateException("音频解码器长时间无响应");
            }
            return pcm.finish();
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) {}
                decoder.release();
            }
            extractor.release();
        }
    }

    public static void writeWav(ContentResolver resolver, Uri destination, AudioData audio,
                                VoiceProcessor.Cancellation cancel) throws Exception {
        if (destination == null) throw new IllegalArgumentException("没有选择保存位置");
        long pcmBytes = audio.samples.length * 2L;
        if (pcmBytes > 0xfffffff0L) throw new IllegalArgumentException("音频过大，无法写入 WAV");
        try (OutputStream raw = resolver.openOutputStream(destination, "w");
             DataOutputStream out = raw == null ? null : new DataOutputStream(new BufferedOutputStream(raw))) {
            if (out == null) throw new IllegalStateException("无法打开保存位置");
            writeAscii(out, "RIFF");
            writeLe32(out, 36 + pcmBytes);
            writeAscii(out, "WAVEfmt ");
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, 1);
            writeLe32(out, audio.sampleRate);
            writeLe32(out, audio.sampleRate * 2L);
            writeLe16(out, 2);
            writeLe16(out, 16);
            writeAscii(out, "data");
            writeLe32(out, pcmBytes);
            for (int i = 0; i < audio.samples.length; i++) {
                if ((i & 8191) == 0) cancel.throwIfCancelled();
                float value = Math.max(-1f, Math.min(1f, audio.samples[i]));
                int sample = Math.round(value * (value < 0 ? 32768f : 32767f));
                writeLe16(out, sample);
            }
        }
    }

    public static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException ignored) {}
        return "所选音频";
    }

    private static final class PcmCollector {
        private int sampleRate;
        private int channels;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private float[] values = new float[8192];
        private int size;

        void setFormat(MediaFormat format) {
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
            if (sampleRate < 8000 || sampleRate > MAX_SAMPLE_RATE) {
                throw new IllegalArgumentException("仅支持 8–48 kHz 音频，当前为 " + sampleRate + " Hz");
            }
            if (channels < 1 || channels > 8) throw new IllegalArgumentException("不支持 " + channels + " 声道音频");
            if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT
                    && encoding != AudioFormat.ENCODING_PCM_8BIT) {
                throw new IllegalArgumentException("设备解码器输出了不支持的 PCM 编码：" + encoding);
            }
        }

        void append(ByteBuffer buffer, VoiceProcessor.Cancellation cancel) throws Exception {
            if (sampleRate == 0 || channels == 0) throw new IllegalStateException("解码器尚未报告 PCM 格式");
            int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_16BIT ? 2 : 1;
            int frames = buffer.remaining() / (bytes * channels);
            ensure(frames);
            for (int frame = 0; frame < frames; frame++) {
                if ((frame & 4095) == 0) cancel.throwIfCancelled();
                float mono = 0f;
                for (int channel = 0; channel < channels; channel++) mono += read(buffer, encoding);
                values[size++] = mono / channels;
                if (size > sampleRate * MAX_INPUT_SECONDS) {
                    throw new IllegalArgumentException("音频超过 120 秒，已拒绝处理（不会静默截断）");
                }
            }
        }

        AudioData finish() {
            if (sampleRate == 0 || size == 0) throw new IllegalArgumentException("解码后没有音频样本");
            return new AudioData(Arrays.copyOf(values, size), sampleRate);
        }

        private void ensure(int extra) {
            int wanted = size + extra;
            int maximum = Math.max(1, sampleRate) * MAX_INPUT_SECONDS + 1;
            if (wanted > maximum) throw new IllegalArgumentException("音频超过 120 秒，已拒绝处理（不会静默截断）");
            if (wanted > values.length) values = Arrays.copyOf(values, Math.min(maximum, Math.max(wanted, values.length * 2)));
        }

        private static float read(ByteBuffer buffer, int encoding) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return buffer.getFloat();
            if (encoding == AudioFormat.ENCODING_PCM_8BIT) return ((buffer.get() & 0xff) - 128) / 128f;
            return buffer.getShort() / 32768f;
        }
    }

    private static void writeAscii(DataOutputStream out, String value) throws Exception {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private static void writeLe16(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff); out.writeByte((value >>> 8) & 0xff);
    }
    private static void writeLe32(DataOutputStream out, long value) throws Exception {
        out.writeByte((int)(value & 0xff)); out.writeByte((int)((value >>> 8) & 0xff));
        out.writeByte((int)((value >>> 16) & 0xff)); out.writeByte((int)((value >>> 24) & 0xff));
    }
}
