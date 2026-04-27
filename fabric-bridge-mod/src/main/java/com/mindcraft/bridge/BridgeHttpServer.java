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
        server.createContext("/batch", this::handleBatch);
        server.createContext("/capabilities", this::handleCapabilities);
        server.createContext("/commands", this::handleCommands);

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
        boolean includeSurface = extractQueryBoolean(query, "surface");
        Integer surfaceRadius = extractQueryInt(query, "surface_radius");
        String json = StateCollector.collect(since, includeSurface, surfaceRadius != null ? surfaceRadius : 8);
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
            // Chat and message commands execute immediately — don't queue them.
            if (command.startsWith("chat:") || command.startsWith("whisper:")) {
                CommandExecutor.execute(command);
                respond(ex, 200, "{\"success\":true,\"output\":\"Command sent: " + jsonEscape(command) + "\"}");
                return;
            }
            // Route Baritone/action commands through TaskQueue for sequential execution.
            // When the queue is disabled, enqueue() falls back to direct execution.
            int queued = TaskQueue.getInstance().enqueue(command);
            respond(ex, 200, "{\"success\":true,\"queued\":" + queued + ",\"output\":\"Command sent: " + jsonEscape(command) + "\"}");
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

            // Check type before executing — craft actions are self-executing
            String actionType = extractJsonString(actionJson, "type");
            boolean isSelfExecuting = "craft".equals(actionType);

            String executed = CommandExecutor.executeTypedJson(actionJson);
            if (executed == null || executed.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Invalid typed action\"}");
                return;
            }

            // Craft actions are self-executing: executeCraftAction() directly
            // sends #task interact and registers a post-Baritone callback.
            // Its return value is a human-readable description, NOT a command.
            // Don't enqueue the description as a command.
            if (isSelfExecuting) {
                respond(ex, 200, "{\"success\":true,\"output\":\"Self-executing action: " + jsonEscape(executed) + "\"}");
                return;
            }

            // Route through TaskQueue for consistent queuing behavior.
            // The executed string is already a concrete command like "move: #goto x y z".
            // Extract just the raw command part after the type prefix for the queue.
            String rawCommand = executed;
            int colonIdx = executed.indexOf(':');
            if (colonIdx > 0) {
                rawCommand = executed.substring(colonIdx + 1).trim();
            }
            int queued = TaskQueue.getInstance().enqueue(rawCommand);
            respond(ex, 200, "{\"success\":true,\"queued\":" + queued + ",\"output\":\"Action sent: " + jsonEscape(executed) + "\"}");
        }
    }

    private void handleBatch(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            // Accept {"actions":[{...},{...}]} or {"commands":["#goto...","#mine..."]}
            java.util.List<String> commands = new java.util.ArrayList<>();
            boolean hadActionsArray = false;
            int selfExecutingCount = 0;

            // Try "actions" array first (typed actions)
            String actionsArray = extractJsonArray(body, "actions");
            if (actionsArray != null) {
                hadActionsArray = true;
                // Parse each action object in the array to a concrete command string
                String[] actionObjects = splitJsonArray(actionsArray);
                for (String actionObj : actionObjects) {
                    if (actionObj == null || actionObj.isBlank()) continue;
                    // Extract the action type BEFORE executing, so we know
                    // whether the result is self-executing (craft) or just a
                    // description string that should NOT be queued.
                    String actionType = extractJsonString(actionObj, "type");
                    boolean isSelfExecuting = "craft".equals(actionType);

                    String executed = CommandExecutor.executeTypedJson(actionObj);
                    if (executed == null || executed.isBlank()) continue;

                    // Craft actions are self-executing: executeCraftAction() directly
                    // sends #task interact and registers a post-Baritone callback.
                    // Its return value is a human-readable description, NOT a command.
                    // Adding it to the queue would inject a garbage command that
                    // Baritone can't parse (e.g. "opening crafting table at...").
                    if (isSelfExecuting) {
                        selfExecutingCount++;
                        continue;
                    }

                    int colonIdx = executed.indexOf(':');
                    String cmd = colonIdx > 0 ? executed.substring(colonIdx + 1).trim() : executed;
                    commands.add(cmd);
                }
            }

            // Try "commands" array (raw command strings)
            if (commands.isEmpty() && !hadActionsArray) {
                String cmdsArray = extractJsonArray(body, "commands");
                if (cmdsArray != null) {
                    String[] cmdElements = splitJsonArray(cmdsArray);
                    for (String cmd : cmdElements) {
                        if (cmd == null || cmd.isBlank()) continue;
                        // Unwrap JSON string quotes if present
                        String trimmed = cmd.trim();
                        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        }
                        if (!trimmed.isBlank()) {
                            commands.add(trimmed);
                        }
                    }
                }
            }

            // If we had only self-executing actions (e.g. craft-only batch),
            // they've already been handled — return success.
            if (commands.isEmpty() && hadActionsArray && selfExecutingCount > 0) {
                respond(ex, 200, "{\"success\":true,\"self_executed\":" + selfExecutingCount + "}");
                return;
            }

            if (commands.isEmpty()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Missing 'actions' or 'commands' array\"}");
                return;
            }

            int queued = TaskQueue.getInstance().enqueue(commands);
            respond(ex, 200, "{\"success\":true,\"queued\":" + queued + "}");
        }
    }

    private void handleCapabilities(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, CommandExecutor.capabilitiesJson());
    }

    private void handleCommands(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, CommandExecutor.discoverCommandsJson());
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

    static Integer extractQueryInt(String query, String key) {
        Long value = extractQueryLong(query, key);
        return value == null ? null : value.intValue();
    }

    static boolean extractQueryBoolean(String query, String key) {
        if (query == null || query.isBlank()) return false;
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (key.equals(kv[0])) {
                if (kv.length == 1 || kv[1].isBlank()) return true;
                String decoded = URLDecoder.decode(kv[1], StandardCharsets.UTF_8).toLowerCase();
                return decoded.equals("1") || decoded.equals("true") || decoded.equals("yes");
            }
        }
        return false;
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

    /**
     * Extract a JSON array value for a given key.
     * Returns the raw array text (including brackets), or null.
     */
    static String extractJsonArray(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('[', colon + 1);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * Split a JSON array string into individual top-level elements.
     * Handles nested objects and arrays.
     */
    static String[] splitJsonArray(String array) {
        // Remove outer brackets
        String inner = array.trim();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1).trim();
        }
        if (inner.isEmpty()) return new String[0];

        java.util.List<String> elements = new java.util.ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    String element = inner.substring(start, i).trim();
                    if (!element.isEmpty()) {
                        elements.add(element);
                    }
                    start = i + 1;
                }
            }
        }
        // Last element
        String last = inner.substring(start).trim();
        if (!last.isEmpty()) {
            elements.add(last);
        }
        return elements.toArray(new String[0]);
    }

    /** Escape a string for embedding inside a JSON string value. */
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
