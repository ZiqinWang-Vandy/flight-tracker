import java.util.List;

/** 数据源接口：现在用公共 API，以后可以换成自己的 SDR 接收站。 */
public interface AircraftSource {
    List<Aircraft> fetch() throws Exception;
}
