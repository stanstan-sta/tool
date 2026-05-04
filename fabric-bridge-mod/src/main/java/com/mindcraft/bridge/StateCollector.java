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

    private static void routeBaritoneToTaskQueue(String content) {
        String clean = stripChatFormatting(content);
        if (clean == null || !clean.contains("[Baritone]")) return;
        if (clean.contains("All queued tasks complete")) {
            TaskQueue.getInstance().onBaritoneComplete();
        } else if (clean.contains("Task failed:")) {
            String reason = clean.substring(clean.indexOf("Task failed:") + "Task failed:".length()).trim();
            TaskQueue.getInstance().onBaritoneFailed(reason);
        }
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
            String content = message.getString();

            // Route Baritone status messages even when they come through the CHAT channel
            // (some Baritone builds emit them as player chat rather than system overlay).
            String cleanContent = stripChatFormatting(content);
            if (cleanContent != null && cleanContent.contains("[Baritone]")) {
                routeBaritoneToTaskQueue(cleanContent);
                chatQueue.add(new ChatEvent("baritone_queue", cleanContent, null));
                return;
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
            // Detect Baritone task-queue status messages and route to TaskQueue.
            // The contract: Baritone logs "[Baritone] All queued tasks complete"
            // on success and "[Baritone] Task failed: <label> - <outcome>" on failure.
            String cleanContent = stripChatFormatting(content);
            if (cleanContent != null && cleanContent.contains("[Baritone]")) {
                routeBaritoneToTaskQueue(cleanContent);
                chatQueue.add(new ChatEvent("baritone_queue", cleanContent, null));
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
            String itemId = getItemId(stack); // e.g. "minecraft:oak_log"
            invSig.append(i).append(':').append(itemId).append(':').append(stack.getCount()).append(';');
            sb.append(String.format("{\"slot\":%d,\"item\":\"%s\",\"count\":%d}",
                    i, escape(itemId), stack.getCount()));
        }
        sb.append("],");

        // Craftable items based on current inventory
        sb.append("\"craftable\":");
        appendCraftable(sb, inv);
        sb.append(",");

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

        // Append task queue state
        TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
        if (!"idle".equals(qs.status()) && !"disabled".equals(qs.status())) {
            sb.append(",\"queue\":{");
            sb.append("\"status\":\"").append(escape(qs.status())).append("\",");
            sb.append("\"active\":").append(qs.active() == null ? "null" : "\"" + escape(qs.active()) + "\"").append(",");
            if (qs.kind() != null) {
                sb.append("\"kind\":\"").append(escape(qs.kind())).append("\",");
            }
            if (qs.completion() != null) {
                sb.append("\"completion\":\"").append(escape(qs.completion())).append("\",");
            }
            sb.append("\"pending\":").append(qs.pending()).append(",");
            sb.append("\"paused\":").append(qs.paused());
            if (qs.lastFailure() != null) {
                sb.append(",\"lastFailure\":\"").append(escape(qs.lastFailure())).append("\"");
            }
            sb.append("}");
        }

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
                return new SurfaceCell(x, y, z, getBlockId(state.getBlock()));
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

    private static String stripChatFormatting(String s) {
        if (s == null) return null;
        return s.replaceAll("\\u00A7[0-9A-FK-ORa-fk-or]", "");
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

    /**
     * Append craftable items to the JSON builder based on current inventory.
     * Uses simple pattern matching — no RecipeManager needed.
     */
    private static void appendCraftable(StringBuilder sb, PlayerInventory inv) {
        sb.append("[");
        boolean first = true;

        // Check each known craftable item
        boolean hasLog = hasItemMatching(inv, id -> id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_hyphae"));
        boolean hasPlank = hasItemMatching(inv, id -> id.endsWith("_planks"));
        boolean hasStick = hasItemNamed(inv, "minecraft:stick");
        int plankCount = countItemMatching(inv, id -> id.endsWith("_planks"));
        int stickCount = countPlanksEquivalent(inv);

        if (hasLog) {
            // Can make planks from any log
            String woodType = findWoodType(inv);
            String planksName = woodType != null ? woodType + "_planks" : "planks";
            appendItem(sb, planksName, first);
            first = false;
        }

        if (hasPlank || hasLog) {
            appendItem(sb, "stick", first);
            first = false;
        }

        if (plankCount >= 4 || (hasLog && hasPlank)) {
            appendItem(sb, "crafting_table", first);
            first = false;
        }

        // Wooden tools (need planks + sticks, or enough planks to make sticks)
        if (plankCount + stickCount >= 3) {
            appendItem(sb, "wooden_pickaxe", first); first = false;
            appendItem(sb, "wooden_axe", first); first = false;
        }
        if (plankCount + stickCount >= 2) {
            appendItem(sb, "wooden_shovel", first); first = false;
            appendItem(sb, "wooden_sword", first); first = false;
            appendItem(sb, "wooden_hoe", first); first = false;
        }

        sb.append("]");
    }

    private static void appendItem(StringBuilder sb, String name, boolean first) {
        if (!first) sb.append(",");
        sb.append("\"").append(name).append("\"");
    }

    private static boolean hasItemMatching(PlayerInventory inv, java.util.function.Predicate<String> predicate) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && predicate.test(getItemId(stack))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasItemNamed(PlayerInventory inv, String exactId) {
        return hasItemMatching(inv, id -> id.equals(exactId));
    }

    private static int countItemMatching(PlayerInventory inv, java.util.function.Predicate<String> predicate) {
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && predicate.test(getItemId(stack))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countPlanksEquivalent(PlayerInventory inv) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = getItemId(stack);
            if (id.equals("minecraft:stick")) {
                total += stack.getCount() * 2; // 2 planks = 4 sticks → 1 plank = 2 stick equivalent
            } else if (id.endsWith("_planks")) {
                total += stack.getCount();
            } else if (id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_hyphae")) {
                total += stack.getCount() * 4; // 1 log = 4 planks
            }
        }
        return total;
    }

    private static String findWoodType(PlayerInventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = getItemId(stack);
            if (id.endsWith("_planks")) {
                return id.substring("minecraft:".length()).replace("_planks", "");
            }
            if (id.endsWith("_log")) {
                return id.substring("minecraft:".length()).replace("_log", "");
            }
        }
        return null;
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ─── ID helpers ──────────────────────────────────────────────────────────

    /** Strip Item{} wrapper from Item.toString() so "Item{minecraft:stick}" → "minecraft:stick". */
    private static String getItemId(ItemStack stack) {
        if (stack.isEmpty()) return "";
        String s = stack.getItem().toString();
        if (s.startsWith("Item{") && s.endsWith("}")) {
            return s.substring(5, s.length() - 1);
        }
        return s;
    }

    /** Strip Block{} wrapper from Block.toString() so "Block{minecraft:oak_log}" → "minecraft:oak_log". */
    private static String getBlockId(net.minecraft.block.Block block) {
        String s = block.toString();
        if (s.startsWith("Block{") && s.endsWith("}")) {
            return s.substring(6, s.length() - 1);
        }
        return s;
    }
}
