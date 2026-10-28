import java.util.Map;
import java.util.Optional;

/** 稀有机型。 */
public class RareTypeDetector implements Detector {

    // 想被提醒的机型（ICAO 机型代码）。可以随便往里加。
    static final Map<String, String> RARE_TYPES = Map.ofEntries(
            Map.entry("A388", "Airbus A380"),
            Map.entry("B748", "Boeing 747-8"),
            Map.entry("B744", "Boeing 747-400"),
            Map.entry("A124", "Antonov An-124"),
            Map.entry("A225", "Antonov An-225"),
            Map.entry("BLCF", "Boeing 747 Dreamlifter"),
            Map.entry("A3ST", "Airbus Beluga"),
            Map.entry("A337", "Airbus BelugaXL"),
            Map.entry("C17", "Boeing C-17 Globemaster"),
            Map.entry("C5M", "Lockheed C-5 Galaxy"),
            Map.entry("B52", "Boeing B-52"),
            Map.entry("K35R", "Boeing KC-135"),
            Map.entry("E3TF", "Boeing E-3 AWACS"),
            Map.entry("U2", "Lockheed U-2"),
            Map.entry("V22", "Bell Boeing V-22 Osprey")
    );

    @Override
    public Optional<Alert> check(Track track) {
        Aircraft a = track.latest();
        String name = a.type() == null ? null : RARE_TYPES.get(a.type());
        if (name == null) return Optional.empty();

        String where = a.lat() == null ? "位置未知" : Airport.describe(a.lat(), a.lon());
        String who = a.registration() == null ? a.callsign()
                : "(none)".equals(a.callsign()) ? a.registration()
                : a.callsign() + " " + a.registration();
        return Optional.of(new Alert("rare", String.format("稀有机型 %s：%s，%s，%s",
                name, who, where, Aircraft.formatAltitude(a.altitudeFt()))));
    }
}
