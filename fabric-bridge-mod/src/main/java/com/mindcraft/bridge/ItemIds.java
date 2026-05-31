package com.mindcraft.bridge;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import java.util.Locale;

public final class ItemIds {
    private ItemIds() {}

    public static String normalize(String item) {
        String id = String.valueOf(item == null ? "" : item).trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    public static String strip(String itemId) {
        String normalized = normalize(itemId);
        return normalized.startsWith("minecraft:") ? normalized.substring(10) : normalized;
    }

    public static String fromStack(ItemStack stack) {
        if (stack.isEmpty()) return "";
        String s = stack.getItem().toString();
        if (s.startsWith("Item{") && s.endsWith("}")) {
            return s.substring(5, s.length() - 1);
        }
        return s;
    }

    public static String fromBlock(Block block) {
        String s = block.toString();
        if (s.startsWith("Block{") && s.endsWith("}")) {
            return s.substring(6, s.length() - 1);
        }
        return s;
    }
}
