import java.util.List;
import java.util.Optional;

/**
 * 复飞（go-around）：航班在机场附近进近下降到很低，没有落地，又重新爬升。
 *
 * 判断条件：
 *   1. 是航班（私人小飞机常做连续起落练习，会误报）
 *   2. 在机场 8 nm 以内，最近 5 分钟没有出现在地面上（排除刚起飞的和已经落地的）
 *   3. 最低点离地不到 1,500 ft，而且是从更高处下降来的
 *   4. 现在比最低点高出 400 ft 以上，并且还在爬升
 */
public class GoAroundDetector implements Detector {
    static final long WINDOW_MS = 5 * 60_000;
    static final double MAX_DISTANCE_NM = 8;
    static final int MAX_LOW_POINT_AGL_FT = 1500;
    static final int MIN_DESCENT_FT = 300;
    static final int MIN_CLIMB_FT = 400;

    @Override
    public Optional<Alert> check(Track track) {
        Aircraft now = track.latest();
        if (!now.looksLikeAirline() || now.altitudeFt() == null || now.lat() == null) return Optional.empty();

        Airport airport = Airport.nearest(now.lat(), now.lon());
        if (airport.distanceNm(now.lat(), now.lon()) > MAX_DISTANCE_NM) return Optional.empty();

        List<Track.Sample> window = track.since(WINDOW_MS);
        if (window.stream().anyMatch(s -> s.aircraft().onGround())) return Optional.empty();

        // 找最低点
        int lowIndex = -1;
        int lowAlt = Integer.MAX_VALUE;
        for (int i = 0; i < window.size(); i++) {
            Integer alt = window.get(i).aircraft().altitudeFt();
            if (alt != null && alt < lowAlt) {
                lowAlt = alt;
                lowIndex = i;
            }
        }
        if (lowIndex < 0 || lowAlt - airport.elevationFt() > MAX_LOW_POINT_AGL_FT) return Optional.empty();

        // 最低点之前是不是在下降
        int highestBefore = window.subList(0, lowIndex).stream()
                .map(s -> s.aircraft().altitudeFt())
                .filter(alt -> alt != null)
                .max(Integer::compare).orElse(lowAlt);
        boolean descended = highestBefore - lowAlt >= MIN_DESCENT_FT;

        // 现在是不是又爬上去了
        boolean climbing = now.verticalRateFpm() == null || now.verticalRateFpm() > 0;
        boolean climbed = now.altitudeFt() - lowAlt >= MIN_CLIMB_FT && climbing;

        if (!descended || !climbed) return Optional.empty();

        return Optional.of(new Alert("go-around", String.format(
                "%s 在 %s 复飞 (go-around)：进近下降到 %s（离地约 %,d ft）后重新爬升，现在 %s",
                now.displayName(), airport.code(), Aircraft.formatAltitude(lowAlt),
                lowAlt - airport.elevationFt(), Aircraft.formatAltitude(now.altitudeFt()))));
    }
}
