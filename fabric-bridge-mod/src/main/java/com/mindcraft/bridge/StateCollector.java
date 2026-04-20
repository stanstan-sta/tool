package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects the current player state into a JSON string.
 *
 * State includes: position, health, hunger, saturation, dimension, game mode,
 * inventory, and nearby players.  Chat messages received since the last /state
 * call are appended and then cleared (so callers see only new messages).
 */
public class StateCollector {

    /** Thread-safe queue of chat messages received since the last /state poll. */
    static final Queue<String> chatQueue = new ConcurrentLinkedQueue<>();
    static final int MAX_CHAT_QUEUE = 200;
    private static String lastStateHash = "";
    private static final AtomicLong stateSeq = new AtomicLong(0);

    /**
     * Register the Fabric chat-receive event listener.
     * Called once from {@link MindcraftBridgeMod#onInitializeClient()}.
     */
    public static void registerEvents() {
        // Listen for incoming chat messages and queue them for the Node.js agent.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                while (chatQueue.size() >= MAX_CHAT_QUEUE) {
                    chatQueue.poll();
                }
                chatQueue.add(message.getString());
            }
        });
    }

    /** Build and return the complete state as a JSON string. */
    public static String collect(Long sinceSeq) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player == null || client.world == null) {
            return "{\"connected\":false}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"connected\":true,");

        // Position
        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        sb.append(String.format("\"x\":%d,\"y\":%d,\"z\":%d,", x, y, z));

        // Vitals
        sb.append(String.format("\"health\":%.1f,", player.getHealth()));
        sb.append(String.format("\"hunger\":%d,", player.getHungerManager().getFoodLevel()));
        sb.append(String.format("\"saturation\":%.1f,", player.getHungerManager().getSaturationLevel()));

        // World info
        String dim = client.world.getRegistryKey().getValue().toString();
        sb.append(String.format("\"dimension\":\"%s\",", dim));

        String mode = player.interactionManager != null
                ? player.interactionManager.getCurrentGameMode().getName()
                : "unknown";
        sb.append(String.format("\"gameMode\":\"%s\",", mode));

        // Inventory
        StringBuilder invSig = new StringBuilder();
        sb.append("\"inventory\":[");
        PlayerInventory inv = player.getInventory();
        boolean firstItem = true;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!firstItem) sb.append(",");
            firstItem = false;
            String itemId = stack.getItem().toString(); // "minecraft:oak_log"
            invSig.append(i).append(':').append(itemId).append(':').append(stack.getCount()).append(';');
            sb.append(String.format("{\"slot\":%d,\"item\":\"%s\",\"count\":%d}",
                    i, escape(itemId), stack.getCount()));
        }
        sb.append("],");

        // Nearby players (within 64 blocks)
        StringBuilder playersSig = new StringBuilder();
        sb.append("\"nearby_players\":[");
        Box searchBox = player.getBoundingBox().expand(64);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox,
                e -> e instanceof net.minecraft.entity.player.PlayerEntity);
        boolean firstPlayer = true;
        for (Entity e : entities) {
            if (!firstPlayer) sb.append(",");
            firstPlayer = false;
            playersSig.append(e.getName().getString()).append(';');
            sb.append("\"").append(escape(e.getName().getString())).append("\"");
        }
        sb.append("],");

        // Nearby non-player entities with precise coordinates and velocity
        StringBuilder entitiesSig = new StringBuilder();
        sb.append("\"nearby_entities\":[");
        List<Entity> nearbyEntities = client.world.getOtherEntities(player, searchBox,
                e -> !(e instanceof net.minecraft.entity.player.PlayerEntity));
        boolean firstEntity = true;
        for (Entity e : nearbyEntities) {
            if (!firstEntity) sb.append(",");
            firstEntity = false;
            Vec3d v = e.getVelocity();
            double ex = e.getX();
            double ey = e.getY();
            double ez = e.getZ();
            entitiesSig.append(e.getType().toString()).append('@')
                    .append(round(ex)).append(',').append(round(ey)).append(',').append(round(ez)).append(';');
            sb.append("{")
              .append("\"name\":\"").append(escape(e.getName().getString())).append("\",")
              .append("\"type\":\"").append(escape(e.getType().toString())).append("\",")
              .append("\"x\":").append(round(ex)).append(",")
              .append("\"y\":").append(round(ey)).append(",")
              .append("\"z\":").append(round(ez)).append(",")
              .append("\"vx\":").append(round(v.x)).append(",")
              .append("\"vy\":").append(round(v.y)).append(",")
              .append("\"vz\":").append(round(v.z))
              .append("}");
        }
        sb.append("],");

        String stateHash = x + "|" + y + "|" + z + "|" + player.getHealth() + "|" +
                player.getHungerManager().getFoodLevel() + "|" + dim + "|" + mode + "|" +
                invSig + "|" + playersSig + "|" + entitiesSig;
        if (!stateHash.equals(lastStateHash)) {
            lastStateHash = stateHash;
            stateSeq.incrementAndGet();
        }
        long seq = stateSeq.get();

        if (sinceSeq != null && sinceSeq == seq && chatQueue.isEmpty()) {
            return "{\"connected\":true,\"seq\":" + seq + ",\"unchanged\":true,\"chat\":[]}";
        }

        // Chat messages received since last poll — drain the queue
        sb.append("\"chat\":[");
        boolean firstChat = true;
        String msg;
        while ((msg = chatQueue.poll()) != null) {
            if (!firstChat) sb.append(",");
            firstChat = false;
            sb.append("\"").append(escape(msg)).append("\"");
        }
        sb.append("]");
        sb.append(",\"seq\":").append(seq);
        sb.append(",\"unchanged\":false");

        sb.append("}");
        return sb.toString();
    }

    /** Minimal JSON string escape. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
