package com.mindcraft.bridge.workers.combat;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class CombatWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");

    private enum CombatState { SEARCHING, APPROACHING, ENGAGING, LOOTING, DONE }

    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#combat";
        try {
            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                CommandExecutor.sendBridgeMessage("[Bridge] Combat: not connected");
                return WorkerResult.failure("combat: not connected");
            }
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = ClientThread.call(() -> MinecraftClient.getInstance().player);

            String targetTypeRaw = CommandExecutor.extractJsonString(actionJson, "target_type");
            String maxDistStr = CommandExecutor.extractJsonPrimitive(actionJson, "max_distance");
            String retreatHpStr = CommandExecutor.extractJsonPrimitive(actionJson, "retreat_hp");
            String countStr = CommandExecutor.extractJsonPrimitive(actionJson, "count");
            String searchTimeStr = CommandExecutor.extractJsonPrimitive(actionJson, "search_time_s");
            String untilItemsJson = CommandExecutor.extractJsonObject(actionJson, "until_items");

            final double maxDistance;
            if (maxDistStr != null) {
                double parsed;
                try { parsed = Double.parseDouble(maxDistStr); } catch (NumberFormatException ignored) { parsed = 48.0; }
                maxDistance = parsed;
            } else {
                maxDistance = 48.0;
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

            boolean hadWeapon = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && InventoryDriver.equipBestWeapon(c.player);
            });
            if (!hadWeapon) {
                CommandExecutor.sendBridgeMessage("[Bridge] Combat: no weapon in hotbar — punching with fist");
            }

            while (state != CombatState.DONE && totalAttempts < maxAttempts) {
                player = ClientThread.call(() -> MinecraftClient.getInstance().player);
                boolean stillConnected = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return c.player != null && c.world != null;
                });
                if (!stillConnected) {
                    CommandExecutor.sendBridgeMessage("[Bridge] Combat: not connected");
                    return WorkerResult.failure("combat: not connected");
                }

                boolean isDead = ClientThread.call(() -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    return p != null && (p.isDead() || p.getHealth() <= 0);
                });
                if (isDead) {
                    CommandExecutor.sendBridgeMessage("[Bridge] Combat: player died");
                    return WorkerResult.failure("combat: player died");
                }
                float health = ClientThread.call(() -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    return p == null ? 0f : p.getHealth();
                });
                if (health <= retreatHp) {
                    CommandExecutor.sendBridgeMessage("[Bridge] Combat: retreating — HP too low");
                    return WorkerResult.failure("combat: retreating");
                }

                switch (state) {
                    case SEARCHING: {
                        lastGotoTarget = null;
                        if (useUntilItems) {
                            boolean allMet = true;
                            for (Map.Entry<String, Integer> entry : untilItems.entrySet()) {
                                int have = ClientThread.call(() -> {
                                    MinecraftClient c = MinecraftClient.getInstance();
                                    return c.player == null ? 0 : InventoryDriver.countItem(c.player, ItemIds.normalize(entry.getKey()));
                                });
                                if (have < entry.getValue()) {
                                    allMet = false;
                                    break;
                                }
                            }
                            if (allMet) {
                                CommandExecutor.sendBridgeMessage("[Bridge] Hunt: all item goals met");
                                state = CombatState.DONE;
                                continue;
                            }
                        } else if (killsRemaining <= 0) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: kill count reached");
                            state = CombatState.DONE;
                            continue;
                        }

                        currentTarget = ClientThread.call(() -> {
                            MinecraftClient c = MinecraftClient.getInstance();
                            return c.player == null ? null : CommandExecutor.findNearestHostileOfType(c, c.player, targetTypeRaw, maxDistance);
                        });
                        if (currentTarget != null) {
                            final Entity currentTargetForName = currentTarget;
                            String targetName = ClientThread.call(() -> currentTargetForName.getType().toString());
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: found " + CommandExecutor.normalizeEntityTypeName(targetName));
                            final Entity currentTargetForPos = currentTarget;
                            lastTargetPos = ClientThread.call(() -> new Vec3d(currentTargetForPos.getX(), currentTargetForPos.getY(), currentTargetForPos.getZ()));
                            state = CombatState.APPROACHING;
                            continue;
                        }

                        if (searchTimeS <= 0 || System.currentTimeMillis() >= searchDeadline) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: no " + (targetTypeRaw != null ? targetTypeRaw : "hostile") + " found after " + searchTimeS + "s");
                            state = CombatState.DONE;
                            continue;
                        }

                        if (!announcedSearch) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: searching via #explore ...");
                            announcedSearch = true;
                        }
                        TaskQueue.getInstance().cancelAll();
                        CommandExecutor.sleepQuietly(150);
                        TaskQueue.getInstance().enqueue("#explore");

                        long exploreDeadline = System.currentTimeMillis() + 8000;
                        boolean found = false;
                        while (System.currentTimeMillis() < exploreDeadline) {
                            player = ClientThread.call(() -> MinecraftClient.getInstance().player);
                            boolean exploreDisconnected = ClientThread.call(() -> {
                                MinecraftClient c = MinecraftClient.getInstance();
                                return c.player == null || c.world == null;
                            });
                            if (exploreDisconnected) return WorkerResult.failure("combat: disconnected during explore");
                            boolean exploreDead = ClientThread.call(() -> {
                                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                                return p != null && (p.isDead() || p.getHealth() <= 0);
                            });
                            if (exploreDead) {
                                CommandExecutor.sendBridgeMessage("[Bridge] Combat: player died");
                                return WorkerResult.failure("combat: player died");
                            }
                            float exploreHealth = ClientThread.call(() -> {
                                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                                return p == null ? 0f : p.getHealth();
                            });
                            if (exploreHealth <= retreatHp) {
                                CommandExecutor.sendBridgeMessage("[Bridge] Combat: retreating — HP too low");
                                return WorkerResult.failure("combat: retreating");
                            }

                            currentTarget = ClientThread.call(() -> {
                                MinecraftClient c = MinecraftClient.getInstance();
                                return c.player == null ? null : CommandExecutor.findNearestHostileOfType(c, c.player, targetTypeRaw, maxDistance);
                            });
                            if (currentTarget != null) {
                                TaskQueue.getInstance().cancelAll();
                                CommandExecutor.sleepQuietly(150);
                                final Entity exploreTargetForName = currentTarget;
                                String targetName = ClientThread.call(() -> exploreTargetForName.getType().toString());
                                CommandExecutor.sendBridgeMessage("[Bridge] Hunt: found " + CommandExecutor.normalizeEntityTypeName(targetName) + " during explore");
                                final Entity exploreTargetForPos = currentTarget;
                                lastTargetPos = ClientThread.call(() -> new Vec3d(exploreTargetForPos.getX(), exploreTargetForPos.getY(), exploreTargetForPos.getZ()));
                                state = CombatState.APPROACHING;
                                found = true;
                                break;
                            }
                            CommandExecutor.sleepQuietly(500);
                        }
                        if (!found) {
                            TaskQueue.getInstance().cancelAll();
                            CommandExecutor.sleepQuietly(150);
                        }
                        continue;
                    }

                    case APPROACHING: {
                        announcedSearch = false;
                        final Entity approachTarget = currentTarget;
                        boolean targetLost = currentTarget == null || ClientThread.call(() -> approachTarget.isRemoved() || !approachTarget.isAlive());
                        if (targetLost) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: target lost during approach");
                            state = CombatState.SEARCHING;
                            continue;
                        }

                        final Entity approachTargetForDist = currentTarget;
                        final ClientPlayerEntity approachPlayer = player;
                        double dist = ClientThread.call(() -> approachPlayer.squaredDistanceTo(approachTargetForDist));
                        if (dist <= 16.0) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: target in melee range, engaging");
                            TaskQueue.getInstance().cancelAll();
                            CommandExecutor.sleepQuietly(200);
                            state = CombatState.ENGAGING;
                            retargetTimer = System.currentTimeMillis();
                            continue;
                        }

                        final Entity ctForBlock = currentTarget;
                        BlockPos currentTargetBlock = ClientThread.call(() -> new BlockPos(
                                (int) Math.floor(ctForBlock.getX()),
                                (int) Math.floor(ctForBlock.getY()),
                                (int) Math.floor(ctForBlock.getZ())
                        ));
                        if (lastGotoTarget == null || !lastGotoTarget.equals(currentTargetBlock)) {
                            TaskQueue.getInstance().cancelAll();
                            CommandExecutor.sleepQuietly(100);
                            TaskQueue.getInstance().enqueue(
                                    "#goto " + currentTargetBlock.getX() + " " +
                                            currentTargetBlock.getY() + " " +
                                            currentTargetBlock.getZ()
                            );
                            lastGotoTarget = currentTargetBlock;
                        }

                        CommandExecutor.sleepQuietly(500);
                        continue;
                    }

                    case ENGAGING: {
                        announcedSearch = false;
                        lastGotoTarget = null;
                        ClientThread.run(() -> {
                            MinecraftClient c = MinecraftClient.getInstance();
                            if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
                        });

                        final Entity engageTargetDead = currentTarget;
                        boolean targetDead = currentTarget == null || ClientThread.call(() -> engageTargetDead.isRemoved() || !engageTargetDead.isAlive());
                        if (targetDead) {
                            boolean recentHit = (System.currentTimeMillis() - lastHitTimestamp.get()) < 2000;
                            if (isSafetyKill) {
                                CommandExecutor.sendBridgeMessage("[Bridge] Hunt: safety-killed threat, back to hunt");
                                isSafetyKill = false;
                                currentTarget = huntTarget;
                                huntTarget = null;
                                final Entity huntTargetAlive = currentTarget;
                                if (currentTarget != null && ClientThread.call(() -> huntTargetAlive.isAlive())) {
                                    state = CombatState.APPROACHING;
                                } else {
                                    state = CombatState.SEARCHING;
                                }
                                continue;
                            }
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: target killed");
                            totalAttempts++;
                            if (!useUntilItems && recentHit) {
                                killsRemaining--;
                            }
                            if (currentTarget != null) {
                                final Entity lastTargetForPos = currentTarget;
                                lastTargetPos = ClientThread.call(() -> new Vec3d(lastTargetForPos.getX(), lastTargetForPos.getY(), lastTargetForPos.getZ()));
                            }
                            state = CombatState.LOOTING;
                            continue;
                        }

                        final Entity engageTargetForDist = currentTarget;
                        final ClientPlayerEntity engagePlayer = player;
                        double dist = ClientThread.call(() -> engagePlayer.squaredDistanceTo(engageTargetForDist));
                        if (dist > 36.0) {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: target moved away, re-approaching");
                            lastGotoTarget = null;
                            state = CombatState.APPROACHING;
                            continue;
                        }

                        if (System.currentTimeMillis() - retargetTimer >= 2000) {
                            retargetTimer = System.currentTimeMillis();
                            Entity threat = ClientThread.call(() -> {
                                MinecraftClient c = MinecraftClient.getInstance();
                                return c.player == null ? null : CommandExecutor.findClosestHostile(c, c.player, 4.0);
                            });
                            if (threat != null && threat != currentTarget) {
                                final ClientPlayerEntity threatPlayer = player;
                                final Entity threatEntity = threat;
                                double distThreat = ClientThread.call(() -> threatPlayer.squaredDistanceTo(threatEntity));
                                final ClientPlayerEntity currentPlayer = player;
                                final Entity currentTargetForComp = currentTarget;
                                double distCurrent = ClientThread.call(() -> currentPlayer.squaredDistanceTo(currentTargetForComp));
                                if (distThreat < distCurrent) {
                                    if (!isSafetyKill) {
                                        huntTarget = currentTarget;
                                        isSafetyKill = true;
                                    }
                                    currentTarget = threat;
                                }
                            }
                        }

                        final ClientPlayerEntity fp = player;
                        final Entity t = currentTarget;
                        final AtomicLong hitStamp = lastHitTimestamp;
                        ClientThread.run(() -> {
                            CommandExecutor.lookAtEntity(fp, t);
                            if (client.interactionManager != null) {
                                client.interactionManager.attackEntity(fp, t);
                                fp.swingHand(Hand.MAIN_HAND);
                                hitStamp.set(System.currentTimeMillis());
                            }
                        });

                        int cooldownMs = ClientThread.call(() -> {
                            MinecraftClient c = MinecraftClient.getInstance();
                            return c.player == null ? 500 : InventoryDriver.getAttackCooldownMs(c.player);
                        });
                        CommandExecutor.sleepQuietly(cooldownMs);
                        continue;
                    }

                    case LOOTING: {
                        announcedSearch = false;
                        lastGotoTarget = null;
                        CommandExecutor.sendBridgeMessage("[Bridge] Hunt: looting drops ...");
                        CommandExecutor.sleepQuietly(2500);

                        if (lastTargetPos != null) {
                            final ClientPlayerEntity lootingPlayer = player;
                            Vec3d playerPos = ClientThread.call(() -> new Vec3d(lootingPlayer.getX(), lootingPlayer.getY(), lootingPlayer.getZ()));
                            Vec3d toTarget = lastTargetPos.subtract(playerPos);
                            double len = toTarget.length();
                            if (len > 0.1) {
                                Vec3d movePos = playerPos.add(toTarget.normalize().multiply(Math.min(len, 2.0)));
                                int mx = (int) Math.floor(movePos.x);
                                int my = (int) Math.floor(movePos.y);
                                int mz = (int) Math.floor(movePos.z);
                                TaskQueue.getInstance().cancelAll();
                                CommandExecutor.sleepQuietly(150);
                                TaskQueue.getInstance().enqueue("#goto " + mx + " " + my + " " + mz);
                                CommandExecutor.waitForActiveTaskComplete(10_000L);
                                TaskQueue.getInstance().cancelAll();
                                CommandExecutor.sleepQuietly(150);
                            }
                        }

                        long lootDeadline = System.currentTimeMillis() + 10_000;
                        boolean itemsMet = false;
                        while (System.currentTimeMillis() < lootDeadline) {
                            player = ClientThread.call(() -> MinecraftClient.getInstance().player);
                            if (player == null) break;
                            if (useUntilItems) {
                                boolean allMet = true;
                                for (Map.Entry<String, Integer> entry : untilItems.entrySet()) {
                                    int have = ClientThread.call(() -> {
                                        MinecraftClient c = MinecraftClient.getInstance();
                                        return c.player == null ? 0 : InventoryDriver.countItem(c.player, ItemIds.normalize(entry.getKey()));
                                    });
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
                            CommandExecutor.sleepQuietly(500);
                        }

                        if (itemsMet) {
                            if (useUntilItems) {
                                CommandExecutor.sendBridgeMessage("[Bridge] Hunt: all item goals met");
                            }
                            state = CombatState.DONE;
                        } else {
                            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: need more kills, returning to search");
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
                CommandExecutor.sendBridgeMessage("[Bridge] Hunt: max attempts reached (" + maxAttempts + ")");
            }
            CommandExecutor.sendBridgeMessage("[Bridge] Hunt: complete");
            ctx.taskQueue().completeActiveIf(command);
            return WorkerResult.success("combat complete");
        } catch (Exception e) {
            LOGGER.error(command + " worker crashed", e);
            ctx.taskQueue().failActiveIf(command, command + ": " + e.getMessage());
            return WorkerResult.failure(command + ": " + e.getMessage());
        } finally {
            try { ScreenDriver.closeScreen(); } catch (Exception ignored) {}
        }
    }
}
