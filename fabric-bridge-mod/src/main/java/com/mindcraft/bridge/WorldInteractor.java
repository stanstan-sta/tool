package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WorldInteractor {
    private WorldInteractor() {}

    public static boolean openBlock(BlockPos pos) {
        return interactBlock(pos, Direction.UP);
    }

    public static boolean interactBlock(BlockPos pos, Direction direction) {
        return Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (player == null || im == null || client.world == null) return false;
            Vec3d hit = Vec3d.ofCenter(pos);
            BlockHitResult bhr = new BlockHitResult(hit, direction, pos, false);
            im.interactBlock(player, Hand.MAIN_HAND, bhr);
            return true;
        }));
    }

    public static boolean interactEntity(int entityId) {
        return Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (player == null || im == null || client.world == null) return false;
            var entity = client.world.getEntityById(entityId);
            if (entity == null) return false;
            im.interactEntity(player, entity, Hand.MAIN_HAND);
            return true;
        }));
    }

    public static boolean placeBlock(BlockPos pos, Direction direction) {
        return interactBlock(pos, direction);
    }

    public static boolean breakBlock(BlockPos pos) {
        return Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (player == null || im == null || client.world == null) return false;
            im.updateBlockBreakingProgress(pos, Direction.UP);
            return true;
        }));
    }
}
