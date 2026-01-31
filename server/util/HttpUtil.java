package util;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpUtil {

    public static Map<String, String> parseFormData(String body) throws UnsupportedEncodingException {
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

    public static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ✅ ADD THIS (FIX)
    public static boolean isValidEmail(String email) {
        if (email == null) return false;
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }
}
