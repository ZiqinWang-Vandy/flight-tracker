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

    public HttpJsonSource(String url) {
        this.url = url;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Aircraft> fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "student-flight-tracker/0.2")
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());

        Map<String, Object> root = (Map<String, Object>) new Json(resp.body()).parse();
        // adsb.lol 用 "ac"，本地 dump1090/readsb 用 "aircraft"
        Object list = root.containsKey("ac") ? root.get("ac") : root.get("aircraft");
        List<Aircraft> result = new ArrayList<>();
        for (Object o : (List<Object>) list) result.add(Aircraft.fromJson((Map<String, Object>) o));
        return result;
    }
}
