import java.util.Optional;

/**
 * 盘旋/等待（holding）：把每两次采样之间的航向变化累加起来，
 * 同一方向累计转过 360° 以上，说明飞机在兜圈子。直飞时的小抖动和 S 形转弯会互相抵消。
 */
public class HoldingDetector implements Detector {
    static final long WINDOW_MS = 12 * 60_000;
    static final long MAX_GAP_MS = 30_000;   // 两次采样间隔太久，中间转了多少算不准，重新累计
    static final int MIN_ALTITUDE_FT = 4000; // 低于这个高度绕圈多半是起落航线练习、直升机或拍照飞行

    @Override
    public Optional<Alert> check(Track track) {
        Aircraft now = track.latest();
        if (now.altitudeFt() == null || now.altitudeFt() < MIN_ALTITUDE_FT || now.lat() == null) {
            return Optional.empty();
        }

        double turned = 0;
        Track.Sample prev = null;
        for (Track.Sample s : track.since(WINDOW_MS)) {
            if (s.aircraft().trackDeg() == null) continue;
            if (prev != null && s.timeMs() - prev.timeMs() > MAX_GAP_MS) {
                turned = 0;
            } else if (prev != null) {
                turned += Geo.headingDelta(prev.aircraft().trackDeg(), s.aircraft().trackDeg());
            }
            prev = s;
        }

        double laps = Math.abs(turned) / 360;
        if (laps < 1) return Optional.empty();

        return Optional.of(new Alert("holding", String.format("%s 在 %s %s盘旋，%s，已转 %.1f 圈，疑似等待 (holding)",
                now.displayName(), Airport.describe(now.lat(), now.lon()),
                turned > 0 ? "右转" : "左转", Aircraft.formatAltitude(now.altitudeFt()), laps)));
    }
}
