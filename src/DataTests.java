import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 测试录制 (Recorder) 和落地判断 (LandingStats)。在临时文件夹里测，不碰你的真实数据。
 * 运行: java DataTests.java
 */
public class DataTests {
    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        testRecorder();
        testLandings();
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void testRecorder() throws Exception {
        Path tmp = Files.createTempDirectory("recorder-test");
        try {
            // 以前的 .csv 在启动时被压缩
            String old = Recorder.HEADER + "\n1,1,abc\n";
            Files.writeString(tmp.resolve("2020-01-01.csv"), old);
            Recorder r = new Recorder(tmp);
            Path gz = tmp.resolve("2020-01-01.csv.gz");
            check("旧的 .csv 被压缩成 .csv.gz", Files.exists(gz) && !Files.exists(tmp.resolve("2020-01-01.csv")));
            try (var in = new GZIPInputStream(Files.newInputStream(gz))) {
                check("压缩后内容不变", new String(in.readAllBytes(), StandardCharsets.UTF_8).equals(old));
            }

            // 同一天写两次（中间重启程序），表头只出现一次
            long t = System.currentTimeMillis();
            Map<String, Object> plane = new HashMap<>(Map.of(
                    "hex", "a1b2c3", "flight", "SWA646  ", "alt_baro", "ground", "gs", 12.0, "seen_pos", 0.5));
            r.record(t, List.of(plane));
            r.close();
            Recorder r2 = new Recorder(tmp);
            r2.record(t + 10_000, List.of(plane));
            r2.close();

            Path today = Files.list(tmp).filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
            List<String> lines = Files.readAllLines(today);
            check("重启后表头只有一行", lines.size() == 3 && lines.get(0).equals(Recorder.HEADER)
                    && lines.stream().filter(l -> l.equals(Recorder.HEADER)).count() == 1);

            List<String> header = List.of(Recorder.HEADER.split(","));
            String[] row = lines.get(1).split(",", -1);
            check("呼号去掉了空格", row[header.indexOf("flight")].equals("SWA646"));
            check("12.0 存成 12", row[header.indexOf("gs")].equals("12"));
            check("pos_time = time - seen_pos", Long.parseLong(row[1]) == t - 500);
            check("没有的字段留空", row[header.indexOf("ias")].isEmpty());

            // 上次被强制关掉，最后一行只写了一半
            Files.writeString(today, "123,45,半行", StandardOpenOption.APPEND);
            Recorder r3 = new Recorder(tmp);
            r3.record(t + 20_000, List.of(plane));
            r3.close();
            lines = Files.readAllLines(today);
            check("半行之后的新数据另起一行", lines.get(lines.size() - 2).equals("123,45,半行")
                    && lines.get(lines.size() - 1).startsWith(Long.toString(t + 20_000)));

            check("含逗号的值加引号", Recorder.csv("a,b").equals("\"a,b\""));
        } finally {
            try (var files = Files.list(tmp)) {
                for (Path p : files.toList()) Files.delete(p);
            }
            Files.delete(tmp);
        }
    }

    static void testLandings() {
        Airport bna = Airport.NEARBY.get(0);
        double lat = bna.lat(), lon = bna.lon();

        // 正常进近 + 落地
        List<LandingStats.Point> t = new ArrayList<>();
        int[] alts = {3000, 2200, 1500, 1000, 700};
        for (int i = 0; i < alts.length; i++) {
            t.add(new LandingStats.Point(i * 20_000L, lat - 0.06 + i * 0.012, lon, alts[i], false, -800));
        }
        t.add(new LandingStats.Point(120_000, lat, lon, null, true, null));
        t.add(new LandingStats.Point(130_000, lat, lon + 0.005, null, true, null));
        check("进近 + 地面 -> 1 次落地", LandingStats.countLandings(t, bna) == 1);

        // 短五边时信号消失
        List<LandingStats.Point> lost = new ArrayList<>(t.subList(0, alts.length));
        check("贴近地面时信号消失 -> 1 次落地", LandingStats.countLandings(lost, bna) == 1);

        // 起飞
        List<LandingStats.Point> dep = List.of(
                new LandingStats.Point(0, lat, lon, null, true, null),
                new LandingStats.Point(10_000, lat, lon, null, true, null),
                new LandingStats.Point(20_000, lat + 0.01, lon, 1100, false, 2500),
                new LandingStats.Point(30_000, lat + 0.03, lon, 2500, false, 2500));
        check("起飞 -> 0 次落地", LandingStats.countLandings(dep, bna) == 0);

        // 复飞：低空之后又爬升走了，没有落地
        List<LandingStats.Point> ga = new ArrayList<>(lost);
        ga.add(new LandingStats.Point(110_000, lat, lon, 1500, false, 2000));
        ga.add(new LandingStats.Point(120_000, lat + 0.02, lon, 2500, false, 2000));
        check("复飞 -> 0 次落地", LandingStats.countLandings(ga, bna) == 0);

        // 停在机场的飞机（没有进近过程）
        List<LandingStats.Point> parked = List.of(
                new LandingStats.Point(0, lat, lon, null, true, null),
                new LandingStats.Point(600_000, lat, lon, null, true, null));
        check("一直停在地面 -> 0 次落地", LandingStats.countLandings(parked, bna) == 0);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++; else failed++;
    }
}
