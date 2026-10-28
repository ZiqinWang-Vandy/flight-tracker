import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 读取 readsb/dump1090 格式的 aircraft.json（adsb.lol API 或本地接收站）。 */
public class HttpJsonSource implements AircraftSource {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String url;
    private final Recorder recorder; // 为 null 时不录制

    public HttpJsonSource(String url) {
        this(url, null);
    }

    public HttpJsonSource(String url, Recorder recorder) {
        this.url = url;
        this.recorder = recorder;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Aircraft> fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "student-flight-tracker/0.3")
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());

        Map<String, Object> root = (Map<String, Object>) new Json(resp.body()).parse();
        // adsb.lol 用 "ac"，本地 dump1090/readsb 用 "aircraft"
        Object list = root.containsKey("ac") ? root.get("ac") : root.get("aircraft");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;

        if (recorder != null) {
            try {
                recorder.record(serverTimeMs(root), rows);
            } catch (IOException e) {
                System.out.println("Recording failed: " + e.getMessage());
            }
        }

        List<Aircraft> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(Aircraft.fromJson(row));
        return result;
    }

    /** 数据源自己的时间戳。adsb.lol 给毫秒，本地 readsb 给秒。 */
    static long serverTimeMs(Map<String, Object> root) {
        if (root.get("now") instanceof Number n) {
            double v = n.doubleValue();
            return v < 1e11 ? Math.round(v * 1000) : Math.round(v);
        }
        return System.currentTimeMillis();
    }
}
