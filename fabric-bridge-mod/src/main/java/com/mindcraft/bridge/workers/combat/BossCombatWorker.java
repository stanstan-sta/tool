package com.mindcraft.bridge.workers.combat;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BossCombatWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");

    private enum BossState {
        SCANNING,
        APPROACHING,
        ENGAGING,
        RETREATING,
        HEALING,
        DONE
    }

    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = ctx.actionType() != null ? "#" + ctx.actionType() : "#boss_combat";
        try {
            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("boss_combat: not connected");
            }

            String bossType = CommandExecutor.extractJsonString(actionJson, "boss_type");
            if (bossType == null && ctx.actionType() != null) {
                bossType = switch (ctx.actionType()) {
                    case "fight_dragon" -> "dragon";
                    case "fight_wither" -> "wither";
                    case "raid" -> "raid";
                    default -> null;
                };
            }
            if (bossType == null) {
                return WorkerResult.failure("boss_combat: missing boss_type");
            }

            WorkerResult result = switch (bossType) {
                case "dragon" -> executeDragonFight(actionJson, ctx);
                case "wither" -> executeWitherFight(actionJson, ctx);
                case "raid" -> executeRaidManagement(actionJson, ctx);
                default -> WorkerResult.failure("boss_combat: unknown boss_type " + bossType);
            };
            if (result.ok()) {
                ctx.taskQueue().completeActiveIf(command);
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(command + " worker crashed", e);
            ctx.taskQueue().failActiveIf(command, command + ": " + e.getMessage());
            return WorkerResult.failure(command + ": " + e.getMessage());
        } finally {
            try { ScreenDriver.closeScreen(); } catch (Exception ignored) {}
        }
    }

    // ─── Dragon Fight ─────────────────────────────────────────────────────────

    private WorkerResult executeDragonFight(String actionJson, WorkerContext ctx) {
        boolean inEnd = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.world != null && c.world.getRegistryKey() == net.minecraft.world.World.END;
        });
        if (!inEnd) {
            return WorkerResult.failure("boss_combat: not in End dimension");
        }

        Entity dragon = findDragon();
        if (dragon == null) {
            return WorkerResult.failure("boss_combat: no dragon found");
        }

        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: dragon fight started");
        ClientThread.run(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
        });

        BossState state = BossState.SCANNING;
        int attempts = 0;
        int maxAttempts = 200;

        while (state != BossState.DONE && attempts < maxAttempts) {
            if (ctx.isCancelled().get()) {
                return WorkerResult.failure("boss_combat: cancelled");
            }

            boolean dead = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null || c.player.isDead() || c.player.getHealth() <= 0;
            });
            if (dead) {
                return WorkerResult.failure("boss_combat: player died");
            }

            float hp = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null ? 999f : c.player.getHealth();
            });
            if (hp <= 8.0f) {
                state = BossState.HEALING;
            }

            state = processDragonState(state, dragon);
            attempts++;
        }

        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: dragon fight complete");
        return WorkerResult.success("boss_combat: dragon fight completed");
    }

    private BossState processDragonState(BossState state, Entity dragon) {
        return switch (state) {
            case SCANNING -> {
                boolean alive = ClientThread.call(() -> dragon != null && dragon.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                double dist = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return Double.MAX_VALUE;
                    return c.player.squaredDistanceTo(dragon);
                });
                if (dist > 100.0) {
                    yield BossState.APPROACHING;
                }
                yield BossState.ENGAGING;
            }
            case APPROACHING -> {
                boolean alive = ClientThread.call(() -> dragon != null && dragon.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                BlockPos target = ClientThread.call(() -> dragon.getBlockPos());
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue(
                    "#goto " + target.getX() + " " + target.getY() + " " + target.getZ()
                );
                CommandExecutor.sleepQuietly(2000);
                yield BossState.SCANNING;
            }
            case ENGAGING -> {
                boolean alive = ClientThread.call(() -> dragon != null && dragon.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                ClientThread.run(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
                });
                final Entity d = dragon;
                ClientThread.run(() -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.interactionManager != null && client.player != null) {
                        client.interactionManager.attackEntity(client.player, d);
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                });
                int cooldown = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return c.player == null ? 500 : InventoryDriver.getAttackCooldownMs(c.player);
                });
                CommandExecutor.sleepQuietly(cooldown);
                yield BossState.SCANNING;
            }
            case RETREATING -> {
                int[] retreatPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 20),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 20)
                    };
                });
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + retreatPos[0] + " " + retreatPos[1] + " " + retreatPos[2]);
                CommandExecutor.sleepQuietly(3000);
                yield BossState.SCANNING;
            }
            case HEALING -> {
                int[] healPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 30),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 30)
                    };
                });
                CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: healing...");
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + healPos[0] + " " + healPos[1] + " " + healPos[2]);
                CommandExecutor.sleepQuietly(5000);
                yield BossState.SCANNING;
            }
            default -> state;
        };
    }

    // ─── Wither Fight ─────────────────────────────────────────────────────────

    private WorkerResult executeWitherFight(String actionJson, WorkerContext ctx) {
        Entity wither = findWither();
        if (wither == null) {
            return WorkerResult.failure("boss_combat: no wither found");
        }

        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: wither fight started");
        ClientThread.run(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
        });

        BossState state = BossState.SCANNING;
        int attempts = 0;
        int maxAttempts = 200;

        while (state != BossState.DONE && attempts < maxAttempts) {
            if (ctx.isCancelled().get()) {
                return WorkerResult.failure("boss_combat: cancelled");
            }

            boolean dead = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null || c.player.isDead() || c.player.getHealth() <= 0;
            });
            if (dead) {
                return WorkerResult.failure("boss_combat: player died");
            }

            float hp = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null ? 999f : c.player.getHealth();
            });
            if (hp <= 10.0f) {
                state = BossState.HEALING;
            }

            state = processWitherState(state, wither);
            attempts++;
        }

        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: wither fight complete");
        return WorkerResult.success("boss_combat: wither fight completed");
    }

    private BossState processWitherState(BossState state, Entity wither) {
        return switch (state) {
            case SCANNING -> {
                boolean alive = ClientThread.call(() -> wither != null && wither.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                double dist = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return Double.MAX_VALUE;
                    return c.player.squaredDistanceTo(wither);
                });
                if (dist > 64.0) {
                    yield BossState.APPROACHING;
                }
                yield BossState.ENGAGING;
            }
            case APPROACHING -> {
                boolean alive = ClientThread.call(() -> wither != null && wither.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                BlockPos target = ClientThread.call(() -> wither.getBlockPos());
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue(
                    "#goto " + target.getX() + " " + target.getY() + " " + target.getZ()
                );
                CommandExecutor.sleepQuietly(2000);
                yield BossState.SCANNING;
            }
            case ENGAGING -> {
                boolean alive = ClientThread.call(() -> wither != null && wither.isAlive());
                if (!alive) {
                    yield BossState.DONE;
                }
                ClientThread.run(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
                });
                final Entity w = wither;
                ClientThread.run(() -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.interactionManager != null && client.player != null) {
                        client.interactionManager.attackEntity(client.player, w);
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                });
                int cooldown = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return c.player == null ? 500 : InventoryDriver.getAttackCooldownMs(c.player);
                });
                CommandExecutor.sleepQuietly(cooldown);
                yield BossState.SCANNING;
            }
            case RETREATING -> {
                int[] retreatPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 20),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 20)
                    };
                });
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + retreatPos[0] + " " + retreatPos[1] + " " + retreatPos[2]);
                CommandExecutor.sleepQuietly(3000);
                yield BossState.SCANNING;
            }
            case HEALING -> {
                int[] healPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 30),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 30)
                    };
                });
                CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: healing...");
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + healPos[0] + " " + healPos[1] + " " + healPos[2]);
                CommandExecutor.sleepQuietly(5000);
                yield BossState.SCANNING;
            }
            default -> state;
        };
    }

    // ─── Raid Management ──────────────────────────────────────────────────────

    private WorkerResult executeRaidManagement(String actionJson, WorkerContext ctx) {
        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: raid management started");

        Entity nearest = findNearestRaidMob();
        if (nearest == null) {
            return WorkerResult.failure("boss_combat: no raid mobs found");
        }

        ClientThread.run(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
        });

        BossState state = BossState.SCANNING;
        int attempts = 0;
        int maxAttempts = 100;

        while (state != BossState.DONE && attempts < maxAttempts) {
            if (ctx.isCancelled().get()) {
                return WorkerResult.failure("boss_combat: cancelled");
            }

            boolean dead = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null || c.player.isDead() || c.player.getHealth() <= 0;
            });
            if (dead) {
                return WorkerResult.failure("boss_combat: player died");
            }

            float hp = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null ? 999f : c.player.getHealth();
            });
            if (hp <= 10.0f) {
                state = BossState.HEALING;
            }

            state = processRaidState(state);
            attempts++;
        }

        CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: raid complete");
        return WorkerResult.success("boss_combat: raid completed");
    }

    private BossState processRaidState(BossState state) {
        return switch (state) {
            case SCANNING -> {
                Entity target = findNearestRaidMob();
                if (target == null) {
                    yield BossState.DONE;
                }
                double dist = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return Double.MAX_VALUE;
                    return c.player.squaredDistanceTo(target);
                });
                if (dist > 16.0) {
                    yield BossState.APPROACHING;
                }
                yield BossState.ENGAGING;
            }
            case APPROACHING -> {
                Entity target = findNearestRaidMob();
                if (target == null) {
                    yield BossState.DONE;
                }
                BlockPos pos = ClientThread.call(() -> target.getBlockPos());
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue(
                    "#goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                );
                CommandExecutor.sleepQuietly(2000);
                yield BossState.SCANNING;
            }
            case ENGAGING -> {
                Entity target = findNearestRaidMob();
                if (target == null) {
                    yield BossState.DONE;
                }
                ClientThread.run(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null) InventoryDriver.equipBestWeapon(c.player);
                });
                final Entity m = target;
                ClientThread.run(() -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.interactionManager != null && client.player != null) {
                        client.interactionManager.attackEntity(client.player, m);
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                });
                int cooldown = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return c.player == null ? 500 : InventoryDriver.getAttackCooldownMs(c.player);
                });
                CommandExecutor.sleepQuietly(cooldown);
                yield BossState.SCANNING;
            }
            case RETREATING -> {
                int[] retreatPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 15),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 15)
                    };
                });
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + retreatPos[0] + " " + retreatPos[1] + " " + retreatPos[2]);
                CommandExecutor.sleepQuietly(3000);
                yield BossState.SCANNING;
            }
            case HEALING -> {
                int[] healPos = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return new int[]{0, 64, 0};
                    return new int[]{
                        (int) Math.floor(c.player.getX() - 25),
                        (int) Math.floor(c.player.getY()),
                        (int) Math.floor(c.player.getZ() - 25)
                    };
                });
                CommandExecutor.sendBridgeMessage("[Bridge] BossCombat: healing...");
                TaskQueue.getInstance().cancelAll();
                CommandExecutor.sleepQuietly(100);
                TaskQueue.getInstance().enqueue("#goto " + healPos[0] + " " + healPos[1] + " " + healPos[2]);
                CommandExecutor.sleepQuietly(5000);
                yield BossState.SCANNING;
            }
            default -> state;
        };
    }

    // ─── Entity Finders ───────────────────────────────────────────────────────

    private Entity findDragon() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(256),
                e -> e instanceof EnderDragonEntity && e.isAlive()
            ).stream().findFirst().orElse(null);
        });
    }

    private Entity findWither() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(128),
                e -> e instanceof WitherEntity && e.isAlive()
            ).stream().findFirst().orElse(null);
        });
    }

    private Entity findNearestRaidMob() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(64),
                e -> e instanceof Monster && e.isAlive()
            ).stream().findFirst().orElse(null);
        });
    }

    @Override
    public boolean requiresThread() {
        return true;
    }
}
