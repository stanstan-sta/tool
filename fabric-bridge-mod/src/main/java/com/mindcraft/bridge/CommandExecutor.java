package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
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
import net.minecraft.text.Text;
import net.minecraft.world.World;

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
        if ("flee".equals(type)) {
            return executeFleeAction(actionJson);
        }
        if ("sleep_try".equals(type)) {
            return executeSleepTryAction(actionJson);
        }
        if ("attack".equals(type)) {
            return executeAttackAction(actionJson);
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

    private static String executeFleeAction(String actionJson) {
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
    }

    private static String executeSleepTryAction(String actionJson) {
        Thread worker = new Thread(() -> sleepTryWorker(), "mindcraft-sleep-try");
        worker.setDaemon(true);
        worker.start();
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
        if (client.player != null && client.world != null) {
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

    private static String waitForActiveTaskComplete(long timeoutMs) {
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
            ClientPlayerEntity player = client.player;
            if (player == null) return null;
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object worldProvider = baritone.getClass().getMethod("getWorldProvider").invoke(baritone);
            Object world = worldProvider.getClass().getMethod("getCurrentWorld").invoke(worldProvider);
            Object cache = world.getClass().getMethod("getCachedWorld").invoke(world);
            int cx = player.getBlockPos().getX() >> 4;
            int cz = player.getBlockPos().getZ() >> 4;
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
        Thread worker = new Thread(() -> combatWorker(actionJson), "mindcraft-combat");
        worker.setDaemon(true);
        worker.start();
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
        Vec3d lastTargetPos = null;
        long retargetTimer = 0;

        boolean hadWeapon = equipBestWeapon(player);
        if (!hadWeapon) {
            sendBridgeMessage("[Bridge] Combat: no weapon in hotbar — punching with fist");
        }

        while (state != CombatState.DONE && totalAttempts < maxAttempts) {
            player = client.player;
            if (player == null || client.world == null) {
                sendBridgeMessage("[Bridge] Combat: not connected");
                return;
            }

            if (player.isDead() || player.getHealth() <= 0) {
                sendBridgeMessage("[Bridge] Combat: player died");
                return;
            }
            if (player.getHealth() <= retreatHp) {
                sendBridgeMessage("[Bridge] Combat: retreating — HP too low");
                return;
            }

            switch (state) {
                case SEARCHING: {
                    if (useUntilItems) {
                        boolean allMet = true;
                        for (Map.Entry<String, Integer> entry : untilItems.entrySet()) {
                            int have = countItemInInventory(player, normalizeItemId(entry.getKey()));
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

                    sendBridgeMessage("[Bridge] Hunt: searching via #explore ...");
                    TaskQueue.getInstance().cancelAll();
                    sleepQuietly(150);
                    TaskQueue.getInstance().enqueue("#explore");

                    long exploreDeadline = System.currentTimeMillis() + 8000;
                    boolean found = false;
                    while (System.currentTimeMillis() < exploreDeadline) {
                        player = client.player;
                        if (player == null || client.world == null) return;
                        if (player.isDead() || player.getHealth() <= 0) {
                            sendBridgeMessage("[Bridge] Combat: player died");
                            return;
                        }
                        if (player.getHealth() <= retreatHp) {
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

                    String followCmd = "#follow entity " + currentTarget.getId();
                    TaskQueue.QueueState qs = TaskQueue.getInstance().getQueueState();
                    String active = qs.active();
                    if (active == null || !active.equals(followCmd)) {
                        TaskQueue.getInstance().cancelAll();
                        sleepQuietly(150);
                        TaskQueue.getInstance().enqueue(followCmd);
                    }

                    sleepQuietly(500);
                    continue;
                }

                case ENGAGING: {
                    if (currentTarget == null || currentTarget.isRemoved() || !currentTarget.isAlive()) {
                        sendBridgeMessage("[Bridge] Hunt: target killed");
                        totalAttempts++;
                        if (!useUntilItems) {
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
                        state = CombatState.APPROACHING;
                        continue;
                    }

                    if (System.currentTimeMillis() - retargetTimer >= 2000) {
                        retargetTimer = System.currentTimeMillis();
                        Entity better = findBetterTarget(client, player, currentTarget, targetTypeRaw, maxDistance);
                        if (better != null && better != currentTarget) {
                            currentTarget = better;
                        }
                    }

                    final ClientPlayerEntity fp = player;
                    final Entity t = currentTarget;
                    client.execute(() -> {
                        lookAtEntity(fp, t);
                        if (client.interactionManager != null) {
                            client.interactionManager.attackEntity(fp, t);
                            fp.swingHand(Hand.MAIN_HAND);
                        }
                    });

                    int cooldownMs = getAttackCooldownMs(player);
                    sleepQuietly(cooldownMs);
                    continue;
                }

                case LOOTING: {
                    sendBridgeMessage("[Bridge] Hunt: looting drops ...");
                    sleepQuietly(1500);

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
                                int have = countItemInInventory(player, normalizeItemId(entry.getKey()));
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

    private static Entity findNearestHostileOfType(MinecraftClient client, ClientPlayerEntity player, String targetType, double maxDistance) {
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

    private static String normalizeEntityTypeName(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase();
        // Strip common prefixes/suffixes: "entity.minecraft.zombie" -> "zombie", "minecraft:zombie" -> "zombie"
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s;
    }

    private static boolean equipBestWeapon(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int bestSlot = -1;
        float bestDamage = -1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String itemId = stack.getItem().toString();
            if (itemId.startsWith("Item{") && itemId.endsWith("}")) {
                itemId = itemId.substring(5, itemId.length() - 1);
            }
            String name = itemId.toLowerCase();
            float damage = getMeleeDamage(name);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            inv.setSelectedSlot(bestSlot);
            sendBridgeMessage("[Bridge] Combat: selected slot " + bestSlot + " (damage " + bestDamage + ")");
            return true;
        }
        return false;
    }

    private static int getAttackCooldownMs(ClientPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) return 250; // fist: 4.0 attack speed
        String itemId = held.getItem().toString();
        if (itemId.startsWith("Item{") && itemId.endsWith("}")) {
            itemId = itemId.substring(5, itemId.length() - 1);
        }
        String name = itemId.toLowerCase();
        // 1.21 attack speeds: sword=1.6 (625ms), axe=1.0 (1000ms), pick=1.2 (833ms), shovel=1.0 (1000ms), fist=4.0 (250ms)
        if (name.contains("sword")) return 625;
        if (name.contains("axe")) return 1000;
        if (name.contains("pickaxe")) return 833;
        if (name.contains("shovel")) return 1000;
        return 250;
    }

    private static float getMeleeDamage(String itemName) {
        if (itemName.contains("netherite_sword")) return 8.0f;
        if (itemName.contains("diamond_sword")) return 7.0f;
        if (itemName.contains("iron_sword")) return 6.0f;
        if (itemName.contains("stone_sword")) return 5.0f;
        if (itemName.contains("wooden_sword")) return 4.0f;
        if (itemName.contains("golden_sword")) return 4.0f;
        if (itemName.contains("netherite_axe")) return 10.0f;
        if (itemName.contains("diamond_axe")) return 9.0f;
        if (itemName.contains("iron_axe")) return 9.0f;
        if (itemName.contains("stone_axe")) return 9.0f;
        if (itemName.contains("wooden_axe")) return 7.0f;
        if (itemName.contains("golden_axe")) return 7.0f;
        if (itemName.contains("pickaxe")) return 3.0f;
        if (itemName.contains("shovel")) return 2.5f;
        return 1.0f; // fist / miscellaneous
    }

    private static void lookAtEntity(ClientPlayerEntity player, Entity target) {
        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getHeight() * 0.5) - player.getEyeY();
        double dz = target.getZ() - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(Math.atan2(-dy, distXZ)));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

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

        // ── Phase 1: seed the plan on the render thread (fast — inventory + cheap scans)
        java.util.concurrent.CompletableFuture<Object> seedFuture = new java.util.concurrent.CompletableFuture<>();
        client.execute(() -> {
            try {
                ClientPlayerEntity player = client.player;
                if (player == null || client.world == null) {
                    seedFuture.complete("craft: not connected");
                    return;
                }

                Identifier itemId = targetItem.contains(":")
                        ? Identifier.tryParse(targetItem)
                        : Identifier.of("minecraft", targetItem);
                if (itemId == null) {
                    seedFuture.complete("craft: invalid item " + targetItem);
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

                MakePlan seed = new MakePlan();
                snapshotInventory(player, seed);
                // Narrow probe only — full world scans were stalling the render thread.
                if (findNearestStationNear(client.world, player, "crafting_table") != null) {
                    seed.nearbyStations.add("crafting_table");
                }
                if (findNearestStationNear(client.world, player, "furnace") != null) {
                    seed.nearbyStations.add("furnace");
                }

                seedFuture.complete(new Object[] { seed, itemKey });
            } catch (Exception e) {
                seedFuture.complete("craft: error — " + e.getMessage());
            }
        });

        MakePlan plan;
        String planKey;
        try {
            Object result = seedFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (result instanceof String str) {
                return str;
            }
            Object[] pair = (Object[]) result;
            plan = (MakePlan) pair[0];
            planKey = (String) pair[1];
        } catch (Exception e) {
            return "craft: timeout seeding plan — " + e.getMessage();
        }

        // ── Phase 2: recursive planning on the HTTP thread (no render-thread work)
        try {
            makeItem(plan, normalizeItemId(planKey), targetCount, 0);
        } catch (Exception e) {
            return "craft: error — " + e.getMessage();
        }
        if (!plan.isSuccess()) {
            return "craft: could not plan " + targetItem + " - "
                    + String.join("; ", plan.errors);
        }

        // ── Phase 3: enqueue steps and post the plan summary back to the client
        final MakePlan finalPlan = plan;
        final String summary = describeMakePlan(plan.steps);
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.literal(
                        "[Bridge] Queued make plan: " + summary), false);
            }
            for (MakeStep step : finalPlan.steps) {
                enqueueMakeStep(step);
            }
        });
        return "craft: queued " + plan.steps.size() + " step(s); " + summary;
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

        boolean hasItem(String itemId) {
            String norm = normalizeItemId(itemId);
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
            reservedTools.add(normalizeItemId(toolId));
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
        // Hard cap is only a safety net for runaway recursion. Real cycles are caught
        // by plan.inProgress below. Diamond's full chain (6+ tiers × ~4 frames each)
        // fits comfortably under 32.
        if (depth > 32) {
            plan.errors.add("recipe chain too deep at " + stripMinecraftNamespace(itemId));
            return false;
        }

        String normalized = normalizeItemId(itemId);

        // Cycle detection: if we're already trying to plan this exact item further up
        // the stack, we have a real dependency loop (e.g. A needs B, B needs A).
        if (!plan.inProgress.add(normalized)) {
            plan.errors.add("recipe cycle detected at " + stripMinecraftNamespace(normalized));
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
            ensureStation(plan, "crafting_table");
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
            if (fuelNeeded > 0) {
                if (!makeItem(plan, "minecraft:coal", fuelNeeded, depth + 1)) {
                    return false;
                }
                plan.consumeItem("minecraft:coal", fuelNeeded);
            }
            ensureStation(plan, "furnace");
            String inputKey = stripMinecraftNamespace(smelt.input());
            plan.steps.add(new MakeStep(MakeStepKind.SMELT, inputKey, missing,
                    "#task smelt " + inputKey + " " + missing));
            plan.addVirtual(normalized, missing);
            plan.consumeItem(normalized, missing);
            return true;
        }

        GatherProvider gather = GATHER_PROVIDERS.get(normalized);
        if (gather != null) {
            String requiredTool = MINE_TOOL_REQUIREMENTS.get(gather.mineTarget());
            if (requiredTool != null && !plan.hasEquivalentOrBetterTool(requiredTool)) {
                if (!makeItem(plan, requiredTool, 1, depth + 1)) {
                    plan.errors.add("Need " + requiredTool + " to mine " + gather.mineTarget()
                            + " but couldn't plan one");
                    return false;
                }
                plan.reserveTool(requiredTool);
            }
            boolean inNether = playerIsInNether();
            boolean isNetherTarget = isNetherGatherTarget(gather.mineTarget());
            boolean needsEnterPortal = isNetherTarget && !inNether;
            boolean needsExitPortal = !isNetherTarget && inNether;
            if (needsEnterPortal) {
                plan.steps.add(new MakeStep(MakeStepKind.MINE, "nether_portal", 1, "#goto nether_portal"));
            }
            if (needsExitPortal) {
                plan.steps.add(new MakeStep(MakeStepKind.MINE, "overworld_portal", 1, "#goto nether_portal"));
            }
            plan.steps.add(new MakeStep(MakeStepKind.MINE, stripMinecraftNamespace(gather.producedItem()), missing,
                    "#mine " + missing + " " + gather.mineTarget()));
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
        // Phase 4: crop / ore gather providers
        addGather(providers, "wheat", "wheat");
        addGather(providers, "carrot", "carrot");
        addGather(providers, "pumpkin", "pumpkin");
        addGather(providers, "cocoa_beans", "cocoa_beans");
        addGather(providers, "sugar_cane", "sugar_cane");
        addGather(providers, "nether_quartz", "nether_quartz_ore", "nether_quartz");
        addGather(providers, "obsidian", "obsidian");
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

    // Maps ore-drop items to their source ore blocks for inventory checking
    private static final Map<String, List<String>> ORE_BLOCK_ALIASES = Map.of(
            "minecraft:raw_iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            "minecraft:raw_gold", List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            "minecraft:raw_copper", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")
    );

    // Minimum tool needed to successfully #mine this block.
    // Missing entries => no tool required (wood, sand, dirt, crops, etc.)
    private static final Map<String, String> MINE_TOOL_REQUIREMENTS = Map.ofEntries(
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

    private static final List<String> PICKAXE_TIERS = List.of(
        "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
        "diamond_pickaxe", "netherite_pickaxe"
    );

    // Nether-only gather targets that require portal travel
    private static final Set<String> NETHER_GATHER_TARGETS = Set.of(
            "ancient_debris", "netherrack", "nether_quartz_ore", "glowstone",
            "soul_sand", "magma_block", "nether_gold_ore", "blackstone",
            "basalt", "crimson_stem", "warped_stem"
    );

    private static boolean isNetherGatherTarget(String mineTarget) {
        return NETHER_GATHER_TARGETS.contains(mineTarget);
    }

    private static boolean playerIsInNether() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.world != null && client.world.getRegistryKey() == World.NETHER;
    }

    private static boolean playerIsInEnd() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.world != null && client.world.getRegistryKey() == World.END;
    }

    /**
     * Narrow, render-thread-only scan for a nearby station (crafting_table / furnace).
     * XZ range 16, Y range ±6 — ~4.5k getBlockState calls, typically <30 ms.
     * Caller MUST already be on the Minecraft client thread.
     */
    private static BlockPos findNearestStationNear(ClientWorld world, ClientPlayerEntity player, String station) {
        if (world == null || player == null) return null;
        String blockId = "minecraft:" + station;
        BlockPos origin = player.getBlockPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    net.minecraft.block.BlockState state = world.getBlockState(m);
                    if (state.isAir()) continue;
                    if (getBlockId(state.getBlock()).equals(blockId)) {
                        double d = m.getSquaredDistance(origin);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = m.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

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
            + "\"action_types\":[\"move\",\"mine\",\"follow\",\"cancel\",\"raw_command\",\"craft\",\"attack\"],"
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
            + "\"attack <target_type>\","
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

    private static String extractJsonObject(String json, String key) {
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

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static boolean isHostile(net.minecraft.entity.Entity e) {
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
}
