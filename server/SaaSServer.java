import com.sun.net.httpserver.HttpServer;

import common.Logger;
import admin.AdminController;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import auth.AuthController;
import staticpkg.StaticFileHandler;

public class SaaSServer {

    public static final File STATIC_ROOT;
    static {
        try {
            STATIC_ROOT = new File("..").getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;

        Logger.info("Starting SaaSServer...");
        Logger.info("Working dir   = " + System.getProperty("user.dir"));
        Logger.info("Static root   = " + STATIC_ROOT.getAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // ===== STATIC =====
        StaticFileHandler staticHandler = new StaticFileHandler(STATIC_ROOT);
        server.createContext("/projects", staticHandler.projectsRouter());
        server.createContext("/admin", staticHandler.adminRouter());
        server.createContext("/", staticHandler);

        // ===== USER AUTH (existing) =====
        AuthController auth = new AuthController();
        server.createContext("/api/register", auth.registerHandler());
        server.createContext("/api/login", auth.loginHandler());
        server.createContext("/api/debug/users", auth.debugUsersHandler());

        // ===== ADMIN (ALL REQUIREMENTS) =====
        AdminController admin = new AdminController();

        // admin auth
        server.createContext("/api/admin/login", admin.login());
        server.createContext("/api/admin/logout", admin.logout());

        // admin data
        server.createContext("/api/admin/system", admin.system(port));
        server.createContext("/api/admin/projects", admin.projects());
        server.createContext("/api/admin/health", admin.health(port));

        // tests
        server.createContext("/api/admin/tests", admin.tests());
        server.createContext("/api/admin/tests/run", admin.runTests(port));

        // users CRUD (admin only)
        server.createContext("/api/admin/users", admin.users());

        // live logs
        server.createContext("/api/admin/logs", admin.logs());
        server.createContext("/api/admin/logs/stream", admin.logsStream());

        // ===== CONCURRENCY =====
        server.setExecutor(Executors.newFixedThreadPool(30));

        Logger.info("Server running: http://localhost:" + port);
        server.start();
    }
}
