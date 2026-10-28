import java.util.Map;

/** 某一时刻一架飞机的状态。字段对应 readsb/dump1090 的 aircraft.json 格式。 */
public record Aircraft(String hex, String callsign, String type, String registration,
                       Integer altitudeFt, boolean onGround, Double groundSpeedKt,
                       Double lat, Double lon, Double trackDeg, Integer verticalRateFpm,
                       String squawk, String emergency) {

    /** 从 JSON 对象创建。公共 API 和自己的接收站都是这个格式。 */
    static Aircraft fromJson(Map<String, Object> j) {
        Object alt = j.get("alt_baro"); // 在地面上时这里是字符串 "ground"
        Object rate = j.containsKey("baro_rate") ? j.get("baro_rate") : j.get("geom_rate");
        String flight = (String) j.get("flight");
        return new Aircraft(
                (String) j.get("hex"),
                flight == null || flight.isBlank() ? "(none)" : flight.trim(),
                (String) j.get("t"),
                (String) j.get("r"),
                alt instanceof Number n ? n.intValue() : null,
                "ground".equals(alt),
                num(j.get("gs")),
                num(j.get("lat")),
                num(j.get("lon")),
                num(j.get("track")),
                rate instanceof Number n ? n.intValue() : null,
                (String) j.get("squawk"),
                (String) j.get("emergency"));
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** 呼号像航班号（3 个字母 + 数字，如 SWA646），而不是 N901VU 这种私人飞机注册号。 */
    boolean looksLikeAirline() {
        return callsign.matches("[A-Z]{3}\\d[0-9A-Z]{0,3}");
    }

    /** 提醒里显示的名字，比如 "SWA646 (B738)"。 */
    String displayName() {
        String name = "(none)".equals(callsign) && registration != null ? registration : callsign;
        return type == null ? name : name + " (" + type + ")";
    }

    /** 美国过渡高度是 18,000 ft：以上用飞行高度层 FL，以下用英尺。 */
    static String formatAltitude(Integer ft) {
        if (ft == null) return "地面";
        if (ft >= 18000) return String.format("FL%03d", Math.round(ft / 100.0));
        return String.format("%,d ft", ft);
    }
}
