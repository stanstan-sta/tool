package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Executes Minecraft commands and typed actions on the client.
 * Called by {@link BridgeHttpServer} on the /command and /action endpoints.
 */
public class CommandExecutor {

    private CommandExecutor() {}

    /**
     * Execute a raw Minecraft command via the client's command handler.
     * Runs on the Minecraft main thread.
     */
    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (command.startsWith("chat:")) {
            String message = command.substring(5).trim();
            if (!message.isEmpty()) {
                sendChat(client, message);
            }
        } else {
            final String cmd = command.startsWith("/") ? command.substring(1) : command;
            client.execute(() -> {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatCommand(cmd);
                }
            });
        }
    }

    /**
     * Execute a typed action (JSON-structured action).
     * Returns a human-readable description of what was sent, or null on failure.
     */
    public static String executeTypedJson(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) return null;

        // Parse minimal fields from the action JSON without a full JSON library.
        String type = extractJsonString(actionJson, "type");
        if (type == null || type.isBlank()) return null;

        String command = extractRawBaritoneCommand(actionJson, type);
        if (command != null) {
            execute(command);
            return type + ": " + command;
        }

        // Fallback: try "command" field
        String rawCmd = extractJsonString(actionJson, "command");
        if (rawCmd != null && !rawCmd.isBlank()) {
            execute(rawCmd);
            return type + ": " + rawCmd;
        }

        return type;
    }

    /**
     * Return JSON describing the mod's capabilities.
     */
    public static String capabilitiesJson() {
        return "{"
            + "\"supports_typed_actions\":true,"
            + "\"default_provider\":\"baritone_chat\","
            + "\"providers\":[\"baritone_chat\"],"
            + "\"action_types\":[\"move\",\"mine\",\"follow\",\"cancel\",\"raw_command\"],"
            + "\"version\":\"1.0.0\""
            + "}";
    }

    /**
     * Return JSON with a static list of discoverable commands.
     * In a full implementation this could scan Minecraft's command registry.
     */
    public static String discoverCommandsJson() {
        return "["
            + "\"#goto x y z\","
            + "\"#mine count block\","
            + "\"#follow player <name>\","
            + "\"#cancel\","
            + "\"chat: <message>\""
            + "]";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void sendChat(MinecraftClient client, String message) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        // Track sent messages so StateCollector can filter echo
        StateCollector.trackSentChat(message);

        // Send via chat message packet
        client.execute(() -> {
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendChatMessage(message);
            }
        });
    }

    /**
     * Convert a typed action into a Baritone chat command if applicable.
     */
    private static String extractRawBaritoneCommand(String json, String type) {
        switch (type) {
            case "move": {
                String x = extractJsonPrimitive(json, "x");
                String y = extractJsonPrimitive(json, "y");
                String z = extractJsonPrimitive(json, "z");
                if (x != null && y != null && z != null) {
                    return "#goto " + x + " " + y + " " + z;
                }
                return null;
            }
            case "mine": {
                String target = extractJsonString(json, "target");
                String count = extractJsonPrimitive(json, "count");
                String secondary = extractJsonString(json, "secondaryTarget");
                if (target == null) return null;
                StringBuilder sb = new StringBuilder("#mine ");
                sb.append(count != null ? count : "64").append(" ").append(target);
                if (secondary != null) sb.append(" ").append(secondary);
                return sb.toString();
            }
            case "follow": {
                String target = extractJsonString(json, "target");
                if (target != null) return "#follow player " + target;
                return null;
            }
            case "cancel":
                return "#cancel";
            case "raw_command":
                return extractJsonString(json, "command");
            default:
                return null;
        }
    }

    /**
     * Simple JSON string value extractor (no external dependencies).
     */
    private static String extractJsonString(String json, String key) {
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
        if (end >= json.length()) return null;
        return json.substring(start + 1, end)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"");
    }

    /**
     * Extract a primitive (number, boolean, null) — no surrounding quotes.
     */
    private static String extractJsonPrimitive(String json, String key) {
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
        return (end > i) ? json.substring(i, end) : null;
    }
}