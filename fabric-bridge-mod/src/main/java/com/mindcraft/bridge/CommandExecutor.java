package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes Minecraft commands and typed actions on the client.
 * Called by {@link BridgeHttpServer} on the /command and /action endpoints.
 */
public class CommandExecutor {

    private CommandExecutor() {}

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
            final String msg = command.trim();
            client.execute(() -> {
                boolean executed = executeBaritoneCommand(msg);
                net.minecraft.client.network.ClientPlayerEntity player = client.player;
                if (!executed && player != null && player.networkHandler != null) {
                    // Fallback to sending chat command without '#' if reflection fails
                    if (msg.startsWith("#")) {
                        // For 1.19+, sendChatMessage directly bypasses interceptors. 
                        // Baritone hooks might miss it if sent directly here, hence we try reflection first.
                        player.networkHandler.sendChatMessage(msg);
                    }
                }
            });
        } else {
            final String cmd = command.startsWith("/") ? command.substring(1) : command;
            client.execute(() -> {
                net.minecraft.client.network.ClientPlayerEntity player = client.player;
                if (player != null && player.networkHandler != null) {
                    player.networkHandler.sendChatCommand(cmd);
                }
            });
        }
    }

    public static String executeTypedJson(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) return null;
        String type = extractJsonString(actionJson, "type");
        if (type == null || type.isBlank()) return null;
        if ("craft".equals(type)) {
            return executeCraftAction(actionJson);
        }
        String command = extractRawBaritoneCommand(actionJson, type);
        if (command != null) {
            return type + ": " + command;
        }
        String rawCmd = extractJsonString(actionJson, "command");
        if (rawCmd != null && !rawCmd.isBlank()) {
            return type + ": " + rawCmd;
        }
        return type;
    }

    // â”€â”€â”€ Craft action orchestration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static String executeCraftAction(String actionJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return "craft: not connected";
        }
        // Parse item/count from the JSON on the HTTP thread (safe â€” pure string ops)
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

        // Queue the Baritone command on the render thread; the table UI work starts
        // after #craft opens the crafting table.
        final String targetItem = itemName;
        final int targetCount = count;
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();

        client.execute(() -> {
            try {
                ClientPlayerEntity player = client.player;
                if (player == null || client.world == null) {
                    future.complete("craft: not connected");
                    return;
                }

                Identifier itemId = targetItem.contains(":")
                        ? Identifier.tryParse(targetItem)
                        : Identifier.of("minecraft", targetItem);
                if (itemId == null) {
                    future.complete("craft: invalid item " + targetItem);
                    return;
                }
                String itemKey = itemId.toString();
                if (itemKey.startsWith("minecraft:")) {
                    itemKey = itemKey.substring(10);
                }
                String normalizedItemKey = normalizeCraftTargetForInventory(player, itemKey);
                if (!normalizedItemKey.equals(itemKey)) {
                    player.sendMessage(net.minecraft.text.Text.literal(
                            "[Bridge] Using " + normalizedItemKey + " instead of " + itemKey
                                    + " based on inventory."), false);
                    itemKey = normalizedItemKey;
                }
                MakePlan plan = planMakeForInventory(player, itemKey, targetCount);
                if (!plan.isSuccess()) {
                    future.complete("craft: could not plan " + targetItem + " - "
                            + String.join("; ", plan.errors));
                    return;
                }

                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Queued make plan: " + describeMakePlan(plan.steps)), false);
                for (MakeStep step : plan.steps) {
                    enqueueMakeStep(step);
                }
                future.complete("craft: queued " + plan.steps.size() + " step(s); "
                        + describeMakePlan(plan.steps));
            } catch (Exception e) {
                future.complete("craft: error â€” " + e.getMessage());
            }
        });

        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "craft: timeout waiting for render thread â€” " + e.getMessage();
        }
    }

    private static void watchForCraftingScreen(MinecraftClient client, Runnable craftAction) {
        Thread watcher = new Thread(() -> {
            for (int wait = 0; wait < 200; wait++) {
                if (Boolean.TRUE.equals(callClient(() -> {
                    ClientPlayerEntity player = client.player;
                    return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
                }))) {
                    callClient(() -> {
                        craftAction.run();
                        return null;
                    });
                    return;
                }
                sleep(50);
            }
            sendBridgeMessage("[Bridge] Crafting failed: crafting table screen never opened.");
        }, "mindcraft-craft-screen-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void craftPostAction(String itemName, int count) {
        Thread worker = new Thread(() -> craftPostActionWorker(itemName, count), "mindcraft-craft-runner");
        worker.setDaemon(true);
        worker.start();
    }

    private enum MakeStepKind { MINE, SMELT, CRAFT }

    private record MakeStep(MakeStepKind kind, String itemName, int count, String command) {}

    private record IngredientNeed(List<String> patterns, int count) {}

    private record GatherProvider(String mineTarget, String producedItem) {}

    private static class MakePlan {
        final List<MakeStep> steps = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Map<String, Integer> virtualInventory = new HashMap<>();

        boolean isSuccess() {
            return errors.isEmpty();
        }

        void addVirtual(String itemId, int count) {
            if (count <= 0) return;
            virtualInventory.merge(normalizeItemId(itemId), count, Integer::sum);
        }

        int countVirtual(String itemId) {
            return virtualInventory.getOrDefault(normalizeItemId(itemId), 0);
        }

        int consumeItem(String itemId, int count) {
            if (count <= 0) return 0;
            String normalized = normalizeItemId(itemId);
            int have = virtualInventory.getOrDefault(normalized, 0);
            int used = Math.min(have, count);
            if (used > 0) {
                int left = have - used;
                if (left > 0) {
                    virtualInventory.put(normalized, left);
                } else {
                    virtualInventory.remove(normalized);
                }
            }
            return count - used;
        }

        int consumeMatching(List<String> patterns, int count) {
            int remaining = count;
            if (remaining <= 0) return 0;
            List<String> keys = new ArrayList<>(virtualInventory.keySet());
            keys.sort(String::compareTo);
            for (String key : keys) {
                if (remaining <= 0) break;
                if (!matchesItemId(key, patterns)) continue;
                remaining = consumeItem(key, remaining);
            }
            return remaining;
        }
    }

    private static void enqueueBridgeCraft(String itemName, int count) {
        AtomicBoolean craftStarted = new AtomicBoolean(false);
        Runnable craftOnce = () -> {
            if (craftStarted.compareAndSet(false, true)) {
                craftPostAction(itemName, count);
            }
        };
        TaskQueue.getInstance().enqueueWithCallback("#craft", craftOnce);
    }

    private static void enqueueMakeStep(MakeStep step) {
        if (step.kind() == MakeStepKind.CRAFT) {
            enqueueBridgeCraft(step.itemName(), step.count());
        } else if (step.command() != null && !step.command().isBlank()) {
            TaskQueue.getInstance().enqueue(step.command());
        }
    }

    private static MakePlan planMakeForInventory(ClientPlayerEntity player, String itemKey, int targetCount) {
        MakePlan plan = new MakePlan();
        snapshotInventory(player, plan);
        makeItem(plan, normalizeItemId(itemKey), targetCount, 0);
        return plan;
    }

    private static boolean makeItem(MakePlan plan, String itemId, int count, int depth) {
        if (!plan.errors.isEmpty()) return false;
        if (count <= 0) return true;
        if (depth > 12) {
            plan.errors.add("recipe chain too deep at " + stripMinecraftNamespace(itemId));
            return false;
        }

        String normalized = normalizeItemId(itemId);
        int missing = plan.consumeItem(normalized, count);
        if (missing <= 0) return true;

        String itemKey = stripMinecraftNamespace(normalized);
        RecipeData recipe = RECIPE_DATABASE.get(itemKey);
        if (recipe != null) {
            int outputCount = Math.max(1, recipe.outputCount);
            int batches = Math.max(1, (int) Math.ceil(missing / (double) outputCount));
            for (IngredientNeed ingredient : aggregateRecipeIngredients(recipe, batches)) {
                if (!makeIngredient(plan, ingredient.patterns(), ingredient.count(), depth + 1)) {
                    return false;
                }
            }
            plan.steps.add(new MakeStep(MakeStepKind.CRAFT, itemKey, missing, null));
            plan.addVirtual(normalized, batches * outputCount);
            plan.consumeItem(normalized, missing);
            return true;
        }

        SmeltRecipe smelt = chooseSmeltRecipeForOutput(plan, normalized);
        if (smelt != null) {
            if (!makeItem(plan, smelt.input(), missing, depth + 1)) {
                return false;
            }
            int fuelNeeded = fuelItemsNeededForPlan(plan, missing, smelt.input());
            if (fuelNeeded > 0 && !makeItem(plan, "minecraft:coal", fuelNeeded, depth + 1)) {
                return false;
            }
            String inputKey = stripMinecraftNamespace(smelt.input());
            plan.steps.add(new MakeStep(MakeStepKind.SMELT, inputKey, missing,
                    "#task smelt " + inputKey + " " + missing));
            plan.addVirtual(normalized, missing);
            plan.consumeItem(normalized, missing);
            return true;
        }

        GatherProvider gather = GATHER_PROVIDERS.get(normalized);
        if (gather != null) {
            plan.steps.add(new MakeStep(MakeStepKind.MINE, stripMinecraftNamespace(gather.producedItem()), missing,
                    "#mine " + missing + " " + gather.mineTarget()));
            plan.addVirtual(gather.producedItem(), missing);
            plan.consumeItem(normalized, missing);
            return true;
        }

        plan.errors.add("no recipe, smelt path, or gather provider for " + stripMinecraftNamespace(normalized));
        return false;
    }

    private static boolean makeIngredient(MakePlan plan, List<String> patterns, int count, int depth) {
        int missing = plan.consumeMatching(patterns, count);
        if (missing <= 0) return true;

        String candidate = chooseIngredientCandidate(plan, patterns);
        if (candidate == null) {
            plan.errors.add("no provider for ingredient " + String.join("/", patterns));
            return false;
        }

        if (!makeItem(plan, candidate, missing, depth + 1)) {
            return false;
        }
        return true;
    }

    private static List<IngredientNeed> aggregateRecipeIngredients(RecipeData recipe, int batches) {
        Map<String, IngredientNeed> byPatternSet = new LinkedHashMap<>();
        for (GridSlot slot : recipe.slots) {
            List<String> normalizedPatterns = new ArrayList<>();
            for (String pattern : slot.ingredientPatterns) {
                normalizedPatterns.add(normalizeIngredientPattern(pattern));
            }
            String key = String.join("|", normalizedPatterns);
            IngredientNeed existing = byPatternSet.get(key);
            byPatternSet.put(key, existing == null
                    ? new IngredientNeed(normalizedPatterns, batches)
                    : new IngredientNeed(existing.patterns(), existing.count() + batches));
        }
        return new ArrayList<>(byPatternSet.values());
    }

    private static String chooseIngredientCandidate(MakePlan plan, List<String> patterns) {
        for (String existing : plan.virtualInventory.keySet()) {
            if (matchesItemId(existing, patterns)) return existing;
        }
        if (patterns.stream().anyMatch(p -> p.equals("*_planks"))) {
            return choosePlanksForPlan(plan);
        }
        for (String pattern : patterns) {
            if (pattern.startsWith("*")) continue;
            String exact = normalizeItemId(pattern);
            if (RECIPE_DATABASE.containsKey(stripMinecraftNamespace(exact))
                    || chooseSmeltRecipeForOutput(plan, exact) != null
                    || GATHER_PROVIDERS.containsKey(exact)) {
                return exact;
            }
        }
        return null;
    }

    private static String choosePlanksForPlan(MakePlan plan) {
        for (String wood : WOOD_TYPES) {
            String planks = normalizeItemId(wood + "_planks");
            if (plan.countVirtual(planks) > 0) return planks;
        }
        for (String wood : WOOD_TYPES) {
            String log = normalizeItemId(wood + "_log");
            if (plan.countVirtual(log) > 0) return normalizeItemId(wood + "_planks");
        }
        return normalizeItemId("oak_planks");
    }

    private static SmeltRecipe chooseSmeltRecipeForOutput(MakePlan plan, String outputItemId) {
        String output = normalizeItemId(outputItemId);
        List<SmeltRecipe> candidates = new ArrayList<>();
        for (SmeltRecipe recipe : SMELT_RECIPES.values()) {
            if (recipe.output().equals(output)) {
                candidates.add(recipe);
            }
        }
        candidates.sort(Comparator.comparing(SmeltRecipe::input));
        for (SmeltRecipe recipe : candidates) {
            if (plan.countVirtual(recipe.input()) > 0) return recipe;
        }
        for (SmeltRecipe recipe : candidates) {
            if (GATHER_PROVIDERS.containsKey(recipe.input())) return recipe;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int fuelItemsNeededForPlan(MakePlan plan, int smeltItems, String avoidItemId) {
        int availableCapacity = 0;
        String avoid = normalizeItemId(avoidItemId);
        for (Map.Entry<String, Integer> entry : plan.virtualInventory.entrySet()) {
            if (entry.getKey().equals(avoid)) continue;
            availableCapacity += entry.getValue() * fuelCapacityItems(entry.getKey());
        }
        int missingCapacity = Math.max(0, smeltItems - availableCapacity);
        if (missingCapacity <= 0) return 0;
        return Math.max(1, (int) Math.ceil(missingCapacity / 8.0));
    }

    private static void snapshotInventory(ClientPlayerEntity player, MakePlan plan) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                plan.addVirtual(getItemId(stack), stack.getCount());
            }
        }
    }

    // Bridge-owned smelting orchestration. Public command remains:
    // #task smelt <input> [count]

    public static void runSmeltTask(String command) {
        Thread worker = new Thread(() -> smeltTaskWorker(command), "mindcraft-smelt-runner");
        worker.setDaemon(true);
        worker.start();
    }

    private record SmeltRecipe(String input, String output, boolean allowFurnace,
                               boolean allowBlast, boolean allowSmoker) {}

    private record FurnaceInfo(BlockPos pos, String blockId, long cookMs, int inputCount,
                               int outputCount, float cookProgress) {}

    private static class FurnacePlan {
        final FurnaceInfo info;
        int assigned;
        FurnacePlan(FurnaceInfo info) {
            this.info = info;
        }
        long etaMs() {
            double existing = info.inputCount > 0
                    ? Math.max(0.0, 1.0 - info.cookProgress) + Math.max(0, info.inputCount - 1)
                    : 0.0;
            return (long) Math.ceil((existing + assigned) * info.cookMs);
        }
    }

    private static void smeltTaskWorker(String command) {
        String normalizedCommand = String.valueOf(command).trim();
        try {
            SmeltRequest request = parseSmeltRequest(normalizedCommand);
            if (request == null) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: expected #task smelt <item> [count]");
                return;
            }

            String resolvedInput = callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? normalizeItemId(request.input())
                        : resolveSmeltInputForInventory(player, request.input());
            });
            SmeltRecipe recipe = SMELT_RECIPES.get(resolvedInput);
            if (recipe == null) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no recipe for " + request.input());
                return;
            }

            int availableInput = callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? 0 : countItemInInventory(player, recipe.input());
            });
            int targetCount = request.count() == null ? availableInput : Math.min(request.count(), availableInput);
            if (targetCount <= 0) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no " + recipe.input() + " in inventory");
                return;
            }

            int fuelCapacity = callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? 0 : countAvailableFuelCapacity(player, recipe.input());
            });
            if (fuelCapacity <= 0) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no fuel available");
                return;
            }

            sendBridgeMessage("[Bridge] Smelting " + targetCount + "x " + recipe.input()
                    + " -> " + recipe.output() + " using nearby furnaces.");

            List<BlockPos> candidates = callClient(() -> scanSmeltFurnaces(recipe, 16));
            if (candidates.isEmpty()) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no usable furnace blocks nearby");
                return;
            }

            List<FurnacePlan> plans = new ArrayList<>();
            for (BlockPos pos : candidates) {
                FurnaceInfo info = inspectFurnace(pos, recipe);
                if (info != null) {
                    plans.add(new FurnacePlan(info));
                }
            }

            if (plans.isEmpty()) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand,
                        "smelt: no compatible furnaces found; all were blocked or incompatible");
                return;
            }

            assignSmeltLoad(plans, targetCount);
            plans.removeIf(plan -> plan.assigned <= 0 && plan.info.outputCount <= 0 && plan.info.inputCount <= 0);
            if (plans.stream().noneMatch(plan -> plan.assigned > 0)) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: could not assign furnace load");
                return;
            }

            int assignedTotal = plans.stream().mapToInt(plan -> plan.assigned).sum();
            if (fuelCapacity < assignedTotal) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand,
                        "smelt: fuel can smelt " + fuelCapacity + " item(s), need " + assignedTotal);
                return;
            }

            for (FurnacePlan plan : plans) {
                if (plan.assigned <= 0) continue;
                if (!loadFurnace(plan.info.pos, recipe, plan.assigned)) {
                    TaskQueue.getInstance().failActiveIf(normalizedCommand,
                            "smelt: failed to load furnace at " + plan.info.pos.toShortString());
                    return;
                }
            }

            long waitMs = plans.stream().mapToLong(FurnacePlan::etaMs).max().orElse(0L) + 1_500L;
            sendBridgeMessage("[Bridge] Smelt plan loaded across "
                    + plans.stream().filter(plan -> plan.assigned > 0).count()
                    + " furnace(s). Waiting about " + Math.max(1, waitMs / 1000) + "s before collection.");
            sleep(waitMs);

            int beforeOutput = countInventoryItemSafe(recipe.output());
            long deadline = System.currentTimeMillis() + Math.max(60_000L, waitMs + 60_000L);
            int collectedDelta = 0;
            while (System.currentTimeMillis() < deadline) {
                for (FurnacePlan plan : plans) {
                    collectFurnaceOutput(plan.info.pos, recipe);
                }
                int afterOutput = countInventoryItemSafe(recipe.output());
                collectedDelta = Math.max(0, afterOutput - beforeOutput);
                if (collectedDelta >= targetCount) {
                    sendBridgeMessage("[Bridge] Smelting complete: collected " + collectedDelta
                            + "x " + recipe.output() + ".");
                    TaskQueue.getInstance().completeActiveIf(normalizedCommand);
                    return;
                }
                sleep(2_000L);
            }

            TaskQueue.getInstance().failActiveIf(normalizedCommand,
                    "smelt: timed out after collecting " + collectedDelta + "/" + targetCount
                            + " " + recipe.output());
        } catch (Exception e) {
            TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: " + e.getMessage());
        } finally {
            callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                if (player != null) player.closeHandledScreen();
                return null;
            });
        }
    }

    private record SmeltRequest(String input, Integer count) {}

    private static SmeltRequest parseSmeltRequest(String command) {
        String[] parts = String.valueOf(command).trim().split("\\s+");
        if (parts.length < 3) return null;
        if (!"#task".equalsIgnoreCase(parts[0]) || !"smelt".equalsIgnoreCase(parts[1])) return null;
        String input = normalizeItemId(parts[2]);
        Integer count = null;
        if (parts.length >= 4) {
            try {
                count = Math.max(1, Integer.parseInt(parts[3]));
            } catch (NumberFormatException ignored) {}
        }
        return new SmeltRequest(input, count);
    }

    private static FurnaceInfo inspectFurnace(BlockPos pos, SmeltRecipe recipe) {
        if (!openFurnace(pos)) return null;
        try {
            return callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                if (player == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler furnace)) {
                    return null;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack input = screen.getSlot(0).getStack();
                ItemStack output = screen.getSlot(2).getStack();
                if (!input.isEmpty() && !getItemId(input).equals(recipe.input())) return null;
                if (!output.isEmpty() && !getItemId(output).equals(recipe.output())) return null;
                String blockId = getBlockId(MinecraftClient.getInstance().world.getBlockState(pos).getBlock());
                return new FurnaceInfo(pos, blockId, cookMsForBlock(blockId),
                        input.isEmpty() ? 0 : input.getCount(),
                        output.isEmpty() ? 0 : output.getCount(),
                        furnace.getCookProgress());
            });
        } finally {
            closeScreenSafe();
        }
    }

    private static boolean loadFurnace(BlockPos pos, SmeltRecipe recipe, int inputCount) {
        if (!openFurnace(pos)) return false;
        try {
            return Boolean.TRUE.equals(callClient(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ClientPlayerEntity player = client.player;
                ClientPlayerInteractionManager im = client.interactionManager;
                if (player == null || im == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler)) {
                    return false;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack input = screen.getSlot(0).getStack();
                ItemStack output = screen.getSlot(2).getStack();
                if (!input.isEmpty() && !getItemId(input).equals(recipe.input())) return false;
                if (!output.isEmpty() && !getItemId(output).equals(recipe.output())) return false;
                if (!moveItemsIntoSlot(player, im, screen, 0, recipe.input(), inputCount)) return false;
                ItemStack fuelStack = screen.getSlot(1).getStack();
                String existingFuelId = fuelStack.isEmpty() ? null : getItemId(fuelStack);
                int fuelItems = fuelItemsNeeded(player, inputCount, recipe.input());
                if (fuelItems <= 0 && existingFuelId == null) return false;
                if (existingFuelId != null && countItemInInventory(player, existingFuelId) <= 0) return true;
                String fuelId = existingFuelId != null ? existingFuelId : bestFuelItem(player, recipe.input());
                return fuelId != null && moveItemsIntoSlot(player, im, screen, 1, fuelId, fuelItems);
            }));
        } finally {
            closeScreenSafe();
        }
    }

    private static void collectFurnaceOutput(BlockPos pos, SmeltRecipe recipe) {
        if (!openFurnace(pos)) return;
        try {
            callClient(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ClientPlayerEntity player = client.player;
                ClientPlayerInteractionManager im = client.interactionManager;
                if (player == null || im == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler)) {
                    return null;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack output = screen.getSlot(2).getStack();
                if (!output.isEmpty() && getItemId(output).equals(recipe.output())) {
                    im.clickSlot(screen.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                }
                return null;
            });
        } finally {
            closeScreenSafe();
        }
    }

    private static void assignSmeltLoad(List<FurnacePlan> plans, int targetCount) {
        for (int i = 0; i < targetCount; i++) {
            FurnacePlan best = null;
            for (FurnacePlan plan : plans) {
                if (best == null || plan.etaMs() < best.etaMs()) {
                    best = plan;
                }
            }
            if (best != null) best.assigned++;
        }
    }

    private static List<BlockPos> scanSmeltFurnaces(SmeltRecipe recipe, int range) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return List.of();
        BlockPos playerPos = player.getBlockPos();
        List<BlockPos> positions = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    BlockState state = client.world.getBlockState(mutable);
                    if (isAllowedFurnaceBlock(state.getBlock(), recipe)) {
                        positions.add(mutable.toImmutable());
                    }
                }
            }
        }
        positions.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(playerPos)));
        return positions;
    }

    private static boolean openFurnace(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!waitUntilNear(pos, 4.75, 30_000L)) return false;
        callClient(() -> {
            ClientPlayerEntity player = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (player == null || im == null || client.world == null) return null;
            Vec3d hit = Vec3d.ofCenter(pos);
            BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
            im.interactBlock(player, Hand.MAIN_HAND, bhr);
            return null;
        });
        for (int i = 0; i < 40; i++) {
            if (Boolean.TRUE.equals(callClient(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player != null && player.currentScreenHandler instanceof AbstractFurnaceScreenHandler;
            }))) {
                sleep(100);
                return true;
            }
            sleep(50);
        }
        return false;
    }

    private static boolean waitUntilNear(BlockPos pos, double distance, long timeoutMs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!Boolean.TRUE.equals(callClient(() -> {
            ClientPlayerEntity player = client.player;
            return player != null && player.getBlockPos().isWithinDistance(pos, distance);
        }))) {
            execute("#goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(callClient(() -> {
                ClientPlayerEntity player = client.player;
                return player != null && player.getBlockPos().isWithinDistance(pos, distance);
            }))) {
                return true;
            }
            sleep(250);
        }
        return false;
    }

    private static boolean moveItemsIntoSlot(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                             ScreenHandler screen, int targetSlot, String itemId, int count) {
        int moved = 0;
        while (moved < count) {
            int source = findInventoryScreenSlot(screen, itemId);
            if (source < 0) return false;
            im.clickSlot(screen.syncId, source, 0, SlotActionType.PICKUP, player);
            int guard = 0;
            while (moved < count && guard++ < 64) {
                ItemStack cursor = screen.getCursorStack();
                if (cursor.isEmpty()) break;
                int before = cursor.getCount();
                im.clickSlot(screen.syncId, targetSlot, 1, SlotActionType.PICKUP, player);
                ItemStack afterCursor = screen.getCursorStack();
                int after = afterCursor.isEmpty() ? 0 : afterCursor.getCount();
                if (after < before) {
                    moved++;
                } else {
                    break;
                }
            }
            im.clickSlot(screen.syncId, source, 0, SlotActionType.PICKUP, player);
        }
        return true;
    }

    private static int findInventoryScreenSlot(ScreenHandler screen, String itemId) {
        for (int screenSlot = 3; screenSlot < screen.slots.size(); screenSlot++) {
            ItemStack stack = screen.getSlot(screenSlot).getStack();
            if (!stack.isEmpty() && getItemId(stack).equals(itemId)) {
                return screenSlot;
            }
        }
        return -1;
    }

    private static int countAvailableFuelCapacity(ClientPlayerEntity player, String avoidItemId) {
        int total = 0;
        String avoid = normalizeItemId(avoidItemId);
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = getItemId(stack);
            if (id.equals(avoid)) continue;
            total += stack.getCount() * fuelCapacityItems(id);
        }
        return total;
    }

    private static int fuelItemsNeeded(ClientPlayerEntity player, int smeltItems, String avoidItemId) {
        String fuel = bestFuelItem(player, avoidItemId);
        if (fuel == null) return 0;
        int capacity = Math.max(1, fuelCapacityItems(fuel));
        return Math.max(1, (int) Math.ceil(smeltItems / (double) capacity));
    }

    private static String bestFuelItem(ClientPlayerEntity player, String avoidItemId) {
        String avoid = normalizeItemId(avoidItemId);
        String best = null;
        int bestCapacity = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = getItemId(stack);
            if (id.equals(avoid)) continue;
            int capacity = fuelCapacityItems(id);
            if (capacity > bestCapacity) {
                bestCapacity = capacity;
                best = id;
            }
        }
        return best;
    }

    private static int fuelCapacityItems(String itemId) {
        String id = normalizeItemId(itemId);
        if (id.equals("minecraft:lava_bucket")) return 100;
        if (id.equals("minecraft:coal_block")) return 80;
        if (id.equals("minecraft:blaze_rod")) return 12;
        if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) return 8;
        if (id.endsWith("_log") || id.endsWith("_wood")) return 1;
        if (id.endsWith("_planks")) return 1;
        return 0;
    }

    private static String resolveSmeltInputForInventory(ClientPlayerEntity player, String requested) {
        String input = normalizeItemId(requested);
        if (countItemInInventory(player, input) > 0) return input;
        Map<String, String> minedDropAliases = Map.of(
                "minecraft:iron_ore", "minecraft:raw_iron",
                "minecraft:deepslate_iron_ore", "minecraft:raw_iron",
                "minecraft:gold_ore", "minecraft:raw_gold",
                "minecraft:deepslate_gold_ore", "minecraft:raw_gold",
                "minecraft:copper_ore", "minecraft:raw_copper",
                "minecraft:deepslate_copper_ore", "minecraft:raw_copper"
        );
        String alias = minedDropAliases.get(input);
        if (alias != null && countItemInInventory(player, alias) > 0) return alias;
        return input;
    }

    private static int countInventoryItemSafe(String itemId) {
        return callClient(() -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            return player == null ? 0 : countItemInInventory(player, itemId);
        });
    }

    private static void closeScreenSafe() {
        callClient(() -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) player.closeHandledScreen();
            return null;
        });
        sleep(100);
    }

    private static boolean isAllowedFurnaceBlock(Block block, SmeltRecipe recipe) {
        if (block == Blocks.FURNACE) return recipe.allowFurnace();
        if (block == Blocks.BLAST_FURNACE) return recipe.allowBlast();
        if (block == Blocks.SMOKER) return recipe.allowSmoker();
        return false;
    }

    private static long cookMsForBlock(String blockId) {
        return (blockId.equals("minecraft:blast_furnace") || blockId.equals("minecraft:smoker"))
                ? 5_000L : 10_000L;
    }

    private static String normalizeItemId(String item) {
        String id = String.valueOf(item == null ? "" : item).trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String normalizeIngredientPattern(String pattern) {
        String value = String.valueOf(pattern == null ? "" : pattern).trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("*")) return value;
        return normalizeItemId(value);
    }

    private static String stripMinecraftNamespace(String itemId) {
        String normalized = normalizeItemId(itemId);
        return normalized.startsWith("minecraft:") ? normalized.substring(10) : normalized;
    }

    private static final Map<String, SmeltRecipe> SMELT_RECIPES = buildSmeltRecipes();

    private static Map<String, SmeltRecipe> buildSmeltRecipes() {
        Map<String, SmeltRecipe> db = new HashMap<>();
        addSmelt(db, "iron_ore", "iron_ingot", true, true, false);
        addSmelt(db, "deepslate_iron_ore", "iron_ingot", true, true, false);
        addSmelt(db, "raw_iron", "iron_ingot", true, true, false);
        addSmelt(db, "gold_ore", "gold_ingot", true, true, false);
        addSmelt(db, "deepslate_gold_ore", "gold_ingot", true, true, false);
        addSmelt(db, "raw_gold", "gold_ingot", true, true, false);
        addSmelt(db, "copper_ore", "copper_ingot", true, true, false);
        addSmelt(db, "deepslate_copper_ore", "copper_ingot", true, true, false);
        addSmelt(db, "raw_copper", "copper_ingot", true, true, false);
        addSmelt(db, "ancient_debris", "netherite_scrap", true, true, false);
        addSmelt(db, "sand", "glass", true, false, false);
        addSmelt(db, "cobblestone", "stone", true, false, false);
        addSmelt(db, "stone", "smooth_stone", true, false, false);
        addSmelt(db, "clay_ball", "brick", true, false, false);
        addSmelt(db, "netherrack", "nether_brick", true, false, false);
        addSmelt(db, "kelp", "dried_kelp", true, false, false);
        for (String wood : lst("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            addSmelt(db, wood + "_log", "charcoal", true, false, false);
            addSmelt(db, wood + "_wood", "charcoal", true, false, false);
        }
        for (String food : lst("beef", "chicken", "porkchop", "mutton", "rabbit", "cod", "salmon", "potato")) {
            String cooked = food.equals("potato") ? "baked_potato" : "cooked_" + food;
            addSmelt(db, food, cooked, true, false, true);
        }
        return db;
    }

    private static void addSmelt(Map<String, SmeltRecipe> db, String input, String output,
                                 boolean furnace, boolean blast, boolean smoker) {
        db.put(normalizeItemId(input), new SmeltRecipe(normalizeItemId(input), normalizeItemId(output),
                furnace, blast, smoker));
    }

    private static final List<String> WOOD_TYPES = lst(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");

    private static final Map<String, GatherProvider> GATHER_PROVIDERS = buildGatherProviders();

    private static Map<String, GatherProvider> buildGatherProviders() {
        Map<String, GatherProvider> providers = new HashMap<>();
        addGather(providers, "cobblestone", "cobblestone");
        addGather(providers, "cobbled_deepslate", "cobbled_deepslate");
        addGather(providers, "blackstone", "blackstone");
        addGather(providers, "sand", "sand");
        addGather(providers, "clay_ball", "clay", "clay_ball");
        addGather(providers, "coal", "coal_ore", "coal");
        addGather(providers, "redstone", "redstone_ore", "redstone");
        addGather(providers, "diamond", "diamond_ore", "diamond");
        addGather(providers, "emerald", "emerald_ore", "emerald");
        addGather(providers, "lapis_lazuli", "lapis_ore", "lapis_lazuli");
        addGather(providers, "flint", "gravel", "flint");
        addGather(providers, "raw_iron", "iron_ore", "raw_iron");
        addGather(providers, "raw_gold", "gold_ore", "raw_gold");
        addGather(providers, "raw_copper", "copper_ore", "raw_copper");
        addGather(providers, "ancient_debris", "ancient_debris");
        for (String wood : WOOD_TYPES) {
            addGather(providers, wood + "_log", wood + "_log");
            addGather(providers, wood + "_wood", wood + "_wood");
        }
        return providers;
    }

    private static void addGather(Map<String, GatherProvider> providers, String item, String mineTarget) {
        addGather(providers, item, mineTarget, item);
    }

    private static void addGather(Map<String, GatherProvider> providers, String item,
                                  String mineTarget, String producedItem) {
        providers.put(normalizeItemId(item), new GatherProvider(mineTarget, normalizeItemId(producedItem)));
    }

    private static int countRecipeIngredientSlots(RecipeData recipe, String itemId) {
        int total = 0;
        for (GridSlot slot : recipe.slots) {
            if (matchesItemId(itemId, slot.ingredientPatterns)) {
                total++;
            }
        }
        return total;
    }

    private static String describeMakePlan(List<MakeStep> plan) {
        if (plan.isEmpty()) return "already satisfied from inventory";
        List<String> parts = new ArrayList<>();
        for (MakeStep step : plan) {
            String name = step.itemName();
            if (step.kind() == MakeStepKind.MINE) {
                parts.add("mine " + step.count() + "x " + name);
            } else if (step.kind() == MakeStepKind.SMELT) {
                parts.add("smelt " + step.count() + "x " + name);
            } else {
                parts.add("craft " + step.count() + "x " + name);
            }
        }
        return String.join(" -> ", parts);
    }

    private static void craftPostActionWorker(String itemName, int count) {
        try {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int wait = 0; wait < 20; wait++) {
            if (Boolean.TRUE.equals(callClient(() -> {
                ClientPlayerEntity player = client.player;
                return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
            }))) {
                break;
            }
            sleep(50);
        }
        if (!Boolean.TRUE.equals(callClient(() -> {
            ClientPlayerEntity player = client.player;
            return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
        }))) {
            sendBridgeMessage("[Bridge] Crafting failed: crafting table did not open.");
            return;
        }

        sendBridgeMessage("[Bridge] Crafting table opened. Starting craft of " + count + "x " + itemName + "...");

        Identifier itemId = itemName.contains(":")
                ? Identifier.tryParse(itemName)
                : Identifier.of("minecraft", itemName);
        if (itemId == null) {
            sendBridgeMessage("[Bridge] Invalid item: " + itemName);
            return;
        }

        String itemKey = itemId.toString();
        if (itemKey.startsWith("minecraft:")) {
            itemKey = itemKey.substring(10);
        }
        final String itemKeyForNormalize = itemKey;
        String normalizedItemKey = callClient(() ->
                normalizeCraftTargetForInventory(client.player, itemKeyForNormalize));
        if (!normalizedItemKey.equals(itemKey)) {
            sendBridgeMessage("[Bridge] Using " + normalizedItemKey + " instead of " + itemKey
                    + " based on inventory.");
            itemKey = normalizedItemKey;
        }
        RecipeData recipe = RECIPE_DATABASE.get(itemKey);
        if (recipe == null) {
            sendBridgeMessage("[Bridge] No recipe found for " + itemName + " in recipe database.");
            return;
        }

        int craftedTotal = 0;
        int maxAttempts = Math.min(count, 64);

        for (int attempt = 0; attempt < maxAttempts && craftedTotal < count; attempt++) {
            if (!Boolean.TRUE.equals(callClient(() -> {
                ClientPlayerEntity player = client.player;
                return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
            }))) {
                sendBridgeMessage("[Bridge] Table closed. Crafted " + craftedTotal + "x " + itemName);
                return;
            }

            boolean filled = Boolean.TRUE.equals(callClient(() -> {
                ClientPlayerEntity player = client.player;
                ClientPlayerInteractionManager im = client.interactionManager;
                if (player == null || im == null || !(player.currentScreenHandler instanceof CraftingScreenHandler)) {
                    return false;
                }
                ScreenHandler screen = player.currentScreenHandler;
                return fillGridFromRecipe(player, im, screen, screen.syncId, recipe);
            }));
            if (!filled) {
                sendBridgeMessage("[Bridge] Failed to fill recipe for " + itemName + ". Crafted " + craftedTotal + "x.");
                break;
            }
            sleep(100);

            int resultCount = 0;
            for (int wait = 0; wait < 20; wait++) {
                resultCount = callClient(() -> {
                    ClientPlayerEntity player = client.player;
                    ClientPlayerInteractionManager im = client.interactionManager;
                    if (player == null || im == null || !(player.currentScreenHandler instanceof CraftingScreenHandler)) {
                        return 0;
                    }
                    ScreenHandler screen = player.currentScreenHandler;
                    return takeCraftingResult(player, im, screen, screen.syncId, itemId);
                });
                if (resultCount > 0) break;
                sleep(50);
            }
            if (resultCount > 0) {
                craftedTotal += resultCount;
            } else {
                String gridState = callClient(() -> {
                    ClientPlayerEntity player = client.player;
                    if (player == null || !(player.currentScreenHandler instanceof CraftingScreenHandler)) {
                        return "table closed";
                    }
                    return describeCraftingGrid(player.currentScreenHandler);
                });
                sendBridgeMessage("[Bridge] Crafting grid after fill: " + gridState);
                sendBridgeMessage("[Bridge] No result in output slot. Stopping.");
                break;
            }
        }
        final int finalCraftedTotal = craftedTotal;
        callClient(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                player.closeHandledScreen();
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Crafting complete: " + finalCraftedTotal + "x " + itemName), false);
            }
            return null;
        });
        } finally {
            sleep(250);
            TaskQueue.getInstance().completeActiveIf("#craft");
        }
    }

    private static boolean fillGridFromRecipe(ClientPlayerEntity player,
                                               ClientPlayerInteractionManager im,
                                               ScreenHandler screen,
                                               int syncId,
                                               RecipeData recipe) {
        for (GridSlot slot : recipe.slots) {
            int screenInvSlot = findMatchingInventoryScreenSlot(screen, slot.ingredientPatterns);
            if (screenInvSlot < 0) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Missing ingredient for slot " + slot.gridIndex + " in " + recipe.output
                        + ". Try: " + String.join(", ", slot.ingredientPatterns)), false);
                return false;
            }
            im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
            im.clickSlot(syncId, slot.gridIndex, 1, SlotActionType.PICKUP, player);
            im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
        }
        return true;
    }

    private static int findMatchingInventoryScreenSlot(ScreenHandler screen, List<String> patterns) {
        // CraftingScreenHandler slots 0-9 are output + 3x3 grid; player inventory starts at 10.
        for (int screenSlot = 10; screenSlot < screen.slots.size(); screenSlot++) {
            ItemStack stack = screen.getSlot(screenSlot).getStack();
            if (stack.isEmpty()) continue;
            if (matchesItemId(getItemId(stack), patterns)) {
                return screenSlot;
            }
        }
        return -1;
    }

    private static String describeCraftingGrid(ScreenHandler screen) {
        List<String> filled = new ArrayList<>();
        for (int gridSlot = 1; gridSlot <= 9; gridSlot++) {
            ItemStack stack = screen.getSlot(gridSlot).getStack();
            if (!stack.isEmpty()) {
                filled.add(gridSlot + "=" + getItemId(stack) + "x" + stack.getCount());
            }
        }
        return filled.isEmpty() ? "empty" : String.join(", ", filled);
    }

    private static boolean matchesItemId(String itemId, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.startsWith("*") && itemId.endsWith(pattern.substring(1))) return true;
            if (itemId.equals(pattern)) return true;
            String stripped = itemId;
            if (stripped.startsWith("minecraft:")) stripped = stripped.substring(10);
            if (stripped.equals(pattern)) return true;
            if (pattern.startsWith("*") && stripped.endsWith(pattern.substring(1))) return true;
        }
        return false;
    }

    // â”€â”€â”€ Recipe Database â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static class GridSlot {
        final int gridIndex;
        final List<String> ingredientPatterns;
        GridSlot(int gridIndex, List<String> ingredientPatterns) {
            this.gridIndex = gridIndex;
            this.ingredientPatterns = ingredientPatterns;
        }
    }

    private static class RecipeData {
        final String output;
        final List<GridSlot> slots;
        @SuppressWarnings("unused")
        final int outputCount;
        RecipeData(String output, List<GridSlot> slots, int outputCount) {
            this.output = output;
            this.slots = slots;
            this.outputCount = outputCount;
        }
    }

    private static GridSlot gs(int idx, List<String> pat) { return new GridSlot(idx, pat); }

    @SafeVarargs
    private static <T> List<T> lst(T... items) { return Arrays.asList(items); }

    private static final Map<String, RecipeData> RECIPE_DATABASE = buildRecipeDatabase();

    private static Map<String, RecipeData> buildRecipeDatabase() {
        Map<String, RecipeData> db = new HashMap<>();

        // Planks (1 log -> 4)
        for (String w : lst("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            db.put(w + "_planks", new RecipeData(w + "_planks", lst(gs(1, lst("minecraft:" + w + "_log"))), 4));
        }

        // Stick (2 planks vertical -> 4)
        db.put("stick", new RecipeData("stick", lst(
            gs(1, lst("*_planks")), gs(4, lst("*_planks"))
        ), 4));

        // Crafting Table (4 planks in 2x2 -> 1)
        db.put("crafting_table", new RecipeData("crafting_table", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")),
            gs(4, lst("*_planks")), gs(5, lst("*_planks"))
        ), 1));

        // Torch (coal + stick -> 4)
        db.put("torch", new RecipeData("torch", lst(
            gs(1, lst("minecraft:coal","minecraft:charcoal")),
            gs(4, lst("minecraft:stick"))
        ), 4));

        // Pickaxes
        db.put("wooden_pickaxe", new RecipeData("wooden_pickaxe", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(5, lst("minecraft:stick")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        db.put("stone_pickaxe", new RecipeData("stone_pickaxe", lst(
            gs(1, lst("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone")),
            gs(2, lst("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone")),
            gs(3, lst("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone")),
            gs(5, lst("minecraft:stick")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Furnace (cobblestone ring -> 1)
        db.put("furnace", new RecipeData("furnace", lst(
            gs(1, lst("minecraft:cobblestone")), gs(2, lst("minecraft:cobblestone")), gs(3, lst("minecraft:cobblestone")),
            gs(4, lst("minecraft:cobblestone")), gs(6, lst("minecraft:cobblestone")),
            gs(7, lst("minecraft:cobblestone")), gs(8, lst("minecraft:cobblestone")), gs(9, lst("minecraft:cobblestone"))
        ), 1));

        // White Wool (4 string in 2x2 -> 1)
        db.put("white_wool", new RecipeData("white_wool", lst(
            gs(1, lst("minecraft:string")), gs(2, lst("minecraft:string")),
            gs(4, lst("minecraft:string")), gs(5, lst("minecraft:string"))
        ), 1));

        // Colored Wool (dye + white_wool -> 1)
        for (String c : lst("orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) {
            db.put(c + "_wool", new RecipeData(c + "_wool", lst(
                gs(1, lst(c + "_dye")), gs(5, lst("minecraft:white_wool"))
            ), 1));
        }

        // Book (3 paper Z + leather -> 1)
        db.put("book", new RecipeData("book", lst(
            gs(1, lst("minecraft:paper")), gs(5, lst("minecraft:paper")),
            gs(6, lst("minecraft:leather")), gs(9, lst("minecraft:paper"))
        ), 1));

        // Bookshelf (6 planks + 3 books -> 1)
        db.put("bookshelf", new RecipeData("bookshelf", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("minecraft:book")), gs(5, lst("minecraft:book")), gs(6, lst("minecraft:book")),
            gs(7, lst("*_planks")), gs(8, lst("*_planks")), gs(9, lst("*_planks"))
        ), 1));

        // Compass (4 iron + 1 redstone in cross pattern -> 1)
        db.put("compass", new RecipeData("compass", lst(
            gs(2, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:redstone")), gs(6, lst("minecraft:iron_ingot")),
            gs(8, lst("minecraft:iron_ingot"))
        ), 1));

        // Clock (4 gold + 1 redstone in cross pattern -> 1)
        db.put("clock", new RecipeData("clock", lst(
            gs(2, lst("minecraft:gold_ingot")),
            gs(4, lst("minecraft:gold_ingot")), gs(5, lst("minecraft:redstone")), gs(6, lst("minecraft:gold_ingot")),
            gs(8, lst("minecraft:gold_ingot"))
        ), 1));

        // Fishing Rod (3 sticks + 2 string -> 1)
        db.put("fishing_rod", new RecipeData("fishing_rod", lst(
            gs(3, lst("minecraft:stick")),
            gs(5, lst("minecraft:stick")), gs(6, lst("minecraft:string")),
            gs(7, lst("minecraft:stick")), gs(9, lst("minecraft:string"))
        ), 1));

        // Cake (3 milk + 2 sugar + 1 egg + 3 wheat -> 1)
        db.put("cake", new RecipeData("cake", lst(
            gs(1, lst("minecraft:milk_bucket")), gs(2, lst("minecraft:milk_bucket")), gs(3, lst("minecraft:milk_bucket")),
            gs(4, lst("minecraft:sugar")), gs(5, lst("minecraft:egg")), gs(6, lst("minecraft:sugar")),
            gs(7, lst("minecraft:wheat")), gs(8, lst("minecraft:wheat")), gs(9, lst("minecraft:wheat"))
        ), 1));

        // Golden Apple (8 gold ingots + apple -> 1)
        db.put("golden_apple", new RecipeData("golden_apple", lst(
            gs(1, lst("minecraft:gold_ingot")), gs(2, lst("minecraft:gold_ingot")), gs(3, lst("minecraft:gold_ingot")),
            gs(4, lst("minecraft:gold_ingot")), gs(5, lst("minecraft:apple")), gs(6, lst("minecraft:gold_ingot")),
            gs(7, lst("minecraft:gold_ingot")), gs(8, lst("minecraft:gold_ingot")), gs(9, lst("minecraft:gold_ingot"))
        ), 1));

        // Paper (3 sugar cane -> 3)
        db.put("paper", new RecipeData("paper", lst(
            gs(1, lst("minecraft:sugar_cane")), gs(2, lst("minecraft:sugar_cane")), gs(3, lst("minecraft:sugar_cane"))
        ), 3));

        // Firework Rocket (1 paper + 1 gunpowder -> 3)
        db.put("firework_rocket", new RecipeData("firework_rocket", lst(
            gs(1, lst("minecraft:paper")), gs(5, lst("minecraft:gunpowder"))
        ), 3));

        // Purple Banner (6 purple_wool + 1 stick -> 1)
        db.put("purple_banner", new RecipeData("purple_banner", lst(
            gs(1, lst("minecraft:purple_wool")), gs(2, lst("minecraft:purple_wool")), gs(3, lst("minecraft:purple_wool")),
            gs(4, lst("minecraft:purple_wool")), gs(5, lst("minecraft:purple_wool")), gs(6, lst("minecraft:purple_wool")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Lectern (3 slabs + 1 bookshelf -> 1)
        db.put("lectern", new RecipeData("lectern", lst(
            gs(1, lst("minecraft:oak_slab","minecraft:spruce_slab","minecraft:birch_slab","*_slab")),
            gs(2, lst("minecraft:oak_slab","minecraft:spruce_slab","minecraft:birch_slab","*_slab")),
            gs(3, lst("minecraft:oak_slab","minecraft:spruce_slab","minecraft:birch_slab","*_slab")),
            gs(5, lst("minecraft:bookshelf"))
        ), 1));

        // Crossbow (3 sticks + 2 string + 1 iron + 1 tripwire -> 1)
        db.put("crossbow", new RecipeData("crossbow", lst(
            gs(2, lst("minecraft:stick")), gs(4, lst("minecraft:stick")), gs(5, lst("minecraft:iron_ingot")),
            gs(6, lst("minecraft:tripwire_hook")), gs(8, lst("minecraft:string")), gs(9, lst("minecraft:string"))
        ), 1));

        // Activator Rail (6 iron + 2 sticks + 1 redstone_torch -> 6)
        db.put("activator_rail", new RecipeData("activator_rail", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:redstone_torch")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:stick")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(8, lst("minecraft:stick")), gs(9, lst("minecraft:iron_ingot"))
        ), 6));

        // Rail (6 iron + 1 stick -> 16)
        db.put("rail", new RecipeData("rail", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:stick")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(9, lst("minecraft:iron_ingot"))
        ), 16));

        // Chest (8 planks ring -> 1)
        db.put("chest", new RecipeData("chest", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("*_planks")), gs(6, lst("*_planks")),
            gs(7, lst("*_planks")), gs(8, lst("*_planks")), gs(9, lst("*_planks"))
        ), 1));

        // Doors (6 planks -> 3)
        for (String w : lst("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            db.put(w + "_door", new RecipeData(w + "_door", lst(
                gs(1, lst(w+"_planks")), gs(2, lst(w+"_planks")),
                gs(4, lst(w+"_planks")), gs(5, lst(w+"_planks")),
                gs(7, lst(w+"_planks")), gs(8, lst(w+"_planks"))
            ), 3));
        }

        // Trapdoors (4 planks -> 2)
        for (String w : lst("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            db.put(w + "_trapdoor", new RecipeData(w + "_trapdoor", lst(
                gs(1, lst(w+"_planks")), gs(2, lst(w+"_planks")),
                gs(4, lst(w+"_planks")), gs(5, lst(w+"_planks"))
            ), 2));
        }

        // Fences (4 planks + 2 sticks -> 3)
        for (String w : lst("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry")) {
            db.put(w + "_fence", new RecipeData(w + "_fence", lst(
                gs(1, lst(w+"_planks")), gs(2, lst("minecraft:stick")), gs(3, lst(w+"_planks")),
                gs(4, lst(w+"_planks")), gs(5, lst("minecraft:stick")), gs(6, lst(w+"_planks"))
            ), 3));
        }

        // Stonecutter (3 stone + 1 iron -> 1)
        db.put("stonecutter", new RecipeData("stonecutter", lst(
            gs(2, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:stone")), gs(5, lst("minecraft:stone")), gs(6, lst("minecraft:stone"))
        ), 1));

        // Shears (2 iron ingots diagonal -> 1)
        db.put("shears", new RecipeData("shears", lst(
            gs(4, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot"))
        ), 1));

        // Iron Block (9 iron ingots -> 1)
        db.put("iron_block", new RecipeData("iron_block", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(8, lst("minecraft:iron_ingot")), gs(9, lst("minecraft:iron_ingot"))
        ), 1));

        // Iron Pickaxe (3 iron ingots + 2 sticks -> 1)
        db.put("iron_pickaxe", new RecipeData("iron_pickaxe", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(5, lst("minecraft:stick")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Diamond Pickaxe (3 diamonds + 2 sticks -> 1)
        db.put("diamond_pickaxe", new RecipeData("diamond_pickaxe", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(5, lst("minecraft:stick")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Bucket (3 iron ingots V -> 1)
        db.put("bucket", new RecipeData("bucket", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(4, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot"))
        ), 1));

        // Flint and Steel (1 iron + 1 flint -> 1)
        db.put("flint_and_steel", new RecipeData("flint_and_steel", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:flint"))
        ), 1));

        return db;
    }

    // â”€â”€â”€ Take crafting result â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static int takeCraftingResult(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                           ScreenHandler screen, int syncId, Identifier expectedItem) {
        var resultSlot = screen.getSlot(0);
        if (resultSlot == null || !resultSlot.hasStack()) return 0;
        ItemStack resultStack = resultSlot.getStack();
        if (resultStack.isEmpty() || !getItemId(resultStack).equals(expectedItem.toString())) return 0;
        int count = resultStack.getCount();
        im.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
        return count;
    }

    // â”€â”€â”€ Block scanning â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    private static boolean hasItemInInventory(ClientPlayerEntity player, String itemId) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && getItemId(stack).equals(itemId)) return true;
        }
        return false;
    }

    // â”€â”€â”€ ID helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static boolean hasIngredientsForRecipe(ClientPlayerEntity player, RecipeData recipe) {
        for (GridSlot slot : recipe.slots) {
            if (countMatchingInventory(player, slot.ingredientPatterns) <= 0) {
                return false;
            }
        }
        return true;
    }

    private static int countMatchingInventory(ClientPlayerEntity player, List<String> patterns) {
        int total = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && matchesItemId(getItemId(stack), patterns)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static String normalizeCraftTargetForInventory(ClientPlayerEntity player, String itemKey) {
        if (player == null || itemKey == null || !itemKey.endsWith("_planks")) {
            return itemKey;
        }

        String requestedWood = itemKey.substring(0, itemKey.length() - "_planks".length());
        String requestedLog = "minecraft:" + requestedWood + "_log";
        if (countItemInInventory(player, requestedLog) > 0) {
            return itemKey;
        }

        for (String wood : lst("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            if (countItemInInventory(player, "minecraft:" + wood + "_log") > 0) {
                return wood + "_planks";
            }
        }

        return itemKey;
    }

    private static int countItemInInventory(ClientPlayerEntity player, String itemId) {
        int total = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && getItemId(stack).equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static String getItemId(ItemStack stack) {
        if (stack.isEmpty()) return "";
        String s = stack.getItem().toString();
        if (s.startsWith("Item{") && s.endsWith("}")) return s.substring(5, s.length() - 1);
        return s;
    }

    private static String getBlockId(net.minecraft.block.Block block) {
        String s = block.toString();
        if (s.startsWith("Block{") && s.endsWith("}")) return s.substring(6, s.length() - 1);
        return s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static <T> T callClient(java.util.concurrent.Callable<T> action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new RuntimeException("Minecraft client-thread action failed", e);
            }
        }
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(action.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Timed out waiting for Minecraft client thread", e);
        }
    }

    private static void sendBridgeMessage(String message) {
        callClient(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal(message), false);
            }
            return null;
        });
    }

    // â”€â”€â”€ Capabilities / commands JSON â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            + "\"#craft\","
            + "\"#sleep\","
            + "\"#mine count block\","
            + "\"#follow player <name>\","
            + "\"#cancel\","
            + "\"chat: <message>\""
            + "]";
    }

    private static void sendChat(MinecraftClient client, String message) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        StateCollector.trackSentChat(message);
        client.execute(() -> {
            ClientPlayerEntity p = client.player;
            if (p != null && p.networkHandler != null) {
                p.networkHandler.sendChatMessage(message);
            }
        });
    }

    private static boolean executeBaritoneCommand(String command) {
        try {
            // Strip leading '#' if we are calling the command manager directly, 
            // wait, does ICommandManager.execute expect prefix?
            // "execute" typically takes the command WITHOUT the prefix, e.g. "mine iron_ore"
            String cmdToExecute = command;
            if (cmdToExecute.startsWith("#")) {
                cmdToExecute = cmdToExecute.substring(1);
            }
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object cmdManager = primary.getClass().getMethod("getCommandManager").invoke(primary);
            Boolean result = (Boolean) cmdManager.getClass().getMethod("execute", String.class).invoke(cmdManager, cmdToExecute);
            return result != null && result;
        } catch (Exception e) {
            System.err.println("[Mindcraft Bridge] Failed to execute baritone command via API: " + e.getMessage());
            return false;
        }
    }

    private static String extractRawBaritoneCommand(String json, String type) {
        switch (type) {
            case "move": {
                String x = extractJsonPrimitive(json, "x");
                String y = extractJsonPrimitive(json, "y");
                String z = extractJsonPrimitive(json, "z");
                if (x != null && y != null && z != null) return "#goto " + x + " " + y + " " + z;
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
            case "cancel": return "#cancel";
            case "raw_command": return normalizeRawBaritoneCommand(extractJsonString(json, "command"));
            default: return null;
        }
    }

    // â”€â”€â”€ JSON extraction helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static String normalizeRawBaritoneCommand(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        if (trimmed.equalsIgnoreCase("#sleep")
                || trimmed.equalsIgnoreCase("sleep")
                || trimmed.equalsIgnoreCase("#task sleep")) {
            return "#sleep";
        }
        return trimmed;
    }

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
        if (json.charAt(i) == '"') return extractJsonString(json, key);
        int end = i;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            end++;
        }
        return (end > i) ? json.substring(i, end) : null;
    }
}
