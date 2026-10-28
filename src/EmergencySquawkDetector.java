import java.util.Map;
import java.util.Optional;

/** 紧急应答机代码（squawk）和 ADS-B 紧急状态。 */
public class EmergencySquawkDetector implements Detector {

    static final Map<String, String> SQUAWKS = Map.of(
            "7700", "一般紧急情况 (general emergency)",
            "7600", "无线电通信失效 (NORDO)",
            "7500", "非法干扰/劫持 (unlawful interference)，实际多为误设"
    );

    // ADS-B 自带的 emergency 字段。lifeguard 是医疗优先航班，不算紧急，不在这里。
    static final Map<String, String> EMERGENCIES = Map.of(
            "general", "一般紧急情况",
            "minfuel", "燃油不足 (minimum fuel)",
            "nordo", "无线电通信失效 (NORDO)",
            "unlawful", "非法干扰/劫持",
            "downed", "飞机迫降 (downed aircraft)"
    );

    @Override
    public Optional<Alert> check(Track track) {
        Aircraft a = track.latest();
        // 注意: Map.of 创建的 Map 用 null 去 get 会抛异常，有的飞机不发 squawk
        String meaning = a.squawk() == null ? null : SQUAWKS.get(a.squawk());
        String key = "squawk" + a.squawk();
        if (meaning == null && a.emergency() != null) {
            meaning = EMERGENCIES.get(a.emergency());
            key = "emergency-" + a.emergency();
        }
        if (meaning == null) return Optional.empty();

        String where = a.lat() == null ? "位置未知" : Airport.describe(a.lat(), a.lon());
        String squawk = a.squawk() == null ? "" : "squawk " + a.squawk() + "，";
        return Optional.of(new Alert(key, String.format("%s %s%s，%s，%s",
                a.displayName(), squawk, meaning, where, Aircraft.formatAltitude(a.altitudeFt()))));
    }
}
