import java.util.Comparator;
import java.util.List;

/** 机场。elevationFt 是机场标高，用来把气压高度换算成离地高度。 */
public record Airport(String code, double lat, double lon, int elevationFt) {

    // 纳什维尔附近的机场。换城市的话改这里。
    static final List<Airport> NEARBY = List.of(
            new Airport("BNA", 36.1245, -86.6782, 599),  // Nashville International
            new Airport("JWN", 36.1824, -86.8867, 495)   // John C. Tune（公务机/通航）
    );

    static Airport nearest(double lat, double lon) {
        return NEARBY.stream()
                .min(Comparator.comparingDouble(a -> Geo.distanceNm(a.lat, a.lon, lat, lon)))
                .orElseThrow();
    }

    double distanceNm(double lat, double lon) {
        return Geo.distanceNm(this.lat, this.lon, lat, lon);
    }

    /** 用最近的机场描述位置，比如 "BNA 东北 12 nm"。 */
    static String describe(double lat, double lon) {
        Airport a = nearest(lat, lon);
        double d = a.distanceNm(lat, lon);
        if (d < 1) return a.code + " 上空";
        return String.format("%s %s %.0f nm", a.code, Geo.compass(Geo.bearingDeg(a.lat, a.lon, lat, lon)), d);
    }
}
