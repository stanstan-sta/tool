package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;

final class DimensionDriver {
    private DimensionDriver() {}

    static String getCurrentDimension() {
        return ClientThread.call(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            return c.world == null ? "" : c.world.getRegistryKey().getValue().toString();
        });
    }

    static boolean waitForDimension(String dimensionId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String dim = getCurrentDimension();
            if (dimensionId.equals(dim)) return true;
            sleep(250);
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
