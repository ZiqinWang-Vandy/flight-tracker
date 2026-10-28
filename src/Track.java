import java.util.*;

/** 一架飞机最近一段时间的历史状态。探测器靠它看"变化"，而不只是当前位置。 */
public class Track {
    static final long KEEP_MS = 15 * 60_000; // 保留最近 15 分钟

    record Sample(long timeMs, Aircraft aircraft) {}

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Set<String> alerted = new HashSet<>();

    void add(long timeMs, Aircraft a) {
        samples.addLast(new Sample(timeMs, a));
        while (timeMs - samples.peekFirst().timeMs() > KEEP_MS) samples.removeFirst();
    }

    Aircraft latest() {
        return samples.peekLast().aircraft();
    }

    long latestTime() {
        return samples.peekLast().timeMs();
    }

    /** 最近 windowMs 毫秒内的样本，按时间从早到晚。 */
    List<Sample> since(long windowMs) {
        long cutoff = latestTime() - windowMs;
        return samples.stream().filter(s -> s.timeMs() >= cutoff).toList();
    }

    /** 同一架飞机同一种提醒只发一次（飞机离开后再回来会重新提醒）。第一次调用返回 true。 */
    boolean markAlerted(String key) {
        return alerted.add(key);
    }
}
