import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 头顶的飞机 —— 命令行版。网页版见 WebApp.java。
 *
 * 用法:
 *   java FlightTracker.java                         默认: Vanderbilt 附近 25 海里
 *   java FlightTracker.java 36.14 -86.80 25         指定 纬度 经度 半径(海里)
 *   java FlightTracker.java 36.14 -86.80 25 http://raspberrypi.local/tar1090/data/aircraft.json
 *                                                   读取你自己的 SDR 接收站数据
 *
 * 需要 Java 17 或更高版本，不依赖任何第三方库。
 */
public class FlightTracker {

    public static void main(String[] args) throws Exception {
        SkyWatcher sky = SkyWatcher.fromArgs(args);
        System.out.printf("Watching %.3f, %.3f within %.0f nm (Ctrl+C to stop)%n%n", sky.lat, sky.lon, sky.radiusNm);

        while (true) {
            try {
                List<SkyWatcher.FeedItem> fresh = sky.poll();
                printTable(sky);
                fresh.forEach(f -> System.out.println("  >>> " + f.message()));
                System.out.println();
            } catch (Exception e) {
                System.out.println("Fetch failed, will retry: " + e.getMessage());
            }
            Thread.sleep(SkyWatcher.POLL_SECONDS * 1000L);
        }
    }

    static void printTable(SkyWatcher sky) {
        List<Aircraft> planes = sky.aircraft();
        String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %d aircraft%n", now, planes.size());
        System.out.printf("  %-9s %-6s %-8s %8s %6s %7s%n", "CALLSIGN", "TYPE", "REG", "ALT(ft)", "KT", "DIST");
        for (Aircraft a : planes.subList(0, Math.min(10, planes.size()))) {
            System.out.printf("  %-9s %-6s %-8s %8s %6s %6.1fnm%n",
                    a.callsign(), orDash(a.type()), orDash(a.registration()),
                    a.altitudeFt() == null ? "ground" : a.altitudeFt(),
                    a.groundSpeedKt() == null ? "-" : Math.round(a.groundSpeedKt()),
                    sky.distanceNm(a));
        }
        if (planes.size() > 10) System.out.println("  ... and " + (planes.size() - 10) + " more");
    }

    static String orDash(String s) {
        return s == null ? "-" : s;
    }
}
