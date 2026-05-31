package com.mindcraft.bridge.workers.crafting;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.util.math.BlockPos;

public class SmithWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = "#smith";
        try {
            if (ctx.isCancelled().get()) {
                return WorkerResult.failure("cancelled");
            }

            String template = ItemIds.normalize(CommandExecutor.extractJsonString(actionJson, "template"));
            String base = ItemIds.normalize(CommandExecutor.extractJsonString(actionJson, "base"));
            String addition = ItemIds.normalize(CommandExecutor.extractJsonString(actionJson, "addition"));
            String output = ItemIds.normalize(CommandExecutor.extractJsonString(actionJson, "output"));

            BlockPos table = WorkstationFinder.findNearestStationOnClientThread("smithing_table");
            if (table == null) {
                return WorkerResult.failure("smith: no smithing table nearby");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            if (!CommandExecutor.waitUntilNear(table, 4.75, 30_000L, ctx.isCancelled())) {
                return WorkerResult.failure("smith: failed to reach table");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            if (!WorldInteractor.openBlock(table)) {
                return WorkerResult.failure("smith: failed to open table");
            }

            if (!ScreenDriver.waitForHandler(SmithingScreenHandler.class, 3000)) {
                ScreenDriver.closeScreen();
                return WorkerResult.failure("smith: screen did not open");
            }

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");

            boolean ok = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                ClientPlayerInteractionManager im = MinecraftClient.getInstance().interactionManager;
                if (p == null || im == null || !(p.currentScreenHandler instanceof SmithingScreenHandler)) {
                    return false;
                }
                ScreenHandler screen = p.currentScreenHandler;

                int templateSlot = ScreenDriver.findSlot(screen, template, 4);
                if (templateSlot < 0) return false;
                ScreenDriver.pickup(templateSlot, SmithingScreenHandler.class);
                ScreenDriver.pickup(0, SmithingScreenHandler.class);
                ScreenDriver.pickup(templateSlot, SmithingScreenHandler.class);

                int baseSlot = ScreenDriver.findSlot(screen, base, 4);
                if (baseSlot < 0) return false;
                ScreenDriver.pickup(baseSlot, SmithingScreenHandler.class);
                ScreenDriver.pickup(1, SmithingScreenHandler.class);
                ScreenDriver.pickup(baseSlot, SmithingScreenHandler.class);

                int additionSlot = ScreenDriver.findSlot(screen, addition, 4);
                if (additionSlot < 0) return false;
                ScreenDriver.pickup(additionSlot, SmithingScreenHandler.class);
                ScreenDriver.pickup(2, SmithingScreenHandler.class);
                ScreenDriver.pickup(additionSlot, SmithingScreenHandler.class);

                CommandExecutor.sleep(100);

                return ScreenDriver.quickMove(3, SmithingScreenHandler.class);
            });

            ScreenDriver.closeScreen();

            if (ctx.isCancelled().get()) return WorkerResult.failure("cancelled");
            if (ok) {
                ctx.taskQueue().completeActiveIf(command);
                return WorkerResult.success("smithed");
            } else {
                return WorkerResult.failure("smith: recipe failed");
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
