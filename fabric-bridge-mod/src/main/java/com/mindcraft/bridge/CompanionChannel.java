package com.mindcraft.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mindcraft.protocol.ProtocolCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompanionChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    private static final Gson GSON = new Gson();

    private CompanionChannel() {}

    public static void register() {
        PayloadTypeRegistry.playS2C().register(CompanionPayload.ID, CompanionPayload.CODEC);
        LOGGER.info("Registered companion channel (S2C)");

        ClientPlayNetworking.registerGlobalReceiver(CompanionPayload.ID, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> route(json));
        });
    }

    static void route(String json) {
        String type = ProtocolCodec.peekType(json);
        if (type == null) return;

        CompanionState state = CompanionState.get();
        CompanionState.Snapshot current = state.snapshot();

        switch (type) {
            case "roster" -> {
                // Extract just the players array (server_players expects array only, not full RosterMessage)
                String playersJson = extractRosterPlayers(json);
                state.putSnapshot(current.withRoster(playersJson));
                LOGGER.debug("Received roster update");
            }
            case "facts" -> {
                state.putSnapshot(current.withFacts(json));
                LOGGER.debug("Received facts update");
            }
            case "hello" -> {
                state.putSnapshot(current.withHello(json));
                LOGGER.debug("Received hello from server companion mod");
            }
            case "event" -> {
                state.addEvent(json);
                LOGGER.debug("Received companion event");
            }
            default -> {
                LOGGER.debug("Ignoring unknown companion message type: {}", type);
            }
        }
    }

    private static String extractRosterPlayers(String rosterJson) {
        try {
            JsonObject obj = GSON.fromJson(rosterJson, JsonObject.class);
            if (obj != null && obj.has("players")) {
                return obj.get("players").toString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to extract players from roster JSON", e);
        }
        return "[]";
    }
}
