import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleServer {

    private static final Path CREDS_PATH = Paths.get("creds.json");
    private static final Object CREDS_LOCK = new Object();

    static class User {
        String userId;
        String name;
        String email;
        String password;   // demo only (plain text)
        String createdAt;
    }

    private static final List<User> users = new ArrayList<>();
    private static final AtomicInteger nextUserId = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        Logger.info("Starting server...");
        Logger.info("Working dir = " + System.getProperty("user.dir"));
        Logger.info("creds.json path = " + CREDS_PATH.toAbsolutePath());

        loadCreds();

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticFileHandler("."));
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/debug/users", new DebugUsersHandler());

        server.setExecutor(null);
        Logger.info("Server running at http://localhost:" + port);
        server.start();
    }

    // ===== utilities =====

    private static String newUserId() {
        return "u" + nextUserId.getAndIncrement();
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

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

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===== JSON (manual) =====

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

    private static String convertUserToJSON(User u) {
        return "{"
                + "\"userId\":\"" + jsonEscape(u.userId) + "\","
                + "\"name\":\"" + jsonEscape(u.name) + "\","
                + "\"email\":\"" + jsonEscape(u.email) + "\","
                + "\"password\":\"" + jsonEscape(u.password) + "\","
                + "\"createdAt\":\"" + jsonEscape(u.createdAt) + "\""
                + "}";
    }

    private static String convertToJSON(List<User> usersList) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"users\":[");
        for (int i = 0; i < usersList.size(); i++) {
            sb.append(convertUserToJSON(usersList.get(i)));
            if (i < usersList.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        if (s == null) return parts;

        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean esc = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                cur.append(c);
                esc = true;
                continue;
            }
            if (c == '"') {
                cur.append(c);
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes && c == sep) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }

            cur.append(c);
        }

        if (cur.length() > 0) parts.add(cur.toString().trim());
        return parts;
    }

    private static String stripQuotes(String v) {
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return jsonUnescape(v);
    }

    private static User parseUserObject(String obj) {
        User u = new User();
        String t = obj.trim();
        if (t.startsWith("{")) t = t.substring(1);
        if (t.endsWith("}")) t = t.substring(0, t.length() - 1);

        List<String> pairs = splitTopLevel(t, ',');
        for (String pair : pairs) {
            List<String> kv = splitTopLevel(pair, ':');
            if (kv.size() < 2) continue;

            String key = stripQuotes(kv.get(0));

            StringBuilder valBuilder = new StringBuilder();
            for (int i = 1; i < kv.size(); i++) {
                if (i > 1) valBuilder.append(":");
                valBuilder.append(kv.get(i));
            }
            String val = stripQuotes(valBuilder.toString());

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

    private static List<String> extractJsonObjectsFromArray(String arr) {
        List<String> objs = new ArrayList<>();
        int i = 0;
        boolean inQuotes = false;
        boolean esc = false;

        while (i < arr.length()) {
            char c = arr.charAt(i);

            if (esc) { esc = false; i++; continue; }
            if (c == '\\') { esc = true; i++; continue; }
            if (c == '"') { inQuotes = !inQuotes; i++; continue; }

            if (!inQuotes && c == '{') {
                int start = i;
                int depth = 0;
                boolean q = false;
                boolean e = false;

                while (i < arr.length()) {
                    char ch = arr.charAt(i);

                    if (e) { e = false; i++; continue; }
                    if (ch == '\\') { e = true; i++; continue; }
                    if (ch == '"') { q = !q; i++; continue; }

                    if (!q) {
                        if (ch == '{') depth++;
                        if (ch == '}') depth--;
                    }

                    i++;
                    if (depth == 0) {
                        objs.add(arr.substring(start, i).trim());
                        break;
                    }
                }
                continue;
            }

            i++;
        }
        return objs;
    }

    private static void loadCreds() throws IOException {
        synchronized (CREDS_LOCK) {
            Logger.info("Loading creds from: " + CREDS_PATH.toAbsolutePath());
            users.clear();

            if (!Files.exists(CREDS_PATH)) {
                Logger.warn("creds.json not found, creating empty one...");
                saveCreds();
                nextUserId.set(1);
                Logger.info("Loaded users count = 0");
                return;
            }

            String json = Files.readString(CREDS_PATH, StandardCharsets.UTF_8).trim();
            Logger.info("creds.json bytes=" + json.length());

            if (json.isEmpty()) {
                nextUserId.set(1);
                Logger.info("Loaded users count = 0 (empty file)");
                return;
            }

            int usersKey = json.indexOf("\"users\"");
            if (usersKey < 0) {
                nextUserId.set(1);
                Logger.warn("No \"users\" key found in creds.json");
                Logger.info("Loaded users count = 0");
                return;
            }

            int startArr = json.indexOf('[', usersKey);
            int endArr = json.lastIndexOf(']');
            if (startArr < 0 || endArr < 0 || endArr <= startArr) {
                nextUserId.set(1);
                Logger.warn("Could not locate users array brackets in creds.json");
                Logger.info("Loaded users count = 0");
                return;
            }

            String arr = json.substring(startArr + 1, endArr).trim();
            if (arr.isEmpty()) {
                nextUserId.set(1);
                Logger.info("Loaded users count = 0 (users array empty)");
                return;
            }

            List<String> objs = extractJsonObjectsFromArray(arr);
            Logger.info("Detected user objects = " + objs.size());

            for (String o : objs) {
                User u = parseUserObject(o);
                if (u.userId != null && !u.userId.isBlank()) users.add(u);
            }

            int max = 0;
            for (User u : users) {
                if (u.userId != null && u.userId.startsWith("u")) {
                    try { max = Math.max(max, Integer.parseInt(u.userId.substring(1))); }
                    catch (Exception ignored) {}
                }
            }
            nextUserId.set(max + 1);

            Logger.info("Loaded users count = " + users.size());
            for (int i = 0; i < Math.min(users.size(), 10); i++) {
                User u = users.get(i);
                Logger.info("User[" + i + "] userId=" + u.userId + " email=" + u.email + " name=" + u.name);
            }
        }
    }

    private static void saveCreds() throws IOException {
        synchronized (CREDS_LOCK) {
            String out = convertToJSON(users);
            Path tmp = Paths.get("creds.json.tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, CREDS_PATH,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Logger.info("Saved creds.json. users=" + users.size());
        }
    }

    private static User findByUserId(String userId) {
        if (userId == null) return null;
        for (User u : users) {
            if (u.userId != null && u.userId.equalsIgnoreCase(userId)) return u;
        }
        return null;
    }

    private static User findByEmail(String email) {
        if (email == null) return null;
        for (User u : users) {
            if (u.email != null && u.email.equalsIgnoreCase(email)) return u;
        }
        return null;
    }

    // ===== static file handler =====
    static class StaticFileHandler implements HttpHandler {
        private final File root;

        StaticFileHandler(String rootDir) throws IOException {
            this.root = new File(rootDir).getCanonicalFile();
            Logger.info("Static root = " + this.root.getAbsolutePath());
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

            File file = new File(root, path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath())) {
                Logger.warn("Blocked traversal attempt: " + path);
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            if (!file.exists() || file.isDirectory()) {
                Logger.warn("Static not found: " + file.getAbsolutePath());
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", guessMimeType(path) + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String guessMimeType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            return "text/plain";
        }
    }

    // ===== /api/register =====
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Logger.info("REGISTER request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("REGISTER raw body: " + body);

                Map<String, String> form = parseFormData(body);

                String name = form.getOrDefault("name", "").trim();
                String email = form.getOrDefault("email", "").trim();
                String password = form.getOrDefault("password", "");
                String confirmPassword = form.getOrDefault("confirmPassword", "");

                Logger.info("REGISTER parsed: name=" + name + ", email=" + email + ", pw=" + Logger.mask(password));

                if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                    sendJson(exchange, 400, "{\"error\":\"All fields are required\"}");
                    return;
                }
                if (!isValidEmail(email)) {
                    sendJson(exchange, 400, "{\"error\":\"Invalid email\"}");
                    return;
                }
                if (password.length() < 6) {
                    sendJson(exchange, 400, "{\"error\":\"Password must be at least 6 characters\"}");
                    return;
                }
                if (!password.equals(confirmPassword)) {
                    sendJson(exchange, 400, "{\"error\":\"Passwords do not match\"}");
                    return;
                }

                synchronized (CREDS_LOCK) {
                    loadCreds();

                    if (findByEmail(email) != null) {
                        sendJson(exchange, 409, "{\"error\":\"Email already exists\"}");
                        return;
                    }

                    User u = new User();
                    u.userId = newUserId();
                    u.name = name;
                    u.email = email;
                    u.password = password;
                    u.createdAt = Instant.now().toString();

                    Logger.info("REGISTER adding userId=" + u.userId + " email=" + u.email);

                    users.add(u);
                    saveCreds();

                    String json = "{"
                            + "\"message\":\"User registered successfully\","
                            + "\"user\":{"
                            + "\"userId\":\"" + jsonEscape(u.userId) + "\","
                            + "\"name\":\"" + jsonEscape(u.name) + "\","
                            + "\"email\":\"" + jsonEscape(u.email) + "\""
                            + "}"
                            + "}";
                    sendJson(exchange, 201, json);
                }

            } catch (Exception ex) {
                Logger.error("REGISTER failed", ex);
                sendJson(exchange, 500, "{\"error\":\"Server error\"}");
            }
        }
    }

    // ===== /api/login =====
    // IMPORTANT: Treats incoming "email" field as USERNAME (userId)
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Logger.info("LOGIN request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("LOGIN raw body: " + body);

                Map<String, String> form = parseFormData(body);

                // client sends field "email" but UI means "Username"
                String username = form.getOrDefault("email", "").trim();
                String password = form.getOrDefault("password", "");

                Logger.info("LOGIN parsed: username(userId)=" + username + ", pw=" + Logger.mask(password));

                if (username.isEmpty() || password.isEmpty()) {
                    sendJson(exchange, 400, "{\"error\":\"Username and password are required\"}");
                    return;
                }

                synchronized (CREDS_LOCK) {
                    loadCreds();
                    Logger.info("LOGIN users in memory = " + users.size());

                    User u = findByUserId(username);
                    Logger.info("LOGIN user found by userId? " + (u != null));

                    if (u != null) {
                        Logger.info("LOGIN stored pw=" + Logger.mask(u.password) + " entered pw=" + Logger.mask(password));
                    }

                    if (u == null || !Objects.equals(u.password, password)) {
                        sendJson(exchange, 401, "{\"error\":\"Invalid username or password\"}");
                        return;
                    }

                    String json = "{"
                            + "\"message\":\"Login successful\","
                            + "\"user\":{"
                            + "\"userId\":\"" + jsonEscape(u.userId) + "\","
                            + "\"name\":\"" + jsonEscape(u.name) + "\","
                            + "\"email\":\"" + jsonEscape(u.email) + "\""
                            + "}"
                            + "}";
                    sendJson(exchange, 200, json);
                }

            } catch (Exception ex) {
                Logger.error("LOGIN failed", ex);
                sendJson(exchange, 500, "{\"error\":\"Server error\"}");
            }
        }
    }

    // ===== /api/debug/users =====
    static class DebugUsersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                synchronized (CREDS_LOCK) {
                    loadCreds();
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"count\":").append(users.size()).append(",\"users\":[");
                    for (int i = 0; i < users.size(); i++) {
                        User u = users.get(i);
                        sb.append("{")
                                .append("\"userId\":\"").append(jsonEscape(u.userId)).append("\",")
                                .append("\"name\":\"").append(jsonEscape(u.name)).append("\",")
                                .append("\"email\":\"").append(jsonEscape(u.email)).append("\"")
                                .append("}");
                        if (i < users.size() - 1) sb.append(",");
                    }
                    sb.append("]}");
                    sendJson(exchange, 200, sb.toString());
                }
            } catch (Exception ex) {
                Logger.error("DEBUG users failed", ex);
                sendJson(exchange, 500, "{\"error\":\"Server error\"}");
            }
        }
    }
}
