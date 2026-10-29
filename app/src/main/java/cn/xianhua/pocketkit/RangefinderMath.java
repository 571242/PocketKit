package cn.xianhua.pocketkit;

/**
 * 测距/测高的纯数学核心（不引用任何 Android 类，可被 JVM 直接跑测试）。
 *
 * <p>坐标系与单位约定：
 * <ul>
 *   <li>传感器为 Android 设备坐标系：X 向右、Y 向上（竖屏）、Z 指向屏幕正面（"屏幕朝外"）。
 *       当手机平放、屏幕朝上时，重力/加速度读数的 Z 约为 +9.80665。
 *   <li>后置摄像头光轴指向设备 -Z（背离屏幕）方向。
 *   <li>倾角 {@code elevation}：光轴相对"水平面"的仰角，单位度。抬头（摄像头朝上）为正，
 *       低头（摄像头朝下）为负。它只取决于俯仰，绕光轴滚转（roll）不会改变它——这正是我们
 *       只取重力 Z 分量即可算角度、且竖屏/横屏/旋转都不影响结果的原因。
 * </ul>
 *
 * <p>测量模型（地面水平假设）：
 * <pre>
 *   相机离地高度 h，俯视地面目标底部，俯角 θ=-α_base（α_base&lt;0）：
 *       d = h / tan(-α_base)                      // 相机到目标底部的水平距离
 *   基准锁定后抬升对准目标顶点（仰角 α_top）：
 *       objectHeight = d * (tan(α_top) - tan(α_base))
 * </pre>
 * 这些公式在接近水平（d 发散）或接近竖直（tan 发散）时数值不稳，本类用显式的
 * {@link Status} 判定把它们挡住，绝不返回一个看起来合理、实则错误的数字。
 */
public final class RangefinderMath {

    private RangefinderMath() {
    }

    /** 标准重力加速度（m/s²），用于判断读数是否处于"静止"状态。 */
    public static final double GRAVITY = 9.80665d;

    /** 底部俯角至少要达到这个值，否则 d=h/tan 会发散。 */
    public static final double MIN_BASE_DEPRESSION_DEG = 2.0d;

    /** 底部俯角超过这个值视为"接近竖直"，tan 急剧放大、结果不稳。 */
    public static final double MAX_BASE_DEPRESSION_DEG = 85.0d;

    /** 顶点相对水平线的绝对仰角上限，防止 tan 在 ±90° 附近爆掉。 */
    public static final double MAX_TOP_ABS_ELEVATION_DEG = 89.0d;

    /** 合理水平距离上限（米），超过一律视为角度过小、不可信。 */
    public static final double MAX_GROUND_DISTANCE_M = 500.0d;

    /** 合理目标高度上限（米）。 */
    public static final double MAX_OBJECT_HEIGHT_M = 300.0d;

    /** 判定状态。OK 之外一律不给出数值结果。 */
    public enum Status {
        OK,
        /** 输入非法：高度不是正数，或角度为 NaN/Inf。 */
        INVALID_INPUT,
        /** 底部太接近水平（甚至仰视），水平距离会发散。 */
        BASE_TOO_LEVEL,
        /** 底部太接近竖直，结果不稳。 */
        BASE_TOO_STEEP,
        /** 由底部角算出的水平距离超出合理上限。 */
        DISTANCE_OUT_OF_RANGE,
        /** 顶点不高于底点（目标顶点低于或等于底线），无法测高。 */
        TOP_NOT_ABOVE_BASE,
        /** 顶点接近竖直，tan 发散。 */
        TOP_TOO_STEEP,
        /** 算出的高度不是有限正数或超出上限。 */
        HEIGHT_OUT_OF_RANGE
    }

    /** 三轴向量模长。 */
    public static double magnitude(float gx, float gy, float gz) {
        return Math.sqrt((double) gx * gx + (double) gy * gy + (double) gz * gz);
    }

    /**
     * 由重力（或加速度）读数求光轴仰角，单位度。
     *
     * <p>光轴方向为 (0,0,-1)，"上"方向为重力单位向量，二者点积即 sin(仰角)：
     * {@code sin(elevation) = -gz / |g|}。屏幕朝上平放时 gz=+|g| → 仰角 -90°（相机垂直朝下）。
     * 无论竖屏还是横屏，光轴始终是设备 -Z，故结果与屏幕旋转无关。
     *
     * @return 仰角（度）；模长过小（读数无效）时返回 {@link Double#NaN}。
     */
    public static double elevationDeg(float gx, float gy, float gz) {
        double mag = magnitude(gx, gy, gz);
        if (!(mag > 1e-3)) {
            return Double.NaN;
        }
        double s = -gz / mag;
        if (s > 1d) {
            s = 1d;
        } else if (s < -1d) {
            s = -1d;
        }
        double deg = Math.toDegrees(Math.asin(s));
        return Double.isNaN(deg) || Double.isInfinite(deg) ? Double.NaN : deg;
    }

    /**
     * 指数低通滤波（防抖）。alpha 越接近 1 越跟手，越接近 0 越平稳。
     * alpha&lt;=0 保持原值，&gt;=1 直接采用输入。
     */
    public static float lowPass(float previous, float input, float alpha) {
        if (Float.isNaN(input)) {
            return previous;
        }
        if (alpha <= 0f) {
            return previous;
        }
        if (alpha >= 1f) {
            return input;
        }
        return previous + alpha * (input - previous);
    }

    /** 底点是否可用来算距离。 */
    public static Status baseStatus(double baseElevDeg, double cameraHeightM) {
        if (!(cameraHeightM > 0d) || Double.isNaN(cameraHeightM) || Double.isInfinite(cameraHeightM)) {
            return Status.INVALID_INPUT;
        }
        if (Double.isNaN(baseElevDeg) || Double.isInfinite(baseElevDeg)) {
            return Status.INVALID_INPUT;
        }
        double depression = -baseElevDeg; // 俯角为正
        if (depression < MIN_BASE_DEPRESSION_DEG) {
            // 包含"平视/仰视"（depression<=0）：不可能在正前方地面找到底部。
            return Status.BASE_TOO_LEVEL;
        }
        if (depression > MAX_BASE_DEPRESSION_DEG) {
            return Status.BASE_TOO_STEEP;
        }
        double d = cameraHeightM / Math.tan(Math.toRadians(depression));
        if (!(d > 0d) || Double.isInfinite(d) || Double.isNaN(d)) {
            return Status.INVALID_INPUT;
        }
        if (d > MAX_GROUND_DISTANCE_M) {
            return Status.DISTANCE_OUT_OF_RANGE;
        }
        return Status.OK;
    }

    /**
     * 相机到目标底部的水平距离 d = h / tan(-α_base)。
     * 仅当 {@link #baseStatus} 为 OK 时返回数值，否则返回 {@link Double#NaN}。
     */
    public static double groundDistanceM(double baseElevDeg, double cameraHeightM) {
        if (baseStatus(baseElevDeg, cameraHeightM) != Status.OK) {
            return Double.NaN;
        }
        double depression = -baseElevDeg;
        return cameraHeightM / Math.tan(Math.toRadians(depression));
    }

    /** 测高是否可给出可信结果。 */
    public static Status heightStatus(double baseElevDeg, double topElevDeg, double cameraHeightM) {
        Status base = baseStatus(baseElevDeg, cameraHeightM);
        if (base != Status.OK) {
            return base;
        }
        if (Double.isNaN(topElevDeg) || Double.isInfinite(topElevDeg)) {
            return Status.INVALID_INPUT;
        }
        if (Math.abs(topElevDeg) > MAX_TOP_ABS_ELEVATION_DEG) {
            return Status.TOP_TOO_STEEP;
        }
        if (topElevDeg <= baseElevDeg) {
            return Status.TOP_NOT_ABOVE_BASE;
        }
        double h = objectHeightMUnchecked(baseElevDeg, topElevDeg, cameraHeightM);
        if (!(h > 0d) || Double.isInfinite(h) || Double.isNaN(h)) {
            return Status.HEIGHT_OUT_OF_RANGE;
        }
        if (h > MAX_OBJECT_HEIGHT_M) {
            return Status.HEIGHT_OUT_OF_RANGE;
        }
        return Status.OK;
    }

    /**
     * 目标高度 objectHeight = d * (tan(α_top) - tan(α_base))，其中 d 由底点角得出。
     * 仅当 {@link #heightStatus} 为 OK 时返回数值，否则返回 {@link Double#NaN}。
     */
    public static double objectHeightM(double baseElevDeg, double topElevDeg, double cameraHeightM) {
        if (heightStatus(baseElevDeg, topElevDeg, cameraHeightM) != Status.OK) {
            return Double.NaN;
        }
        return objectHeightMUnchecked(baseElevDeg, topElevDeg, cameraHeightM);
    }

    private static double objectHeightMUnchecked(double baseElevDeg, double topElevDeg, double cameraHeightM) {
        double d = cameraHeightM / Math.tan(Math.toRadians(-baseElevDeg));
        return d * (Math.tan(Math.toRadians(topElevDeg)) - Math.tan(Math.toRadians(baseElevDeg)));
    }

    /**
     * 已知水平距离 D 与相机高度 h 时，地面目标底部"应有的"真实俯视仰角（负值）：
     * {@code α_true = -atan(h / D)}。
     */
    public static double trueBaseElevationDeg(double cameraHeightM, double knownDistanceM) {
        if (!(cameraHeightM > 0d) || !(knownDistanceM > 0d)
                || !Double.isFinite(cameraHeightM) || !Double.isFinite(knownDistanceM)) {
            return Double.NaN;
        }
        return -Math.toDegrees(Math.atan(cameraHeightM / knownDistanceM));
    }

    /**
     * 零偏校准：用户把准星对准已知水平距离 D 处的目标底部，此刻设备读数（修正前的原始仰角）
     * 为 {@code measuredBaseElevDeg}。零偏 = 实测 - 真实，之后所有角度都减去它即可对齐。
     *
     * <p>校准正确性可验证：用该零偏修正后 {@link #groundDistanceM} 应还原为 D。
     */
    public static double zeroOffsetDeg(double measuredBaseElevDeg, double cameraHeightM, double knownDistanceM) {
        double truth = trueBaseElevationDeg(cameraHeightM, knownDistanceM);
        if (!Double.isFinite(truth) || !Double.isFinite(measuredBaseElevDeg)) {
            return Double.NaN;
        }
        return measuredBaseElevDeg - truth;
    }
}
