package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;

public final class DimensionDriver {
    private DimensionDriver() {}

    public static String getCurrentDimension() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.world == null ? "" : c.world.getRegistryKey().getValue().toString();
        });
    }

    public static String normalizeDimension(String dimensionId) {
        if (dimensionId == null) return "";
        String dim = dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
        if (dim.equals("nether") || dim.equals("the_nether") || dim.equals("minecraft:nether")
                || dim.equals("minecraft:the_nether")) {
            return "minecraft:the_nether";
        }
        if (dim.equals("end") || dim.equals("the_end") || dim.equals("minecraft:end")
                || dim.equals("minecraft:the_end")) {
            return "minecraft:the_end";
        }
        if (dim.equals("overworld") || dim.equals("minecraft:overworld")) {
            return "minecraft:overworld";
        }
        return dim;
    }

    public static boolean isCurrentDimension(String dimensionId) {
        return normalizeDimension(dimensionId).equals(normalizeDimension(getCurrentDimension()));
    }

    public static boolean waitForDimension(String dimensionId, long timeoutMs) {
        String target = normalizeDimension(dimensionId);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String dim = getCurrentDimension();
            if (target.equals(normalizeDimension(dim))) return true;
            sleep(250);
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
