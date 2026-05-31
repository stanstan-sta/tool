package com.mindcraft.bridge.workers.gathering;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public class FarmWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#farm";
        try {
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            String cropId = CommandExecutor.extractJsonString(actionJson, "crop");
            String countStr = CommandExecutor.extractJsonPrimitive(actionJson, "count");
            if (cropId == null) return WorkerResult.failure("farm: missing crop");

            int targetCount = 1;
            if (countStr != null) {
                try { targetCount = Math.max(1, Integer.parseInt(countStr)); } catch (NumberFormatException ignored) {}
            }

            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("farm: not connected");
            }

            String rawCropBlock = cropId.replace("_seeds", "").replace("minecraft:", "minecraft:");
            final String cropBlock;
            if (rawCropBlock.equals("minecraft:wheat")) cropBlock = "minecraft:wheat";
            else if (rawCropBlock.equals("minecraft:carrot")) cropBlock = "minecraft:carrots";
            else if (rawCropBlock.equals("minecraft:potato")) cropBlock = "minecraft:potatoes";
            else if (rawCropBlock.equals("minecraft:beetroot")) cropBlock = "minecraft:beetroots";
            else cropBlock = rawCropBlock;

            int collected = 0;
            int maxSearch = targetCount * 10;
            for (int attempt = 0; attempt < maxSearch && collected < targetCount; attempt++) {
                if (ctx.isCancelled().get()) break;
                BlockPos found = WorkstationFinder.findNearestOnClientThread(cropBlock, 16);
                if (found == null) {
                    if (collected > 0) break;
                    return WorkerResult.failure("farm: no " + cropBlock + " found nearby");
                }
                if (!WorldInteractor.breakBlock(found)) {
                    CommandExecutor.sleep(200);
                    continue;
                }
                CommandExecutor.sleep(300);
                
                // Replant if config enabled
                if (BridgeConfig.get().farmReplantAfterHarvest) {
                    String seedItem = getSeedForCrop(cropId);
                    if (seedItem != null) {
                        boolean hasSeed = ClientThread.call(() -> {
                            var p = MinecraftClient.getInstance().player;
                            return p != null && InventoryDriver.countItem(p, seedItem) > 0;
                        });
                        if (hasSeed) {
                            // Place seed at the same position
                            ClientThread.run(() -> {
                                var c = MinecraftClient.getInstance();
                                if (c.player != null && c.interactionManager != null) {
                                    // Select seed in hotbar
                                    var inv = c.player.getInventory();
                                    for (int i = 0; i < 9; i++) {
                                        var stack = inv.getStack(i);
                                        if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(seedItem)) {
                                            InventoryDriver.selectSlot(i);
                                            break;
                                        }
                                    }
                            // Use item on the block
                            var hit = net.minecraft.util.math.Vec3d.ofCenter(found);
                            var hitResult = new net.minecraft.util.hit.BlockHitResult(hit, net.minecraft.util.math.Direction.UP, found, false);
                            c.interactionManager.interactBlock(c.player, net.minecraft.util.Hand.MAIN_HAND, hitResult);
                                }
                            });
                            CommandExecutor.sleep(200);
                        }
                    }
                }
                
                collected++;
                CommandExecutor.sleep(100);
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (collected >= targetCount) {
                ctx.taskQueue().completeActiveIf(command);
                return WorkerResult.success("farmed " + collected);
            } else {
                return WorkerResult.failure("farm: collected " + collected + "/" + targetCount);
            }
        } catch (Exception e) {
            LOGGER.error(command + " worker crashed", e);
            ctx.taskQueue().failActiveIf(command, command + ": " + e.getMessage());
            return WorkerResult.failure(command + ": " + e.getMessage());
        } finally {
            try { ScreenDriver.closeScreen(); } catch (Exception ignored) {}
        }
    }
    
    private String getSeedForCrop(String cropId) {
        return switch (cropId) {
            case "minecraft:wheat" -> "minecraft:wheat_seeds";
            case "minecraft:carrots" -> "minecraft:carrot";
            case "minecraft:potatoes" -> "minecraft:potato";
            case "minecraft:beetroots" -> "minecraft:beetroot_seeds";
            case "minecraft:pumpkin" -> "minecraft:pumpkin_seeds";
            case "minecraft:melon" -> "minecraft:melon_seeds";
            default -> null;
        };
    }
}
