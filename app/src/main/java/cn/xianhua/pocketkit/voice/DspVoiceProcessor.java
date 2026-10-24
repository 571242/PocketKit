package cn.xianhua.pocketkit.voice;

import java.util.Arrays;

/** Small dependency-free voice DSP: WSOLA time stretch plus compensated pitch resampling. */
public final class DspVoiceProcessor implements VoiceProcessor {
    @Override public float[] process(float[] mono, int sampleRate, Params params, Cancellation cancel) throws Exception {
        if (mono == null || mono.length == 0) throw new IllegalArgumentException("没有可处理的音频");
        if (sampleRate < 8000 || sampleRate > 48000) throw new IllegalArgumentException("仅支持 8–48 kHz 音频");
        cancel.throwIfCancelled();

        float presetPitch = params.preset == Preset.CLEAR ? 1f : params.preset == Preset.DEEP ? -3f : 0f;
        float semitones = clamp(params.pitchSemitones + presetPitch, -12f, 12f);
        double pitchFactor = Math.pow(2.0, semitones / 12.0);
        int pitchedLength = Math.max(1, (int)Math.round(mono.length / pitchFactor));
        int targetLength = Math.max(1, (int)Math.round(mono.length / params.speed));

        float[] pitched = Math.abs(semitones) < 0.001f ? Arrays.copyOf(mono, mono.length)
                : resample(mono, pitchedLength, cancel);
        float[] result = pitched.length == targetLength ? pitched
                : timeStretch(pitched, targetLength, sampleRate, cancel);

        switch (params.preset) {
            case CLEAR:
                clear(result, cancel);
                break;
            case DEEP:
                warm(result, sampleRate, cancel);
                break;
            case ROBOT:
                robot(result, sampleRate, cancel);
                break;
            default:
                break;
        }
        sanitizeAndNormalize(result, cancel);
        return result;
    }

    static float[] resample(float[] input, int outputLength, Cancellation cancel) throws Exception {
        float[] out = new float[outputLength];
        if (input.length == 1) {
            Arrays.fill(out, input[0]);
            return out;
        }
        double scale = (input.length - 1.0) / Math.max(1, outputLength - 1);
        for (int i = 0; i < outputLength; i++) {
            if ((i & 8191) == 0) cancel.throwIfCancelled();
            double source = i * scale;
            int left = Math.min(input.length - 1, (int)source);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float)(source - left);
            out[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return out;
    }

    static float[] timeStretch(float[] input, int targetLength, int sampleRate, Cancellation cancel) throws Exception {
        if (targetLength == input.length) return Arrays.copyOf(input, input.length);
        int window = even(clamp((int)(sampleRate * 0.032f), 256, 1536));
        if (input.length < window * 2 || targetLength < window) return resample(input, targetLength, cancel);
        int overlap = window / 2;
        int synthesisHop = window - overlap;
        double analysisHop = synthesisHop * (input.length / (double)targetLength);
        int search = Math.min(window / 3, Math.max(24, sampleRate / 125));
        float[] out = new float[targetLength];

        System.arraycopy(input, 0, out, 0, Math.min(window, targetLength));
        int outPos = synthesisHop;
        double expected = analysisHop;
        while (outPos < targetLength) {
            cancel.throwIfCancelled();
            int predicted = (int)Math.round(expected);
            int min = Math.max(0, predicted - search);
            int max = Math.min(input.length - window, predicted + search);
            if (max < min) break;
            int source = bestOverlap(input, out, outPos, min, max, overlap);
            int cross = Math.min(overlap, targetLength - outPos);
            for (int i = 0; i < cross; i++) {
                float mix = i / (float)Math.max(1, overlap - 1);
                out[outPos + i] = out[outPos + i] * (1f - mix) + input[source + i] * mix;
            }
            int copyStart = cross;
            int copy = Math.min(window - copyStart, targetLength - outPos - copyStart);
            if (copy > 0) System.arraycopy(input, source + copyStart, out, outPos + copyStart, copy);
            outPos += synthesisHop;
            expected = source + analysisHop;
        }
        if (outPos < targetLength - overlap) {
            // Very short or extreme combinations use a deterministic tail rather than silent padding.
            float[] fallback = resample(input, targetLength, cancel);
            int from = Math.max(0, outPos);
            System.arraycopy(fallback, from, out, from, targetLength - from);
        }
        return out;
    }

    private static int bestOverlap(float[] input, float[] output, int outPos, int min, int max, int overlap) {
        int best = min;
        double bestScore = -Double.MAX_VALUE;
        int candidateStep = Math.max(1, (max - min) / 80);
        int sampleStep = Math.max(1, overlap / 96);
        for (int candidate = min; candidate <= max; candidate += candidateStep) {
            double dot = 0, a2 = 1e-12, b2 = 1e-12;
            int count = Math.min(overlap, output.length - outPos);
            for (int i = 0; i < count; i += sampleStep) {
                float a = output[outPos + i];
                float b = input[candidate + i];
                dot += a * b;
                a2 += a * a;
                b2 += b * b;
            }
            double score = dot / Math.sqrt(a2 * b2);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void clear(float[] data, Cancellation cancel) throws Exception {
        float previousInput = 0f;
        float previousOutput = 0f;
        for (int i = 0; i < data.length; i++) {
            if ((i & 8191) == 0) cancel.throwIfCancelled();
            float input = data[i];
            float high = input - previousInput + 0.94f * previousOutput;
            previousInput = input;
            previousOutput = high;
            data[i] = input * 0.78f + high * 0.32f;
        }
    }

    private static void warm(float[] data, int sampleRate, Cancellation cancel) throws Exception {
        float cutoff = 2200f;
        float alpha = (float)(1.0 - Math.exp(-2.0 * Math.PI * cutoff / sampleRate));
        float low = 0f;
        for (int i = 0; i < data.length; i++) {
            if ((i & 8191) == 0) cancel.throwIfCancelled();
            low += alpha * (data[i] - low);
            data[i] = 0.25f * data[i] + 0.85f * low;
        }
    }

    private static void robot(float[] data, int sampleRate, Cancellation cancel) throws Exception {
        double phase = 0;
        double step = 2.0 * Math.PI * 72.0 / sampleRate;
        for (int i = 0; i < data.length; i++) {
            if ((i & 8191) == 0) cancel.throwIfCancelled();
            float dry = data[i];
            data[i] = dry * (0.18f + 0.82f * (float)Math.sin(phase));
            phase += step;
            if (phase >= Math.PI * 2) phase -= Math.PI * 2;
        }
    }

    private static void sanitizeAndNormalize(float[] data, Cancellation cancel) throws Exception {
        float peak = 0f;
        for (int i = 0; i < data.length; i++) {
            if ((i & 8191) == 0) cancel.throwIfCancelled();
            if (!Float.isFinite(data[i])) data[i] = 0f;
            peak = Math.max(peak, Math.abs(data[i]));
        }
        float gain = peak > 0.98f ? 0.98f / peak : 1f;
        for (int i = 0; i < data.length; i++) data[i] = clamp(data[i] * gain, -1f, 1f);
    }

    private static int even(int value) { return value & ~1; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
