package com.mindcraft.bridge.utils;

import com.mindcraft.bridge.ClientThread;
import net.minecraft.block.BlockState;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.world.ClientWorld;

public class BlockStateReader {
    
    public static int getRepeaterDelay(ClientWorld world, BlockPos pos) {
        return ClientThread.call(() -> {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof RepeaterBlock) {
                return state.get(Properties.DELAY);
            }
            return -1;
        });
    }
    
    public static int getComparatorOutput(ClientWorld world, BlockPos pos) {
        return ClientThread.call(() -> {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof ComparatorBlock) {
                return state.get(Properties.POWER);
            }
            return -1;
        });
    }
    
    public static boolean isRedstonePowered(ClientWorld world, BlockPos pos) {
        return ClientThread.call(() -> {
            return world.isReceivingRedstonePower(pos);
        });
    }
}
