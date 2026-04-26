package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Executes Minecraft commands and typed actions on the client.
 * Called by {@link BridgeHttpServer} on the /command and /action endpoints.
 */
public class CommandExecutor {

    private CommandExecutor() {}

    /**
     * Execute a raw Minecraft command via the client's command handler.
     * Runs on the Minecraft main thread.
     */
    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (command.startsWith("chat:")) {
            String message = command.substring(5).trim();
            if (!message.isEmpty()) {
                sendChat(client, message);
            }
        } else {
            final String cmd = command.startsWith("/") ? command.substring(1) : command;
            client.execute(() -> {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatCommand(cmd);
                }
            });
        }
    }

    /**
     * Execute a typed action (JSON-structured action).
     * Returns a human-readable description of what was sent, or null on failure.
     * For "craft" actions, this enqueues a #task interact command to open the
     * crafting table and sets a post-Baritone action that does the actual crafting
     * via window clicks once the container is open.
     */
    public static String executeTypedJson(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) return null;

        // Parse minimal fields from the action JSON without a full JSON library.
        String type = extractJsonString(actionJson, "type");
        if (type == null || type.isBlank()) return null;

        // ── Craft action: two-phase orchestration ─────────────────────────
        if ("craft".equals(type)) {
            return executeCraftAction(actionJson);
        }

        String command = extractRawBaritoneCommand(actionJson, type);
        if (command != null) {
            execute(command);
            return type + ": " + command;
        }

        // Fallback: try "command" field
        String rawCmd = extractJsonString(actionJson, "command");
        if (rawCmd != null && !rawCmd.isBlank()) {
            execute(rawCmd);
            return type + ": " + rawCmd;
        }

        return type;
    }

    /**
     * Handle the "craft" typed action.
     *
     * Phase 1: Scan for nearest crafting table. If found, send
     *   #task interact <x> <y> <z> and register a post-Baritone callback
     *   that executes the actual crafting via window clicks.
     * Phase 1b: If no crafting table nearby, log failure.
     */
    private static String executeCraftAction(String actionJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return "craft: not connected";
        }

        String itemName = extractJsonString(actionJson, "item");
        if (itemName == null || itemName.isBlank()) {
            return "craft: missing 'item' field";
        }

        String countStr = extractJsonPrimitive(actionJson, "count");
        int count = 1;
        if (countStr != null && !countStr.isBlank()) {
            try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
        }
        if (count < 1) count = 1;

        // Phase 1: Find crafting table and queue interaction
        final BlockPos tablePos = findNearestBlock(client.world, client.player,
                "minecraft:crafting_table", 32);

        if (tablePos == null) {
            boolean hasTableItem = hasItemInInventory(client.player, "minecraft:crafting_table");
            if (!hasTableItem) {
                return "craft: no crafting table in inventory or within 32 blocks";
            }
            // Could place and interact, but that requires Baritone to path to an empty
            // spot and use #task interact on it. For now, tell the agent where.
            return "craft: no crafting table placed within 32 blocks. Place one or move closer to one.";
        }

        final String targetItem = itemName;
        final int targetCount = count;

        // Register the post-Baritone crafting callback
        TaskQueue.getInstance().setPostAction(() -> craftPostAction(targetItem, targetCount));

        // Phase 1: Send #task interact to open the crafting table
        String interactCmd = "#task interact " + tablePos.getX() + " " + tablePos.getY() + " " + tablePos.getZ();
        execute(interactCmd);
        return "craft: opening crafting table at " + tablePos.getX() + " " + tablePos.getY() + " " + tablePos.getZ()
                + " — will craft " + targetCount + "x " + targetItem + " on completion";
    }

    /**
     * Post-Baritone callback: runs after #task interact completes.
     * At this point the crafting table should be open (CraftingScreenHandler).
     */
    private static void craftPostAction(String itemName, int count) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || im == null) return;

        ScreenHandler screen = player.currentScreenHandler;
        if (!(screen instanceof CraftingScreenHandler)) {
            player.sendMessage(
                    net.minecraft.text.Text.literal("[Bridge] Crafting failed: crafting table did not open."),
                    false);
            return;
        }

        int syncId = screen.syncId;

        // Build item identifier
        Identifier itemId = itemName.contains(":")
                ? Identifier.tryParse(itemName)
                : Identifier.of("minecraft", itemName);
        if (itemId == null) {
            player.sendMessage(net.minecraft.text.Text.literal("[Bridge] Invalid item: " + itemName), false);
            return;
        }

        int craftedTotal = 0;
        int maxAttempts = Math.min(count, 64);

        for (int attempt = 0; attempt < maxAttempts && craftedTotal < count; attempt++) {
            // Re-verify crafting table is open
            if (!(player.currentScreenHandler instanceof CraftingScreenHandler)) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Table closed. Crafted " + craftedTotal + "x " + itemName), false);
                return;
            }
            screen = player.currentScreenHandler;
            syncId = screen.syncId;

            // Try auto-fill from recipe book: click the recipe result slot
            // In many CraftingScreenHandler implementations, clicking a recipe
            // in the recipe book auto-fills the grid. The recipe book result
            // slots are typically in the range 10+36 to 10+36+N.
            // We'll try a simpler approach: attempt to click every slot after
            // the player inventory looking for recipe results, OR do manual fill.
            boolean filled = tryFillGridFromInventory(player, im, screen, syncId, itemId);
            if (!filled) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Missing ingredients for " + itemName + ". Crafted " + craftedTotal + "x."), false);
                break;
            }

            // Small delay for server to process
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}

            // Re-verify screen is still open
            if (!(player.currentScreenHandler instanceof CraftingScreenHandler)) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Table closed mid-craft. Crafted " + craftedTotal + "x " + itemName), false);
                return;
            }
            screen = player.currentScreenHandler;
            syncId = screen.syncId;

            // Take result from output slot (slot 0 in CraftingScreenHandler)
            boolean took = takeCraftingResult(im, screen, syncId, itemId);
            if (took) {
                craftedTotal++;
            } else {
                // Output slot empty — ingredients consumed but no result?
                // Recipe might not match; stop.
                break;
            }
        }

        // Close the crafting table
        player.closeHandledScreen();
        player.sendMessage(net.minecraft.text.Text.literal(
                "[Bridge] Crafting complete: " + craftedTotal + "x " + itemName), false);
    }

    /**
     * Try to fill the crafting grid with the ingredients needed for targetItem.
     * Uses a simple approach: compare itemId patterns to known recipes.
     * The actual recipe lookup requires knowing yarn-mapped class names,
     * so we use a fallback: try clicking the recipe book slots.
     *
     * Returns true if ingredients were placed (or recipe book auto-filled).
     */
    private static boolean tryFillGridFromInventory(ClientPlayerEntity player,
                                                     ClientPlayerInteractionManager im,
                                                     ScreenHandler screen,
                                                     int syncId,
                                                     Identifier targetId) {
        // Strategy 1: Try clicking recipe book output slots
        // In CraftingScreenHandler, recipe book results are after player inventory.
        // If we click one and the grid fills, we win.
        int totalSlots = screen.slots.size();
        // Player inventory typically starts at slot 10 (for CraftingScreenHandler),
        // with 36 player inv slots, so recipe book starts at ~46.
        int recipeBookStart = Math.max(10, totalSlots - 30); // heuristic start
        for (int slotIdx = recipeBookStart; slotIdx < totalSlots; slotIdx++) {
            var slot = screen.getSlot(slotIdx);
            if (slot == null || !slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            if (stack.getItem().toString().equals(targetId.toString())) {
                // Click this recipe book result — should auto-fill grid
                im.clickSlot(syncId, slotIdx, 0, SlotActionType.PICKUP, player);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                return true;
            }
        }

        // Strategy 2: Manual fill using known recipe patterns
        // For common items, try to fill based on known recipes.
        // This only works for simple recipes, but covers the most common cases.
        PlayerInventory inv = player.getInventory();

        // Known recipes: planks (from log), sticks (from planks), crafting_table (from planks)
        String targetName = targetId.toString();
        if (targetName.equals("minecraft:stick")) {
            return fillStickRecipe(inv, im, screen, syncId);
        } else if (targetName.equals("minecraft:crafting_table")) {
            return fillCraftingTableRecipe(inv, im, screen, syncId);
        } else if (targetName.endsWith("_planks")) {
            return fillPlanksRecipe(inv, im, screen, syncId, targetName);
        }

        // Unknown recipe — give up
        player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Unknown recipe for " + targetName + ". Use recipe book or craft manually."),
                false);
        return false;
    }

    private static boolean fillStickRecipe(PlayerInventory inv, ClientPlayerInteractionManager im,
                                            ScreenHandler screen, int syncId) {
        // 2 planks → 4 sticks. Place planks vertically (slots 1, 5)
        int plankSlot = findItemInInventory(inv, item -> item.endsWith("_planks"));
        if (plankSlot < 0) return false;
        int screenInvSlot = 10 + plankSlot;
        // Pickup planks
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Split into half: right-click slot 1 (grid)
        im.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Place remaining in slot 5
        im.clickSlot(syncId, 5, 1, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Return any leftover
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        return true;
    }

    private static boolean fillCraftingTableRecipe(PlayerInventory inv, ClientPlayerInteractionManager im,
                                                    ScreenHandler screen, int syncId) {
        // 4 planks → 1 crafting table. Fill 2x2 (slots 1,2,4,5)
        int plankSlot = findItemInInventory(inv, item -> item.endsWith("_planks"));
        if (plankSlot < 0) return false;
        int screenInvSlot = 10 + plankSlot;
        // Pickup
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Place 1 in each of 4 slots
        int[] gridSlots = {1, 2, 4, 5};
        for (int gs : gridSlots) {
            im.clickSlot(syncId, gs, 1, SlotActionType.PICKUP, null);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        // Return any leftover
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        return true;
    }

    private static boolean fillPlanksRecipe(PlayerInventory inv, ClientPlayerInteractionManager im,
                                             ScreenHandler screen, int syncId, String targetName) {
        // 1 log → 4 planks. Place log in slot 1.
        String logType = targetName.replace("_planks", "_log");
        int logSlot = findItemByExactName(inv, logType);
        if (logSlot < 0) {
            // Try stripped variant
            logType = "minecraft:stripped_" + targetName.substring("minecraft:".length()).replace("_planks", "_log");
            logSlot = findItemByExactName(inv, logType);
        }
        if (logSlot < 0) {
            // Try just matching anything ending in _log
            logSlot = findItemInInventory(inv, item -> item.endsWith("_log"));
        }
        if (logSlot < 0) return false;
        int screenInvSlot = 10 + logSlot;
        // Pickup log
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Place 1 in slot 1
        im.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        // Return any leftover
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, null);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        return true;
    }

    @FunctionalInterface
    private interface ItemPredicate {
        boolean matches(String itemId);
    }

    private static int findItemInInventory(PlayerInventory inv, ItemPredicate pred) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                String id = stack.getItem().toString();
                if (pred.matches(id)) return i;
            }
        }
        return -1;
    }

    private static int findItemByExactName(PlayerInventory inv, String exactId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().toString().equals(exactId)) return i;
        }
        return -1;
    }

    /**
     * Take the crafted result from the output slot and place it in inventory.
     * Returns true if an item was successfully taken.
     */
    private static boolean takeCraftingResult(ClientPlayerInteractionManager im, ScreenHandler screen,
                                               int syncId, Identifier expectedItem) {
        var resultSlot = screen.getSlot(0);
        if (resultSlot == null || !resultSlot.hasStack()) {
            return false;
        }

        ItemStack resultStack = resultSlot.getStack();
        if (resultStack.isEmpty()
                || !resultStack.getItem().toString().equals(expectedItem.toString())) {
            return false;
        }

        // Shift-click the result to move it to inventory (quick move)
        im.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, null);
        try { Thread.sleep(80); } catch (InterruptedException ignored) {}

        return true;
    }

    // ─── Block scanning ─────────────────────────────────────────────────────

    /**
     * Find the nearest block of a given type within range.
     * Scans loaded chunk blocks.
     */
    static BlockPos findNearestBlock(ClientWorld world, ClientPlayerEntity player,
                                     String blockId, int range) {
        BlockPos playerPos = player.getBlockPos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    net.minecraft.block.BlockState state = world.getBlockState(mutable);
                    if (state.isAir()) continue;
                    String id = state.getBlock().toString();
                    if (id.equals(blockId)) {
                        double distSq = mutable.getSquaredDistance(playerPos);
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq;
                            nearest = mutable.toImmutable();
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Check if the player has at least one of the given item in their inventory.
     */
    private static boolean hasItemInInventory(ClientPlayerEntity player, String itemId) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().toString().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    // ─── Capabilities / commands JSON ────────────────────────────────────────

    /**
     * Return JSON describing the mod's capabilities.
     */
    public static String capabilitiesJson() {
        return "{"
            + "\"supports_typed_actions\":true,"
            + "\"default_provider\":\"baritone_chat\","
            + "\"providers\":[\"baritone_chat\"],"
            + "\"action_types\":[\"move\",\"mine\",\"follow\",\"cancel\",\"raw_command\",\"craft\"],"
            + "\"version\":\"1.0.0\""
            + "}";
    }

    /**
     * Return JSON with a static list of discoverable commands.
     * In a full implementation this could scan Minecraft's command registry.
     */
    public static String discoverCommandsJson() {
        return "["
            + "\"#goto x y z\","
            + "\"#mine count block\","
            + "\"#follow player <name>\","
            + "\"#cancel\","
            + "\"chat: <message>\""
            + "]";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Raw command helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void sendChat(MinecraftClient client, String message) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        // Track sent messages so StateCollector can filter echo
        StateCollector.trackSentChat(message);

        // Send via chat message packet
        client.execute(() -> {
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendChatMessage(message);
            }
        });
    }

    /**
     * Convert a typed action into a Baritone chat command if applicable.
     */
    private static String extractRawBaritoneCommand(String json, String type) {
        switch (type) {
            case "move": {
                String x = extractJsonPrimitive(json, "x");
                String y = extractJsonPrimitive(json, "y");
                String z = extractJsonPrimitive(json, "z");
                if (x != null && y != null && z != null) {
                    return "#goto " + x + " " + y + " " + z;
                }
                return null;
            }
            case "mine": {
                String target = extractJsonString(json, "target");
                String count = extractJsonPrimitive(json, "count");
                String secondary = extractJsonString(json, "secondaryTarget");
                if (target == null) return null;
                StringBuilder sb = new StringBuilder("#mine ");
                sb.append(count != null ? count : "64").append(" ").append(target);
                if (secondary != null) sb.append(" ").append(secondary);
                return sb.toString();
            }
            case "follow": {
                String target = extractJsonString(json, "target");
                if (target != null) return "#follow player " + target;
                return null;
            }
            case "cancel":
                return "#cancel";
            case "raw_command":
                return extractJsonString(json, "command");
            default:
                return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // JSON extraction helpers (no external dependencies)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Simple JSON string value extractor (no external dependencies).
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"')  { break; }
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start + 1, end)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"");
    }

    /**
     * Extract a primitive (number, boolean, null) — no surrounding quotes.
     */
    private static String extractJsonPrimitive(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            return extractJsonString(json, key);
        }
        int end = i;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            end++;
        }
        return (end > i) ? json.substring(i, end) : null;
    }
}