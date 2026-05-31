package com.mindcraft.bridge.workers.redstone;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedstoneWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = ctx.actionType() != null ? "#" + ctx.actionType() : "#redstone";
        try {
            boolean connected = ClientThread.call(() -> {
                var c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("redstone: not connected");
            }
            
            String action = extractAction(actionJson);
            
            WorkerResult result = switch (action) {
                case "tune_repeater" -> executeTuneRepeater(actionJson, ctx);
                case "read_comparator" -> executeReadComparator(actionJson, ctx);
                case "place_redstone" -> executePlaceRedstone(actionJson, ctx);
                default -> WorkerResult.failure("redstone: unknown action " + action);
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
    
    private WorkerResult executeTuneRepeater(String actionJson, WorkerContext ctx) {
        BlockPos pos = extractPosition(actionJson);
        if (pos == null) {
            return WorkerResult.failure("redstone: missing position");
        }
        
        int delay = extractInt(actionJson, "delay", 1);
        if (delay < 1 || delay > 4) {
            return WorkerResult.failure("redstone: delay must be 1-4");
        }
        
        BlockState state = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.world == null ? null : c.world.getBlockState(pos);
        });
        
        if (!(state.getBlock() instanceof RepeaterBlock)) {
            return WorkerResult.failure("redstone: not a repeater at position");
        }
        
        int currentDelay = state.get(Properties.DELAY);
        
        int clicksNeeded = (delay - currentDelay + 4) % 4;
        
        for (int i = 0; i < clicksNeeded; i++) {
            WorldInteractor.interactBlock(pos, Direction.UP);
        }
        
        return WorkerResult.success("redstone: repeater tuned to " + delay + " ticks");
    }
    
    private WorkerResult executeReadComparator(String actionJson, WorkerContext ctx) {
        BlockPos pos = extractPosition(actionJson);
        if (pos == null) {
            return WorkerResult.failure("redstone: missing position");
        }
        
        int output = ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.world == null) return -1;
            BlockState state = c.world.getBlockState(pos);
            if (state.getBlock() instanceof ComparatorBlock) {
                return state.get(Properties.POWER);
            }
            return -1;
        });
        
        if (output < 0) {
            return WorkerResult.failure("redstone: not a comparator at position");
        }
        
        return WorkerResult.success("redstone: comparator output = " + output);
    }
    
    private WorkerResult executePlaceRedstone(String actionJson, WorkerContext ctx) {
        BlockPos pos = extractPosition(actionJson);
        if (pos == null) {
            return WorkerResult.failure("redstone: missing position");
        }
        
        String blockType = extractString(actionJson, "block", "redstone_wire");
        
        boolean success = WorldInteractor.placeBlock(pos, Direction.UP);
        
        if (success) {
            return WorkerResult.success("redstone: placed " + blockType);
        } else {
            return WorkerResult.failure("redstone: failed to place");
        }
    }
    
    private BlockPos extractPosition(String json) {
        int x = extractInt(json, "x", 0);
        int y = extractInt(json, "y", 0);
        int z = extractInt(json, "z", 0);
        return new BlockPos(x, y, z);
    }
    
    private int extractInt(String json, String key, int defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int start = json.indexOf(":", idx) + 1;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        try {
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
    
    private String extractString(String json, String key, String defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int start = json.indexOf("\"", idx + key.length() + 2) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
    
    private String extractAction(String json) {
        int idx = json.indexOf("\"action\"");
        if (idx < 0) return "";
        int start = json.indexOf("\"", idx + 8) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
    
    @Override
    public boolean requiresThread() {
        return true;
    }
}
