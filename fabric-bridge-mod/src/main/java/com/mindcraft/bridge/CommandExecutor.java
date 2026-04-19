package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

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

    public static void execute(String command) {
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
}
