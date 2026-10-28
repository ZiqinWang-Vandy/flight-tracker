import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 头顶的飞机 —— 网页版。运行后在浏览器打开 http://localhost:8080
 *
 * 后端每 10 秒拉一次数据，网页再从这里取，所以不管开几个网页，只会请求一次 adsb.lol。
 *
 * API:
 *   GET /api/config            位置、范围、附近机场
 *   GET /api/aircraft          当前所有飞机
 *   GET /api/alerts            最近的提醒（最新的在前）
 *   GET /api/track?hex=abc123  某架飞机最近 15 分钟的轨迹
 *
 * 参数和命令行版一样: [纬度 经度 半径(海里) [数据源 URL]]
 */
public class WebApp {
    static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        SkyWatcher sky = SkyWatcher.fromArgs(args);
        Path webDir = findWebDir();

        // 后台定时拉数据
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
            try {
                sky.poll().forEach(f -> System.out.println(">>> " + f.message()));
            } catch (Exception e) {
                System.out.println("Fetch failed, will retry: " + e.getMessage());
            }
        }, 0, SkyWatcher.POLL_SECONDS, TimeUnit.SECONDS);

        // 只监听本机 (localhost)，同一网络里的其他电脑访问不到
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/api/config", ex -> sendJson(ex, config(sky)));
        server.createContext("/api/aircraft", ex -> sendJson(ex, sky.aircraft().stream().map(a -> aircraftJson(sky, a)).toList()));
        server.createContext("/api/alerts", ex -> sendJson(ex, sky.feed().stream().map(WebApp::feedJson).toList()));
        server.createContext("/api/track", ex -> sendJson(ex, trackJson(sky, queryParam(ex, "hex"))));
        server.createContext("/", ex -> serveStatic(ex, webDir));
        server.start();

        System.out.println("Flight Tracker running: http://localhost:" + PORT);
    }

    // ---------------------------------------------------------------------
    // 把 Java 对象转成 JSON 用的 Map
    // ---------------------------------------------------------------------

    static Map<String, Object> config(SkyWatcher sky) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lat", sky.lat);
        m.put("lon", sky.lon);
        m.put("radiusNm", sky.radiusNm);
        m.put("lastUpdate", sky.lastUpdateMs());
        m.put("airports", Airport.NEARBY.stream().map(a -> Map.of(
                "code", a.code(), "lat", a.lat(), "lon", a.lon())).toList());
        return m;
    }

    static Map<String, Object> aircraftJson(SkyWatcher sky, Aircraft a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hex", a.hex());
        m.put("callsign", a.callsign());
        m.put("type", a.type());
        m.put("reg", a.registration());
        m.put("alt", a.altitudeFt());
        m.put("ground", a.onGround());
        m.put("gs", a.groundSpeedKt());
        m.put("lat", a.lat());
        m.put("lon", a.lon());
        m.put("track", a.trackDeg());
        m.put("rate", a.verticalRateFpm());
        m.put("squawk", a.squawk());
        m.put("dist", Math.round(sky.distanceNm(a) * 10) / 10.0);
        return m;
    }

    static Map<String, Object> feedJson(SkyWatcher.FeedItem f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", f.timeMs());
        m.put("hex", f.hex());
        m.put("callsign", f.callsign());
        m.put("kind", f.kind());
        m.put("message", f.message());
        return m;
    }

    /** 轨迹点: [纬度, 经度, 高度]，高度在地面时为 null。 */
    static List<Object> trackJson(SkyWatcher sky, String hex) {
        if (hex == null) return List.of();
        List<Object> points = new ArrayList<>();
        for (Track.Sample s : sky.track(hex)) {
            Aircraft a = s.aircraft();
            points.add(Arrays.asList(a.lat(), a.lon(), a.altitudeFt()));
        }
        return points;
    }

    // ---------------------------------------------------------------------
    // HTTP 工具
    // ---------------------------------------------------------------------

    static void sendJson(HttpExchange ex, Object body) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name) && kv.length == 2) return kv[1];
        }
        return null;
    }

    static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon");

    /** 发送 web/ 文件夹里的网页文件。 */
    static void serveStatic(HttpExchange ex, Path webDir) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webDir.resolve(path.substring(1)).normalize();

        // 防止 /../../ 这种路径读到 web/ 外面的文件
        if (!file.startsWith(webDir) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1);
        send(ex, 200, CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
    }

    /** 在 IntelliJ 里工作目录是项目根目录；在 src 里用命令行运行时是 src。两种都支持。 */
    static Path findWebDir() {
        for (String candidate : List.of("web", "../web")) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("找不到 web 文件夹。请在项目根目录运行。");
    }
}
