package cn.xianhua.pocketkit;

/**
 * 测距/测高纯数学的 JVM 自检：只验证角度换算、地面水平假设下的距离/高度公式、
 * 以及接近水平/竖直、顶点低于底点等退化情形的显式拒绝。
 *
 * <p>不启动 Android、不验证相机与传感器真机行为——真机取景与姿态融合需要主代理统一验收，
 * 这些断言不能冒充整机验收。
 */
public final class RangefinderMathTest {

    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance,
                label + "（实际 " + actual + "，期望 " + expected + "）");
    }

    private static void isNaN(double value, String label) {
        check(Double.isNaN(value), label + "（期望 NaN，实际 " + value + "）");
    }

    public static void main(String[] args) {
        elevation();
        groundDistance();
        objectHeight();
        degenerateRejections();
        calibration();
        smoothing();

        System.out.println("RangefinderMathTest PASS: " + checks + " assertions");
    }

    private static void elevation() {
        float g = 9.80665f;

        // 平放屏幕朝上：光轴垂直朝下 → -90°。
        near(RangefinderMath.elevationDeg(0f, 0f, g), -90d, 1e-6, "平放朝上 → -90°");
        // 竖直立起（摄像头水平朝前）→ 0°。
        near(RangefinderMath.elevationDeg(0f, g, 0f), 0d, 1e-6, "竖直 → 0°");
        // 光轴朝下 45°。
        float c = (float) (g * Math.cos(Math.toRadians(45)));
        float s = (float) (g * Math.sin(Math.toRadians(45)));
        near(RangefinderMath.elevationDeg(0f, c, s), -45d, 1e-4, "朝下 45°");
        // 光轴朝上 45°：屏幕朝下倾斜，gz 为负。
        near(RangefinderMath.elevationDeg(0f, c, -s), 45d, 1e-4, "朝上 45°");

        // 只取重力 Z 分量：绕光轴滚转（roll）不改变仰角。
        // 同一 45° 俯角，把 gx 拉进来、gy 减小，gz 不变 → 仰角仍 -45°。
        near(RangefinderMath.elevationDeg(s, 0f, s), -45d, 1e-4, "滚转不改变仰角");

        // 无信号 → NaN，不给假读数。
        isNaN(RangefinderMath.elevationDeg(0f, 0f, 0f), "零向量 → NaN");
    }

    private static void groundDistance() {
        // 45° 俯角、高 1.5 m → 水平距离 1.5 m。
        near(RangefinderMath.groundDistanceM(-45d, 1.5d), 1.5d, 1e-6, "45° → d=h");

        // 用真实几何反推一个角，再算回距离，应回到原值。
        double truth = RangefinderMath.trueBaseElevationDeg(1.5d, 10d);
        near(RangefinderMath.groundDistanceM(truth, 1.5d), 10d, 1e-6, "真实角 → d=10");

        // 负仰角（俯视）才有正距离；状态需为 OK。
        check(RangefinderMath.baseStatus(truth, 1.5d) == RangefinderMath.Status.OK, "合法底点状态 OK");
    }

    private static void objectHeight() {
        // h=1.5, base=-45° → d=1.5；top=+45° → 高 = 1.5*(tan45 - tan-45)=1.5*(1+1)=3。
        near(RangefinderMath.objectHeightM(-45d, 45d, 1.5d), 3.0d, 1e-6, "对称 45° → 高 3m");

        // 现实场景：h=1.5, d=10(底 -8.53°), 顶 +20° → 高 = 10*(tan20-tan-8.53)。
        double base = RangefinderMath.trueBaseElevationDeg(1.5d, 10d);
        double expected = 10d * (Math.tan(Math.toRadians(20d)) - Math.tan(Math.toRadians(base)));
        near(RangefinderMath.objectHeightM(base, 20d, 1.5d), expected, 1e-6, "现实俯仰 → 高");
        check(RangefinderMath.heightStatus(base, 20d, 1.5d) == RangefinderMath.Status.OK, "合法测高状态 OK");

        // 高度随仰角单调增：顶点更高 → 高更大。
        double h1 = RangefinderMath.objectHeightM(base, 10d, 1.5d);
        double h2 = RangefinderMath.objectHeightM(base, 30d, 1.5d);
        check(h2 > h1, "仰角越大测高越大");
    }

    private static void degenerateRejections() {
        // 底部接近水平：距离发散，必须拒绝。
        check(RangefinderMath.baseStatus(0d, 1.5d) == RangefinderMath.Status.BASE_TOO_LEVEL, "底点平视 → 拒绝");
        check(RangefinderMath.baseStatus(-1d, 1.5d) == RangefinderMath.Status.BASE_TOO_LEVEL, "底点 -1° → 拒绝");
        // 仰视（正仰角）当作"太平"，同样拒绝。
        check(RangefinderMath.baseStatus(5d, 1.5d) == RangefinderMath.Status.BASE_TOO_LEVEL, "仰视底点 → 拒绝");
        isNaN(RangefinderMath.groundDistanceM(0d, 1.5d), "平视距离不给值");
        isNaN(RangefinderMath.groundDistanceM(-1d, 1.5d), "近水平距离不给值");

        // 底部接近竖直：tan 爆掉，拒绝。
        check(RangefinderMath.baseStatus(-86d, 1.5d) == RangefinderMath.Status.BASE_TOO_STEEP, "底点 -86° → 拒绝");
        isNaN(RangefinderMath.groundDistanceM(-86d, 1.5d), "近竖直距离不给值");

        // 距离超上限：大高度 + 小俯角（仍 >2°）→ 距离 >500，拒绝。
        check(RangefinderMath.baseStatus(-2.5d, 50d) == RangefinderMath.Status.DISTANCE_OUT_OF_RANGE, "距离超限 → 拒绝");
        isNaN(RangefinderMath.groundDistanceM(-2.5d, 50d), "超限距离不给值");

        // 高度非法输入：h<=0 或 NaN。
        check(RangefinderMath.baseStatus(-45d, 0d) == RangefinderMath.Status.INVALID_INPUT, "高度 0 → 拒绝");
        check(RangefinderMath.baseStatus(-45d, -1d) == RangefinderMath.Status.INVALID_INPUT, "高度负 → 拒绝");
        check(RangefinderMath.baseStatus(Double.NaN, 1.5d) == RangefinderMath.Status.INVALID_INPUT, "NaN 角 → 拒绝");

        // 顶点低于/等于底点：无法测高，显式禁用。
        check(RangefinderMath.heightStatus(-10d, -20d, 1.5d) == RangefinderMath.Status.TOP_NOT_ABOVE_BASE,
                "顶点低于底点 → 拒绝");
        check(RangefinderMath.heightStatus(-10d, -10d, 1.5d) == RangefinderMath.Status.TOP_NOT_ABOVE_BASE,
                "顶点等于底点 → 拒绝");
        isNaN(RangefinderMath.objectHeightM(-10d, -20d, 1.5d), "顶点低于底点不给高度");

        // 顶点接近竖直：拒绝。
        check(RangefinderMath.heightStatus(-45d, 89.5d, 1.5d) == RangefinderMath.Status.TOP_TOO_STEEP,
                "顶点 89.5° → 拒绝");
        isNaN(RangefinderMath.objectHeightM(-45d, 89.5d, 1.5d), "近竖直顶点不给高度");

        // 底点不合法时，测高也一并拒绝（状态向上传递）。
        check(RangefinderMath.heightStatus(0d, 20d, 1.5d) == RangefinderMath.Status.BASE_TOO_LEVEL,
                "底点非法 → 测高连带拒绝");
    }

    private static void calibration() {
        // 真实角：h=1.5, D=10。
        double truth = RangefinderMath.trueBaseElevationDeg(1.5d, 10d);
        near(truth, -Math.toDegrees(Math.atan(0.15d)), 1e-9, "真实俯角 = -atan(h/D)");

        // 设备存在 +3° 偏差：实测角 = 真实 + 3。
        double measured = truth + 3d;
        double offset = RangefinderMath.zeroOffsetDeg(measured, 1.5d, 10d);
        near(offset, 3d, 1e-9, "零偏 = 实测-真实 = 3°");

        // 修正后用同一公式应还原出已知距离 D。
        double corrected = measured - offset;
        near(RangefinderMath.groundDistanceM(corrected, 1.5d), 10d, 1e-6, "校准后还原距离");

        // 无偏差时零偏为 0。
        near(RangefinderMath.zeroOffsetDeg(truth, 1.5d, 10d), 0d, 1e-9, "无偏差 → 零偏 0");

        // 非法参数 → NaN。
        isNaN(RangefinderMath.zeroOffsetDeg(measured, 0d, 10d), "高度 0 校准 → NaN");
        isNaN(RangefinderMath.zeroOffsetDeg(measured, 1.5d, 0d), "距离 0 校准 → NaN");
        isNaN(RangefinderMath.trueBaseElevationDeg(-1d, 10d), "负高度真实角 → NaN");
        isNaN(RangefinderMath.trueBaseElevationDeg(1.5d, Double.POSITIVE_INFINITY), "无限距离 → NaN");
        isNaN(RangefinderMath.zeroOffsetDeg(Double.POSITIVE_INFINITY, 1.5d, 10d), "无限角度 → NaN");
    }

    private static void smoothing() {
        near(RangefinderMath.lowPass(0f, 10f, 0.5f), 5f, 1e-6, "低通 alpha=0.5");
        near(RangefinderMath.lowPass(2f, 8f, 0f), 2f, 1e-6, "alpha=0 保持");
        near(RangefinderMath.lowPass(2f, 8f, 1f), 8f, 1e-6, "alpha=1 直接采用");
        near(RangefinderMath.lowPass(2f, Float.NaN, 0.5f), 2f, 1e-6, "NaN 输入保持原值");
    }
}
