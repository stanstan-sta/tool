package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EnchantmentProvider implements ItemProvider {
    private static final Set<String> ENCHANTABLE_ITEMS = Set.of(
        "minecraft:diamond_sword", "minecraft:diamond_pickaxe",
        "minecraft:diamond_axe", "minecraft:diamond_shovel",
        "minecraft:diamond_helmet", "minecraft:diamond_chestplate",
        "minecraft:diamond_leggings", "minecraft:diamond_boots",
        "minecraft:netherite_sword", "minecraft:netherite_pickaxe",
        "minecraft:netherite_axe", "minecraft:netherite_shovel",
        "minecraft:netherite_helmet", "minecraft:netherite_chestplate",
        "minecraft:netherite_leggings", "minecraft:netherite_boots",
        "minecraft:iron_sword", "minecraft:iron_pickaxe",
        "minecraft:book"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        return id.startsWith("minecraft:enchanted_") || ENCHANTABLE_ITEMS.contains(id);
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        if (id.startsWith("minecraft:enchanted_")) {
            String baseItem = id.replace("enchanted_", "");
            steps.add(new PlanStep("enchant", "{\"item\":\"" + baseItem + "\",\"target\":\"" + id + "\"}"));
        } else if (ENCHANTABLE_ITEMS.contains(id)) {
            steps.add(new PlanStep("find_enchanting_table", "{}"));
        }

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planEnchant(String item, String targetEnchantment, PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        int hasLapis = ctx.inventory().getOrDefault("minecraft:lapis_lazuli", 0);
        if (hasLapis < 3) {
            steps.add(new PlanStep("mine", "{\"target\":\"minecraft:lapis_lazuli\",\"count\":3}"));
        }

        steps.add(new PlanStep("find_enchanting_table", "{}"));
        steps.add(new PlanStep("enchant", "{\"item\":\"" + item + "\",\"enchantment\":\"" + targetEnchantment + "\"}"));

        return new ProviderPlan(true, null, steps);
    }

    public Set<String> getEnchantableItems() {
        return ENCHANTABLE_ITEMS;
    }
}
