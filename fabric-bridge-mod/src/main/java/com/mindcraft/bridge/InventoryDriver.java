package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

public final class InventoryDriver {
    private InventoryDriver() {}

    public static int countItem(ClientPlayerEntity player, String itemId) {
        int total = 0;
        PlayerInventory inv = player.getInventory();
        String targetStripped = ItemIds.strip(itemId);
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && ItemIds.strip(ItemIds.fromStack(stack)).equals(targetStripped)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static boolean hasItem(ClientPlayerEntity player, String itemId) {
        return countItem(player, itemId) > 0;
    }

    public static boolean selectSlot(int slot) {
        if (slot < 0 || slot > 8) return false;
        return ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity p = client.player;
            if (p == null || p.networkHandler == null) return false;
            p.getInventory().setSelectedSlot(slot);
            p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            return true;
        });
    }

    static int findBestWeaponSlot(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int bestSlot = -1;
        float bestDamage = -1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String itemId = ItemIds.fromStack(stack).toLowerCase();
            float damage = getMeleeDamage(itemId);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public static boolean equipBestWeapon(ClientPlayerEntity player) {
        int slot = findBestWeaponSlot(player);
        if (slot < 0) return false;
        if (slot == player.getInventory().getSelectedSlot()) return true;
        return selectSlot(slot);
    }

    public static int getAttackCooldownMs(ClientPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) return 250;
        String name = ItemIds.fromStack(held).toLowerCase();
        if (name.contains("sword")) return 625;
        if (name.contains("axe")) return 1000;
        if (name.contains("pickaxe")) return 833;
        if (name.contains("shovel")) return 1000;
        return 250;
    }

    private static float getMeleeDamage(String itemName) {
        if (itemName.contains("netherite_sword")) return 8.0f;
        if (itemName.contains("diamond_sword")) return 7.0f;
        if (itemName.contains("iron_sword")) return 6.0f;
        if (itemName.contains("stone_sword")) return 5.0f;
        if (itemName.contains("wooden_sword")) return 4.0f;
        if (itemName.contains("golden_sword")) return 4.0f;
        if (itemName.contains("netherite_axe")) return 10.0f;
        if (itemName.contains("diamond_axe")) return 9.0f;
        if (itemName.contains("iron_axe")) return 9.0f;
        if (itemName.contains("stone_axe")) return 9.0f;
        if (itemName.contains("wooden_axe")) return 7.0f;
        if (itemName.contains("golden_axe")) return 7.0f;
        if (itemName.contains("pickaxe")) return 3.0f;
        if (itemName.contains("shovel")) return 2.5f;
        return 1.0f;
    }
}
