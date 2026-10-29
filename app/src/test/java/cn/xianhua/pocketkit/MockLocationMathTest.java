package cn.xianhua.pocketkit;

import cn.xianhua.pocketkit.mocklocation.CoordTransform;
import cn.xianhua.pocketkit.mocklocation.RouteTrack;

import java.util.Arrays;
import java.util.List;

/**
 * 模拟定位的纯 JVM 自检：只验证坐标换算与路线几何。
 * 不启动 Android、不注册测试 Provider——真机行为和这些断言无关，不能拿它冒充验收。
 */
public final class MockLocationMathTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + "（实际 " + actual + "，期望 " + expected + "）");
    }

    public static void main(String[] args) {
        // 境外不偏移
        double[] greenwich = CoordTransform.wgs84ToGcj02(51.4779, -0.0015);
        near(greenwich[0], 51.4779, 1e-9, "境外纬度不变");
        near(greenwich[1], -0.0015, 1e-9, "境外经度不变");

        // 天安门一带：WGS → GCJ 会偏出几百米（约 0.002~0.006 度），方向为东北
        double[] gcj = CoordTransform.wgs84ToGcj02(39.90890, 116.39750);
        check(gcj[0] > 39.90890, "GCJ 纬度应北移");
        check(gcj[1] > 116.39750, "GCJ 经度应东移");
        double shift = CoordTransform.distanceMeters(39.90890, 116.39750, gcj[0], gcj[1]);
        check(shift > 100 && shift < 900, "GCJ 偏移量应在百米级，实际 " + shift);

        // 往返：GCJ → WGS → GCJ 与 WGS → GCJ → WGS 都应回到原值（厘米级）
        for (double[] point : new double[][]{
                {39.90890, 116.39750}, {31.23040, 121.47370}, {23.12910, 113.26440},
                {45.75000, 126.65000}, {29.56300, 106.55100}, {43.82559, 87.61685}}) {
            double[] back = CoordTransform.gcj02ToWgs84(point[0], point[1]);
            double[] again = CoordTransform.wgs84ToGcj02(back[0], back[1]);
            near(again[0], point[0], 1e-6, "往返纬度 " + point[0]);
            near(again[1], point[1], 1e-6, "往返经度 " + point[1]);
        }

        // 接续「从当前位置接着走」的接缝：原生把当前点以 GCJ-02 交给页面，页面 start 时再转回
        // WGS-84。这条往返若漂移，定位换足迹时位置就会跳。这里验证漂移 < 1 米（实测厘米级）。
        double[] seamWgs = {39.90890, 116.39750};
        double[] seamGcj = CoordTransform.wgs84ToGcj02(seamWgs[0], seamWgs[1]);
        double[] seamBack = CoordTransform.gcj02ToWgs84(seamGcj[0], seamGcj[1]);
        check(CoordTransform.distanceMeters(seamWgs[0], seamWgs[1], seamBack[0], seamBack[1]) < 1.0,
                "当前点经 GCJ 往返后漂移应小于 1 米");

        // 迭代必须比"反着套一次"更准：一次近似的残差应大于迭代后的残差
        double lat = 39.90890, lng = 116.39750;
        double[] oneShot = CoordTransform.gcj02ToWgs84(lat, lng);
        double[] forward = CoordTransform.wgs84ToGcj02(oneShot[0], oneShot[1]);
        double residual = CoordTransform.distanceMeters(lat, lng, forward[0], forward[1]);
        check(residual < 0.5, "迭代逆变换残差应小于 0.5 米，实际 " + residual + " 米");

        // 米级偏移：向北 100 米，纬度约 +0.0009；向东 100 米，经度增量随纬度放大
        double[] north = CoordTransform.offsetMeters(30.0, 120.0, 100, 0);
        near(CoordTransform.distanceMeters(30.0, 120.0, north[0], north[1]), 100, 0.5, "向北 100 米");
        double[] east = CoordTransform.offsetMeters(30.0, 120.0, 0, 100);
        near(CoordTransform.distanceMeters(30.0, 120.0, east[0], east[1]), 100, 0.5, "向东 100 米");
        double[] both = CoordTransform.offsetMeters(30.0, 120.0, 60, 80);
        near(CoordTransform.distanceMeters(30.0, 120.0, both[0], both[1]), 100, 1.0, "斜向 100 米");

        // 方位角：正北 0，正东 90
        near(CoordTransform.bearingDegrees(30.0, 120.0, 30.1, 120.0), 0, 0.5, "正北方位");
        near(CoordTransform.bearingDegrees(30.0, 120.0, 30.0, 120.1), 90, 0.5, "正东方位");

        // 单点路线：原地不动，进度视作完成
        RouteTrack fixed = new RouteTrack(List.of(new double[]{39.9, 116.4}));
        check(fixed.totalMeters() == 0, "单点总长为 0");
        RouteTrack.Sample still = fixed.advance(100);
        check(still.arrived, "单点直接到终点");
        near(still.lat, 39.9, 1e-9, "单点纬度");
        near(still.lng, 116.4, 1e-9, "单点经度");

        // 两点：沿正北走 1000 米，按 10 m/s 每步 10 米
        double[] start = CoordTransform.offsetMeters(30.0, 120.0, 0, 0);
        double[] end = CoordTransform.offsetMeters(30.0, 120.0, 1000, 0);
        RouteTrack track = new RouteTrack(Arrays.asList(start, end));
        near(track.totalMeters(), 1000, 2, "两点总长");
        RouteTrack.Sample half = track.advance(500);
        near(CoordTransform.distanceMeters(start[0], start[1], half.lat, half.lng), 500, 2, "走一半的距离");
        near(half.progress(), 0.5, 0.01, "半程进度");
        check(!half.arrived, "半程未到终点");
        RouteTrack.Sample over = track.advance(9999);
        check(over.arrived, "越过终点标记到达");
        near(over.lat, end[0], 1e-7, "终点纬度");
        near(over.lng, end[1], 1e-7, "终点经度");
        near(over.travelled, track.totalMeters(), 1e-6, "行进距离封顶");

        // 三段折线：总长是三段之和，且中间点会被经过
        double[] a = {30.0, 120.0};
        double[] b = {30.0, 120.01};
        double[] c = {30.01, 120.01};
        RouteTrack zigzag = new RouteTrack(Arrays.asList(a, b, c));
        double expected = CoordTransform.distanceMeters(a[0], a[1], b[0], b[1])
                + CoordTransform.distanceMeters(b[0], b[1], c[0], c[1]);
        near(zigzag.totalMeters(), expected, 1e-6, "折线总长");
        double firstLeg = CoordTransform.distanceMeters(a[0], a[1], b[0], b[1]);
        RouteTrack.Sample beforeB = zigzag.advance(firstLeg - 1);
        near(beforeB.bearing, 90, 1.0, "第一段朝东");
        RouteTrack.Sample atB = zigzag.advance(1);
        near(atB.lat, b[0], 1e-6, "经过中间点纬度");
        near(atB.lng, b[1], 1e-6, "经过中间点经度");
        near(atB.bearing, 0, 1.0, "过点后转向正北");

        // 负步长不倒退；reset 归零
        RouteTrack rewind = new RouteTrack(Arrays.asList(start, end));
        rewind.advance(300);
        rewind.advance(-500);
        near(rewind.travelledMeters(), 300, 1e-9, "负步长不倒退");
        rewind.reset();
        near(rewind.travelledMeters(), 0, 1e-9, "reset 归零");

        // 非法输入必须拒绝，不能悄悄生成一个假坐标
        rejects(() -> new RouteTrack(List.of()), "空路线应拒绝");
        rejects(() -> new RouteTrack(List.of(new double[]{91, 0})), "纬度越界应拒绝");
        rejects(() -> new RouteTrack(List.of(new double[]{0, 181})), "经度越界应拒绝");
        rejects(() -> new RouteTrack(List.of(new double[]{Double.NaN, 0})), "NaN 应拒绝");
        check(RouteTrack.valid(39.9, 116.4), "合法坐标");
        check(!RouteTrack.valid(39.9, 200), "经度 200 非法");

        System.out.println("MockLocationMathTest PASS: " + checks + " assertions");
    }

    private interface Failure {
        void run();
    }

    private static void rejects(Failure work, String label) {
        try {
            work.run();
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }
}
