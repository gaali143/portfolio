package auth;
import common.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CredsRepository {

    private final Path credsPath;
    private final Object lock = new Object();

    private final List<User> users = new ArrayList<>();
    private final AtomicInteger nextUserId = new AtomicInteger(1);

    public CredsRepository(Path credsPath) {
        this.credsPath = credsPath;
    }

    public Object lock() { return lock; }

    public List<User> getUsersUnsafe() { return users; } // only use inside synchronized(lock)

    public String newUserId() {
        return "u" + nextUserId.getAndIncrement();
    }

    public void load() throws IOException {
        synchronized (lock) {
            Logger.info("Loading creds: " + credsPath.toAbsolutePath());
            users.clear();

            if (!Files.exists(credsPath)) {
                Logger.warn("creds.json missing -> creating empty");
                save();
                nextUserId.set(1);
                return;
            }

            String json = Files.readString(credsPath, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) { nextUserId.set(1); return; }

            int usersKey = json.indexOf("\"users\"");
            int startArr = json.indexOf('[', usersKey);
            int endArr = json.lastIndexOf(']');
            if (usersKey < 0 || startArr < 0 || endArr <= startArr) { nextUserId.set(1); return; }

            String arr = json.substring(startArr + 1, endArr).trim();
            if (!arr.isEmpty()) {
                for (String o : extractJsonObjectsFromArray(arr)) {
                    User u = parseUserObject(o);
                    if (u.userId != null && !u.userId.isBlank()) users.add(u);
                }
            }

            int max = 0;
            for (User u : users) {
                if (u.userId != null && u.userId.startsWith("u")) {
                    try { max = Math.max(max, Integer.parseInt(u.userId.substring(1))); } catch (Exception ignored) {}
                }
            }
            nextUserId.set(max + 1);

            Logger.info("Users loaded = " + users.size());
        }
    }

    public void save() throws IOException {
        synchronized (lock) {
            String out = convertToJSON(users);
            Path tmp = Paths.get(credsPath.toString() + ".tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, credsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Logger.info("creds.json saved. users=" + users.size());
        }
    }

    public User findByUserId(String userId) {
        if (userId == null) return null;
        for (User u : users) {
            if (u.userId != null && u.userId.equalsIgnoreCase(userId)) return u;
        }
        return null;
    }

    public User findByEmail(String email) {
        if (email == null) return null;
        for (User u : users) {
            if (u.email != null && u.email.equalsIgnoreCase(email)) return u;
        }
        return null;
    }

    // ===== minimal JSON parsing/writing (same as before) =====

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String jsonUnescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!esc) {
                if (c == '\\') esc = true;
                else out.append(c);
            } else {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    case '"' -> out.append('"');
                    default -> out.append(c);
                }
                esc = false;
            }
        }
        return out.toString();
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { cur.append(c); esc = false; continue; }
            if (c == '\\') { cur.append(c); esc = true; continue; }
            if (c == '"') { cur.append(c); inQuotes = !inQuotes; continue; }
            if (!inQuotes && c == sep) { parts.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) parts.add(cur.toString().trim());
        return parts;
    }

    private static String stripQuotes(String v) {
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
            v = v.substring(1, v.length() - 1);
        return jsonUnescape(v);
    }

    private static List<String> extractJsonObjectsFromArray(String arr) {
        List<String> objs = new ArrayList<>();
        int i = 0;
        boolean inQuotes = false, esc = false;

        while (i < arr.length()) {
            char c = arr.charAt(i);
            if (esc) { esc = false; i++; continue; }
            if (c == '\\') { esc = true; i++; continue; }
            if (c == '"') { inQuotes = !inQuotes; i++; continue; }

            if (!inQuotes && c == '{') {
                int start = i;
                int depth = 0;
                boolean q = false, e = false;
                while (i < arr.length()) {
                    char ch = arr.charAt(i);
                    if (e) { e = false; i++; continue; }
                    if (ch == '\\') { e = true; i++; continue; }
                    if (ch == '"') { q = !q; i++; continue; }
                    if (!q) { if (ch == '{') depth++; if (ch == '}') depth--; }
                    i++;
                    if (depth == 0) { objs.add(arr.substring(start, i).trim()); break; }
                }
                continue;
            }
            i++;
        }
        return objs;
    }

    private static User parseUserObject(String obj) {
        User u = new User();
        String t = obj.trim();
        if (t.startsWith("{")) t = t.substring(1);
        if (t.endsWith("}")) t = t.substring(0, t.length() - 1);

        for (String pair : splitTopLevel(t, ',')) {
            List<String> kv = splitTopLevel(pair, ':');
            if (kv.size() < 2) continue;

            String key = stripQuotes(kv.get(0));
            StringBuilder vb = new StringBuilder();
            for (int i = 1; i < kv.size(); i++) {
                if (i > 1) vb.append(":");
                vb.append(kv.get(i));
            }
            String val = stripQuotes(vb.toString());

            switch (key) {
                case "userId" -> u.userId = val;
                case "name" -> u.name = val;
                case "email" -> u.email = val;
                case "password" -> u.password = val;
                case "createdAt" -> u.createdAt = val;
            }
        }
        return u;
    }

    private static String convertUserToJSON(User u) {
        return "{"
                + "\"userId\":\"" + jsonEscape(u.userId) + "\","
                + "\"name\":\"" + jsonEscape(u.name) + "\","
                + "\"email\":\"" + jsonEscape(u.email) + "\","
                + "\"password\":\"" + jsonEscape(u.password) + "\","
                + "\"createdAt\":\"" + jsonEscape(u.createdAt) + "\""
                + "}";
    }

    private static String convertToJSON(List<User> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"users\":[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(convertUserToJSON(list.get(i)));
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }
}
