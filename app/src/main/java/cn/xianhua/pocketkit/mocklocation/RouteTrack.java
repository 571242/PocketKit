package cn.xianhua.pocketkit.mocklocation;

import java.util.List;

/**
 * 足迹路线：把若干途经点连成折线，按走过的米数插值出当前位置。
 *
 * 只做几何，不碰 Android 和定时器，方便纯 JVM 断言「总长、按速度行进、到终点停住」。
 * 坐标一律 WGS-84（真正写进系统的坐标系），界面上的 GCJ-02 转换在更外层完成。
 */
public final class RouteTrack {

    /** 一次采样结果。 */
    public static final class Sample {
        public final double lat;
        public final double lng;
        /** 行进方向，正北为 0。静止时为 0。 */
        public final double bearing;
        public final double travelled;
        public final double total;
        public final boolean arrived;

        Sample(double lat, double lng, double bearing, double travelled, double total, boolean arrived) {
            this.lat = lat;
            this.lng = lng;
            this.bearing = bearing;
            this.travelled = travelled;
            this.total = total;
            this.arrived = arrived;
        }

        public double progress() {
            return total <= 0 ? 1 : Math.max(0, Math.min(1, travelled / total));
        }
    }

    private final double[] lat;
    private final double[] lng;
    private final double[] cumulative;
    private double travelled;

    /**
     * @param points 至少一个 {lat, lng}；单点表示原地不动。
     */
    public RouteTrack(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个坐标点");
        }
        int count = points.size();
        lat = new double[count];
        lng = new double[count];
        cumulative = new double[count];
        for (int i = 0; i < count; i++) {
            double[] point = points.get(i);
            if (point == null || point.length < 2) {
                throw new IllegalArgumentException("坐标格式错误");
            }
            double la = point[0];
            double ln = point[1];
            if (!valid(la, ln)) {
                throw new IllegalArgumentException("坐标超出范围：" + la + "," + ln);
            }
            lat[i] = la;
            lng[i] = ln;
        }
        for (int i = 1; i < count; i++) {
            cumulative[i] = cumulative[i - 1] + CoordTransform.distanceMeters(lat[i - 1], lng[i - 1], lat[i], lng[i]);
        }
    }

    public static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    public double totalMeters() {
        return cumulative[cumulative.length - 1];
    }

    public double travelledMeters() {
        return travelled;
    }

    public void reset() {
        travelled = 0;
    }

    /** 前进若干米（负数忽略），返回新位置。越过终点后停在终点。 */
    public Sample advance(double meters) {
        if (meters > 0) {
            travelled = Math.min(totalMeters(), travelled + meters);
        }
        return sample();
    }

    public Sample sample() {
        int count = lat.length;
        double total = totalMeters();
        if (count == 1) {
            return new Sample(lat[0], lng[0], 0, 0, 0, true);
        }
        if (travelled >= total) {
            double bearing = CoordTransform.bearingDegrees(lat[count - 2], lng[count - 2], lat[count - 1], lng[count - 1]);
            return new Sample(lat[count - 1], lng[count - 1], bearing, total, total, true);
        }
        int segment = 0;
        while (segment < count - 2 && cumulative[segment + 1] <= travelled) {
            segment++;
        }
        double segmentLength = cumulative[segment + 1] - cumulative[segment];
        double ratio = segmentLength <= 0 ? 0 : (travelled - cumulative[segment]) / segmentLength;
        double la = lat[segment] + (lat[segment + 1] - lat[segment]) * ratio;
        double ln = lng[segment] + (lng[segment + 1] - lng[segment]) * ratio;
        double bearing = CoordTransform.bearingDegrees(lat[segment], lng[segment], lat[segment + 1], lng[segment + 1]);
        return new Sample(la, ln, bearing, travelled, total, false);
    }
}
