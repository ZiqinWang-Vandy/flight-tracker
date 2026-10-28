import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 把每次拉到的原始数据存下来，给以后训练模型用。
 *
 * 每天（按 UTC 日期，航空业的惯例）一个文件，比如 2026-10-28.csv。
 * 当天的文件是普通 CSV，一行一行追加，程序被强制关掉也不会损坏；
 * 过了这一天，下次启动或跨过午夜时自动压缩成 .csv.gz（大约缩小到 1/6）。
 *
 * 保存位置在 local.properties 的 dataDir 里设置，默认是项目下的 data/。
 */
public class Recorder {

    // 原始 JSON 里要存的字段。有的飞机不发某些字段（比如空速、风、温度），那一格就留空。
    static final List<String> FIELDS = List.of(
            "hex", "flight", "r", "t", "category", "type",
            "lat", "lon", "alt_baro", "alt_geom", "gs", "ias", "tas", "mach", "track", "roll",
            "baro_rate", "geom_rate", "squawk", "emergency",
            "nav_altitude_mcp", "nav_heading", "nav_qnh", "wd", "ws", "oat", "seen_pos", "rssi");

    // time_ms: 这批数据的时间; pos_time_ms: 这个位置实际被收到的时间（= time_ms - seen_pos）
    static final String HEADER = "time_ms,pos_time_ms," + String.join(",", FIELDS);

    private final Path dir;
    private LocalDate day;
    private BufferedWriter out;

    Recorder(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        compressOldFiles(LocalDate.now(ZoneOffset.UTC));
    }

    static Recorder fromConfig() throws IOException {
        return new Recorder(dataDir());
    }

    static Path dataDir() {
        return Path.of(LocalConfig.get("dataDir", "data")).toAbsolutePath().normalize();
    }

    Path dir() {
        return dir;
    }

    /** serverTimeMs: 数据源给的时间戳; rows: 原始 JSON 里的飞机列表。 */
    synchronized void record(long serverTimeMs, List<Map<String, Object>> rows) throws IOException {
        LocalDate today = Instant.ofEpochMilli(serverTimeMs).atZone(ZoneOffset.UTC).toLocalDate();
        if (!today.equals(day)) openDay(today);

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            long posTime = row.get("seen_pos") instanceof Number n
                    ? serverTimeMs - Math.round(n.doubleValue() * 1000)
                    : serverTimeMs;
            sb.append(serverTimeMs).append(',').append(posTime);
            for (String f : FIELDS) sb.append(',').append(csv(row.get(f)));
            sb.append('\n');
        }
        out.write(sb.toString());
        out.flush(); // 每批都写到硬盘上，被强制关掉最多丢这 10 秒
    }

    synchronized void close() throws IOException {
        if (out != null) out.close();
        out = null;
        day = null;
    }

    private void openDay(LocalDate today) throws IOException {
        close();
        compressOldFiles(today); // 跨过午夜时把昨天的压缩掉

        Path file = dir.resolve(today + ".csv");
        boolean isNew = !Files.exists(file) || Files.size(file) == 0;
        boolean brokenLastLine = !isNew && !endsWithNewline(file); // 上次被强制关掉，最后一行可能只写了一半

        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (isNew) out.write(HEADER + "\n");
        else if (brokenLastLine) out.write("\n"); // 半行会变成一条坏数据，读的时候跳过就行
        day = today;
    }

    /** 把 today 之前的 .csv 压缩成 .csv.gz，然后删掉原文件。 */
    private void compressOldFiles(LocalDate today) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path csv : files) {
                String name = csv.getFileName().toString();
                LocalDate date;
                try {
                    date = LocalDate.parse(name.substring(0, name.length() - ".csv".length()));
                } catch (DateTimeParseException e) {
                    continue; // 不是我们生成的文件，不动它
                }
                if (!date.isBefore(today)) continue;

                Path gz = dir.resolve(name + ".gz");
                if (Files.exists(gz)) {
                    System.out.println("Not compressing " + csv + ": " + gz.getFileName() + " already exists");
                    continue;
                }
                // 先写到临时文件，写完再改名，压缩到一半断电也不会丢数据
                Path tmp = dir.resolve(name + ".gz.tmp");
                try (InputStream in = Files.newInputStream(csv);
                     OutputStream o = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                    in.transferTo(o);
                }
                Files.move(tmp, gz, StandardCopyOption.ATOMIC_MOVE);
                Files.delete(csv);
            }
        }
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            f.seek(f.length() - 1);
            return f.read() == '\n';
        }
    }

    static String csv(Object v) {
        if (v == null) return "";
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue()); // 6775.0 -> 6775，省空间
        }
        String s = v instanceof String str ? str.trim() : v.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
