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
        } else if (command.startsWith("#")) {
            // Baritone commands (e.g. #task interact, #goto, #mine, #cancel)
            // MUST be sent as chat messages, NOT as slash commands.
            // Sending them via sendChatCommand() causes the server to reject
            // them as unknown commands (e.g. "/task interact" is not a real
            // Minecraft command — it needs to reach Baritone's chat parser).
            final String msg = command.trim();
            client.execute(() -> {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatMessage(msg);
                }
            });
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
     * All crafting uses the crafting table (3×3 grid) via Baritone: find the
     * nearest crafting table, tell Baritone to interact with it via
     * #task interact <x> <y> <z>, then register a post-Baritone callback
     * that fills the 3×3 grid and takes the result once the GUI is open.
     *
     * 2×2 inventory crafting was attempted and found unreliable: clickSlot
     * on PlayerScreenHandler without a visible GUI does not sync cursor
     * state with the server correctly. All recipes go through the 3×3 table.
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

        // Find nearest crafting table
        client.player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Searching for crafting table..."),
                false);
        final BlockPos tablePos = findNearestBlock(client.world, client.player,
                "minecraft:crafting_table", 32);

        if (tablePos == null) {
            boolean hasTableItem = hasItemInInventory(client.player, "minecraft:crafting_table");
            if (!hasTableItem) {
                // Send chat message so the LLM sees this error in next /state poll
                client.player.sendMessage(
                        net.minecraft.text.Text.literal("[Bridge] No crafting table found. You need a crafting table to craft items. Craft one from 4 planks first."),
                        false);
                return "craft: no crafting table in inventory or within 32 blocks. You need a crafting table to craft items.";
            }
            client.player.sendMessage(
                    net.minecraft.text.Text.literal("[Bridge] Crafting table is in your inventory but not placed. Place it first, then retry crafting."),
                    false);
            return "craft: crafting table found in inventory but not placed. Place it first, then retry.";
        }

        client.player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Found crafting table at " + tablePos.getX() + " " + tablePos.getY() + " " + tablePos.getZ()),
                false);

        final String targetItem = itemName;
        final int targetCount = count;

        // Register the post-Baritone crafting callback
        TaskQueue.getInstance().setPostAction(() -> craftPostAction(targetItem, targetCount));

        // Send #task interact to open the crafting table
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

        // Wait up to 1 second for CraftingScreenHandler to appear (server packet race)
        ScreenHandler screen = null;
        for (int wait = 0; wait < 20; wait++) {
            screen = player.currentScreenHandler;
            if (screen instanceof CraftingScreenHandler) break;
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        if (!(screen instanceof CraftingScreenHandler)) {
            player.sendMessage(
                    net.minecraft.text.Text.literal("[Bridge] Crafting failed: crafting table did not open."),
                    false);
            return;
        }

        player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Crafting table opened. Starting craft of " + count + "x " + itemName + "..."),
                false);

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

            // Try to fill the grid manually
            boolean filled = tryFillGridFromInventory(player, im, screen, syncId, itemId);
            if (!filled) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Failed to fill recipe for " + itemName + ". Crafted " + craftedTotal + "x."), false);
                break;
            }
            player.sendMessage(net.minecraft.text.Text.literal(
                    "[Bridge] Recipe filled, taking result..."), false);

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
            int resultCount = takeCraftingResult(player, im, screen, syncId, itemId);
            if (resultCount > 0) {
                craftedTotal += resultCount;
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Took result (" + resultCount + "). Total crafted: " + craftedTotal + "x " + itemName), false);
            } else {
                // Output slot empty — ingredients consumed but no result?
                // Recipe might not match; stop.
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] No result in output slot. Stopping."), false);
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
     * Returns true if ingredients were placed.
     */
    private static boolean tryFillGridFromInventory(ClientPlayerEntity player,
                                                     ClientPlayerInteractionManager im,
                                                     ScreenHandler screen,
                                                     int syncId,
                                                     Identifier targetId) {
        String targetName = targetId.toString();
        player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Attempting recipe for " + targetName + "..."),
                false);
        if (targetName.equals("minecraft:stick")) {
            return fillStickRecipe(player, im, screen, syncId);
        } else if (targetName.equals("minecraft:crafting_table")) {
            return fillCraftingTableRecipe(player, im, screen, syncId);
        } else if (targetName.endsWith("_planks")) {
            return fillPlanksRecipe(player, im, screen, syncId, targetName);
        }

        player.sendMessage(
                net.minecraft.text.Text.literal("[Bridge] Unknown recipe for " + targetName + ". Use recipe book or craft manually."),
                false);
        return false;
    }

    private static boolean fillStickRecipe(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                            ScreenHandler screen, int syncId) {
        // 2 planks → 4 sticks. Place planks vertically (slots 1, 5)
        int plankSlot = findItemInInventory(player.getInventory(), item -> item.endsWith("_planks"));
        if (plankSlot < 0) {
            player.sendMessage(net.minecraft.text.Text.literal("[Bridge] No planks found for stick recipe."), false);
            return false;
        }
        int screenInvSlot = invSlotToScreenSlot(plankSlot);
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        im.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        im.clickSlot(syncId, 5, 1, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        player.sendMessage(net.minecraft.text.Text.literal("[Bridge] Filled stick recipe."), false);
        return true;
    }

    private static boolean fillCraftingTableRecipe(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                                    ScreenHandler screen, int syncId) {
        // 4 planks → 1 crafting table. Fill 2x2 (slots 1,2,4,5)
        int plankSlot = findItemInInventory(player.getInventory(), item -> item.endsWith("_planks"));
        if (plankSlot < 0) {
            player.sendMessage(net.minecraft.text.Text.literal("[Bridge] No planks found for crafting table recipe."), false);
            return false;
        }
        int screenInvSlot = invSlotToScreenSlot(plankSlot);
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        int[] gridSlots = {1, 2, 4, 5};
        for (int gs : gridSlots) {
            im.clickSlot(syncId, gs, 1, SlotActionType.PICKUP, player);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        player.sendMessage(net.minecraft.text.Text.literal("[Bridge] Filled crafting table recipe."), false);
        return true;
    }

    private static boolean fillPlanksRecipe(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                             ScreenHandler screen, int syncId, String targetName) {
        // 1 log → 4 planks. Place log in slot 1.
        PlayerInventory inv = player.getInventory();
        String logType = targetName.replace("_planks", "_log");
        int logSlot = findItemByExactName(inv, logType);
        if (logSlot < 0) {
            logType = "minecraft:stripped_" + targetName.substring("minecraft:".length()).replace("_planks", "_log");
            logSlot = findItemByExactName(inv, logType);
        }
        if (logSlot < 0) {
            logSlot = findItemInInventory(inv, item -> item.endsWith("_log"));
        }
        if (logSlot < 0) {
            player.sendMessage(net.minecraft.text.Text.literal("[Bridge] No log found for planks recipe."), false);
            return false;
        }
        int screenInvSlot = invSlotToScreenSlot(logSlot);
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        im.clickSlot(syncId, 1, 1, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        player.sendMessage(net.minecraft.text.Text.literal("[Bridge] Filled planks recipe."), false);
        return true;
    }

    // ─── Inventory utilities ─────────────────────────────────────────────────

    @FunctionalInterface
    private interface ItemPredicate {
        boolean matches(String itemId);
    }

    private static int findItemInInventory(PlayerInventory inv, ItemPredicate pred) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                if (pred.matches(getItemId(stack))) return i;
            }
        }
        return -1;
    }

    private static int findItemByExactName(PlayerInventory inv, String exactId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && getItemId(stack).equals(exactId)) return i;
        }
        return -1;
    }

    /**
     * Take the crafted result from the output slot and place it in inventory.
     * Returns the number of items taken, or 0 on failure.
     */
    private static int takeCraftingResult(ClientPlayerEntity player, ClientPlayerInteractionManager im, ScreenHandler screen,
                                               int syncId, Identifier expectedItem) {
        var resultSlot = screen.getSlot(0);
        if (resultSlot == null || !resultSlot.hasStack()) {
            player.sendMessage(net.minecraft.text.Text.literal("[Bridge] Result slot empty."), false);
            return 0;
        }

        ItemStack resultStack = resultSlot.getStack();
        if (resultStack.isEmpty()
                || !getItemId(resultStack).equals(expectedItem.toString())) {
            player.sendMessage(net.minecraft.text.Text.literal(
                "[Bridge] Result mismatch: expected " + expectedItem + ", got " + getItemId(resultStack)), false);
            return 0;
        }

        int count = resultStack.getCount();

        // Shift-click the result to move it to inventory (quick move)
        im.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
        try { Thread.sleep(80); } catch (InterruptedException ignored) {}

        return count;
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
                    if (getBlockId(state.getBlock()).equals(blockId)) {
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
            if (!stack.isEmpty() && getItemId(stack).equals(itemId)) {
                return true;
            }
        }
        return false;
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

    /** Convert PlayerInventory slot index (0-35) to CraftingScreenHandler slot index. */
    private static int invSlotToScreenSlot(int invSlot) {
        if (invSlot < 9) return 37 + invSlot;  // hotbar  → slots 37-45
        return 10 + (invSlot - 9);              // main inv → slots 10-36
    }

    // ─── Capabilities / commands JSON ────────────────────────────────────────

    public static String capabilitiesJson() {
        return "{"
            + "\"supports_typed_actions\":true,"
            + "\"default_provider\":\"baritone_chat\","
            + "\"providers\":[\"baritone_chat\"],"
            + "\"action_types\":[\"move\",\"mine\",\"follow\",\"cancel\",\"raw_command\",\"craft\"],"
            + "\"version\":\"1.0.0\""
            + "}";
    }

    public static String discoverCommandsJson() {
        return "["
            + "\"#goto x y z\","
            + "\"#task interact x y z\","
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
