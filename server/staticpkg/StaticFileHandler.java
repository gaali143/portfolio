package staticpkg;
import common.Logger;

import com.sun.net.httpserver.*;
import java.io.*;
import java.nio.file.Files;

public class StaticFileHandler implements HttpHandler {

    private final File root;

    public StaticFileHandler(File root) {
        this.root = root;
    }

    public HttpHandler projectsRouter() {
        return (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath(); // /projects/...
            String mapped = "/Projects" + path.substring("/projects".length());
            handleWithPathOverride(ex, mapped);
        };
    }

    // ✅ /admin -> /Projects/admin
    public HttpHandler adminRouter() {
        return (HttpExchange ex) -> {
            String reqPath = ex.getRequestURI().getPath(); // /admin or /admin/...

            // If user opens "/admin" (no trailing slash), redirect to "/admin/"
            if (reqPath.equals("/admin")) {
                ex.getResponseHeaders().add("Location", "/admin/");
                ex.sendResponseHeaders(302, -1);
                return;
            }

            // Map:
            // /admin/             -> /Projects/admin/
            // /admin/index.html   -> /Projects/admin/index.html
            // /admin/admin.css    -> /Projects/admin/admin.css
            String mapped = "/Projects/admin" + reqPath.substring("/admin".length());
            handleWithPathOverride(ex, mapped);
        };
    }

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

        // block directory traversal
        if (!file.getPath().startsWith(root.getPath())) {
            Logger.warn("STATIC traversal blocked: " + path);
            ex.sendResponseHeaders(403, -1);
            return;
        }

        // folder -> index.html
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
