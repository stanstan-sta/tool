package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ClientThread {
    private ClientThread() {}

    public static <T> T call(Callable<T> action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            try {
                return action.call();
            } catch (Exception e) {
                throw new RuntimeException("Minecraft client-thread action failed", e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(action.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Timed out waiting for Minecraft client thread", e);
        }
    }

    public static void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }
}
