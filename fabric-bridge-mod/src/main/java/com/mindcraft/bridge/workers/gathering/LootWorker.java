package com.mindcraft.bridge.workers.gathering;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;

public class LootWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = ctx.actionType() != null ? "#" + ctx.actionType() : "#loot";
        try {
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            String targetId = CommandExecutor.extractJsonString(actionJson, "target");
            if (targetId == null) {
                // Sub-actions derive their identity from the registered type
                String actionType = ctx.actionType();
                if (actionType != null && !actionType.equals("loot")) {
                    targetId = actionType;
                }
            }
            if (targetId == null) return WorkerResult.failure("loot: missing target");

            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("loot: not connected");
            }

            String containerBlock = null;
            for (String candidate : new String[]{"minecraft:chest", "minecraft:barrel", "minecraft:trapped_chest"}) {
                BlockPos found = ClientThread.call(() -> {
                    MinecraftClient c = MinecraftClient.getInstance();
                    return WorkstationFinder.findNearest(c.world, c.player, candidate, 16);
                });
                if (found != null) {
                    containerBlock = candidate;
                    if (!WorldInteractor.openBlock(found)) {
                        CommandExecutor.sleep(200);
                        continue;
                    }
                    break;
                }
            }
            if (containerBlock == null) {
                return WorkerResult.failure("loot: no container found nearby");
            }

            if (!ScreenDriver.waitForHandler(GenericContainerScreenHandler.class, 5000)) {
                ScreenDriver.closeScreen();
                return WorkerResult.failure("loot: container screen did not open");
            }

            String normalized = ItemIds.normalize(targetId);
            boolean found = false;
            for (int attempt = 0; attempt < 50; attempt++) {
                if (ctx.isCancelled().get()) break;
                int slot = ClientThread.call(() -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    if (p == null || !(p.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return -1;
                    for (int i = 0; i < handler.getInventory().size(); i++) {
                        ItemStack stack = handler.getInventory().getStack(i);
                        if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(normalized)) return i;
                    }
                    return -1;
                });
                if (slot >= 0) {
                    CommandExecutor.sleep(50); // Wait for slot state sync before moving
                    ScreenDriver.quickMove(slot);
                    found = true;
                    CommandExecutor.sleep(200);
                    break;
                }
                CommandExecutor.sleep(100);
            }

            ScreenDriver.closeScreen();
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (found) {
                ctx.taskQueue().completeActiveIf(command);
                return WorkerResult.success("looted " + normalized);
            } else {
                return WorkerResult.failure("loot: " + normalized + " not found in container");
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
