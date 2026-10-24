package cn.xianhua.pocketkit.device;

/**
 * 传感器与测量面板共用的纯数学。
 *
 * 这里刻意不引用任何 Android 类：设备子代理的算法自检用 javac + java 直接跑，
 * 不需要模拟器，也不受"无真机"限制。
 */
public final class SensorMath {

    private SensorMath() {
    }

    /** 标准重力加速度，用于把 m/s² 换算成 g。 */
    public static final float GRAVITY = 9.80665f;

    /** 分贝下限：低于此值一律当作静音，避免 -Infinity 进入界面。 */
    public static final float DB_FLOOR = -120f;

    /** 三轴向量模长。 */
    public static float magnitude(float[] v) {
        if (v == null || v.length < 3) {
            return 0f;
        }
        double sum = (double) v[0] * v[0] + (double) v[1] * v[1] + (double) v[2] * v[2];
        return (float) Math.sqrt(sum);
    }

    /**
     * 指数低通滤波。alpha 越接近 1 越跟手，越接近 0 越平稳。
     * alpha <= 0 时保持原值，>= 1 时直接采用输入。
     */
    public static float lowPass(float previous, float input, float alpha) {
        if (alpha <= 0f) {
            return previous;
        }
        if (alpha >= 1f) {
            return input;
        }
        return previous + alpha * (input - previous);
    }

    /** 16 位 PCM 采样块的均方根，范围 0..32768。count 超过实际长度时按实际长度算。 */
    public static float pcmRms(short[] samples, int count) {
        if (samples == null || count <= 0) {
            return 0f;
        }
        if (count > samples.length) {
            count = samples.length;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double s = samples[i];
            sum += s * s;
        }
        return (float) Math.sqrt(sum / count);
    }

    /**
     * 满量程分贝（dBFS）：数字满刻度为 0，静音为 {@link #DB_FLOOR}。
     * 注意这是数字电平，不是声压级；见 {@link #UNCALIBRATED_SPL_NOTE}。
     */
    public static double dbfs(float rms, float fullScale) {
        if (fullScale <= 0f || rms <= 0f) {
            return DB_FLOOR;
        }
        double db = 20d * Math.log10(rms / fullScale);
        return db < DB_FLOOR ? DB_FLOOR : db;
    }

    /**
     * 校准说明。刻意不提供任何默认偏移量：
     * 手机麦克风没有可读取的绝对灵敏度标定，凭空给一个 +94 dB 只会把 dBFS 包装成假的声压级。
     */
    public static final String UNCALIBRATED_SPL_NOTE =
            "未校准：手机麦克风没有可读取的绝对灵敏度标定，dBFS 只能表示相对电平。"
                    + "要得到声压级，必须用外部声级计自行测定偏移量；未标定前，读数只能用于同一台设备的前后对比，"
                    + "不能作为声级计或噪声检测的法定依据。";

    /** 把角度规整到 [0, 360)。 */
    public static float normalizeHeading(float deg) {
        if (Float.isNaN(deg) || Float.isInfinite(deg)) {
            return 0f;
        }
        float r = deg % 360f;
        if (r < 0f) {
            r += 360f;
        }
        return r;
    }

    /**
     * 方位角的圆周平滑：直接对 359° 和 1° 做线性插值会绕远路，所以先取最短角差。
     */
    public static float smoothHeading(float previous, float next, float alpha) {
        float delta = normalizeHeading(next - previous + 180f) - 180f;
        if (alpha <= 0f) {
            return normalizeHeading(previous);
        }
        if (alpha >= 1f) {
            return normalizeHeading(next);
        }
        return normalizeHeading(previous + alpha * delta);
    }

    /**
     * 屏幕左右倾斜角（度）。设备平放时 x 与 z 的比为 0，回正为 0。
     * 用 atan2 而不是 asin，倾斜接近垂直时不会因为浮点误差崩掉。
     */
    public static float tiltX(float[] gravity) {
        if (gravity == null || gravity.length < 3) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(gravity[0], gravity[2]));
    }

    /** 屏幕上下倾斜角（度），平放为 0。 */
    public static float tiltY(float[] gravity) {
        if (gravity == null || gravity.length < 3) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(gravity[1], gravity[2]));
    }

    /** 把数值映射到 0..100 的显示百分比，超出范围直接截断。 */
    public static int percent(float value, float min, float max) {
        if (max <= min) {
            return 0;
        }
        float p = (value - min) / (max - min) * 100f;
        if (p < 0f) {
            return 0;
        }
        if (p > 100f) {
            return 100;
        }
        return Math.round(p);
    }

    /** 气泡相对中心的位移，范围 -1..1（满格即倾角超出 maxDeg）。 */
    public static float bubble(float tiltDeg, float maxDeg) {
        if (maxDeg <= 0f) {
            return 0f;
        }
        float v = tiltDeg / maxDeg;
        if (v < -1f) {
            return -1f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }

    /**
     * 尺子校准：由用户用一个已知长度的实物对齐后给出「每毫米多少像素」。
     * knownMm 必须为正，否则返回 0 表示尚未校准。
     */
    public static float pxPerMm(float measuredPx, float knownMm) {
        if (measuredPx <= 0f || knownMm <= 0f) {
            return 0f;
        }
        return measuredPx / knownMm;
    }

    /** 用校准系数把像素长度换算成毫米；未校准时返回 -1。 */
    public static float pxToMm(float px, float pxPerMm) {
        if (pxPerMm <= 0f) {
            return -1f;
        }
        return px / pxPerMm;
    }
}
