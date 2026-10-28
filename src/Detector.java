import java.util.Optional;

/** 一种"值得关注的事"的探测器：看一架飞机的历史轨迹，判断要不要提醒。 */
public interface Detector {

    /** key 用来去重（同一架飞机同一个 key 只提醒一次），message 是给人看的一句话。 */
    record Alert(String key, String message) {}

    Optional<Alert> check(Track track);
}
