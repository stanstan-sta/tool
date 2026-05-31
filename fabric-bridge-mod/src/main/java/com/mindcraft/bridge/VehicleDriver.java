package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

final class VehicleDriver {
    private VehicleDriver() {}

    static boolean dismount() {
        return ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null || !p.hasVehicle()) return false;
            p.dismountVehicle();
            return true;
        });
    }

    static boolean isMounted() {
        return ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            return p != null && p.hasVehicle();
        });
    }

    static int getVehicleEntityId() {
        return ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null || !p.hasVehicle()) return -1;
            return p.getVehicle().getId();
        });
    }
}
