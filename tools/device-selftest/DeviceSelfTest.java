import cn.xianhua.pocketkit.device.SensorMath;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备子代理算法自检。纯 Java，不依赖 Android 也不依赖 JUnit。
 *
 * 运行：javac -d out SensorMath.java DeviceSelfTest.java && java -cp out DeviceSelfTest
 * 只验证算法本身，不代表传感器在真机上的行为（当前无真机）。
 */
public final class DeviceSelfTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        magnitude();
        lowPass();
        pcmRms();
        dbfs();
        normalizeHeading();
        smoothHeading();
        tilt();
        percentAndBubble();
        rulerCalibration();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    private static void magnitude() {
        near("3-4-5 模长", SensorMath.magnitude(new float[]{3f, 4f, 0f}), 5f, 1e-4f);
        near("零向量", SensorMath.magnitude(new float[]{0f, 0f, 0f}), 0f, 1e-6f);
        check("null 向量返回 0", SensorMath.magnitude(null) == 0f);
        check("长度不足返回 0", SensorMath.magnitude(new float[]{1f, 2f}) == 0f);
        // 三轴都用 double 求和：极值下不能溢出成 Infinity。
        check("大值不溢出", Float.isFinite(SensorMath.magnitude(new float[]{1e20f, 1e20f, 1e20f})));
    }

    private static void lowPass() {
        near("alpha=0 保持原值", SensorMath.lowPass(10f, 99f, 0f), 10f, 1e-6f);
        near("alpha=1 直接采用", SensorMath.lowPass(10f, 99f, 1f), 99f, 1e-6f);
        near("alpha=0.5 取中点", SensorMath.lowPass(10f, 20f, 0.5f), 15f, 1e-6f);
        // 反复滤波必须收敛到输入，不能漂移。
        float v = 0f;
        for (int i = 0; i < 400; i++) {
            v = SensorMath.lowPass(v, 100f, 0.1f);
        }
        near("多次滤波收敛", v, 100f, 0.1f);
    }

    private static void pcmRms() {
        near("全零静音", SensorMath.pcmRms(new short[]{0, 0, 0, 0}, 4), 0f, 1e-6f);
        near("满量程方波 RMS 等于幅值", SensorMath.pcmRms(new short[]{32767, -32768, 32767, -32768}, 4),
                32767.5f, 1f);
        // count 超出数组长度时按实际长度算，不能越界。
        near("count 超长按实际长度", SensorMath.pcmRms(new short[]{10, 10}, 99), 10f, 1e-4f);
        check("count<=0 返回 0", SensorMath.pcmRms(new short[]{5}, 0) == 0f);
        check("null 返回 0", SensorMath.pcmRms(null, 4) == 0f);
        // 单样本 [-32768] 的平方是 2^30，用 int 累加会溢出，必须用 double。
        near("极值样本不溢出", SensorMath.pcmRms(new short[]{-32768}, 1), 32768f, 1e-3f);
    }

    private static void dbfs() {
        near("满量程 0 dBFS", SensorMath.dbfs(32768f, 32768f), 0f, 1e-4f);
        near("半量程约 -6.02 dBFS", SensorMath.dbfs(16384f, 32768f), -6.0206f, 1e-3f);
        near("十分之一约 -20 dBFS", SensorMath.dbfs(3276.8f, 32768f), -20f, 1e-3f);
        check("静音落到下限", SensorMath.dbfs(0f, 32768f) == SensorMath.DB_FLOOR);
        check("负值也落到下限", SensorMath.dbfs(-1f, 32768f) == SensorMath.DB_FLOOR);
        check("全量程为 0 时不为 NaN", SensorMath.dbfs(10f, 0f) == SensorMath.DB_FLOOR);
        check("结果永远有限", Double.isFinite(SensorMath.dbfs(1e-30f, 32768f)));
    }

    private static void normalizeHeading() {
        near("0 保持", SensorMath.normalizeHeading(0f), 0f, 1e-6f);
        near("360 归零", SensorMath.normalizeHeading(360f), 0f, 1e-6f);
        near("负角折算", SensorMath.normalizeHeading(-90f), 270f, 1e-6f);
        near("-450 折算", SensorMath.normalizeHeading(-450f), 270f, 1e-6f);
        near("720 归零", SensorMath.normalizeHeading(720f), 0f, 1e-6f);
        check("NaN 归零", SensorMath.normalizeHeading(Float.NaN) == 0f);
        check("Infinity 归零", SensorMath.normalizeHeading(Float.POSITIVE_INFINITY) == 0f);
    }

    private static void smoothHeading() {
        // 关键用例：359° 到 1° 的平滑不能绕一整圈。
        float r = SensorMath.smoothHeading(359f, 1f, 0.5f);
        near("跨 0 度走最短路径", r, 0f, 1e-3f);
        float r2 = SensorMath.smoothHeading(1f, 359f, 0.5f);
        near("反向跨 0 度", r2, 0f, 1e-3f);
        near("同向平滑", SensorMath.smoothHeading(10f, 20f, 0.5f), 15f, 1e-3f);
        near("alpha=1 直接采用", SensorMath.smoothHeading(10f, 350f, 1f), 350f, 1e-3f);
        // 反复平滑应停在目标附近而不是抖动。
        float v = 350f;
        for (int i = 0; i < 200; i++) {
            v = SensorMath.smoothHeading(v, 10f, 0.2f);
        }
        near("收敛到目标角度", v, 10f, 0.5f);
    }

    private static void tilt() {
        // 平放：x=0, y=0, z=9.8 -> 两个方向都是 0 度。
        near("平放 tiltX", SensorMath.tiltX(new float[]{0f, 0f, 9.8f}), 0f, 1e-3f);
        near("平放 tiltY", SensorMath.tiltY(new float[]{0f, 0f, 9.8f}), 0f, 1e-3f);
        // 绕 Y 轴倾斜 45 度：x=z。
        near("倾斜 45 度", SensorMath.tiltX(new float[]{7f, 0f, 7f}), 45f, 1e-3f);
        near("倾斜 -45 度", SensorMath.tiltX(new float[]{-7f, 0f, 7f}), -45f, 1e-3f);
        // 竖直时 z 接近 0，atan2 仍然给出有限值，不会因为除零崩掉。
        check("竖直仍有限", Float.isFinite(SensorMath.tiltX(new float[]{0f, 9.8f, 0f})));
        check("null 安全", SensorMath.tiltX(null) == 0f);
    }

    private static void percentAndBubble() {
        check("下限 0%", SensorMath.percent(0f, 0f, 100f) == 0);
        check("中点 50%", SensorMath.percent(50f, 0f, 100f) == 50);
        check("上限 100%", SensorMath.percent(100f, 0f, 100f) == 100);
        check("超出上限截断", SensorMath.percent(500f, 0f, 100f) == 100);
        check("低于下限截断", SensorMath.percent(-5f, 0f, 100f) == 0);
        check("范围为空返回 0", SensorMath.percent(5f, 10f, 10f) == 0);

        near("气泡居中", SensorMath.bubble(0f, 15f), 0f, 1e-6f);
        near("气泡上限截断", SensorMath.bubble(100f, 15f), 1f, 1e-6f);
        near("气泡下限截断", SensorMath.bubble(-100f, 15f), -1f, 1e-6f);
        near("气泡中点", SensorMath.bubble(7.5f, 15f), 0.5f, 1e-6f);
        check("零量程返回 0", SensorMath.bubble(5f, 0f) == 0f);
    }

    private static void rulerCalibration() {
        near("校准系数", SensorMath.pxPerMm(856f, 85.6f), 10f, 1e-3f);
        near("像素转毫米", SensorMath.pxToMm(30f, 10f), 3f, 1e-4f);
        check("未校准返回 -1", SensorMath.pxToMm(30f, 0f) == -1f);
        check("非法校准返回 0", SensorMath.pxPerMm(0f, 85.6f) == 0f);
        check("负长度返回 0", SensorMath.pxPerMm(100f, -1f) == 0f);
    }

    // ---- 断言辅助 ----

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
        }
    }

    private static void near(String name, float actual, float expected, float tolerance) {
        if (Math.abs(actual - expected) <= tolerance) {
            passed++;
        } else {
            failed++;
            failures.add(name + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private static void near(String name, double actual, double expected, double tolerance) {
        if (Math.abs(actual - expected) <= tolerance) {
            passed++;
        } else {
            failed++;
            failures.add(name + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
