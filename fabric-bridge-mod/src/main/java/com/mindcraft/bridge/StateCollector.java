package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
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
 * inventory, nearby players, and structured chat events. Chat messages received
 * since the last /state call are appended and then cleared (so callers see only
 * new messages).
 */
public class StateCollector {

            static final Queue<ChatEvent> chatQueue = new ConcurrentLinkedQueue<>();
    static final int MAX_CHAT_QUEUE = 200;
    private static String lastStateHash = "";
    private static final AtomicLong stateSeq = new AtomicLong(0);

    // Track recently-sent chat messages so they can be excluded from the
    // GAME listener, which otherwise echoes them back and causes a loop.
    private static final Queue<String> recentSentChats = new ConcurrentLinkedQueue<>();
    private static final int MAX_SENT_TRACK = 16;

    public static void trackSentChat(String message) {
        while (recentSentChats.size() >= MAX_SENT_TRACK) {
            recentSentChats.poll();
        }
        recentSentChats.add(message);
    }

    /**
     * Register the Fabric chat-receive event listener.
     * Called once from {@link MindcraftBridgeMod#onInitializeClient()}.
     */
    public static void registerEvents() {
        // Listen for incoming chat messages and queue them for the Node.js agent.
        // CHAT covers normal player chat, GAME covers overlay/system messages.
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            while (chatQueue.size() >= MAX_CHAT_QUEUE) {
                chatQueue.poll();
            }
            String senderName = null;
            if (sender != null) {
                try {
                    java.lang.reflect.Method method = sender.getClass().getMethod("getName");
                    Object nameObj = method.invoke(sender);
                    if (nameObj != null) {
                        senderName = nameObj.toString();
                    }
                } catch (Throwable ignored) {
                    senderName = sender.toString();
                }
            }
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client != null ? client.player : null;
            if (senderName != null && player != null) {
                String selfName = player.getName().getString();
                if (senderName.equals(selfName)) {
                    return;
                }
            }
            chatQueue.add(new ChatEvent("player", message.getString(), senderName));
        });

                        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            while (chatQueue.size() >= MAX_CHAT_QUEUE) {
                chatQueue.poll();
            }
            String content = message.getString();
            // Skip messages that match a recently sent chat to prevent the
            // echo loop: Node sends "chat:..." → mod sends via network →
            // GAME listener fires with the same text → Node picks it up →
            // LLM responds → repeat.
            if (content != null && recentSentChats.contains(content)) {
                return;
            }
            String type = overlay ? "system" : "player";
            chatQueue.add(new ChatEvent(type, content, null));
        });
    }

    /** Build and return the complete state as a JSON string. */
    public static String collect(Long sinceSeq, boolean includeSurfaceMap, int surfaceRadius) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player == null || client.world == null) {
            return "{\"connected\":false}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"connected\":true,");
        sb.append("\"player_name\":\"").append(escape(player.getName().getString())).append("\",");

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

        // Game mode — GameMode.getName() was removed in 1.21.11;
        // use the standard Java enum name() and lower-case it instead.
        String mode = (client.interactionManager != null && client.interactionManager.getCurrentGameMode() != null)
                ? client.interactionManager.getCurrentGameMode().name().toLowerCase()
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

        if (includeSurfaceMap) {
            appendSurfaceMap(sb, player, client.world, surfaceRadius);
        }

        String stateHash = x + "|" + y + "|" + z + "|" + player.getHealth() + "|" +
                player.getHungerManager().getFoodLevel() + "|" + dim + "|" + mode + "|" +
                invSig + "|" + playersSig + "|" + entitiesSig;
        if (!stateHash.equals(lastStateHash)) {
            lastStateHash = stateHash;
            stateSeq.incrementAndGet();
        }
        long seq = stateSeq.get();

        if (sinceSeq != null && sinceSeq == seq && chatQueue.isEmpty()) {
            return "{\"connected\":true,\"seq\":" + seq + ",\"player_name\":\"" + escape(player.getName().getString()) + "\",\"unchanged\":true,\"chat\":[],\"chat_events\":[]}";
        }

        // Chat messages received since last poll — drain the queue into both legacy and structured arrays.
        StringBuilder chatArray = new StringBuilder();
        StringBuilder eventArray = new StringBuilder();
        chatArray.append("[");
        eventArray.append("[");
        boolean firstChat = true;
        boolean firstEvent = true;
        ChatEvent event;
        while ((event = chatQueue.poll()) != null) {
            if (!firstChat) chatArray.append(",");
            firstChat = false;
            chatArray.append("\"").append(escape(event.message)).append("\"");

            if (!firstEvent) eventArray.append(",");
            firstEvent = false;
            eventArray.append(event.toJson());
        }
        chatArray.append("]");
        eventArray.append("]");

        sb.append("\"chat\":").append(chatArray).append(",");
        sb.append("\"chat_events\":").append(eventArray);
        sb.append(",\"seq\":").append(seq);
        sb.append(",\"unchanged\":false");

        sb.append("}");
        return sb.toString();
    }

    private static void appendSurfaceMap(StringBuilder sb, ClientPlayerEntity player, ClientWorld world, int radius) {
        int centerX = (int) Math.floor(player.getX());
        int centerY = (int) Math.floor(player.getY());
        int centerZ = (int) Math.floor(player.getZ());
        sb.append("\"surface_map\":{");
        sb.append("\"center\":{");
        sb.append("\"x\":").append(centerX).append(",");
        sb.append("\"y\":").append(centerY).append(",");
        sb.append("\"z\":").append(centerZ).append("},");
        sb.append("\"radius\":").append(radius).append(",");
        sb.append("\"cells\":[");

        boolean firstCell = true;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (!firstCell) sb.append(",");
                firstCell = false;
                int x = centerX + dx;
                int z = centerZ + dz;
                SurfaceCell cell = findTopSurfaceBlock(world, x, z, centerY + Math.min(radius, 32));
                sb.append("{");
                sb.append("\"x\":").append(cell.x).append(",");
                sb.append("\"z\":").append(cell.z).append(",");
                sb.append("\"y\":").append(cell.y).append(",");
                sb.append("\"block\":\"").append(escape(cell.block)).append("\"");
                sb.append("}");
            }
        }
        sb.append("]},");
    }

    private static class SurfaceCell {
        final int x;
        final int y;
        final int z;
        final String block;

        SurfaceCell(int x, int y, int z, String block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }

    private static SurfaceCell findTopSurfaceBlock(ClientWorld world, int x, int z, int startY) {
        for (int y = startY; y >= 0; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (world == null) break;
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            if (state == null) continue;
            if (!state.isAir()) {
                return new SurfaceCell(x, y, z, state.getBlock().toString());
            }
        }
        return new SurfaceCell(x, 0, z, "minecraft:air");
    }

    /** Minimal JSON string escape. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static class ChatEvent {
        final String type;
        final String message;
        final String sender;

        ChatEvent(String type, String message, String sender) {
            this.type = type;
            this.message = message;
            this.sender = sender;
        }

        String toJson() {
            return "{\"type\":\"" + escape(type) + "\",\"message\":\"" + escape(message) + "\",\"sender\":" + (sender == null ? "null" : "\"" + escape(sender) + "\"") + "}";
        }
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}