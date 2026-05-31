package com.mindcraft.bridge.workers.gathering;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public class FishWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#fish";
        try {
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            String countStr = CommandExecutor.extractJsonPrimitive(actionJson, "count");
            int targetCount = 1;
            if (countStr != null) {
                try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {}
            }

            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("fish: not connected");
            }

            int rodSlot = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player == null) return -1;
                PlayerInventory inv = c.player.getInventory();
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty() && ItemIds.fromStack(stack).equals("minecraft:fishing_rod")) {
                        return i;
                    }
                }
                return -1;
            });
            if (rodSlot < 0) {
                return WorkerResult.failure("fish: no fishing rod in hotbar");
            }

            BlockPos water = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player == null || c.world == null) return null;
                BlockPos.Mutable mutable = new BlockPos.Mutable();
                BlockPos playerPos = c.player.getBlockPos();
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                            if (c.world.getBlockState(mutable).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                                return mutable.toImmutable();
                            }
                        }
                    }
                }
                return null;
            });
            if (water == null) {
                return WorkerResult.failure("fish: no water found nearby");
            }

            InventoryDriver.selectSlot(rodSlot);
            CommandExecutor.sleep(200);

            int caught = 0;
            for (int attempt = 0; attempt < targetCount * 5 && caught < targetCount; attempt++) {
                if (ctx.isCancelled().get()) break;

                ClientThread.run(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player != null && c.interactionManager != null) {
                        c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
                    }
                });
                CommandExecutor.sleep(500);

                // Wait for bite by checking bobber entity for movement
                long biteDeadline = System.currentTimeMillis() + 30_000L;
                boolean bobberMoved = false;
                int lastBobberY = -1;
                while (System.currentTimeMillis() < biteDeadline && !bobberMoved) {
                    if (ctx.isCancelled().get()) break;
                    
                    // Check for fishing bobber entity and detect bite via Y movement
                    Integer bobberY = ClientThread.call(() -> {
                        MinecraftClient c = MinecraftClient.getInstance();
                        if (c.player == null || c.world == null) return null;
                        // Find fishing bobber owned by player
                        var entities = c.world.getEntitiesByClass(
                            net.minecraft.entity.projectile.FishingBobberEntity.class,
                            c.player.getBoundingBox().expand(32),
                            e -> e.getOwner() == c.player
                        );
                        if (entities.isEmpty()) return null;
                        return (int) entities.get(0).getY();
                    });
                    
                    if (bobberY != null) {
                        if (lastBobberY >= 0 && bobberY != lastBobberY) {
                            // Bobber moved — fish bit!
                            bobberMoved = true;
                        }
                        lastBobberY = bobberY;
                    }
                    
                    CommandExecutor.sleep(200);
                }
                
                if (ctx.isCancelled().get()) break;
                
                // Reel in (right-click again to catch)
                if (bobberMoved) {
                    ClientThread.run(() -> {
                        MinecraftClient c = MinecraftClient.getInstance();
                        if (c.player != null && c.interactionManager != null) {
                            c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
                        }
                    });
                    CommandExecutor.sleep(500);
                    caught++;
                }
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (caught >= targetCount) {
                ctx.taskQueue().completeActiveIf(command);
                return WorkerResult.success("fished " + caught);
            } else {
                return WorkerResult.failure("fish: caught " + caught + "/" + targetCount);
            }
        } catch (Exception e) {
            LOGGER.error(command + " worker crashed", e);
            ctx.taskQueue().failActiveIf(command, command + ": " + e.getMessage());
            return WorkerResult.failure(command + ": " + e.getMessage());
        } finally {
            try { ScreenDriver.closeScreen(); } catch (Exception ignored) {}
        }
    }
}
