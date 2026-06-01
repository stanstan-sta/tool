package com.mindcraft.bridge.workers.crafting;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

public class BrewWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#brew";
        try {
            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            String ingredient = CommandExecutor.extractJsonString(actionJson, "ingredient");
            String potions = CommandExecutor.extractJsonString(actionJson, "potions");
            String fuel = CommandExecutor.extractJsonString(actionJson, "fuel");

            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("brew: not connected");
            }

            BlockPos stand = WorkstationFinder.findNearestStationOnClientThread("brewing_stand");
            if (stand == null) {
                return WorkerResult.failure("brew: no brewing stand nearby");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (!CommandExecutor.waitUntilNear(stand, 4.75, 30_000L, ctx.isCancelled())) {
                return WorkerResult.failure("brew: failed to reach stand");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (!WorldInteractor.openBlock(stand)) {
                return WorkerResult.failure("brew: failed to open stand");
            }

            if (!ScreenDriver.waitForHandler(BrewingStandScreenHandler.class, 3000)) {
                ScreenDriver.closeScreen();
                return WorkerResult.failure("brew: screen did not open");
            }

            boolean ok = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                ClientPlayerInteractionManager im = MinecraftClient.getInstance().interactionManager;
                if (p == null || im == null || !(p.currentScreenHandler instanceof BrewingStandScreenHandler)) return false;
                ScreenHandler screen = p.currentScreenHandler;

                if (ingredient != null) {
                    String normIngredient = ItemIds.normalize(ingredient);
                    int ingSlot = ScreenDriver.findSlot(screen, normIngredient, 5);
                    if (ingSlot < 0) return false;
                    ScreenDriver.pickup(ingSlot, BrewingStandScreenHandler.class);
                    ScreenDriver.pickup(3, BrewingStandScreenHandler.class);
                    ScreenDriver.pickup(ingSlot, BrewingStandScreenHandler.class);
                }
                CommandExecutor.sleep(100); // Wait for server acknowledgment

                if (fuel != null) {
                    String normFuel = ItemIds.normalize(fuel);
                    int fuelSlot = ScreenDriver.findSlot(screen, normFuel, 5);
                    if (fuelSlot < 0) return false;
                    ScreenDriver.pickup(fuelSlot, BrewingStandScreenHandler.class);
                    ScreenDriver.pickup(4, BrewingStandScreenHandler.class);
                    ScreenDriver.pickup(fuelSlot, BrewingStandScreenHandler.class);
                }
                CommandExecutor.sleep(100); // Wait for server acknowledgment

                if (potions != null) {
                    String normPotions = ItemIds.normalize(potions);
                    int moved = 0;
                    for (int targetSlot = 0; targetSlot < 3; targetSlot++) {
                        int potSlot = ScreenDriver.findSlot(screen, normPotions, 5);
                        if (potSlot < 0) break;
                        ScreenDriver.pickup(potSlot, BrewingStandScreenHandler.class);
                        ScreenDriver.pickup(targetSlot, BrewingStandScreenHandler.class);
                        ScreenDriver.pickup(potSlot, BrewingStandScreenHandler.class);
                        moved++;
                        CommandExecutor.sleep(100); // Wait for server acknowledgment
                    }
                    if (moved == 0) return false;
                }

                return true;
            });

            ScreenDriver.closeScreen();
            if (!CommandExecutor.sleepCancellable(ctx.isCancelled(), 20_000L, 500L)) {
                ctx.taskQueue().failActiveIf("#brew", "cancelled");
                return WorkerResult.failure("cancelled");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            ctx.taskQueue().completeActiveIf(command);
            return WorkerResult.success("brewed");
        } catch (Exception e) {
            LOGGER.error(command + " worker crashed", e);
            ctx.taskQueue().failActiveIf(command, command + ": " + e.getMessage());
            return WorkerResult.failure(command + ": " + e.getMessage());
        } finally {
            try { ScreenDriver.closeScreen(); } catch (Exception ignored) {}
        }
    }
}
