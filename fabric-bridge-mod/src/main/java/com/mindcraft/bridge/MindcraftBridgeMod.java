package com.mindcraft.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mindcraft Bridge Mod — main entry point.
 *
 * On client initialisation this starts a lightweight HTTP server on
 * localhost:8765 that the Node.js Mindcraft agent uses to:
 *
 *   GET  /ping    — liveness check
 *   GET  /state   — current player state (position, health, inventory, chat queue)
 *   POST /command — execute a Baritone / Minecraft command on this client
 *
 * Baritone commands (prefixed with #) are sent as chat messages that Baritone
 * intercepts via its ALLOW_CHAT Fabric event hook before they reach the server.
 */
@Environment(EnvType.CLIENT)
public class MindcraftBridgeMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    public static final int HTTP_PORT = 8765;

    private static BridgeHttpServer httpServer;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Mindcraft Bridge Mod initialising...");
        try {
            StateCollector.registerEvents();
            httpServer = new BridgeHttpServer(HTTP_PORT);
            httpServer.start();
            LOGGER.info("Mindcraft Bridge HTTP server started on localhost:{}", HTTP_PORT);
        } catch (Exception e) {
            LOGGER.error("Failed to start Mindcraft Bridge HTTP server", e);
        }

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(new net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick() {
            boolean applied = false;
            @Override
            public void onEndTick(net.minecraft.client.MinecraftClient client) {
                if (applied) return;
                if (client.world == null || client.player == null) return;
                BaritoneTuning.applyOpinionatedDefaults();
                applied = true;
            }
        });
    }
}
