package com.mindcraft.bridge.workers.husbandry;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.*;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class HusbandryWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");

    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = ctx.actionType() != null ? "#" + ctx.actionType() : "#husbandry";
        try {
            boolean connected = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("husbandry: not connected");
            }

            String action = CommandExecutor.extractJsonString(actionJson, "action");
            if (action == null) action = ctx.actionType();
            if (action == null) action = "shear";

            WorkerResult result = switch (action) {
                case "shear" -> executeShear(actionJson, ctx);
                case "milk" -> executeMilk(actionJson, ctx);
                case "breed" -> executeBreed(actionJson, ctx);
                case "tame" -> executeTame(actionJson, ctx);
                default -> WorkerResult.failure("husbandry: unknown action " + action);
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

    private WorkerResult executeShear(String actionJson, WorkerContext ctx) {
        Entity target = findNearestSheep();
        if (target == null) {
            return WorkerResult.failure("husbandry: no sheep nearby");
        }

        if (ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player == null || InventoryDriver.countItem(c.player, "minecraft:shears") <= 0;
        })) {
            return WorkerResult.failure("husbandry: no shears");
        }

        int slot = findAndSelectItem("minecraft:shears");
        if (slot < 0) {
            return WorkerResult.failure("husbandry: no shears in hotbar");
        }

        boolean success = interactWithEntity(target.getId());
        if (success) {
            CommandExecutor.sendBridgeMessage("[Bridge] Husbandry: sheared sheep");
            return WorkerResult.success("husbandry: sheared sheep");
        } else {
            return WorkerResult.failure("husbandry: failed to shear");
        }
    }

    private WorkerResult executeMilk(String actionJson, WorkerContext ctx) {
        Entity target = findNearestCow();
        if (target == null) {
            return WorkerResult.failure("husbandry: no cow nearby");
        }

        if (ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player == null || InventoryDriver.countItem(c.player, "minecraft:bucket") <= 0;
        })) {
            return WorkerResult.failure("husbandry: no bucket");
        }

        int slot = findAndSelectItem("minecraft:bucket");
        if (slot < 0) {
            return WorkerResult.failure("husbandry: no bucket in hotbar");
        }

        CommandExecutor.sleepQuietly(200);

        boolean success = interactWithEntity(target.getId());
        if (success) {
            CommandExecutor.sendBridgeMessage("[Bridge] Husbandry: milked cow");
            return WorkerResult.success("husbandry: milked cow");
        } else {
            return WorkerResult.failure("husbandry: failed to milk");
        }
    }

    private WorkerResult executeBreed(String actionJson, WorkerContext ctx) {
        List<Entity> animals = findNearbyAnimals();
        if (animals.size() < 2) {
            return WorkerResult.failure("husbandry: need at least 2 animals");
        }

        Entity animal1 = animals.get(0);
        Entity animal2 = animals.get(1);

        String breedingItem = getBreedingItem(animal1);
        if (breedingItem == null) {
            return WorkerResult.failure("husbandry: unknown animal type");
        }

        if (ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player == null || InventoryDriver.countItem(c.player, breedingItem) < 2;
        })) {
            return WorkerResult.failure("husbandry: need 2 " + breedingItem + " for breeding");
        }

        int slot = findAndSelectItem(breedingItem);
        if (slot < 0) {
            return WorkerResult.failure("husbandry: no breeding item in hotbar");
        }

        CommandExecutor.sleepQuietly(200);

        boolean fed1 = interactWithEntity(animal1.getId());
        if (!fed1) {
            return WorkerResult.failure("husbandry: failed to feed first animal");
        }

        CommandExecutor.sleepQuietly(500);

        boolean fed2 = interactWithEntity(animal2.getId());
        if (fed2) {
            CommandExecutor.sendBridgeMessage("[Bridge] Husbandry: bred animals");
            return WorkerResult.success("husbandry: bred animals");
        } else {
            return WorkerResult.failure("husbandry: failed to feed second animal");
        }
    }

    private WorkerResult executeTame(String actionJson, WorkerContext ctx) {
        Entity target = findNearestTameable();
        if (target == null) {
            return WorkerResult.failure("husbandry: no tameable animal nearby");
        }

        String tamingItem = getTamingItem(target);
        if (tamingItem == null) {
            return WorkerResult.failure("husbandry: unknown tameable type");
        }

        if (ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.player == null || InventoryDriver.countItem(c.player, tamingItem) <= 0;
        })) {
            return WorkerResult.failure("husbandry: no " + tamingItem + " for taming");
        }

        int slot = findAndSelectItem(tamingItem);
        if (slot < 0) {
            return WorkerResult.failure("husbandry: no taming item in hotbar");
        }

        for (int i = 0; i < 10; i++) {
            if (ctx.isCancelled().get()) {
                return WorkerResult.failure("husbandry: cancelled");
            }

            boolean dead = ClientThread.call(() -> {
                MinecraftClient c = MinecraftClient.getInstance();
                return c.player == null || c.player.isDead() || c.player.getHealth() <= 0;
            });
            if (dead) {
                return WorkerResult.failure("husbandry: player died");
            }

            CommandExecutor.sleepQuietly(300);

            boolean success = interactWithEntity(target.getId());
            if (success) {
                CommandExecutor.sendBridgeMessage("[Bridge] Husbandry: tamed animal");
                return WorkerResult.success("husbandry: tamed animal");
            }
        }

        return WorkerResult.failure("husbandry: failed to tame after attempts");
    }

    private boolean interactWithEntity(int entityId) {
        boolean result = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            if (player == null || client.interactionManager == null || client.world == null) return false;
            Entity entity = client.world.getEntityById(entityId);
            if (entity == null) return false;
            client.interactionManager.interactEntity(player, entity, Hand.MAIN_HAND);
            return true;
        }));
        CommandExecutor.sleepQuietly(100); // Wait for server acknowledgment
        return result;
    }

    private Entity findNearestSheep() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(16),
                e -> e instanceof SheepEntity sheep && sheep.isAlive()
            ).stream().findFirst().orElse(null);
        });
    }

    private Entity findNearestCow() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(16),
                e -> e instanceof CowEntity cow && cow.isAlive()
            ).stream().findFirst().orElse(null);
        });
    }

    private Entity findNearestTameable() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return null;
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(16),
                e -> e.isAlive() && (e instanceof TameableEntity || e instanceof AbstractHorseEntity)
            ).stream().findFirst().orElse(null);
        });
    }

    private List<Entity> findNearbyAnimals() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null || c.player == null) return List.of();
            return c.world.getOtherEntities(null,
                c.player.getBoundingBox().expand(16),
                e -> e.isAlive() && e instanceof AnimalEntity
            );
        });
    }

    private String getBreedingItem(Entity entity) {
        if (entity instanceof CowEntity) return "minecraft:wheat";
        if (entity instanceof SheepEntity) return "minecraft:wheat";
        if (entity instanceof PigEntity) return "minecraft:carrot";
        if (entity instanceof ChickenEntity) return "minecraft:wheat_seeds";
        if (entity instanceof WolfEntity) return "minecraft:bone";
        if (entity instanceof CatEntity) return "minecraft:raw_cod";
        return null;
    }

    private String getTamingItem(Entity entity) {
        if (entity instanceof WolfEntity) return "minecraft:bone";
        if (entity instanceof CatEntity) return "minecraft:raw_cod";
        if (entity instanceof ParrotEntity) return "minecraft:wheat_seeds";
        if (entity instanceof AbstractHorseEntity) return "minecraft:golden_apple";
        return null;
    }

    private int findAndSelectItem(String itemId) {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player == null) return -1;
            for (int i = 0; i < 9; i++) {
                var stack = c.player.getInventory().getStack(i);
                if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(itemId)) {
                    InventoryDriver.selectSlot(i);
                    return i;
                }
            }
            return -1;
        });
    }

    @Override
    public boolean requiresThread() {
        return true;
    }
}
