package com.mindcraft.bridge;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

final class StructureValidator {
    private StructureValidator() {}

    record BlockMismatch(BlockPos pos, String expected, String actual) {}

    static List<BlockMismatch> compare(List<String> paletteIds, short[] blocks,
                                       int width, int height, int length,
                                       BlockPos origin) {
        return ClientThread.call(() -> {
            List<BlockMismatch> mismatches = new ArrayList<>();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return mismatches;

            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        int idx = (y * length + z) * width + x;
                        int paletteIdx = blocks[idx] & 0xFFFF;
                        if (paletteIdx < 0 || paletteIdx >= paletteIds.size()) continue;
                        String expected = paletteIds.get(paletteIdx);
                        if (expected == null) continue;
                        BlockState expectedState = BridgeSchematic.parseState(expected);
                        if (expectedState != null && expectedState.isAir()) continue;

                        mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        BlockState actualState = client.world.getBlockState(mutable);
                        if (expectedState == null || !actualState.equals(expectedState)) {
                            mismatches.add(new BlockMismatch(
                                    mutable.toImmutable(), expected,
                                    BridgeSchematic.serializeState(actualState)));
                        }
                    }
                }
            }
            return mismatches;
        });
    }
}
