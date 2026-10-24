package cn.xianhua.pocketkit.voice;

/** Pluggable mono PCM processor shared by the local DSP and future real AI engines. */
public interface VoiceProcessor {
    float[] process(float[] mono, int sampleRate, Params params, Cancellation cancel) throws Exception;

    /** Sample rate of process() output; most processors preserve the input rate. */
    default int outputSampleRate(int inputSampleRate) { return inputSampleRate; }

    /** Engines holding native resources release them here. */
    default void close() { }

    enum Preset { ORIGINAL, CLEAR, DEEP, ROBOT }

    final class Params {
        public final Preset preset;
        public final float pitchSemitones;
        public final float speed;

        public Params(Preset preset, float pitchSemitones, float speed) {
            if (preset == null) throw new IllegalArgumentException("缺少效果预设");
            if (!Float.isFinite(pitchSemitones) || pitchSemitones < -8f || pitchSemitones > 8f) {
                throw new IllegalArgumentException("音调应在 -8 到 +8 半音之间");
            }
            if (!Float.isFinite(speed) || speed < 0.75f || speed > 1.25f) {
                throw new IllegalArgumentException("速度应在 0.75 到 1.25 之间");
            }
            this.preset = preset;
            this.pitchSemitones = pitchSemitones;
            this.speed = speed;
        }
    }

    interface Cancellation {
        boolean isCancelled();

        default void throwIfCancelled() throws CancelledException {
            if (isCancelled() || Thread.currentThread().isInterrupted()) throw new CancelledException();
        }
    }

    final class CancelledException extends Exception {
        public CancelledException() { super("任务已取消"); }
    }
}
