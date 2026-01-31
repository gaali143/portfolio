import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void info(String msg) {
        System.out.println(ts() + " [INFO] " + msg);
    }

    public static void warn(String msg) {
        System.out.println(ts() + " [WARN] " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.out.println(ts() + " [ERROR] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }

    public static String mask(String s) {
        if (s == null) return "null";
        if (s.length() <= 2) return "*".repeat(s.length());
        return s.charAt(0) + "*".repeat(s.length() - 2) + s.charAt(s.length() - 1);
    }

    private static String ts() {
        return LocalDateTime.now().format(FMT);
    }
}
