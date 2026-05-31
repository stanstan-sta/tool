package com.mindcraft.bridge;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

public final class WorkstationFinder {
    private WorkstationFinder() {}

    public static BlockPos findNearest(ClientWorld world, ClientPlayerEntity player,
                                String blockId, int range) {
        BlockPos playerPos = player.getBlockPos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isAir()) continue;
                    if (ItemIds.fromBlock(state.getBlock()).equals(blockId)) {
                        double distSq = mutable.getSquaredDistance(playerPos);
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq;
                            nearest = mutable.toImmutable();
                        }
                    }
                }
            }
        }
        return nearest;
    }

    public static BlockPos findNearestStation(ClientWorld world, ClientPlayerEntity player, String station) {
        return findNearest(world, player, "minecraft:" + station, 16);
    }

    public static BlockPos findNearestOnClientThread(String blockId, int range) {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player == null || c.world == null) return null;
            return findNearest(c.world, c.player, blockId, range);
        });
    }

    public static BlockPos findNearestStationOnClientThread(String station) {
        return findNearestOnClientThread("minecraft:" + station, 16);
    }
}
