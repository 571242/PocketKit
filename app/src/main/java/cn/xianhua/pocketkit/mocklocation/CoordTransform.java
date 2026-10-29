package cn.xianhua.pocketkit.mocklocation;

/**
 * 中国国测局坐标（GCJ-02）与 WGS-84 的互转，以及米级偏移/距离工具。
 *
 * 为什么需要两个方向：
 *  - 界面上的高德瓦片是 GCJ-02，用户点出来的点是 GCJ-02，写进系统前必须还原成 WGS-84；
 *  - 搜索结果来自 OpenStreetMap（WGS-84），画到高德瓦片上必须转成 GCJ-02。
 * 只做单向会让地图上的点和真正注入的坐标差出几百米。
 *
 * 纯 Java，无 Android 依赖，可直接在 JVM 里断言。
 */
public final class CoordTransform {

    /** 克拉索夫斯基椭球长半轴。 */
    private static final double A = 6378245.0;
    /** 第一偏心率平方。 */
    private static final double EE = 0.00669342162296594323;
    /** 迭代求解的收敛阈值：1e-9 度约 0.1 毫米。 */
    private static final double EPSILON = 1e-9;

    private CoordTransform() {
    }

    /** 境外不做偏移；这也是 GCJ-02 只在中国大陆生效的边界。 */
    public static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    /** WGS-84 → GCJ-02。返回 {lat, lng}。 */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * GCJ-02 → WGS-84。
     *
     * 正向公式没有解析逆，直接反着套一次会在市区留下 1~2 米误差；这里用不动点迭代，
     * 6 轮内基本收敛到厘米级，成本可忽略（只在用户选点时调用）。
     */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double wLat = lat;
        double wLng = lng;
        for (int i = 0; i < 6; i++) {
            double[] forward = wgs84ToGcj02(wLat, wLng);
            double dLat = forward[0] - lat;
            double dLng = forward[1] - lng;
            if (Math.abs(dLat) < EPSILON && Math.abs(dLng) < EPSILON) {
                break;
            }
            wLat -= dLat;
            wLng -= dLng;
        }
        return new double[]{wLat, wLng};
    }

    /** 在给定坐标上按「北 / 东」方向偏移若干米，用于制造合理抖动。 */
    public static double[] offsetMeters(double lat, double lng, double north, double east) {
        double dLat = north / 111_320.0;
        double cos = Math.cos(Math.toRadians(lat));
        double dLng = Math.abs(cos) < 1e-9 ? 0 : east / (111_320.0 * cos);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** 两点球面距离（米），haversine。 */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** 从 point1 指向 point2 的方位角，0 为北，顺时针。 */
    public static double bearingDegrees(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360.0) % 360.0;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
