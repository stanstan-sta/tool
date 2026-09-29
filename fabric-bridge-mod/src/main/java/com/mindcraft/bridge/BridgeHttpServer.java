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

import com.mindcraft.bridge.workers.ActionRegistry;
import net.minecraft.client.MinecraftClient;

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
        server.createContext("/queue/skip", this::handleQueueSkip);
        server.createContext("/queue/resume", this::handleQueueResume);
        server.createContext("/queue/cancel", this::handleQueueCancel);
        server.createContext("/queue/state", this::handleQueueState);
        server.createContext("/read_blocks", this::handleReadBlocks);
        server.createContext("/screenshot", this::handleScreenshot);

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

    public void stop() {
        server.stop(0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Local-only control plane: bearer auth, loopback-only CORS, explicit drain
    // ──────────────────────────────────────────────────────────────────────────

    // A10: every non-liveness endpoint requires the per-installation bearer
    // token from the mod config. /ping stays open as a liveness check only.
    // Fails closed when no token is configured. Constant-time comparison.
    static boolean isAuthorizedBridgeCall(HttpExchange ex) {
        String token = BridgeConfig.get().bridgeToken;
        if (token == null || token.isBlank()) return false;
        if (ex == null || ex.getRequestHeaders() == null) return false;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        String supplied = auth.substring("Bearer ".length()).trim();
        if (supplied.isEmpty()) return false;
        return java.security.MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private boolean requireBridgeAuth(HttpExchange ex) throws IOException {
        if (isAuthorizedBridgeCall(ex)) return false;
        respond(ex, 401, "{\"success\":false,\"error\":\"unauthorized_bridge_token\"}");
        return true;
    }

    // A12: browsers may only read responses when the requesting origin is
    // itself loopback. Arbitrary web origins get no ACAO header, so the
    // browser blocks their reads of state/screenshots. Non-browser clients
    // send no Origin and are unaffected.
    private static void applyBridgeCors(HttpExchange ex) {
        if (ex == null || ex.getRequestHeaders() == null) return;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        String o = origin.trim();
        if (o.matches("http://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?")) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", o);
            ex.getResponseHeaders().set("Vary", "Origin");
        }
    }

    // Minimal preflight for loopback browser tooling; other origins get a
    // bare 204 with no ACAO, which the browser refuses to share. Returns true
    // when the request was a preflight and has been answered.
    private static boolean handleBridgePreflight(HttpExchange ex) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) return false;
        applyBridgeCors(ex);
        if (ex.getResponseHeaders().containsKey("Access-Control-Allow-Origin")) {
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
        ex.sendResponseHeaders(204, -1);
        ex.getResponseBody().close();
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Handlers
    // ──────────────────────────────────────────────────────────────────────────

    private void handlePing(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        respond(ex, 200, "{\"ok\":true}");
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        Long since = extractQueryLong(query, "since");
        boolean includeSurface = extractQueryBoolean(query, "surface");
        // A12: reads are non-destructive by default. Only the agent's
        // observation poll sends drain=true explicitly; peek reads (vision,
        // inspections, reconnect checks) and legacy peek=true leave the chat,
        // world-event and companion queues intact for the real consumer.
        boolean drainEvents = extractQueryBoolean(query, "drain");

        int maxRadius = BridgeConfig.get().maxSurfaceRadius;
        Integer rawRadius = extractQueryInt(query, "surface_radius");
        int surfaceRadius = rawRadius != null ? rawRadius : 8;
        if (surfaceRadius < 0) {
            respond(ex, 400, "{\"error\":\"surface_radius must be >= 0\",\"maxSurfaceRadius\":" + maxRadius + "}");
            return;
        }
        if (surfaceRadius > maxRadius) {
            respond(ex, 400, "{\"error\":\"surface_radius exceeds maxSurfaceRadius=" + maxRadius + "\",\"maxSurfaceRadius\":" + maxRadius + "}");
            return;
        }

        String json = StateCollector.collect(since, includeSurface, surfaceRadius, drainEvents);
        respond(ex, 200, json);
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
            // Parse {"command":"..."} manually — no Gson needed, keep it simple.
            String command = extractJsonString(body, "command");
            if (command == null || command.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"error\":\"Missing 'command' field\"}");
                return;
            }
            command = CommandExecutor.normalizeRawBaritoneCommand(command);
            if (!command.startsWith("chat:") && !RawCommandPolicy.isAllowed(command)) {
                respond(ex, 422, "{\"success\":false,\"accepted\":false,\"error\":\"raw_command_forbidden\",\"queued\":0}");
                return;
            }
            if (!isClientConnected()) {
                respond(ex, 503, "{\"success\":false,\"error\":\"client_not_connected\",\"queued\":0}");
                return;
            }
            // Chat executes immediately; all other commands have passed the raw policy.
            if (command.startsWith("chat:")) {
                CommandExecutor.execute(command);
                respond(ex, 200, "{\"success\":true,\"output\":\"Command sent: " + jsonEscape(command) + "\"}");
                return;
            }
            // Queue the normalized command
            TaskQueue.EnqueueResult eq = TaskQueue.getInstance()
                .enqueueDetailed(java.util.List.of(new TaskQueue.QueuedCommand(command, null)));
            if (eq.status() == TaskQueue.EnqueueStatus.REJECTED) {
                int httpStatus = "QUEUE_FULL".equals(eq.error()) ? 503 : 400;
                respond(ex, httpStatus, "{\"success\":false,\"error\":\"" + jsonEscape(eq.error()) + "\",\"queued\":0}");
                return;
            }
            respond(ex, 200, "{\"success\":true,\"queued\":" + eq.queued() + ",\"output\":\"Command sent: " + jsonEscape(command) + "\"}");
        } catch (PayloadTooLargeException e) {
            respond(ex, 413, "{\"success\":false,\"error\":\"PAYLOAD_TOO_LARGE\"}");
        }
    }

    private static boolean isClientConnected() {
        try {
            Boolean connected = ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                return client != null && client.player != null && client.world != null;
            });
            return Boolean.TRUE.equals(connected);
        } catch (Throwable t) {
            return false;
        }
    }

    private void handleAction(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
            if (rejectStaleGeneration(ex, body)) return;
            String actionJson = extractJsonObject(body, "action");
            if (actionJson == null) {
                actionJson = body != null ? body.trim() : null;
            }
            if (actionJson == null || actionJson.isBlank()) {
                respond(ex, 400, "{\"success\":false,\"accepted\":false,\"error\":\"Missing 'action' field\"}");
                return;
            }

            String rawType = extractJsonString(actionJson, "type");
            com.mindcraft.bridge.workers.ActionRegistry.ActionSpec spec =
                    com.mindcraft.bridge.workers.ActionRegistry.get().getSpec(rawType);
            if (spec == null || !com.mindcraft.bridge.workers.ActionRegistry.get().isExternallyRoutable(rawType)) {
                respond(ex, 400, "{\"success\":false,\"accepted\":false,\"error\":\"Unknown action type\"}");
                return;
            }
            String lifecycle = spec.lifecycle().name().toLowerCase(java.util.Locale.ROOT);
            CommandExecutor.TranslatedAction result = new CommandExecutor.TranslatedAction(
                    true, rawType, lifecycle, null, null, "queued");
            TaskQueue.EnqueueResult eq = TaskQueue.getInstance().enqueueTypedActions(
                    java.util.List.of(new TaskQueue.TypedAction(rawType, actionJson)));
            if (eq.status() == TaskQueue.EnqueueStatus.REJECTED) {
                respond(ex, "QUEUE_FULL".equals(eq.error()) ? 503 : 400,
                    "{\"success\":false,\"accepted\":false,\"error\":\"" + jsonEscape(eq.error()) + "\",\"queued\":0}");
                return;
            }
            respond(ex, 200, "{"
                + "\"success\":true,"
                + "\"accepted\":true,"
                + "\"queued\":" + eq.queued() + ","
                + "\"task_ids\":" + taskIdsJson(eq.taskIds()) + ","
                + "\"results\":[" + resultJson(result) + "],"
                + "\"output\":\"Action queued: " + jsonEscape(rawType) + "\""
                + "}");
        } catch (PayloadTooLargeException e) {
            respond(ex, 413, "{\"success\":false,\"error\":\"PAYLOAD_TOO_LARGE\"}");
        }
    }

    private void handleBatch(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
            if (rejectStaleGeneration(ex, body)) return;

            int totalQueued = 0;
            boolean hadActionsArray = false;
            boolean hadCommandsArray = false;
            boolean cancelled = false;
            java.util.List<CommandExecutor.TranslatedAction> results = new java.util.ArrayList<>();
            java.util.List<Long> allTaskIds = new java.util.ArrayList<>();

            // Try "actions" array first (typed actions)
            String actionsArray = extractJsonArray(body, "actions");
            if (actionsArray != null) {
                hadActionsArray = true;
                String[] actionObjects = splitJsonArray(actionsArray);

                for (String actionObj : actionObjects) {
                    if (actionObj == null || actionObj.isBlank()) continue;
                    String actionType = extractJsonString(actionObj, "type");

                    if ("cancel".equals(actionType)) {
                        TaskQueue.getInstance().cancelAll();
                        cancelled = true;
                        results.add(new CommandExecutor.TranslatedAction(true, "cancel", "immediate", null, null, "cancelled"));
                        continue;
                    }
                    int pq = processTypedBatchAction(actionObj, results, allTaskIds);
                    if (pq < 0) {
                        respond(ex, 503, "{\"success\":false,\"accepted\":false,\"error\":\"QUEUE_FULL\",\"queued\":0}");
                        return;
                    }
                    totalQueued += pq;
                }

            }

            // Try "commands" array (raw command strings)
            if (!hadActionsArray) {
                String cmdsArray = extractJsonArray(body, "commands");
                if (cmdsArray != null) {
                    hadCommandsArray = true;
                    java.util.List<String> rawCommands = new java.util.ArrayList<>();
                    String[] cmdElements = splitJsonArray(cmdsArray);
                    for (String cmd : cmdElements) {
                        if (cmd == null || cmd.isBlank()) continue;
                        String trimmed = cmd.trim();
                        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        }
                        if (!trimmed.isBlank()) {
                            String normalized = CommandExecutor.normalizeRawBaritoneCommand(trimmed);
                            if (!RawCommandPolicy.isAllowed(normalized)) {
                                results.add(new CommandExecutor.TranslatedAction(false, "raw_command", null,
                                        null, "raw_command_forbidden", "raw_command not allowed: " + trimmed));
                                continue;
                            }
                            rawCommands.add(normalized);
                        }
                    }
                    if (!rawCommands.isEmpty()) {
                        TaskQueue.EnqueueResult eq = TaskQueue.getInstance()
                            .enqueueDetailed(rawCommands.stream()
                                .map(cmd -> new TaskQueue.QueuedCommand(cmd, null))
                                .collect(java.util.stream.Collectors.toList()));
                        if (eq.status() == TaskQueue.EnqueueStatus.REJECTED) {
                            respond(ex, "QUEUE_FULL".equals(eq.error()) ? 503 : 400,
                                "{\"success\":false,\"accepted\":false,\"error\":\"" + jsonEscape(eq.error()) + "\",\"queued\":0}");
                            return;
                        }
                        totalQueued += eq.queued();
                        allTaskIds.addAll(eq.taskIds());
                        for (String cmd : rawCommands) {
                            results.add(new CommandExecutor.TranslatedAction(true, "raw_command", "queued", cmd, null, "queued"));
                        }
                    }
                }
            }

            // Build response
            StringBuilder responseJson = new StringBuilder("{");
            boolean hasError = results.stream().anyMatch(r -> !r.ok());
            boolean allRejected = results.stream().allMatch(r -> !r.ok());
            responseJson.append("\"success\":").append(!hasError).append(",");
            responseJson.append("\"accepted\":").append(!allRejected).append(",");
            responseJson.append("\"queued\":").append(totalQueued).append(",");
            responseJson.append("\"task_ids\":").append(taskIdsJson(allTaskIds)).append(",");

            responseJson.append("\"results\":[");
            boolean firstResult = true;
            for (CommandExecutor.TranslatedAction r : results) {
                if (!firstResult) responseJson.append(",");
                firstResult = false;
                responseJson.append(resultJson(r));
            }
            responseJson.append("]");

            if (cancelled) {
                responseJson.append(",\"cancelled\":true");
            }

            responseJson.append(",\"output\":\"Batch processed: ").append(totalQueued).append(" actions queued\"");
            responseJson.append("}");

            if (totalQueued == 0 && !hadActionsArray && !hadCommandsArray) {
                respond(ex, 400, "{\"success\":false,\"accepted\":false,\"error\":\"Missing 'actions' or 'commands' array\"}");
            } else if (totalQueued == 0 && hasError) {
                respond(ex, 422, responseJson.toString());
            } else {
                respond(ex, 200, responseJson.toString());
            }
        } catch (PayloadTooLargeException e) {
            respond(ex, 413, "{\"success\":false,\"error\":\"PAYLOAD_TOO_LARGE\"}");
        }
    }

    private int processTypedBatchAction(String actionObj,
            java.util.List<CommandExecutor.TranslatedAction> results,
            java.util.List<Long> taskIds) {
        String actionType = extractJsonString(actionObj, "type");
        com.mindcraft.bridge.workers.ActionRegistry.ActionSpec spec =
                com.mindcraft.bridge.workers.ActionRegistry.get().getSpec(actionType);
        if (spec == null || !com.mindcraft.bridge.workers.ActionRegistry.get().isExternallyRoutable(actionType)) {
            results.add(new CommandExecutor.TranslatedAction(false, actionType, null, null,
                    "unknown_action", "Unknown action type: " + actionType));
            return 0;
        }
        String lifecycle = spec.lifecycle().name().toLowerCase(java.util.Locale.ROOT);
        CommandExecutor.TranslatedAction accepted = new CommandExecutor.TranslatedAction(
                true, actionType, lifecycle, null, null, "queued");
        TaskQueue.EnqueueResult eq = TaskQueue.getInstance().enqueueTypedActions(
                java.util.List.of(new TaskQueue.TypedAction(actionType, actionObj)));
        if (eq.status() == TaskQueue.EnqueueStatus.REJECTED) {
            results.add(new CommandExecutor.TranslatedAction(false, actionType, null, null,
                    "QUEUE_FULL", eq.error()));
            return -1;
        }
        results.add(accepted);
        taskIds.addAll(eq.taskIds());
        return eq.queued();
    }

    /**
     * Flush buffered contiguous craft actions through the shared batch planner.
     * Returns the number of queued steps, adds results and task IDs.
     */
    private int flushCraftBatch(java.util.List<String> craftActions,
            java.util.List<CommandExecutor.TranslatedAction> results,
            java.util.List<Long> taskIds) {
        String result = CommandExecutor.executeCraftBatch(craftActions);
        if (result != null && result.startsWith("craft: queued")) {
            int count = parseCraftQueuedCount(result);
            // All craft actions in the buffer succeeded
            for (int i = 0; i < craftActions.size(); i++) {
                String itemName = CommandExecutor.extractJsonString(craftActions.get(i), "item");
                String actionDesc = itemName != null ? "craft " + itemName : "craft action " + (i + 1);
                results.add(new CommandExecutor.TranslatedAction(true, "craft", "self_executing", result, null,
                        "queued: " + actionDesc));
            }
            return count;
        }
        // Check if rejected due to parse errors in specific entries
        if (result != null && result.startsWith("craft: batch rejected")) {
            // Mark all craft actions in this buffer as rejected
            for (int i = 0; i < craftActions.size(); i++) {
                String itemName = CommandExecutor.extractJsonString(craftActions.get(i), "item");
                String actionDesc = itemName != null ? "craft " + itemName : "craft action " + (i + 1);
                if (itemName == null || itemName.isBlank()) {
                    results.add(new CommandExecutor.TranslatedAction(false, "craft", null,
                            null, "invalid_craft_action", "Missing 'item' field in craft action " + (i + 1)));
                } else {
                    results.add(new CommandExecutor.TranslatedAction(false, "craft", null,
                            null, "invalid_craft_action", "Invalid craft action: " + actionDesc + " - " + result));
                }
            }
            return 0;
        }
        // Generic failure
        String err = result == null || result.isBlank() ? "craft: invalid action" : result;
        for (int i = 0; i < craftActions.size(); i++) {
            String itemName = CommandExecutor.extractJsonString(craftActions.get(i), "item");
            String actionDesc = itemName != null ? "craft " + itemName : "craft action " + (i + 1);
            results.add(new CommandExecutor.TranslatedAction(false, "craft", null, null, "craft_failed", err));
        }
        return 0;
    }

    private void handleCapabilities(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, CommandExecutor.capabilitiesJson());
    }

    private void handleCommands(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, CommandExecutor.discoverCommandsJson());
    }

    private void handleScreenshot(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        if (!isClientConnected()) {
            respond(ex, 503, "{\"success\":false,\"error\":\"client_not_connected\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        Integer downscale = extractQueryInt(query, "downscale");
        Float quality = extractQueryFloat(query, "quality");
        int requestedDownscale = downscale == null ? 2 : downscale;
        float requestedQuality = quality == null ? 0.8f : quality;
        try {
            ScreenshotCapture.Capture capture = ScreenshotCapture.captureJpeg(requestedDownscale, requestedQuality);
            ex.getResponseHeaders().set("X-Mindcraft-Image-Width", String.valueOf(capture.width()));
            ex.getResponseHeaders().set("X-Mindcraft-Image-Height", String.valueOf(capture.height()));
            ex.getResponseHeaders().set("X-Mindcraft-Image-Quality", String.valueOf(capture.quality()));
            ex.getResponseHeaders().set("X-Mindcraft-Image-Downscale", String.valueOf(capture.downscaleFactor()));
            respondBytes(ex, 200, "image/jpeg", capture.bytes());
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            respond(ex, 503, "{\"success\":false,\"error\":\"screenshot_failed\",\"message\":\"" + jsonEscape(message) + "\"}");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static String resultJson(CommandExecutor.TranslatedAction result) {
        if (result == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"action_type\":\"").append(jsonEscape(result.actionType() != null ? result.actionType() : "")).append("\",");
        sb.append("\"status\":\"").append(result.ok() ? (result.lifecycle() != null ? result.lifecycle() : "accepted") : "rejected").append("\",");
        sb.append("\"command\":").append(result.command() == null ? "null" : "\"" + jsonEscape(result.command()) + "\"").append(",");
        sb.append("\"failure_code\":").append(result.failureCode() == null ? "null" : "\"" + jsonEscape(result.failureCode()) + "\"").append(",");
        sb.append("\"message\":\"").append(jsonEscape(result.message() != null ? result.message() : "")).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String taskIdsJson(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private static final class PayloadTooLargeException extends IOException {}

    private static String readBodyLimited(HttpExchange exchange, int maxBytes) throws IOException {
        try (java.io.InputStream in = exchange.getRequestBody();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = in.read(buffer);
                if (read == -1) break;
                total += read;
                if (total > maxBytes) throw new PayloadTooLargeException();
                out.write(buffer, 0, read);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        applyBridgeCors(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondBytes(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        applyBridgeCors(ex);
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

    static Float extractQueryFloat(String query, String key) {
        if (query == null || query.isBlank()) return null;
        String[] parts = query.split("&");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                try {
                    String decoded = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    return Float.parseFloat(decoded);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
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

    private static int parseCraftQueuedCount(String output) {
        if (output == null) return 1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^craft: queued\\s+(\\d+)").matcher(output);
        if (!matcher.find()) return 1;
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Queue management handlers
    // ──────────────────────────────────────────────────────────────────────────

    private boolean rejectStaleGeneration(HttpExchange ex, String body) throws IOException {
        if (body == null || body.isBlank()) return false;
        String rawGeneration = extractJsonPrimitive(body, "generation");
        if (rawGeneration == null) return false;

        Long requestedGeneration;
        try {
            requestedGeneration = Long.parseLong(rawGeneration);
        } catch (NumberFormatException ignored) {
            respond(ex, 400, "{\"success\":false,\"accepted\":false,\"error\":\"invalid_generation\",\"queued\":0}");
            return true;
        }

        if (TaskQueue.getInstance().acceptClientGeneration(requestedGeneration)) return false;
        respond(ex, 409, "{\"success\":false,\"accepted\":false,\"error\":\"stale_generation\",\"queued\":0}");
        return true;
    }

    private void handleQueueSkip(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
        if (rejectStaleGeneration(ex, body)) return;
        TaskQueue.getInstance().skip();
        respond(ex, 200, "{\"success\":true,\"action\":\"skipped\"}");
    }

    private void handleQueueResume(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
        if (rejectStaleGeneration(ex, body)) return;
        TaskQueue.getInstance().resume();
        respond(ex, 200, "{\"success\":true,\"action\":\"resumed\"}");
    }

    private void handleQueueCancel(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String body = readBodyLimited(ex, BridgeConfig.get().maxRequestBytes);
        if (rejectStaleGeneration(ex, body)) return;
        TaskQueue.getInstance().cancelAll();
        respond(ex, 200, "{\"success\":true,\"action\":\"cancelled\"}");
    }

    private void handleQueueState(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
        String json = "{"
            + "\"active_id\":" + (qs.activeId() == null ? "null" : String.valueOf(qs.activeId())) + ","
            + "\"active_action_type\":" + (qs.activeActionType() == null ? "null" : "\"" + jsonEscape(qs.activeActionType()) + "\"") + ","
            + "\"status\":\"" + jsonEscape(qs.status()) + "\","
            + "\"active\":" + (qs.active() == null ? "null" : "\"" + jsonEscape(qs.active()) + "\"") + ","
            + (qs.kind() != null ? "\"kind\":\"" + jsonEscape(qs.kind()) + "\"," : "")
            + (qs.completion() != null ? "\"completion\":\"" + jsonEscape(qs.completion()) + "\"," : "")
            + "\"pending\":" + qs.pending() + ","
            + "\"paused\":" + qs.paused()
            + (qs.lastFailure() != null ? ",\"lastFailure\":\"" + jsonEscape(qs.lastFailure()) + "\"" : "")
            + (qs.failureCode() != null ? ",\"failure_code\":\"" + jsonEscape(qs.failureCode()) + "\"" : "")
            + (qs.elapsedMs() != null ? ",\"elapsed_ms\":" + qs.elapsedMs() : "")
            + (qs.timeoutMs() != null ? ",\"timeout_ms\":" + qs.timeoutMs() : "")
            + (qs.cancellable() != null ? ",\"cancellable\":" + qs.cancellable() : "")
            + "}";
        respond(ex, 200, json);
    }

    /** Escape a string for embedding inside a JSON string value. */
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * GET /read_blocks?x=..&y=..&z=..&w=..&h=..&l=..
     * Returns a flat array of block identifier strings for the box
     * (x..x+w, y..y+h, z..z+l) in XZY order.
     * Clamped to 24x24x24 to avoid runaway responses.
     */
    private void handleReadBlocks(HttpExchange ex) throws IOException {
        if (handleBridgePreflight(ex)) return;
        if (requireBridgeAuth(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery();
        Integer x = extractQueryInt(query, "x");
        Integer y = extractQueryInt(query, "y");
        Integer z = extractQueryInt(query, "z");
        Integer w = extractQueryInt(query, "w");
        Integer h = extractQueryInt(query, "h");
        Integer l = extractQueryInt(query, "l");
        if (x == null || y == null || z == null || w == null || h == null || l == null) {
            respond(ex, 400, "{\"error\":\"Missing x/y/z/w/h/l query params\"}");
            return;
        }
        if (w <= 0 || h <= 0 || l <= 0) {
            respond(ex, 400, "{\"error\":\"w/h/l must be positive\"}");
            return;
        }
        if (w > 24) w = 24;
        if (h > 24) h = 24;
        if (l > 24) l = 24;
        String json = CommandExecutor.readBlocksInBox(x, y, z, w, h, l);
        if (json == null) {
            respond(ex, 503, "{\"error\":\"Not connected\"}");
            return;
        }
        respond(ex, 200, json);
    }
}
