package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

import com.mindcraft.bridge.workers.ActionRegistry;
import com.mindcraft.bridge.workers.Worker;
import com.mindcraft.bridge.workers.WorkerContext;
import com.mindcraft.bridge.workers.WorkerResult;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes Minecraft commands and typed actions on the client.
 * Called by {@link BridgeHttpServer} on the /command and /action endpoints.
 */
public class CommandExecutor {

    private CommandExecutor() {}

    // ─── Phase 1: Structured Translation Results ──────────────────────────────

    public record TranslatedAction(
            boolean accepted,
            String actionType,
            String lifecycle,
            String command,
            String failureCode,
            String message,
            boolean genericWorker
    ) {
        public TranslatedAction(boolean accepted, String actionType, String lifecycle, String command, String failureCode, String message) {
            this(accepted, actionType, lifecycle, command, failureCode, message, false);
        }
        public boolean ok() {
            return accepted && failureCode == null;
        }

        public String legacyOutput() {
            if (!ok()) return "error: " + message;
            return actionType + ": " + (command == null ? message : command);
        }
    }

    public static TranslatedAction translateTypedJson(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return new TranslatedAction(false, null, null, null, "missing_action", "Action JSON is blank");
        }
        String type = extractJsonString(actionJson, "type");
        if (type == null || type.isBlank()) {
            return new TranslatedAction(false, null, null, null, "missing_type", "Missing action type");
        }
        if (!ActionRegistry.get().isExternallyRoutable(type)) {
            return new TranslatedAction(false, type, null, null, "unknown_action", "Unknown action type: " + type);
        }

        // Self-executing actions
        if ("craft".equals(type)) {
            String result = executeCraftAction(actionJson);
            if (result == null) {
                return new TranslatedAction(false, type, "self_executing", null, "craft_failed", "Craft action returned null");
            }
            return selfExecuting(type, result);
        }
        if ("attack".equals(type)) {
            String result = executeAttackAction(actionJson);
            if (result == null) {
                return new TranslatedAction(false, type, "self_executing", null, "attack_failed", "Attack action returned null");
            }
            return selfExecuting(type, result);
        }
        if ("sleep_try".equals(type)) {
            return queued(type, "#sleep");
        }
        if ("build_schematic".equals(type)) {
            String result = executeBuildSchematicAction(actionJson);
            if (result == null) {
                return new TranslatedAction(false, type, "self_executing", null, "build_schematic_failed", "Build schematic returned null");
            }
            return selfExecuting(type, result);
        }
        if ("cancel_build".equals(type)) {
            String result = executeCancelBuildAction();
            if (result == null) {
                return new TranslatedAction(false, type, "immediate", null, "cancel_build_failed", "Cancel build returned null");
            }
            return immediate(type, result);
        }
        if ("flee".equals(type)) {
            String result = executeFleeAction(actionJson);
            if (result == null) {
                return new TranslatedAction(false, type, "queued", null, "flee_failed", "Flee action returned null");
            }
            return queued(type, result);
        }
        if ("select_slot".equals(type)) {
            return immediate(type, executeSelectSlotAction(actionJson));
        }
        if ("equip".equals(type)) {
            return immediate(type, executeEquipAction(actionJson));
        }
        if ("equip_best".equals(type)) {
            return immediate(type, executeEquipBestAction(actionJson));
        }
        if ("use_item".equals(type)) {
            return immediate(type, executeUseItemAction(actionJson));
        }
        if ("consume".equals(type)) {
            return immediate(type, executeConsumeAction(actionJson));
        }
        if ("drop_items".equals(type)) {
            return immediate(type, executeDropItemsAction(actionJson));
        }
        if ("pickup_items".equals(type)) {
            return queued(type, executePickupItemsAction(actionJson));
        }
        if ("open_block".equals(type)) {
            return immediate(type, executeOpenBlockAction(actionJson));
        }
        if ("close_screen".equals(type)) {
            return immediate(type, executeCloseScreenAction());
        }
        if ("transfer_items".equals(type)) {
            return immediate(type, executeTransferItemsAction(actionJson));
        }
        TranslatedAction bridgeAction = translateBridgeStateAction(type, actionJson);
        if (bridgeAction != null) {
            return bridgeAction;
        }
        if ("interact_block".equals(type)) {
            return immediate(type, executeInteractBlockAction(actionJson));
        }
        if ("interact_entity".equals(type)) {
            return immediate(type, executeInteractEntityAction(actionJson));
        }
        if ("smith".equals(type)) {
            return ofGenericWorker(type, "#smith", "queued");
        }
        if ("brew".equals(type)) {
            return ofGenericWorker(type, "#brew", "queued");
        }
        if ("enchant".equals(type)) {
            return ofGenericWorker(type, "#enchant", "queued");
        }
        if ("anvil".equals(type)) {
            return selfExecuting(type, executeAnvilAction(actionJson));
        }
        if ("grindstone".equals(type)) {
            return selfExecuting(type, executeGrindstoneAction(actionJson));
        }
        if ("stonecut".equals(type)) {
            return selfExecuting(type, executeStonecutAction(actionJson));
        }
        if ("loom".equals(type)) {
            return selfExecuting(type, executeLoomAction(actionJson));
        }
        if ("cartography".equals(type)) {
            return selfExecuting(type, executeCartographyAction(actionJson));
        }
        if ("trade".equals(type)) {
            return selfExecuting(type, executeTradeAction(actionJson));
        }
        if ("obtain".equals(type)) {
            return selfExecuting(type, executeObtainAction(actionJson));
        }
        if ("ranged_attack".equals(type)) {
            return selfExecuting(type, executeRangedAttackAction(actionJson));
        }
        if ("defend".equals(type)) {
            return selfExecuting(type, executeDefendAction(actionJson));
        }
        if ("retreat".equals(type)) {
            return queued(type, executeRetreatAction(actionJson));
        }
        if ("clear_hostiles".equals(type)) {
            return selfExecuting(type, executeClearHostilesAction(actionJson));
        }
        if ("hunt_mob".equals(type)) {
            return selfExecuting(type, executeHuntMobAction(actionJson));
        }
        if ("farm".equals(type)) {
            return selfExecuting(type, executeFarmAction(actionJson));
        }
        if ("fish".equals(type)) {
            return selfExecuting(type, executeFishAction(actionJson));
        }
        if ("loot".equals(type)) {
            return selfExecuting(type, executeLootAction(actionJson));
        }
        if ("collect_fluid".equals(type)) {
            return selfExecuting(type, executeCollectFluidAction(actionJson));
        }
        if ("ride_entity".equals(type)) {
            return immediate(type, executeRideEntityAction(actionJson));
        }
        if ("use_boat".equals(type)) {
            return selfExecuting(type, executeUseBoatAction(actionJson));
        }
        if ("use_minecart".equals(type)) {
            return selfExecuting(type, executeUseMinecartAction(actionJson));
        }
        if ("dismount".equals(type)) {
            return immediate(type, executeDismountAction());
        }
        if ("elytra_fly".equals(type)) {
            return selfExecuting(type, executeElytraFlyAction(actionJson));
        }
        if ("portal_travel".equals(type)) {
            return selfExecuting(type, executePortalTravelAction(actionJson));
        }
        if ("return_to_overworld".equals(type)) {
            return selfExecuting(type, executeReturnToOverworldAction());
        }
        if ("place_block".equals(type)) {
            return immediate(type, executePlaceBlockAction(actionJson));
        }
        if ("break_block".equals(type)) {
            return immediate(type, executeBreakBlockAction(actionJson));
        }
        if ("validate_structure".equals(type)) {
            return selfExecuting(type, executeValidateStructureAction(actionJson));
        }
        if ("repair_structure".equals(type)) {
            return selfExecuting(type, executeRepairStructureAction(actionJson));
        }

        // raw_command — passes through arbitrary command (gated by allowlist)
        if ("raw_command".equals(type)) {
            String rawCmd = normalizeRawBaritoneCommand(extractJsonString(actionJson, "command"));
            if (rawCmd == null || rawCmd.isBlank()) {
                return new TranslatedAction(false, type, null, null, "missing_command", "raw_command requires a 'command' field");
            }
            if (!RawCommandPolicy.isAllowed(rawCmd)) {
                return new TranslatedAction(false, type, null, null, "raw_command_forbidden", "raw_command not allowed: " + rawCmd);
            }
            return queued(type, rawCmd);
        }

        // Baritone commands (move, mine, follow, cancel)
        String command = extractRawBaritoneCommand(actionJson, type);
        if (command != null) {
            return queued(type, command);
        }

        // Generic worker fallback — registered worker with no explicit handler above
        if (ActionRegistry.get().dispatchKind(type) == ActionRegistry.DispatchKind.GENERIC_WORKER) {
            Worker worker = ActionRegistry.get().getWorker(type);
            if (worker != null) {
                return ofGenericWorker(type, "#" + type, "queued");
            }
        }

        return new TranslatedAction(false, type, null, null, "unsupported_action", "Unsupported action type: " + type);
    }

    private static TranslatedAction queued(String type, String command) {
        return new TranslatedAction(true, type, "queued", command, null, "queued");
    }

    private static TranslatedAction selfExecuting(String type, String result) {
        return new TranslatedAction(true, type, "self_executing", result, null, result);
    }

    private static TranslatedAction immediate(String type, String result) {
        return new TranslatedAction(true, type, "immediate", result, null, result);
    }

    public static TranslatedAction ofGenericWorker(String type, String command, String message) {
        return new TranslatedAction(true, type, "self_executing", command, null, message, true);
    }

    private static TranslatedAction translateBridgeStateAction(String type, String actionJson) {
        switch (type) {
            case "screen_click_slot": {
                Integer slot = requireInt(actionJson, "slot");
                if (slot == null) return missing(type, "slot");
                if (slot < 0) return invalid(type, "slot");
                String action = extractJsonString(actionJson, "action");
                if (action != null && parseSlotActionType(action) == null) return invalid(type, "action");
                Integer button = optionalInt(actionJson, "button", 0);
                if (button == null || button < 0) return invalid(type, "button");
                return immediate(type, executeScreenClickSlotAction(actionJson));
            }
            case "container_deposit": {
                Integer slot = optionalInt(actionJson, "slot", -1);
                String item = extractJsonString(actionJson, "item");
                if ((slot == null || slot < 0) && (item == null || item.isBlank())) return missing(type, "item_or_slot");
                if (slot != null && slot < -1) return invalid(type, "slot");
                return immediate(type, executeContainerMoveAction(actionJson, true));
            }
            case "container_withdraw": {
                Integer slot = optionalInt(actionJson, "slot", -1);
                String item = extractJsonString(actionJson, "item");
                if ((slot == null || slot < 0) && (item == null || item.isBlank())) return missing(type, "item_or_slot");
                if (slot != null && slot < -1) return invalid(type, "slot");
                return immediate(type, executeContainerMoveAction(actionJson, false));
            }
            case "container_quick_move": {
                Integer slot = requireInt(actionJson, "slot");
                if (slot == null) return missing(type, "slot");
                if (slot < 0) return invalid(type, "slot");
                return immediate(type, executeContainerQuickMoveAction(actionJson));
            }
            case "look": {
                if (requireFloat(actionJson, "yaw") == null) return missing(type, "yaw");
                if (requireFloat(actionJson, "pitch") == null) return missing(type, "pitch");
                return immediate(type, executeLookAction(actionJson));
            }
            case "look_at": {
                String entityId = extractJsonPrimitive(actionJson, "entity_id");
                boolean hasCoords = extractJsonPrimitive(actionJson, "x") != null
                        && extractJsonPrimitive(actionJson, "y") != null
                        && extractJsonPrimitive(actionJson, "z") != null;
                if ((entityId == null || entityId.isBlank()) && !hasCoords) return missing(type, "target");
                return immediate(type, executeLookAtAction(actionJson));
            }
            case "press_key": {
                String key = extractJsonString(actionJson, "key");
                if (key == null || key.isBlank()) return missing(type, "key");
                if (resolveKeyName(key) == null) return invalid(type, "key");
                Integer durationMs = optionalInt(actionJson, "duration_ms", null);
                if (durationMs != null && (durationMs < 0 || durationMs > 5000)) return invalid(type, "duration_ms");
                return immediate(type, executePressKeyAction(actionJson));
            }
            case "swing":
                return immediate(type, executeSwingAction(actionJson));
            case "attack_entity": {
                Integer entityId = requireInt(actionJson, "entity_id");
                if (entityId == null) return missing(type, "entity_id");
                return immediate(type, executeAttackEntityAction(actionJson));
            }
            case "use_item_on_block": {
                if (requireInt(actionJson, "x") == null || requireInt(actionJson, "y") == null || requireInt(actionJson, "z") == null) {
                    return missing(type, "coordinates");
                }
                String direction = firstNonBlank(extractJsonString(actionJson, "face"), extractJsonString(actionJson, "direction"));
                if (direction != null && parseDirection(direction) == null) return invalid(type, "face");
                return immediate(type, executeUseItemOnBlockAction(actionJson));
            }
            case "use_item_on_entity": {
                Integer entityId = requireInt(actionJson, "entity_id");
                if (entityId == null) return missing(type, "entity_id");
                return immediate(type, executeUseItemOnEntityAction(actionJson));
            }
            case "hold_use_item": {
                Integer durationMs = optionalInt(actionJson, "duration_ms", 500);
                if (durationMs == null || durationMs < 0 || durationMs > 5000) return invalid(type, "duration_ms");
                return immediate(type, executeHoldUseItemAction(actionJson));
            }
            default:
                return null;
        }
    }

    private static TranslatedAction missing(String type, String field) {
        return new TranslatedAction(false, type, null, null, "missing_" + field, type + " requires " + field);
    }

    private static TranslatedAction invalid(String type, String field) {
        return new TranslatedAction(false, type, null, null, "invalid_" + field, type + " has invalid " + field);
    }

    public static void execute(String command) {
        if (command == null || command.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        // SAFE: volatile null check — no mutation, just bail if obviously disconnected
        if (client.player == null) return;
        if (command.startsWith("chat:")) {
            String message = command.substring(5).trim();
            if (!message.isEmpty()) {
                sendChat(client, message);
            }
        } else if (command.startsWith("#")) {
            final String msg = command.trim();
            ClientThread.run(() -> {
                boolean executed = dispatchBaritoneChatCommand(msg);
                if (!executed) {
                    executed = executeBaritoneCommand(msg);
                }
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
            ClientThread.run(() -> {
                net.minecraft.client.network.ClientPlayerEntity player = client.player;
                if (player != null && player.networkHandler != null) {
                    player.networkHandler.sendChatCommand(cmd);
                }
            });
        }
    }

    public static String executeTypedJson(String actionJson) {
        TranslatedAction result = translateTypedJson(actionJson);
        return result == null ? null : result.legacyOutput();
    }

    // â”€â”€â”€ Craft action orchestration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static String executeFleeAction(String actionJson) {
        return ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) return "flee: not connected";

            String distStr = extractJsonPrimitive(actionJson, "distance");
            double distance = 24.0;
            if (distStr != null) {
                try { distance = Double.parseDouble(distStr); } catch (NumberFormatException ignored) {}
            }

            // Find nearest hostile entity
            Box searchBox = player.getBoundingBox().expand(32);
            List<Entity> entities = client.world.getOtherEntities(player, searchBox, e -> isHostile(e));
            Entity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Entity e : entities) {
                double d = player.squaredDistanceTo(e);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = e;
                }
            }

            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            Vec3d dest;
            if (nearest != null) {
                Vec3d hostilePos = new Vec3d(nearest.getX(), nearest.getY(), nearest.getZ());
                Vec3d away = playerPos.subtract(hostilePos).normalize().multiply(distance);
                dest = playerPos.add(away);
            } else {
                // No hostile found — flee in a random direction
                double yaw = Math.toRadians(player.getYaw());
                dest = playerPos.add(new Vec3d(-Math.sin(yaw) * distance, 0, Math.cos(yaw) * distance));
            }

            int tx = (int) Math.floor(dest.x);
            int ty = (int) Math.floor(dest.y);
            int tz = (int) Math.floor(dest.z);
            return "flee: #goto " + tx + " " + ty + " " + tz;
        });
    }

    private static String executeSleepTryAction(String actionJson) {
        WorkerThreads.start("mindcraft-sleep-try", () -> sleepTryWorker());
        return "sleep_try: queued cascade";
    }

    private static void sleepTryWorker() {
        sendBridgeMessage("[Bridge] Sleep cascade: trying #sleep ...");
        TaskQueue.getInstance().enqueue("#sleep");
        String result = waitForActiveTaskComplete(35_000L);
        if ("success".equals(result)) {
            sendBridgeMessage("[Bridge] Sleep cascade: #sleep succeeded");
            return;
        }

        sendBridgeMessage("[Bridge] Sleep cascade: #sleep failed (" + result + "); checking cache ...");
        BlockPos cached = findCachedBed();
        if (cached != null) {
            sendBridgeMessage("[Bridge] Sleep cascade: cached bed at " + cached.toShortString());
            TaskQueue.getInstance().discardPausedFailure();
            TaskQueue.getInstance().enqueue("#goto " + cached.getX() + " " + cached.getY() + " " + cached.getZ());
            String gotoResult = waitForActiveTaskComplete(120_000L);
            if ("success".equals(gotoResult)) {
                TaskQueue.getInstance().enqueue("#sleep");
                String sleep2 = waitForActiveTaskComplete(35_000L);
                if ("success".equals(sleep2)) {
                    sendBridgeMessage("[Bridge] Sleep cascade: cached bed sleep succeeded");
                    return;
                }
            }
        }

        sendBridgeMessage("[Bridge] Sleep cascade: no cached bed; planning new bed ...");
        TaskQueue.getInstance().discardPausedFailure();
        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (Boolean.TRUE.equals(connected)) {
            MakePlan plan = planMakeForInventory(client.player, "red_bed", 1);
            if (plan.isSuccess() && !plan.steps.isEmpty()) {
                for (MakeStep step : plan.steps) {
                    enqueueMakeStep(step);
                }
                TaskQueue.getInstance().enqueue("#sleep");
                String sleep3 = waitForActiveTaskComplete(35_000L);
                if ("success".equals(sleep3)) {
                    sendBridgeMessage("[Bridge] Sleep cascade: crafted bed sleep succeeded");
                    return;
                }
            }
        }
        sendBridgeMessage("[Bridge] Sleep cascade: exhausted all options.");
    }

    public static String waitForActiveTaskComplete(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
            if ("idle".equals(qs.status())) {
                return "success";
            }
            if ("paused".equals(qs.status())) {
                return "failed";
            }
            sleepQuietly(500);
        }
        return "timeout";
    }

    private static BlockPos findCachedBed() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            int[] playerChunk = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                if (p == null) return null;
                return new int[]{ p.getBlockPos().getX() >> 4, p.getBlockPos().getZ() >> 4 };
            });
            if (playerChunk == null) return null;
            int cx = playerChunk[0];
            int cz = playerChunk[1];
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object worldProvider = baritone.getClass().getMethod("getWorldProvider").invoke(baritone);
            Object world = worldProvider.getClass().getMethod("getCurrentWorld").invoke(worldProvider);
            Object cache = world.getClass().getMethod("getCachedWorld").invoke(world);
            List<?> beds = (List<?>) cache.getClass()
                    .getMethod("getLocationsOf", String.class, int.class, int.class, int.class, int.class)
                    .invoke(cache, "bed", 1, cx, cz, 64);
            if (beds == null || beds.isEmpty()) return null;
            return (BlockPos) beds.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── Attack action orchestration ──────────────────────────────────────────────────────────────────

    private enum CombatState { SEARCHING, APPROACHING, ENGAGING, LOOTING, DONE }

    private static String executeAttackAction(String actionJson) {
        return executeAttackAction(actionJson, "#attack");
    }

    private static String executeAttackAction(String actionJson, String trackingCommand) {
        WorkerThreads.start("mindcraft-combat", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("attack").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf(trackingCommand);
                } else {
                    TaskQueue.getInstance().failActiveIf(trackingCommand, result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf(trackingCommand, "attack: " + e.getMessage());
            }
        });
        return "attack: queued melee combat";
    }

    private static void combatWorker(String actionJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            sendBridgeMessage("[Bridge] Combat: not connected");
            return;
        }

        String targetTypeRaw = extractJsonString(actionJson, "target_type");
        String maxDistStr = extractJsonPrimitive(actionJson, "max_distance");
        String retreatHpStr = extractJsonPrimitive(actionJson, "retreat_hp");
        String countStr = extractJsonPrimitive(actionJson, "count");
        String searchTimeStr = extractJsonPrimitive(actionJson, "search_time_s");
        String untilItemsJson = extractJsonObject(actionJson, "until_items");

        double maxDistance = 48.0;
        if (maxDistStr != null) {
            try { maxDistance = Double.parseDouble(maxDistStr); } catch (NumberFormatException ignored) {}
        }
        double retreatHp = 10.0;
        if (retreatHpStr != null) {
            try { retreatHp = Double.parseDouble(retreatHpStr); } catch (NumberFormatException ignored) {}
        }
        int targetCount = 1;
        if (countStr != null) {
            try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {}
        }
        int searchTimeS = 30;
        if (searchTimeStr != null) {
            try { searchTimeS = Math.max(0, Math.min(120, Integer.parseInt(searchTimeStr))); } catch (NumberFormatException ignored) {}
        }

        Map<String, Integer> untilItems = new HashMap<>();
        boolean useUntilItems = untilItemsJson != null && !untilItemsJson.isBlank();
        if (useUntilItems) {
            String inner = untilItemsJson.trim();
            if (inner.startsWith("{")) inner = inner.substring(1);
            if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
            for (String pair : inner.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String item = kv[0].trim().replace("\"", "");
                    String count = kv[1].trim().replace("\"", "");
                    try {
                        untilItems.put(item, Integer.parseInt(count));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        int killsRemaining = targetCount;
        int totalAttempts = 0;
        int maxAttempts = targetCount * 3;
        if (useUntilItems && maxAttempts < 30) {
            maxAttempts = 30;
        }

        CombatState state = CombatState.SEARCHING;
        long searchDeadline = System.currentTimeMillis() + (searchTimeS * 1000L);
        Entity currentTarget = null;
        Entity huntTarget = null;
        boolean isSafetyKill = false;
        AtomicLong lastHitTimestamp = new AtomicLong(0);
        Vec3d lastTargetPos = null;
        long retargetTimer = 0;
        BlockPos lastGotoTarget = null;
        boolean announcedSearch = false;

        boolean hadWeapon = InventoryDriver.equipBestWeapon(player);
        if (!hadWeapon) {
            sendBridgeMessage("[Bridge] Combat: no weapon in hotbar — punching with fist");
        }

        while (state != CombatState.DONE && totalAttempts < maxAttempts) {
            player = client.player;
            if (player == null || client.world == null) {
                sendBridgeMessage("[Bridge] Combat: not connected");
                return;
            }

            boolean isDead = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                return p != null && (p.isDead() || p.getHealth() <= 0);
            });
            if (isDead) {
                sendBridgeMessage("[Bridge] Combat: player died");
                return;
            }
            float health = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                return p == null ? 0f : p.getHealth();
            });
            if (health <= retreatHp) {
                sendBridgeMessage("[Bridge] Combat: retreating — HP too low");
                return;
            }

            switch (state) {
                case SEARCHING: {
                    lastGotoTarget = null;
                    if (useUntilItems) {
                        boolean allMet = true;
                        for (Map.Entry<String, Integer> entry : untilItems.entrySet()) {
                            int have = InventoryDriver.countItem(player, ItemIds.normalize(entry.getKey()));
                            if (have < entry.getValue()) {
                                allMet = false;
                                break;
                            }
                        }
                        if (allMet) {
                            sendBridgeMessage("[Bridge] Hunt: all item goals met");
                            state = CombatState.DONE;
                            continue;
                        }
                    } else if (killsRemaining <= 0) {
                        sendBridgeMessage("[Bridge] Hunt: kill count reached");
                        state = CombatState.DONE;
                        continue;
                    }

                    currentTarget = findNearestHostileOfType(client, player, targetTypeRaw, maxDistance);
                    if (currentTarget != null) {
                        String targetName = currentTarget.getType().toString();
                        sendBridgeMessage("[Bridge] Hunt: found " + normalizeEntityTypeName(targetName));
                        lastTargetPos = new Vec3d(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());
                        state = CombatState.APPROACHING;
                        continue;
                    }

                    if (searchTimeS <= 0 || System.currentTimeMillis() >= searchDeadline) {
                        sendBridgeMessage("[Bridge] Hunt: no " + (targetTypeRaw != null ? targetTypeRaw : "hostile") + " found after " + searchTimeS + "s");
                        state = CombatState.DONE;
                        continue;
                    }

                    if (!announcedSearch) {
                        sendBridgeMessage("[Bridge] Hunt: searching via #explore ...");
                        announcedSearch = true;
                    }
                    TaskQueue.getInstance().cancelAll();
                    sleepQuietly(150);
                    TaskQueue.getInstance().enqueue("#explore");

                    long exploreDeadline = System.currentTimeMillis() + 8000;
                    boolean found = false;
                    while (System.currentTimeMillis() < exploreDeadline) {
                        player = client.player;
                        if (player == null || client.world == null) return;
                        boolean exploreDead = ClientThread.call(() -> {
                            ClientPlayerEntity p = MinecraftClient.getInstance().player;
                            return p != null && (p.isDead() || p.getHealth() <= 0);
                        });
                        if (exploreDead) {
                            sendBridgeMessage("[Bridge] Combat: player died");
                            return;
                        }
                        float exploreHealth = ClientThread.call(() -> {
                            ClientPlayerEntity p = MinecraftClient.getInstance().player;
                            return p == null ? 0f : p.getHealth();
                        });
                        if (exploreHealth <= retreatHp) {
                            sendBridgeMessage("[Bridge] Combat: retreating — HP too low");
                            return;
                        }

                        currentTarget = findNearestHostileOfType(client, player, targetTypeRaw, maxDistance);
                        if (currentTarget != null) {
                            TaskQueue.getInstance().cancelAll();
                            sleepQuietly(150);
                            String targetName = currentTarget.getType().toString();
                        sendBridgeMessage("[Bridge] Hunt: found " + normalizeEntityTypeName(targetName) + " during explore");
                        lastTargetPos = new Vec3d(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());
                        state = CombatState.APPROACHING;
                            found = true;
                            break;
                        }
                        sleepQuietly(500);
                    }
                    if (!found) {
                        TaskQueue.getInstance().cancelAll();
                        sleepQuietly(150);
                    }
                    continue;
                }

                case APPROACHING: {
                    announcedSearch = false;
                    if (currentTarget == null || currentTarget.isRemoved() || !currentTarget.isAlive()) {
                        sendBridgeMessage("[Bridge] Hunt: target lost during approach");
                        state = CombatState.SEARCHING;
                        continue;
                    }

                    double dist = player.squaredDistanceTo(currentTarget);
                    if (dist <= 16.0) {
                        sendBridgeMessage("[Bridge] Hunt: target in melee range, engaging");
                        TaskQueue.getInstance().cancelAll();
                        sleepQuietly(200);
                        state = CombatState.ENGAGING;
                        retargetTimer = System.currentTimeMillis();
                        continue;
                    }

                    BlockPos currentTargetBlock = new BlockPos(
                            (int) Math.floor(currentTarget.getX()),
                            (int) Math.floor(currentTarget.getY()),
                            (int) Math.floor(currentTarget.getZ())
                    );
                    if (lastGotoTarget == null || !lastGotoTarget.equals(currentTargetBlock)) {
                        TaskQueue.getInstance().cancelAll();
                        sleepQuietly(100);
                        TaskQueue.getInstance().enqueue(
                                "#goto " + currentTargetBlock.getX() + " " +
                                        currentTargetBlock.getY() + " " +
                                        currentTargetBlock.getZ()
                        );
                        lastGotoTarget = currentTargetBlock;
                    }

                    sleepQuietly(500);
                    continue;
                }

                case ENGAGING: {
                    announcedSearch = false;
                    lastGotoTarget = null;
                    InventoryDriver.equipBestWeapon(player);

                    if (currentTarget == null || currentTarget.isRemoved() || !currentTarget.isAlive()) {
                        boolean recentHit = (System.currentTimeMillis() - lastHitTimestamp.get()) < 2000;
                        if (isSafetyKill) {
                            sendBridgeMessage("[Bridge] Hunt: safety-killed threat, back to hunt");
                            isSafetyKill = false;
                            currentTarget = huntTarget;
                            huntTarget = null;
                            if (currentTarget != null && currentTarget.isAlive()) {
                                state = CombatState.APPROACHING;
                            } else {
                                state = CombatState.SEARCHING;
                            }
                            continue;
                        }
                        sendBridgeMessage("[Bridge] Hunt: target killed");
                        totalAttempts++;
                        if (!useUntilItems && recentHit) {
                            killsRemaining--;
                        }
                        if (currentTarget != null) {
                            lastTargetPos = new Vec3d(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());
                        }
                        state = CombatState.LOOTING;
                        continue;
                    }

                    double dist = player.squaredDistanceTo(currentTarget);
                    if (dist > 36.0) {
                        sendBridgeMessage("[Bridge] Hunt: target moved away, re-approaching");
                        lastGotoTarget = null;
                        state = CombatState.APPROACHING;
                        continue;
                    }

                    if (System.currentTimeMillis() - retargetTimer >= 2000) {
                        retargetTimer = System.currentTimeMillis();
                        Entity threat = findClosestHostile(client, player, 4.0);
                        if (threat != null && threat != currentTarget
                                && player.squaredDistanceTo(threat) < player.squaredDistanceTo(currentTarget)) {
                            if (!isSafetyKill) {
                                huntTarget = currentTarget;
                                isSafetyKill = true;
                            }
                            currentTarget = threat;
                        }
                    }

                    final ClientPlayerEntity fp = player;
                    final Entity t = currentTarget;
                    final AtomicLong hitStamp = lastHitTimestamp;
                    ClientThread.run(() -> {
                        lookAtEntity(fp, t);
                        if (client.interactionManager != null) {
                            client.interactionManager.attackEntity(fp, t);
                            fp.swingHand(Hand.MAIN_HAND);
                            hitStamp.set(System.currentTimeMillis());
                        }
                    });

                    int cooldownMs = InventoryDriver.getAttackCooldownMs(player);
                    sleepQuietly(cooldownMs);
                    continue;
                }

                case LOOTING: {
                    announcedSearch = false;
                    lastGotoTarget = null;
                    sendBridgeMessage("[Bridge] Hunt: looting drops ...");
                    sleepQuietly(2500);

                    if (lastTargetPos != null) {
                        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                        Vec3d toTarget = lastTargetPos.subtract(playerPos);
                        double len = toTarget.length();
                        if (len > 0.1) {
                            Vec3d movePos = playerPos.add(toTarget.normalize().multiply(Math.min(len, 2.0)));
                            int mx = (int) Math.floor(movePos.x);
                            int my = (int) Math.floor(movePos.y);
                            int mz = (int) Math.floor(movePos.z);
                            TaskQueue.getInstance().cancelAll();
                            sleepQuietly(150);
                            TaskQueue.getInstance().enqueue("#goto " + mx + " " + my + " " + mz);
                            waitForActiveTaskComplete(10_000L);
                            TaskQueue.getInstance().cancelAll();
                            sleepQuietly(150);
                        }
                    }

                    long lootDeadline = System.currentTimeMillis() + 10_000;
                    boolean itemsMet = false;
                    while (System.currentTimeMillis() < lootDeadline) {
                        player = client.player;
                        if (player == null) break;
                        if (useUntilItems) {
                            boolean allMet = true;
                            for (Map.Entry<String, Integer> entry : untilItems.entrySet()) {
                                int have = InventoryDriver.countItem(player, ItemIds.normalize(entry.getKey()));
                                if (have < entry.getValue()) {
                                    allMet = false;
                                    break;
                                }
                            }
                            if (allMet) {
                                itemsMet = true;
                                break;
                            }
                        } else {
                            itemsMet = true;
                            break;
                        }
                        sleepQuietly(500);
                    }

                    if (itemsMet) {
                        if (useUntilItems) {
                            sendBridgeMessage("[Bridge] Hunt: all item goals met");
                        }
                        state = CombatState.DONE;
                    } else {
                        sendBridgeMessage("[Bridge] Hunt: need more kills, returning to search");
                        state = CombatState.SEARCHING;
                    }
                    continue;
                }

                case DONE: {
                    break;
                }
            }
        }

        if (totalAttempts >= maxAttempts) {
            sendBridgeMessage("[Bridge] Hunt: max attempts reached (" + maxAttempts + ")");
        }
        sendBridgeMessage("[Bridge] Hunt: complete");
    }

    public static Entity findNearestHostileOfType(MinecraftClient client, ClientPlayerEntity player, String targetType, double maxDistance) {
        Box box = player.getBoundingBox().expand(maxDistance);
        List<Entity> entities = client.world.getOtherEntities(player, box, e -> isHostile(e));
        if (targetType != null && !targetType.isBlank()) {
            String lowerTarget = targetType.toLowerCase();
            entities.removeIf(e -> {
                String typeName = normalizeEntityTypeName(e.getType().toString());
                return !typeName.equals(lowerTarget);
            });
        }
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            double d = player.squaredDistanceTo(e);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = e;
            }
        }
        return nearest;
    }

    // DEPRECATED: replaced by findClosestHostile in 1b-fix-retarget
    private static Entity findBetterTarget(MinecraftClient client, ClientPlayerEntity player, Entity currentTarget, String targetType, double maxDistance) {
        if (currentTarget == null || player == null || client.world == null) return null;
        Box box = player.getBoundingBox().expand(8.0);
        List<Entity> entities = client.world.getOtherEntities(player, box, e -> isHostile(e));
        if (targetType != null && !targetType.isBlank()) {
            String lowerTarget = targetType.toLowerCase();
            entities.removeIf(e -> {
                String typeName = normalizeEntityTypeName(e.getType().toString());
                return !typeName.equals(lowerTarget);
            });
        }
        double bestDist = player.squaredDistanceTo(currentTarget);
        Entity best = currentTarget;
        for (Entity e : entities) {
            double d = player.squaredDistanceTo(e);
            if (d <= 16.0 && d < bestDist && e != currentTarget) {
                best = e;
                bestDist = d;
            }
        }
        return best == currentTarget ? null : best;
    }

    public static Entity findClosestHostile(MinecraftClient client, ClientPlayerEntity player, double radius) {
        if (player == null || client.world == null) return null;
        Box box = player.getBoundingBox().expand(radius);
        List<Entity> entities = client.world.getOtherEntities(player, box, e -> isHostile(e));
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            double d = player.squaredDistanceTo(e);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = e;
            }
        }
        return nearest;
    }

    public static String normalizeEntityTypeName(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase();
        // Strip common prefixes/suffixes: "entity.minecraft.zombie" -> "zombie", "minecraft:zombie" -> "zombie"
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s;
    }

    public static void lookAtEntity(ClientPlayerEntity player, Entity target) {
        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getHeight() * 0.5) - player.getEyeY();
        double dz = target.getZ() - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(Math.atan2(-dy, distXZ)));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private static void lookAtPosition(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, distXZ));
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setPitch(clampPitch(pitch));
    }

    // ─── Build (schematic) action orchestration ──────────────────────────────────────────────────────

    /**
     * Self-executing build action.
     * Expects an already-validated schematic payload in the JSON (see
     * {@link BridgeSchematic#createProxy}). Hands the resulting schematic to
     * Baritone's BuilderProcess via reflection. Returns a human-readable
     * status string, not a Baritone command.
     */
    private static String executeBuildSchematicAction(String actionJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            return "build_schematic: not connected";
        }

        // Parse size + origin + palette + blocks
        String name = extractJsonString(actionJson, "name");
        if (name == null || name.isBlank()) name = "bridge_build";

        String originObj = extractJsonObject(actionJson, "origin");
        String sizeObj = extractJsonObject(actionJson, "size");
        if (originObj == null || sizeObj == null) return "build_schematic: missing origin/size";

        Integer ox = parseIntSafe(extractJsonPrimitive(originObj, "x"));
        Integer oy = parseIntSafe(extractJsonPrimitive(originObj, "y"));
        Integer oz = parseIntSafe(extractJsonPrimitive(originObj, "z"));
        Integer sx = parseIntSafe(extractJsonPrimitive(sizeObj, "x"));
        Integer sy = parseIntSafe(extractJsonPrimitive(sizeObj, "y"));
        Integer sz = parseIntSafe(extractJsonPrimitive(sizeObj, "z"));
        if (ox == null || oy == null || oz == null || sx == null || sy == null || sz == null) {
            return "build_schematic: invalid origin/size numbers";
        }
        if (sx <= 0 || sy <= 0 || sz <= 0) return "build_schematic: size must be positive";
        long total = (long) sx * (long) sy * (long) sz;
        if (total > 60_000L) return "build_schematic: size exceeds 60k block cap";

        List<String> palette = parseStringArray(extractJsonArrayRaw(actionJson, "palette"));
        if (palette == null || palette.isEmpty()) return "build_schematic: empty palette";

        short[] blocks;
        String b64 = extractJsonString(actionJson, "blocks");
        if (b64 != null && !b64.isBlank()) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(b64.trim());
                if (raw.length < total * 2) return "build_schematic: blocks payload too short";
                blocks = new short[(int) total];
                for (int i = 0; i < total; i++) {
                    int lo = raw[i * 2] & 0xFF;
                    int hi = raw[i * 2 + 1] & 0xFF;
                    blocks[i] = (short) (lo | (hi << 8));
                }
            } catch (IllegalArgumentException iae) {
                return "build_schematic: bad base64 blocks";
            }
        } else {
            String arr = extractJsonArrayRaw(actionJson, "blocks");
            if (arr == null) return "build_schematic: no blocks payload";
            List<String> ints = splitJsonArray(arr);
            if (ints.size() < total) return "build_schematic: blocks count < size";
            blocks = new short[(int) total];
            for (int i = 0; i < total; i++) {
                try {
                    blocks[i] = (short) Integer.parseInt(ints.get(i).trim());
                } catch (NumberFormatException nfe) {
                    return "build_schematic: non-integer block index";
                }
            }
        }

        final Object schematic = BridgeSchematic.createProxy(palette, blocks, sx, sy, sz);
        if (schematic == null) return "build_schematic: failed to build schematic proxy";

        final String buildName = name;
        final int fox = ox, foy = oy, foz = oz;
        final int fsx = sx, fsy = sy, fsz = sz;

        // Hand off on the render thread; BuilderProcess reads world state on tick.
        // BUT first spawn a launcher that waits for TaskQueue to drain so any
        // craft/mine/smelt actions queued before us finish first.
        final List<String> palettePass = palette;
        final short[] blocksPass = blocks;
        WorkerThreads.start("mindcraft-build-launcher",
                () -> buildLauncherWorker(buildName, schematic, fox, foy, foz, fsx, fsy, fsz,
                        palettePass, blocksPass));

        return "build_schematic: queued " + buildName + " (" + fsx + "x" + fsy + "x" + fsz + ")";
    }

    private static void buildLauncherWorker(String buildName, Object schematic,
                                            int fox, int foy, int foz,
                                            int fsx, int fsy, int fsz,
                                            List<String> palette, short[] blocks) {
        // ── Phase 1: wait for any prerequisite tasks already in the queue. ──
        long deadline = System.currentTimeMillis() + 15L * 60L * 1000L;
        boolean first = true;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
            String status = qs.status();
            if ("idle".equals(status) || "disabled".equals(status)) break;
            if ("paused".equals(status)) {
                sendBridgeMessage("[Bridge] Build: prerequisite task failed, aborting " + buildName
                        + ": " + (qs.lastFailure() == null ? "unknown" : qs.lastFailure()));
                return;
            }
            if (first) {
                sendBridgeMessage("[Bridge] Build: waiting for prerequisites (" + qs.pending() + " pending)");
                first = false;
            }
            sleepQuietly(1000);
        }

        // ── Phase 2: materials fence. ──────────────────────────────────────
        // Compare placed-block tally against live inventory. If we're short on
        // anything, enqueue make-steps for the delta and loop. This is STRICT:
        // if we can't gather everything within MAX_RETRIES rounds, we abort
        // instead of starting a half-finished build. A partial cabin is worse
        // than "sorry, I couldn't get wool" — the user asked for a house, not
        // a shell.
        MinecraftClient client = MinecraftClient.getInstance();
        int retries = 0;
        final int MAX_RETRIES = 3;
        Map<String, Integer> lastShortages = new LinkedHashMap<>();
        boolean fenceSatisfied = false;
        while (retries < MAX_RETRIES) {
            Map<String, Integer> needed = tallyPlacedBlocksAsItems(palette, blocks);
            if (needed.isEmpty()) { fenceSatisfied = true; break; }

            Map<String, Integer> shortages = ClientThread.call(() -> computeInventoryShortages(client.player, needed));
            if (shortages.isEmpty()) { fenceSatisfied = true; break; }
            lastShortages = shortages;

            // Short on something. Plan it and enqueue the make-steps.
            if (retries == 0) {
                sendBridgeMessage("[Bridge] Build: fence shortage — "
                        + formatShortages(shortages) + ". Topping up...");
            } else {
                sendBridgeMessage("[Bridge] Build: retry " + (retries + 1) + "/" + MAX_RETRIES
                        + " — still short " + formatShortages(shortages));
            }

            boolean queuedAny = false;
            boolean planFailure = false;
            StringBuilder planErrors = new StringBuilder();
            for (Map.Entry<String, Integer> e : shortages.entrySet()) {
                String item = e.getKey();
                int count = e.getValue();
                if (count <= 0) continue;
                Boolean connected2 = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return c.player != null && c.world != null;
                });
                if (!Boolean.TRUE.equals(connected2)) break;
                MakePlan plan = planMakeForInventory(client.player, item, count);
                if (!plan.isSuccess() || plan.steps.isEmpty()) {
                    planFailure = true;
                    if (planErrors.length() > 0) planErrors.append("; ");
                    planErrors.append(count).append("x ").append(item);
                    if (!plan.errors.isEmpty()) {
                        planErrors.append(" (").append(String.join(", ", plan.errors)).append(")");
                    }
                    continue;
                }
                for (MakeStep step : plan.steps) {
                    enqueueMakeStep(step);
                }
                queuedAny = true;
            }

            if (!queuedAny) {
                // Nothing at all could be queued — no provider for these items.
                sendBridgeMessage("[Bridge] Build: can't obtain "
                        + formatShortages(shortages)
                        + (planFailure ? " (" + planErrors + ")" : "")
                        + ". Aborting " + buildName + ".");
                return;
            }

            // Wait for the top-up queue to drain before re-tallying.
            long topupDeadline = System.currentTimeMillis() + 10L * 60L * 1000L;
            while (System.currentTimeMillis() < topupDeadline) {
                TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
                String status = qs.status();
                if ("idle".equals(status) || "disabled".equals(status)) break;
                if ("paused".equals(status)) {
                    sendBridgeMessage("[Bridge] Build: top-up task failed, aborting " + buildName
                            + ": " + (qs.lastFailure() == null ? "unknown" : qs.lastFailure()));
                    return;
                }
                sleepQuietly(1000);
            }
            retries++;
        }

        if (!fenceSatisfied) {
            // Materials still missing after MAX_RETRIES rounds. Do NOT build.
            // The user explicitly asked for a house and we can't fulfill it;
            // a half-built shell is worse than an honest failure.
            sendBridgeMessage("[Bridge] Build: gave up after " + MAX_RETRIES
                    + " top-up rounds — still short " + formatShortages(lastShortages)
                    + ". Not starting build of " + buildName + ".");
            return;
        }

        // ── Phase 3: launch on the render thread. ──────────────────────────
        ClientThread.run(() -> {
            boolean ok = invokeBuilderBuild(buildName, schematic, fox, foy, foz);
            if (ok) {
                sendBridgeMessage("[Bridge] Build: started " + buildName + " @ (" + fox + "," + foy + "," + foz + ") size " + fsx + "x" + fsy + "x" + fsz);
                WorkerThreads.start("mindcraft-build-watchdog", () -> buildWatchdogWorker(buildName));
            } else {
                sendBridgeMessage("[Bridge] Build: reflective invocation failed (is Baritone present?)");
            }
        });
    }

    /**
     * Tally the schematic's non-air blocks as item counts.
     * Doors + beds place two blocks from a single item; we approximate that by
     * counting *half* of matching-name blocks for door/bed entries so we don't
     * over-order. The templates actually only set the lower half so they're
     * unaffected; this is defensive for scanned-readback buildings that include
     * both halves.
     */
    /**
     * Tally the schematic's non-air blocks as item counts.
     *
     * Doors and beds span two cells in the schematic but one item. We divide
     * their raw cell count by 2 (rounded up) so the fence doesn't over-order.
     * Same for tall plants (large_fern, sunflower, etc.).
     */
    private static Map<String, Integer> tallyPlacedBlocksAsItems(List<String> palette, short[] blocks) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        if (palette == null || blocks == null) return raw;
        for (short idx : blocks) {
            int pi = idx & 0xFFFF;
            if (pi < 0 || pi >= palette.size()) continue;
            String id = palette.get(pi);
            if (id == null || id.isEmpty()) continue;
            String stripped = ItemIds.strip(id);
            if ("air".equals(stripped) || "cave_air".equals(stripped) || "void_air".equals(stripped)) continue;
            raw.merge(stripped, 1, Integer::sum);
        }
        // Halve two-cell items.
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            String item = e.getKey();
            int count = e.getValue();
            if (isTwoCellItem(item)) {
                count = (count + 1) / 2;
            }
            out.put(item, count);
        }
        return out;
    }

    private static boolean isTwoCellItem(String stripped) {
        return stripped.endsWith("_door")
                || stripped.endsWith("_bed")
                || "sunflower".equals(stripped)
                || "lilac".equals(stripped)
                || "rose_bush".equals(stripped)
                || "peony".equals(stripped)
                || "large_fern".equals(stripped)
                || "tall_grass".equals(stripped)
                || "small_dripleaf".equals(stripped);
    }

    /**
     * Compare needed items against current inventory. Returns only shortages.
     */
    private static Map<String, Integer> computeInventoryShortages(ClientPlayerEntity player,
                                                                  Map<String, Integer> needed) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (player == null) return out;
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            String item = e.getKey();
            int want = e.getValue();
            int have = InventoryDriver.countItem(player, "minecraft:" + item);
            if (have < want) out.put(item, want - have);
        }
        return out;
    }

    private static String formatShortages(Map<String, Integer> shortages) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : shortages.entrySet()) {
            if (shown > 0) sb.append(", ");
            sb.append(e.getValue()).append("x ").append(e.getKey());
            shown++;
            if (shown >= 5) {
                if (shortages.size() > shown) sb.append(" (+").append(shortages.size() - shown).append(" more)");
                break;
            }
        }
        return sb.toString();
    }

    private static String executeCancelBuildAction() {
        boolean ok = invokeBuilderCancel();
        return ok ? "cancel_build: builder stopped" : "cancel_build: failed or no active build";
    }

    /**
     * Reflectively call {@code baritone.api.IBaritone.getBuilderProcess().build(name, schematic, origin)}.
     * Uses {@code net.minecraft.util.math.BlockPos} as the {@code Vec3i} concrete type expected by
     * Baritone's signature (BlockPos is a Vec3i in Minecraft).
     */
    private static boolean invokeBuilderBuild(String name, Object schematic, int x, int y, int z) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object builder = baritone.getClass().getMethod("getBuilderProcess").invoke(baritone);
            if (builder == null) return false;

            Class<?> iSchematic = Class.forName("baritone.api.schematic.ISchematic");
            // Baritone signature: void build(String, ISchematic, Vec3i)
            // net.minecraft.util.math.BlockPos extends Vec3i.
            BlockPos origin = new BlockPos(x, y, z);

            // Find the (String, ISchematic, Vec3i-compatible) overload.
            for (java.lang.reflect.Method m : builder.getClass().getMethods()) {
                if (!"build".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3) continue;
                if (!p[0].equals(String.class)) continue;
                if (!iSchematic.isAssignableFrom(p[1])) continue;
                if (!p[2].isAssignableFrom(origin.getClass())) continue;
                m.invoke(builder, name, schematic, origin);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean invokeBuilderCancel() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object builder = baritone.getClass().getMethod("getBuilderProcess").invoke(baritone);
            if (builder == null) return false;
            // IBuilderProcess.onLostControl() / onPause() — simplest exit is to call onLostControl via the pause method
            try {
                builder.getClass().getMethod("pause").invoke(builder);
                return true;
            } catch (NoSuchMethodException nsm) {
                // Fallback: let Baritone's generic process cancel handle it
                Object mgr = baritone.getClass().getMethod("getPathingControlManager").invoke(baritone);
                if (mgr != null) {
                    mgr.getClass().getMethod("cancelEverything").invoke(mgr);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Polls BuilderProcess.isActive() and posts a single [Bridge] status line
     * when the builder goes idle. 15-minute hard cap. Runs off the render thread.
     * This does NOT interact with TaskQueue — build progress is exposed via
     * {@link CommandExecutor#isBuilderActive()} on the /state payload.
     */
    private static void buildWatchdogWorker(String name) {
        long deadline = System.currentTimeMillis() + 15L * 60L * 1000L;
        // Give the builder a beat to latch.
        sleepQuietly(1500);
        boolean everActive = false;
        while (System.currentTimeMillis() < deadline) {
            Boolean active = readBuilderActive();
            if (Boolean.TRUE.equals(active)) {
                everActive = true;
            } else if (everActive && Boolean.FALSE.equals(active)) {
                sendBridgeMessage("[Bridge] Build: " + name + " finished");
                return;
            }
            sleepQuietly(1000);
        }
        sendBridgeMessage("[Bridge] Build: " + name + " watchdog timeout");
    }

    /** Exposed for StateCollector. Returns null if Baritone isn't loaded. */
    public static Boolean isBuilderActive() {
        return readBuilderActive();
    }

    private static Boolean readBuilderActive() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object builder = baritone.getClass().getMethod("getBuilderProcess").invoke(baritone);
            if (builder == null) return null;
            Object active = builder.getClass().getMethod("isActive").invoke(builder);
            return (active instanceof Boolean) ? (Boolean) active : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // Read a JSON array value's raw substring (brackets included). Null if missing.
    private static String extractJsonArrayRaw(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('[', colon + 1);
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    // Split a JSON array body "[a, b, \"c\"]" into its element string list. Shallow splitter.
    public static List<String> splitJsonArray(String arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        String body = arr.trim();
        if (body.startsWith("[")) body = body.substring(1);
        if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                cur.append(c);
                if (c == '\\') {
                    if (i + 1 < body.length()) { cur.append(body.charAt(i + 1)); i++; }
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; cur.append(c); continue; }
            if (c == '[' || c == '{') { depth++; cur.append(c); continue; }
            if (c == ']' || c == '}') { depth--; cur.append(c); continue; }
            if (c == ',' && depth == 0) {
                String v = cur.toString().trim();
                if (!v.isEmpty()) out.add(v);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static List<String> parseStringArray(String arr) {
        if (arr == null) return null;
        List<String> items = splitJsonArray(arr);
        List<String> out = new ArrayList<>(items.size());
        for (String item : items) {
            String s = item.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
                s = s.replace("\\\"", "\"").replace("\\\\", "\\");
            }
            out.add(s);
        }
        return out;
    }

    private static Integer parseIntSafe(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException nfe) { return null; }
    }

    private static Integer requireInt(String json, String key) {
        return parseIntSafe(extractJsonPrimitive(json, key));
    }

    private static Integer optionalInt(String json, String key, Integer fallback) {
        Integer parsed = parseIntSafe(extractJsonPrimitive(json, key));
        return parsed != null ? parsed : fallback;
    }

    private static Float requireFloat(String json, String key) {
        String value = extractJsonPrimitive(json, key);
        if (value == null) return null;
        try {
            float f = Float.parseFloat(value.trim());
            return Float.isFinite(f) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double optionalDouble(String json, String key, Double fallback) {
        String value = extractJsonPrimitive(json, key);
        if (value == null) return fallback;
        try {
            double d = Double.parseDouble(value.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized > 180.0f) normalized -= 360.0f;
        if (normalized < -180.0f) normalized += 360.0f;
        return normalized;
    }

    private static Boolean optionalBoolean(String json, String key, Boolean fallback) {
        String value = extractJsonPrimitive(json, key);
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        return fallback;
    }

    private static SlotActionType parseSlotActionType(String raw) {
        if (raw == null || raw.isBlank()) return SlotActionType.PICKUP;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("CLICK".equals(normalized) || "LEFT".equals(normalized) || "RIGHT".equals(normalized)) return SlotActionType.PICKUP;
        if ("SHIFT_CLICK".equals(normalized)) return SlotActionType.QUICK_MOVE;
        try {
            return SlotActionType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Direction parseDirection(String raw) {
        if (raw == null || raw.isBlank()) return Direction.UP;
        try {
            return Direction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Hand parseHand(String raw) {
        if (raw == null || raw.isBlank()) return Hand.MAIN_HAND;
        String hand = raw.trim().toLowerCase(Locale.ROOT);
        return hand.equals("off") || hand.equals("offhand") || hand.equals("off_hand")
                ? Hand.OFF_HAND : Hand.MAIN_HAND;
    }

    private static String resolveKeyName(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (key) {
            case "forward", "w" -> "forward";
            case "back", "backward", "s" -> "back";
            case "left", "a" -> "left";
            case "right", "d" -> "right";
            case "jump", "space" -> "jump";
            case "sneak", "crouch", "shift" -> "sneak";
            case "sprint" -> "sprint";
            case "attack", "mouse1" -> "attack";
            case "use", "use_item", "mouse2" -> "use";
            case "drop", "q" -> "drop";
            case "inventory", "e" -> "inventory";
            default -> null;
        };
    }

    private static KeyBinding resolveKey(MinecraftClient client, String raw) {
        if (client == null || client.options == null) return null;
        String key = resolveKeyName(raw);
        if (key == null) return null;
        return switch (key) {
            case "forward" -> client.options.forwardKey;
            case "back" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            case "jump" -> client.options.jumpKey;
            case "sneak" -> client.options.sneakKey;
            case "sprint" -> client.options.sprintKey;
            case "attack" -> client.options.attackKey;
            case "use" -> client.options.useKey;
            case "drop" -> client.options.dropKey;
            case "inventory" -> client.options.inventoryKey;
            default -> null;
        };
    }

    private static String executeCraftAction(String actionJson) {
        return executeCraftBatch(java.util.List.of(actionJson));
    }

    /**
     * Plan and queue one or more craft actions using a shared MakePlan.
     * Contiguous craft actions in a batch share one inventory snapshot and
     * one planning pass, so dependencies (e.g. iron for pickaxe + sword)
     * are accounted for together. Compatible MINE and SMELT steps are
     * coalesced (e.g. two #mine iron_ore steps become one).
     */
    static String executeCraftBatch(java.util.List<String> actionJsons) {
        // Parse all requests off the render thread
        java.util.List<CraftRequest> requests = new java.util.ArrayList<>();
        java.util.List<String> parseErrors = new java.util.ArrayList<>();
        for (String json : actionJsons) {
            String itemName = extractJsonString(json, "item");
            if (itemName == null || itemName.isBlank()) {
                parseErrors.add("craft: missing 'item' field");
                continue;
            }
            String countStr = extractJsonPrimitive(json, "count");
            int count = 1;
            if (countStr != null && !countStr.isBlank()) {
                try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
            }
            if (count < 1) count = 1;
            requests.add(new CraftRequest(itemName, count));
        }

        // Reject entire batch if any craft action has parse errors.
        // This prevents silently accepting later valid actions while earlier
        // invalid ones were ignored — the batch is semantically atomic.
        if (!parseErrors.isEmpty()) {
            return "craft: batch rejected - " + String.join("; ", parseErrors);
        }

        if (requests.isEmpty()) {
            return "craft: could not plan - no valid actions";
        }

        MinecraftClient client = MinecraftClient.getInstance();
        // Move null check inside ClientThread to avoid accessing player on HTTP thread
        Object connectionCheck;
        try {
            connectionCheck = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                if (p == null || MinecraftClient.getInstance().world == null) {
                    return "craft: not connected";
                }
                return null;
            });
        } catch (RuntimeException e) {
            return "craft: error checking connection - " + e.getMessage();
        }
        if (connectionCheck instanceof String s) return s;

        // â”€â”€ Phase 1: seed plan + normalize all item keys on render thread
        Object seedResult;
        try {
            seedResult = ClientThread.call(() -> {
                ClientPlayerEntity player = client.player;
                if (player == null || client.world == null) {
                    return "craft: not connected";
                }

                java.util.List<String> normalizedKeys = new java.util.ArrayList<>();
                for (CraftRequest req : requests) {
                    String rawItem = req.itemName;
                    Identifier itemId = rawItem.contains(":")
                            ? Identifier.tryParse(rawItem)
                            : Identifier.of("minecraft", rawItem);
                    if (itemId == null) {
                        return "craft: invalid item " + rawItem;
                    }
                    String itemKey = itemId.toString();
                    if (itemKey.startsWith("minecraft:")) itemKey = itemKey.substring(10);
                    String normKey = normalizeCraftTargetForInventory(player, itemKey);
                    if (!normKey.equals(itemKey)) {
                        player.sendMessage(net.minecraft.text.Text.literal(
                                "[Bridge] Using " + normKey + " instead of " + itemKey
                                        + " based on inventory."), false);
                    }
                    normalizedKeys.add(normKey);
                }

                MakePlan seed = new MakePlan();
                snapshotInventory(player, seed);
                if (WorkstationFinder.findNearestStation(client.world, player, "crafting_table") != null) {
                    seed.nearbyStations.add("crafting_table");
                }
                if (WorkstationFinder.findNearestStation(client.world, player, "furnace") != null) {
                    seed.nearbyStations.add("furnace");
                }

                return new Object[]{seed, normalizedKeys};
            });
        } catch (RuntimeException e) {
            return "craft: error seeding plan - " + e.getMessage();
        }

        if (seedResult instanceof String str) return str;
        Object[] pair = (Object[]) seedResult;
        MakePlan plan = (MakePlan) pair[0];
        java.util.List<String> planKeys = (java.util.List<String>) pair[1];

        // â”€â”€ Phase 2: recursive planning for each request in order
        java.util.List<String> phaseErrors = new java.util.ArrayList<>();
        for (int i = 0; i < planKeys.size(); i++) {
            String itemKey = planKeys.get(i);
            CraftRequest req = requests.get(i);
            try {
                makeItem(plan, ItemIds.normalize(itemKey), req.count, 0);
            } catch (Exception e) {
                phaseErrors.add("error planning " + itemKey + ": " + e.getMessage());
            }
        }

        if (!plan.isSuccess()) {
            String errMsg = phaseErrors.isEmpty()
                    ? String.join("; ", plan.errors)
                    : String.join("; ", phaseErrors);
            if (plan.steps.isEmpty()) {
                return "craft: could not plan - " + errMsg;
            }
            phaseErrors.addAll(plan.errors);
        }

        // â”€â”€ Coalesce compatible steps
        java.util.List<MakeStep> coalesced = coalesceMakeSteps(plan.steps);
        plan.steps.clear();
        plan.steps.addAll(coalesced);

        // â”€â”€ Phase 3: enqueue the first step and post the plan summary back to the client
        java.util.List<CraftGoal> goals = new java.util.ArrayList<>();
        for (int i = 0; i < planKeys.size(); i++) {
            goals.add(new CraftGoal(ItemIds.normalize(planKeys.get(i)), requests.get(i).count));
        }
        final String summary = describeMakePlan(plan.steps);
        ClientThread.run(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Planned: " + summary), false);
            }
            planAndExecuteCraftContinuations(goals, 50 * Math.max(1, goals.size()));
        });

        String result = "craft: queued 1 step(s) (continuation-based); " + summary;
        if (!phaseErrors.isEmpty()) {
            result += " (warnings: " + String.join("; ", phaseErrors) + ")";
        }
        return result;
    }

    private static void watchForCraftingScreen(MinecraftClient client, Runnable craftAction) {
        WorkerThreads.start("mindcraft-craft-screen-watcher", () -> {
            for (int wait = 0; wait < 200; wait++) {
                if (Boolean.TRUE.equals(ClientThread.call(() -> {
                    ClientPlayerEntity player = client.player;
                    return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
                }))) {
                    ClientThread.call(() -> {
                        craftAction.run();
                        return null;
                    });
                    return;
                }
                sleep(50);
            }
            sendBridgeMessage("[Bridge] Crafting failed: crafting table screen never opened.");
        });
    }

    private static void craftPostAction(String itemName, int count) {
        craftPostAction(itemName, count, null);
    }

    private static void craftPostAction(String itemName, int count, Runnable continuation) {
        WorkerThreads.start("mindcraft-craft-runner", () -> craftPostActionWorkerWithContinuation(itemName, count, continuation));
    }

    public enum MakeStepKind { MINE, SMELT, CRAFT }

    public record MakeStep(MakeStepKind kind, String itemName, int count, String command) {}

    record IngredientNeed(List<String> patterns, int count) {}

    public record GatherProvider(List<String> mineTargets, String producedItem) {
        public String primaryTarget() { return mineTargets.isEmpty() ? producedItem : mineTargets.get(0); }
        public String mineTarget() { return primaryTarget(); }
        public String mineCommand(int count) {
            return "#mine " + count + " " + String.join(" ", mineTargets);
        }
    }

    private record CraftRequest(String itemName, int count) {}
    private record CraftGoal(String itemName, int count) {}
    static final int CRAFT_PATHING_BLOCK_RESERVE = 5;
    private static final Set<String> CRAFT_PATHING_RESERVE_ITEMS = Set.of(
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:blackstone",
            "minecraft:dirt",
            "minecraft:netherrack"
    );

    public static class MakePlan {
        final List<MakeStep> steps = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Map<String, Integer> virtualInventory = new HashMap<>();
        final Map<String, Integer> snapshotInventory = new HashMap<>();
        final Set<String> reservedTools = new HashSet<>();
        final Set<String> stations = new HashSet<>();
        // Stations already present in the world near the player, pre-probed on the
        // render thread so the off-thread planner never has to scan blocks.
        final Set<String> nearbyStations = new HashSet<>();
        // Items currently being planned — used for cycle detection. Normalized ids.
        final Set<String> inProgress = new HashSet<>();

        boolean isSuccess() {
            return errors.isEmpty();
        }

        void addVirtual(String itemId, int count) {
            if (count <= 0) return;
            virtualInventory.merge(ItemIds.normalize(itemId), count, Integer::sum);
        }

        int countVirtual(String itemId) {
            return virtualInventory.getOrDefault(ItemIds.normalize(itemId), 0);
        }

        void addSnapshot(String itemId, int count) {
            if (count <= 0) return;
            snapshotInventory.merge(ItemIds.normalize(itemId), count, Integer::sum);
        }

        int countSnapshot(String itemId) {
            return snapshotInventory.getOrDefault(ItemIds.normalize(itemId), 0);
        }

        int consumeItem(String itemId, int count) {
            if (count <= 0) return 0;
            String normalized = ItemIds.normalize(itemId);
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

        boolean hasItem(String itemId) {
            String norm = ItemIds.normalize(itemId);
            if (virtualInventory.getOrDefault(norm, 0) > 0) return true;
            if (reservedTools.contains(norm)) return true;
            return false;
        }

        boolean hasEquivalentOrBetterTool(String minTool) {
            if (!minTool.endsWith("_pickaxe")) return hasItem(minTool);
            int minIdx = PICKAXE_TIERS.indexOf(minTool);
            if (minIdx < 0) return hasItem(minTool);
            for (int i = minIdx; i < PICKAXE_TIERS.size(); i++) {
                if (hasItem(PICKAXE_TIERS.get(i))) return true;
            }
            return false;
        }

        void reserveTool(String toolId) {
            reservedTools.add(ItemIds.normalize(toolId));
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

    /**
     * Continuation-based craft executor.
     * After each prerequisite step completes, re-plans from current inventory
     * and enqueues only the next missing prerequisite. Stops when the target
     * item count is satisfied or planning genuinely fails.
     */
    private static void planAndExecuteCraftContinuation(String targetItem, int targetCount, int remainingSteps) {
        planAndExecuteCraftContinuations(
                java.util.List.of(new CraftGoal(targetItem, targetCount)),
                remainingSteps);
    }

    private static void planAndExecuteCraftContinuations(java.util.List<CraftGoal> goals, int remainingSteps) {
        if (goals == null || goals.isEmpty()) return;
        CraftGoal activeGoal = null;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            sendBridgeMessage("[Bridge] Craft failed: not connected");
            return;
        }
        for (CraftGoal goal : goals) {
            if (InventoryDriver.countItem(player, goal.itemName()) < goal.count()) {
                activeGoal = goal;
                break;
            }
        }
        if (activeGoal == null) {
            sendBridgeMessage("[Bridge] make-continuation: all goals already met");
            return;
        }
        planAndExecuteCraftContinuation(activeGoal.itemName(), activeGoal.count(), remainingSteps,
                () -> planAndExecuteCraftContinuations(goals, remainingSteps - 1));
    }

    private static void planAndExecuteCraftContinuation(String targetItem, int targetCount, int remainingSteps, Runnable continuation) {
        sendBridgeMessage("[Bridge] make-continuation fired for " + targetItem);
        if (remainingSteps <= 0) {
            sendBridgeMessage("[Bridge] Craft plan exceeded max steps for " + ItemIds.strip(targetItem));
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            sendBridgeMessage("[Bridge] Craft failed: not connected");
            return;
        }
        if (InventoryDriver.countItem(player, targetItem) >= targetCount) {
            sendBridgeMessage("[Bridge] make-continuation: goal already met for " + ItemIds.strip(targetItem));
            return;
        }
        MakePlan plan = new MakePlan();
        snapshotInventory(player, plan);
        if (MinecraftClient.getInstance().world != null) {
            if (WorkstationFinder.findNearestStation(MinecraftClient.getInstance().world, player, "crafting_table") != null) {
                plan.nearbyStations.add("crafting_table");
            }
            if (WorkstationFinder.findNearestStation(MinecraftClient.getInstance().world, player, "furnace") != null) {
                plan.nearbyStations.add("furnace");
            }
        }
        makeItem(plan, targetItem, targetCount, 0);
        if (!plan.isSuccess() || plan.steps.isEmpty()) {
            String err = plan.errors.isEmpty() ? "no steps generated" : String.join("; ", plan.errors);
            sendBridgeMessage("[Bridge] Craft plan failed for " + ItemIds.strip(targetItem) + ": " + err);
            return;
        }
        List<MakeStep> coalesced = coalesceMakeSteps(plan.steps);
        MakeStep firstStep = coalesced.get(0);
        sendBridgeMessage("[Bridge] make-continuation: next step = "
                + firstStep.kind() + " " + firstStep.command());
        if (firstStep.kind() == MakeStepKind.CRAFT) {
            // For CRAFT steps, the continuation must run AFTER the craft
            // thread finishes (i.e., after completeActiveIf), not in the
            // Baritone-idle callback. Pass continuation to craftPostAction
            // so it fires when items are actually in inventory.
            craftPostActionWithContinuation(firstStep.itemName(), firstStep.count(), continuation);
        } else if (firstStep.command() != null && !firstStep.command().isBlank()) {
            TaskQueue.getInstance().enqueueWithCallback(firstStep.command(), continuation);
        }
    }

    private static void craftPostActionWithContinuation(String itemName, int count, Runnable continuation) {
        AtomicBoolean craftStarted = new AtomicBoolean(false);
        Runnable craftOnce = () -> {
            if (craftStarted.compareAndSet(false, true)) {
                craftPostAction(itemName, count, continuation);
            }
        };
        TaskQueue.getInstance().enqueueWithCallback("#craft", craftOnce);
    }

    /**
     * Coalesce compatible MINE and SMELT steps in a craft plan.
     * Simple overworld #mine N <target> steps with the same target block
     * are merged into one step with the larger target-total count. Same for
     * #task smelt <input> N steps with the same input. Portal-related
     * gotos and nether-target mines are left untouched.
     */
    static java.util.List<MakeStep> coalesceMakeSteps(java.util.List<MakeStep> steps) {
        java.util.List<MakeStep> result = new java.util.ArrayList<>();
        for (MakeStep step : steps) {
            if (isSimpleMineStep(step)) {
                String targets = extractMineTargets(step.command());
                if (targets != null && tryMergeMineStep(result, step, targets)) continue;
            } else if (isSimpleSmeltStep(step)) {
                String input = extractSmeltInput(step.command());
                if (input != null && tryMergeSmeltStep(result, step, input)) continue;
            }
            result.add(step);
        }
        return result;
    }

    private static boolean isSimpleMineStep(MakeStep step) {
        return step.kind() == MakeStepKind.MINE
                && step.command() != null
                && step.command().startsWith("#mine ");
    }

    private static boolean isSimpleSmeltStep(MakeStep step) {
        return step.kind() == MakeStepKind.SMELT
                && step.command() != null
                && step.command().startsWith("#task smelt ");
    }

    private static String extractMineTargets(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3) return null;
        return String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
    }

    private static String extractSmeltInput(String command) {
        String[] parts = command.split(" ");
        return parts.length >= 3 ? parts[2] : null;
    }

    /**
     * Find the earliest MINE step in the result list with the same mine
     * targets and merge the target-total count into it. Baritone #mine N is
     * target-total inventory, not "mine N more", so the merged count must be
     * max(existing, next) instead of a sum.
     */
    private static boolean tryMergeMineStep(java.util.List<MakeStep> result, MakeStep step, String targets) {
        for (int i = 0; i < result.size(); i++) {
            MakeStep existing = result.get(i);
            if (isSimpleMineStep(existing) && targets.equals(extractMineTargets(existing.command()))) {
                int newCount = Math.max(existing.count(), step.count());
                result.set(i, new MakeStep(MakeStepKind.MINE, existing.itemName(), newCount,
                        "#mine " + newCount + " " + targets));
                return true;
            }
        }
        return false;
    }

    /**
     * Find the earliest SMELT step in the result list with the same smelt
     * input and merge the count into it. Returns true if merged.
     */
    private static boolean tryMergeSmeltStep(java.util.List<MakeStep> result, MakeStep step, String input) {
        for (int i = 0; i < result.size(); i++) {
            MakeStep existing = result.get(i);
            if (isSimpleSmeltStep(existing) && input.equals(extractSmeltInput(existing.command()))) {
                int newCount = existing.count() + step.count();
                result.set(i, new MakeStep(MakeStepKind.SMELT, existing.itemName(), newCount,
                        "#task smelt " + input + " " + newCount));
                return true;
            }
        }
        return false;
    }

    private static MakePlan planMakeForInventory(ClientPlayerEntity player, String itemKey, int targetCount) {
        MakePlan plan = new MakePlan();
        // SAFE: snapshotInventory reads player.getInventory() which must be on client thread
        ClientThread.call(() -> { snapshotInventory(player, plan); return null; });
        makeItem(plan, ItemIds.normalize(itemKey), targetCount, 0);
        return plan;
    }

    private static boolean makeItem(MakePlan plan, String itemId, int count, int depth) {
        if (!plan.errors.isEmpty()) return false;
        if (count <= 0) return true;
        // Hard cap is only a safety net for runaway recursion. Real cycles are caught
        // by plan.inProgress below. Diamond's full chain (6+ tiers × ~4 frames each)
        // fits comfortably under 32.
        if (depth > 32) {
            plan.errors.add("recipe chain too deep at " + ItemIds.strip(itemId));
            return false;
        }

        String normalized = ItemIds.normalize(itemId);

        // Cycle detection: if we're already trying to plan this exact item further up
        // the stack, we have a real dependency loop (e.g. A needs B, B needs A).
        if (!plan.inProgress.add(normalized)) {
            plan.errors.add("recipe cycle detected at " + ItemIds.strip(normalized));
            return false;
        }
        try {
            return makeItemInner(plan, normalized, count, depth);
        } finally {
            plan.inProgress.remove(normalized);
        }
    }

    private static boolean makeItemInner(MakePlan plan, String normalized, int count, int depth) {
        int missing = plan.consumeItem(normalized, count);
        if (missing <= 0) return true;

        // If the item is an ore drop, check if we have the raw ore block in inventory
        List<String> oreBlocks = ORE_BLOCK_ALIASES.get(normalized);
        if (oreBlocks != null && missing > 0) {
            for (String oreBlock : oreBlocks) {
                int oreHave = plan.countVirtual(oreBlock);
                if (oreHave > 0) {
                    int toConsume = Math.min(oreHave, missing);
                    plan.consumeItem(oreBlock, toConsume);
                    plan.addVirtual(normalized, toConsume);
                    missing -= toConsume;
                    if (missing <= 0) return true;
                }
            }
        }

        String itemKey = ItemIds.strip(normalized);

        // Smithing gate: netherite gear (and any future smithing outputs) must
        // go through a SmithingScreenHandler, which the bridge doesn't drive
        // yet. Fail fast with a clear message instead of letting the planner
        // pick the minecraft-data fake crafting recipe (legacy pre-1.20 one).
        SmithingRecipe smithing = SMITHING_RECIPES.get(itemKey);
        if (smithing != null) {
            plan.errors.add("smithing " + ItemIds.strip(smithing.output())
                    + " requires the 'smith' action (not yet integrated into craft planner)");
            return false;
        }

        RecipeData recipe = RECIPE_DATABASE.get(itemKey);

        // Skip storage conversion recipes (block↔ingot↔nugget, raw_block↔raw)
        // when the input isn't already available — these cause reversible cycles
        // (e.g., iron_ingot → 9 iron_nugget → 1 iron_ingot) and should fall through
        // to gather/smelt providers for the raw material.
        if (recipe != null && isStorageConversionRecipe(itemKey, recipe)) {
            if (!hasAnyInputInInventory(plan, recipe)) {
                recipe = null;
            }
        }

        if (recipe != null) {
            int outputCount = Math.max(1, recipe.outputCount);
            int batches = Math.max(1, (int) Math.ceil(missing / (double) outputCount));
            for (IngredientNeed ingredient : aggregateRecipeIngredients(recipe, batches)) {
                if (!makeIngredient(plan, ingredient.patterns(), ingredient.count(), depth + 1)) {
                    return false;
                }
            }
            ensureStation(plan, "crafting_table");
            plan.steps.add(new MakeStep(MakeStepKind.CRAFT, itemKey, missing, null));
            plan.addVirtual(normalized, batches * outputCount);
            plan.consumeItem(normalized, missing);
            return true;
        }

        // Tier priority: gather BEFORE smelting when the output has a direct
        // gather provider. Many ores drop their item (diamond, coal, redstone,
        // emerald, lapis, quartz) when mined with the right tool — that's the
        // obvious, fast path. Smelting falls back only for ores that don't
        // drop their ingot directly (iron, gold, copper raw ores).
        GatherProvider gatherEarly = GATHER_PROVIDERS.get(normalized);
        if (gatherEarly != null) {
            return emitGatherStep(plan, normalized, gatherEarly, missing, depth);
        }

        SmeltRecipe smelt = chooseSmeltRecipeForOutput(plan, normalized);
        if (smelt != null) {
            return emitSmeltPlan(plan, normalized, smelt, missing, depth);
        }

        GatherProvider gather = GATHER_PROVIDERS.get(normalized);
        if (gather != null) {
            return emitGatherStep(plan, normalized, gather, missing, depth);
        }

        plan.errors.add("no recipe, smelt path, or gather provider for " + ItemIds.strip(normalized));
        return false;
    }

    /**
     * Emit the mine step + any tool/portal prereqs for a gather provider.
     * Extracted so it can be called from the early gather-first tier and the
     * later fallback tier without duplicating logic.
     */
    private static boolean emitGatherStep(MakePlan plan, String normalized,
                                          GatherProvider gather, int missing, int depth) {
        String primaryTarget = gather.primaryTarget();
        String requiredTool = MINE_TOOL_REQUIREMENTS.get(primaryTarget);
        if (requiredTool != null && !plan.hasEquivalentOrBetterTool(requiredTool)) {
            if (!makeItem(plan, requiredTool, 1, depth + 1)) {
                plan.errors.add("Need " + requiredTool + " to mine " + primaryTarget
                        + " but couldn't plan one");
                return false;
            }
            plan.reserveTool(requiredTool);
        }
        boolean inNether = playerIsInNether();
        boolean isNetherTarget = gather.mineTargets().stream().anyMatch(t -> isNetherGatherTarget(t));
        boolean needsEnterPortal = isNetherTarget && !inNether;
        boolean needsExitPortal = !isNetherTarget && inNether;
        if (needsEnterPortal) {
            plan.steps.add(new MakeStep(MakeStepKind.MINE, "nether_portal", 1, "#goto nether_portal"));
        }
        if (needsExitPortal) {
            plan.steps.add(new MakeStep(MakeStepKind.MINE, "overworld_portal", 1, "#goto nether_portal"));
        }
        int mineTargetTotal = baritoneMineTargetTotal(plan, gather, missing);
        plan.steps.add(new MakeStep(MakeStepKind.MINE, ItemIds.strip(gather.producedItem()), mineTargetTotal,
                gather.mineCommand(mineTargetTotal)));
        plan.addVirtual(gather.producedItem(), missing);
        plan.consumeItem(normalized, missing);
        if (needsEnterPortal) {
            plan.steps.add(new MakeStep(MakeStepKind.MINE, "overworld_portal", 1, "#goto nether_portal"));
        }
        if (needsExitPortal) {
            plan.steps.add(new MakeStep(MakeStepKind.MINE, "nether_portal", 1, "#goto nether_portal"));
        }
        return true;
    }

    static int baritoneMineTargetTotal(MakePlan plan, GatherProvider gather, int missing) {
        int current = plan == null ? 0 : plan.countSnapshot(gather.producedItem());
        int reserve = craftPathingReserveFor(gather.producedItem());
        return Math.max(1, current + Math.max(0, missing) + reserve);
    }

    static String bridgeMineCommandForGather(MakePlan plan, GatherProvider gather, int missing) {
        return gather.mineCommand(baritoneMineTargetTotal(plan, gather, missing));
    }

    static int craftPathingReserveFor(String itemId) {
        String normalized = ItemIds.normalize(itemId);
        return CRAFT_PATHING_RESERVE_ITEMS.contains(normalized) ? CRAFT_PATHING_BLOCK_RESERVE : 0;
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
            String exact = ItemIds.normalize(pattern);
            if (RECIPE_DATABASE.containsKey(ItemIds.strip(exact))
                    || chooseSmeltRecipeForOutput(plan, exact) != null
                    || GATHER_PROVIDERS.containsKey(exact)) {
                return exact;
            }
        }
        return null;
    }

    private static String choosePlanksForPlan(MakePlan plan) {
        for (String wood : WOOD_TYPES) {
            String planks = ItemIds.normalize(wood + "_planks");
            if (plan.countVirtual(planks) > 0) return planks;
        }
        for (String wood : WOOD_TYPES) {
            String log = ItemIds.normalize(wood + "_log");
            if (plan.countVirtual(log) > 0) return ItemIds.normalize(wood + "_planks");
        }
        return ItemIds.normalize("oak_planks");
    }

    private static boolean isStorageConversionRecipe(String itemKey, RecipeData recipe) {
        if (recipe == null || recipe.slots == null) return false;
        Set<String> inputs = new HashSet<>();
        for (GridSlot slot : recipe.slots) {
            for (String pattern : slot.ingredientPatterns) {
                inputs.add(ItemIds.strip(pattern));
            }
        }
        return switch (itemKey) {
            case "iron_ingot" -> inputs.contains("iron_nugget") || inputs.contains("iron_block");
            case "iron_nugget" -> inputs.contains("iron_ingot");
            case "iron_block" -> inputs.contains("iron_ingot");
            case "gold_ingot" -> inputs.contains("gold_nugget") || inputs.contains("gold_block");
            case "gold_nugget" -> inputs.contains("gold_ingot");
            case "gold_block" -> inputs.contains("gold_ingot");
            case "copper_ingot" -> inputs.contains("copper_block");
            case "copper_block" -> inputs.contains("copper_ingot");
            case "raw_iron" -> inputs.contains("raw_iron_block");
            case "raw_iron_block" -> inputs.contains("raw_iron");
            case "raw_gold" -> inputs.contains("raw_gold_block");
            case "raw_gold_block" -> inputs.contains("raw_gold");
            case "raw_copper" -> inputs.contains("raw_copper_block");
            case "raw_copper_block" -> inputs.contains("raw_copper");
            default -> false;
        };
    }

    private static boolean hasAnyInputInInventory(MakePlan plan, RecipeData recipe) {
        for (GridSlot slot : recipe.slots) {
            for (String pattern : slot.ingredientPatterns) {
                String stripped = ItemIds.strip(pattern);
                if (stripped.startsWith("*")) return false;
                if (plan.countVirtual(stripped) > 0) return true;
                if (plan.countVirtual(ItemIds.normalize(stripped)) > 0) return true;
            }
        }
        return false;
    }

    private static boolean emitSmeltPlan(MakePlan plan, String normalized, SmeltRecipe smelt, int missing, int depth) {
        if (!makeItem(plan, smelt.input(), missing, depth + 1)) {
            return false;
        }
        if (!reserveFuelForPlan(plan, missing, smelt.input(), depth)) {
            return false;
        }
        ensureStation(plan, "furnace");
        String inputKey = ItemIds.strip(smelt.input());
        plan.steps.add(new MakeStep(MakeStepKind.SMELT, inputKey, missing,
                "#task smelt " + inputKey + " " + missing));
        plan.addVirtual(normalized, missing);
        plan.consumeItem(normalized, missing);
        return true;
    }

    private static SmeltRecipe chooseSmeltRecipeForOutput(MakePlan plan, String outputItemId) {
        String output = ItemIds.normalize(outputItemId);
        List<SmeltRecipe> candidates = new ArrayList<>();
        for (SmeltRecipe recipe : SMELT_RECIPES.values()) {
            if (recipe.output().equals(output)) {
                candidates.add(recipe);
            }
        }
        candidates.sort(Comparator.comparing(SmeltRecipe::input));
        // 1. Prefer inputs we already have (virtually).
        for (SmeltRecipe recipe : candidates) {
            if (plan.countVirtual(recipe.input()) > 0) return recipe;
        }
        // 2. Prefer inputs with a direct gather provider.
        for (SmeltRecipe recipe : candidates) {
            if (GATHER_PROVIDERS.containsKey(recipe.input())) return recipe;
        }
        // 3. Prefer inputs that are reachable transitively via another smelt
        //    whose input is gatherable (raw_iron ← iron_ore, for example).
        for (SmeltRecipe recipe : candidates) {
            if (isInputTransitivelyReachable(recipe.input(), 0)) return recipe;
        }
        // 4. Last resort: first alphabetical candidate, even if unreachable.
        //    The make planner will fail with a clean "no provider" message
        //    if nothing can satisfy it.
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Cheap transitive reachability check used to prefer smelt inputs that
     * actually have a source. Returns true when the item is gatherable, has
     * a recipe whose ingredients are each reachable, or smelts from a
     * reachable input. Depth-bounded to avoid cycles.
     */
    private static boolean isInputTransitivelyReachable(String itemId, int depth) {
        if (depth > 4) return false;
        String normalized = ItemIds.normalize(itemId);
        if (GATHER_PROVIDERS.containsKey(normalized)) return true;
        String key = ItemIds.strip(normalized);
        RecipeData recipe = RECIPE_DATABASE.get(key);
        if (recipe != null) {
            // Accept if at least one ingredient-variant in every slot is reachable.
            for (GridSlot slot : recipe.slots) {
                boolean any = false;
                for (String p : slot.ingredientPatterns) {
                    if (p.startsWith("*")) { any = true; break; } // wildcard accepts many
                    if (isInputTransitivelyReachable(p, depth + 1)) { any = true; break; }
                }
                if (!any) return false;
            }
            return true;
        }
        for (SmeltRecipe s : SMELT_RECIPES.values()) {
            if (s.output().equals(normalized)) {
                if (isInputTransitivelyReachable(s.input(), depth + 1)) return true;
            }
        }
        return false;
    }

    private static int fuelItemsNeededForPlan(MakePlan plan, int smeltItems, String avoidItemId) {
        int availableCapacity = 0;
        String avoid = ItemIds.normalize(avoidItemId);
        for (Map.Entry<String, Integer> entry : plan.virtualInventory.entrySet()) {
            if (entry.getKey().equals(avoid)) continue;
            availableCapacity += entry.getValue() * fuelCapacityItems(entry.getKey());
        }
        int missingCapacity = Math.max(0, smeltItems - availableCapacity);
        if (missingCapacity <= 0) return 0;
        return Math.max(1, (int) Math.ceil(missingCapacity / 8.0));
    }

    private static boolean reserveFuelForPlan(MakePlan plan, int smeltItems, String avoidItemId, int depth) {
        int remainingCapacity = Math.max(0, smeltItems);
        String avoid = ItemIds.normalize(avoidItemId);
        List<String> fuels = new ArrayList<>(plan.virtualInventory.keySet());
        fuels.sort((a, b) -> {
            int capCompare = Integer.compare(fuelCapacityItems(b), fuelCapacityItems(a));
            return capCompare != 0 ? capCompare : a.compareTo(b);
        });

        for (String fuel : fuels) {
            if (remainingCapacity <= 0) return true;
            if (fuel.equals(avoid)) continue;
            int capacity = fuelCapacityItems(fuel);
            if (capacity <= 0) continue;
            int available = plan.countVirtual(fuel);
            int needed = Math.min(available, (int) Math.ceil(remainingCapacity / (double) capacity));
            if (needed <= 0) continue;
            plan.consumeItem(fuel, needed);
            remainingCapacity -= needed * capacity;
        }

        if (remainingCapacity <= 0) return true;

        int coalNeeded = Math.max(1, (int) Math.ceil(remainingCapacity / 8.0));
        if (!makeItem(plan, "minecraft:coal", coalNeeded, depth + 1)) {
            return false;
        }
        plan.consumeItem("minecraft:coal", coalNeeded);
        return true;
    }

    private static void snapshotInventory(ClientPlayerEntity player, MakePlan plan) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                String itemId = ItemIds.fromStack(stack);
                plan.addVirtual(itemId, stack.getCount());
                plan.addSnapshot(itemId, stack.getCount());
            }
        }
    }

    // Bridge-owned smelting orchestration. Public command remains:
    // #task smelt <input> [count]

    public static void runSmeltTask(String command) {
        WorkerThreads.start("mindcraft-smelt-runner", () -> smeltTaskWorker(command));
    }

    public record SmeltRecipe(String input, String output, boolean allowFurnace,
                                boolean allowBlast, boolean allowSmoker) {}

    /**
     * Vanilla smithing table recipe: a base item combined with an addition
     * (typically netherite_ingot) and a template item on a smithing table.
     * Execution requires the smithing screen handler worker (not yet
     * implemented). The planner surfaces a clear error when asked for a
     * smithing output so the caller knows why.
     */
    public record SmithingRecipe(String output, String template, String base, String addition) {}

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

            String resolvedInput = ClientThread.call(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? ItemIds.normalize(request.input())
                        : resolveSmeltInputForInventory(player, request.input());
            });
            SmeltRecipe recipe = SMELT_RECIPES.get(resolvedInput);
            if (recipe == null) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no recipe for " + request.input());
                return;
            }

            int availableInput = ClientThread.call(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? 0 : InventoryDriver.countItem(player, recipe.input());
            });
            int targetCount = request.count() == null ? availableInput : Math.min(request.count(), availableInput);
            if (targetCount <= 0) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no " + recipe.input() + " in inventory");
                return;
            }

            int fuelCapacity = ClientThread.call(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                return player == null ? 0 : countAvailableFuelCapacity(player, recipe.input());
            });
            if (fuelCapacity <= 0) {
                TaskQueue.getInstance().failActiveIf(normalizedCommand, "smelt: no fuel available");
                return;
            }

            sendBridgeMessage("[Bridge] Smelting " + targetCount + "x " + recipe.input()
                    + " -> " + recipe.output() + " using nearby furnaces.");

            List<BlockPos> candidates = ClientThread.call(() -> scanSmeltFurnaces(recipe, 16));
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

            // Pull any pre-existing output out of each furnace BEFORE we measure the
            // baseline. Otherwise the first collect pass sweeps those items into the
            // player inventory and the delta (afterOutput - beforeOutput) credits
            // them as if we just smelted them, causing the task to signal "complete"
            // before our loaded input has actually cooked.
            // Unconditional — collectFurnaceOutput is a no-op when slot 2 is empty.
            for (FurnacePlan plan : plans) {
                collectFurnaceOutput(plan.info.pos, recipe);
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
            ClientThread.call(() -> {
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
        String input = ItemIds.normalize(parts[2]);
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
            return ClientThread.call(() -> {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                if (player == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler furnace)) {
                    return null;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack input = screen.getSlot(0).getStack();
                ItemStack output = screen.getSlot(2).getStack();
                if (!input.isEmpty() && !ItemIds.fromStack(input).equals(recipe.input())) return null;
                if (!output.isEmpty() && !ItemIds.fromStack(output).equals(recipe.output())) return null;
                String blockId = ItemIds.fromBlock(MinecraftClient.getInstance().world.getBlockState(pos).getBlock());
                return new FurnaceInfo(pos, blockId, cookMsForBlock(blockId),
                        input.isEmpty() ? 0 : input.getCount(),
                        output.isEmpty() ? 0 : output.getCount(),
                        furnace.getCookProgress());
            });
        } finally {
            ScreenDriver.closeScreen();
        }
    }

    private static boolean loadFurnace(BlockPos pos, SmeltRecipe recipe, int inputCount) {
        if (!openFurnace(pos)) return false;
        try {
            return Boolean.TRUE.equals(ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ClientPlayerEntity player = client.player;
                ClientPlayerInteractionManager im = client.interactionManager;
                if (player == null || im == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler)) {
                    return false;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack input = screen.getSlot(0).getStack();
                ItemStack output = screen.getSlot(2).getStack();
                if (!input.isEmpty() && !ItemIds.fromStack(input).equals(recipe.input())) return false;
                if (!output.isEmpty() && !ItemIds.fromStack(output).equals(recipe.output())) return false;
                if (!moveItemsIntoSlot(player, im, screen, 0, recipe.input(), inputCount)) return false;
                ItemStack fuelStack = screen.getSlot(1).getStack();
                String existingFuelId = fuelStack.isEmpty() ? null : ItemIds.fromStack(fuelStack);
                int fuelItems = fuelItemsNeeded(player, inputCount, recipe.input());
                if (fuelItems <= 0 && existingFuelId == null) return false;
                if (existingFuelId != null && InventoryDriver.countItem(player, existingFuelId) <= 0) return true;
                String fuelId = existingFuelId != null ? existingFuelId : bestFuelItem(player, recipe.input());
                return fuelId != null && moveItemsIntoSlot(player, im, screen, 1, fuelId, fuelItems);
            }));
        } finally {
            ScreenDriver.closeScreen();
        }
    }

    private static void collectFurnaceOutput(BlockPos pos, SmeltRecipe recipe) {
        if (!openFurnace(pos)) return;
        try {
            ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ClientPlayerEntity player = client.player;
                ClientPlayerInteractionManager im = client.interactionManager;
                if (player == null || im == null || !(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler)) {
                    return null;
                }
                ScreenHandler screen = player.currentScreenHandler;
                ItemStack output = screen.getSlot(2).getStack();
                if (!output.isEmpty() && ItemIds.fromStack(output).equals(recipe.output())) {
                    im.clickSlot(screen.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                    sleep(50);
                }
                return null;
            });
        } finally {
            ScreenDriver.closeScreen();
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
        if (!waitUntilNear(pos, 4.75, 30_000L)) return false;
        WorldInteractor.openBlock(pos);
        for (int i = 0; i < 40; i++) {
            if (Boolean.TRUE.equals(ClientThread.call(() -> {
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

    public static boolean waitUntilNear(BlockPos target, double distance, long timeoutMs,
                                         java.util.function.Supplier<Boolean> isCancelled) {
        BridgeConfig config = BridgeConfig.get();
        double maxDistance = distance > 0 ? distance : config.workstationApproachDistance;
        long timeout = timeoutMs > 0 ? timeoutMs : config.workstationApproachTimeoutMs;

        Boolean alreadyNear = ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return false;
            return client.player.getBlockPos().isWithinDistance(target, maxDistance);
        });

        if (!Boolean.TRUE.equals(alreadyNear)) {
            execute("#goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        }

        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled != null && Boolean.TRUE.equals(isCancelled.get())) return false;

            Boolean near = ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null || client.world == null) return false;
                return client.player.getBlockPos().isWithinDistance(target, maxDistance);
            });

            if (Boolean.TRUE.equals(near)) return true;
            if (!sleepCancellable(isCancelled, 250L, 250L)) return false;
        }
        return false;
    }

    public static boolean waitUntilNear(BlockPos target, double distance, long timeoutMs) {
        return waitUntilNear(target, distance, timeoutMs, null);
    }

    private static boolean moveItemsIntoSlot(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                             ScreenHandler screen, int targetSlot, String itemId, int count) {
        int moved = 0;
        while (moved < count) {
            int source = findInventoryScreenSlot(screen, itemId);
            if (source < 0) return false;
            im.clickSlot(screen.syncId, source, 0, SlotActionType.PICKUP, player);
            sleep(50);
            int guard = 0;
            while (moved < count && guard++ < 64) {
                ItemStack cursor = screen.getCursorStack();
                if (cursor.isEmpty()) break;
                int before = cursor.getCount();
                im.clickSlot(screen.syncId, targetSlot, 1, SlotActionType.PICKUP, player);
                sleep(50);
                ItemStack afterCursor = screen.getCursorStack();
                int after = afterCursor.isEmpty() ? 0 : afterCursor.getCount();
                if (after < before) {
                    moved++;
                } else {
                    break;
                }
            }
            im.clickSlot(screen.syncId, source, 0, SlotActionType.PICKUP, player);
            sleep(50);
        }
        return true;
    }

    private static int findInventoryScreenSlot(ScreenHandler screen, String itemId) {
        for (int screenSlot = 3; screenSlot < screen.slots.size(); screenSlot++) {
            ItemStack stack = screen.getSlot(screenSlot).getStack();
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(itemId)) {
                return screenSlot;
            }
        }
        return -1;
    }

    private static int countAvailableFuelCapacity(ClientPlayerEntity player, String avoidItemId) {
        int total = 0;
        String avoid = ItemIds.normalize(avoidItemId);
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = ItemIds.fromStack(stack);
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
        String avoid = ItemIds.normalize(avoidItemId);
        String best = null;
        int bestCapacity = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = ItemIds.fromStack(stack);
            if (id.equals(avoid)) continue;
            int capacity = fuelCapacityItems(id);
            if (capacity > bestCapacity) {
                bestCapacity = capacity;
                best = id;
            }
        }
        return best;
    }

    public static int fuelCapacityItems(String itemId) {
        String id = ItemIds.normalize(itemId);
        if (id.equals("minecraft:lava_bucket")) return 100;
        if (id.equals("minecraft:coal_block")) return 80;
        if (id.equals("minecraft:blaze_rod")) return 12;
        if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) return 8;
        if (id.endsWith("_log") || id.endsWith("_wood")) return 1;
        if (id.endsWith("_planks")) return 1;
        return 0;
    }

    private static String resolveSmeltInputForInventory(ClientPlayerEntity player, String requested) {
        String input = ItemIds.normalize(requested);
        if (InventoryDriver.countItem(player, input) > 0) return input;
        Map<String, String> minedDropAliases = Map.of(
                "minecraft:iron_ore", "minecraft:raw_iron",
                "minecraft:deepslate_iron_ore", "minecraft:raw_iron",
                "minecraft:gold_ore", "minecraft:raw_gold",
                "minecraft:deepslate_gold_ore", "minecraft:raw_gold",
                "minecraft:copper_ore", "minecraft:raw_copper",
                "minecraft:deepslate_copper_ore", "minecraft:raw_copper"
        );
        String alias = minedDropAliases.get(input);
        if (alias != null && InventoryDriver.countItem(player, alias) > 0) return alias;
        return input;
    }

    private static int countInventoryItemSafe(String itemId) {
        return ClientThread.call(() -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            return player == null ? 0 : InventoryDriver.countItem(player, itemId);
        });
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

    private static String normalizeIngredientPattern(String pattern) {
        String value = String.valueOf(pattern == null ? "" : pattern).trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("*")) return value;
        return ItemIds.normalize(value);
    }

    public static final Map<String, SmeltRecipe> SMELT_RECIPES = buildSmeltRecipes();

    private static Map<String, SmeltRecipe> buildSmeltRecipes() {
        Map<String, SmeltRecipe> db = new HashMap<>();

        // ── Ore smelting (regular + deepslate + raw variants). ──────────
        addSmelt(db, "iron_ore",             "iron_ingot",    true, true,  false);
        addSmelt(db, "deepslate_iron_ore",   "iron_ingot",    true, true,  false);
        addSmelt(db, "raw_iron",             "iron_ingot",    true, true,  false);
        addSmelt(db, "gold_ore",             "gold_ingot",    true, true,  false);
        addSmelt(db, "deepslate_gold_ore",   "gold_ingot",    true, true,  false);
        addSmelt(db, "raw_gold",             "gold_ingot",    true, true,  false);
        addSmelt(db, "copper_ore",           "copper_ingot",  true, true,  false);
        addSmelt(db, "deepslate_copper_ore", "copper_ingot",  true, true,  false);
        addSmelt(db, "raw_copper",           "copper_ingot",  true, true,  false);
        addSmelt(db, "nether_gold_ore",      "gold_ingot",    true, true,  false);
        addSmelt(db, "coal_ore",             "coal",          true, true,  false);
        addSmelt(db, "deepslate_coal_ore",   "coal",          true, true,  false);
        addSmelt(db, "diamond_ore",          "diamond",       true, true,  false);
        addSmelt(db, "deepslate_diamond_ore","diamond",       true, true,  false);
        addSmelt(db, "emerald_ore",          "emerald",       true, true,  false);
        addSmelt(db, "deepslate_emerald_ore","emerald",       true, true,  false);
        addSmelt(db, "lapis_ore",            "lapis_lazuli",  true, true,  false);
        addSmelt(db, "deepslate_lapis_ore",  "lapis_lazuli",  true, true,  false);
        addSmelt(db, "redstone_ore",         "redstone",      true, true,  false);
        addSmelt(db, "deepslate_redstone_ore","redstone",     true, true,  false);
        addSmelt(db, "nether_quartz_ore",    "quartz",        true, true,  false);
        addSmelt(db, "ancient_debris",       "netherite_scrap", true, true, false);

        // ── Stone / sand / clay / brick family. ─────────────────────────
        addSmelt(db, "sand",             "glass",           true, false, false);
        addSmelt(db, "red_sand",         "glass",           true, false, false);
        addSmelt(db, "cobblestone",      "stone",           true, false, false);
        addSmelt(db, "stone",            "smooth_stone",    true, false, false);
        addSmelt(db, "cobbled_deepslate","deepslate",       true, false, false);
        addSmelt(db, "sandstone",        "smooth_sandstone",true, false, false);
        addSmelt(db, "red_sandstone",    "smooth_red_sandstone", true, false, false);
        addSmelt(db, "quartz_block",     "smooth_quartz",   true, false, false);
        addSmelt(db, "basalt",           "smooth_basalt",   true, false, false);
        addSmelt(db, "clay_ball",        "brick",           true, false, false);
        addSmelt(db, "clay",             "terracotta",      true, false, false);
        addSmelt(db, "netherrack",       "nether_brick",    true, false, false);

        // ── Misc. ────────────────────────────────────────────────────────
        addSmelt(db, "kelp",             "dried_kelp",      true, false, true);
        addSmelt(db, "cactus",           "green_dye",       true, false, false);
        addSmelt(db, "sea_pickle",       "lime_dye",        true, false, false);
        addSmelt(db, "chorus_fruit",     "popped_chorus_fruit", true, false, false);
        addSmelt(db, "wet_sponge",       "sponge",          true, false, false);

        // ── Log → charcoal (all real wood types, including pale_oak). ──
        for (String wood : lst("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","pale_oak")) {
            addSmelt(db, wood + "_log",   "charcoal", true, false, false);
            addSmelt(db, wood + "_wood",  "charcoal", true, false, false);
            addSmelt(db, "stripped_" + wood + "_log",  "charcoal", true, false, false);
            addSmelt(db, "stripped_" + wood + "_wood", "charcoal", true, false, false);
        }

        // ── Food (all smoker-compatible). ───────────────────────────────
        for (String food : lst("beef","chicken","porkchop","mutton","rabbit","cod","salmon","potato")) {
            String cooked = food.equals("potato") ? "baked_potato" : "cooked_" + food;
            addSmelt(db, food, cooked, true, false, true);
        }

        // ── Tool / armor recovery (furnace-only — turns worn iron/gold gear
        //    into nuggets). Not commonly used, but documents the path. ───
        for (String metal : lst("iron","golden")) {
            String result = metal.equals("golden") ? "gold_nugget" : "iron_nugget";
            for (String tool : lst("pickaxe","axe","shovel","hoe","sword","helmet","chestplate","leggings","boots")) {
                addSmelt(db, metal + "_" + tool, result, true, true, false);
            }
            addSmelt(db, metal + "_horse_armor", result, true, true, false);
        }
        // Chainmail → iron_nugget.
        for (String piece : lst("helmet","chestplate","leggings","boots")) {
            addSmelt(db, "chainmail_" + piece, "iron_nugget", true, true, false);
        }

        return db;
    }

    private static void addSmelt(Map<String, SmeltRecipe> db, String input, String output,
                                 boolean furnace, boolean blast, boolean smoker) {
        db.put(ItemIds.normalize(input), new SmeltRecipe(ItemIds.normalize(input), ItemIds.normalize(output),
                furnace, blast, smoker));
    }

    // ─── Smithing recipes ────────────────────────────────────────────────
    // Every vanilla smithing-table recipe as of 1.21. Execution still
    // requires a SmithingScreenHandler worker; for now the planner uses
    // this table only to detect and announce the requirement to the user.
    public static final Map<String, SmithingRecipe> SMITHING_RECIPES = buildSmithingRecipes();

    private static Map<String, SmithingRecipe> buildSmithingRecipes() {
        Map<String, SmithingRecipe> db = new HashMap<>();
        // Netherite upgrades: diamond gear + netherite_upgrade_smithing_template + netherite_ingot.
        String tmpl = "minecraft:netherite_upgrade_smithing_template";
        String add  = "minecraft:netherite_ingot";
        for (String tool : lst("pickaxe","axe","shovel","hoe","sword")) {
            db.put("netherite_" + tool,
                new SmithingRecipe("netherite_" + tool, tmpl, "minecraft:diamond_" + tool, add));
        }
        for (String piece : lst("helmet","chestplate","leggings","boots")) {
            db.put("netherite_" + piece,
                new SmithingRecipe("netherite_" + piece, tmpl, "minecraft:diamond_" + piece, add));
        }
        return db;
    }

    private static final List<String> WOOD_TYPES = lst(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");

    public static final Map<String, GatherProvider> GATHER_PROVIDERS = buildGatherProviders();

    private static Map<String, GatherProvider> buildGatherProviders() {
        Map<String, GatherProvider> providers = new HashMap<>();
        addGather(providers, "cobblestone", "cobblestone");
        addGather(providers, "cobbled_deepslate", "cobbled_deepslate");
        addGather(providers, "blackstone", "blackstone");
        addGather(providers, "sand", "sand");
        addGather(providers, "clay_ball", ml("clay"), "clay_ball");
        addGather(providers, "coal", ml("coal_ore", "deepslate_coal_ore"), "coal");
        addGather(providers, "redstone", ml("redstone_ore", "deepslate_redstone_ore"), "redstone");
        addGather(providers, "diamond", ml("diamond_ore", "deepslate_diamond_ore"), "diamond");
        addGather(providers, "emerald", ml("emerald_ore", "deepslate_emerald_ore"), "emerald");
        addGather(providers, "lapis_lazuli", ml("lapis_ore", "deepslate_lapis_ore"), "lapis_lazuli");
        addGather(providers, "flint", ml("gravel"), "flint");
        addGather(providers, "raw_iron", ml("iron_ore", "deepslate_iron_ore"), "raw_iron");
        addGather(providers, "raw_gold", ml("gold_ore", "deepslate_gold_ore", "nether_gold_ore"), "raw_gold");
        addGather(providers, "raw_copper", ml("copper_ore", "deepslate_copper_ore"), "raw_copper");
        addGather(providers, "ancient_debris", "ancient_debris");
        // Phase 4: crop / ore gather providers
        addGather(providers, "wheat", "wheat");
        addGather(providers, "carrot", "carrot");
        addGather(providers, "pumpkin", "pumpkin");
        addGather(providers, "cocoa_beans", "cocoa_beans");
        addGather(providers, "sugar_cane", "sugar_cane");
        addGather(providers, "nether_quartz", ml("nether_quartz_ore"), "nether_quartz");
        addGather(providers, "obsidian", "obsidian");
        // Flower gather providers — dye recipes above depend on these.
        addGather(providers, "poppy", "poppy");
        addGather(providers, "dandelion", "dandelion");
        addGather(providers, "oxeye_daisy", "oxeye_daisy");
        addGather(providers, "cornflower", "cornflower");
        addGather(providers, "pink_tulip", "pink_tulip");
        addGather(providers, "orange_tulip", "orange_tulip");
        addGather(providers, "red_tulip", "red_tulip");
        addGather(providers, "white_tulip", "white_tulip");
        addGather(providers, "allium", "allium");
        addGather(providers, "lily_of_the_valley", "lily_of_the_valley");
        addGather(providers, "azure_bluet", "azure_bluet");
        addGather(providers, "blue_orchid", "blue_orchid");
        // Wool: #mine with naturally-placed wool (villages, sheep if stripped,
        // rare mineshaft variants). Baritone will search the cached world for
        // any wool block of the requested color. This is a best-effort
        // fallback; shearing sheep is a planned follow-up (shear_entity action).
        for (String c : lst("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) {
            addGather(providers, c + "_wool", c + "_wool");
        }
        // String from cobweb (mineshafts). Mob-drop path (kill spiders) is a
        // future feature — right now this is the only recipe-compatible route.
        addGather(providers, "string", ml("cobweb"), "string");
        // Bones from mob drops — no gather path yet. Included for visibility;
        // planner will fail with a clear "no provider for bone" message rather
        // than silently omitting it.
        for (String wood : WOOD_TYPES) {
            addGather(providers, wood + "_log", wood + "_log");
            addGather(providers, wood + "_wood", wood + "_wood");
        }
        return providers;
    }

    @SafeVarargs
    private static List<String> ml(String... targets) { return List.of(targets); }

    private static void addGather(Map<String, GatherProvider> providers, String item, String mineTarget) {
        addGather(providers, item, ml(mineTarget), item);
    }

    private static void addGather(Map<String, GatherProvider> providers, String item,
                                  List<String> mineTargets, String producedItem) {
        providers.put(ItemIds.normalize(item), new GatherProvider(mineTargets, ItemIds.normalize(producedItem)));
    }

    // Maps ore-drop items to their source ore blocks for inventory checking
    private static final Map<String, List<String>> ORE_BLOCK_ALIASES = Map.of(
            "minecraft:raw_iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            "minecraft:raw_gold", List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            "minecraft:raw_copper", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")
    );

    // Mine target alias expansion: maps user/LLM target names to the full list of
    // ore block variants that Baritone should #mine. Every ore input (both the item
    // name and the stone-ore name) maps to stone + deepslate variants so the player
    // finds ore regardless of which deepslate layer it is in.
    private static final Map<String, List<String>> MINE_TARGET_ALIASES = Map.ofEntries(
        Map.entry("iron_ore",         List.of("iron_ore", "deepslate_iron_ore")),
        Map.entry("raw_iron",         List.of("iron_ore", "deepslate_iron_ore")),
        Map.entry("copper_ore",       List.of("copper_ore", "deepslate_copper_ore")),
        Map.entry("raw_copper",       List.of("copper_ore", "deepslate_copper_ore")),
        Map.entry("gold_ore",         List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore")),
        Map.entry("raw_gold",         List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore")),
        Map.entry("coal_ore",         List.of("coal_ore", "deepslate_coal_ore")),
        Map.entry("coal",             List.of("coal_ore", "deepslate_coal_ore")),
        Map.entry("diamond_ore",      List.of("diamond_ore", "deepslate_diamond_ore")),
        Map.entry("diamond",          List.of("diamond_ore", "deepslate_diamond_ore")),
        Map.entry("redstone_ore",     List.of("redstone_ore", "deepslate_redstone_ore")),
        Map.entry("redstone",         List.of("redstone_ore", "deepslate_redstone_ore")),
        Map.entry("lapis_ore",        List.of("lapis_ore", "deepslate_lapis_ore")),
        Map.entry("lapis_lazuli",     List.of("lapis_ore", "deepslate_lapis_ore")),
        Map.entry("emerald_ore",      List.of("emerald_ore", "deepslate_emerald_ore")),
        Map.entry("emerald",          List.of("emerald_ore", "deepslate_emerald_ore"))
    );

    // Minimum tool needed to successfully #mine this block.
    // Missing entries => no tool required (wood, sand, dirt, crops, etc.)
    public static final Map<String, String> MINE_TOOL_REQUIREMENTS = Map.ofEntries(
        Map.entry("stone",              "wooden_pickaxe"),
        Map.entry("cobblestone",        "wooden_pickaxe"),
        Map.entry("cobbled_deepslate",  "wooden_pickaxe"),
        Map.entry("coal_ore",           "wooden_pickaxe"),
        Map.entry("iron_ore",           "stone_pickaxe"),
        Map.entry("copper_ore",         "stone_pickaxe"),
        Map.entry("lapis_ore",          "stone_pickaxe"),
        Map.entry("gold_ore",           "iron_pickaxe"),
        Map.entry("redstone_ore",       "iron_pickaxe"),
        Map.entry("diamond_ore",        "iron_pickaxe"),
        Map.entry("emerald_ore",        "iron_pickaxe"),
        Map.entry("obsidian",           "diamond_pickaxe"),
        Map.entry("ancient_debris",     "diamond_pickaxe"),
        Map.entry("nether_quartz_ore",  "wooden_pickaxe"),
        Map.entry("blackstone",         "wooden_pickaxe")
    );

    public static final List<String> PICKAXE_TIERS = List.of(
        "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
        "diamond_pickaxe", "netherite_pickaxe"
    );

    // Nether-only gather targets that require portal travel
    public static final Set<String> NETHER_GATHER_TARGETS = Set.of(
            "ancient_debris", "netherrack", "nether_quartz_ore", "glowstone",
            "soul_sand", "magma_block", "nether_gold_ore", "blackstone",
            "basalt", "crimson_stem", "warped_stem"
    );

    private static boolean isNetherGatherTarget(String mineTarget) {
        return NETHER_GATHER_TARGETS.contains(mineTarget);
    }

    private static boolean playerIsInNether() {
        return ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.world != null && client.world.getRegistryKey() == World.NETHER;
        });
    }

    private static boolean playerIsInEnd() {
        return ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.world != null && client.world.getRegistryKey() == World.END;
        });
    }

    /**
     * Narrow, render-thread-only scan for a nearby station (crafting_table / furnace).
     * XZ range 16, Y range ±6 — ~4.5k getBlockState calls, typically <30 ms.
     */
    private static void ensureStation(MakePlan plan, String station) {
        if (plan.stations.contains(station)) return;
        if (plan.hasItem(station)) { plan.stations.add(station); return; }
        // Pre-probed on the render thread before planning starts — off-thread-safe.
        if (plan.nearbyStations.contains(station)) { plan.stations.add(station); return; }
        // Mark as in-progress before recursing so sub-plans that also want this
        // station (e.g. planks recipe → ensureStation("crafting_table")) don't
        // recurse back into makeItem and trip the cycle guard.
        plan.stations.add(station);
        if (!makeItem(plan, station, 1, 0)) {
            plan.stations.remove(station);
        }
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
            if (step.command() != null && step.command().startsWith("#goto")) {
                parts.add("goto " + name);
            } else if (step.kind() == MakeStepKind.MINE) {
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
        craftPostActionWorkerWithContinuation(itemName, count, null);
    }

    private static void craftPostActionWorkerWithContinuation(String itemName, int count, Runnable continuation) {
        try {
        MinecraftClient client = MinecraftClient.getInstance();

        for (int wait = 0; wait < 20; wait++) {
            if (Boolean.TRUE.equals(ClientThread.call(() -> {
                ClientPlayerEntity player = client.player;
                return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
            }))) {
                break;
            }
            sleep(50);
        }
        if (!Boolean.TRUE.equals(ClientThread.call(() -> {
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
        String normalizedItemKey = ClientThread.call(() ->
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
            if (!Boolean.TRUE.equals(ClientThread.call(() -> {
                ClientPlayerEntity player = client.player;
                return player != null && player.currentScreenHandler instanceof CraftingScreenHandler;
            }))) {
                sendBridgeMessage("[Bridge] Table closed. Crafted " + craftedTotal + "x " + itemName);
                return;
            }

            boolean filled = Boolean.TRUE.equals(ClientThread.call(() -> {
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
                resultCount = ClientThread.call(() -> {
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
                String gridState = ClientThread.call(() -> {
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
        ClientThread.call(() -> {
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
            if (continuation != null) {
                continuation.run();
            }
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
            sleep(50);
            im.clickSlot(syncId, slot.gridIndex, 1, SlotActionType.PICKUP, player);
            sleep(50);
            im.clickSlot(syncId, screenInvSlot, 0, SlotActionType.PICKUP, player);
            sleep(50);
        }
        return true;
    }

    private static int findMatchingInventoryScreenSlot(ScreenHandler screen, List<String> patterns) {
        // CraftingScreenHandler slots 0-9 are output + 3x3 grid; player inventory starts at 10.
        for (int screenSlot = 10; screenSlot < screen.slots.size(); screenSlot++) {
            ItemStack stack = screen.getSlot(screenSlot).getStack();
            if (stack.isEmpty()) continue;
            String itemId = ItemIds.fromStack(stack);
            if (itemId.startsWith("minecraft:")) itemId = itemId.substring(10);
            if (matchesItemId(itemId, patterns)) {
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
                filled.add(gridSlot + "=" + ItemIds.fromStack(stack) + "x" + stack.getCount());
            }
        }
        return filled.isEmpty() ? "empty" : String.join(", ", filled);
    }

    private static boolean matchesItemId(String itemId, List<String> patterns) {
        String strippedItemId = ItemIds.strip(itemId);
        for (String pattern : patterns) {
            String strippedPattern = ItemIds.strip(pattern);
            if (strippedPattern.startsWith("*") && strippedItemId.endsWith(strippedPattern.substring(1))) return true;
            if (strippedItemId.equals(strippedPattern)) return true;
        }
        return false;
    }

    // â”€â”€â”€ Recipe Database â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public static class GridSlot {
        public final int gridIndex;
        public final List<String> ingredientPatterns;
        GridSlot(int gridIndex, List<String> ingredientPatterns) {
            this.gridIndex = gridIndex;
            this.ingredientPatterns = ingredientPatterns;
        }
    }

    public static class RecipeData {
        public final String output;
        public final List<GridSlot> slots;
        @SuppressWarnings("unused")
        public final int outputCount;
        RecipeData(String output, List<GridSlot> slots, int outputCount) {
            this.output = output;
            this.slots = slots;
            this.outputCount = outputCount;
        }
    }

    private static GridSlot gs(int idx, List<String> pat) { return new GridSlot(idx, pat); }

    @SafeVarargs
    private static <T> List<T> lst(T... items) { return Arrays.asList(items); }

    public static final Map<String, RecipeData> RECIPE_DATABASE = buildRecipeDatabase();

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

        // Beds (3 same-color wool top row + 3 *_planks bottom row -> 1)
        // Colors get their bed name from the wool color. Planks can be any type.
        for (String c : lst("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) {
            db.put(c + "_bed", new RecipeData(c + "_bed", lst(
                gs(1, lst("minecraft:" + c + "_wool")),
                gs(2, lst("minecraft:" + c + "_wool")),
                gs(3, lst("minecraft:" + c + "_wool")),
                gs(7, lst("*_planks")),
                gs(8, lst("*_planks")),
                gs(9, lst("*_planks"))
            ), 1));
        }

        // Dyes from single-flower sources (1 flower -> 1 dye).
        // These are the ones the build templates actually rely on.
        db.put("red_dye",        new RecipeData("red_dye",        lst(gs(1, lst("minecraft:poppy"))),              1));
        db.put("yellow_dye",     new RecipeData("yellow_dye",     lst(gs(1, lst("minecraft:dandelion"))),          1));
        db.put("light_gray_dye", new RecipeData("light_gray_dye", lst(gs(1, lst("minecraft:oxeye_daisy"))),        1));
        db.put("blue_dye",       new RecipeData("blue_dye",       lst(gs(1, lst("minecraft:cornflower"))),         1));
        db.put("pink_dye",       new RecipeData("pink_dye",       lst(gs(1, lst("minecraft:pink_tulip"))),         1));
        db.put("orange_dye",     new RecipeData("orange_dye",     lst(gs(1, lst("minecraft:orange_tulip"))),       1));
        db.put("magenta_dye",    new RecipeData("magenta_dye",    lst(gs(1, lst("minecraft:allium"))),             1));
        db.put("white_dye",      new RecipeData("white_dye",      lst(gs(1, lst("minecraft:lily_of_the_valley"))), 1));
        // Bone meal also makes white dye: 1 bone -> 3 bone_meal, 1 bone_meal acts as white_dye in recipes.
        // The planner treats them as distinct items; if you want bone_meal as fallback it should be wired separately.
        db.put("bone_meal",      new RecipeData("bone_meal",      lst(gs(1, lst("minecraft:bone"))),               3));

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

        // ---- Phase 1: Essentials ----

        // Netherite Ingot (4 scrap + 4 gold ingot -> 1, shapeless approximated)
        db.put("netherite_ingot", new RecipeData("netherite_ingot", lst(
            gs(1, lst("minecraft:netherite_scrap")), gs(2, lst("minecraft:netherite_scrap")),
            gs(3, lst("minecraft:netherite_scrap")), gs(4, lst("minecraft:netherite_scrap")),
            gs(5, lst("minecraft:gold_ingot")), gs(6, lst("minecraft:gold_ingot")),
            gs(7, lst("minecraft:gold_ingot")), gs(8, lst("minecraft:gold_ingot"))
        ), 1));

        // Gold Nugget (1 gold ingot -> 9)
        db.put("gold_nugget", new RecipeData("gold_nugget", lst(
            gs(1, lst("minecraft:gold_ingot"))
        ), 9));

        // Compressed blocks
        db.put("gold_block", new RecipeData("gold_block", lst(
            gs(1, lst("minecraft:gold_ingot")), gs(2, lst("minecraft:gold_ingot")), gs(3, lst("minecraft:gold_ingot")),
            gs(4, lst("minecraft:gold_ingot")), gs(5, lst("minecraft:gold_ingot")), gs(6, lst("minecraft:gold_ingot")),
            gs(7, lst("minecraft:gold_ingot")), gs(8, lst("minecraft:gold_ingot")), gs(9, lst("minecraft:gold_ingot"))
        ), 1));
        db.put("diamond_block", new RecipeData("diamond_block", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(5, lst("minecraft:diamond")), gs(6, lst("minecraft:diamond")),
            gs(7, lst("minecraft:diamond")), gs(8, lst("minecraft:diamond")), gs(9, lst("minecraft:diamond"))
        ), 1));
        db.put("emerald_block", new RecipeData("emerald_block", lst(
            gs(1, lst("minecraft:emerald")), gs(2, lst("minecraft:emerald")), gs(3, lst("minecraft:emerald")),
            gs(4, lst("minecraft:emerald")), gs(5, lst("minecraft:emerald")), gs(6, lst("minecraft:emerald")),
            gs(7, lst("minecraft:emerald")), gs(8, lst("minecraft:emerald")), gs(9, lst("minecraft:emerald"))
        ), 1));
        db.put("copper_block", new RecipeData("copper_block", lst(
            gs(1, lst("minecraft:copper_ingot")), gs(2, lst("minecraft:copper_ingot")), gs(3, lst("minecraft:copper_ingot")),
            gs(4, lst("minecraft:copper_ingot")), gs(5, lst("minecraft:copper_ingot")), gs(6, lst("minecraft:copper_ingot")),
            gs(7, lst("minecraft:copper_ingot")), gs(8, lst("minecraft:copper_ingot")), gs(9, lst("minecraft:copper_ingot"))
        ), 1));

        // Netherite Block (9 netherite ingots -> 1)
        db.put("netherite_block", new RecipeData("netherite_block", lst(
            gs(1, lst("minecraft:netherite_ingot")), gs(2, lst("minecraft:netherite_ingot")), gs(3, lst("minecraft:netherite_ingot")),
            gs(4, lst("minecraft:netherite_ingot")), gs(5, lst("minecraft:netherite_ingot")), gs(6, lst("minecraft:netherite_ingot")),
            gs(7, lst("minecraft:netherite_ingot")), gs(8, lst("minecraft:netherite_ingot")), gs(9, lst("minecraft:netherite_ingot"))
        ), 1));

        // Bread (3 wheat -> 1)
        db.put("bread", new RecipeData("bread", lst(
            gs(1, lst("minecraft:wheat")), gs(2, lst("minecraft:wheat")), gs(3, lst("minecraft:wheat"))
        ), 1));

        // Cookie (2 wheat + 1 cocoa_beans -> 8)
        db.put("cookie", new RecipeData("cookie", lst(
            gs(1, lst("minecraft:wheat")), gs(2, lst("minecraft:cocoa_beans")), gs(3, lst("minecraft:wheat"))
        ), 8));

        // Pumpkin Pie (pumpkin + sugar + egg -> 1)
        db.put("pumpkin_pie", new RecipeData("pumpkin_pie", lst(
            gs(1, lst("minecraft:pumpkin")), gs(2, lst("minecraft:sugar")), gs(3, lst("minecraft:egg"))
        ), 1));

        // Golden Carrot (8 gold nuggets + carrot -> 1)
        db.put("golden_carrot", new RecipeData("golden_carrot", lst(
            gs(1, lst("minecraft:gold_nugget")), gs(2, lst("minecraft:gold_nugget")), gs(3, lst("minecraft:gold_nugget")),
            gs(4, lst("minecraft:gold_nugget")), gs(5, lst("minecraft:carrot")), gs(6, lst("minecraft:gold_nugget")),
            gs(7, lst("minecraft:gold_nugget")), gs(8, lst("minecraft:gold_nugget")), gs(9, lst("minecraft:gold_nugget"))
        ), 1));

        // Bow (3 sticks + 3 string -> 1)
        db.put("bow", new RecipeData("bow", lst(
            gs(1, lst("minecraft:string")), gs(2, lst("minecraft:string")), gs(4, lst("minecraft:string")),
            gs(5, lst("minecraft:stick")), gs(7, lst("minecraft:string")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Arrow (flint + stick + feather -> 4)
        db.put("arrow", new RecipeData("arrow", lst(
            gs(1, lst("minecraft:flint")), gs(4, lst("minecraft:stick")), gs(7, lst("minecraft:feather"))
        ), 4));

        // Ladder (7 sticks in H shape -> 3)
        db.put("ladder", new RecipeData("ladder", lst(
            gs(1, lst("minecraft:stick")), gs(3, lst("minecraft:stick")),
            gs(4, lst("minecraft:stick")), gs(5, lst("minecraft:stick")), gs(6, lst("minecraft:stick")),
            gs(7, lst("minecraft:stick")), gs(9, lst("minecraft:stick"))
        ), 3));

        // Shield (6 planks + 1 iron ingot -> 1)
        db.put("shield", new RecipeData("shield", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("*_planks")), gs(5, lst("minecraft:iron_ingot")), gs(6, lst("*_planks")),
            gs(8, lst("*_planks"))
        ), 1));

        // Boat (5 planks U shape -> 1)
        db.put("boat", new RecipeData("boat", lst(
            gs(1, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("*_planks")), gs(5, lst("*_planks")), gs(6, lst("*_planks"))
        ), 1));

        // Golden Pickaxe (3 gold ingots + 2 sticks -> 1)
        db.put("golden_pickaxe", new RecipeData("golden_pickaxe", lst(
            gs(1, lst("minecraft:gold_ingot")), gs(2, lst("minecraft:gold_ingot")), gs(3, lst("minecraft:gold_ingot")),
            gs(5, lst("minecraft:stick")),
            gs(8, lst("minecraft:stick"))
        ), 1));

        // Iron Tools
        db.put("iron_shovel", new RecipeData("iron_shovel", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(4, lst("minecraft:stick")), gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("iron_axe", new RecipeData("iron_axe", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:stick")),
            gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("iron_sword", new RecipeData("iron_sword", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(4, lst("minecraft:iron_ingot")), gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("iron_hoe", new RecipeData("iron_hoe", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:stick")), gs(7, lst("minecraft:stick"))
        ), 1));

        // Diamond Tools
        db.put("diamond_sword", new RecipeData("diamond_sword", lst(
            gs(1, lst("minecraft:diamond")), gs(4, lst("minecraft:diamond")), gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("diamond_axe", new RecipeData("diamond_axe", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(5, lst("minecraft:stick")),
            gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("diamond_shovel", new RecipeData("diamond_shovel", lst(
            gs(1, lst("minecraft:diamond")), gs(4, lst("minecraft:stick")), gs(7, lst("minecraft:stick"))
        ), 1));
        db.put("diamond_hoe", new RecipeData("diamond_hoe", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")),
            gs(4, lst("minecraft:stick")), gs(7, lst("minecraft:stick"))
        ), 1));

        // Iron Armor
        db.put("iron_helmet", new RecipeData("iron_helmet", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot"))
        ), 1));
        db.put("iron_chestplate", new RecipeData("iron_chestplate", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(8, lst("minecraft:iron_ingot")), gs(9, lst("minecraft:iron_ingot"))
        ), 1));
        db.put("iron_leggings", new RecipeData("iron_leggings", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(9, lst("minecraft:iron_ingot"))
        ), 1));
        db.put("iron_boots", new RecipeData("iron_boots", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot"))
        ), 1));

        // Diamond Armor
        db.put("diamond_helmet", new RecipeData("diamond_helmet", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(6, lst("minecraft:diamond"))
        ), 1));
        db.put("diamond_chestplate", new RecipeData("diamond_chestplate", lst(
            gs(1, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(5, lst("minecraft:diamond")), gs(6, lst("minecraft:diamond")),
            gs(7, lst("minecraft:diamond")), gs(8, lst("minecraft:diamond")), gs(9, lst("minecraft:diamond"))
        ), 1));
        db.put("diamond_leggings", new RecipeData("diamond_leggings", lst(
            gs(1, lst("minecraft:diamond")), gs(2, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(6, lst("minecraft:diamond")),
            gs(7, lst("minecraft:diamond")), gs(9, lst("minecraft:diamond"))
        ), 1));
        db.put("diamond_boots", new RecipeData("diamond_boots", lst(
            gs(1, lst("minecraft:diamond")), gs(3, lst("minecraft:diamond")),
            gs(4, lst("minecraft:diamond")), gs(6, lst("minecraft:diamond"))
        ), 1));

        // ---- Phase 2: Redstone / Utility ----

        // Note Block (8 planks + redstone -> 1)
        db.put("note_block", new RecipeData("note_block", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("*_planks")), gs(5, lst("minecraft:redstone")), gs(6, lst("*_planks")),
            gs(7, lst("*_planks")), gs(8, lst("*_planks")), gs(9, lst("*_planks"))
        ), 1));

        // Dispenser (7 cobblestone + bow + redstone -> 1)
        db.put("dispenser", new RecipeData("dispenser", lst(
            gs(1, lst("minecraft:cobblestone")), gs(2, lst("minecraft:cobblestone")), gs(3, lst("minecraft:cobblestone")),
            gs(4, lst("minecraft:cobblestone")), gs(5, lst("minecraft:bow")), gs(6, lst("minecraft:cobblestone")),
            gs(7, lst("minecraft:cobblestone")), gs(8, lst("minecraft:redstone")), gs(9, lst("minecraft:cobblestone"))
        ), 1));

        // Dropper (7 cobblestone + redstone -> 1)
        db.put("dropper", new RecipeData("dropper", lst(
            gs(1, lst("minecraft:cobblestone")), gs(2, lst("minecraft:cobblestone")), gs(3, lst("minecraft:cobblestone")),
            gs(4, lst("minecraft:cobblestone")), gs(6, lst("minecraft:cobblestone")),
            gs(7, lst("minecraft:cobblestone")), gs(8, lst("minecraft:redstone")), gs(9, lst("minecraft:cobblestone"))
        ), 1));

        // Observer (6 cobblestone + redstone + quartz -> 1)
        db.put("observer", new RecipeData("observer", lst(
            gs(1, lst("minecraft:cobblestone")), gs(2, lst("minecraft:cobblestone")), gs(3, lst("minecraft:cobblestone")),
            gs(4, lst("minecraft:redstone")), gs(5, lst("minecraft:redstone")), gs(6, lst("minecraft:nether_quartz")),
            gs(7, lst("minecraft:cobblestone")), gs(8, lst("minecraft:cobblestone")), gs(9, lst("minecraft:cobblestone"))
        ), 1));

        // Piston (3 planks + 4 cobblestone + iron + redstone -> 1)
        db.put("piston", new RecipeData("piston", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("minecraft:cobblestone")), gs(5, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:cobblestone")),
            gs(7, lst("minecraft:cobblestone")), gs(8, lst("minecraft:redstone")), gs(9, lst("minecraft:cobblestone"))
        ), 1));

        // Sticky Piston (piston + slime_ball -> 1)
        // NOTE: slime_ball requires mob kill; not automatically craftable
        // db.put("sticky_piston", ...)  -- SKIPPED: no slime_ball source

        // Hopper (5 iron + chest -> 1)
        db.put("hopper", new RecipeData("hopper", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:chest")), gs(6, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot"))
        ), 1));

        // TNT (4 sand + 5 gunpowder -> 1)
        db.put("tnt", new RecipeData("tnt", lst(
            gs(1, lst("minecraft:gunpowder")), gs(2, lst("minecraft:sand")), gs(3, lst("minecraft:gunpowder")),
            gs(4, lst("minecraft:sand")), gs(5, lst("minecraft:gunpowder")), gs(6, lst("minecraft:sand")),
            gs(7, lst("minecraft:gunpowder")), gs(8, lst("minecraft:sand")), gs(9, lst("minecraft:gunpowder"))
        ), 1));

        // ---- Phase 3: Building / Decoration ----

        // Stone Bricks (2x2 stone -> 4)
        db.put("stone_bricks", new RecipeData("stone_bricks", lst(
            gs(1, lst("minecraft:stone")), gs(2, lst("minecraft:stone")),
            gs(4, lst("minecraft:stone")), gs(5, lst("minecraft:stone"))
        ), 4));

        // Bricks (2x2 brick -> 4)
        db.put("bricks", new RecipeData("bricks", lst(
            gs(1, lst("minecraft:brick")), gs(2, lst("minecraft:brick")),
            gs(4, lst("minecraft:brick")), gs(5, lst("minecraft:brick"))
        ), 4));

        // Nether Bricks (2x2 nether_brick -> 4)
        db.put("nether_bricks", new RecipeData("nether_bricks", lst(
            gs(1, lst("minecraft:nether_brick")), gs(2, lst("minecraft:nether_brick")),
            gs(4, lst("minecraft:nether_brick")), gs(5, lst("minecraft:nether_brick"))
        ), 4));

        // Quartz Block (2x2 quartz -> 4)
        db.put("quartz_block", new RecipeData("quartz_block", lst(
            gs(1, lst("minecraft:quartz")), gs(2, lst("minecraft:quartz")),
            gs(4, lst("minecraft:quartz")), gs(5, lst("minecraft:quartz"))
        ), 4));

        // Glass Pane (6 glass in bottom 2 rows -> 16)
        db.put("glass_pane", new RecipeData("glass_pane", lst(
            gs(1, lst("minecraft:glass")), gs(2, lst("minecraft:glass")), gs(3, lst("minecraft:glass")),
            gs(4, lst("minecraft:glass")), gs(5, lst("minecraft:glass")), gs(6, lst("minecraft:glass"))
        ), 16));

        // Iron Bars (6 iron ingots in bottom 2 rows -> 16)
        db.put("iron_bars", new RecipeData("iron_bars", lst(
            gs(1, lst("minecraft:iron_ingot")), gs(2, lst("minecraft:iron_ingot")), gs(3, lst("minecraft:iron_ingot")),
            gs(4, lst("minecraft:iron_ingot")), gs(5, lst("minecraft:iron_ingot")), gs(6, lst("minecraft:iron_ingot"))
        ), 16));

        // Sign (6 planks + stick -> 3)
        db.put("sign", new RecipeData("sign", lst(
            gs(1, lst("*_planks")), gs(2, lst("*_planks")), gs(3, lst("*_planks")),
            gs(4, lst("*_planks")), gs(5, lst("*_planks")), gs(6, lst("*_planks")),
            gs(8, lst("minecraft:stick"))
        ), 3));

        // Item Frame (8 sticks + leather -> 1)
        db.put("item_frame", new RecipeData("item_frame", lst(
            gs(1, lst("minecraft:stick")), gs(2, lst("minecraft:stick")), gs(3, lst("minecraft:stick")),
            gs(4, lst("minecraft:stick")), gs(5, lst("minecraft:leather")), gs(6, lst("minecraft:stick")),
            gs(7, lst("minecraft:stick")), gs(8, lst("minecraft:stick")), gs(9, lst("minecraft:stick"))
        ), 1));

        // Painting (8 sticks + wool -> 1)
        db.put("painting", new RecipeData("painting", lst(
            gs(1, lst("minecraft:stick")), gs(2, lst("minecraft:stick")), gs(3, lst("minecraft:stick")),
            gs(4, lst("minecraft:stick")), gs(5, lst("minecraft:white_wool")), gs(6, lst("minecraft:stick")),
            gs(7, lst("minecraft:stick")), gs(8, lst("minecraft:stick")), gs(9, lst("minecraft:stick"))
        ), 1));

        // Enchanting Table (book + 2 diamond + 4 obsidian -> 1)
        db.put("enchanting_table", new RecipeData("enchanting_table", lst(
            gs(2, lst("minecraft:book")),
            gs(4, lst("minecraft:diamond")), gs(5, lst("minecraft:obsidian")), gs(6, lst("minecraft:diamond")),
            gs(7, lst("minecraft:obsidian")), gs(8, lst("minecraft:obsidian")), gs(9, lst("minecraft:obsidian"))
        ), 1));

        // Anvil (3 iron_block + 4 iron_ingot -> 1)
        db.put("anvil", new RecipeData("anvil", lst(
            gs(1, lst("minecraft:iron_block")), gs(2, lst("minecraft:iron_block")), gs(3, lst("minecraft:iron_block")),
            gs(5, lst("minecraft:iron_ingot")),
            gs(7, lst("minecraft:iron_ingot")), gs(8, lst("minecraft:iron_ingot")), gs(9, lst("minecraft:iron_ingot"))
        ), 1));

        // ── Overlay generated recipes ─────────────────────────────────────
        // Fill in every vanilla recipe we didn't explicitly curate above,
        // using the generated JSON extracted from minecraft-data. Existing
        // hand-written entries take priority — they use richer wildcards
        // (*_planks, *_logs) than the explicit enumerations in the JSON.
        loadGeneratedRecipes(db);

        return db;
    }

    /**
     * Load recipes_1.21.11.json from the mod's resources and merge any
     * recipes whose output isn't already in the curated map. Silently skips
     * on any parse failure — the hand-written table is enough to keep the
     * build pipeline running.
     */
    private static void loadGeneratedRecipes(Map<String, RecipeData> db) {
        try (java.io.InputStream in = CommandExecutor.class.getResourceAsStream("/recipes_1.21.11.json")) {
            if (in == null) {
                System.out.println("[mindcraft-bridge] recipes_1.21.11.json not found on classpath; using curated defaults only");
                return;
            }
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int added = 0;
            int skipped = 0;
            // Top-level object: { "<item>": {"out":N, "slots":[{"i":idx,"p":[...]}, ...]} }
            // Parse by scanning for the top-level keys. Our embedded JSON is
            // deterministic (no whitespace) so we can do this with a simple
            // balance-tracking walk rather than pulling in a full parser.
            for (String[] entry : iterateTopLevelEntries(json)) {
                String key = entry[0];
                String body = entry[1];
                if (key.contains("__alt")) { skipped++; continue; } // only primary variant for now
                if (db.containsKey(key)) { skipped++; continue; }    // curated wins
                // Skip smithing outputs — minecraft-data emits a legacy fake
                // crafting recipe for netherite_ingot that would bypass the
                // smithing gate above. SMITHING_RECIPES handles them.
                if (SMITHING_RECIPES.containsKey(key)) { skipped++; continue; }
                RecipeData parsed = parseGeneratedRecipe(key, body);
                if (parsed != null) {
                    db.put(key, parsed);
                    added++;
                }
            }
            System.out.println("[mindcraft-bridge] loaded " + added + " generated recipes (" + skipped + " skipped as duplicates/alts)");
        } catch (Throwable t) {
            System.out.println("[mindcraft-bridge] failed to load generated recipes: " + t.getMessage());
        }
    }

    /**
     * Iterate key/body pairs from a single-level JSON object, yielding pairs
     * where body is the raw substring of the value (including surrounding
     * braces). Handles strings and nested structures via brace/bracket depth.
     */
    private static List<String[]> iterateTopLevelEntries(String json) {
        List<String[]> out = new ArrayList<>();
        int i = 0;
        int n = json.length();
        while (i < n && json.charAt(i) != '{') i++;
        if (i >= n) return out;
        i++; // past outer {
        while (i < n) {
            // Skip whitespace + commas
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= n || json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') break;
            int keyStart = ++i;
            while (i < n && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++;
                i++;
            }
            String key = json.substring(keyStart, i);
            i++; // past closing quote
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) i++;
            if (i >= n) break;
            int valStart = i;
            int depth = 0;
            boolean inStr = false;
            while (i < n) {
                char c = json.charAt(i);
                if (inStr) {
                    if (c == '\\') { i += 2; continue; }
                    if (c == '"') inStr = false;
                } else {
                    if (c == '"') inStr = true;
                    else if (c == '{' || c == '[') depth++;
                    else if (c == '}' || c == ']') {
                        depth--;
                        if (depth == 0) { i++; break; }
                    }
                }
                i++;
            }
            out.add(new String[]{ key, json.substring(valStart, i) });
        }
        return out;
    }

    /**
     * Parse a single recipe body of shape {"out":N,"slots":[{"i":n,"p":["a","b"]}, ...]}
     * into a RecipeData. Returns null on any structural issue.
     */
    private static RecipeData parseGeneratedRecipe(String key, String body) {
        try {
            Integer outCount = parseIntSafe(extractJsonPrimitive(body, "out"));
            if (outCount == null || outCount <= 0) outCount = 1;
            String slotsArr = extractJsonArrayRaw(body, "slots");
            if (slotsArr == null) return null;
            List<String> slotObjs = splitJsonArray(slotsArr);
            List<GridSlot> slots = new ArrayList<>();
            for (String obj : slotObjs) {
                Integer idx = parseIntSafe(extractJsonPrimitive(obj, "i"));
                if (idx == null || idx < 1 || idx > 9) continue;
                String pArr = extractJsonArrayRaw(obj, "p");
                if (pArr == null) continue;
                List<String> pRaw = splitJsonArray(pArr);
                List<String> patterns = new ArrayList<>(pRaw.size());
                for (String p : pRaw) {
                    String s = p.trim();
                    if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                        s = s.substring(1, s.length() - 1);
                    }
                    if (s.isEmpty()) continue;
                    // All our pattern matching handles bare names and minecraft: prefix.
                    patterns.add("minecraft:" + s.replaceAll("^minecraft:", ""));
                }
                if (!patterns.isEmpty()) slots.add(new GridSlot(idx, patterns));
            }
            if (slots.isEmpty()) return null;
            return new RecipeData(key, slots, outCount);
        } catch (Throwable t) {
            return null;
        }
    }

    // â”€â”€â”€ Take crafting result â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static int takeCraftingResult(ClientPlayerEntity player, ClientPlayerInteractionManager im,
                                           ScreenHandler screen, int syncId, Identifier expectedItem) {
        var resultSlot = screen.getSlot(0);
        if (resultSlot == null || !resultSlot.hasStack()) return 0;
        ItemStack resultStack = resultSlot.getStack();
        if (resultStack.isEmpty() || !ItemIds.fromStack(resultStack).equals(expectedItem.toString())) return 0;
        int count = resultStack.getCount();
        im.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, player);
        return count;
    }

    // â”€â”€â”€ Block scanning â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            if (!stack.isEmpty() && matchesItemId(ItemIds.fromStack(stack), patterns)) {
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
        String requestedLog;
        if (requestedWood.equals("crimson") || requestedWood.equals("warped")) {
            requestedLog = "minecraft:" + requestedWood + "_stem";
        } else {
            requestedLog = "minecraft:" + requestedWood + "_log";
        }
        if (InventoryDriver.countItem(player, requestedLog) > 0) {
            return itemKey;
        }

        for (String wood : lst("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak")) {
            if (InventoryDriver.countItem(player, "minecraft:" + wood + "_log") > 0) {
                return wood + "_planks";
            }
        }
        if (InventoryDriver.countItem(player, "minecraft:crimson_stem") > 0) {
            return "crimson_planks";
        }
        if (InventoryDriver.countItem(player, "minecraft:warped_stem") > 0) {
            return "warped_planks";
        }

        return itemKey;
    }


    public static void sleep(long ms) {
        sleepInterruptibly(ms);
    }

    public static boolean sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean sleepCancellable(java.util.function.Supplier<Boolean> isCancelled, long totalMs, long stepMs) {
        long deadline = System.currentTimeMillis() + totalMs;
        long interval = Math.max(50L, stepMs);
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled != null && Boolean.TRUE.equals(isCancelled.get())) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            long sleepFor = Math.min(interval, Math.max(1L, remaining));
            try {
                Thread.sleep(sleepFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isCancelled == null || !Boolean.TRUE.equals(isCancelled.get());
    }



    public static void sendBridgeMessage(String message) {
        ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal(message), false);
            }
            return null;
        });
    }

    // â”€â”€â”€ Capabilities / commands JSON â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // ─── Phase 3: Workstation Mechanics ──────────────────────────────────────

    private static String executeSmithAction(String actionJson) {
        WorkerThreads.start("mindcraft-smith", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("smith").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#smith");
                } else {
                    TaskQueue.getInstance().failActiveIf("#smith", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#smith", "smith: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void smithWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String template = ItemIds.normalize(extractJsonString(actionJson, "template"));
        String base = ItemIds.normalize(extractJsonString(actionJson, "base"));
        String addition = ItemIds.normalize(extractJsonString(actionJson, "addition"));
        String output = ItemIds.normalize(extractJsonString(actionJson, "output"));

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#smith", "smith: not connected");
            return;
        }

        BlockPos table = WorkstationFinder.findNearestStation(client.world, client.player, "smithing_table");
        if (table == null) {
            TaskQueue.getInstance().failActiveIf("#smith", "smith: no smithing table nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (!waitUntilNear(table, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#smith", "smith: failed to reach table");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (!WorldInteractor.openBlock(table)) {
            TaskQueue.getInstance().failActiveIf("#smith", "smith: failed to open table");
            return;
        }

        if (!ScreenDriver.waitForHandler(SmithingScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#smith", "smith: screen did not open");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null || !(p.currentScreenHandler instanceof SmithingScreenHandler)) {
                return false;
            }
            ScreenHandler screen = p.currentScreenHandler;

            int templateSlot = ScreenDriver.findSlot(screen, template, 4);
            if (templateSlot < 0) return false;
            if (!ScreenDriver.clickAndVerify(templateSlot, 0, SlotActionType.PICKUP, template, 1)) return false;
            if (!ScreenDriver.clickAndVerify(0, 0, SlotActionType.PICKUP, template, 1)) return false;

            int baseSlot = ScreenDriver.findSlot(screen, base, 4);
            if (baseSlot < 0) return false;
            if (!ScreenDriver.clickAndVerify(baseSlot, 0, SlotActionType.PICKUP, base, 1)) return false;
            if (!ScreenDriver.clickAndVerify(1, 0, SlotActionType.PICKUP, base, 1)) return false;

            int additionSlot = ScreenDriver.findSlot(screen, addition, 4);
            if (additionSlot < 0) return false;
            if (!ScreenDriver.clickAndVerify(additionSlot, 0, SlotActionType.PICKUP, addition, 1)) return false;
            if (!ScreenDriver.clickAndVerify(2, 0, SlotActionType.PICKUP, addition, 1)) return false;

            return ScreenDriver.clickWithRecovery(3, 0, SlotActionType.QUICK_MOVE, null, 0, table, SmithingScreenHandler.class);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#smith");
        } else {
            TaskQueue.getInstance().failActiveIf("#smith", "smith: recipe failed");
        }
    }

    private static String executeBrewAction(String actionJson) {
        WorkerThreads.start("mindcraft-brew", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("brew").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#brew");
                } else {
                    TaskQueue.getInstance().failActiveIf("#brew", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#brew", "brew: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void brewWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String ingredient = extractJsonString(actionJson, "ingredient");
        String potions = extractJsonString(actionJson, "potions");
        String fuel = extractJsonString(actionJson, "fuel");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#brew", "brew: not connected");
            return;
        }

        BlockPos stand = WorkstationFinder.findNearestStation(client.world, client.player, "brewing_stand");
        if (stand == null) {
            TaskQueue.getInstance().failActiveIf("#brew", "brew: no brewing stand nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(stand, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#brew", "brew: failed to reach stand");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(stand)) {
            TaskQueue.getInstance().failActiveIf("#brew", "brew: failed to open stand");
            return;
        }

        if (!ScreenDriver.waitForHandler(BrewingStandScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#brew", "brew: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null || !(p.currentScreenHandler instanceof BrewingStandScreenHandler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (ingredient != null) {
                String normIngredient = ItemIds.normalize(ingredient);
                int ingSlot = ScreenDriver.findSlot(screen, normIngredient, 5);
                if (ingSlot >= 0) {
                    ScreenDriver.pickup(ingSlot);
                    ScreenDriver.pickup(3);
                    ScreenDriver.pickup(ingSlot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (fuel != null) {
                String normFuel = ItemIds.normalize(fuel);
                int fuelSlot = ScreenDriver.findSlot(screen, normFuel, 5);
                if (fuelSlot >= 0) {
                    ScreenDriver.pickup(fuelSlot);
                    ScreenDriver.pickup(4);
                    ScreenDriver.pickup(fuelSlot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (potions != null) {
                String normPotions = ItemIds.normalize(potions);
                for (int targetSlot = 0; targetSlot < 3; targetSlot++) {
                    int potSlot = ScreenDriver.findSlot(screen, normPotions, 5);
                    if (potSlot < 0) break;
                    ScreenDriver.pickup(potSlot);
                    ScreenDriver.pickup(targetSlot);
                    ScreenDriver.pickup(potSlot);
                    sleep(100); // Wait for server acknowledgment
                }
            }

            return true;
        });

        ScreenDriver.closeScreen();
        if (!sleepCancellable(() -> TaskQueue.getInstance().isCancellationRequested(), 20_000L, 500L)) return;

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#brew");
    }

    private static String executeEnchantAction(String actionJson) {
        WorkerThreads.start("mindcraft-enchant", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("enchant").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#enchant");
                } else {
                    TaskQueue.getInstance().failActiveIf("#enchant", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#enchant", "enchant: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void enchantWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String item = extractJsonString(actionJson, "item");
        String level = extractJsonString(actionJson, "level");
        String lapis = extractJsonString(actionJson, "lapis");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: not connected");
            return;
        }

        BlockPos table = WorkstationFinder.findNearestStation(client.world, client.player, "enchanting_table");
        if (table == null) {
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: no enchanting table nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(table, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: failed to reach table");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(table)) {
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: failed to open table");
            return;
        }

        if (!ScreenDriver.waitForHandler(EnchantmentScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null || !(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (item != null) {
                String normItem = ItemIds.normalize(item);
                int itemSlot = ScreenDriver.findSlot(screen, normItem, 2);
                if (itemSlot >= 0) {
                    if (!ScreenDriver.clickAndVerify(itemSlot, 0, SlotActionType.PICKUP, normItem, 1)) return false;
                    if (!ScreenDriver.clickAndVerify(0, 0, SlotActionType.PICKUP, normItem, 1)) return false;
                }
            }

            if (lapis != null) {
                String normLapis = ItemIds.normalize(lapis);
                int lapisSlot = ScreenDriver.findSlot(screen, normLapis, 2);
                if (lapisSlot >= 0) {
                    if (!ScreenDriver.clickAndVerify(lapisSlot, 0, SlotActionType.PICKUP, normLapis, 1)) return false;
                    if (!ScreenDriver.clickAndVerify(1, 0, SlotActionType.PICKUP, normLapis, 1)) return false;
                }
            }

            if (level != null) {
                try {
                    int buttonIndex = Integer.parseInt(level);
                    if (buttonIndex >= 0 && buttonIndex < 3) {
                        if (p.networkHandler != null) {
                            p.networkHandler.sendPacket(
                                new net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket(
                                    handler.syncId, buttonIndex));
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }

            sleep(200);
            return ScreenDriver.clickWithRecovery(0, 0, SlotActionType.QUICK_MOVE, null, 0, table, EnchantmentScreenHandler.class);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#enchant");
        } else {
            TaskQueue.getInstance().failActiveIf("#enchant", "enchant: failed");
        }
    }

    private static String executeAnvilAction(String actionJson) {
        WorkerThreads.start("mindcraft-anvil", () -> anvilWorker(actionJson));
        return "queued";
    }

    private static void anvilWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String input1 = extractJsonString(actionJson, "input1");
        String input2 = extractJsonString(actionJson, "input2");
        String name = extractJsonString(actionJson, "name");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: not connected");
            return;
        }

        BlockPos anvil = WorkstationFinder.findNearestStation(client.world, client.player, "anvil");
        if (anvil == null) {
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: no anvil nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(anvil, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: failed to reach anvil");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(anvil)) {
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: failed to open anvil");
            return;
        }

        if (!ScreenDriver.waitForHandler(AnvilScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null || !(p.currentScreenHandler instanceof AnvilScreenHandler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (input1 != null) {
                String normInput1 = ItemIds.normalize(input1);
                int slot = ScreenDriver.findSlot(screen, normInput1, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(0);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (input2 != null) {
                String normInput2 = ItemIds.normalize(input2);
                int slot = ScreenDriver.findSlot(screen, normInput2, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(1);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(200); // Longer wait for final operation
            return ScreenDriver.quickMove(2);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#anvil");
        } else {
            TaskQueue.getInstance().failActiveIf("#anvil", "anvil: failed");
        }
    }

    private static String executeGrindstoneAction(String actionJson) {
        WorkerThreads.start("mindcraft-grindstone", () -> grindstoneWorker(actionJson));
        return "queued";
    }

    private static void grindstoneWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String input1 = extractJsonString(actionJson, "input1");
        String input2 = extractJsonString(actionJson, "input2");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#grindstone", "grindstone: not connected");
            return;
        }

        BlockPos stone = WorkstationFinder.findNearestStation(client.world, client.player, "grindstone");
        if (stone == null) {
            TaskQueue.getInstance().failActiveIf("#grindstone", "no grindstone nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(stone, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#grindstone", "grindstone: failed to reach");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(stone)) {
            TaskQueue.getInstance().failActiveIf("#grindstone", "grindstone: failed to open");
            return;
        }

        if (!ScreenDriver.waitForHandler(GrindstoneScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#grindstone", "grindstone: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null || !(p.currentScreenHandler instanceof GrindstoneScreenHandler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (input1 != null) {
                String normInput1 = ItemIds.normalize(input1);
                int slot = ScreenDriver.findSlot(screen, normInput1, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(0);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (input2 != null) {
                String normInput2 = ItemIds.normalize(input2);
                int slot = ScreenDriver.findSlot(screen, normInput2, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(1);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(200); // Longer wait for final operation
            return ScreenDriver.quickMove(2);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#grindstone");
        } else {
            TaskQueue.getInstance().failActiveIf("#grindstone", "grindstone: failed");
        }
    }

    private static String executeStonecutAction(String actionJson) {
        WorkerThreads.start("mindcraft-stonecut", () -> stonecutWorker(actionJson));
        return "queued";
    }

    private static void stonecutWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String input = extractJsonString(actionJson, "input");
        String output = extractJsonString(actionJson, "output");
        String countStr = extractJsonPrimitive(actionJson, "count");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: not connected");
            return;
        }

        BlockPos cutter = WorkstationFinder.findNearestStation(client.world, client.player, "stonecutter");
        if (cutter == null) {
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: no stonecutter nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(cutter, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: failed to reach");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(cutter)) {
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: failed to open");
            return;
        }

        if (!ScreenDriver.waitForHandler(StonecutterScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: screen did not open");
            return;
        }

        int totalMade = 0;
        int targetCount = 1;
        if (countStr != null) {
            try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {}
        }

        for (int batch = 0; batch < targetCount && totalMade < targetCount; batch++) {
            if (TaskQueue.getInstance().isCancellationRequested()) break;

            boolean batchOk = ClientThread.call(() -> {
                ClientPlayerEntity p = client.player;
                if (p == null || !(p.currentScreenHandler instanceof StonecutterScreenHandler)) return false;
                ScreenHandler screen = p.currentScreenHandler;

                if (input != null) {
                    String normInput = ItemIds.normalize(input);
                    int inputSlot = ScreenDriver.findSlot(screen, normInput, 2);
                    if (inputSlot < 0) return false;
                    ScreenDriver.pickup(inputSlot);
                    ScreenDriver.pickup(0);
                    ScreenDriver.pickup(inputSlot);
                }

                sleep(200); // Longer wait for final operation
                return ScreenDriver.quickMove(1);
            });

            if (batchOk) totalMade++;
            else break;
        }

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (totalMade > 0) {
            TaskQueue.getInstance().completeActiveIf("#stonecut");
        } else {
            TaskQueue.getInstance().failActiveIf("#stonecut", "stonecut: failed to produce any items");
        }
    }

    private static String executeLoomAction(String actionJson) {
        WorkerThreads.start("mindcraft-loom", () -> loomWorker(actionJson));
        return "queued";
    }

    private static void loomWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String banner = extractJsonString(actionJson, "banner");
        String dye = extractJsonString(actionJson, "dye");
        String pattern = extractJsonString(actionJson, "pattern");

        MinecraftClient client = MinecraftClient.getInstance();
        Boolean connected = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player != null && c.world != null;
        });
        if (!Boolean.TRUE.equals(connected)) {
            TaskQueue.getInstance().failActiveIf("#loom", "loom: not connected");
            return;
        }

        BlockPos loom = WorkstationFinder.findNearestStation(client.world, client.player, "loom");
        if (loom == null) {
            TaskQueue.getInstance().failActiveIf("#loom", "loom: no loom nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(loom, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#loom", "loom: failed to reach");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(loom)) {
            TaskQueue.getInstance().failActiveIf("#loom", "loom: failed to open");
            return;
        }

        if (!ScreenDriver.waitForHandler(LoomScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#loom", "loom: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            if (p == null || !(p.currentScreenHandler instanceof LoomScreenHandler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (banner != null) {
                String normBanner = ItemIds.normalize(banner);
                int slot = ScreenDriver.findSlot(screen, normBanner, 4);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(0);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (dye != null) {
                String normDye = ItemIds.normalize(dye);
                int slot = ScreenDriver.findSlot(screen, normDye, 4);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(1);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (pattern != null) {
                String normPattern = ItemIds.normalize(pattern);
                int slot = ScreenDriver.findSlot(screen, normPattern, 4);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(2);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(200); // Longer wait for final operation
            return ScreenDriver.quickMove(3);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#loom");
        } else {
            TaskQueue.getInstance().failActiveIf("#loom", "loom: failed");
        }
    }

    private static String executeCartographyAction(String actionJson) {
        WorkerThreads.start("mindcraft-cartography", () -> cartographyWorker(actionJson));
        return "queued";
    }

    private static void cartographyWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String input1 = extractJsonString(actionJson, "input1");
        String input2 = extractJsonString(actionJson, "input2");

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: not connected");
            return;
        }

        BlockPos table = WorkstationFinder.findNearestStation(client.world, client.player, "cartography_table");
        if (table == null) {
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: no cartography table nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!waitUntilNear(table, 4.75, 30_000L)) {
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: failed to reach");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.openBlock(table)) {
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: failed to open");
            return;
        }

        if (!ScreenDriver.waitForHandler(CartographyTableScreenHandler.class, 3000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            if (p == null || !(p.currentScreenHandler instanceof CartographyTableScreenHandler)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (input1 != null) {
                String normInput1 = ItemIds.normalize(input1);
                int slot = ScreenDriver.findSlot(screen, normInput1, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(0);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(100); // Wait for server acknowledgment

            if (input2 != null) {
                String normInput2 = ItemIds.normalize(input2);
                int slot = ScreenDriver.findSlot(screen, normInput2, 3);
                if (slot >= 0) {
                    ScreenDriver.pickup(slot);
                    ScreenDriver.pickup(1);
                    ScreenDriver.pickup(slot);
                }
            }
            sleep(200); // Longer wait for final operation
            return ScreenDriver.quickMove(2);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#cartography");
        } else {
            TaskQueue.getInstance().failActiveIf("#cartography", "cartography: failed");
        }
    }

    private static String executeTradeAction(String actionJson) {
        WorkerThreads.start("mindcraft-trade", () -> tradeWorker(actionJson));
        return "queued";
    }

    private static void tradeWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String villagerIdStr = extractJsonPrimitive(actionJson, "villager_id");
        String tradeIndexStr = extractJsonPrimitive(actionJson, "trade_index");

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#trade", "trade: not connected");
            return;
        }
        ClientPlayerEntity player = client.player;

        int villagerId = -1;
        if (villagerIdStr != null) {
            try { villagerId = Integer.parseInt(villagerIdStr); } catch (NumberFormatException e) {
                TaskQueue.getInstance().failActiveIf("#trade", "trade: invalid villager_id");
                return;
            }
        }

        if (villagerId < 0) {
            Box box = player.getBoundingBox().expand(10.0);
            List<Entity> entities = client.world.getOtherEntities(player, box, e -> true);
            Entity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Entity e : entities) {
                if (e instanceof VillagerEntity) {
                    double d = player.squaredDistanceTo(e);
                    if (d < nearestDist) {
                        nearestDist = d;
                        nearest = e;
                    }
                }
            }
            if (nearest != null) {
                villagerId = nearest.getId();
            } else {
                TaskQueue.getInstance().failActiveIf("#trade", "trade: no villager found");
                return;
            }
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (!WorldInteractor.interactEntity(villagerId)) {
            TaskQueue.getInstance().failActiveIf("#trade", "trade: failed to interact with villager");
            return;
        }

        if (!ScreenDriver.waitForHandler(MerchantScreenHandler.class, 5000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#trade", "trade: trade screen did not open");
            return;
        }

        boolean ok = ClientThread.call(() -> {
            ClientPlayerEntity p = client.player;
            if (p == null || !(p.currentScreenHandler instanceof MerchantScreenHandler merchant)) return false;
            ScreenHandler screen = p.currentScreenHandler;

            if (tradeIndexStr != null) {
                try {
                    int index = Integer.parseInt(tradeIndexStr);
                    merchant.switchTo(index);
                } catch (NumberFormatException ignored) {}
            }

            sleep(100);
            return ScreenDriver.quickMove(2);
        });

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#trade");
        } else {
            TaskQueue.getInstance().failActiveIf("#trade", "trade: failed");
        }
    }

    private static String executeObtainAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        String countStr = extractJsonPrimitive(actionJson, "count");
        int count = 1;
        if (countStr != null) {
            try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
        }
        if (count < 1) count = 1;
        final int finalCount = count;
        final String finalItem = item;

        WorkerThreads.start("mindcraft-obtain", () -> obtainWorker(finalItem, finalCount));
        return "queued " + count + "x " + item;
    }

    private static void obtainWorker(String item, int count) {
        String normalized = ItemIds.normalize(item);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#obtain", "obtain: not connected");
            return;
        }

        Map<String, Integer> inventoryMap = new HashMap<>();
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                String id = ItemIds.fromStack(stack);
                inventoryMap.merge(id, stack.getCount(), Integer::sum);
            }
        }

        PlanContext ctx = new PlanContext(
            inventoryMap,
            DimensionDriver.getCurrentDimension(),
            true, true
        );

        for (ItemProvider provider : allProviders()) {
            if (!provider.canProvide(normalized, ctx)) continue;
            ProviderPlan plan = provider.plan(normalized, count, ctx);
            if (!plan.ok()) continue;

            if (plan.steps().isEmpty()) {
                TaskQueue.getInstance().completeActiveIf("#obtain");
                return;
            }

            for (PlanStep step : plan.steps()) {
                String fullJson = "{\"type\":\"" + step.actionType() + "\"," + step.payloadJson().substring(1);
                TranslatedAction ta = CommandExecutor.translateTypedJson(fullJson);
                if (!ta.ok()) {
                    TaskQueue.getInstance().failActiveIf("#obtain",
                        "obtain: step failed - " + step.actionType() + ": " + ta.message());
                    return;
                }

                if (ta.genericWorker()) {
                    Worker worker = ActionRegistry.get().getWorker(ta.actionType());
                    if (worker == null) {
                        TaskQueue.getInstance().failActiveIf("#obtain",
                            "obtain: no worker for " + step.actionType());
                        return;
                    }
                    WorkerContext workerCtx = new WorkerContext(
                        TaskQueue.getInstance(),
                        () -> TaskQueue.getInstance().isCancellationRequested(),
                        fullJson,
                        ta.actionType()
                    );
                    WorkerResult result = worker.execute(fullJson, workerCtx);
                    if (result == null || !result.ok()) {
                        TaskQueue.getInstance().failActiveIf("#obtain",
                            "obtain: worker failed - " + step.actionType() + ": "
                                + (result == null ? "null worker result" : result.error()));
                        return;
                    }
                } else if ("self_executing".equals(ta.lifecycle())) {
                    long stepId = TaskQueue.getInstance().createNestedTrackingTask(
                        "#" + step.actionType(), step.actionType());
                    if (!obtainWaitForStep(stepId)) {
                        TaskQueue.getInstance().failActiveIf("#obtain",
                            "obtain: step timed out - " + step.actionType());
                        return;
                    }
                } else if ("queued".equals(ta.lifecycle()) && ta.command() != null) {
                    TaskQueue.getInstance().suspendActiveTask();
                    TaskQueue.getInstance().enqueueDetailed(java.util.List.of(
                        new TaskQueue.QueuedCommand(ta.command(), ta.actionType())));
                    obtainWaitForIdle();
                    TaskQueue.getInstance().restoreSuspendedTask();
                }
            }

            TaskQueue.getInstance().completeActiveIf("#obtain");
            return;
        }

        TaskQueue.getInstance().failActiveIf("#obtain", "obtain: no provider for " + item);
    }

    private static boolean obtainWaitForStep(long stepId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.QueueState state = TaskQueue.getInstance().getQueueState();
            Long activeId = state.activeId();
            if (activeId == null || activeId != stepId) return true;
            if ("paused".equals(state.status())) return false;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return false;
            }
        }
        return false;
    }

    private static void obtainWaitForIdle() {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.QueueState state = TaskQueue.getInstance().getQueueState();
            String status = state.status();
            if ("idle".equals(status) || "disabled".equals(status)) return;
            if ("paused".equals(status)) return;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }

    private static boolean waitForQueueIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.QueueState state = TaskQueue.getInstance().getQueueState();
            if ("paused".equals(state.status())) return false;
            if (state.activeId() == null && state.pending() == 0) return true;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public static java.util.List<ItemProvider> allProviders() {
        if (ALL_PROVIDERS == null) {
            ALL_PROVIDERS = java.util.Arrays.<ItemProvider>asList(
                new com.mindcraft.bridge.providers.InventoryProvider(),
                new com.mindcraft.bridge.providers.CraftingProvider(),
                new com.mindcraft.bridge.providers.SmeltingProvider(),
                new com.mindcraft.bridge.providers.MiningProvider(),
                new com.mindcraft.bridge.providers.SmithingProvider(),
                new com.mindcraft.bridge.providers.BrewingProvider(),
                new com.mindcraft.bridge.providers.FarmingProvider(),
                new com.mindcraft.bridge.providers.FishingProvider(),
                new com.mindcraft.bridge.providers.MobDropProvider(),
                new com.mindcraft.bridge.providers.VillagerTradeProvider(),
                new com.mindcraft.bridge.providers.DimensionalTravelProvider(),
                new com.mindcraft.bridge.providers.VillagerManipulationProvider(),
                new com.mindcraft.bridge.providers.EnchantmentProvider(),
                new com.mindcraft.bridge.providers.ContainerLootProvider(),
                new com.mindcraft.bridge.providers.FluidCollectionProvider(),
                new com.mindcraft.bridge.providers.HusbandryProvider()
            );
        }
        return ALL_PROVIDERS;
    }
    private static java.util.List<ItemProvider> ALL_PROVIDERS;

    private static PlanContext buildPlanContext(ClientPlayerEntity player) {
        Map<String, Integer> inventory = new HashMap<>();
        String dimension = DimensionDriver.getCurrentDimension();
        return new PlanContext(inventory, dimension, true, true);
    }

    // ─── Phase 5: Combat Extensions ──────────────────────────────────────────

    private static String executeRangedAttackAction(String actionJson) {
        WorkerThreads.start("mindcraft-ranged", () -> rangedAttackWorker(actionJson));
        return "queued";
    }

    private static void rangedAttackWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String targetTypeRaw = extractJsonString(actionJson, "target_type");
        String maxDistStr = extractJsonPrimitive(actionJson, "max_distance");
        String countStr = extractJsonPrimitive(actionJson, "count");

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#ranged_attack", "ranged_attack: not connected");
            return;
        }

        double maxDistance = 48.0;
        if (maxDistStr != null) {
            try { maxDistance = Double.parseDouble(maxDistStr); } catch (NumberFormatException ignored) {}
        }
        int targetCount = 1;
        if (countStr != null) {
            try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {}
        }

        int bowSlot = -1;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = ItemIds.fromStack(stack).toLowerCase();
            if (id.contains("bow") || id.contains("crossbow")) {
                bowSlot = i;
                break;
            }
        }
        if (bowSlot < 0) {
            TaskQueue.getInstance().failActiveIf("#ranged_attack", "ranged_attack: no bow/crossbow in hotbar");
            return;
        }

        boolean hasArrows = InventoryDriver.countItem(player, "minecraft:arrow") > 0;
        if (!hasArrows) {
            TaskQueue.getInstance().failActiveIf("#ranged_attack", "ranged_attack: no arrows");
            return;
        }

        InventoryDriver.selectSlot(bowSlot);

        int kills = 0;
        int attempts = 0;
        int maxAttempts = targetCount * 3;

        while (kills < targetCount && attempts < maxAttempts) {
            if (TaskQueue.getInstance().isCancellationRequested()) return;
            player = client.player;
            if (player == null || player.isDead()) return;

            Entity target = null;
            for (var entity : client.world.getEntities()) {
                if (entity == player) continue;
                if (!entity.isAlive()) continue;
                if (player.squaredDistanceTo(entity) > maxDistance * maxDistance) continue;
                String typeStr = entity.getType().toString().toLowerCase();
                if (targetTypeRaw != null && !targetTypeRaw.isBlank()) {
                    if (!typeStr.contains(targetTypeRaw.toLowerCase())) continue;
                } else if (!typeStr.contains("skeleton") && !typeStr.contains("zombie")
                        && !typeStr.contains("spider") && !typeStr.contains("creeper")) {
                    continue;
                }
                target = entity;
                break;
            }

            if (target == null) {
                attempts++;
                sleep(1000);
                continue;
            }

            final Entity t = target;
            final ClientPlayerEntity p = player;
            ClientThread.run(() -> lookAtEntity(p, t));

            int chargeMs = 1000;
            if (ItemIds.fromStack(player.getMainHandStack()).toLowerCase().contains("crossbow")) {
                chargeMs = 1250;
            }

            ClientThread.run(() -> {
                if (client.interactionManager != null && client.options != null) {
                    client.options.useKey.setPressed(true);
                }
            });
            sleep(chargeMs);
            final ClientPlayerEntity p2 = player;
            ClientThread.run(() -> {
                if (client.interactionManager != null && client.options != null) {
                    client.options.useKey.setPressed(false);
                }
                p2.swingHand(Hand.MAIN_HAND);
            });

            sleep(500);

            if (target.isRemoved() || !target.isAlive()) {
                kills++;
                sendBridgeMessage("[Bridge] Ranged: killed " + normalizeEntityTypeName(target.getType().toString()) + " (" + kills + "/" + targetCount + ")");
            }
            attempts++;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#ranged_attack");
    }

    private static String executeDefendAction(String actionJson) {
        WorkerThreads.start("mindcraft-defend", () -> defendWorker(actionJson));
        return "queued";
    }

    private static void defendWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String durationStr = extractJsonPrimitive(actionJson, "duration_s");
        int durationS = 30;
        if (durationStr != null) {
            try { durationS = Math.max(5, Math.min(120, Integer.parseInt(durationStr))); }
            catch (NumberFormatException ignored) {}
        }

        MinecraftClient client = MinecraftClient.getInstance();
        final ClientPlayerEntity player = client.player;
        if (player == null) {
            TaskQueue.getInstance().failActiveIf("#defend", "defend: not connected");
            return;
        }

        boolean hasShield = InventoryDriver.countItem(player, "minecraft:shield") > 0;
        if (!hasShield) {
            TaskQueue.getInstance().failActiveIf("#defend", "defend: no shield");
            return;
        }

        ClientThread.run(() -> {
            if (client.options != null) client.options.useKey.setPressed(true);
        });

        long deadline = System.currentTimeMillis() + (durationS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (TaskQueue.getInstance().isCancellationRequested()) {
                ClientThread.run(() -> {
                    if (client.options != null) client.options.useKey.setPressed(false);
                });
                return;
            }
            sleep(500);
        }

        ClientThread.run(() -> {
            if (client.options != null) client.options.useKey.setPressed(false);
        });

        TaskQueue.getInstance().completeActiveIf("#defend");
    }

    private static String executeRetreatAction(String actionJson) {
        String distStr = extractJsonPrimitive(actionJson, "distance");
        double distance = 24.0;
        if (distStr != null) {
            try { distance = Double.parseDouble(distStr); } catch (NumberFormatException ignored) {}
        }
        return "#goto away " + distance;
    }

    private static String executeClearHostilesAction(String actionJson) {
        String actionData = "{\"type\":\"attack\",\"target_type\":\"\",\"count\":999,\"search_time_s\":30,\"retreat_hp\":8}";
        return executeAttackAction(actionData, "#clear_hostiles");
    }

    private static String executeHuntMobAction(String actionJson) {
        return executeAttackAction(actionJson, "#hunt_mob");
    }

    private static String executeFarmAction(String actionJson) {
        WorkerThreads.start("mindcraft-farm", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("farm").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#farm");
                } else {
                    TaskQueue.getInstance().failActiveIf("#farm", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#farm", "farm: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void farmWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String cropId = extractJsonString(actionJson, "crop");
        String countStr = extractJsonPrimitive(actionJson, "count");
        if (cropId == null) { TaskQueue.getInstance().failActiveIf("#farm", "farm: missing crop"); return; }
        int targetCount = 1;
        if (countStr != null) { try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {} }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#farm", "farm: not connected"); return;
        }

        String cropBlock = cropId.replace("_seeds", "").replace("minecraft:", "minecraft:");
        if (cropBlock.equals("minecraft:wheat")) cropBlock = "minecraft:wheat";
        else if (cropBlock.equals("minecraft:carrot")) cropBlock = "minecraft:carrots";
        else if (cropBlock.equals("minecraft:potato")) cropBlock = "minecraft:potatoes";
        else if (cropBlock.equals("minecraft:beetroot")) cropBlock = "minecraft:beetroots";

        int collected = 0;
        int maxSearch = targetCount * 10;
        for (int attempt = 0; attempt < maxSearch && collected < targetCount; attempt++) {
            if (TaskQueue.getInstance().isCancellationRequested()) break;
            BlockPos found = WorkstationFinder.findNearest(client.world, player, cropBlock, 16);
            if (found == null) {
                if (collected > 0) break;
                TaskQueue.getInstance().failActiveIf("#farm", "farm: no " + cropBlock + " found nearby");
                return;
            }
            if (!WorldInteractor.breakBlock(found)) {
                sleep(200); continue;
            }
            sleep(300);
            collected++;
            sleep(100);
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (collected >= targetCount) {
            TaskQueue.getInstance().completeActiveIf("#farm");
        } else {
            TaskQueue.getInstance().failActiveIf("#farm", "farm: collected " + collected + "/" + targetCount);
        }
    }

    private static String executeFishAction(String actionJson) {
        WorkerThreads.start("mindcraft-fish", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("fish").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#fish");
                } else {
                    TaskQueue.getInstance().failActiveIf("#fish", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#fish", "fish: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void fishWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String countStr = extractJsonPrimitive(actionJson, "count");
        int targetCount = 1;
        if (countStr != null) { try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {} }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#fish", "fish: not connected"); return;
        }

        int rodSlot = -1;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals("minecraft:fishing_rod")) {
                rodSlot = i; break;
            }
        }
        if (rodSlot < 0) {
            TaskQueue.getInstance().failActiveIf("#fish", "fish: no fishing rod in hotbar"); return;
        }

        BlockPos water = null;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos playerPos = player.getBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (client.world.getBlockState(mutable).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                        water = mutable.toImmutable();
                        break;
                    }
                }
                if (water != null) break;
            }
            if (water != null) break;
        }
        if (water == null) {
            TaskQueue.getInstance().failActiveIf("#fish", "fish: no water found nearby"); return;
        }

        InventoryDriver.selectSlot(rodSlot);
        sleep(200);

        int caught = 0;
        for (int attempt = 0; attempt < targetCount * 5 && caught < targetCount; attempt++) {
            if (TaskQueue.getInstance().isCancellationRequested()) break;

            ClientThread.run(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player != null && c.interactionManager != null) {
                    c.interactionManager.interactItem(c.player, net.minecraft.util.Hand.MAIN_HAND);
                }
            });
            sleep(500);

            long biteDeadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < biteDeadline) {
                if (TaskQueue.getInstance().isCancellationRequested()) break;
                ClientThread.run(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null && c.interactionManager != null) {
                        c.interactionManager.interactItem(c.player, net.minecraft.util.Hand.MAIN_HAND);
                    }
                });
                sleep(200);
                break;
            }
            sleep(1000);
            caught++;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (caught >= targetCount) {
            TaskQueue.getInstance().completeActiveIf("#fish");
        } else {
            TaskQueue.getInstance().failActiveIf("#fish", "fish: caught " + caught + "/" + targetCount);
        }
    }

    private static String executeLootAction(String actionJson) {
        WorkerThreads.start("mindcraft-loot", () -> {
            try {
                var ctx = new com.mindcraft.bridge.workers.WorkerContext(
                    TaskQueue.getInstance(),
                    () -> TaskQueue.getInstance().isCancellationRequested(),
                    actionJson
                );
                var result = com.mindcraft.bridge.workers.ActionRegistry.get().getWorker("loot").execute(actionJson, ctx);
                if (result.ok()) {
                    TaskQueue.getInstance().completeActiveIf("#loot");
                } else {
                    TaskQueue.getInstance().failActiveIf("#loot", result.error());
                }
            } catch (Exception e) {
                TaskQueue.getInstance().failActiveIf("#loot", "loot: " + e.getMessage());
            }
        });
        return "queued";
    }

    private static void lootWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String targetId = extractJsonString(actionJson, "target");
        if (targetId == null) { TaskQueue.getInstance().failActiveIf("#loot", "loot: missing target"); return; }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#loot", "loot: not connected"); return;
        }

        String containerBlock = null;
        BlockPos containerPos = null;
        for (String candidate : new String[]{"minecraft:chest", "minecraft:barrel", "minecraft:trapped_chest"}) {
            BlockPos found = WorkstationFinder.findNearest(client.world, player, candidate, 16);
            if (found != null) {
                containerBlock = candidate;
                containerPos = found;
                if (!WorldInteractor.openBlock(found)) {
                    sleep(200); continue;
                }
                break;
            }
        }
        if (containerBlock == null) {
            TaskQueue.getInstance().failActiveIf("#loot", "loot: no container found nearby"); return;
        }

        if (!ScreenDriver.waitForHandler(net.minecraft.screen.GenericContainerScreenHandler.class, 5000)) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#loot", "loot: container screen did not open"); return;
        }

        String normalized = ItemIds.normalize(targetId);
        int slot = ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null || !(p.currentScreenHandler instanceof net.minecraft.screen.GenericContainerScreenHandler handler)) return -1;
            for (int i = 0; i < handler.getInventory().size(); i++) {
                ItemStack stack = handler.getInventory().getStack(i);
                if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(normalized)) return i;
            }
            return -1;
        });

        if (slot < 0) {
            ScreenDriver.closeScreen();
            TaskQueue.getInstance().failActiveIf("#loot", "loot: item not found in container");
            return;
        }

        // Transfer with verification
        boolean transferred = ScreenDriver.clickWithRecovery(
            slot, 0, SlotActionType.QUICK_MOVE, null, 0, containerPos, net.minecraft.screen.GenericContainerScreenHandler.class
        );

        ScreenDriver.closeScreen();

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (transferred) {
            TaskQueue.getInstance().completeActiveIf("#loot");
        } else {
            TaskQueue.getInstance().failActiveIf("#loot", "loot: failed to transfer item (desync)");
        }
    }

    private static String executeCollectFluidAction(String actionJson) {
        WorkerThreads.start("mindcraft-collect-fluid", () -> collectFluidWorker(actionJson));
        return "queued";
    }

    private static void collectFluidWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String targetId = extractJsonString(actionJson, "target");
        if (targetId == null) { TaskQueue.getInstance().failActiveIf("#collect_fluid", "collect_fluid: missing target"); return; }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#collect_fluid", "collect_fluid: not connected"); return;
        }

        int bucketSlot = -1;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals("minecraft:bucket")) {
                bucketSlot = i; break;
            }
        }
        if (bucketSlot < 0) {
            TaskQueue.getInstance().failActiveIf("#collect_fluid", "collect_fluid: no bucket in hotbar"); return;
        }

        net.minecraft.registry.tag.TagKey<net.minecraft.fluid.Fluid> fluidTag;
        if (targetId.contains("lava")) {
            fluidTag = net.minecraft.registry.tag.FluidTags.LAVA;
        } else {
            fluidTag = net.minecraft.registry.tag.FluidTags.WATER;
        }

        BlockPos fluidPos = null;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos playerPos = player.getBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    if (client.world.getBlockState(mutable).getFluidState().isIn(fluidTag)) {
                        fluidPos = mutable.toImmutable();
                        break;
                    }
                }
                if (fluidPos != null) break;
            }
            if (fluidPos != null) break;
        }
        if (fluidPos == null) {
            TaskQueue.getInstance().failActiveIf("#collect_fluid", "collect_fluid: no " + targetId + " source found nearby"); return;
        }

        InventoryDriver.selectSlot(bucketSlot);
        sleep(200);

        boolean ok = WorldInteractor.interactBlock(fluidPos, net.minecraft.util.math.Direction.UP);
        sleep(300);

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#collect_fluid");
        } else {
            TaskQueue.getInstance().failActiveIf("#collect_fluid", "collect_fluid: failed to collect from " + fluidPos.toShortString());
        }
    }

    private static boolean isCreeper(Entity e) {
        return e.getType().toString().toLowerCase().contains("creeper");
    }

    private static boolean isSkeleton(Entity e) {
        String type = e.getType().toString().toLowerCase();
        return type.contains("skeleton") && !type.contains("wither");
    }

    private static boolean isEnderman(Entity e) {
        return e.getType().toString().toLowerCase().contains("enderman");
    }

    private static boolean isBlaze(Entity e) {
        return e.getType().toString().toLowerCase().contains("blaze");
    }

    private static boolean isGhast(Entity e) {
        return e.getType().toString().toLowerCase().contains("ghast");
    }

    // ─── Phase 6: Vehicles, Elytra, and Dimensions ──────────────────────────

    private static String executeRideEntityAction(String actionJson) {
        String entityIdStr = extractJsonPrimitive(actionJson, "entity_id");
        if (entityIdStr == null) return "ride_entity: missing entity_id";
        try {
            int entityId = Integer.parseInt(entityIdStr);
            return WorldInteractor.interactEntity(entityId) ? "ride_entity: mounted" : "ride_entity: failed";
        } catch (NumberFormatException e) {
            return "ride_entity: invalid entity_id";
        }
    }

    private static String executeUseBoatAction(String actionJson) {
        WorkerThreads.start("mindcraft-boat", () -> boatWorker(actionJson));
        return "queued";
    }

    private static void boatWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String targetX = extractJsonPrimitive(actionJson, "x");
        String targetZ = extractJsonPrimitive(actionJson, "z");

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#use_boat", "use_boat: not connected");
            return;
        }

        Entity boat = null;
        for (var entity : client.world.getEntities()) {
            if (entity.getType().toString().toLowerCase().contains("boat")) {
                boat = entity;
                break;
            }
        }
        if (boat == null) {
            TaskQueue.getInstance().failActiveIf("#use_boat", "use_boat: no boat nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        VehicleDriver.dismount();
        sleep(200);

        final Entity b = boat;
        boolean mounted = Boolean.TRUE.equals(ClientThread.call(() -> {
            if (client.interactionManager != null && client.player != null) {
                client.interactionManager.interactEntity(client.player, b, Hand.MAIN_HAND);
                return true;
            }
            return false;
        }));
        if (!mounted) {
            TaskQueue.getInstance().failActiveIf("#use_boat", "use_boat: failed to mount");
            return;
        }

        sleep(1000);

        if (targetX != null && targetZ != null) {
            try {
                int tx = Integer.parseInt(targetX);
                int tz = Integer.parseInt(targetZ);
                long deadline = System.currentTimeMillis() + 60_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (TaskQueue.getInstance().isCancellationRequested()) return;
                    if (client.player == null || client.player.getVehicle() == null) break;
                    double dx = tx - client.player.getX();
                    double dz = tz - client.player.getZ();
                    if (dx * dx + dz * dz < 9.0) break;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    ClientThread.run(() -> {
                        if (client.player != null) {
                            client.player.setYaw(yaw);
                            client.player.setHeadYaw(yaw);
                        }
                    });
                    sleep(1000);
                }
            } catch (NumberFormatException ignored) {}
        }

        VehicleDriver.dismount();
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#use_boat");
    }

    private static String executeUseMinecartAction(String actionJson) {
        WorkerThreads.start("mindcraft-minecart", () -> minecartWorker(actionJson));
        return "queued";
    }

    private static void minecartWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#use_minecart", "use_minecart: not connected");
            return;
        }

        Entity cart = null;
        for (var entity : client.world.getEntities()) {
            if (entity.getType().toString().toLowerCase().contains("minecart")) {
                cart = entity;
                break;
            }
        }
        if (cart == null) {
            TaskQueue.getInstance().failActiveIf("#use_minecart", "use_minecart: no minecart nearby");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        final Entity c = cart;
        boolean mounted = Boolean.TRUE.equals(ClientThread.call(() -> {
            if (client.interactionManager != null && client.player != null) {
                client.interactionManager.interactEntity(client.player, c, Hand.MAIN_HAND);
                return true;
            }
            return false;
        }));
        if (!mounted) {
            TaskQueue.getInstance().failActiveIf("#use_minecart", "use_minecart: failed to mount");
            return;
        }

        sleep(2000);
        VehicleDriver.dismount();
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#use_minecart");
    }

    private static String executeDismountAction() {
        boolean ok = VehicleDriver.dismount();
        return ok ? "dismounted" : "not mounted";
    }

    private static String executeElytraFlyAction(String actionJson) {
        WorkerThreads.start("mindcraft-elytra", () -> elytraWorker(actionJson));
        return "queued";
    }

    private static void elytraWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String targetX = extractJsonPrimitive(actionJson, "x");
        String targetZ = extractJsonPrimitive(actionJson, "z");

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf("#elytra_fly", "elytra_fly: not connected");
            return;
        }

        boolean hasElytra = InventoryDriver.countItem(player, "minecraft:elytra") > 0;
        if (!hasElytra) {
            TaskQueue.getInstance().failActiveIf("#elytra_fly", "elytra_fly: no elytra");
            return;
        }

        ClientThread.run(() -> {
            if (client.player != null && client.options != null) {
                client.options.jumpKey.setPressed(true);
            }
        });
        sleep(500);
        ClientThread.run(() -> {
            if (client.player != null && client.options != null) {
                client.options.jumpKey.setPressed(false);
            }
        });
        sleep(200);

        long deadlineGlide = System.currentTimeMillis() + 5000;
        boolean gliding = false;
        while (System.currentTimeMillis() < deadlineGlide) {
            if (TaskQueue.getInstance().isCancellationRequested()) return;
            player = client.player;
            if (player == null) return;
            if (player.getVelocity().y < 0) { gliding = true; break; }
            sleep(200);
        }
        if (!gliding) {
            TaskQueue.getInstance().failActiveIf("#elytra_fly", "elytra_fly: failed to start gliding");
            return;
        }

        if (targetX != null && targetZ != null) {
            try {
                int tx = Integer.parseInt(targetX);
                int tz = Integer.parseInt(targetZ);
                long deadline = System.currentTimeMillis() + 30_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (TaskQueue.getInstance().isCancellationRequested()) return;
                    if (client.player == null) break;
                    boolean descending = client.player.getVelocity().y < 0;
                    double dx = tx - client.player.getX();
                    double dz = tz - client.player.getZ();
                    if (dx * dx + dz * dz < 25.0) break;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = -15.0f;
                    ClientThread.run(() -> {
                        if (client.player != null) {
                            client.player.setYaw(yaw);
                            client.player.setHeadYaw(yaw);
                            client.player.setPitch(pitch);
                        }
                    });
                    sleep(1000);
                }
            } catch (NumberFormatException ignored) {}
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#elytra_fly");
    }

    private static String executePortalTravelAction(String actionJson) {
        return executePortalTravelAction(actionJson, "#portal_travel");
    }

    private static String executePortalTravelAction(String actionJson, String trackingCommand) {
        String dimension = extractJsonString(actionJson, "dimension");
        if (dimension == null) return "portal_travel: missing dimension";

        String target = DimensionDriver.normalizeDimension(dimension);
        WorkerThreads.start("mindcraft-portal", () -> portalTravelWorker(target, trackingCommand));
        return "queued to " + target;
    }

    private static void portalTravelWorker(String dimension, String trackingCommand) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (DimensionDriver.isCurrentDimension(dimension)) {
            TaskQueue.getInstance().completeActiveIf(trackingCommand);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            TaskQueue.getInstance().failActiveIf(trackingCommand, "portal_travel: not connected");
            return;
        }

        String portalBlock = dimension.contains("the_nether") ? "nether_portal" :
                             dimension.contains("the_end") ? "end_portal" : "nether_portal";

        BlockPos portal = WorkstationFinder.findNearest(client.world, client.player, "minecraft:" + portalBlock, 32);
        if (portal == null) {
            TaskQueue.getInstance().failActiveIf(trackingCommand, "portal_travel: no portal found");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (!waitUntilNear(portal, 2.0, 60_000L)) {
            TaskQueue.getInstance().failActiveIf(trackingCommand, "portal_travel: failed to reach portal");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (!waitUntilInPortalBlock(dimension, 30_000L)) {
            TaskQueue.getInstance().failActiveIf(trackingCommand, "portal_travel: reached portal but did not enter");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;

        if (!DimensionDriver.waitForDimension(dimension, 30_000L)) {
            TaskQueue.getInstance().failActiveIf(trackingCommand, "portal_travel: dimension change timeout");
            return;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf(trackingCommand);
    }

    private static String executeReturnToOverworldAction() {
        if (DimensionDriver.isCurrentDimension("minecraft:overworld")) {
            return "already in overworld";
        }
        String target = "minecraft:overworld";
        return executePortalTravelAction("{\"dimension\":\"" + target + "\"}", "#return_to_overworld");
    }

    private static boolean waitUntilInPortalBlock(String targetDimension, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (TaskQueue.getInstance().isCancellationRequested()) return false;
            if (DimensionDriver.isCurrentDimension(targetDimension)) return true;

            Boolean inPortal = ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null || client.world == null) return false;
                BlockPos feet = client.player.getBlockPos();
                BlockState feetState = client.world.getBlockState(feet);
                BlockState headState = client.world.getBlockState(feet.up());
                return feetState.isOf(Blocks.NETHER_PORTAL)
                        || headState.isOf(Blocks.NETHER_PORTAL)
                        || feetState.isOf(Blocks.END_PORTAL)
                        || headState.isOf(Blocks.END_PORTAL);
            });

            if (Boolean.TRUE.equals(inPortal)) return true;
            sleep(250L);
        }
        return false;
    }

    // ─── Phase 7: Building Validation and Repair ─────────────────────────────

    private static String executePlaceBlockAction(String actionJson) {
        String xStr = extractJsonPrimitive(actionJson, "x");
        String yStr = extractJsonPrimitive(actionJson, "y");
        String zStr = extractJsonPrimitive(actionJson, "z");
        String block = extractJsonString(actionJson, "block");
        String direction = extractJsonString(actionJson, "direction");

        if (xStr == null || yStr == null || zStr == null || block == null) {
            return "place_block: missing parameters";
        }

        try {
            BlockPos pos = new BlockPos(Integer.parseInt(xStr), Integer.parseInt(yStr), Integer.parseInt(zStr));
            Direction dir = direction != null ? Direction.valueOf(direction.toUpperCase()) : Direction.UP;
            return WorldInteractor.placeBlock(pos, dir) ? "place_block: placed" : "place_block: failed";
        } catch (IllegalArgumentException e) {
            return "place_block: invalid parameters";
        }
    }

    private static String executeBreakBlockAction(String actionJson) {
        String xStr = extractJsonPrimitive(actionJson, "x");
        String yStr = extractJsonPrimitive(actionJson, "y");
        String zStr = extractJsonPrimitive(actionJson, "z");
        if (xStr == null || yStr == null || zStr == null) {
            return "break_block: missing coordinates";
        }
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(xStr), Integer.parseInt(yStr), Integer.parseInt(zStr));
            return WorldInteractor.breakBlock(pos) ? "break_block: broke " + pos.toShortString() : "break_block: failed";
        } catch (NumberFormatException e) {
            return "break_block: invalid coordinates";
        }
    }

    private static String executeValidateStructureAction(String actionJson) {
        WorkerThreads.start("mindcraft-validate", () -> validateWorker(actionJson));
        return "queued";
    }

    private static void validateWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;

        String originObj = extractJsonObject(actionJson, "origin");
        String sizeObj = extractJsonObject(actionJson, "size");
        if (originObj == null || sizeObj == null) {
            TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: missing origin/size");
            return;
        }

        Integer ox = parseIntSafe(extractJsonPrimitive(originObj, "x"));
        Integer oy = parseIntSafe(extractJsonPrimitive(originObj, "y"));
        Integer oz = parseIntSafe(extractJsonPrimitive(originObj, "z"));
        Integer sx = parseIntSafe(extractJsonPrimitive(sizeObj, "x"));
        Integer sy = parseIntSafe(extractJsonPrimitive(sizeObj, "y"));
        Integer sz = parseIntSafe(extractJsonPrimitive(sizeObj, "z"));
        if (ox == null || oy == null || oz == null || sx == null || sy == null || sz == null) {
            TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: invalid origin/size numbers");
            return;
        }
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: size must be positive");
            return;
        }
        long total = (long) sx * (long) sy * (long) sz;

        List<String> palette = parseStringArray(extractJsonArrayRaw(actionJson, "palette"));
        if (palette == null || palette.isEmpty()) {
            TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: empty palette");
            return;
        }

        short[] blocks;
        String b64 = extractJsonString(actionJson, "blocks");
        if (b64 != null && !b64.isBlank()) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(b64.trim());
                if (raw.length < total * 2) {
                    TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: blocks payload too short");
                    return;
                }
                blocks = new short[(int) total];
                for (int i = 0; i < total; i++) {
                    int lo = raw[i * 2] & 0xFF;
                    int hi = raw[i * 2 + 1] & 0xFF;
                    blocks[i] = (short) (lo | (hi << 8));
                }
            } catch (IllegalArgumentException iae) {
                TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: bad base64 blocks");
                return;
            }
        } else {
            String arr = extractJsonArrayRaw(actionJson, "blocks");
            if (arr == null) {
                TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: no blocks payload");
                return;
            }
            List<String> ints = splitJsonArray(arr);
            if (ints.size() < total) {
                TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: blocks count < size");
                return;
            }
            blocks = new short[(int) total];
            for (int i = 0; i < total; i++) {
                try {
                    blocks[i] = (short) Integer.parseInt(ints.get(i).trim());
                } catch (NumberFormatException nfe) {
                    TaskQueue.getInstance().failActiveIf("#validate_structure", "validate_structure: non-integer block index");
                    return;
                }
            }
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        BlockPos origin = new BlockPos(ox, oy, oz);
        List<StructureValidator.BlockMismatch> mismatches = StructureValidator.compare(palette, blocks, sx, sy, sz, origin);

        if (mismatches.isEmpty()) {
            sendBridgeMessage("[Bridge] Validate: structure matches");
        } else {
            StringBuilder msg = new StringBuilder("[Bridge] Validate: ").append(mismatches.size()).append(" mismatches");
            for (int i = 0; i < Math.min(5, mismatches.size()); i++) {
                StructureValidator.BlockMismatch m = mismatches.get(i);
                msg.append("; ").append(m.pos().toShortString()).append(" expected ").append(m.expected()).append(" got ").append(m.actual());
            }
            if (mismatches.size() > 5) msg.append("; ... and ").append(mismatches.size() - 5).append(" more");
            sendBridgeMessage(msg.toString());
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#validate_structure");
    }

    private static String executeRepairStructureAction(String actionJson) {
        WorkerThreads.start("mindcraft-repair", () -> repairWorker(actionJson));
        return "queued";
    }

    private static void repairWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;

        String originObj = extractJsonObject(actionJson, "origin");
        String sizeObj = extractJsonObject(actionJson, "size");
        if (originObj == null || sizeObj == null) {
            TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: missing origin/size");
            return;
        }

        Integer ox = parseIntSafe(extractJsonPrimitive(originObj, "x"));
        Integer oy = parseIntSafe(extractJsonPrimitive(originObj, "y"));
        Integer oz = parseIntSafe(extractJsonPrimitive(originObj, "z"));
        Integer sx = parseIntSafe(extractJsonPrimitive(sizeObj, "x"));
        Integer sy = parseIntSafe(extractJsonPrimitive(sizeObj, "y"));
        Integer sz = parseIntSafe(extractJsonPrimitive(sizeObj, "z"));
        if (ox == null || oy == null || oz == null || sx == null || sy == null || sz == null) {
            TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: invalid origin/size numbers");
            return;
        }
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: size must be positive");
            return;
        }
        long total = (long) sx * (long) sy * (long) sz;

        List<String> palette = parseStringArray(extractJsonArrayRaw(actionJson, "palette"));
        if (palette == null || palette.isEmpty()) {
            TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: empty palette");
            return;
        }

        short[] blocks;
        String b64 = extractJsonString(actionJson, "blocks");
        if (b64 != null && !b64.isBlank()) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(b64.trim());
                if (raw.length < total * 2) {
                    TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: blocks payload too short");
                    return;
                }
                blocks = new short[(int) total];
                for (int i = 0; i < total; i++) {
                    int lo = raw[i * 2] & 0xFF;
                    int hi = raw[i * 2 + 1] & 0xFF;
                    blocks[i] = (short) (lo | (hi << 8));
                }
            } catch (IllegalArgumentException iae) {
                TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: bad base64 blocks");
                return;
            }
        } else {
            String arr = extractJsonArrayRaw(actionJson, "blocks");
            if (arr == null) {
                TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: no blocks payload");
                return;
            }
            List<String> ints = splitJsonArray(arr);
            if (ints.size() < total) {
                TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: blocks count < size");
                return;
            }
            blocks = new short[(int) total];
            for (int i = 0; i < total; i++) {
                try {
                    blocks[i] = (short) Integer.parseInt(ints.get(i).trim());
                } catch (NumberFormatException nfe) {
                    TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: non-integer block index");
                    return;
                }
            }
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        BlockPos origin = new BlockPos(ox, oy, oz);
        List<StructureValidator.BlockMismatch> mismatches = StructureValidator.compare(palette, blocks, sx, sy, sz, origin);

        if (mismatches.isEmpty()) {
            sendBridgeMessage("[Bridge] Repair: nothing to fix");
            TaskQueue.getInstance().completeActiveIf("#repair_structure");
            return;
        }

                sendBridgeMessage("[Bridge] Repair: fixing " + mismatches.size() + " mismatches...");
        int fixed = 0;
        for (StructureValidator.BlockMismatch m : mismatches) {
            if (TaskQueue.getInstance().isCancellationRequested()) return;
            if (!WorldInteractor.breakBlock(m.pos())) {
                sendBridgeMessage("[Bridge] Repair: failed to break " + m.pos().toShortString());
                continue;
            }
            sleep(200);

            String expectedBlock = m.expected();
            if (expectedBlock != null && !expectedBlock.equals("minecraft:air")) {
                BlockPos above = m.pos().up();
                WorldInteractor.placeBlock(above, Direction.DOWN);
                sleep(100);
            }

            fixed++;
        }

        sendBridgeMessage("[Bridge] Repair: fixed " + fixed + "/" + mismatches.size() + " blocks");
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (fixed > 0) {
            TaskQueue.getInstance().completeActiveIf("#repair_structure");
        } else {
            TaskQueue.getInstance().failActiveIf("#repair_structure", "repair_structure: unable to fix any blocks");
        }
    }

    // ─── Phase 2: Screen, Inventory, and Interaction Actions ─────────────────

    private static String executeSelectSlotAction(String actionJson) {
        String slotStr = extractJsonPrimitive(actionJson, "slot");
        if (slotStr == null) return "select_slot: missing slot";
        try {
            int slot = Integer.parseInt(slotStr);
            if (slot < 0 || slot > 8) return "select_slot: invalid slot " + slot;
            return InventoryDriver.selectSlot(slot) ? "select_slot: selected " + slot : "select_slot: failed";
        } catch (NumberFormatException e) {
            return "select_slot: invalid slot number";
        }
    }

    private static String executeEquipAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        String slotType = extractJsonString(actionJson, "slot");
        if (item == null || slotType == null) return "equip: missing item or slot";

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return "equip: not connected";

        String norm = ItemIds.normalize(item);
        int slot = -1;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty() && ItemIds.fromStack(inv.getStack(i)).equals(norm)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return "equip: item not found";
        final int fSlot = slot;

        String st = slotType.toLowerCase();
        if (st.contains("mainhand") || st.contains("hand")) {
            if (fSlot < 9) {
                inv.setSelectedSlot(fSlot);
            } else {
                int hotbar = findEmptyHotbarSlot(inv);
                if (hotbar < 0) return "equip: no hotbar space";
                boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                    if (client.interactionManager != null && client.player != null
                            && client.player.currentScreenHandler != null) {
                        client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId, fSlot, hotbar,
                            SlotActionType.SWAP, client.player);
                        return true;
                    }
                    return false;
                }));
                if (!ok) return "equip: failed to move to hotbar";
                inv.setSelectedSlot(hotbar);
            }
        } else if (st.contains("offhand")) {
            boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                if (client.interactionManager != null && client.player != null
                        && client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId, fSlot, 40,
                        SlotActionType.SWAP, client.player);
                    return true;
                }
                return false;
            }));
            if (!ok) return "equip: failed to equip to offhand";
        } else if (st.contains("head") || st.contains("helmet")) {
            boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                if (client.interactionManager != null && client.player != null
                        && client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId, fSlot, 5 + 36,
                        SlotActionType.SWAP, client.player);
                    return true;
                }
                return false;
            }));
            if (!ok) return "equip: failed to equip helmet";
        } else if (st.contains("chest") || st.contains("body")) {
            boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                if (client.interactionManager != null && client.player != null
                        && client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId, fSlot, 6 + 36,
                        SlotActionType.SWAP, client.player);
                    return true;
                }
                return false;
            }));
            if (!ok) return "equip: failed to equip chestplate";
        } else if (st.contains("legs") || st.contains("leggings")) {
            boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                if (client.interactionManager != null && client.player != null
                        && client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId, fSlot, 7 + 36,
                        SlotActionType.SWAP, client.player);
                    return true;
                }
                return false;
            }));
            if (!ok) return "equip: failed to equip leggings";
        } else if (st.contains("feet") || st.contains("boots")) {
            boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
                if (client.interactionManager != null && client.player != null
                        && client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId, fSlot, 8 + 36,
                        SlotActionType.SWAP, client.player);
                    return true;
                }
                return false;
            }));
            if (!ok) return "equip: failed to equip boots";
        } else {
            return "equip: unknown slot type " + slotType;
        }

        return "equip: equipped " + ItemIds.strip(norm) + " to " + slotType;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private static String executeEquipBestAction(String actionJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return "equip_best: not connected";
        boolean ok = InventoryDriver.equipBestWeapon(player);
        return ok ? "equip_best: equipped best weapon" : "equip_best: no weapon found";
    }

    private static String executeUseItemAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        if (item == null) return "use_item: missing item";

        WorkerThreads.start("mindcraft-useitem", () -> useItemWorker(item));
        return "queued";
    }

    private static void useItemWorker(String item) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            TaskQueue.getInstance().failActiveIf("#use_item", "use_item: not connected");
            return;
        }

        String norm = ItemIds.normalize(item);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(norm)) {
                player.getInventory().setSelectedSlot(i);
                break;
            }
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        ClientThread.run(() -> {
            if (client.interactionManager != null && client.player != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
        });

        sleep(500);
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#use_item");
    }

    private static String executeConsumeAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        if (item == null) return "consume: missing item";

        WorkerThreads.start("mindcraft-consume", () -> consumeWorker(item));
        return "queued";
    }

    private static void consumeWorker(String item) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            TaskQueue.getInstance().failActiveIf("#consume", "consume: not connected");
            return;
        }

        String norm = ItemIds.normalize(item);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(norm)) {
                player.getInventory().setSelectedSlot(i);
                break;
            }
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        ClientThread.run(() -> {
            if (client.interactionManager != null && client.player != null) {
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
        });

        for (int t = 0; t < 50; t++) {
            if (TaskQueue.getInstance().isCancellationRequested()) return;
            if (client.player == null) return;
            if (client.player.getItemUseTimeLeft() <= 0) break;
            sleep(100);
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#consume");
    }

    private static String executeDropItemsAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        if (item == null) return "drop_items: missing item";

        WorkerThreads.start("mindcraft-drop", () -> dropItemsWorker(actionJson));
        return "queued";
    }

    private static void dropItemsWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String item = extractJsonString(actionJson, "item");
        String countStr = extractJsonPrimitive(actionJson, "count");

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            TaskQueue.getInstance().failActiveIf("#drop_items", "drop_items: not connected");
            return;
        }

        String norm = ItemIds.normalize(item);
        int dropCount = 1;
        if (countStr != null) {
            try { dropCount = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
        }

        int dropped = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size() && dropped < dropCount; i++) {
            if (TaskQueue.getInstance().isCancellationRequested()) return;
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !ItemIds.fromStack(stack).equals(norm)) continue;
            int toDrop = Math.min(stack.getCount(), dropCount - dropped);
            for (int d = 0; d < toDrop; d++) {
                if (i < 9) {
                    inv.setSelectedSlot(i);
                    ClientThread.run(() -> player.dropSelectedItem(false));
                } else {
                    boolean ok = clickSlotSync(client, player, i, 1, SlotActionType.THROW);
                    if (!ok) break;
                }
                sleep(50);
            }
            dropped += toDrop;
        }

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        TaskQueue.getInstance().completeActiveIf("#drop_items");
    }

    private static boolean clickSlotSync(MinecraftClient client, ClientPlayerEntity player, int slot, int button, SlotActionType action) {
        boolean result = Boolean.TRUE.equals(ClientThread.call(() -> {
            if (client.interactionManager == null || player.currentScreenHandler == null) return false;
            client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot, button, action, player);
            return true;
        }));
        sleep(50); // Allow server to process
        return result;
    }

    private static String executePickupItemsAction(String actionJson) {
        return "pickup_items: #explore";
    }

    private static String executeOpenBlockAction(String actionJson) {
        String xStr = extractJsonPrimitive(actionJson, "x");
        String yStr = extractJsonPrimitive(actionJson, "y");
        String zStr = extractJsonPrimitive(actionJson, "z");
        if (xStr == null || yStr == null || zStr == null) return "open_block: missing coordinates";
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(xStr), Integer.parseInt(yStr), Integer.parseInt(zStr));
            return WorldInteractor.openBlock(pos) ? "open_block: opened " + pos.toShortString() : "open_block: failed";
        } catch (NumberFormatException e) {
            return "open_block: invalid coordinates";
        }
    }

    private static String executeCloseScreenAction() {
        ScreenDriver.closeScreen();
        return "close_screen: closed";
    }

    private static String executeTransferItemsAction(String actionJson) {
        String item = extractJsonString(actionJson, "item");
        String countStr = extractJsonPrimitive(actionJson, "count");
        String fromSlotStr = extractJsonPrimitive(actionJson, "from_slot");
        String toSlotStr = extractJsonPrimitive(actionJson, "to_slot");
        if (item == null || countStr == null || fromSlotStr == null || toSlotStr == null) {
            return "transfer_items: missing parameters";
        }

        WorkerThreads.start("mindcraft-transfer", () -> transferWorker(actionJson));
        return "queued";
    }

    private static void transferWorker(String actionJson) {
        if (TaskQueue.getInstance().isCancellationRequested()) return;
        String item = extractJsonString(actionJson, "item");
        String countStr = extractJsonPrimitive(actionJson, "count");
        String fromSlotStr = extractJsonPrimitive(actionJson, "from_slot");
        String toSlotStr = extractJsonPrimitive(actionJson, "to_slot");
        String direction = extractJsonString(actionJson, "direction");

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            TaskQueue.getInstance().failActiveIf("#transfer_items", "transfer_items: not connected");
            return;
        }

        int count = 1;
        try { count = Integer.parseInt(countStr); } catch (NumberFormatException ignored) {}
        int fromSlot, toSlot;
        try {
            fromSlot = Integer.parseInt(fromSlotStr);
            toSlot = Integer.parseInt(toSlotStr);
        } catch (NumberFormatException e) {
            TaskQueue.getInstance().failActiveIf("#transfer_items", "transfer_items: invalid slots");
            return;
        }

        boolean toContainer = direction != null && direction.equals("to_container");

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        boolean ok = ClientThread.call(() -> {
            if (client.interactionManager == null || player.currentScreenHandler == null) return false;
            ScreenHandler screen = player.currentScreenHandler;
            int srcSlot = toContainer ? fromSlot : toSlot;
            int dstSlot = toContainer ? toSlot : fromSlot;
            client.interactionManager.clickSlot(screen.syncId, srcSlot, 0, SlotActionType.PICKUP, player);
            sleep(50);
            client.interactionManager.clickSlot(screen.syncId, dstSlot, 0, SlotActionType.PICKUP, player);
            sleep(50);
            client.interactionManager.clickSlot(screen.syncId, srcSlot, 0, SlotActionType.PICKUP, player);
            sleep(50);
            return true;
        });

        if (TaskQueue.getInstance().isCancellationRequested()) return;
        if (ok) {
            TaskQueue.getInstance().completeActiveIf("#transfer_items");
        } else {
            TaskQueue.getInstance().failActiveIf("#transfer_items", "transfer_items: failed to transfer");
        }
    }

    private static String executeScreenClickSlotAction(String actionJson) {
        int slot = Integer.parseInt(extractJsonPrimitive(actionJson, "slot"));
        int button = optionalInt(actionJson, "button", 0);
        SlotActionType actionType = parseSlotActionType(extractJsonString(actionJson, "action"));
        if (actionType == null) actionType = SlotActionType.PICKUP;
        Integer expectedSyncId = optionalInt(actionJson, "sync_id", null);
        String preflight = ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (client.currentScreen == null || player == null || player.currentScreenHandler == null) return "screen_not_open";
            ScreenHandler handler = player.currentScreenHandler;
            if (expectedSyncId != null && handler.syncId != expectedSyncId) return "sync_id_mismatch";
            if (slot < 0 || slot >= handler.slots.size()) return "invalid_slot";
            return "ok";
        });
        if (!"ok".equals(preflight)) return "screen_click_slot: " + (preflight == null ? "transfer_failed" : preflight);
        boolean ok = ScreenDriver.click(slot, button, actionType);
        return ok ? "screen_click_slot: clicked " + slot : "screen_click_slot: transfer_failed";
    }

    private static String executeContainerQuickMoveAction(String actionJson) {
        int slot = Integer.parseInt(extractJsonPrimitive(actionJson, "slot"));
        boolean ok = ScreenDriver.quickMove(slot);
        return ok ? "container_quick_move: moved " + slot : "container_quick_move: failed";
    }

    private static String executeContainerMoveAction(String actionJson, boolean deposit) {
        String slotStr = extractJsonPrimitive(actionJson, "slot");
        String item = extractJsonString(actionJson, "item");
        int count = Math.max(1, optionalInt(actionJson, "count", 1));
        Integer expectedSyncId = optionalInt(actionJson, "sync_id", null);
        Integer requestedSlot = parseIntSafe(slotStr);
        String norm = item == null ? null : ItemIds.normalize(item);
        String result = ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null || player.currentScreenHandler == null) return "screen_not_open";
            ScreenHandler handler = player.currentScreenHandler;
            if (expectedSyncId != null && handler.syncId != expectedSyncId) return "sync_id_mismatch";
            if (requestedSlot != null && requestedSlot >= 0) {
                if (requestedSlot >= handler.slots.size()) return "invalid_slot";
                Slot requested = handler.slots.get(requestedSlot);
                if (isPlayerSlot(requested, player) != deposit) return "invalid_slot";
            }
            if (norm == null) return "item_not_found";
            int sourceSlot = -1;
            for (int i = 0; i < handler.slots.size(); i++) {
                var slotObj = handler.slots.get(i);
                boolean playerSlot = isPlayerSlot(slotObj, player);
                if (deposit != playerSlot) continue;
                if (requestedSlot != null && requestedSlot >= 0 && requestedSlot != i) continue;
                ItemStack stack = slotObj.getStack();
                if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(norm) && stack.getCount() >= count) {
                    sourceSlot = i;
                    break;
                }
            }
            if (sourceSlot < 0) return "item_not_found";

            ItemStack sourceStack = handler.slots.get(sourceSlot).getStack();
            int destinationSlot = -1;
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slotObj = handler.slots.get(i);
                if (isPlayerSlot(slotObj, player) == deposit) continue;
                ItemStack stack = slotObj.getStack();
                if (stack.isEmpty()) {
                    if (count <= sourceStack.getMaxCount()) {
                        destinationSlot = i;
                        break;
                    }
                    continue;
                }
                if (ItemIds.fromStack(stack).equals(norm) && stack.getCount() + count <= stack.getMaxCount()) {
                    destinationSlot = i;
                    break;
                }
            }
            if (destinationSlot < 0) return "insufficient_space";

            int beforeSource = sourceStack.getCount();
            ItemStack beforeDest = handler.slots.get(destinationSlot).getStack().copy();
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
            sleep(30);
            for (int i = 0; i < count; i++) {
                client.interactionManager.clickSlot(handler.syncId, destinationSlot, 1, SlotActionType.PICKUP, player);
                sleep(15);
            }
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
            sleep(80);

            ItemStack afterSource = handler.slots.get(sourceSlot).getStack();
            ItemStack afterDest = handler.slots.get(destinationSlot).getStack();
            int expectedSource = beforeSource - count;
            int expectedDest = beforeDest.isEmpty() ? count : beforeDest.getCount() + count;
            boolean sourceOk = expectedSource == 0 ? afterSource.isEmpty() : (!afterSource.isEmpty() && ItemIds.fromStack(afterSource).equals(norm) && afterSource.getCount() == expectedSource);
            boolean destOk = !afterDest.isEmpty() && ItemIds.fromStack(afterDest).equals(norm) && afterDest.getCount() == expectedDest;
            return sourceOk && destOk ? "ok:" + sourceSlot + "->" + destinationSlot + ":" + count : "transfer_failed";
        });
        if (result == null) result = "transfer_failed";
        if (result.startsWith("ok:")) {
            return (deposit ? "container_deposit: moved " : "container_withdraw: moved ") + result.substring(3);
        }
        return (deposit ? "container_deposit: " : "container_withdraw: ") + result;
    }

    private static boolean isPlayerSlot(Slot slot, ClientPlayerEntity player) {
        return slot != null && player != null && slot.inventory == player.getInventory();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String executeLookAction(String actionJson) {
        float yaw = normalizeYaw(Float.parseFloat(extractJsonPrimitive(actionJson, "yaw")));
        float pitch = clampPitch(Float.parseFloat(extractJsonPrimitive(actionJson, "pitch")));
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null) return false;
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setPitch(pitch);
            return true;
        }));
        return ok ? "look: set" : "look: not connected";
    }

    private static String executeLookAtAction(String actionJson) {
        Integer entityId = optionalInt(actionJson, "entity_id", null);
        Double x = optionalDouble(actionJson, "x", null);
        Double y = optionalDouble(actionJson, "y", null);
        Double z = optionalDouble(actionJson, "z", null);
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) return false;
            if (entityId != null) {
                Entity entity = client.world.getEntityById(entityId);
                if (entity == null) return false;
                lookAtEntity(player, entity);
                return true;
            }
            if (x == null || y == null || z == null) return false;
            lookAtPosition(player, new Vec3d(x, y, z));
            return true;
        }));
        return ok ? "look_at: set" : "look_at: target unavailable";
    }

    private static String executePressKeyAction(String actionJson) {
        String keyName = extractJsonString(actionJson, "key");
        boolean pressed = Boolean.TRUE.equals(optionalBoolean(actionJson, "pressed", true));
        Integer parsedDuration = optionalInt(actionJson, "duration_ms", null);
        int durationMs = parsedDuration != null ? parsedDuration : (pressed ? 80 : 0);
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            KeyBinding key = resolveKey(client, keyName);
            if (key == null) return false;
            key.setPressed(pressed);
            return true;
        }));
        if (!ok) return "press_key: unavailable";
        if (pressed && durationMs > 0) {
            sleep(Math.min(durationMs, 5000));
            ClientThread.run(() -> {
                KeyBinding key = resolveKey(MinecraftClient.getInstance(), keyName);
                if (key != null) key.setPressed(false);
            });
        }
        return "press_key: " + (pressed ? "pressed " : "released ") + resolveKeyName(keyName);
    }

    private static String executeSwingAction(String actionJson) {
        Hand hand = parseHand(extractJsonString(actionJson, "hand"));
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return false;
            client.player.swingHand(hand);
            return true;
        }));
        return ok ? "swing: swung" : "swing: not connected";
    }

    private static String executeAttackEntityAction(String actionJson) {
        int entityId = Integer.parseInt(extractJsonPrimitive(actionJson, "entity_id"));
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null || client.interactionManager == null) return false;
            Entity entity = client.world.getEntityById(entityId);
            if (entity == null) return false;
            lookAtEntity(player, entity);
            client.interactionManager.attackEntity(player, entity);
            player.swingHand(Hand.MAIN_HAND);
            return true;
        }));
        return ok ? "attack_entity: attacked" : "attack_entity: target unavailable";
    }

    private static String executeUseItemOnBlockAction(String actionJson) {
        try {
            int x = Integer.parseInt(extractJsonPrimitive(actionJson, "x"));
            int y = Integer.parseInt(extractJsonPrimitive(actionJson, "y"));
            int z = Integer.parseInt(extractJsonPrimitive(actionJson, "z"));
            Direction direction = parseDirection(firstNonBlank(extractJsonString(actionJson, "face"), extractJsonString(actionJson, "direction")));
            if (direction == null) direction = Direction.UP;
            Hand hand = parseHand(extractJsonString(actionJson, "hand"));
            BlockPos pos = new BlockPos(x, y, z);
            return WorldInteractor.interactBlock(pos, direction, hand) ? "use_item_on_block: used" : "use_item_on_block: failed";
        } catch (NumberFormatException e) {
            return "use_item_on_block: invalid coordinates";
        }
    }

    private static String executeUseItemOnEntityAction(String actionJson) {
        int entityId = Integer.parseInt(extractJsonPrimitive(actionJson, "entity_id"));
        Hand hand = parseHand(extractJsonString(actionJson, "hand"));
        return WorldInteractor.interactEntity(entityId, hand) ? "use_item_on_entity: used" : "use_item_on_entity: target unavailable";
    }

    private static String executeHoldUseItemAction(String actionJson) {
        int durationMs = optionalInt(actionJson, "duration_ms", 500);
        Hand hand = parseHand(extractJsonString(actionJson, "hand"));
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) return false;
            client.options.useKey.setPressed(true);
            client.interactionManager.interactItem(client.player, hand);
            return true;
        }));
        if (!ok) return "hold_use_item: not connected";
        sleep(Math.min(durationMs, 5000));
        ClientThread.run(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.options.useKey.setPressed(false);
            if (client.player != null) client.player.stopUsingItem();
        });
        return "hold_use_item: held";
    }

    private static String executeInteractBlockAction(String actionJson) {
        String xStr = extractJsonPrimitive(actionJson, "x");
        String yStr = extractJsonPrimitive(actionJson, "y");
        String zStr = extractJsonPrimitive(actionJson, "z");
        String direction = extractJsonString(actionJson, "direction");
        if (xStr == null || yStr == null || zStr == null) return "interact_block: missing coordinates";
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(xStr), Integer.parseInt(yStr), Integer.parseInt(zStr));
            Direction dir = direction != null ? Direction.valueOf(direction.toUpperCase()) : Direction.UP;
            return WorldInteractor.interactBlock(pos, dir) ? "interact_block: interacted" : "interact_block: failed";
        } catch (IllegalArgumentException e) {
            return "interact_block: invalid parameters";
        }
    }

    private static String executeInteractEntityAction(String actionJson) {
        String entityIdStr = extractJsonPrimitive(actionJson, "entity_id");
        if (entityIdStr == null) return "interact_entity: missing entity_id";
        try {
            int entityId = Integer.parseInt(entityIdStr);
            return WorldInteractor.interactEntity(entityId) ? "interact_entity: interacted" : "interact_entity: failed";
        } catch (NumberFormatException e) {
            return "interact_entity: invalid entity_id";
        }
    }

    public static String capabilitiesJson() {
        return BridgeActionRegistry.capabilitiesJson();
    }

    public static String discoverCommandsJson() {
        return "["
            + "\"#goto x y z\","
            + "\"#craft\","
            + "\"#sleep\","
            + "\"#mine count block\","
            + "\"#follow player <name>\","
            + "\"#cancel\","
            + "\"attack <target_type>\","
            + "\"sleep_try\","
            + "\"build_schematic <payload>\","
            + "\"cancel_build\","
            + "\"chat: <message>\""
            + "]";
    }

    private static void sendChat(MinecraftClient client, String message) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        StateCollector.trackSentChat(message);
        ClientThread.run(() -> {
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

    private static boolean dispatchBaritoneChatCommand(String command) {
        try {
            boolean allowed = ClientSendMessageEvents.ALLOW_CHAT.invoker().allowSendChatMessage(command);
            if (!allowed) {
                ClientSendMessageEvents.CHAT_CANCELED.invoker().onSendChatMessageCanceled(command);
                return true;
            }
            return false;
        } catch (Throwable t) {
            System.err.println("[Mindcraft Bridge] Failed to dispatch baritone command via chat hook: " + t.getMessage());
            return false;
        }
    }

    // Expands a mine target name to include all ore variant blocks.
    // If the target matches an entry in MINE_TARGET_ALIASES, returns the
    // space-joined alias list so the #mine command covers stone + deepslate
    // (and nether for gold). Otherwise returns the original target unchanged.
    private static String expandMineTarget(String target) {
        if (target == null) return null;
        List<String> aliases = MINE_TARGET_ALIASES.get(target);
        if (aliases != null) {
            return String.join(" ", aliases);
        }
        return target;
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
                String mode = extractJsonString(json, "mode");
                if (target == null) return null;
                String effectiveCount = count != null ? count : "64";
                if (mode == null || !mode.equalsIgnoreCase("ensure_inventory")) {
                    effectiveCount = additionalMineTargetCount(target, effectiveCount);
                }
                StringBuilder sb = new StringBuilder("#mine ");
                sb.append(effectiveCount).append(" ").append(expandMineTarget(target));
                if (secondary != null) sb.append(" ").append(secondary);
                return sb.toString();
            }
            case "follow": {
                String target = extractJsonString(json, "target");
                if (target != null) return "#follow player " + target;
                return null;
            }
            case "cancel": return "#cancel";
            default: return null;
        }
    }

    // â”€â”€â”€ JSON extraction helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static String additionalMineTargetCount(String target, String requestedCount) {
        int requested = 64;
        try {
            requested = Math.max(1, Integer.parseInt(requestedCount));
        } catch (NumberFormatException ignored) {}
        return String.valueOf(countInventoryForMineTarget(target) + requested);
    }

    private static int countInventoryForMineTarget(String target) {
        String normalized = ItemIds.normalize(target);
        try {
            return ClientThread.call(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                ClientPlayerEntity player = client == null ? null : client.player;
                if (player == null) return 0;
                int total = 0;
                PlayerInventory inv = player.getInventory();
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(normalized)) {
                        total += stack.getCount();
                    }
                }
                return total;
            });
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static String normalizeRawBaritoneCommand(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        if (trimmed.equalsIgnoreCase("#sleep")
                || trimmed.equalsIgnoreCase("sleep")
                || trimmed.equalsIgnoreCase("#task sleep")) {
            return "#sleep";
        }
        return trimmed;
    }

    public static String extractJsonString(String json, String key) {
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

    public static String extractJsonPrimitive(String json, String key) {
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

    public static String extractJsonObject(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki < 0) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('{', colon + 1);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    public static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public static boolean isHostile(net.minecraft.entity.Entity e) {
        String type = e.getType().toString().toLowerCase();
        return type.contains("zombie") || type.contains("skeleton") || type.contains("creeper")
                || type.contains("spider") || type.contains("enderman") || type.contains("witch")
                || type.contains("phantom") || type.contains("slime") || type.contains("drowned")
                || type.contains("husk") || type.contains("stray") || type.contains("pillager")
                || type.contains("vindicator") || type.contains("evoker") || type.contains("ravager")
                || type.contains("vex") || type.contains("silverfish") || type.contains("blaze")
                || type.contains("ghast") || type.contains("magma_cube") || type.contains("piglin")
                || type.contains("hoglin") || type.contains("zoglin") || type.contains("wither_skeleton")
                || type.contains("guardian") || type.contains("elder_guardian");
    }

    /**
     * Read block identifier strings for a box (x..x+w-1, y..y+h-1, z..z+l-1)
     * in XZY order. Returns a JSON object:
     *   {"origin":[x,y,z],"size":[w,h,l],"blocks":["minecraft:air",...]}
     * or {@code null} when the client isn't connected.
     */
    public static String readBlocksInBox(int x, int y, int z, int w, int h, int l) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            return readBlocksInBoxOnClientThread(x, y, z, w, h, l);
        }
        return ClientThread.call(() -> readBlocksInBoxOnClientThread(x, y, z, w, h, l));
    }

    private static String readBlocksInBoxOnClientThread(int x, int y, int z, int w, int h, int l) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return null;
        ClientWorld world = client.world;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"origin\":[").append(x).append(",").append(y).append(",").append(z).append("],");
        sb.append("\"size\":[").append(w).append(",").append(h).append(",").append(l).append("],");
        sb.append("\"blocks\":[");

        BlockPos.Mutable pos = new BlockPos.Mutable();
        boolean first = true;
        for (int yy = 0; yy < h; yy++) {
            for (int zz = 0; zz < l; zz++) {
                for (int xx = 0; xx < w; xx++) {
                    pos.set(x + xx, y + yy, z + zz);
                    String id;
                    try {
                        BlockState state = world.getBlockState(pos);
                        id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
                    } catch (Throwable t) {
                        id = "minecraft:air";
                    }
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(id).append("\"");
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
