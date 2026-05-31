package com.mindcraft.bridge.workers.storage;

import com.mindcraft.bridge.*;
import com.mindcraft.bridge.workers.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpecialStorageWorker implements Worker {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    
    @Override
    public WorkerResult execute(String actionJson, WorkerContext ctx) {
        String command = ctx.actionType() != null ? "#" + ctx.actionType() : "#storage";
        try {
            boolean connected = ClientThread.call(() -> {
                var c = MinecraftClient.getInstance();
                return c.player != null && c.world != null;
            });
            if (!connected) {
                return WorkerResult.failure("storage: not connected");
            }
            
            String action = extractAction(actionJson);
            
            WorkerResult result = switch (action) {
                case "interact_item_frame" -> executeItemFrame(actionJson, ctx);
                case "interact_armor_stand" -> executeArmorStand(actionJson, ctx);
                case "use_jukebox" -> executeJukebox(actionJson, ctx);
                default -> WorkerResult.failure("storage: unknown action " + action);
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
    
    private WorkerResult executeItemFrame(String actionJson, WorkerContext ctx) {
        int entityId = extractInt(actionJson, "entity_id", -1);
        if (entityId < 0) {
            return WorkerResult.failure("storage: missing entity_id");
        }
        
        boolean success = WorldInteractor.interactEntity(entityId);
        
        if (success) {
            return WorkerResult.success("storage: interacted with item frame");
        } else {
            return WorkerResult.failure("storage: failed to interact with item frame");
        }
    }
    
    private WorkerResult executeArmorStand(String actionJson, WorkerContext ctx) {
        int entityId = extractInt(actionJson, "entity_id", -1);
        if (entityId < 0) {
            return WorkerResult.failure("storage: missing entity_id");
        }
        
        boolean success = WorldInteractor.interactEntity(entityId);
        
        if (success) {
            return WorkerResult.success("storage: interacted with armor stand");
        } else {
            return WorkerResult.failure("storage: failed to interact with armor stand");
        }
    }
    
    private WorkerResult executeJukebox(String actionJson, WorkerContext ctx) {
        BlockPos pos = extractPosition(actionJson);
        if (pos == null) {
            return WorkerResult.failure("storage: missing position");
        }
        
        boolean success = WorldInteractor.openBlock(pos);
        
        if (success) {
            return WorkerResult.success("storage: opened jukebox");
        } else {
            return WorkerResult.failure("storage: failed to open jukebox");
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
