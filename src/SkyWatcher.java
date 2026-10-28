import java.io.IOException;
import java.util.*;

/**
 * 核心逻辑：拉数据、维护每架飞机的轨迹、跑探测器、记录提醒。
 * 命令行版 (FlightTracker) 和网页版 (WebApp) 共用这一个类。
 */
public class SkyWatcher {
    static final int POLL_SECONDS = 10;
    static final long FORGET_AFTER_MS = 5 * 60_000; // 这么久没收到信号，就当飞机已经离开
    static final int MAX_FEED = 100;

    /** 一条提醒。kind 用来给网页上色：emergency / go-around / holding / rare。 */
    record FeedItem(long timeMs, String hex, String callsign, String kind, String message) {}

    final double lat, lon, radiusNm;
    private final AircraftSource source;
    private final List<Detector> detectors = List.of(
            new EmergencySquawkDetector(),
            new GoAroundDetector(),
            new HoldingDetector(),
            new RareTypeDetector());

    private final Map<String, Track> tracks = new HashMap<>(); // key 是飞机的 ICAO hex 地址
    private final Deque<FeedItem> feed = new ArrayDeque<>();   // 最新的在前面
    private List<Aircraft> current = List.of();
    private long lastUpdateMs = 0;

    SkyWatcher(double lat, double lon, double radiusNm, AircraftSource source) {
        this.lat = lat;
        this.lon = lon;
        this.radiusNm = radiusNm;
        this.source = source;
    }

    /**
     * 参数: [纬度 经度 半径(海里) [数据源 URL]]
     * 默认位置: Vanderbilt University, Nashville（BNA 机场在东边约 6 海里）
     */
    static SkyWatcher fromArgs(String[] args) {
        double lat = args.length > 0 ? Double.parseDouble(args[0]) : 36.1447;
        double lon = args.length > 1 ? Double.parseDouble(args[1]) : -86.8027;
        double radiusNm = args.length > 2 ? Double.parseDouble(args[2]) : 25;
        String url = args.length > 3 ? args[3]
                : "https://api.adsb.lol/v2/point/" + lat + "/" + lon + "/" + (int) radiusNm;

        // 录制原始数据给以后训练模型用；数据目录有问题也不影响程序运行
        Recorder recorder = null;
        try {
            recorder = Recorder.fromConfig();
            System.out.println("Recording to " + recorder.dir());
        } catch (IOException e) {
            System.out.println("Recording disabled, can't use " + Recorder.dataDir() + ": " + e.getMessage());
        }
        return new SkyWatcher(lat, lon, radiusNm, new HttpJsonSource(url, recorder));
    }

    /** 拉一次数据并跑一遍探测器，返回这一轮新产生的提醒。 */
    List<FeedItem> poll() throws Exception {
        // 网络请求放在锁外面，免得网页取数据时被卡住
        List<Aircraft> planes = source.fetch().stream()
                .filter(a -> a.lat() != null && a.lon() != null)
                .filter(a -> distanceNm(a) <= radiusNm)
                .sorted(Comparator.comparingDouble(this::distanceNm))
                .toList();

        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Aircraft a : planes) tracks.computeIfAbsent(a.hex(), h -> new Track()).add(now, a);
            tracks.values().removeIf(t -> now - t.latestTime() > FORGET_AFTER_MS);

            List<FeedItem> fresh = new ArrayList<>();
            for (Track t : tracks.values()) {
                if (t.latestTime() != now) continue; // 这一轮没收到的飞机不检查
                Aircraft a = t.latest();
                for (Detector d : detectors) {
                    d.check(t)
                            .filter(alert -> t.markAlerted(alert.key()))
                            .ifPresent(alert -> fresh.add(
                                    new FeedItem(now, a.hex(), a.callsign(), kindOf(alert.key()), alert.message())));
                }
            }
            for (FeedItem f : fresh) feed.addFirst(f);
            while (feed.size() > MAX_FEED) feed.removeLast();

            current = planes;
            lastUpdateMs = now;
            return fresh;
        }
    }

    synchronized List<Aircraft> aircraft() {
        return current;
    }

    synchronized List<FeedItem> feed() {
        return new ArrayList<>(feed);
    }

    synchronized long lastUpdateMs() {
        return lastUpdateMs;
    }

    /** 某架飞机最近 15 分钟的轨迹；没有这架飞机就返回空列表。 */
    synchronized List<Track.Sample> track(String hex) {
        Track t = tracks.get(hex);
        return t == null ? List.of() : t.since(Track.KEEP_MS);
    }

    double distanceNm(Aircraft a) {
        return Geo.distanceNm(lat, lon, a.lat(), a.lon());
    }

    static String kindOf(String alertKey) {
        if (alertKey.startsWith("squawk") || alertKey.startsWith("emergency")) return "emergency";
        return alertKey; // go-around / holding / rare
    }
}
