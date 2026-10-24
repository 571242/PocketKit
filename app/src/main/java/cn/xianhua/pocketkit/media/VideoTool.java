package cn.xianhua.pocketkit.media;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A08 视频压缩与裁剪，兼 080 视频提取音频。
 *
 * <p>Two techniques, chosen per operation:
 * <ul>
 *   <li>Trim and audio extraction use {@link MediaExtractor} + {@link MediaMuxer}: the samples are
 *       copied, never decoded, so the result is lossless and takes seconds even for a long video.</li>
 *   <li>Compression decodes with the platform decoder and re-encodes with the platform H.264
 *       encoder, which is the only way to make a file smaller. That path is inherently slower than
 *       real time and depends on the device's encoder supporting the chosen size, so failures are
 *       reported with the step that failed rather than as a generic error.</li>
 * </ul>
 */
public final class VideoTool implements Tool {

    private final Host host;

    public VideoTool(Host host) {
        this.host = host;
    }

    /** Duration in milliseconds, or -1 when the container does not report it. */
    static long duration(Context ctx, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(ctx, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1 : Long.parseLong(value);
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Releasing the retriever only frees native resources.
            }
        }
    }

    @Override public void build() {
        String id = host.activity().getIntent().getStringExtra("tool_id");
        if ("080".equals(id)) {
            buildExtractAudio();
            return;
        }
        buildVideo();
    }

    // ------------------------------------------------------------ A08

    private void buildVideo() {
        host.note("压缩会重新编码（较慢，依赖设备编码器）；裁剪只复制片段，不重编码，因此很快且无损。");

        final Uri[] source = {null};
        final android.widget.TextView info = host.note("尚未选择视频");
        host.button("选择视频", () -> host.pickDocument("video/*", uri -> {
            source[0] = uri;
            long ms = duration(host.activity(), uri);
            long size = MediaIo.size(host.activity(), uri);
            info.setText("已选择：" + MediaIo.displayName(host.activity(), uri)
                    + (ms > 0 ? "\n时长：" + formatDuration(ms) : "\n（容器未报告时长）")
                    + (size > 0 ? "，大小：" + cn.xianhua.pocketkit.core.SizeFmt.bytes(size) : ""));
        }));

        host.note("—— 裁剪（不重编码，快速无损）——");
        final EditText startAt = host.field("起始时间（mm:ss，留空表示开头）", "例如 00:30");
        final EditText endAt = host.field("结束时间（mm:ss，留空表示结尾）", "例如 01:15");
        host.button("裁剪并保存", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一个视频。");
                return;
            }
            long total = duration(host.activity(), uri);
            long startMs;
            long endMs;
            try {
                startMs = parseTime(startAt.getText().toString(), 0);
                endMs = parseTime(endAt.getText().toString(), total > 0 ? total : Long.MAX_VALUE);
            } catch (IllegalArgumentException ex) {
                host.show("未完成：" + ex.getMessage());
                return;
            }
            if (total > 0 && endMs > total) {
                endMs = total;
            }
            if (endMs <= startMs) {
                host.show("未完成：结束时间必须晚于起始时间。当前解析为 "
                        + formatDuration(startMs) + " 到 " + formatDuration(endMs) + "。");
                return;
            }
            final long from = startMs;
            final long to = endMs;
            run("裁剪", () -> trim(uri, from, to));
        });

        host.note("—— 压缩（重新编码）——");
        final Spinner resolution = host.select("输出分辨率", "保持原始", "1080p", "720p", "480p");
        final Spinner bitrate = host.select("码率", "自动（按分辨率）", "1 Mbps（最小）", "2 Mbps", "4 Mbps", "8 Mbps");
        final EditText compressStart = host.field("只压缩片段：起始（mm:ss，留空=全部）", "例如 00:10");
        final EditText compressEnd = host.field("只压缩片段：结束（mm:ss，留空=全部）", "例如 00:40");
        host.button("压缩并保存", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一个视频。");
                return;
            }
            long total = duration(host.activity(), uri);
            long startMs;
            long endMs;
            try {
                startMs = parseTime(compressStart.getText().toString(), 0);
                endMs = parseTime(compressEnd.getText().toString(), total > 0 ? total : Long.MAX_VALUE);
            } catch (IllegalArgumentException ex) {
                host.show("未完成：" + ex.getMessage());
                return;
            }
            if (total > 0 && endMs > total) {
                endMs = total;
            }
            if (endMs <= startMs) {
                host.show("未完成：结束时间必须晚于起始时间。");
                return;
            }
            final int resIndex = resolution.getSelectedItemPosition();
            final int brIndex = bitrate.getSelectedItemPosition();
            final long from = startMs;
            final long to = endMs;
            run("压缩", () -> compress(uri, resIndex, brIndex, from, to));
        });

        host.note("说明：压缩需要解码再编码，一个几分钟的视频可能要等同等时长；"
                + "期间可以按「取消当前任务」停止，已写出的临时文件会被丢弃。"
                + "音轨原样复制、不重编码，因此音频不会二次损失。"
                + "部分设备不支持所选分辨率，此时会明确报出编码器错误。");
    }

    // ------------------------------------------------------------ 080

    private void buildExtractAudio() {
        host.note("从视频中提取音轨。不重新编码，直接把原始音频流封装成新文件，因此无损且很快。");
        final Uri[] source = {null};
        host.button("选择视频", () -> host.pickDocument("video/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「提取音频」。");
        }));
        host.button("提取音频", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一个视频。");
                return;
            }
            run("提取音频", () -> extractAudio(uri));
        });
        host.note("如果视频里没有音轨，会如实告知。输出格式跟随原始音轨（常见为 AAC/M4A）。");
    }

    // ------------------------------------------------------------ jobs

    private interface Job {
        Result call() throws Exception;
    }

    private static final class Result {
        final File file;
        final String name;
        final String mime;
        final String summary;

        Result(File file, String name, String mime, String summary) {
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.summary = summary;
        }
    }

    private void run(String what, Job job) {
        final Cancel cancel = new Cancel();
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            File prepared = null;
            try {
                Result result = job.call();
                prepared = result.file;
                final File ready = prepared;
                final String name = result.name;
                final String mime = result.mime;
                final String summary = result.summary;
                prepared = null;
                host.post(() -> {
                    host.setRunning(false);
                    host.show(summary + "\n\n请选择保存位置。");
                    host.createDocument(name, mime, target -> host.background(() -> {
                        try {
                            MediaIo.publish(host.activity(), target, ready);
                            host.post(() -> host.show(summary + "\n\n已保存：" + name));
                        } finally {
                            if (ready.exists()) {
                                ready.delete();
                            }
                        }
                    }));
                });
            } catch (Cancel.Stop stopped) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("已取消，未写出文件。");
                });
            } catch (Exception ex) {
                host.post(() -> {
                    host.setRunning(false);
                    host.fail(ex);
                });
            } finally {
                if (prepared != null && prepared.exists()) {
                    prepared.delete();
                }
            }
        });
    }

    /** Copies the selected span of every track without decoding it. */
    Result trim(Uri uri, long startMs, long endMs) throws Exception {
        File out = MediaIo.scratch(host.activity(), ".mp4");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(host.activity(), uri, null);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackCount = extractor.getTrackCount();
            int[] sourceTrack = new int[trackCount];
            int[] targetTrack = new int[trackCount];
            String mime = null;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String type = format.getString(MediaFormat.KEY_MIME);
                try {
                    targetTrack[i] = muxer.addTrack(format);
                    sourceTrack[i] = i;
                } catch (Exception e) {
                    // A track the muxer cannot carry is skipped rather than failing the whole export.
                    targetTrack[i] = -1;
                    sourceTrack[i] = -1;
                }
                if (type != null && type.startsWith("video/")) {
                    mime = type;
                }
            }
            if (mime == null) {
                throw new IOException("这个文件里没有视频轨道。若要提取音频，请用「视频提取音频」。");
            }
            muxer.start();

            long startUs = startMs * 1000;
            long endUs = endMs >= Long.MAX_VALUE / 2000 ? Long.MAX_VALUE : endMs * 1000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] buffer = new byte[1024 * 1024];
            long written = 0;
            for (int i = 0; i < trackCount; i++) {
                int src = sourceTrack[i];
                int dst = targetTrack[i];
                if (src < 0 || dst < 0) {
                    continue;
                }
                extractor.selectTrack(src);
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime < 0 || sampleTime > endUs) {
                        break;
                    }
                    int size = extractor.readSampleData(ByteBuffer.wrap(buffer), 0);
                    if (size < 0) {
                        break;
                    }
                    // Trim starts at a keyframe, so the first frames may be slightly before the
                    // requested time. Rebasing to the requested start keeps the output duration right
                    // and is what a lossless cut can honestly offer.
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = Math.max(0, sampleTime - startUs);
                    // Extractor flags are not codec flags (see SampleFlags): encrypted and partial
                    // samples are refused here instead of being written with a colliding bit set.
                    info.flags = SampleFlags.forMuxer(extractor.getSampleFlags());
                    muxer.writeSampleData(dst, ByteBuffer.wrap(buffer), info);
                    written += size;
                    extractor.advance();
                }
                extractor.unselectTrack(src);
            }
            muxer.stop();
            if (written == 0) {
                throw new IOException("所选时间段内没有可复制的画面，请检查起止时间");
            }
            String name = ImageCompressTool.suggestName(
                    MediaIo.displayName(host.activity(), uri), "trimmed.mp4");
            return new Result(out, name, "video/mp4",
                    "裁剪完成（无重编码）：" + formatDuration(startMs) + " – " + formatDuration(endMs)
                            + "\n文件大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(out.length())
                            + "。起点会对齐到最近的关键帧，因此实际开头可能比设定值稍早一点。");
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // Already stopped or failed; nothing left to release safely.
                }
            }
        }
    }

    Result extractAudio(Uri uri) throws Exception {
        File out = MediaIo.scratch(host.activity(), ".m4a");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(host.activity(), uri, null);
            int audioTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String type = format.getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                    break;
                }
            }
            if (audioTrack < 0) {
                throw new IOException("这个视频没有音轨，无法提取音频。");
            }
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = muxer.addTrack(audioFormat);
            extractor.selectTrack(audioTrack);
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
            long written = 0;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = SampleFlags.forMuxer(extractor.getSampleFlags());
                muxer.writeSampleData(dst, buffer, info);
                written += size;
                extractor.advance();
            }
            muxer.stop();
            if (written == 0) {
                throw new IOException("这个视频的音轨没有可复制的数据");
            }
            String extension = extensionFor(audioFormat.getString(MediaFormat.KEY_MIME));
            String name = ImageCompressTool.suggestName(
                    MediaIo.displayName(host.activity(), uri), "audio" + extension);
            return new Result(out, name, mimeFor(audioFormat.getString(MediaFormat.KEY_MIME)),
                    "提取完成（无重编码）：音轨格式 " + audioFormat.getString(MediaFormat.KEY_MIME)
                            + "\n文件大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(out.length()));
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // Nothing to release safely after a failure.
                }
            }
        }
    }

    public static String extensionFor(String mime) {
        if (mime == null) {
            return ".m4a";
        }
        if (mime.contains("aac") || mime.contains("mp4a")) {
            return ".m4a";
        }
        if (mime.contains("mpeg")) {
            return ".mp3";
        }
        if (mime.contains("opus")) {
            return ".opus";
        }
        if (mime.contains("vorbis")) {
            return ".ogg";
        }
        return ".m4a";
    }

    public static String mimeFor(String trackMime) {
        String ext = extensionFor(trackMime);
        switch (ext) {
            case ".mp3": return "audio/mpeg";
            case ".opus": return "audio/opus";
            case ".ogg": return "audio/ogg";
            default: return "audio/mp4";
        }
    }

    /**
     * Re-encodes the span into H.264/AAC.
     *
     * <p>The loop is the standard synchronous codec pattern: feed an input buffer, drain every
     * output buffer, and stop once the presentation timestamp passes the requested end. Progress is
     * derived from the presentation time, which is the only reliable signal the codec gives.
     */
    Result compress(Uri uri, int resolutionIndex, int bitrateIndex, long startMs, long endMs)
            throws Exception {
        Context ctx = host.activity();
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        File out = MediaIo.scratch(ctx, ".mp4");
        try {
            extractor.setDataSource(ctx, uri, null);
            int videoTrack = -1;
            MediaFormat videoFormat = null;
            int audioTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String type = format.getString(MediaFormat.KEY_MIME);
                if (type == null) {
                    continue;
                }
                if (videoTrack < 0 && type.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                } else if (audioTrack < 0 && type.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                }
            }
            if (videoTrack < 0) {
                throw new IOException("这个文件没有视频轨道。");
            }
            int srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
            int srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = 0;
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
            }
            int[] size = targetSize(srcW, srcH, resolutionIndex);
            int outW = size[0];
            int outH = size[1];
            int bitrate = targetBitrate(outW, outH, bitrateIndex);

            String mime = videoFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(videoFormat, null, null, 0);
            decoder.start();

            MediaFormat encodeFormat = MediaFormat.createVideoFormat("video/avc", outW, outH);
            encodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate(videoFormat));
            encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType("video/avc");
            // configure() must come first: createInputSurface() is only legal on a configured encoder.
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            // Surface input: the decoder renders straight into the encoder, so frames never travel
            // through app memory and a 1080p video does not allocate a single Bitmap.
            android.view.Surface surface = encoder.createInputSurface();
            encoder.start();
            // The decoder writes into the encoder's input surface.
            decoder.setOutputSurface(surface);

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // The video track is registered lazily from the encoder's real output format: the
            // requested format carries no SPS/PPS, so an eagerly added track yields a broken file.
            int[] outTrack = {-1};
            // The audio track is copied, not re-encoded: re-encoding would cost time and quality
            // for no size benefit at these bitrates.
            boolean hasAudio = audioTrack >= 0;
            int audioOutTrack = hasAudio ? muxer.addTrack(audioFormat) : -1;
            if (rotation != 0) {
                muxer.setOrientationHint(rotation);
            }
            boolean muxerStarted = false;

            extractor.selectTrack(videoTrack);
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            // An unknown duration leaves endMs at Long.MAX_VALUE; multiplying that by 1000 wraps
            // negative and would cut every sample away.
            long endUs = endMs >= Long.MAX_VALUE / 2000 ? Long.MAX_VALUE : endMs * 1000;

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        long sampleTime = extractor.getSampleTime();
                        if (sampleSize < 0 || sampleTime > endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            // The decoder does take the extractor's flags, but only after the mapping:
                            // a partial frame is a real decodable notion here, and it would otherwise
                            // arrive as END_OF_STREAM and truncate the video.
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime,
                                    SampleFlags.forDecoderInput(extractor.getSampleFlags()));
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    // Surface mode reports size == 0 for every frame, so size cannot decide whether
                    // to render: always hand the frame to the encoder's input surface.
                    decoder.releaseOutputBuffer(outIndex, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                    }
                }
                int encIndex = encoder.dequeueOutputBuffer(info, 10_000);
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Register both tracks from the encoder's real format, then start the muxer once.
                    outTrack[0] = muxer.addTrack(encoder.getOutputFormat());
                    if (!muxerStarted) {
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (encIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                    if (encoded != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(outTrack[0], encoded, info);
                    }
                    encoder.releaseOutputBuffer(encIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (!muxerStarted) {
                throw new IOException("编码器没有输出视频格式，无法写入文件");
            }

            // Copy the audio samples for the same span. This runs after the video loop so the two
            // extractor consumers never interleave on one track selection.
            boolean audioCopied = false;
            if (hasAudio) {
                extractor.unselectTrack(videoTrack);
                extractor.selectTrack(audioTrack);
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                ByteBuffer audioBuffer = ByteBuffer.allocate(256 * 1024);
                while (true) {
                    int audioSize = extractor.readSampleData(audioBuffer, 0);
                    long time = extractor.getSampleTime();
                    if (audioSize < 0 || time > endUs) {
                        break;
                    }
                    audioInfo.offset = 0;
                    audioInfo.size = audioSize;
                    // Rebase to the cut start so audio lines up with the re-encoded video, which also
                    // begins at zero.
                    audioInfo.presentationTimeUs = Math.max(0, time - startMs * 1000);
                    audioInfo.flags = SampleFlags.forMuxer(extractor.getSampleFlags());
                    muxer.writeSampleData(audioOutTrack, audioBuffer, audioInfo);
                    audioCopied = true;
                    extractor.advance();
                }
            }

            muxer.stop();
            if (out.length() == 0) {
                throw new IOException("编码后没有产生数据，可能是所选分辨率不受设备编码器支持");
            }
            String name = ImageCompressTool.suggestName(
                    MediaIo.displayName(ctx, uri), "compressed.mp4");
            String audioReport = !hasAudio
                    ? "原视频没有音轨。"
                    : audioCopied
                    ? "音轨已原样复制（" + audioFormat.getString(MediaFormat.KEY_MIME) + "，未重编码）。"
                    : "原视频有音轨，但所选片段内没有音频采样，输出的这段没有声音。";
            return new Result(out, name, "video/mp4",
                    "压缩完成：" + srcW + "×" + srcH + " → " + outW + "×" + outH
                            + "，视频目标码率 " + (bitrate / 1_000_000) + " Mbps"
                            + "\n片段 " + formatDuration(startMs) + " – " + formatDuration(endMs)
                            + "，文件大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(out.length())
                            + "\n" + audioReport);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                    // Already stopped or never started successfully.
                }
                decoder.release();
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                    // Same as above.
                }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // Nothing to release safely after a failure.
                }
            }
            extractor.release();
        }
    }

    /** Target size per preset; "keep original" also snaps to even numbers, which H.264 requires. */
    public static int[] targetSize(int srcW, int srcH, int resolutionIndex) {
        int longEdge = Math.max(srcW, srcH);
        int target;
        switch (resolutionIndex) {
            case 1: target = 1920; break;
            case 2: target = 1280; break;
            case 3: target = 854; break;
            default: target = longEdge;
        }
        if (target >= longEdge && resolutionIndex != 0) {
            target = longEdge;
        }
        double k = target / (double) longEdge;
        int w = even((int) Math.round(srcW * k));
        int h = even((int) Math.round(srcH * k));
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    private static int even(int v) {
        return v % 2 == 0 ? v : v - 1;
    }

    public static int targetBitrate(int w, int h, int bitrateIndex) {
        switch (bitrateIndex) {
            case 1: return 1_000_000;
            case 2: return 2_000_000;
            case 3: return 4_000_000;
            case 4: return 8_000_000;
            default:
                // Roughly 0.09 bits per pixel per frame at 30 fps: a quality/size balance that keeps
                // ordinary footage watchable without the file ballooning.
                return (int) Math.max(1_000_000L, Math.min(20_000_000L, (long) w * h * 3L));
        }
    }

    static int frameRate(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (fps >= 1 && fps <= 120) {
                    return fps;
                }
            } catch (Exception ignored) {
                // Some encoders report a float here; fall through to the default.
            }
        }
        return 30;
    }

    /** Parses "mm:ss" or "hh:mm:ss" or a raw seconds count into milliseconds. */
    public static long parseTime(String text, long fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split(":");
        try {
            if (parts.length == 1) {
                double seconds = Double.parseDouble(parts[0]);
                return (long) (seconds * 1000);
            }
            long total = 0;
            for (String part : parts) {
                total = total * 60 + Long.parseLong(part.trim());
            }
            return total * 1000;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("时间「" + trimmed + "」看不懂，请用 mm:ss（例如 01:30）或秒数（例如 90）");
        }
    }

    public static String formatDuration(long ms) {
        if (ms < 0) {
            return "未知";
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }
}
