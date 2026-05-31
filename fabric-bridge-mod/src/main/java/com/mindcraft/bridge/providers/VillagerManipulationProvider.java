package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class VillagerManipulationProvider implements ItemProvider {
    private static final Set<String> MANIPULATION_ITEMS = Set.of(
        "minecraft:enchanted_book",
        "minecraft:emerald_block",
        "minecraft:lectern"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return MANIPULATION_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        if (id.equals("minecraft:enchanted_book")) {
            steps.add(new PlanStep("cycle_lectern", "{\"enchantment\":\"mending\"}"));
        } else if (id.equals("minecraft:emerald_block")) {
            steps.add(new PlanStep("cure_villager", "{}"));
        } else if (id.equals("minecraft:lectern")) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:lectern\",\"count\":1}"));
        }

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planLecternCycle(String enchantment, PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        boolean hasLectern = ctx.inventory().containsKey("minecraft:lectern");
        if (!hasLectern) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:lectern\",\"count\":1}"));
        }

        steps.add(new PlanStep("place_block", "{\"block\":\"minecraft:lectern\"}"));
        steps.add(new PlanStep("cycle_trades", "{\"enchantment\":\"" + enchantment + "\"}"));

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planCureVillager(PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        boolean hasPotion = ctx.inventory().containsKey("minecraft:splash_potion_of_weakness");
        boolean hasApple = ctx.inventory().containsKey("minecraft:golden_apple");

        if (!hasPotion) {
            steps.add(new PlanStep("brew", "{\"potions\":\"splash_potion_of_weakness\",\"count\":1}"));
        }

        if (!hasApple) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:golden_apple\",\"count\":1}"));
        }

        steps.add(new PlanStep("find_entity", "{\"type\":\"zombie_villager\"}"));
        steps.add(new PlanStep("use_item", "{\"item\":\"minecraft:splash_potion_of_weakness\"}"));
        steps.add(new PlanStep("feed_entity", "{\"item\":\"minecraft:golden_apple\"}"));
        steps.add(new PlanStep("wait", "{\"duration\":300}"));

        return new ProviderPlan(true, null, steps);
    }
}
