package admin;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import common.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class AdminAuth {

    private static final Path ADMIN_JSON = Paths.get("admin.json");
    private static final Object LOCK = new Object();

    private static String adminUser = "admin";
    private static String adminPass = "admin123";

    private static final Map<String, Long> sessions = new HashMap<>();
    private static final long SESSION_TTL_MS = 1000L * 60L * 60L; // 1 hour

    public AdminAuth() {
        try { loadAdminJson(); }
        catch (Exception e) { Logger.warn("AdminAuth using defaults (admin.json missing or invalid)"); }
    }

    public void loadAdminJson() throws IOException {
        synchronized (LOCK) {
            if (!Files.exists(ADMIN_JSON)) {
                Files.writeString(ADMIN_JSON,
                        "{\n  \"username\": \"admin\",\n  \"password\": \"admin123\"\n}\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
            String json = Files.readString(ADMIN_JSON, StandardCharsets.UTF_8);
            adminUser = pick(json, "username", "admin");
            adminPass = pick(json, "password", "admin123");
        }
    }

    private String pick(String json, String key, String def) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return def;
        int c = json.indexOf(':', k);
        if (c < 0) return def;
        int q1 = json.indexOf('"', c);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return def;
        return json.substring(q1 + 1, q2).trim();
    }

    public boolean isValid(String username, String password) {
        return username != null && password != null
                && username.equals(adminUser)
                && password.equals(adminPass);
    }

    public void setSessionCookie(HttpExchange ex) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long exp = System.currentTimeMillis() + SESSION_TTL_MS;
        synchronized (LOCK) { sessions.put(token, exp); }
        ex.getResponseHeaders().add("Set-Cookie",
                "ADMIN_SESS=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    public void clearSessionCookie(HttpExchange ex) {
        String token = getCookie(ex, "ADMIN_SESS");
        if (token != null) {
            synchronized (LOCK) { sessions.remove(token); }
        }
        ex.getResponseHeaders().add("Set-Cookie",
                "ADMIN_SESS=; Path=/; Max-Age=0; SameSite=Lax");
    }

    public boolean requireAuth(HttpExchange ex) throws IOException {
        String token = getCookie(ex, "ADMIN_SESS");
        if (token == null) return unauthorized(ex);

        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Long exp = sessions.get(token);
            if (exp == null) return unauthorized(ex);
            if (exp < now) {
                sessions.remove(token);
                return unauthorized(ex);
            }
            // sliding expiration
            sessions.put(token, now + SESSION_TTL_MS);
        }
        return true;
    }

    private boolean unauthorized(HttpExchange ex) throws IOException {
        byte[] b = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(401, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
        return false;
    }

    private String getCookie(HttpExchange ex, String name) {
        List<String> cs = ex.getRequestHeaders().get("Cookie");
        if (cs == null) return null;
        for (String header : cs) {
            String[] parts = header.split(";");
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith(name + "=")) return t.substring((name + "=").length());
            }
        }
        return null;
    }

    public String uptime() {
        return Instant.now().toString();
    }
}
