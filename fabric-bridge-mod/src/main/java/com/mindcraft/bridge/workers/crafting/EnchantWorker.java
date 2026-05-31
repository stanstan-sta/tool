package com.mindcraft.bridge.workers.crafting;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

public class EnchantWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#enchant";
        try {
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            String item = CommandExecutor.extractJsonString(actionJson, "item");
            String level = CommandExecutor.extractJsonString(actionJson, "level");
            String lapis = CommandExecutor.extractJsonString(actionJson, "lapis");

            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("enchant: not connected");
            }

            BlockPos table = WorkstationFinder.findNearestStationOnClientThread("enchanting_table");
            if (table == null) {
                return WorkerResult.failure("enchant: no enchanting table nearby");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (!CommandExecutor.waitUntilNear(table, 4.75, 30_000L, ctx.isCancelled())) {
                return WorkerResult.failure("enchant: failed to reach table");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (!WorldInteractor.openBlock(table)) {
                return WorkerResult.failure("enchant: failed to open table");
            }

            if (!ScreenDriver.waitForHandler(EnchantmentScreenHandler.class, 3000)) {
                ScreenDriver.closeScreen();
                return WorkerResult.failure("enchant: screen did not open");
            }

            boolean ok = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                ClientPlayerInteractionManager im = MinecraftClient.getInstance().interactionManager;
                if (p == null || im == null || !(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) return false;
                ScreenHandler screen = p.currentScreenHandler;

                if (item != null) {
                    String normItem = ItemIds.normalize(item);
                    int itemSlot = ScreenDriver.findSlot(screen, normItem, 2);
                    if (itemSlot >= 0) {
                        ScreenDriver.pickup(itemSlot, EnchantmentScreenHandler.class);
                        ScreenDriver.pickup(0, EnchantmentScreenHandler.class);
                        ScreenDriver.pickup(itemSlot, EnchantmentScreenHandler.class);
                    }
                }
                CommandExecutor.sleep(100); // Wait for server acknowledgment

                if (lapis != null) {
                    String normLapis = ItemIds.normalize(lapis);
                    int lapisSlot = ScreenDriver.findSlot(screen, normLapis, 2);
                    if (lapisSlot >= 0) {
                        ScreenDriver.pickup(lapisSlot, EnchantmentScreenHandler.class);
                        ScreenDriver.pickup(1, EnchantmentScreenHandler.class);
                        ScreenDriver.pickup(lapisSlot, EnchantmentScreenHandler.class);
                    }
                }
                CommandExecutor.sleep(100); // Wait for server acknowledgment

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

                CommandExecutor.sleep(200); // Longer wait for final operation
                return ScreenDriver.quickMove(0, EnchantmentScreenHandler.class);
            });

            ScreenDriver.closeScreen();

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (ok) {
                ctx.taskQueue().completeActiveIf(command);
                return WorkerResult.success("enchanted");
            } else {
                return WorkerResult.failure("enchant: " + command + " failed");
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
