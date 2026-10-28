import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 读取项目根目录下的 local.properties。
 * 这个文件不上传到 GitHub（见 .gitignore），每台电脑可以有自己的设置，比如数据存在哪个盘。
 */
public class LocalConfig {
    private static final Properties PROPS = load();

    static String get(String key, String defaultValue) {
        return PROPS.getProperty(key, defaultValue);
    }

    private static Properties load() {
        Properties p = new Properties();
        // IntelliJ 里工作目录是项目根目录；在 src 里用命令行运行时是 src
        for (String candidate : List.of("local.properties", "../local.properties")) {
            Path file = Path.of(candidate);
            if (!Files.isRegularFile(file)) continue;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.out.println("Couldn't read " + file.toAbsolutePath() + ": " + e.getMessage());
            }
            break;
        }
        return p;
    }
}
