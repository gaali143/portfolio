package common;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class LogStore {

    private static final int MAX_LINES = 2000;

    private static final Deque<String> lines = new ArrayDeque<>();
    private static final List<OutputStream> subscribers = new CopyOnWriteArrayList<>();

    public static void append(String line) {
        if (line == null) return;

        // store tail
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }

        // push to SSE subscribers
        String payload = "data: " + escapeForSse(line) + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        for (OutputStream os : subscribers) {
            try {
                os.write(bytes);
                os.flush();
            } catch (Exception e) {
                // dead client -> remove
                subscribers.remove(os);
                try { os.close(); } catch (Exception ignored) {}
            }
        }
    }

    // last N lines
    public static List<String> tail(int limit) {
        if (limit <= 0) limit = 1;
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            int skip = Math.max(0, lines.size() - limit);
            int i = 0;
            for (String s : lines) {
                if (i++ < skip) continue;
                out.add(s);
            }
        }
        return out;
    }

    public static void subscribe(OutputStream os) {
        if (os == null) return;
        subscribers.add(os);
    }

    public static void unsubscribe(OutputStream os) {
        if (os == null) return;
        subscribers.remove(os);
        try { os.close(); } catch (Exception ignored) {}
    }

    private static String escapeForSse(String s) {
        // SSE: avoid breaking lines. Replace CR/LF with visible markers.
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
