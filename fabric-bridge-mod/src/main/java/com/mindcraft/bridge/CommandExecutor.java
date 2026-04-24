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
                // sendCommand() was removed from ClientPlayerEntity in 1.21.11;
                // sendChatCommand() on the network handler is the correct replacement.
                client.getNetworkHandler().sendChatCommand(command.substring(1));
                MindcraftBridgeMod.LOGGER.info("[Bridge] Command: {}", command);

            } else if (command.startsWith("chat:")) {
                String msg = command.substring(5).trim();
                // sendChatMessage() was removed from ClientPlayerEntity in 1.21.11;
                // use the network handler directly instead.
                client.getNetworkHandler().sendChatMessage(truncateChatMessage(msg));
                MindcraftBridgeMod.LOGGER.info("[Bridge] Chat: {}", truncateChatMessage(msg));

            } else {
                // Everything else (including Baritone # commands) is sent as a
                // chat message.  Baritone hooks ClientSendMessageEvents.ALLOW_CHAT
                // and intercepts messages that start with its configured prefix (#).
                // If Baritone is not installed the message goes to public chat.
                client.getNetworkHandler().sendChatMessage(truncateChatMessage(command));
                MindcraftBridgeMod.LOGGER.info("[Bridge] Chat/Baritone: {}", truncateChatMessage(command));
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

    private static String truncateChatMessage(String message) {
        if (message == null) {
            return "";
        }
        final int MAX_CHAT_LENGTH = 256;
        if (message.length() <= MAX_CHAT_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_CHAT_LENGTH);
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
    public static String discoverCommandsJson() {
        if (!FabricLoader.getInstance().isModLoaded("baritone")) {
            return "[]";
        }
        try {
            Class<?> baritoneApi = Class.forName("baritone.api.BaritoneAPI");
            Object provider = baritoneApi.getMethod("getProvider").invoke(null);
            Object primaryBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object commandManager = primaryBaritone.getClass().getMethod("getCommandManager").invoke(primaryBaritone);

            Object commands = tryInvoke(commandManager, "getKnownCommands");
            if (commands == null) {
                commands = tryInvoke(commandManager, "getCommands");
            }
            if (commands == null) {
                commands = tryInvoke(commandManager, "getCommandNames");
            }
            if (commands == null) {
                return "[]";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            if (commands instanceof java.util.Collection) {
                for (Object command : (java.util.Collection<?>) commands) {
                    String name = extractCommandName(command);
                    if (name == null || name.isBlank()) continue;
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(BridgeHttpServer.jsonEscape(name)).append("\"");
                }
            } else {
                String text = commands.toString();
                if (!text.isBlank()) {
                    sb.append("\"").append(BridgeHttpServer.jsonEscape(text)).append("\"");
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Failed to discover Baritone commands", t);
            return "[]";
        }
    }

    private static Object tryInvoke(Object target, String methodName) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractCommandName(Object commandObject) {
        if (commandObject == null) return null;
        if (commandObject instanceof String) {
            return (String) commandObject;
        }
        try {
            java.lang.reflect.Method getName = commandObject.getClass().getMethod("getName");
            Object name = getName.invoke(commandObject);
            if (name instanceof String && !((String) name).isBlank()) {
                return (String) name;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method getCommand = commandObject.getClass().getMethod("getCommand");
            Object name = getCommand.invoke(commandObject);
            if (name instanceof String && !((String) name).isBlank()) {
                return (String) name;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method toStringMethod = commandObject.getClass().getMethod("toString");
            Object name = toStringMethod.invoke(commandObject);
            if (name instanceof String) {
                return (String) name;
            }
        } catch (Throwable ignored) {
        }
        return null;
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