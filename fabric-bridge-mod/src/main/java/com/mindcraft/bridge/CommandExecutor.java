package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Executes a command string on the Minecraft client main thread.
 *
 * Supported prefixes:
 *   #…        Baritone command — Baritone's ALLOW_CHAT hook intercepts the
 *              message before it reaches the server and executes it locally.
 *   /…        Minecraft server command (e.g. /time set day)
 *   chat: …   Public chat message (everything after "chat: ")
 *   (default) Treated as a plain chat message
 */
public class CommandExecutor {
    private static String lastCommand = "";
    private static long lastCommandAt = 0L;
    private static final long COMMAND_DEBOUNCE_MS = 150L;

    public static void execute(String command) {
        long now = System.currentTimeMillis();
        if (command != null && command.equals(lastCommand) && (now - lastCommandAt) < COMMAND_DEBOUNCE_MS) {
            MindcraftBridgeMod.LOGGER.info("[Bridge] Debounced duplicate command: {}", command);
            return;
        }
        lastCommand = command;
        lastCommandAt = now;

        MinecraftClient client = MinecraftClient.getInstance();
        // Schedule on the main game thread to avoid concurrency issues
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player == null || client.getNetworkHandler() == null) {
                MindcraftBridgeMod.LOGGER.warn("Cannot execute command — player not in game: {}", command);
                return;
            }

            if (command.startsWith("/")) {
                // Strip the leading slash and send as a server command
                client.getNetworkHandler().sendCommand(command.substring(1));
                MindcraftBridgeMod.LOGGER.info("[Bridge] Command: {}", command);

            } else if (command.startsWith("chat:")) {
                String msg = command.substring(5).trim();
                client.getNetworkHandler().sendChatMessage(msg);
                MindcraftBridgeMod.LOGGER.info("[Bridge] Chat: {}", msg);

            } else {
                // Everything else (including Baritone # commands) is sent as a
                // chat message.  Baritone hooks ClientSendMessageEvents.ALLOW_CHAT
                // and intercepts messages that start with its configured prefix (#).
                // If Baritone is not installed the message goes to public chat.
                client.getNetworkHandler().sendChatMessage(command);
                MindcraftBridgeMod.LOGGER.info("[Bridge] Chat/Baritone: {}", command);
            }
        });
    }

    public static String executeTypedJson(String actionJson) {
        String type = BridgeHttpServer.extractJsonString(actionJson, "type");
        String provider = BridgeHttpServer.extractJsonString(actionJson, "provider");
        String target = BridgeHttpServer.extractJsonString(actionJson, "target");
        String command = BridgeHttpServer.extractJsonString(actionJson, "command");
        String message = BridgeHttpServer.extractJsonString(actionJson, "message");
        String x = BridgeHttpServer.extractJsonPrimitive(actionJson, "x");
        String y = BridgeHttpServer.extractJsonPrimitive(actionJson, "y");
        String z = BridgeHttpServer.extractJsonPrimitive(actionJson, "z");
        String count = BridgeHttpServer.extractJsonPrimitive(actionJson, "count");

        if (type == null || type.isBlank()) {
            return null;
        }

        String mapped = mapTypedAction(type, provider, target, command, message, x, y, z, count);
        if (mapped == null || mapped.isBlank()) {
            return null;
        }
        execute(mapped);
        return mapped;
    }

    private static String mapTypedAction(
            String type,
            String provider,
            String target,
            String command,
            String message,
            String x,
            String y,
            String z,
            String count
    ) {
        boolean nativeRequested = "baritone_native".equalsIgnoreCase(provider);
        if (nativeRequested) {
            // Native provider placeholder: currently falls back to chat-prefixed Baritone mapping below.
            MindcraftBridgeMod.LOGGER.info("[Bridge] baritone_native requested; using baritone_chat fallback.");
        }

        switch (type) {
            case "move":
                if (x == null || y == null || z == null) return null;
                return "#goto " + x + " " + y + " " + z;
            case "mine":
                if (target == null || target.isBlank()) return null;
                String mineCount = (count == null || count.isBlank()) ? "1" : count;
                return "#mine " + target + " " + mineCount;
            case "follow":
                if (target == null || target.isBlank()) return null;
                return "#follow player " + target;
            case "cancel":
                return "#cancel";
            case "interact":
                if (message != null && !message.isBlank()) {
                    return "chat: " + message;
                }
                return command;
            case "raw_command":
                return command;
            default:
                return null;
        }
    }

    public static String capabilitiesJson() {
        boolean baritoneLoaded = FabricLoader.getInstance().isModLoaded("baritone");
        return "{"
                + "\"supports_typed_actions\":true,"
                + "\"default_provider\":\"baritone_chat\","
                + "\"providers\":{"
                + "\"baritone_native\":{\"available\":" + baritoneLoaded + "},"
                + "\"baritone_chat\":{\"available\":true}"
                + "}"
                + "}";
    }
}
