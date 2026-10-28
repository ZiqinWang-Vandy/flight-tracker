import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个极简 JSON 解析器（为了不依赖第三方库）。
 * 以后改用 Maven/Gradle 时可以换成 Jackson 或 Gson。
 */
public class Json {
    private final String s;
    private int i = 0;

    public Json(String s) {
        this.s = s;
    }

    public Object parse() {
        skipWs();
        char c = s.charAt(i);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> m = new HashMap<>();
        i++; // {
        skipWs();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            i++; // :
            m.put(key, parse());
            skipWs();
            if (s.charAt(i++) == '}') return m; // 否则是 ','
        }
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        i++; // [
        skipWs();
        if (s.charAt(i) == ']') { i++; return list; }
        while (true) {
            list.add(parse());
            skipWs();
            if (s.charAt(i++) == ']') return list; // 否则是 ','
        }
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        i++; // 开头的 "
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e); // \" \\ \/
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Number parseNumber() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    // ---------------------------------------------------------------------
    // 反过来：把 Map / List / String / Number / Boolean / null 写成 JSON 字符串
    // ---------------------------------------------------------------------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String str) {
            writeString(str, sb);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof List<?> list) {
            sb.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) sb.append(',');
                write(list.get(k), sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Can't write " + o.getClass() + " as JSON");
        }
    }

    private static void writeString(String str, StringBuilder sb) {
        sb.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
