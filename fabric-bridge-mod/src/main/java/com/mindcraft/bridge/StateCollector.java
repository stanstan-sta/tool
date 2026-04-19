package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    /**
     * Register the Fabric chat-receive event listener.
     * Called once from {@link MindcraftBridgeMod#onInitializeClient()}.
     */
    public static void registerEvents() {
        // Listen for incoming chat messages and queue them for the Node.js agent.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chatQueue.add(message.getString());
            }
        });
    }

    /** Build and return the complete state as a JSON string. */
    public static String collect() {
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
        sb.append("\"inventory\":[");
        PlayerInventory inv = player.getInventory();
        boolean firstItem = true;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!firstItem) sb.append(",");
            firstItem = false;
            String itemId = stack.getItem().toString(); // "minecraft:oak_log"
            sb.append(String.format("{\"slot\":%d,\"item\":\"%s\",\"count\":%d}",
                    i, escape(itemId), stack.getCount()));
        }
        sb.append("],");

        // Nearby players (within 64 blocks)
        sb.append("\"nearby_players\":[");
        Box searchBox = player.getBoundingBox().expand(64);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox,
                e -> e instanceof net.minecraft.entity.player.PlayerEntity);
        boolean firstPlayer = true;
        for (Entity e : entities) {
            if (!firstPlayer) sb.append(",");
            firstPlayer = false;
            sb.append("\"").append(escape(e.getName().getString())).append("\"");
        }
        sb.append("],");

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
}
