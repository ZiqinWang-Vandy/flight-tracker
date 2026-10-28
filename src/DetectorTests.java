import java.util.Optional;

/**
 * 用模拟轨迹测试各个探测器，不用等天上真的发生复飞或紧急情况。
 * 运行: java DetectorTests.java
 */
public class DetectorTests {
    static final double BNA_LAT = 36.1245, BNA_LON = -86.6782;
    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        HoldingDetector holding = new HoldingDetector();
        GoAroundDetector goAround = new GoAroundDetector();
        EmergencySquawkDetector squawk = new EmergencySquawkDetector();
        RareTypeDetector rare = new RareTypeDetector();

        // --- 盘旋 ---
        Track t = new Track();
        for (int i = 0; i < 18; i++) {  // 每 10 秒右转 30°（标准转弯率 3°/s），3 分钟转 1.5 圈
            t.add(i * 10_000L, plane("SWA646", 8000, BNA_LAT + 0.2, BNA_LON + 0.1, (i * 30) % 360, 0, "4521", false));
        }
        expect("在 8000 ft 兜 1.5 圈 -> 盘旋", holding.check(t), true);

        t = new Track();
        for (int i = 0; i < 18; i++) {  // 航向 90° 左右小幅抖动
            t.add(i * 10_000L, plane("SWA646", 8000, BNA_LAT, BNA_LON + i * 0.01, 90 + (i % 2 == 0 ? 2 : -2), 0, "4521", false));
        }
        expect("直飞 -> 不是盘旋", holding.check(t), false);

        t = new Track();
        for (int i = 0; i < 18; i++) {
            t.add(i * 10_000L, plane("N284ME", 1500, BNA_LAT, BNA_LON, (i * 30) % 360, 0, "1200", false));
        }
        expect("小飞机 1500 ft 绕圈 -> 不报（起落航线练习）", holding.check(t), false);

        t = new Track();
        for (int i = 0; i < 18; i++) {  // 一直右转，但中间有 2 分钟没收到信号
            long time = i * 10_000L + (i >= 9 ? 120_000L : 0);
            t.add(time, plane("SWA646", 8000, BNA_LAT + 0.2, BNA_LON, (i * 30) % 360, 0, "4521", false));
        }
        expect("信号中断，两段各转不满一圈 -> 不报", holding.check(t), false);

        // --- 复飞 ---
        t = new Track();
        int[] alts = {3000, 2600, 2200, 1800, 1400, 1100, 1300, 1700, 2200};
        int[] rates = {-700, -700, -700, -700, -700, -600, 1500, 2000, 2000};
        for (int i = 0; i < alts.length; i++) {
            t.add(i * 10_000L, plane("UAL499", alts[i], BNA_LAT + 0.05, BNA_LON - 0.05, 20, rates[i], "3321", false));
        }
        expect("进近下降到 1100 ft 又爬升 -> 复飞", goAround.check(t), true);

        t = new Track();
        int[] depAlts = {800, 1500, 2300, 3100};
        for (int i = 0; i < depAlts.length; i++) {
            t.add(i * 10_000L, plane("UAL500", depAlts[i], BNA_LAT + 0.02, BNA_LON, 20, 2500, "3322", false));
        }
        expect("刚起飞一直爬升 -> 不是复飞", goAround.check(t), false);

        t = new Track();
        t.add(0, plane("DAL1477", 1500, BNA_LAT, BNA_LON - 0.03, 20, -700, "3323", false));
        t.add(10_000, plane("DAL1477", 900, BNA_LAT, BNA_LON - 0.01, 20, -700, "3323", false));
        t.add(20_000, plane("DAL1477", null, BNA_LAT, BNA_LON, 20, 0, "3323", true));
        t.add(30_000, plane("DAL1477", null, BNA_LAT, BNA_LON, 20, 0, "3323", true));
        expect("正常落地 -> 不是复飞", goAround.check(t), false);

        t = new Track();
        for (int i = 0; i < alts.length; i++) {
            t.add(i * 10_000L, plane("UAL499", alts[i] + 30000, BNA_LAT + 0.05, BNA_LON - 0.05, 20, rates[i], "3321", false));
        }
        expect("高空的高度起伏 -> 不是复飞", goAround.check(t), false);

        // --- 紧急代码 ---
        t = new Track();
        t.add(0, plane("AAL1637", 12000, BNA_LAT + 0.3, BNA_LON, 200, -1500, "7700", false));
        expect("squawk 7700 -> 报警", squawk.check(t), true);

        t = new Track();
        t.add(0, plane("N226FD", 7200, BNA_LAT + 0.3, BNA_LON, 200, 0, "1200", false));
        expect("squawk 1200（目视飞行）-> 不报", squawk.check(t), false);

        t = new Track();
        t.add(0, new Aircraft("ae1234", "R20700", "H60", null, 925, false, null,
                BNA_LAT, BNA_LON, null, null, null, null));
        expect("没有 squawk 也没有 emergency 字段 -> 不报也不崩溃", squawk.check(t), false);
        expect("没有 squawk 字段的飞机查稀有机型 -> 不崩溃", rare.check(t), false);

        // --- 稀有机型 + 去重 ---
        t = new Track();
        t.add(0, new Aircraft("4d0221", "CLX12V", "B748", "LX-VCK", 3000, false, 180.0,
                BNA_LAT, BNA_LON, 20.0, -700, "2001", "none"));
        Optional<Detector.Alert> alert = rare.check(t);
        expect("747-8 -> 稀有机型", alert, true);
        check("同一架飞机第二次不重复提醒",
                t.markAlerted(alert.get().key()) && !t.markAlerted(alert.get().key()));

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static Aircraft plane(String callsign, Integer alt, double lat, double lon, double track,
                          int rate, String squawk, boolean onGround) {
        return new Aircraft("abc123", callsign, "B738", "N12345", alt, onGround, 250.0,
                lat, lon, track, rate, squawk, "none");
    }

    static void expect(String name, Optional<Detector.Alert> result, boolean shouldAlert) {
        check(name, result.isPresent() == shouldAlert);
        result.ifPresent(a -> System.out.println("       " + a.message()));
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++; else failed++;
    }
}
