package admin;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import common.Logger;
import common.LogStore;
import auth.CredsRepository;
import auth.User;
import util.HttpUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

public class AdminController {

    private final AdminAuth adminAuth = new AdminAuth();
    private final CredsRepository creds = new CredsRepository(Paths.get("creds.json"));

    // path config: if missing -> "No path set"
    private final Map<String, String> projectPath = new HashMap<>();

    // in-memory tests results
    private final List<Map<String,String>> testResults = new ArrayList<>();

    public AdminController() {
        try { creds.load(); }
        catch (Exception e) { Logger.error("AdminController failed to load creds", e); }

        // You can edit these anytime
        projectPath.put("login-signup", "/projects/login-signup/");
        projectPath.put("timetool", "/projects/timetool/");
        projectPath.put("crud-crm", "/projects/crud-crm/");
        projectPath.put("games", "/projects/games/");
        projectPath.put("3d-designs", "/projects/3d-designs/");
    }

    // ===== Admin Login =====
    public HttpHandler login() {
        return (HttpExchange ex) -> {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,String> form = HttpUtil.parseFormData(body);

            String u = form.getOrDefault("username","").trim();
            String p = form.getOrDefault("password","");

            if (!adminAuth.isValid(u,p)) {
                HttpUtil.sendJson(ex,401,"{\"error\":\"Invalid admin credentials\"}");
                return;
            }
            adminAuth.setSessionCookie(ex);
            HttpUtil.sendJson(ex,200,"{\"message\":\"ok\"}");
        };
    }

    public HttpHandler logout() {
        return (HttpExchange ex) -> {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }
            adminAuth.clearSessionCookie(ex);
            HttpUtil.sendJson(ex,200,"{\"message\":\"ok\"}");
        };
    }

    // ===== System info =====
    public HttpHandler system(int port) {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;
            String json = "{"
                    + "\"host\":\"localhost\","
                    + "\"port\":\"" + port + "\","
                    + "\"uptime\":\"" + HttpUtil.jsonEscape(Instant.now().toString()) + "\""
                    + "}";
            HttpUtil.sendJson(ex,200,json);
        };
    }

    // ===== Projects inventory =====
    public HttpHandler projects() {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;

            java.io.File root = new java.io.File("../Projects");
            java.io.File[] dirs = root.listFiles();
            if (dirs == null) { HttpUtil.sendJson(ex,200,"[]"); return; }

            List<String> out = new ArrayList<>();

            for (java.io.File f : dirs) {
                if (!f.isDirectory()) continue;
                String name = f.getName();
                if ("admin".equalsIgnoreCase(name)) continue;

                boolean ui = new java.io.File(f, "index.html").exists();
                String path = projectPath.get(name);

                String status, message;
                if (!ui && path == null) {
                    status = "INACTIVE";
                    message = "No UI and no path configured";
                } else if (path == null) {
                    status = "MISCONFIGURED";
                    message = "No path set";
                } else {
                    status = "ACTIVE";
                    message = ui ? "OK" : "Path set (no UI)";
                }

                out.add("{"
                        + "\"name\":\"" + HttpUtil.jsonEscape(name) + "\","
                        + "\"path\":" + (path == null ? "null" : "\"" + HttpUtil.jsonEscape(path) + "\"") + ","
                        + "\"uiPresent\":" + (ui ? "true" : "false") + ","
                        + "\"status\":\"" + status + "\","
                        + "\"message\":\"" + HttpUtil.jsonEscape(message) + "\""
                        + "}");
            }

            HttpUtil.sendJson(ex,200,"[" + String.join(",", out) + "]");
        };
    }

    // ===== Health check =====
    public HttpHandler health(int port) {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;

            List<Map<String,Object>> rows = computeHealth(port);
            List<String> out = new ArrayList<>();

            for (Map<String,Object> r : rows) {
                out.add("{"
                        + "\"name\":\"" + HttpUtil.jsonEscape((String) r.get("name")) + "\","
                        + "\"path\":" + (r.get("path")==null ? "null" : "\"" + HttpUtil.jsonEscape((String)r.get("path")) + "\"") + ","
                        + "\"state\":\"" + HttpUtil.jsonEscape((String)r.get("state")) + "\","
                        + "\"http\":" + (r.get("http")==null ? "null" : r.get("http")) + ","
                        + "\"ms\":" + (r.get("ms")==null ? "null" : r.get("ms")) + ","
                        + "\"message\":\"" + HttpUtil.jsonEscape((String)r.get("message")) + "\""
                        + "}");
            }

            HttpUtil.sendJson(ex,200,"[" + String.join(",", out) + "]");
        };
    }

    private List<Map<String,Object>> computeHealth(int port) {
        java.io.File root = new java.io.File("../Projects");
        java.io.File[] dirs = root.listFiles();
        List<Map<String,Object>> rows = new ArrayList<>();
        if (dirs == null) return rows;

        for (java.io.File f : dirs) {
            if (!f.isDirectory()) continue;
            String name = f.getName();
            if ("admin".equalsIgnoreCase(name)) continue;

            boolean ui = new java.io.File(f, "index.html").exists();
            String path = projectPath.get(name);

            Map<String,Object> r = new HashMap<>();
            r.put("name", name);
            r.put("path", path);

            if (!ui && path == null) {
                r.put("state", "INACTIVE");
                r.put("message", "No UI and no path configured");
                rows.add(r);
                continue;
            }
            if (path == null) {
                r.put("state", "MISCONFIGURED");
                r.put("message", "No path set");
                rows.add(r);
                continue;
            }

            long t0 = System.currentTimeMillis();
            try {
                URL url = new URL("http://localhost:" + port + path);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(1200);
                c.setReadTimeout(1800);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                long ms = System.currentTimeMillis() - t0;

                r.put("http", code);
                r.put("ms", ms);

                if (code >= 200 && code < 300) {
                    r.put("state", "UP");
                    r.put("message", "OK");
                } else {
                    r.put("state", "DOWN");
                    r.put("message", "HTTP " + code);
                }
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - t0;
                r.put("http", null);
                r.put("ms", ms);
                r.put("state", "DOWN");
                r.put("message", "Timeout/Exception");
            }

            rows.add(r);
        }

        return rows;
    }

    // ===== Tests =====
    public HttpHandler tests() {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;
            List<String> out = new ArrayList<>();
            for (Map<String,String> t : testResults) {
                out.add("{"
                        + "\"project\":\"" + HttpUtil.jsonEscape(t.get("project")) + "\","
                        + "\"test\":\"" + HttpUtil.jsonEscape(t.get("test")) + "\","
                        + "\"result\":\"" + HttpUtil.jsonEscape(t.get("result")) + "\","
                        + "\"message\":\"" + HttpUtil.jsonEscape(t.get("message")) + "\","
                        + "\"time\":\"" + HttpUtil.jsonEscape(t.get("time")) + "\""
                        + "}");
            }
            HttpUtil.sendJson(ex,200,"[" + String.join(",", out) + "]");
        };
    }

    public HttpHandler runTests(int port) {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { ex.sendResponseHeaders(405, -1); return; }

            testResults.clear();

            java.io.File root = new java.io.File("../Projects");
            java.io.File[] dirs = root.listFiles();
            if (dirs == null) { HttpUtil.sendJson(ex,200,"{\"message\":\"ok\"}"); return; }

            Map<String, Map<String,Object>> health = new HashMap<>();
            for (Map<String,Object> h : computeHealth(port)) {
                Map<String,Object> m = new HashMap<>(h);
                health.put((String)h.get("name"), m);
            }

            for (java.io.File f : dirs) {
                if (!f.isDirectory()) continue;
                String name = f.getName();
                if ("admin".equalsIgnoreCase(name)) continue;

                boolean ui = new java.io.File(f, "index.html").exists();
                String path = projectPath.get(name);

                addTest(name, "Folder exists", "UP", "OK");
                addTest(name, "UI index.html", ui ? "UP" : "DOWN", ui ? "OK" : "Missing index.html");
                addTest(name, "Path configured", (path != null) ? "UP" : "MISCONFIGURED", (path != null) ? path : "No path set");

                Map<String,Object> h = health.get(name);
                if (h != null) {
                    addTest(name, "Health check", (String)h.get("state"), (String)h.get("message"));
                }
            }

            HttpUtil.sendJson(ex,200,"{\"message\":\"ok\"}");
        };
    }

    private void addTest(String project, String test, String result, String msg) {
        Map<String,String> m = new HashMap<>();
        m.put("project", project);
        m.put("test", test);
        m.put("result", result);
        m.put("message", msg);
        m.put("time", Instant.now().toString());
        testResults.add(m);
    }

    // ===== Users CRUD =====
    public HttpHandler users() {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;

            String method = ex.getRequestMethod().toUpperCase();

            if (method.equals("GET")) {
                creds.load();
                List<User> list = creds.getUsersUnsafe();
                List<String> out = new ArrayList<>();
                for (User u : list) {
                    out.add("{"
                            + "\"userId\":\"" + HttpUtil.jsonEscape(u.userId) + "\","
                            + "\"name\":\"" + HttpUtil.jsonEscape(u.name) + "\","
                            + "\"email\":\"" + HttpUtil.jsonEscape(u.email) + "\","
                            + "\"createdAt\":\"" + HttpUtil.jsonEscape(u.createdAt) + "\""
                            + "}");
                }
                HttpUtil.sendJson(ex,200,"[" + String.join(",", out) + "]");
                return;
            }

            if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String,String> form = HttpUtil.parseFormData(body);

                synchronized (creds.lock()) {
                    creds.load();
                    List<User> list = creds.getUsersUnsafe();

                    if (method.equals("POST")) {
                        String name = form.getOrDefault("name","").trim();
                        String email = form.getOrDefault("email","").trim();
                        String password = form.getOrDefault("password","");

                        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                            HttpUtil.sendJson(ex,400,"{\"error\":\"name,email,password required\"}");
                            return;
                        }

                        User u = new User();
                        u.userId = creds.newUserId();
                        u.name = name;
                        u.email = email;
                        u.password = password;
                        u.createdAt = Instant.now().toString();

                        list.add(u);
                        creds.save();
                        HttpUtil.sendJson(ex,201,"{\"message\":\"created\"}");
                        return;
                    }

                    if (method.equals("PUT")) {
                        String userId = form.getOrDefault("userId","").trim();
                        String name = form.getOrDefault("name","").trim();
                        String email = form.getOrDefault("email","").trim();

                        User u = null;
                        for (User x : list) {
                            if (x.userId != null && x.userId.equalsIgnoreCase(userId)) { u = x; break; }
                        }
                        if (u == null) { HttpUtil.sendJson(ex,404,"{\"error\":\"user not found\"}"); return; }

                        if (!name.isEmpty()) u.name = name;
                        if (!email.isEmpty()) u.email = email;

                        creds.save();
                        HttpUtil.sendJson(ex,200,"{\"message\":\"updated\"}");
                        return;
                    }

                    // DELETE
                    String userId = form.getOrDefault("userId","").trim();
                    boolean removed = list.removeIf(x -> x.userId != null && x.userId.equalsIgnoreCase(userId));
                    if (!removed) { HttpUtil.sendJson(ex,404,"{\"error\":\"user not found\"}"); return; }

                    creds.save();
                    HttpUtil.sendJson(ex,200,"{\"message\":\"deleted\"}");
                    return;
                }
            }

            ex.sendResponseHeaders(405, -1);
        };
    }

    // ===== Logs =====
    public HttpHandler logs() {
        return (HttpExchange ex) -> {
            if (!adminAuth.requireAuth(ex)) return;

            String q = ex.getRequestURI().getQuery();
            int limit = 200;
            if (q != null && q.contains("limit=")) {
                try { limit = Integer.parseInt(q.substring(q.indexOf("limit=") + 6)); } catch (Exception ignored) {}
            }

            List<String> tail = LogStore.tail(limit);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"lines\":[");
            for (int i=0;i<tail.size();i++) {
                sb.append("\"").append(HttpUtil.jsonEscape(tail.get(i))).append("\"");
                if (i<tail.size()-1) sb.append(",");
            }
            sb.append("]}");
            HttpUtil.sendJson(ex,200,sb.toString());
        };
    }

    public HttpHandler logsStream() {
    return (HttpExchange ex) -> {
        if (!adminAuth.requireAuth(ex)) return;

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        var os = ex.getResponseBody();
        LogStore.subscribe(os);

        try {
            // Send initial hint so browser shows connection active
            os.write(("data: [CONNECTED]\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            // Keep the connection alive
            while (true) {
                Thread.sleep(15000);
                os.write(("data: [PING] " + Instant.now() + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception ignored) {
        } finally {
            LogStore.unsubscribe(os);
        }
    };
}

}
