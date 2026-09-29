package com.mindcraft.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
        BridgeConfig config = BridgeConfig.get();
        try {
            StateCollector.registerEvents();
            if (config.companionChannelEnabled) {
                CompanionChannel.register();
            }
            httpServer = new BridgeHttpServer(HTTP_PORT);
            httpServer.start();
            LOGGER.info("Mindcraft Bridge HTTP server started on localhost:{}", HTTP_PORT);
            LOGGER.info("Bridge endpoints (except /ping) require the bearer token in config/mindcraft-bridge.json (field bridgeToken). Copy it into the agent keys.json as FABRIC_BRIDGE_TOKEN; /state drains chat/events only with ?drain=true.");
        } catch (Exception e) {
            LOGGER.error("Failed to start Mindcraft Bridge HTTP server", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mindcraft-shutdown"));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());

        ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
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

    private void shutdown() {
        LOGGER.info("Mindcraft Bridge Mod shutting down...");
        TaskQueue.getInstance().cancelAll();
        WorkerThreads.interruptAll("shutdown");
        if (httpServer != null) {
            httpServer.stop();
        }
        LOGGER.info("Mindcraft Bridge Mod shutdown complete");
    }

    private void onDisconnect() {
        LOGGER.info("Player disconnected, cancelling all tasks");
        TaskQueue.getInstance().cancelAll();
        WorkerThreads.interruptAll("disconnect");
        // Drop server-companion data so /state doesn't report stale roster/facts after switching servers.
        CompanionState.get().reset();
    }
}
