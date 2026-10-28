import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 看看数据攒到哪一步了：每天录了多少行、BNA 落地了多少次。
 * 运行: java LandingStats.java（或者在 IntelliJ 里运行这个文件）
 *
 * 怎么算"落地"（以后训练到达时间模型，标准答案就从这里来）:
 *   飞机先在 BNA 6 nm 以内、离地 1,500 ft 以下进近，然后
 *   (a) 3 分钟内出现在 BNA 3 nm 以内的地面上，或者
 *   (b) 在离地 800 ft 以下、4 nm 以内、还在下降时信号消失（贴近地面时常常收不到信号）
 */
public class LandingStats {
    static final long GAP_MS = 10 * 60_000; // 两次记录间隔超过这么久，算作不同的一段航程

    record Point(long timeMs, double lat, double lon, Integer alt, boolean ground, Integer rate) {}

    record DayStats(String name, long bytes, long rows, long badRows, int landings) {}

    public static void main(String[] args) throws IOException {
        Path dir = Recorder.dataDir();
        Airport bna = Airport.NEARBY.stream().filter(a -> a.code().equals("BNA")).findFirst().orElseThrow();

        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.{csv,csv.gz}")) {
                s.forEach(files::add);
            }
        }
        files.sort(Comparator.naturalOrder());

        System.out.println("数据目录: " + dir);
        if (files.isEmpty()) {
            System.out.println("还没有数据。先运行 WebApp 或 FlightTracker，它们会自动录制。");
            return;
        }

        System.out.printf("%n  %-12s %9s %12s %10s%n", "日期 (UTC)", "大小", "行数", "BNA 落地");
        long totalBytes = 0, totalRows = 0;
        int totalLandings = 0;
        for (Path f : files) {
            DayStats d = analyze(f, bna);
            System.out.printf("  %-12s %7.1f MB %,12d %10d%s%n", d.name(), d.bytes() / 1e6, d.rows(), d.landings(),
                    d.badRows() > 0 ? "   (" + d.badRows() + " 行损坏，已跳过)" : "");
            totalBytes += d.bytes();
            totalRows += d.rows();
            totalLandings += d.landings();
        }
        System.out.printf("  %-12s %7.1f MB %,12d %10d%n", "合计", totalBytes / 1e6, totalRows, totalLandings);
    }

    static DayStats analyze(Path file, Airport airport) throws IOException {
        String name = file.getFileName().toString().replace(".csv.gz", "").replace(".csv", "");
        Map<String, List<Point>> tracks = new HashMap<>();
        long rows = 0, bad = 0;

        InputStream in = Files.newInputStream(file);
        if (file.toString().endsWith(".gz")) in = new GZIPInputStream(in);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = r.readLine();
            if (headerLine == null) return new DayStats(name, Files.size(file), 0, 0, 0);
            List<String> header = List.of(headerLine.split(",", -1));
            int iHex = header.indexOf("hex"), iTime = header.indexOf("pos_time_ms");
            int iLat = header.indexOf("lat"), iLon = header.indexOf("lon");
            int iAlt = header.indexOf("alt_baro"), iRate = header.indexOf("baro_rate");

            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length != header.size()) { bad++; continue; }
                rows++;
                if (c[iLat].isEmpty() || c[iLon].isEmpty()) continue;
                try {
                    boolean ground = c[iAlt].equals("ground");
                    tracks.computeIfAbsent(c[iHex], h -> new ArrayList<>()).add(new Point(
                            Long.parseLong(c[iTime]),
                            Double.parseDouble(c[iLat]),
                            Double.parseDouble(c[iLon]),
                            ground || c[iAlt].isEmpty() ? null : (int) Double.parseDouble(c[iAlt]),
                            ground,
                            c[iRate].isEmpty() ? null : (int) Double.parseDouble(c[iRate])));
                } catch (NumberFormatException e) {
                    bad++;
                }
            }
        } catch (EOFException e) {
            // 压缩文件不完整：已经读到的部分照样统计
        }

        int landings = 0;
        for (List<Point> t : tracks.values()) {
            t.sort(Comparator.comparingLong(Point::timeMs));
            landings += countLandings(t, airport);
        }
        return new DayStats(name, Files.size(file), rows, bad, landings);
    }

    /** 一架飞机按时间排好序的记录里，在 airport 落地了几次。 */
    static int countLandings(List<Point> track, Airport airport) {
        int landings = 0;
        long lastLowApproachMs = Long.MIN_VALUE / 2;
        boolean justLanded = false; // 避免同一次落地数两次

        for (int i = 0; i < track.size(); i++) {
            Point p = track.get(i);
            double dist = airport.distanceNm(p.lat(), p.lon());
            Integer agl = p.alt() == null ? null : p.alt() - airport.elevationFt();
            boolean gapBefore = i > 0 && p.timeMs() - track.get(i - 1).timeMs() > GAP_MS;
            boolean gapAfter = i == track.size() - 1 || track.get(i + 1).timeMs() - p.timeMs() > GAP_MS;

            if (justLanded) {
                // 等它下一次起飞（飞到 3,000 ft 以上）或者消失一段时间，才开始找下一次落地
                if (gapBefore || (agl != null && agl > 3000)) justLanded = false;
                else continue;
            }

            if (!p.ground() && agl != null && agl < 1500 && dist <= 6) lastLowApproachMs = p.timeMs();

            boolean touchdown = p.ground() && dist <= 3 && p.timeMs() - lastLowApproachMs <= 3 * 60_000;
            boolean lostOnShortFinal = !p.ground() && agl != null && agl < 800 && dist <= 4
                    && p.rate() != null && p.rate() < 0 && gapAfter;

            if (touchdown || lostOnShortFinal) {
                landings++;
                justLanded = true;
            }
        }
        return landings;
    }
}
