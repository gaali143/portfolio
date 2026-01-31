package auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import common.Logger;
import util.HttpUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AuthController {

    private final CredsRepository repo = new CredsRepository(Paths.get("creds.json"));

    public AuthController() {
        try { repo.load(); }
        catch (Exception e) { Logger.error("Failed to load creds on startup", e); }
    }
    public static boolean isValidEmail(String email) {
        if (email == null) return false;
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    // ===== /api/register =====
    public HttpHandler registerHandler() {
        return (HttpExchange ex) -> {
            Logger.info("API REGISTER: " + ex.getRequestMethod() + " " + ex.getRequestURI());
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }

            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("REGISTER body: " + body);

                Map<String,String> form = HttpUtil.parseFormData(body);
                String name = form.getOrDefault("name","").trim();
                String email = form.getOrDefault("email","").trim();
                String pw = form.getOrDefault("password","");
                String cpw = form.getOrDefault("confirmPassword","");

                if (name.isEmpty() || email.isEmpty() || pw.isEmpty() || cpw.isEmpty()) {
                    HttpUtil.sendJson(ex,400,"{\"error\":\"All fields are required\"}");
                    return;
                }
                if (!HttpUtil.isValidEmail(email)) {
                    HttpUtil.sendJson(ex,400,"{\"error\":\"Invalid email\"}");
                    return;
                }
                if (pw.length() < 6) {
                    HttpUtil.sendJson(ex,400,"{\"error\":\"Password must be at least 6 characters\"}");
                    return;
                }
                if (!pw.equals(cpw)) {
                    HttpUtil.sendJson(ex,400,"{\"error\":\"Passwords do not match\"}");
                    return;
                }

                synchronized (repo.lock()) {
                    repo.load();
                    if (repo.findByEmail(email) != null) {
                        HttpUtil.sendJson(ex,409,"{\"error\":\"Email already exists\"}");
                        return;
                    }

                    User u = new User();
                    u.userId = repo.newUserId();
                    u.name = name;
                    u.email = email;
                    u.password = pw;
                    u.createdAt = Instant.now().toString();

                    repo.getUsersUnsafe().add(u);
                    repo.save();

                    String json = "{"
                            + "\"message\":\"User registered successfully\","
                            + "\"user\":{"
                            + "\"userId\":\""+HttpUtil.jsonEscape(u.userId)+"\","
                            + "\"name\":\""+HttpUtil.jsonEscape(u.name)+"\","
                            + "\"email\":\""+HttpUtil.jsonEscape(u.email)+"\""
                            + "}}";

                    HttpUtil.sendJson(ex,201,json);
                }
            } catch (Exception e) {
                Logger.error("REGISTER failed", e);
                HttpUtil.sendJson(ex,500,"{\"error\":\"Server error\"}");
            }
        };
    }

    // ===== /api/login =====
    // IMPORTANT: Username is userId (your UI sends field "email" but you treat it as username)
    public HttpHandler loginHandler() {
        return (HttpExchange ex) -> {
            Logger.info("API LOGIN: " + ex.getRequestMethod() + " " + ex.getRequestURI());
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }

            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Logger.info("LOGIN body: " + body);

                Map<String,String> form = HttpUtil.parseFormData(body);
                String username = form.getOrDefault("email","").trim(); // userId
                String pw = form.getOrDefault("password","");

                if (username.isEmpty() || pw.isEmpty()) {
                    HttpUtil.sendJson(ex,400,"{\"error\":\"Username and password are required\"}");
                    return;
                }

                synchronized (repo.lock()) {
                    repo.load();
                    User u = repo.findByUserId(username);

                    if (u == null || !Objects.equals(u.password, pw)) {
                        HttpUtil.sendJson(ex,401,"{\"error\":\"Invalid username or password\"}");
                        return;
                    }

                    String json = "{"
                            + "\"message\":\"Login successful\","
                            + "\"user\":{"
                            + "\"userId\":\""+HttpUtil.jsonEscape(u.userId)+"\","
                            + "\"name\":\""+HttpUtil.jsonEscape(u.name)+"\","
                            + "\"email\":\""+HttpUtil.jsonEscape(u.email)+"\""
                            + "}}";

                    HttpUtil.sendJson(ex,200,json);
                }
            } catch (Exception e) {
                Logger.error("LOGIN failed", e);
                HttpUtil.sendJson(ex,500,"{\"error\":\"Server error\"}");
            }
        };
    }

    // ===== /api/debug/users =====
    public HttpHandler debugUsersHandler() {
        return (HttpExchange ex) -> {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { ex.sendResponseHeaders(405, -1); return; }

            try {
                synchronized (repo.lock()) {
                    repo.load();
                    List<User> users = repo.getUsersUnsafe();

                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"count\":").append(users.size()).append(",\"users\":[");
                    for (int i=0;i<users.size();i++) {
                        User u = users.get(i);
                        sb.append("{\"userId\":\"").append(HttpUtil.jsonEscape(u.userId))
                                .append("\",\"name\":\"").append(HttpUtil.jsonEscape(u.name))
                                .append("\",\"email\":\"").append(HttpUtil.jsonEscape(u.email))
                                .append("\"}");
                        if (i<users.size()-1) sb.append(",");
                    }
                    sb.append("]}");
                    HttpUtil.sendJson(ex,200,sb.toString());
                }
            } catch (Exception e) {
                Logger.error("DEBUG users failed", e);
                HttpUtil.sendJson(ex,500,"{\"error\":\"Server error\"}");
            }
        };
    }
}
