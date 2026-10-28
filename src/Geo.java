/** 地理计算工具。 */
public class Geo {
    static final double EARTH_RADIUS_NM = 3440.065;
    private static final String[] COMPASS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    /** 两个经纬度之间的大圆距离（海里），Haversine 公式。 */
    static double distanceNm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_NM * Math.asin(Math.sqrt(h));
    }

    /** 从点 1 看点 2 的真方位角（0-360°，0 = 正北）。 */
    static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static String compass(double bearingDeg) {
        return COMPASS[(int) Math.round(bearingDeg / 45) % 8];
    }

    /** 把航向差规整到 -180 ~ +180，比如 350° → 10° 是右转 20°，不是左转 340°。 */
    static double headingDelta(double from, double to) {
        return ((to - from + 540) % 360) - 180;
    }
}
