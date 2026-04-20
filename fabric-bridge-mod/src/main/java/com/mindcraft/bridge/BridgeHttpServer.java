package com.mindcraft.bridge;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server (JDK built-in, no extra deps) that routes the three
 * endpoints the Node.js bridge agent needs:
 *
 *   GET  /ping    → 200 OK  {"ok":true}
 *   GET  /state   → 200 OK  &lt;player state JSON&gt;
 *   POST /command → 200 OK  {"success":true,"output":"..."}
 */
public class BridgeHttpServer {

    private final HttpServer server;

    public BridgeHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", port), 16);

        server.createContext("/ping",    this::handlePing);
        server.createContext("/state",   this::handleState);
        server.createContext("/command", this::handleCommand);
        server.createContext("/action", this::handleAction);
        server.createContext("/capabilities", this::handleCapabilities);

        // Single-threaded executor is fine — Minecraft main-thread work is
        // scheduled via MinecraftClient.execute() inside the handlers.
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mindcraft-bridge-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Handlers
    // ──────────────────────────────────────────────────────────────────────────

    private void handlePing(HttpExchange ex) throws IOException {
        respond(ex, 200, "{\"ok\":true}");
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        Long since = extractQueryLong(query, "since");
        String json = StateCollector.collect(since);
        respond(ex, 200, json);
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Parse {"command":"..."} manually — no Gson needed, keep it simple.
            String command = extractJsonString(body, "command");
            if (command == null || command.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Missing 'command' field\"}");
                return;
            }
            CommandExecutor.execute(command);
            respond(ex, 200, "{\"success\":true,\"output\":\"Command sent: " + jsonEscape(command) + "\"}");
        }
    }

    private void handleAction(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String actionJson = extractJsonObject(body, "action");
            if (actionJson == null) {
                actionJson = body != null ? body.trim() : null;
            }
            if (actionJson == null || actionJson.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Missing 'action' field\"}");
                return;
            }
            String executed = CommandExecutor.executeTypedJson(actionJson);
            if (executed == null || executed.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Invalid typed action\"}");
                return;
            }
            respond(ex, 200, "{\"success\":true,\"output\":\"Action sent: " + jsonEscape(executed) + "\"}");
        }
    }

    private void handleCapabilities(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, CommandExecutor.capabilitiesJson());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Tiny JSON string extractor — avoids pulling in a JSON library.
     * Finds the first occurrence of {@code "key":"value"} in the input.
     */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"')  { break; }
            end++;
        }
        return json.substring(start + 1, end)
                   .replace("\\n",  "\n")
                   .replace("\\t",  "\t")
                   .replace("\\\\", "\\")
                   .replace("\\\"", "\"");
    }

    static Long extractQueryLong(String query, String key) {
        if (query == null || query.isBlank()) return null;
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                try {
                    String decoded = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    return Long.parseLong(decoded);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static String extractJsonObject(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('{', colon + 1);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    static String extractJsonPrimitive(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            return extractJsonString(json, key);
        }
        int end = i;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            end++;
        }
        if (end <= i) return null;
        return json.substring(i, end);
    }

    /** Escape a string for embedding inside a JSON string value. */
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
