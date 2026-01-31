import com.sun.net.httpserver.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SaaSServer {

    // portfolio root is one level up from /server
    private static final File STATIC_ROOT;
    static {
        try { STATIC_ROOT = new File("..").getCanonicalFile(); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    // creds.json lives inside /server
    private static final Path CREDS_PATH = Paths.get("creds.json");
    private static final Object CREDS_LOCK = new Object();

    static class User {
        String userId, name, email, password, createdAt;
    }

    private static final List<User> users = new ArrayList<>();
    private static final AtomicInteger nextUserId = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        int port = 8080;

        Logger.info("Starting SaaSServer...");
        Logger.info("Working dir   = " + System.getProperty("user.dir"));
        Logger.info("Static root   = " + STATIC_ROOT.getAbsolutePath());
        Logger.info("Creds path    = " + CREDS_PATH.toAbsolutePath());

        loadCreds();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // API endpoints (single backend for entire site)
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/debug/users", new DebugUsersHandler());

        // Route /projects/... -> /Projects/... (case + nice URL)
        server.createContext("/projects", new ProjectsRouter());

        // Serve root site and everything else as static
        server.createContext("/", new StaticFileHandler(STATIC_ROOT));

        server.setExecutor(null);
        Logger.info("Server running: http://localhost:" + port);
        server.start();
    }

    // ========== Router: /projects -> /Projects ==========
    static class ProjectsRouter implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            // /projects/login-signup/ -> /Projects/login-signup/
            String path = ex.getRequestURI().getPath(); // starts with /projects
            String mapped = "/Projects" + path.substring("/projects".length());

            // serve mapped static
            new StaticFileHandler(STATIC_ROOT).handleWithPathOverride(ex, mapped);
        }
    }

    // ========== Static file serving + SaaS logs ==========
    static class StaticFileHandler implements HttpHandler {
        private final File root;
        StaticFileHandler(File root) { this.root = root; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            handleWithPathOverride(ex, null);
        }

        public void handleWithPathOverride(HttpExchange ex, String overridePath) throws IOException {
            String method = ex.getRequestMethod();
            String uri = ex.getRequestURI().toString();

            if (!method.equalsIgnoreCase("GET")) {
                Logger.warn("STATIC " + method + " " + uri + " -> 405");
                ex.sendResponseHeaders(405, -1);
                return;
            }

            String path = (overridePath != null) ? overridePath : ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

            File file = new File(root, path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath())) {
                Logger.warn("STATIC traversal blocked: " + path);
                ex.sendResponseHeaders(403, -1);
                return;
            }

            // If folder -> try index.html inside it
            if (file.exists() && file.isDirectory()) {
                File index = new File(file, "index.html");
                if (index.exists()) file = index;
            }

            if (!file.exists() || file.isDirectory()) {
                Logger.warn("STATIC 404: " + file.getAbsolutePath());
                ex.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            String mime = guessMimeType(file.getName());
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", mime + (mime.startsWith("text/") ? "; charset=utf-8" : ""));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }

            Logger.info("STATIC 200: " + path + " (" + bytes.length + " bytes)");
        }

        private String guessMimeType(String name) {
            name = name.toLowerCase();
            if (name.endsWith(".html")) return "text/html";
            if (name.endsWith(".css")) return "text/css";
            if (name.endsWith(".js")) return "application/javascript";
            if (name.endsWith(".png")) return "image/png";
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
            if (name.endsWith(".svg")) return "image/svg+xml";
            return "text/plain";
        }
    }

    // ========== API utilities ==========
    private static Map<String, String> parseFormData(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;

        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], "UTF-8");
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
            map.put(key, value);
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ========== creds.json (manual JSON) ==========
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

    private static void loadCreds() throws IOException {
        synchronized (CREDS_LOCK) {
            Logger.info("Loading creds: " + CREDS_PATH.toAbsolutePath());
            users.clear();

            if (!Files.exists(CREDS_PATH)) {
                Logger.warn("creds.json missing -> creating empty");
                saveCreds();
                nextUserId.set(1);
                return;
            }

            String json = Files.readString(CREDS_PATH, StandardCharsets.UTF_8).trim();
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

    private static void saveCreds() throws IOException {
        synchronized (CREDS_LOCK) {
            String out = convertToJSON(users);
            Path tmp = Paths.get("creds.json.tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, CREDS_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Logger.info("creds.json saved. users=" + users.size());
        }
    }

    private static User findByUserId(String userId) {
        for (User u : users) if (u.userId != null && u.userId.equalsIgnoreCase(userId)) return u;
        return null;
    }

    private static User findByEmail(String email) {
        for (User u : users) if (u.email != null && u.email.equalsIgnoreCase(email)) return u;
        return null;
    }

    // ========== API Handlers ==========
    static class RegisterHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Logger.info("API REGISTER: " + ex.getRequestMethod() + " " + ex.getRequestURI());
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }

            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("REGISTER body: " + body);

                Map<String,String> form = parseFormData(body);
                String name = form.getOrDefault("name","").trim();
                String email = form.getOrDefault("email","").trim();
                String pw = form.getOrDefault("password","");
                String cpw = form.getOrDefault("confirmPassword","");

                if (name.isEmpty()||email.isEmpty()||pw.isEmpty()||cpw.isEmpty()) {
                    sendJson(ex,400,"{\"error\":\"All fields are required\"}"); return;
                }
                if (!isValidEmail(email)) { sendJson(ex,400,"{\"error\":\"Invalid email\"}"); return; }
                if (pw.length()<6) { sendJson(ex,400,"{\"error\":\"Password must be at least 6 characters\"}"); return; }
                if (!pw.equals(cpw)) { sendJson(ex,400,"{\"error\":\"Passwords do not match\"}"); return; }

                synchronized (CREDS_LOCK) {
                    loadCreds();
                    if (findByEmail(email)!=null) { sendJson(ex,409,"{\"error\":\"Email already exists\"}"); return; }

                    User u = new User();
                    u.userId = "u" + nextUserId.getAndIncrement();
                    u.name = name;
                    u.email = email;
                    u.password = pw;
                    u.createdAt = Instant.now().toString();
                    users.add(u);
                    saveCreds();

                    String json = "{"
                            + "\"message\":\"User registered successfully\","
                            + "\"user\":{"
                            + "\"userId\":\""+jsonEscape(u.userId)+"\","
                            + "\"name\":\""+jsonEscape(u.name)+"\","
                            + "\"email\":\""+jsonEscape(u.email)+"\""
                            + "}}";
                    sendJson(ex,201,json);
                }
            } catch (Exception e) {
                Logger.error("REGISTER failed", e);
                sendJson(ex,500,"{\"error\":\"Server error\"}");
            }
        }
    }

    // Username is userId (field from JS is still "email")
    static class LoginHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            Logger.info("API LOGIN: " + ex.getRequestMethod() + " " + ex.getRequestURI());
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }

            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("LOGIN body: " + body);

                Map<String,String> form = parseFormData(body);
                String username = form.getOrDefault("email","").trim(); // userId
                String pw = form.getOrDefault("password","");

                if (username.isEmpty()||pw.isEmpty()) {
                    sendJson(ex,400,"{\"error\":\"Username and password are required\"}"); return;
                }

                synchronized (CREDS_LOCK) {
                    loadCreds();
                    User u = findByUserId(username);

                    if (u == null || !Objects.equals(u.password, pw)) {
                        sendJson(ex,401,"{\"error\":\"Invalid username or password\"}"); return;
                    }

                    String json = "{"
                            + "\"message\":\"Login successful\","
                            + "\"user\":{"
                            + "\"userId\":\""+jsonEscape(u.userId)+"\","
                            + "\"name\":\""+jsonEscape(u.name)+"\","
                            + "\"email\":\""+jsonEscape(u.email)+"\""
                            + "}}";
                    sendJson(ex,200,json);
                }
            } catch (Exception e) {
                Logger.error("LOGIN failed", e);
                sendJson(ex,500,"{\"error\":\"Server error\"}");
            }
        }
    }

    static class DebugUsersHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { ex.sendResponseHeaders(405,-1); return; }
            synchronized (CREDS_LOCK) {
                loadCreds();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"count\":").append(users.size()).append(",\"users\":[");
                for (int i=0;i<users.size();i++) {
                    User u = users.get(i);
                    sb.append("{\"userId\":\"").append(jsonEscape(u.userId))
                      .append("\",\"name\":\"").append(jsonEscape(u.name))
                      .append("\",\"email\":\"").append(jsonEscape(u.email)).append("\"}");
                    if (i<users.size()-1) sb.append(",");
                }
                sb.append("]}");
                sendJson(ex,200,sb.toString());
            }
        }
    }
}
