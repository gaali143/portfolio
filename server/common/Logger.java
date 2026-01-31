package common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void info(String msg) { log("INFO", msg, null); }
    public static void warn(String msg) { log("WARN", msg, null); }
    public static void error(String msg, Throwable t) { log("ERROR", msg, t); }

    public static String mask(String s) {
        if (s == null) return "";
        if (s.length() <= 2) return "**";
        return s.charAt(0) + "****" + s.charAt(s.length()-1);
    }

    private static void log(String level, String msg, Throwable t) {
        String line = LocalDateTime.now().format(FMT) + " [" + level + "] " + (msg == null ? "" : msg);
        System.out.println(line);

        // ✅ This is what makes LIVE LOGS work
        LogStore.append(line);

        if (t != null) {
            String tline = LocalDateTime.now().format(FMT) + " [" + level + "] " + t.toString();
            System.out.println(tline);
            LogStore.append(tline);
        }
    }
}
